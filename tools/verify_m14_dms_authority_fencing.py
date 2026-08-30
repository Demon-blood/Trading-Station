#!/usr/bin/env python3
from pathlib import Path
import sys

def read(path):
    p = Path(path)
    return p.read_text(encoding="utf-8") if p.exists() else ""

def main():
    print("INFO | M14 verifier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    lease = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/EngineAuthorityLeaseManager.kt")
    dms = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/KrakenDmsSafetyManager.kt")
    client = read(repo / "app/src/main/java/com/ksp/cryptobot/cloudshare/CloudShareClient.kt")
    worker = read(repo / "app/src/main/assets/cloudshare_setup/cloudshare-worker.js")
    schema = read(repo / "app/src/main/assets/cloudshare_setup/schema.sql")
    migration = read(repo / "app/src/main/assets/cloudshare_setup/m14_engine_lease_v2_migration.sql")
    service = read(repo / "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt")
    controller = read(repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    fence_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/execution/EngineAuthorityFencingPolicyTest.kt")
    dms_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/execution/KrakenDmsSafetyPolicyTest.kt")
    db = read(repo / "app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt")

    checks = {
        "no Room schema bump":
            "version = 12" in db,

        "lease snapshot carries fencing token":
            "val fencingToken: Long = 0L" in lease,
        "lease snapshot carries schema version":
            "val leaseSchemaVersion: Int = 0" in lease,
        "lease snapshot carries monotonic deadline":
            "val localDeadlineElapsedMs: Long = 0L" in lease,
        "fencing schema version is v2":
            "const val LEASE_SCHEMA_VERSION = 2" in lease,
        "acquisition requires positive fencing token":
            "fencingToken > 0L" in lease and "fun acquisitionValid(" in lease,
        "heartbeat requires unchanged fencing token":
            "responseToken == expectedToken" in lease and "fun heartbeatValid(" in lease,
        "runtime lease uses elapsedRealtime deadline":
            "SystemClock.elapsedRealtime()" in lease and
            "runtimeLeaseValid(" in lease and
            "localDeadlineElapsedMs > nowElapsedMs" in lease,
        "old CloudShare lease schema blocks LIVE":
            'blocked("LEASE_SCHEMA_UPGRADE_REQUIRED"' in lease and
            'health["engine_lease_schema_version"]' in lease,
        "heartbeat loss remains fail closed":
            'EngineAuthoritySnapshot(false, "LOST"' in lease and
            'EngineAuthoritySnapshot(false, "UNKNOWN"' in lease,
        "PAPER still bypasses distributed LIVE lease":
            'EngineAuthoritySnapshot(true, "PAPER"' in lease,

        "fresh D1 schema has fence token":
            "fence_token INTEGER NOT NULL DEFAULT 1" in schema,
        "fresh D1 schema is lease v2":
            "schema_version INTEGER NOT NULL DEFAULT 2" in schema,
        "existing D1 migration supplied":
            "ALTER TABLE engine_leases" in migration and
            "ADD COLUMN fence_token" in migration and
            "ADD COLUMN schema_version" in migration,

        "Worker declares lease schema v2":
            "const ENGINE_LEASE_SCHEMA_VERSION = 2;" in worker,
        "Worker health verifies and advertises lease schema":
            'SELECT fence_token, schema_version FROM engine_leases LIMIT 1' in worker and
            "engine_lease_schema_version: ENGINE_LEASE_SCHEMA_VERSION" in worker,
        "expired acquisition increments fence":
            "engine_leases.fence_token + 1" in worker and
            "engine_leases.expires_at_epoch_ms <= ?" in worker,
        "heartbeat requires submitted fence":
            "AND fence_token=?" in worker and
            'positive fence_token required' in worker,
        "release is fenced":
            'path === "/v1/engine-lease/release"' in worker and
            "AND fence_token=?" in worker,
        "lease status route is fenced":
            'path === "/v1/engine-lease/status"' in worker and
            "const owned =" in worker,
        "Worker returns server lease remaining time":
            "server_now_epoch_ms: now" in worker and
            "lease_remaining_ms: Math.max(0, expiry - now)" in worker,
        "CloudShare heartbeat sends fence token":
            '"fence_token" to fenceToken' in client and
            "suspend fun heartbeatEngineLease(" in client,
        "CloudShare release sends fence token":
            "suspend fun releaseEngineLease(" in client and
            client.count('"fence_token" to fenceToken') >= 3,

        "DMS policy only confirms DISARMED":
            'state == "DISARMED"' in dms,
        "DMS confirmation expires quickly":
            "CONFIRMATION_MAX_AGE_MS = 45_000L" in dms,
        "M14 only sends Kraken DMS timeout zero":
            "setDeadMansSwitch(0)" in dms and
            "setDeadMansSwitch(60)" not in dms and
            "setDeadMansSwitch(120)" not in dms,
        "DMS failure blocks new entries":
            "safeForNewEntries = false" in dms and
            'state = "UNKNOWN"' in dms,
        "PAPER has no DMS requirement":
            'mode == BotMode.PAPER' in dms and
            '"PAPER has no Kraken DMS requirement."' in dms,

        "service confirms DMS before controller start":
            service.find('dmsSafety.ensureDisarmed(startSettings') >= 0 and
            service.find('dmsSafety.ensureDisarmed(startSettings') < service.find("controller.start()"),
        "service reasserts DMS during LIVE cycles":
            'dmsSafety.ensureDisarmed(current, "service-cycle")' in service,
        "service exposes DMS health":
            "KrakenDmsSafetyRuntime.snapshot()" in service and
            "dms=${dmsHealth.state}" in service,
        "service stops DMS runtime":
            service.count("dmsSafety.stop()") >= 2,

        "BUY gate requires fresh DMS disarm":
            "KrakenDmsSafetyRuntime.canSubmitNewEntry(settings.mode)" in controller and
            "LIVE entry blocked by Kraken DMS safety gate" in controller,
        "DMS gate remains BUY-only":
            "settings.mode != BotMode.PAPER && request.side == OrderSide.BUY" in controller,
        "protective SELL is not DMS-gated":
            "request.side == OrderSide.SELL" not in controller[
                max(0, controller.find("KrakenDmsSafetyRuntime.canSubmitNewEntry") - 500):
                controller.find("KrakenDmsSafetyRuntime.canSubmitNewEntry") + 500
            ],

        "fencing policy regression tests":
            "acquisitionRequiresV2FenceAndPositiveServerRemainingTime" in fence_tests and
            "heartbeatRejectsOldFencingToken" in fence_tests and
            "localMonotonicDeadlineExpiresAuthority" in fence_tests,
        "DMS policy regression tests":
            "paperNeverNeedsDmsConfirmation" in dms_tests and
            "liveRequiresFreshConfirmedDisarm" in dms_tests,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        raise SystemExit("M14 DMS / authority fencing verification failed: " + ", ".join(failed))

    print("\nPASS | M14 safe DMS policy and distributed authority fencing contracts satisfied.")

if __name__ == "__main__":
    main()
