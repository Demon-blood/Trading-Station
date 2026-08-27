#!/usr/bin/env python3
from pathlib import Path
import sys


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""


def main():
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    engine = read(repo / "app/src/main/java/com/ksp/cryptobot/intelligence/AiAdaptiveGovernanceEngine.kt")
    controller = read(repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    m7 = read(repo / "app/src/main/java/com/ksp/cryptobot/intelligence/AiValueAttributionEngine.kt")
    tests = read(repo / "app/src/test/java/com/ksp/cryptobot/intelligence/AiAdaptiveGovernanceEngineTest.kt")

    checks = {
        "M8 defensive governance engine": "class AiAdaptiveGovernanceEngine" in engine,
        "actions are hold/disable only": "DISABLE_SOL" in engine and "DISABLE_CLOUD_AI" in engine and "ENABLE_" not in engine,
        "minimum overall samples 50": "MIN_OVERALL_SAMPLES = 50" in engine,
        "minimum Sol samples 30": "MIN_SOL_SAMPLES = 30" in engine,
        "seven day evidence span": "MIN_EVIDENCE_SPAN_MS = 7L * 24L" in engine,
        "overall total harm floor": 'MIN_OVERALL_TOTAL_HARM = BigDecimal("-0.25")' in engine,
        "Sol total harm floor": 'MIN_SOL_TOTAL_HARM = BigDecimal("-0.10")' in engine,
        "minimum normalized harm rate": 'MIN_HARM_MEAN_RATE = BigDecimal("-0.0005")' in engine,
        "optimistic upper 95 bound required negative overall": "overall.upper95 < BigDecimal.ZERO" in engine,
        "optimistic upper 95 bound required negative Sol": "sol.upper95 < BigDecimal.ZERO" in engine,
        "sample variance and standard error": "standardDeviation" in engine and "standardError" in engine,
        "conservative critical interval": "critical95" in engine and "2.03" in engine,
        "outlier clipping": 'CLIP_RATE = BigDecimal("0.25")' in engine and "coerceIn(CLIP_RATE.negate(), CLIP_RATE)" in engine,
        "low-integrity current-ticker fallback excluded": 'row.resolution != "HORIZON_FALLBACK_CURRENT_TICKER"' in engine,
        "resolved rows only": 'row.status == "RESOLVED"' in engine,
        "paid Luna rows only": "lunaCost <= BigDecimal.ZERO" in engine,
        "paid Sol rows only": "solCost <= BigDecimal.ZERO" in engine,
        "overall uses M7 net AI value": "row.aiValueAddedQuote" in engine,
        "Sol uses M7 incremental value": "row.solIncrementalValueQuote" in engine,
        "never auto re-enables": "M8 never auto-enables paid AI" in engine,
        "disable Sol preserves Luna": "enabled = config.enabled" in engine and "solEnabled = false" in engine,
        "disable cloud turns paid AI off": "enabled = false" in engine,
        "budget preserved": "monthlyBudgetUsd = config.monthlyBudgetUsd" in engine,
        "daily cap preserved": "maxSolCallsPerDay = config.maxSolCallsPerDay" in engine,
        "24h mutation cooldown": "ACTION_COOLDOWN_MS = 24L" in engine,
        "serialized mutations": "private val mutationMutex = Mutex()" in engine and "mutationMutex.lock()" in engine and "mutationMutex.unlock()" in engine,
        "read-only inspect path": "suspend fun inspect()" in engine,
        "M7 settlement triggers M8": "aiAdaptiveGovernance.evaluateAndApply()" in controller,
        "adaptation only after new M7 resolution": "settledAiCounterfactuals > 0" in controller,
        "defensive action status": "M8 AI adaptive governance=" in controller,
        "non-HOLD adaptation alert": "adaptiveDecision.action != AiAdaptiveAction.HOLD" in controller,
        "system verifier uses inspection not mutation": "aiAdaptiveGovernance.inspect()" in controller and "Inspection is read-only" in controller,
        "public M8 decision snapshot": "loadAiAdaptiveGovernanceDecision()" in controller,
        "public M8 state snapshot": "loadAiAdaptiveGovernanceState()" in controller,
        "M7 remains present": "class AiValueAttributionEngine" in m7,
        "50 harmful regression": "fiftyConsistentlyHarmfulAiSamplesDisableCloud" in tests,
        "49 insufficient regression": "fortyNineHarmfulSamplesAreStillInsufficient" in tests,
        "single-regime span regression": "concentratedOneDayEvidenceCannotDisableAi" in tests,
        "Sol-only disable regression": "harmfulSolCanBeDisabledWithoutDisablingLuna" in tests,
        "positive evidence no expansion regression": "positiveEvidenceNeverAutoExpandsAi" in tests,
        "disabled cloud no re-enable regression": "disabledCloudIsNeverAutomaticallyReenabled" in tests,
        "outlier clipping regression": "extremeOutlierRateIsClippedBeforeConfidenceCalculation" in tests,
        "fallback exclusion regression": "fallbackCurrentTickerResolutionIsNotHighIntegrityEvidence" in tests,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        raise SystemExit("M8 adaptive-governance verification failed: " + ", ".join(failed))

    print("\nPASS | M8 statistically-gated defensive AI governance contracts satisfied.")


if __name__ == "__main__":
    main()
