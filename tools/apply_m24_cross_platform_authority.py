#!/usr/bin/env python3
from pathlib import Path
import os
import sys

PAYLOAD_FILES = [
    "app/src/main/java/com/ksp/cryptobot/execution/M24CrossPlatformAuthority.kt",
    "app/src/test/java/com/ksp/cryptobot/execution/M24CrossPlatformAuthorityPolicyTest.kt",
    "app/src/test/java/com/ksp/cryptobot/execution/M24CrossPlatformAuthorityScenarioTest.kt",
    "app/src/main/assets/cloudshare_setup/m24_cross_platform_authority_protocol.md",
]


def fail(msg: str):
    raise SystemExit("ERROR | " + msg)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)


def main():
    print("INFO | M24 applier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app tree:\n" + dirty)

    payload = Path(__file__).resolve().parent / "m24_payload"
    for rel in PAYLOAD_FILES:
        src = payload / rel
        dst = repo / rel
        if not src.exists():
            fail(f"M24 payload missing: {rel}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(src.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
        print("WRITE |", rel)

    # Extend M14 authority runtime rather than creating a second authority system.
    p = repo / "app/src/main/java/com/ksp/cryptobot/execution/EngineAuthorityLeaseManager.kt"
    t = p.read_text(encoding="utf-8")

    t = replace_once(
        t,
        '''    val engineId: String = "",
    val holderEngineId: String = "",
    val expiresAtEpochMs: Long = 0L,
''',
        '''    val engineId: String = "",
    val holderEngineId: String = "",
    val localPlatform: String = "",
    val holderPlatform: String = "",
    val expiresAtEpochMs: Long = 0L,
''',
        "M24 authority snapshot platforms"
    )

    t = replace_once(
        t,
        '''    fun publish(value: EngineAuthoritySnapshot) {
        snapshot = value
    }

    fun canSubmitNewEntry(mode: BotMode): Pair<Boolean, String> {
''',
        '''    fun publish(value: EngineAuthoritySnapshot) {
        snapshot = value
    }

    @Volatile
    private var remoteSubmissionValidator: (suspend () -> Pair<Boolean, String>)? = null

    fun installRemoteSubmissionValidator(validator: suspend () -> Pair<Boolean, String>) {
        remoteSubmissionValidator = validator
    }

    fun canSubmitNewEntry(mode: BotMode): Pair<Boolean, String> {
''',
        "M24 authoritative validator registration"
    )

    t = replace_once(
        t,
        '''        return true to "Distributed LIVE authority lease is active for engine=${s.engineId}, fence=${s.fencingToken}, remainingMs=${(s.localDeadlineElapsedMs - nowElapsed).coerceAtLeast(0L)}."
    }
}

/**
''',
        '''        return true to "Distributed LIVE authority lease is active for engine=${s.engineId}, fence=${s.fencingToken}, remainingMs=${(s.localDeadlineElapsedMs - nowElapsed).coerceAtLeast(0L)}."
    }

    suspend fun canSubmitNewLiveEntryAuthoritative(): Pair<Boolean, String> {
        val local = canSubmitNewEntry(BotMode.LIVE_AUTO)
        if (!local.first) return local
        val validator = remoteSubmissionValidator
            ?: return false to "M24 remote authority validator is unavailable; LIVE BUY is fail-closed."
        return validator()
    }
}

/**
''',
        "M24 authoritative submission gate"
    )

    t = replace_once(
        t,
        '''        const val LEASE_TTL_SECONDS = 75
        const val HEARTBEAT_SECONDS = 20L
''',
        '''        const val LEASE_TTL_SECONDS = 75
        const val HEARTBEAT_SECONDS = 20L
        const val LOCAL_PLATFORM = "ANDROID"
''',
        "M24 local platform constant"
    )

    t = replace_once(
        t,
        '''    suspend fun acquire(settings: BotSettings): EngineAuthoritySnapshot {
''',
        '''    init {
        EngineAuthorityRuntime.installRemoteSubmissionValidator { validateRemoteSubmission() }
    }

    suspend fun acquire(settings: BotSettings): EngineAuthoritySnapshot {
''',
        "M24 manager validator installation"
    )

    t = replace_once(
        t,
        '''        val response = client.acquireEngineLease(
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
''',
        '''        val acquireStartedElapsedMs = SystemClock.elapsedRealtime()
        val response = client.acquireEngineLease(
            accountKey = identity.accountKey,
            engineId = engineId,
            platform = LOCAL_PLATFORM,
            ttlSeconds = LEASE_TTL_SECONDS
        )
        val acquireRoundTripMs = (SystemClock.elapsedRealtime() - acquireStartedElapsedMs).coerceAtLeast(0L)

        val acquired = response.bool("acquired")
        val holder = response.text("holder_engine_id")
        val holderPlatform = response.text("holder_platform")
        val expires = response.long("expires_at_epoch_ms")
        val fence = response.long("fence_token")
        val schema = response.int("lease_schema_version")
        val remaining = M24CrossPlatformAuthorityPolicy.conservativeRemainingMs(
            response.long("lease_remaining_ms"),
            acquireRoundTripMs
        )
''',
        "M24 conservative cross-platform acquisition"
    )

    # All holder snapshots in acquisition/heartbeat scopes now retain platform identity.
    old_holder = '''                holderEngineId = holder,
                expiresAtEpochMs = expires,
'''
    holder_count = t.count(old_holder)
    if holder_count < 3:
        fail(f"M24 holder platform snapshots: expected at least 3 matches, got {holder_count}")
    t = t.replace(
        old_holder,
        '''                holderEngineId = holder,
                localPlatform = LOCAL_PLATFORM,
                holderPlatform = holderPlatform,
                expiresAtEpochMs = expires,
'''
    )

    t = replace_once(
        t,
        '''                val next = runCatching {
                    client.heartbeatEngineLease(
                        accountKey = accountKey,
                        engineId = engineId,
                        fenceToken = expectedFence,
                        ttlSeconds = LEASE_TTL_SECONDS
                    )
                }.fold(
''',
        '''                val heartbeatStartedElapsedMs = SystemClock.elapsedRealtime()
                val next = runCatching {
                    client.heartbeatEngineLease(
                        accountKey = accountKey,
                        engineId = engineId,
                        platform = LOCAL_PLATFORM,
                        fenceToken = expectedFence,
                        ttlSeconds = LEASE_TTL_SECONDS
                    )
                }.fold(
''',
        "M24 platform heartbeat"
    )

    t = replace_once(
        t,
        '''                        val renewed = response.bool("renewed")
                        val holder = response.text("holder_engine_id")
                        val expires = response.long("expires_at_epoch_ms")
                        val responseFence = response.long("fence_token")
                        val schema = response.int("lease_schema_version")
                        val remaining = response.long("lease_remaining_ms")
''',
        '''                        val heartbeatRoundTripMs = (SystemClock.elapsedRealtime() - heartbeatStartedElapsedMs).coerceAtLeast(0L)
                        val renewed = response.bool("renewed")
                        val holder = response.text("holder_engine_id")
                        val holderPlatform = response.text("holder_platform")
                        val expires = response.long("expires_at_epoch_ms")
                        val responseFence = response.long("fence_token")
                        val schema = response.int("lease_schema_version")
                        val remaining = M24CrossPlatformAuthorityPolicy.conservativeRemainingMs(
                            response.long("lease_remaining_ms"),
                            heartbeatRoundTripMs
                        )
''',
        "M24 conservative heartbeat"
    )

    t = replace_once(
        t,
        '''    fun stop() {
''',
        '''    private suspend fun validateRemoteSubmission(): Pair<Boolean, String> {
        val client = activeClient
            ?: return false to "M24 CloudShare authority client is unavailable; LIVE BUY is fail-closed."
        val accountKey = activeAccountKey
        val expectedFence = activeFenceToken
        if (accountKey.isBlank() || expectedFence <= 0L) {
            return false to "M24 active account/fence is unavailable; LIVE BUY is fail-closed."
        }

        val startedElapsedMs = SystemClock.elapsedRealtime()
        val response = runCatching {
            client.engineLeaseStatus(
                accountKey = accountKey,
                engineId = engineId,
                fenceToken = expectedFence
            )
        }.getOrElse { error ->
            val current = EngineAuthorityRuntime.snapshot()
            EngineAuthorityRuntime.publish(
                current.copy(
                    authorized = false,
                    state = "PARTITION_UNKNOWN",
                    reason = "M24 authoritative pre-submit status failed: ${error.message ?: error.javaClass.simpleName}"
                )
            )
            return false to "M24 authoritative CloudShare status unavailable; network partition/uncertainty blocks LIVE BUY."
        }

        val roundTripMs = (SystemClock.elapsedRealtime() - startedElapsedMs).coerceAtLeast(0L)
        val holder = response.text("holder_engine_id")
        val holderPlatform = response.text("holder_platform")
        val responseFence = response.long("fence_token")
        val schema = response.int("lease_schema_version")
        val expires = response.long("expires_at_epoch_ms")
        val remaining = M24CrossPlatformAuthorityPolicy.conservativeRemainingMs(
            response.long("lease_remaining_ms"),
            roundTripMs
        )
        val valid = M24CrossPlatformAuthorityPolicy.remoteSubmissionValid(
            remoteReachable = true,
            owned = response.bool("owned"),
            holderMatches = holder == engineId,
            holderPlatform = holderPlatform,
            expectedPlatform = M24AuthorityPlatform.ANDROID,
            schemaVersion = schema,
            expectedFence = expectedFence,
            responseFence = responseFence,
            conservativeRemainingMs = remaining
        )

        val nowElapsed = SystemClock.elapsedRealtime()
        val current = EngineAuthorityRuntime.snapshot()
        if (!valid) {
            EngineAuthorityRuntime.publish(
                current.copy(
                    authorized = false,
                    state = "FENCED_OR_TRANSFERRED",
                    holderEngineId = holder,
                    localPlatform = LOCAL_PLATFORM,
                    holderPlatform = holderPlatform,
                    expiresAtEpochMs = expires,
                    fencingToken = responseFence,
                    leaseSchemaVersion = schema,
                    leaseRemainingMs = remaining,
                    localDeadlineElapsedMs = nowElapsed + remaining,
                    reason = "M24 authoritative pre-submit status rejected local owner/platform/fence."
                )
            )
            return false to "M24 authoritative owner/fence check rejected LIVE BUY. holder=$holder platform=$holderPlatform fence=$responseFence expectedFence=$expectedFence."
        }

        EngineAuthorityRuntime.publish(
            current.copy(
                authorized = true,
                state = "HELD",
                holderEngineId = holder,
                localPlatform = LOCAL_PLATFORM,
                holderPlatform = holderPlatform,
                expiresAtEpochMs = expires,
                fencingToken = responseFence,
                leaseSchemaVersion = schema,
                leaseRemainingMs = remaining,
                localDeadlineElapsedMs = nowElapsed + remaining,
                reason = "M24 authoritative pre-submit validation confirmed ANDROID owner and fence=$responseFence."
            )
        )
        return true to "M24 authoritative CloudShare status confirmed engine=$engineId platform=$holderPlatform fence=$responseFence."
    }

    suspend fun transferAuthority(target: M24AuthorityTransferTarget): EngineAuthoritySnapshot {
        val client = activeClient
        val accountKey = activeAccountKey
        val oldFence = activeFenceToken
        val current = EngineAuthorityRuntime.snapshot()
        if (client == null || accountKey.isBlank() || oldFence <= 0L || !current.authorized) {
            val denied = current.copy(
                authorized = false,
                state = "TRANSFER_BLOCKED",
                reason = "M24 transfer requires the currently authorized local LIVE owner."
            )
            EngineAuthorityRuntime.publish(denied)
            return denied
        }

        val startedElapsedMs = SystemClock.elapsedRealtime()
        val response = runCatching {
            client.transferEngineLease(
                accountKey = accountKey,
                engineId = engineId,
                fenceToken = oldFence,
                targetClientId = target.clientId,
                targetEngineId = target.engineId,
                targetPlatform = target.platform.wireName,
                ttlSeconds = LEASE_TTL_SECONDS
            )
        }.getOrElse { error ->
            val denied = current.copy(
                authorized = false,
                state = "TRANSFER_UNKNOWN",
                reason = "M24 authority transfer failed/unknown: ${error.message ?: error.javaClass.simpleName}"
            )
            EngineAuthorityRuntime.publish(denied)
            return denied
        }

        val roundTripMs = (SystemClock.elapsedRealtime() - startedElapsedMs).coerceAtLeast(0L)
        val newFence = response.long("fence_token")
        val holder = response.text("holder_engine_id")
        val holderPlatform = response.text("holder_platform")
        val remaining = M24CrossPlatformAuthorityPolicy.conservativeRemainingMs(
            response.long("lease_remaining_ms"),
            roundTripMs
        )
        val accepted = M24CrossPlatformAuthorityPolicy.transferAccepted(
            transferred = response.bool("transferred"),
            oldFence = oldFence,
            newFence = newFence,
            targetEngineId = target.engineId,
            responseHolderEngineId = holder,
            targetPlatform = target.platform,
            responseHolderPlatform = holderPlatform,
            conservativeRemainingMs = remaining
        )

        if (!accepted) {
            val denied = current.copy(
                authorized = false,
                state = "TRANSFER_REJECTED",
                holderEngineId = holder,
                holderPlatform = holderPlatform,
                fencingToken = newFence,
                leaseRemainingMs = remaining,
                reason = "M24 transfer response did not prove an atomic newer fencing epoch."
            )
            EngineAuthorityRuntime.publish(denied)
            return denied
        }

        heartbeatJob?.cancel()
        heartbeatJob = null
        activeClient = null
        activeAccountKey = ""
        activeFenceToken = 0L
        val transferred = current.copy(
            authorized = false,
            state = "TRANSFERRED",
            holderEngineId = holder,
            localPlatform = LOCAL_PLATFORM,
            holderPlatform = holderPlatform,
            fencingToken = newFence,
            leaseRemainingMs = remaining,
            localDeadlineElapsedMs = 0L,
            reason = "M24 LIVE authority atomically transferred to ${target.platform.wireName} engine=${target.engineId}; local Android is dashboard-only."
        )
        EngineAuthorityRuntime.publish(transferred)
        return transferred
    }

    fun stop() {
''',
        "M24 authoritative status and transfer manager"
    )

    # PAPER/STOPPED snapshots should identify the local host without claiming a remote holder.
    t = t.replace(
        '''                engineId = engineId,
                reason = "PAPER has no live execution authority."
''',
        '''                engineId = engineId,
                localPlatform = LOCAL_PLATFORM,
                reason = "PAPER has no live execution authority."
'''
    )
    t = t.replace(
        '''                state = "STOPPED",
                engineId = engineId,
                reason = "Trading host stopped; LIVE entry authority released/expiring."
''',
        '''                state = "STOPPED",
                engineId = engineId,
                localPlatform = LOCAL_PLATFORM,
                reason = "Trading host stopped; LIVE entry authority released/expiring."
'''
    )
    t = t.replace(
        '''            engineId = engineId,
            reason = reason
''',
        '''            engineId = engineId,
            localPlatform = LOCAL_PLATFORM,
            reason = reason
'''
    )

    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # Cross-platform CloudShare client contract. Heartbeats must retain platform identity.
    p = repo / "app/src/main/java/com/ksp/cryptobot/cloudshare/CloudShareClient.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''    suspend fun heartbeatEngineLease(
        accountKey: String,
        engineId: String,
        fenceToken: Long,
        ttlSeconds: Int
''',
        '''    suspend fun heartbeatEngineLease(
        accountKey: String,
        engineId: String,
        platform: String,
        fenceToken: Long,
        ttlSeconds: Int
''',
        "M24 heartbeat platform parameter"
    )
    t = replace_once(
        t,
        '''            "account_key" to accountKey,
            "engine_id" to engineId,
            "fence_token" to fenceToken,
            "ttl_seconds" to ttlSeconds.coerceIn(30, 300)
        )
    )

    suspend fun releaseEngineLease(
''',
        '''            "account_key" to accountKey,
            "engine_id" to engineId,
            "platform" to platform,
            "fence_token" to fenceToken,
            "ttl_seconds" to ttlSeconds.coerceIn(30, 300)
        )
    )

    suspend fun releaseEngineLease(
''',
        "M24 heartbeat sends platform"
    )

    t = replace_once(
        t,
        '''    suspend fun engineLeaseStatus(
''',
        '''    suspend fun transferEngineLease(
        accountKey: String,
        engineId: String,
        fenceToken: Long,
        targetClientId: String,
        targetEngineId: String,
        targetPlatform: String,
        ttlSeconds: Int
    ): Map<String, Any?> = requestMap(
        "POST",
        "/v1/engine-lease/transfer",
        body = mapOf(
            "account_key" to accountKey,
            "engine_id" to engineId,
            "fence_token" to fenceToken,
            "target_client_id" to targetClientId,
            "target_engine_id" to targetEngineId,
            "target_platform" to targetPlatform,
            "ttl_seconds" to ttlSeconds.coerceIn(30, 300)
        )
    )

    suspend fun engineLeaseStatus(
''',
        "M24 transfer client route"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # CloudShare Worker: platform validation, monotonic release, atomic transfer.
    p = repo / "app/src/main/assets/cloudshare_setup/cloudshare-worker.js"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        'const ENGINE_LEASE_SCHEMA_VERSION = 2;\n',
        'const ENGINE_LEASE_SCHEMA_VERSION = 2;\nconst ENGINE_AUTHORITY_PLATFORMS = new Set(["ANDROID", "WINDOWS"]);\n',
        "M24 supported platforms"
    )
    t = replace_once(
        t,
        '  const platform = String(body.platform || "").trim().slice(0, 40);\n',
        '  const platform = String(body.platform || "").trim().toUpperCase().slice(0, 40);\n',
        "M24 normalized platform"
    )
    t = replace_once(
        t,
        '''  if (path === "/v1/engine-lease/acquire") {
    await env.DB.prepare(
''',
        '''  if (path === "/v1/engine-lease/acquire") {
    if (!ENGINE_AUTHORITY_PLATFORMS.has(platform)) {
      return json({ error: "platform must be ANDROID or WINDOWS" }, 400);
    }
    await env.DB.prepare(
''',
        "M24 acquire platform validation"
    )
    t = replace_once(
        t,
        '''  if (path === "/v1/engine-lease/heartbeat") {
    if (fenceToken <= 0) return json({ error: "positive fence_token required" }, 400);
''',
        '''  if (path === "/v1/engine-lease/heartbeat") {
    if (fenceToken <= 0) return json({ error: "positive fence_token required" }, 400);
    if (!ENGINE_AUTHORITY_PLATFORMS.has(platform)) {
      return json({ error: "platform must be ANDROID or WINDOWS" }, 400);
    }
''',
        "M24 heartbeat platform validation"
    )

    old_release = '''  if (path === "/v1/engine-lease/release") {
    if (fenceToken <= 0) return json({ error: "positive fence_token required" }, 400);
    const result = await env.DB.prepare(
      `DELETE FROM engine_leases
        WHERE account_key=?
          AND holder_client_id=?
          AND holder_engine_id=?
          AND fence_token=?
          AND schema_version=?`
    ).bind(
      accountKey, client.client_id, engineId, fenceToken,
      ENGINE_LEASE_SCHEMA_VERSION
    ).run();
    return json({
      released: Number(result?.meta?.changes || 0) > 0,
      fence_token: fenceToken,
      lease_schema_version: ENGINE_LEASE_SCHEMA_VERSION,
      server_now_epoch_ms: now
    });
  }

'''
    new_release = '''  if (path === "/v1/engine-lease/release") {
    if (fenceToken <= 0) return json({ error: "positive fence_token required" }, 400);
    // M24 never deletes the row: preserving fence_token prevents a later owner
    // from restarting at fencing epoch 1 after a clean release.
    const result = await env.DB.prepare(
      `UPDATE engine_leases
          SET expires_at_epoch_ms=?, updated_at=?
        WHERE account_key=?
          AND holder_client_id=?
          AND holder_engine_id=?
          AND fence_token=?
          AND schema_version=?
          AND expires_at_epoch_ms > ?`
    ).bind(
      now, updated,
      accountKey, client.client_id, engineId, fenceToken,
      ENGINE_LEASE_SCHEMA_VERSION, now
    ).run();
    return json({
      released: Number(result?.meta?.changes || 0) > 0,
      fence_token: fenceToken,
      lease_schema_version: ENGINE_LEASE_SCHEMA_VERSION,
      server_now_epoch_ms: now
    });
  }

'''
    t = replace_once(t, old_release, new_release, "M24 monotonic release")

    transfer = '''  if (path === "/v1/engine-lease/transfer") {
    if (fenceToken <= 0) return json({ error: "positive fence_token required" }, 400);
    const targetClientId = String(body.target_client_id || "").trim().slice(0, 120);
    const targetEngineId = String(body.target_engine_id || "").trim().slice(0, 120);
    const targetPlatform = String(body.target_platform || "").trim().toUpperCase().slice(0, 40);
    if (!targetClientId || !targetEngineId || !ENGINE_AUTHORITY_PLATFORMS.has(targetPlatform)) {
      return json({ error: "valid target_client_id, target_engine_id and target_platform required" }, 400);
    }

    const result = await env.DB.prepare(
      `UPDATE engine_leases
          SET holder_client_id=?, holder_engine_id=?, platform=?,
              expires_at_epoch_ms=?, updated_at=?, fence_token=fence_token + 1
        WHERE account_key=?
          AND holder_client_id=?
          AND holder_engine_id=?
          AND fence_token=?
          AND schema_version=?
          AND expires_at_epoch_ms > ?
          AND EXISTS (SELECT 1 FROM clients WHERE client_id=? AND enabled=1)`
    ).bind(
      targetClientId, targetEngineId, targetPlatform,
      expires, updated,
      accountKey, client.client_id, engineId, fenceToken,
      ENGINE_LEASE_SCHEMA_VERSION, now,
      targetClientId
    ).run();

    const row = await readLease();
    const transferred = Number(result?.meta?.changes || 0) > 0 &&
      row?.holder_client_id === targetClientId &&
      row?.holder_engine_id === targetEngineId &&
      row?.platform === targetPlatform &&
      Number(row?.fence_token || 0) > fenceToken &&
      Number(row?.expires_at_epoch_ms || 0) > now;
    return json(wire(row, { transferred }));
  }

'''
    t = replace_once(
        t,
        '  if (path === "/v1/engine-lease/status") {\n',
        transfer + '  if (path === "/v1/engine-lease/status") {\n',
        "M24 atomic cross-platform transfer"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # Kraken final AddOrder boundary: remote owner/fence status immediately before network submission.
    p = repo / "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        'import com.ksp.cryptobot.observability.M23RemoteOperationsRuntime\n',
        'import com.ksp.cryptobot.observability.M23RemoteOperationsRuntime\nimport com.ksp.cryptobot.execution.EngineAuthorityRuntime\n',
        "M24 exchange authority import"
    )
    t = replace_once(
        t,
        '''        KrakenPrivateExecutionRegistry.markSubmissionPending(
            clientOrderId = krakenClientOrderId,
''',
        '''        if (request.side == OrderSide.BUY) {
            val m24Authority = EngineAuthorityRuntime.canSubmitNewLiveEntryAuthoritative()
            if (!m24Authority.first) {
                error("M24 cross-platform authority gate blocks BUY at Kraken AddOrder boundary: ${m24Authority.second}")
            }
        }
        KrakenPrivateExecutionRegistry.markSubmissionPending(
            clientOrderId = krakenClientOrderId,
''',
        "M24 final Kraken BUY authority gate"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # M23 diagnostics should show platform ownership explicitly, but never raw CloudShare credentials.
    p = repo / "app/src/main/java/com/ksp/cryptobot/observability/M23Observability.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''            "engine_id_fingerprint" to M23Redaction.fingerprint(authority.engineId),
            "holder_id_fingerprint" to M23Redaction.fingerprint(authority.holderEngineId),
            "fencing_token" to authority.fencingToken,
''',
        '''            "engine_id_fingerprint" to M23Redaction.fingerprint(authority.engineId),
            "holder_id_fingerprint" to M23Redaction.fingerprint(authority.holderEngineId),
            "local_platform" to authority.localPlatform.ifBlank { "UNKNOWN" },
            "holder_platform" to authority.holderPlatform.ifBlank { "UNKNOWN" },
            "fencing_token" to authority.fencingToken,
''',
        "M24 authority diagnostics platforms"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    print("PASS | M24 controlled payload applied.")


if __name__ == "__main__":
    main()
