#!/usr/bin/env python3
from pathlib import Path
import os, sys

PAYLOAD_FILES = [
    "app/src/main/java/com/ksp/cryptobot/execution/EngineAuthorityLeaseManager.kt",
    "app/src/main/java/com/ksp/cryptobot/execution/KrakenDmsSafetyManager.kt",
    "app/src/main/assets/cloudshare_setup/m14_engine_lease_v2_migration.sql",
    "app/src/test/java/com/ksp/cryptobot/execution/EngineAuthorityFencingPolicyTest.kt",
    "app/src/test/java/com/ksp/cryptobot/execution/KrakenDmsSafetyPolicyTest.kt",
]

def fail(msg):
    raise SystemExit("ERROR | " + msg)

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

def replace_scope(text, start_marker, end_marker, replacement, label):
    start = text.find(start_marker)
    if start < 0:
        fail(f"{label}: start marker missing")
    end = text.find(end_marker, start + len(start_marker))
    if end < 0:
        fail(f"{label}: end marker missing")
    return text[:start] + replacement + text[end:]

def main():
    print("INFO | M14 applier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app tree:\n" + dirty)

    payload = Path(__file__).resolve().parent / "m14_payload"
    for rel in PAYLOAD_FILES:
        src = payload / rel
        dst = repo / rel
        if not src.exists():
            fail(f"M14 payload missing: {rel}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(src.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
        if dst.read_text(encoding="utf-8").endswith("\\n"):
            fail(f"M14 payload copy produced literal backslash-n EOF: {rel}")
        print("WRITE |", rel)

    # CloudShare client: v2 fencing-token contract.
    p = repo / "app/src/main/java/com/ksp/cryptobot/cloudshare/CloudShareClient.kt"
    t = p.read_text(encoding="utf-8")
    lease_client = r'''    suspend fun acquireEngineLease(
        accountKey: String,
        engineId: String,
        platform: String,
        ttlSeconds: Int
    ): Map<String, Any?> = requestMap(
        "POST",
        "/v1/engine-lease/acquire",
        body = mapOf(
            "account_key" to accountKey,
            "engine_id" to engineId,
            "platform" to platform,
            "ttl_seconds" to ttlSeconds.coerceIn(30, 300)
        )
    )

    suspend fun heartbeatEngineLease(
        accountKey: String,
        engineId: String,
        fenceToken: Long,
        ttlSeconds: Int
    ): Map<String, Any?> = requestMap(
        "POST",
        "/v1/engine-lease/heartbeat",
        body = mapOf(
            "account_key" to accountKey,
            "engine_id" to engineId,
            "fence_token" to fenceToken,
            "ttl_seconds" to ttlSeconds.coerceIn(30, 300)
        )
    )

    suspend fun releaseEngineLease(
        accountKey: String,
        engineId: String,
        fenceToken: Long
    ): Map<String, Any?> = requestMap(
        "POST",
        "/v1/engine-lease/release",
        body = mapOf(
            "account_key" to accountKey,
            "engine_id" to engineId,
            "fence_token" to fenceToken
        )
    )

    suspend fun engineLeaseStatus(
        accountKey: String,
        engineId: String,
        fenceToken: Long
    ): Map<String, Any?> = requestMap(
        "POST",
        "/v1/engine-lease/status",
        body = mapOf(
            "account_key" to accountKey,
            "engine_id" to engineId,
            "fence_token" to fenceToken
        )
    )

'''
    t = replace_scope(
        t,
        "    suspend fun acquireEngineLease(",
        "    suspend fun adminPing()",
        lease_client,
        "M14 CloudShare fencing client"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # Fresh D1 schema uses fencing v2.
    p = repo / "app/src/main/assets/cloudshare_setup/schema.sql"
    t = p.read_text(encoding="utf-8")
    old_schema = '''CREATE TABLE IF NOT EXISTS engine_leases (
    account_key TEXT PRIMARY KEY,
    holder_client_id TEXT NOT NULL,
    holder_engine_id TEXT NOT NULL,
    platform TEXT NOT NULL DEFAULT '',
    expires_at_epoch_ms INTEGER NOT NULL,
    updated_at TEXT NOT NULL
);
'''
    new_schema = '''CREATE TABLE IF NOT EXISTS engine_leases (
    account_key TEXT PRIMARY KEY,
    holder_client_id TEXT NOT NULL,
    holder_engine_id TEXT NOT NULL,
    platform TEXT NOT NULL DEFAULT '',
    expires_at_epoch_ms INTEGER NOT NULL,
    updated_at TEXT NOT NULL,
    fence_token INTEGER NOT NULL DEFAULT 1,
    schema_version INTEGER NOT NULL DEFAULT 2
);
'''
    t = replace_once(t, old_schema, new_schema, "M14 fresh engine lease schema v2")
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # Worker: server-side fencing token + server-derived remaining lease time.
    p = repo / "app/src/main/assets/cloudshare_setup/cloudshare-worker.js"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        'const PROTOCOL_VERSION = "2026-07-26";\n',
        'const PROTOCOL_VERSION = "2026-07-26";\nconst ENGINE_LEASE_SCHEMA_VERSION = 2;\n',
        "M14 Worker lease schema constant"
    )

    lease_handler = r'''async function handleEngineLease(request, env, client, path) {
  const body = await request.json().catch(() => ({}));
  const accountKey = String(body.account_key || "").trim().toLowerCase();
  const engineId = String(body.engine_id || "").trim().slice(0, 120);
  const platform = String(body.platform || "").trim().slice(0, 40);
  const fenceToken = Math.max(0, Number(body.fence_token || 0));
  const ttlSeconds = Math.min(300, Math.max(30, Number(body.ttl_seconds || 75)));
  if (!/^[a-f0-9]{64}$/.test(accountKey) || !engineId) {
    return json({ error: "valid account_key and engine_id required" }, 400);
  }

  const now = Date.now();
  const expires = now + ttlSeconds * 1000;
  const updated = nowIso();

  const readLease = async () => env.DB.prepare(
    `SELECT holder_client_id, holder_engine_id, platform, expires_at_epoch_ms,
            fence_token, schema_version
       FROM engine_leases WHERE account_key=? LIMIT 1`
  ).bind(accountKey).first();

  const wire = (row, extra = {}) => {
    const expiry = Number(row?.expires_at_epoch_ms || 0);
    return {
      ...extra,
      holder_engine_id: row?.holder_engine_id || "",
      holder_platform: row?.platform || "",
      expires_at_epoch_ms: expiry,
      fence_token: Number(row?.fence_token || 0),
      lease_schema_version: Number(row?.schema_version || 0),
      server_now_epoch_ms: now,
      lease_remaining_ms: Math.max(0, expiry - now)
    };
  };

  if (path === "/v1/engine-lease/acquire") {
    await env.DB.prepare(
      `INSERT INTO engine_leases
       (account_key, holder_client_id, holder_engine_id, platform,
        expires_at_epoch_ms, updated_at, fence_token, schema_version)
       VALUES (?, ?, ?, ?, ?, ?, 1, ?)
       ON CONFLICT(account_key) DO UPDATE SET
         holder_client_id=excluded.holder_client_id,
         holder_engine_id=excluded.holder_engine_id,
         platform=excluded.platform,
         expires_at_epoch_ms=excluded.expires_at_epoch_ms,
         updated_at=excluded.updated_at,
         fence_token=CASE
           WHEN engine_leases.expires_at_epoch_ms <= ? THEN engine_leases.fence_token + 1
           ELSE engine_leases.fence_token
         END,
         schema_version=excluded.schema_version
       WHERE engine_leases.expires_at_epoch_ms <= ?
          OR (engine_leases.holder_client_id=? AND engine_leases.holder_engine_id=?)`
    ).bind(
      accountKey, client.client_id, engineId, platform, expires, updated,
      ENGINE_LEASE_SCHEMA_VERSION,
      now,
      now, client.client_id, engineId
    ).run();

    const row = await readLease();
    const acquired = !!row &&
      row.holder_client_id === client.client_id &&
      row.holder_engine_id === engineId &&
      Number(row.schema_version || 0) === ENGINE_LEASE_SCHEMA_VERSION &&
      Number(row.expires_at_epoch_ms || 0) > now;

    return json(wire(row, { acquired }));
  }

  if (path === "/v1/engine-lease/heartbeat") {
    if (fenceToken <= 0) return json({ error: "positive fence_token required" }, 400);

    const result = await env.DB.prepare(
      `UPDATE engine_leases
          SET expires_at_epoch_ms=?, updated_at=?, platform=?
        WHERE account_key=?
          AND holder_client_id=?
          AND holder_engine_id=?
          AND fence_token=?
          AND schema_version=?
          AND expires_at_epoch_ms > ?`
    ).bind(
      expires, updated, platform,
      accountKey, client.client_id, engineId, fenceToken,
      ENGINE_LEASE_SCHEMA_VERSION, now
    ).run();

    const row = await readLease();
    const renewed = Number(result?.meta?.changes || 0) > 0 &&
      row?.holder_client_id === client.client_id &&
      row?.holder_engine_id === engineId &&
      Number(row?.fence_token || 0) === fenceToken;

    return json(wire(row, { renewed }));
  }

  if (path === "/v1/engine-lease/release") {
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

  if (path === "/v1/engine-lease/status") {
    const row = await readLease();
    const owned = !!row &&
      row.holder_client_id === client.client_id &&
      row.holder_engine_id === engineId &&
      Number(row.fence_token || 0) === fenceToken &&
      Number(row.schema_version || 0) === ENGINE_LEASE_SCHEMA_VERSION &&
      Number(row.expires_at_epoch_ms || 0) > now;
    return json(wire(row, { owned }));
  }

  return json({ error: "engine lease route not found" }, 404);
}

'''
    t = replace_scope(
        t,
        "async function handleEngineLease(",
        "async function adminRoutes(",
        lease_handler,
        "M14 Worker fencing lease handler"
    )
    t = replace_once(
        t,
        '      if (request.method === "GET" && path === "/v1/health") {\n        await env.DB.prepare("SELECT 1 AS ok").first();\n        return json({',
        '      if (request.method === "GET" && path === "/v1/health") {\n        await env.DB.prepare("SELECT 1 AS ok").first();\n        await env.DB.prepare("SELECT fence_token, schema_version FROM engine_leases LIMIT 1").first();\n        return json({',
        "M14 Worker health verifies lease columns"
    )
    t = replace_once(
        t,
        '          protocol_version: PROTOCOL_VERSION,\n          d1: true,',
        '          protocol_version: PROTOCOL_VERSION,\n          engine_lease_schema_version: ENGINE_LEASE_SCHEMA_VERSION,\n          d1: true,',
        "M14 Worker health lease schema"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # Foreground host: DMS must be freshly confirmed disabled for LIVE.
    p = repo / "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        'import com.ksp.cryptobot.execution.EngineAuthorityLeaseManager\n',
        'import com.ksp.cryptobot.execution.EngineAuthorityLeaseManager\nimport com.ksp.cryptobot.execution.KrakenDmsSafetyManager\nimport com.ksp.cryptobot.execution.KrakenDmsSafetyRuntime\n',
        "M14 service DMS imports"
    )
    t = replace_once(
        t,
        '    private lateinit var authorityLease: EngineAuthorityLeaseManager\n',
        '    private lateinit var authorityLease: EngineAuthorityLeaseManager\n    private lateinit var dmsSafety: KrakenDmsSafetyManager\n',
        "M14 service DMS field"
    )
    t = replace_once(
        t,
        '        authorityLease = EngineAuthorityLeaseManager(applicationContext)\n',
        '        authorityLease = EngineAuthorityLeaseManager(applicationContext)\n        dmsSafety = KrakenDmsSafetyManager(applicationContext)\n',
        "M14 service DMS initialization"
    )
    startup_marker = '                statusStore.write("Distributed LIVE authority acquired. engine=${authority.engineId}, state=${authority.state}, expires=${authority.expiresAtEpochMs}.", "LIVE")\n'
    startup_block = startup_marker + r'''                val dms = dmsSafety.ensureDisarmed(startSettings, "startup:$recoveryReason")
                if (!dms.safeForNewEntries) {
                    hostStore.failure("LIVE DMS safety blocked: ${dms.state}: ${dms.reason}")
                    statusStore.write("LIVE start blocked because Kraken DMS could not be confirmed disabled: ${dms.state}: ${dms.reason}", "ERROR")
                    updateNotification("LIVE blocked: Kraken DMS state unknown")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                statusStore.write("Kraken DMS safety confirmed: ${dms.state}. Account-wide CancelAllOrdersAfter remains disabled to preserve protective SELL orders.", "LIVE")
'''
    t = replace_once(t, startup_marker, startup_block, "M14 startup DMS confirmation")

    cycle_marker = '''                configureRealtimeMarketData(current, network.usable)
                configurePrivateExecutionState(current, network.usable)
                val cycleStart = System.currentTimeMillis()
'''
    cycle_block = '''                configureRealtimeMarketData(current, network.usable)
                configurePrivateExecutionState(current, network.usable)
                if (current.mode != BotMode.PAPER && current.exchangeProvider == ExchangeProvider.KRAKEN) {
                    val dms = dmsSafety.ensureDisarmed(current, "service-cycle")
                    if (!dms.safeForNewEntries) {
                        statusStore.write(
                            "Kraken DMS confirmation degraded: ${dms.state}: ${dms.reason}. New BUYs are fail-closed; protective SELLs remain allowed.",
                            "ERROR"
                        )
                    }
                }
                val cycleStart = System.currentTimeMillis()
'''
    t = replace_once(t, cycle_marker, cycle_block, "M14 cycle DMS reassertion")

    notify_marker = '''                    val wsHealth = KrakenRealtimeMarketDataRegistry.health()
                    val execHealth = KrakenPrivateExecutionRegistry.health()
                    updateNotification(
                        "RUNNING $modeText • net=${network.transports} • ws=${wsHealth.state}/${wsHealth.systemStatus} • exec=${execHealth.state}${if (execHealth.knownForEntries) "/known" else "/unknown"} • next=${selectedDelay}s • signals=${decisions.size}"
                    )
'''
    notify_block = '''                    val wsHealth = KrakenRealtimeMarketDataRegistry.health()
                    val execHealth = KrakenPrivateExecutionRegistry.health()
                    val dmsHealth = KrakenDmsSafetyRuntime.snapshot()
                    updateNotification(
                        "RUNNING $modeText • net=${network.transports} • ws=${wsHealth.state}/${wsHealth.systemStatus} • exec=${execHealth.state}${if (execHealth.knownForEntries) "/known" else "/unknown"} • dms=${dmsHealth.state} • next=${selectedDelay}s • signals=${decisions.size}"
                    )
'''
    t = replace_once(t, notify_marker, notify_block, "M14 DMS notification status")
    t = replace_once(
        t,
        '''        authorityLease.stop()
        controller.stop()
''',
        '''        authorityLease.stop()
        dmsSafety.stop()
        controller.stop()
''',
        "M14 stopBot DMS state"
    )
    t = replace_once(
        t,
        '''        authorityLease.stop()
        connectivity.stop()
''',
        '''        authorityLease.stop()
        dmsSafety.stop()
        connectivity.stop()
''',
        "M14 onDestroy DMS state"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # BUY gate: fencing lease + fresh DMS disarm. SELL is deliberately not gated.
    p = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    t = p.read_text(encoding="utf-8")
    old_gate = '''        if (settings.mode != BotMode.PAPER && request.side == OrderSide.BUY) {
            val authority = com.ksp.cryptobot.execution.EngineAuthorityRuntime.canSubmitNewEntry(settings.mode)
            if (!authority.first) {
                updateStatus("LIVE entry blocked by distributed engine-authority gate: ${authority.second}", "ERROR")
                return ExecutionAttemptResult(false)
            }
        }
'''
    new_gate = '''        if (settings.mode != BotMode.PAPER && request.side == OrderSide.BUY) {
            val authority = com.ksp.cryptobot.execution.EngineAuthorityRuntime.canSubmitNewEntry(settings.mode)
            if (!authority.first) {
                updateStatus("LIVE entry blocked by distributed engine-authority gate: ${authority.second}", "ERROR")
                return ExecutionAttemptResult(false)
            }
            val dms = com.ksp.cryptobot.execution.KrakenDmsSafetyRuntime.canSubmitNewEntry(settings.mode)
            if (!dms.first) {
                updateStatus("LIVE entry blocked by Kraken DMS safety gate: ${dms.second}", "ERROR")
                return ExecutionAttemptResult(false)
            }
        }
'''
    t = replace_once(t, old_gate, new_gate, "M14 BUY DMS safety gate")
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    actual = changed | untracked
    allowed = set(PAYLOAD_FILES) | {
        "app/src/main/java/com/ksp/cryptobot/cloudshare/CloudShareClient.kt",
        "app/src/main/assets/cloudshare_setup/schema.sql",
        "app/src/main/assets/cloudshare_setup/cloudshare-worker.js",
        "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt",
        "app/src/main/java/com/ksp/cryptobot/core/BotController.kt",
    }
    if actual - allowed:
        fail("Unexpected M14 app changes: " + ",".join(sorted(actual - allowed)))
    if allowed - actual:
        fail("Expected M14 changes missing: " + ",".join(sorted(allowed - actual)))

    print("PASS | M14 controlled app diff.")

if __name__ == "__main__":
    main()
