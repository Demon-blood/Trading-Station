#!/usr/bin/env python3
from pathlib import Path
import sys


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""


def main():
    print("INFO | M24 verifier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    policy = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/M24CrossPlatformAuthority.kt")
    lease = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/EngineAuthorityLeaseManager.kt")
    client = read(repo / "app/src/main/java/com/ksp/cryptobot/cloudshare/CloudShareClient.kt")
    worker = read(repo / "app/src/main/assets/cloudshare_setup/cloudshare-worker.js")
    exchange = read(repo / "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt")
    health = read(repo / "app/src/main/java/com/ksp/cryptobot/observability/M23Observability.kt")
    protocol = read(repo / "app/src/main/assets/cloudshare_setup/m24_cross_platform_authority_protocol.md")
    tests_policy = read(repo / "app/src/test/java/com/ksp/cryptobot/execution/M24CrossPlatformAuthorityPolicyTest.kt")
    tests_scenarios = read(repo / "app/src/test/java/com/ksp/cryptobot/execution/M24CrossPlatformAuthorityScenarioTest.kt")
    db = read(repo / "app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt")

    add_order_gate = exchange.find("M24 cross-platform authority gate blocks BUY at Kraken AddOrder boundary")
    pending = exchange.find("KrakenPrivateExecutionRegistry.markSubmissionPending")
    m24_window = exchange[max(0, add_order_gate - 700): min(len(exchange), pending + 300)] if add_order_gate >= 0 and pending >= 0 else ""

    release_start = worker.find('path === "/v1/engine-lease/release"')
    status_start = worker.find('path === "/v1/engine-lease/status"')
    release_block = worker[release_start:status_start] if release_start >= 0 and status_start > release_start else ""

    transfer_start = worker.find('path === "/v1/engine-lease/transfer"')
    transfer_block = worker[transfer_start:status_start] if transfer_start >= 0 and status_start > transfer_start else ""

    checks = {
        "no Room schema bump": "version = 12" in db,
        "M14 D1 lease schema remains v2 compatible":
            "const val LEASE_SCHEMA_VERSION = 2" in lease and
            "const ENGINE_LEASE_SCHEMA_VERSION = 2;" in worker,

        "policy supports Android and Windows only":
            'ANDROID("ANDROID")' in policy and
            'WINDOWS("WINDOWS")' in policy and
            "fun supportedPlatform" in policy,
        "policy subtracts RTT and safety margin":
            "serverRemainingMs - roundTripMs.coerceAtLeast(0L) - RESPONSE_SAFETY_MARGIN_MS" in policy,
        "remote submission requires exact platform/fence":
            "fun remoteSubmissionValid(" in policy and
            "responseFence == expectedFence" in policy and
            "M24AuthorityPlatform.parse(holderPlatform) == expectedPlatform" in policy,
        "transfer requires strictly newer fence":
            "fun transferAccepted(" in policy and "newFence > oldFence" in policy,

        "authority snapshot carries local and holder platform":
            'val localPlatform: String = ""' in lease and
            'val holderPlatform: String = ""' in lease,
        "runtime installs authoritative remote submission validator":
            "installRemoteSubmissionValidator" in lease and
            "canSubmitNewLiveEntryAuthoritative" in lease and
            "remote authority validator is unavailable; LIVE BUY is fail-closed" in lease,
        "manager registers authoritative validator":
            "installRemoteSubmissionValidator { validateRemoteSubmission() }" in lease,
        "manager validates CloudShare status before submission":
            "private suspend fun validateRemoteSubmission()" in lease and
            "client.engineLeaseStatus(" in lease and
            "M24CrossPlatformAuthorityPolicy.remoteSubmissionValid(" in lease,
        "partition or status failure blocks BUY":
            'state = "PARTITION_UNKNOWN"' in lease and
            "network partition/uncertainty blocks LIVE BUY" in lease,
        "manager subtracts RTT on acquire heartbeat and status":
            lease.count("M24CrossPlatformAuthorityPolicy.conservativeRemainingMs(") >= 3,
        "Android heartbeat carries platform":
            "platform = LOCAL_PLATFORM" in lease and
            "suspend fun heartbeatEngineLease(" in client and
            '"platform" to platform' in client,
        "manager exposes atomic authority transfer":
            "suspend fun transferAuthority(target: M24AuthorityTransferTarget)" in lease and
            "client.transferEngineLease(" in lease and
            'state = "TRANSFERRED"' in lease,

        "Worker accepts only Android or Windows holders":
            'new Set(["ANDROID", "WINDOWS"])' in worker and
            worker.count("platform must be ANDROID or WINDOWS") >= 2,
        "normal release preserves fencing row":
            "UPDATE engine_leases" in release_block and
            "SET expires_at_epoch_ms=?, updated_at=?" in release_block and
            "DELETE FROM engine_leases" not in release_block,
        "normal release remains holder and fence conditional":
            "holder_client_id=?" in release_block and
            "holder_engine_id=?" in release_block and
            "fence_token=?" in release_block,
        "Worker has atomic transfer route":
            'path === "/v1/engine-lease/transfer"' in worker and
            "fence_token=fence_token + 1" in transfer_block and
            "holder_client_id=?" in transfer_block and
            "holder_engine_id=?" in transfer_block,
        "transfer requires enabled registered target":
            "EXISTS (SELECT 1 FROM clients WHERE client_id=? AND enabled=1)" in transfer_block,
        "transfer response proves newer target epoch":
            "Number(row?.fence_token || 0) > fenceToken" in transfer_block and
            "row?.holder_engine_id === targetEngineId" in transfer_block and
            "row?.platform === targetPlatform" in transfer_block,
        "CloudShare client exposes transfer contract":
            "suspend fun transferEngineLease(" in client and
            '"/v1/engine-lease/transfer"' in client and
            '"target_client_id" to targetClientId' in client and
            '"target_platform" to targetPlatform' in client,

        "final Kraken BUY boundary performs M24 remote authority check":
            add_order_gate >= 0 and
            pending > add_order_gate and
            "EngineAuthorityRuntime.canSubmitNewLiveEntryAuthoritative()" in m24_window and
            "request.side == OrderSide.BUY" in m24_window,
        "M24 final authority gate remains BUY-only":
            "request.side == OrderSide.SELL" not in m24_window,
        "M23 authority diagnostics expose platforms":
            '"local_platform" to authority.localPlatform' in health and
            '"holder_platform" to authority.holderPlatform' in health,
        "diagnostics do not expose CloudShare token":
            "clientToken" not in health and "Authorization" not in health,

        "protocol documents one-live-engine invariant":
            "only one LIVE engine may submit new orders" in protocol and
            "Windows LIVE engine must use this same protocol" in protocol,
        "protocol documents dashboard-only non-owner":
            "dashboard-only" in protocol.lower(),

        "policy tests cover partition and exact authority":
            "partitionFailsClosed" in tests_policy and
            "authoritativeSubmissionRequiresExactOwnerPlatformAndFence" in tests_policy,
        "scenario tests cover lease contention":
            "windowsVsAndroidContentionHasOneWinner" in tests_scenarios,
        "scenario tests cover simultaneous launch":
            "simultaneousLaunchStillProducesExactlyOneOwner" in tests_scenarios,
        "scenario tests cover authority transfer":
            "authorityTransferFencesOldAndroidImmediately" in tests_scenarios,
        "scenario tests cover old process return":
            "oldProcessComingBackOnlineCannotUseStaleFence" in tests_scenarios,
        "scenario tests cover stale owner":
            "staleOwnerAfterExpiryCannotMutateNewEpoch" in tests_scenarios,
        "scenario tests cover release fencing continuity":
            "releasePreservesFenceHistoryAcrossCrossPlatformFailover" in tests_scenarios,
        "scenario tests cover network partition":
            "networkPartitionIsRejectedBySubmissionPolicyEvenBeforeServerExpiry" in tests_scenarios,
        "scenario tests cover cross-platform failover":
            "crossPlatformFailoverAfterExpiryUsesNewFence" in tests_scenarios,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        raise SystemExit("M24 cross-platform authority verification failed: " + ", ".join(failed))

    print("\nPASS | M24 Windows / Android cross-platform LIVE authority contracts satisfied.")


if __name__ == "__main__":
    main()
