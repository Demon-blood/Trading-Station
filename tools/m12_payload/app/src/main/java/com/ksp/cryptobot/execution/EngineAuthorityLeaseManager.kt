
package com.ksp.cryptobot.execution

import android.content.Context
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
    val reason: String = ""
)

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
        return if (s.authorized) {
            true to "Distributed LIVE authority lease is active for engine=${s.engineId}."
        } else {
            false to "Distributed LIVE authority is ${s.state}: ${s.reason}"
        }
    }
}

/**
 * Cross-device authority lease backed by the user's CloudShare Worker/D1.
 *
 * LIVE modes intentionally fail closed if CloudShare lease infrastructure is missing
 * or stale. The D1 lease endpoint performs atomic conditional acquisition, preventing
 * two registered engines from owning the same Kraken account authority key.
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
    private val engineId: String = prefs.getString("engine_id", "").orEmpty().ifBlank {
        UUID.randomUUID().toString().also {
            // commit makes the engine identity durable before a distributed lease can be acquired.
            prefs.edit().putString("engine_id", it).commit()
        }
    }

    suspend fun acquire(settings: BotSettings): EngineAuthoritySnapshot {
        if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) {
            val paper = EngineAuthoritySnapshot(true, "PAPER", engineId = engineId, reason = "PAPER has no live execution authority.")
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
        val response = client.acquireEngineLease(
            accountKey = identity.accountKey,
            engineId = engineId,
            platform = "ANDROID",
            ttlSeconds = LEASE_TTL_SECONDS
        )
        val acquired = response["acquired"]?.toString()?.equals("true", true) == true
        val holder = response["holder_engine_id"]?.toString().orEmpty()
        val expires = response["expires_at_epoch_ms"]?.toString()?.toDoubleOrNull()?.toLong() ?: 0L

        val snapshot = if (acquired && holder == engineId) {
            EngineAuthoritySnapshot(
                authorized = true,
                state = "HELD",
                accountKey = identity.accountKey,
                engineId = engineId,
                holderEngineId = holder,
                expiresAtEpochMs = expires,
                reason = "Atomic CloudShare/D1 authority acquired using ${identity.source} account-stable identity."
            )
        } else {
            EngineAuthoritySnapshot(
                authorized = false,
                state = "HELD_BY_OTHER_ENGINE",
                accountKey = identity.accountKey,
                engineId = engineId,
                holderEngineId = holder,
                expiresAtEpochMs = expires,
                reason = "Another registered engine owns the LIVE authority lease."
            )
        }
        EngineAuthorityRuntime.publish(snapshot)
        if (snapshot.authorized) {
            activeClient = client
            activeAccountKey = identity.accountKey
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
                if (accountKey.isBlank()) break
                val next = runCatching {
                    client.heartbeatEngineLease(accountKey, engineId, LEASE_TTL_SECONDS)
                }.fold(
                    onSuccess = { response ->
                        val renewed = response["renewed"]?.toString()?.equals("true", true) == true
                        val holder = response["holder_engine_id"]?.toString().orEmpty()
                        val expires = response["expires_at_epoch_ms"]?.toString()?.toDoubleOrNull()?.toLong() ?: 0L
                        if (renewed && holder == engineId) {
                            EngineAuthoritySnapshot(true, "HELD", accountKey, engineId, holder, expires, "Distributed authority heartbeat renewed.")
                        } else {
                            EngineAuthoritySnapshot(false, "LOST", accountKey, engineId, holder, expires, "Distributed authority heartbeat was rejected or lease ownership changed.")
                        }
                    },
                    onFailure = { error ->
                        EngineAuthoritySnapshot(false, "UNKNOWN", accountKey, engineId, reason = "Authority heartbeat failed: ${error.message ?: error.javaClass.simpleName}")
                    }
                )
                EngineAuthorityRuntime.publish(next)
                if (!next.authorized) {
                    // Keep retrying acquisition. New entries remain fail-closed meanwhile.
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
        activeClient = null
        activeAccountKey = ""
        EngineAuthorityRuntime.publish(
            EngineAuthoritySnapshot(false, "STOPPED", engineId = engineId, reason = "Trading host stopped; LIVE entry authority released/expiring.")
        )
        if (client != null && accountKey.isNotBlank()) {
            scope.launch {
                runCatching { client.releaseEngineLease(accountKey, engineId) }
            }
        }
    }

    private fun blocked(state: String, reason: String): EngineAuthoritySnapshot {
        val snapshot = EngineAuthoritySnapshot(false, state, engineId = engineId, reason = reason)
        EngineAuthorityRuntime.publish(snapshot)
        return snapshot
    }
}
