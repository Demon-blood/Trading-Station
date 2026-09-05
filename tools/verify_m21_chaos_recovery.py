#!/usr/bin/env python3
from pathlib import Path
import sys

def read(path):
    p = Path(path)
    return p.read_text(encoding="utf-8") if p.exists() else ""

def main():
    print("INFO | M21 verifier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    policy = read(repo / "app/src/main/java/com/ksp/cryptobot/exchange/RecoveryIntegrityPolicy.kt")
    quarantine = read(repo / "app/src/main/java/com/ksp/cryptobot/exchange/KrakenDurableExecutionQuarantine.kt")
    private = read(repo / "app/src/main/java/com/ksp/cryptobot/exchange/KrakenPrivateExecutionState.kt")
    service = read(repo / "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt")
    manifest = read(repo / "app/src/main/AndroidManifest.xml")
    db = read(repo / "app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt")
    policy_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/exchange/RecoveryIntegrityPolicyM21Test.kt")
    sequence_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/exchange/PrivateExecutionSequencePolicyM21Test.kt")
    chaos_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/exchange/RecoveryChaosMatrixM21Test.kt")
    codec_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/exchange/DurableQuarantineCodecM21Test.kt")

    checks = {
        "M20 prerequisite exists":
            (repo / "tools/verify_m20_net_profit_optimizer.py").exists(),
        "no Room schema bump":
            "version = 12" in db,
        "recovery requires full authoritative reconcile":
            "authoritativeReconciliationComplete" in policy and
            "Authoritative recovery reconciliation is incomplete" in policy,
        "recovery requires network and exchange truth":
            "networkUsable" in policy and
            "privateExecutionContinuous" in policy and
            "recentRestTruth" in policy,
        "durable AddOrder ambiguity blocks BUY":
            "durableSubmissionAmbiguities" in policy,
        "ambiguous amend/cancel state blocks BUY":
            "ambiguousOrderMutations" in policy,
        "database failure blocks BUY":
            "databaseHealthy" in policy,
        "clock rollback blocks BUY":
            "wallClockSane" in policy and
            "MAX_BACKWARD_JUMP_MS = 5_000L" in policy,
        "distributed authority remains prerequisite":
            "distributedAuthorityHeld" in policy,
        "unprotected position blocks new BUY":
            "unprotectedPositions" in policy,
        "protective SELL quantity is clamped":
            "requestedSellQuantity.min(authoritativeBaseQuantity)" in policy,
        "sequence policy distinguishes duplicate stale and gap":
            "DUPLICATE" in policy and
            "STALE_OR_OUT_OF_ORDER" in policy and
            "GAP" in policy,
        "exec_id filter is idempotent":
            "class IdempotentExecutionIdFilter" in policy and
            "if (!seen.add(id)) return false" in policy,
        "pre-submit quarantine commit success is mandatory":
            "Refusing Kraken AddOrder boundary because unresolved intent could not be durably committed" in quarantine and
            "private fun persistLocked(): Boolean" in quarantine,
        "ambiguous quarantine state must persist":
            "Unable to durably persist ambiguous Kraken AddOrder state" in quarantine,
        "failed quarantine clear restores in-memory block":
            "rows[clientOrderId] = removed" in quarantine and
            "keeping entry fail-closed" in quarantine,
        "private registry starts recovery-fenced":
            "private var recoveryReady = false" in private,
        "private registry exposes recovery transition API":
            "fun markRecoveryUnknown(" in private and
            "fun markRecoveryReconciled(" in private,
        "BUY is recovery-fenced":
            "M21 recovery fence blocks new BUY" in private,
        "protective SELL remains outside entry fence":
            "Protective/exit side is not entry-gated" in private,
        "credential change resets recovery fence":
            "Kraken credentials changed; full authoritative reconciliation required." in private and
            "executionIds.clear()" in private,
        "network loss resets recovery fence":
            "Validated network lost; authoritative reconciliation required." in private,
        "disconnect resets recovery fence":
            "Private execution transport disconnected; reconciliation required." in private,
        "silent stream resets recovery fence":
            "Private execution stream became silent; reconciliation required." in private,
        "sequence discontinuity resets recovery fence":
            "Private execution sequence continuity lost; full reconciliation required." in private,
        "duplicate private sequence is ignored":
            "PrivateSequenceDisposition.DUPLICATE -> return" in private,
        "private exec_id is deduped before publication":
            "if (!executionIds.accept(report.executionId)) continue" in private,
        "service marks recovery unknown on network outage":
            "Validated network unavailable during service cycle." in service,
        "service marks recovery unknown while reconciling":
            "Authoritative reconciliation in progress: $reason" in service,
        "service releases fence only after full reconciliation":
            "controller.reconcileLiveExecutionState(settings)" in service and
            "controller.loadOpenOrdersSnapshot(settings)" in service and
            "controller.loadLifecycleSnapshot(settings)" in service and
            "controller.loadPortfolioSnapshot(settings)" in service and
            "markRecoveryReconciled(" in service,
        "failed reconcile preserves fence":
            "Reconciliation failed after $reason" in service and
            "markRecoveryUnknown(" in service,
        "sticky process restart remains":
            "return START_STICKY" in service and
            "sticky-process-restart" in service,
        "boot recovery remains declared":
            "android.intent.action.BOOT_COMPLETED" in manifest,
        "chaos test covers AddOrder/ACK crash":
            "killAfterAddOrderBeforeAckBlocksDuplicateBuy" in chaos_tests,
        "chaos test covers fill-before-DB crash":
            "killAfterFillBeforeDbWriteBlocksUntilReconcile" in chaos_tests,
        "chaos test covers Wi-Fi and WS+REST failure":
            "wifiLossBlocksEntries" in chaos_tests and
            "wsAndRestTruthFailureBlocksEntries" in chaos_tests,
        "chaos test covers amend/cancel lost responses":
            "amendResponseLostBlocksAdditionalMutationPath" in chaos_tests and
            "cancelAckLostBlocksReplacementUntilTruth" in chaos_tests,
        "chaos test covers sequence gap and reboot":
            "sequenceGapBlocksUntilSnapshotAndReconcile" in chaos_tests and
            "deviceRebootStartsUnknown" in chaos_tests,
        "chaos test covers lease loss and unprotected position":
            "distributedLeaseFailureBlocksBuy" in chaos_tests and
            "protectiveStopAckLostBlocksNewRiskButCanReduceExposure" in chaos_tests,
        "chaos test covers late and duplicate fill idempotence":
            "lateFillAfterCancelCanBeAppliedExactlyOnce" in chaos_tests and
            "duplicatePrivateFillCannotBeAppliedTwice" in chaos_tests,
        "chaos test covers Doze-like network suspension":
            "dozeLikeNetworkSuspensionBlocksNewBuy" in chaos_tests,
        "chaos test covers wall-clock regression":
            "clockJumpBackwardBlocksAgeDependentTrading" in chaos_tests and
            "materialClockRegressionBlocksBuy" in policy_tests,
        "sequence tests cover duplicate stale and gap":
            "duplicateSequenceIsIdentifiedWithoutReapplying" in sequence_tests and
            "outOfOrderSequenceInvalidatesContinuity" in sequence_tests and
            "forwardGapInvalidatesContinuity" in sequence_tests,
        "quarantine codec tests cover process boundary":
            "pendingIntentSurvivesProcessBoundaryCodec" in codec_tests and
            "ambiguousReasonRoundTripsWithoutDelimiterCorruption" in codec_tests,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        raise SystemExit(
            "M21 chaos/recovery/data-integrity verification failed: " +
            ", ".join(failed)
        )

    print()
    print("PASS | M21 crash, restart, network, sequence, durable-intent and recovery-fence contracts satisfied.")

if __name__ == "__main__":
    main()
