package com.ksp.cryptobot.execution

import android.content.Context
import android.os.SystemClock
import com.ksp.cryptobot.cloudshare.CloudShareClient
import com.ksp.cryptobot.cloudshare.CloudShareSettingsStore
import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.ExchangeProvider
import com.ksp.cryptobot.exchange.KrakenSpotClient
import com.ksp.cryptobot.settings.AppSettingsStore
import kotlinx.coroutines.*
import java.util.UUID

data class EngineAuthoritySnapshot(
    val authorized: Boolean,
    val state: String,
    val accountKey: String = "",
    val engineId: String = "",
    val holderEngineId: String = "",
    val expiresAtEpochMs: Long = 0L,
    val fencingToken: Long = 0L,
    val leaseSchemaVersion: Int = 0,
    val leaseRemainingMs: Long = 0L,
    val localDeadlineElapsedMs: Long = 0L,
    val reason: String = ""
)

object EngineAuthorityFencePolicy {
    const val LEASE_SCHEMA_VERSION = 2

    fun acquisitionValid(
        acquired: Boolean,
        holderMatches: Boolean,
        schemaVersion: Int,
        fencingToken: Long,
        remainingMs: Long
    ): Boolean =
        acquired &&
            holderMatches &&
            schemaVersion == LEASE_SCHEMA_VERSION &&
            fencingToken > 0L &&
            remainingMs > 0L

    fun heartbeatValid(
        renewed: Boolean,
        holderMatches: Boolean,
        schemaVersion: Int,
        expectedToken: Long,
        responseToken: Long,
        remainingMs: Long
    ): Boolean =
        renewed &&
            holderMatches &&
            schemaVersion == LEASE_SCHEMA_VERSION &&
            expectedToken > 0L &&
            responseToken == expectedToken &&
            remainingMs > 0L

    fun runtimeLeaseValid(
        schemaVersion: Int,
        fencingToken: Long,
        localDeadlineElapsedMs: Long,
        nowElapsedMs: Long
    ): Boolean =
        schemaVersion == LEASE_SCHEMA_VERSION &&
            fencingToken > 0L &&
            localDeadlineElapsedMs > nowElapsedMs
}

object EngineAuthorityPolicy {
    fun entryAuthorized(mode: BotMode, distributedLeaseAuthorized: Boolean): Boolean =
        mode == BotMode.PAPER || distributedLeaseAuthorized
}

object EngineAuthorityRuntime {
    @Volatile
    private var snapshot = EngineAuthoritySnapshot(
        authorized = false,
        state = "UNINITIALIZED",
        reason = "Distributed LIVE engine authority has not been acquired."
    )

    fun snapshot(): EngineAuthoritySnapshot = snapshot

    fun publish(value: EngineAuthoritySnapshot) {
        snapshot = value
    }

    fun canSubmitNewEntry(mode: BotMode): Pair<Boolean, String> {
        if (mode == BotMode.PAPER) return true to "PAPER does not require a distributed LIVE authority lease."
        val s = snapshot
        if (!s.authorized) {
            return false to "Distributed LIVE authority is ${s.state}: ${s.reason}"
        }
        val nowElapsed = SystemClock.elapsedRealtime()
        if (!EngineAuthorityFencePolicy.runtimeLeaseValid(
                schemaVersion = s.leaseSchemaVersion,
                fencingToken = s.fencingToken,
                localDeadlineElapsedMs = s.localDeadlineElapsedMs,
                nowElapsedMs = nowElapsed
            )
        ) {
            return false to "Distributed LIVE authority lease is stale/fenced locally. token=${s.fencingToken}, schema=${s.leaseSchemaVersion}, deadlineElapsed=${s.localDeadlineElapsedMs}, nowElapsed=$nowElapsed."
        }
        return true to "Distributed LIVE authority lease is active for engine=${s.engineId}, fence=${s.fencingToken}, remainingMs=${(s.localDeadlineElapsedMs - nowElapsed).coerceAtLeast(0L)}."
    }
}

/**
 * Cross-device authority lease backed by the user's CloudShare Worker/D1.
 *
 * M14 upgrades M12 ownership to a fencing-token lease. A new ownership epoch receives
 * a monotonically increasing token. Heartbeats/releases must present the same token,
 * so a stale engine cannot mutate a newer lease. Android converts the Worker's
 * server-computed remaining lease time into an elapsedRealtime deadline, avoiding
 * device wall-clock drift.
 */
class EngineAuthorityLeaseManager(context: Context) {
    companion object {
        const val LEASE_TTL_SECONDS = 75
        const val HEARTBEAT_SECONDS = 20L
    }

    private val appContext = context.applicationContext
    private val cloudStore = CloudShareSettingsStore(appContext)
    private val settingsStore = AppSettingsStore(appContext)
    private val prefs = appContext.getSharedPreferences("cts_engine_authority", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var heartbeatJob: Job? = null
    @Volatile private var activeClient: CloudShareClient? = null
    @Volatile private var activeAccountKey: String = ""
    @Volatile private var activeFenceToken: Long = 0L

    private val engineId: String = prefs.getString("engine_id", "").orEmpty().ifBlank {
        UUID.randomUUID().toString().also {
            prefs.edit().putString("engine_id", it).commit()
        }
    }

    suspend fun acquire(settings: BotSettings): EngineAuthoritySnapshot {
        if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) {
            val paper = EngineAuthoritySnapshot(true, "PAPER",
                engineId = engineId,
                reason = "PAPER has no live execution authority."
            )
            EngineAuthorityRuntime.publish(paper)
            return paper
        }
        if (settings.exchangeProvider != ExchangeProvider.KRAKEN) {
            return blocked("UNSUPPORTED_PROVIDER", "Distributed LIVE authority is currently implemented for Kraken only.")
        }
        if (!cloudStore.enabled || cloudStore.apiUrl.isBlank()) {
            return blocked("CLOUDSHARE_REQUIRED", "CloudShare must be enabled because a local-only mutex cannot prevent Android and Windows from trading the same account.")
        }
        val credentials = cloudStore.credentials()
            ?: return blocked("CLOUDSHARE_UNREGISTERED", "This engine has no registered CloudShare client credentials.")

        val key = settingsStore.exchangeApiKey(ExchangeProvider.KRAKEN).orEmpty()
        val secret = settingsStore.exchangeSecretKey(ExchangeProvider.KRAKEN).orEmpty()
        if (key.isBlank() || secret.isBlank()) {
            return blocked("KRAKEN_CREDENTIALS_MISSING", "Kraken credentials are required to derive the account authority identity.")
        }

        val identity = KrakenSpotClient(key, secret).accountAuthorityIdentity()
        val client = CloudShareClient(cloudStore.apiUrl, credentials = credentials)

        val health = runCatching { client.health() }.getOrElse { error ->
            return blocked(
                "LEASE_SCHEMA_UPGRADE_REQUIRED",
                "CloudShare M14 lease health check failed. Deploy the M14 Worker and apply the one-time D1 fencing migration before LIVE. ${error.message ?: error.javaClass.simpleName}"
            )
        }
        val remoteSchema = health["engine_lease_schema_version"]?.toString()?.toDoubleOrNull()?.toInt() ?: 0
        if (remoteSchema != EngineAuthorityFencePolicy.LEASE_SCHEMA_VERSION) {
            return blocked(
                "LEASE_SCHEMA_UPGRADE_REQUIRED",
                "CloudShare engine lease schema=$remoteSchema, required=${EngineAuthorityFencePolicy.LEASE_SCHEMA_VERSION}. Deploy the M14 Worker and apply the one-time D1 fencing migration before LIVE."
            )
        }

        val response = client.acquireEngineLease(
            accountKey = identity.accountKey,
            engineId = engineId,
            platform = "ANDROID",
            ttlSeconds = LEASE_TTL_SECONDS
        )

        val acquired = response.bool("acquired")
        val holder = response.text("holder_engine_id")
        val expires = response.long("expires_at_epoch_ms")
        val fence = response.long("fence_token")
        val schema = response.int("lease_schema_version")
        val remaining = response.long("lease_remaining_ms")
        val valid = EngineAuthorityFencePolicy.acquisitionValid(
            acquired = acquired,
            holderMatches = holder == engineId,
            schemaVersion = schema,
            fencingToken = fence,
            remainingMs = remaining
        )

        val snapshot = if (valid) {
            EngineAuthoritySnapshot(
                authorized = true,
                state = "HELD",
                accountKey = identity.accountKey,
                engineId = engineId,
                holderEngineId = holder,
                expiresAtEpochMs = expires,
                fencingToken = fence,
                leaseSchemaVersion = schema,
                leaseRemainingMs = remaining,
                localDeadlineElapsedMs = SystemClock.elapsedRealtime() + remaining,
                reason = "Atomic CloudShare/D1 authority acquired with fencing token=$fence using ${identity.source} account-stable identity."
            )
        } else {
            EngineAuthoritySnapshot(
                authorized = false,
                state = if (acquired) "INVALID_FENCE" else "HELD_BY_OTHER_ENGINE",
                accountKey = identity.accountKey,
                engineId = engineId,
                holderEngineId = holder,
                expiresAtEpochMs = expires,
                fencingToken = fence,
                leaseSchemaVersion = schema,
                leaseRemainingMs = remaining,
                reason = if (acquired) {
                    "CloudShare returned an invalid/stale lease fencing contract."
                } else {
                    "Another registered engine owns the LIVE authority lease."
                }
            )
        }

        EngineAuthorityRuntime.publish(snapshot)
        if (snapshot.authorized) {
            activeClient = client
            activeAccountKey = identity.accountKey
            activeFenceToken = snapshot.fencingToken
            startHeartbeat()
        }
        return snapshot
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_SECONDS * 1000L)
                val client = activeClient ?: break
                val accountKey = activeAccountKey
                val expectedFence = activeFenceToken
                if (accountKey.isBlank() || expectedFence <= 0L) break

                val next = runCatching {
                    client.heartbeatEngineLease(
                        accountKey = accountKey,
                        engineId = engineId,
                        fenceToken = expectedFence,
                        ttlSeconds = LEASE_TTL_SECONDS
                    )
                }.fold(
                    onSuccess = { response ->
                        val renewed = response.bool("renewed")
                        val holder = response.text("holder_engine_id")
                        val expires = response.long("expires_at_epoch_ms")
                        val responseFence = response.long("fence_token")
                        val schema = response.int("lease_schema_version")
                        val remaining = response.long("lease_remaining_ms")
                        val valid = EngineAuthorityFencePolicy.heartbeatValid(
                            renewed = renewed,
                            holderMatches = holder == engineId,
                            schemaVersion = schema,
                            expectedToken = expectedFence,
                            responseToken = responseFence,
                            remainingMs = remaining
                        )

                        if (valid) {
                            EngineAuthoritySnapshot(
                                authorized = true,
                                state = "HELD",
                                accountKey = accountKey,
                                engineId = engineId,
                                holderEngineId = holder,
                                expiresAtEpochMs = expires,
                                fencingToken = responseFence,
                                leaseSchemaVersion = schema,
                                leaseRemainingMs = remaining,
                                localDeadlineElapsedMs = SystemClock.elapsedRealtime() + remaining,
                                reason = "Distributed authority heartbeat renewed with unchanged fence=$responseFence."
                            )
                        } else {
                            EngineAuthoritySnapshot(false, "LOST",
                                accountKey = accountKey,
                                engineId = engineId,
                                holderEngineId = holder,
                                expiresAtEpochMs = expires,
                                fencingToken = responseFence,
                                leaseSchemaVersion = schema,
                                leaseRemainingMs = remaining,
                                reason = "Authority heartbeat rejected, expired, schema-mismatched, or fencing token changed."
                            )
                        }
                    },
                    onFailure = { error ->
                        EngineAuthoritySnapshot(false, "UNKNOWN",
                            accountKey = accountKey,
                            engineId = engineId,
                            fencingToken = expectedFence,
                            leaseSchemaVersion = EngineAuthorityFencePolicy.LEASE_SCHEMA_VERSION,
                            reason = "Authority heartbeat failed: ${error.message ?: error.javaClass.simpleName}"
                        )
                    }
                )

                EngineAuthorityRuntime.publish(next)
                if (!next.authorized) {
                    activeClient = null
                    activeAccountKey = ""
                    activeFenceToken = 0L
                    runCatching { acquire(settingsStore.load()) }
                }
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        val client = activeClient
        val accountKey = activeAccountKey
        val fenceToken = activeFenceToken
        activeClient = null
        activeAccountKey = ""
        activeFenceToken = 0L

        EngineAuthorityRuntime.publish(
            EngineAuthoritySnapshot(
                authorized = false,
                state = "STOPPED",
                engineId = engineId,
                reason = "Trading host stopped; LIVE entry authority released/expiring."
            )
        )

        if (client != null && accountKey.isNotBlank() && fenceToken > 0L) {
            scope.launch {
                runCatching {
                    client.releaseEngineLease(
                        accountKey = accountKey,
                        engineId = engineId,
                        fenceToken = fenceToken
                    )
                }
            }
        }
    }

    private fun blocked(state: String, reason: String): EngineAuthoritySnapshot {
        activeClient = null
        activeAccountKey = ""
        activeFenceToken = 0L
        val snapshot = EngineAuthoritySnapshot(
            authorized = false,
            state = state,
            engineId = engineId,
            reason = reason
        )
        EngineAuthorityRuntime.publish(snapshot)
        return snapshot
    }

    private fun Map<String, Any?>.text(key: String): String =
        this[key]?.toString().orEmpty()

    private fun Map<String, Any?>.long(key: String): Long =
        this[key]?.toString()?.toDoubleOrNull()?.toLong() ?: 0L

    private fun Map<String, Any?>.int(key: String): Int =
        this[key]?.toString()?.toDoubleOrNull()?.toInt() ?: 0

    private fun Map<String, Any?>.bool(key: String): Boolean =
        when (val value = this[key]) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            else -> value?.toString()?.equals("true", ignoreCase = true) == true
        }
}
