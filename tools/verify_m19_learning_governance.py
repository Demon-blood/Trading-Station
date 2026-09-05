#!/usr/bin/env python3
from pathlib import Path
import sys

def read(path):
    p = Path(path)
    return p.read_text(encoding="utf-8") if p.exists() else ""

def main():
    print("INFO | M19 verifier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    truth = read(repo / "app/src/main/java/com/ksp/cryptobot/strategy/StrategyTruthRegistry.kt")
    governance = read(repo / "app/src/main/java/com/ksp/cryptobot/governance/LearningGovernanceEngine.kt")
    learning = read(repo / "app/src/main/java/com/ksp/cryptobot/learning/TrueSelfLearningEngine.kt")
    controller = read(repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    micro = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/MarketMicrostructureEngine.kt")
    lifecycle = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/SmartOrderLifecycleManager.kt")
    db = read(repo / "app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt")
    mono_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/governance/LearningMonotonicPolicyM19Test.kt")
    drift_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/governance/LearningGovernanceDriftM19Test.kt")
    stat_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/governance/LearningGovernanceStatisticsM19Test.kt")

    checks = {
        "M18 truth registry prerequisite exists":
            "M18 strategy truth registry" in truth and
            "fun autoSelectable()" in truth,

        "no Room schema bump":
            "version = 12" in db,

        "governance has explicit lifecycle stages":
            "WARMUP" in governance and
            "PAPER_VALIDATION" in governance and
            "SHADOW_VALIDATED" in governance and
            "TINY_LIVE" in governance and
            "ROLLBACK" in governance,

        "feature drift is chronological recent-vs-baseline":
            "fun featureDrift(" in governance and
            "sortedBy { it.timestampEpochMs }" in governance and
            "FEATURE_RECENT = 50" in governance and
            "FEATURE_BASELINE = 100" in governance,

        "feature drift includes score spread volume and volatility proxy":
            "finalScore.toDouble() / 100.0" in governance and
            "spreadPercent" in governance and
            "ln1p(" in governance and
            "priceChange24hPercent" in governance,

        "regime drift stays unknown without labels":
            "fun regimeDrift(" in governance and
            "regime drift unknown" in governance and
            "totalVariation" in governance,

        "execution-model drift compares recent and baseline realized slippage":
            "fun executionDrift(" in governance and
            "recentAvg=" in governance and
            "baselineAvg=" in governance and
            "recentWorst" in governance,

        "performance decay uses realized chronological exits":
            "fun performanceDecay(" in governance and
            'it.side.equals("SELL", true)' in governance and
            "recentLower95MeanPnl" in governance,

        "confidence calibration uses Brier score":
            "fun confidenceCalibration(" in governance and
            "Brier=" in governance and
            "(p - y) * (p - y)" in governance,

        "parameter stability uses chronological thirds":
            "fun parameterStability(" in governance and
            "val chunkSize = pnl.size / 3" in governance and
            "signFlips" in governance,

        "M7 AI attribution is used with modelPath evidence":
            "fun modelAttribution(" in governance and
            "aiValueAddedQuote" in governance and
            "totalAiCostQuote" in governance and
            "modelPath" in governance and
            "badPath" in governance,

        "positive learning requires lower-95 positive realized outcome":
            "performance.recentLower95MeanPnl > BigDecimal.ZERO" in governance,

        "severe drift or decay can force rollback":
            "val rollback =" in governance and
            "severeDrift" in governance and
            "LearningGovernanceStage.ROLLBACK" in governance,

        "positive score learning is tightly bounded":
            "scoreBoostCeiling in 0..3" in governance and
            "if (positiveLearning) 3 else 0" in governance,

        "LIVE learned size ceiling can never exceed baseline":
            'liveSizeMultiplierCeiling in BigDecimal("0.25")..BigDecimal.ONE' in governance and
            "bounds.liveSizeMultiplierCeiling" in governance,

        "fill probability calibration can only be conservative":
            "fillProbabilityOffset in -0.08..0.0" in governance and
            "governanceOffset" in micro and
            ".coerceIn(-0.08, 0.0)" in micro,

        "execution timing adaptation is bounded":
            "staleTimingMultiplier in 0.75..1.0" in governance and
            "governanceMultiplier" in lifecycle and
            ".coerceIn(0.75, 1.0)" in lifecycle,

        "amend policy learning is bounded":
            "amendFillProbabilityThreshold in 0.45..0.60" in governance and
            "governedFillThreshold" in lifecycle and
            ".coerceIn(0.45, 0.60)" in lifecycle,

        "slippage safety buffer is measured and bounded":
            "slippageSafetyBufferBps in 0.0..25.0" in governance and
            "recentSlippageBps" in governance,

        "runtime defaults to positive adaptation disabled":
            "positiveLearningEnabled = false" in governance and
            "restrictiveDefault" in governance,

        "online learning is monotonic by action authority":
            "object LearningMonotonicPolicy" in governance and
            "SignalAction.WAIT -> if (score < 45) SignalAction.AVOID else SignalAction.WAIT" in governance and
            "SignalAction.AVOID -> SignalAction.AVOID" in governance,

        "SMALL_BUY cannot become BUY":
            "SignalAction.SMALL_BUY -> when" in governance and
            "score >= minBuyScore - 8 -> SignalAction.SMALL_BUY" in governance,

        "legacy learnedAction score-only promotion is removed":
            "score >= settings.minStrategyScoreToBuy -> SignalAction.BUY" not in learning and
            "LearningMonotonicPolicy.action(" in learning,

        "self-learning reads governance evidence and installs M19 state":
            "learningGovernance.assess(" in learning and
            "recentExecutionQuality(250)" in learning and
            "resolvedAiAttributions(1000)" in learning and
            "LearningGovernanceRuntime.install(governance)" in learning,

        "M19 governance decision is durably audited":
            'eventType = "M19_GOVERNANCE"' in learning and
            'key = "m19_learning_governance"' in learning,

        "persisted symbol learning obeys score and size governance":
            "governance.clampScoreAdjustment(raw.scoreAdjustment)" in learning and
            "governance.clampPositionMultiplier(" in learning,

        "learned hold deferral shuts off unless positive learning is qualified":
            "raw.shouldDeferTakeProfit && governance.positiveLearningEnabled" in learning and
            "raw.shouldDeferTrailingExit && governance.positiveLearningEnabled" in learning,

        "learned strategy selection respects M18 truth":
            "StrategyTruthRegistry.spec(preferred)?.liveSelectable == true" in learning and
            "StrategyTruthRegistry.spec(mode)?.liveSelectable == true" in learning,

        "adjustDecision cannot use positive adjustment above governance ceiling":
            "rawProfileAdjustment.coerceAtMost(governance.bounds.scoreBoostCeiling)" in learning,

        "adaptive automation LIVE size cannot exceed deterministic input":
            "learnedSize.min(automation.positionSizeEur)" in learning and
            "if (settings.mode == BotMode.PAPER)" in learning,

        "BotController supplies GovernanceDao":
            "TrueSelfLearningEngine(" in controller and
            "AppDatabase.get(appContext).governanceDao()" in controller,

        "M15 learned fill-time base remains observed data":
            "calibrationSamples" in lifecycle and
            "meanFillSeconds" in lifecycle and
            "meanFillSeconds * 1.25 * governanceMultiplier" in lifecycle,

        "M16 still explicitly treats L2 fill probability as heuristic":
            "This is NOT exchange queue position" in micro and
            "L2 is aggregated depth, not exact queue position." in micro,

        "monotonic regression tests cover WAIT WATCH AVOID and SMALL_BUY":
            "waitCanNeverBecomeEntry" in mono_tests and
            "watchCanNeverBecomeEntry" in mono_tests and
            "avoidCanNeverBecomeEntry" in mono_tests and
            "smallBuyCanNeverUpgradeToFullBuy" in mono_tests,

        "drift regression tests cover unknown evidence and rollback":
            "featureDriftStaysUnknownWithInsufficientEvidence" in drift_tests and
            "regimeDriftStaysUnknownWithoutLabels" in drift_tests and
            "severeExecutionDeteriorationTriggersRollback" in drift_tests,

        "statistics tests cover calibration stability and model attribution":
            "poorConfidenceCalibrationIsDetected" in stat_tests and
            "unstableThreeChunkOutcomesDoNotPassStability" in stat_tests and
            "consistentlyNegativeAiValueBecomesDefensive" in stat_tests,
    }

    # M19 is not allowed to rewrite deterministic capital/risk authority.
    forbidden_learning_mutations = [
        "maxDailyLossEur =",
        "maxPositionEur =",
        "maxCoinExposurePercent =",
        "maxSingleAssetAllocationPercent =",
        "stopLossPercent =",
        "liveTradingAcknowledged =",
        "withdraw-funds",
        "add-withdraw-address",
        "update-withdraw-address",
        "CancelAllOrdersAfter",
        "engine_leases",
        "fencingToken =",
    ]
    checks["learning governance does not mutate deterministic risk/authority settings"] = not any(
        token in governance or token in learning for token in forbidden_learning_mutations
    )

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        raise SystemExit(
            "M19 self-learning/model-governance verification failed: " +
            ", ".join(failed)
        )

    print("\nPASS | M19 drift control, monotonic learning, defensive rollback and bounded execution-learning contracts satisfied.")

if __name__ == "__main__":
    main()
