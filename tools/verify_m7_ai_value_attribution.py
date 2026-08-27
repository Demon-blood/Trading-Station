#!/usr/bin/env python3
from pathlib import Path
import sys

def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""

def main():
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    entity = read(repo / "app/src/main/java/com/ksp/cryptobot/data/AiValueAttributionEntities.kt")
    engine = read(repo / "app/src/main/java/com/ksp/cryptobot/intelligence/AiValueAttributionEngine.kt")
    dao = read(repo / "app/src/main/java/com/ksp/cryptobot/data/GovernanceDao.kt")
    db = read(repo / "app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt")
    router = read(repo / "app/src/main/java/com/ksp/cryptobot/intelligence/OpenAiDecisionRouter.kt")
    controller = read(repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    advanced = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt")
    tests = read(repo / "app/src/test/java/com/ksp/cryptobot/intelligence/AiValueAttributionEngineTest.kt")
    canonical = read(repo / "tools/verify_canonical_v407.py")

    checks = {
        "M7 Room entity": 'tableName = "ai_value_attribution"' in entity,
        "fingerprint is durable primary key": "@PrimaryKey val fingerprint: String" in entity,
        "model path stored": "val modelPath: String" in entity,
        "deterministic path stored": "deterministicNetPnlQuote" in entity,
        "Luna path stored": "lunaNetPnlQuote" in entity,
        "Luna+Sol path stored": "finalNetPnlQuote" in entity,
        "AI cost stored": "totalAiCostQuote" in entity,
        "avoided loss stored": "avoidedLossQuote" in entity,
        "missed profit stored": "missedProfitQuote" in entity,
        "generated profit stored": "aiGeneratedProfitQuote" in entity,
        "Room version bumped to 12": "version = 12" in db,
        "canonical verifier accepts evolving Room schema": '"Room schema >= 11"' in canonical,
        "canonical verifier requires current migration chain": '"Room migration chain current"' in canonical and "MIGRATION_11_12" in canonical,
        "stale canonical Room-11-only check removed": 'audit.check("Room schema 11"' not in canonical,
        "explicit 11 to 12 migration": "MIGRATION_11_12 = object : Migration(11, 12)" in db,
        "migration registered": "MIGRATION_10_11, MIGRATION_11_12" in db,
        "M7 table migration": "CREATE TABLE IF NOT EXISTS ai_value_attribution" in db,
        "M7 indexes migration": "idx_ai_value_status_created" in db and "idx_ai_value_model_resolved" in db,
        "DAO upsert": "upsertAiAttribution" in dao,
        "DAO open counterfactual query": "openAiAttributionForSymbol" in dao,
        "DAO resolved attribution query": "resolvedAiAttributions" in dao,
        "counterfactual horizon four hours": "SHADOW_HORIZON_MINUTES = 240" in engine,
        "M1 path resolution": "Timeframe.M1, 720" in engine,
        "same candle stop first": "STOP_AND_TARGET_SAME_M1_CANDLE_CONSERVATIVE_STOP_FIRST" in engine,
        "target path resolution": "TARGET_HIT_M1_PATH" in engine,
        "stop path resolution": "STOP_HIT_M1_PATH" in engine,
        "horizon resolution": "HORIZON_M1_CLOSE" in engine,
        "deterministic vs Luna comparison": "lunaValueAddedQuote" in engine and "lunaNet.subtract(deterministic)" in engine,
        "Luna vs Sol incremental comparison": "solIncrementalValueQuote" in engine and "finalNet.subtract(lunaNet)" in engine,
        "total AI value comparison": "finalNet.subtract(deterministic)" in engine,
        "avoided loss classification": "deterministic < BigDecimal.ZERO" in engine and "avoidedLoss" in engine,
        "missed profit classification": "deterministic > BigDecimal.ZERO" in engine and "missedProfit" in engine,
        "AI ROI calculated": "val roi = ratio(aiValue, aiCost)" in engine,
        "Luna ROI calculated": "val lunaRoi = ratio(lunaValue, lunaCost)" in engine,
        "Sol incremental ROI calculated": "val solRoi = ratio(solValue, solCost)" in engine,
        "insufficient sample guard": 'rows.size < 20 -> "INSUFFICIENT_DATA"' in engine,
        "cloud disable recommendation": '"DISABLE_CLOUD_AI_RECOMMENDED"' in engine,
        "Sol disable recommendation": '"KEEP_LUNA_DISABLE_SOL_RECOMMENDED"' in engine,
        "router retains Luna verdict": "val lunaVerdict: CloudAiVerdict" in router and "lunaVerdict = luna.payload.verdict" in router,
        "router retains Luna multiplier": "val lunaRiskMultiplier: BigDecimal" in router and "lunaRiskMultiplier = luna.payload.riskMultiplier" in router,
        "BotController records cloud review": "aiValueAttribution.beginCloudReview(" in controller,
        "BotController settles due shadows": "aiValueAttribution.settleDueForSymbol(exchange, ticker)" in controller,
        "system verifier reports AI_COST": "AI_COST=${attribution.totalAiCostQuote" in controller,
        "system verifier reports AI_VALUE_ADDED": "AI_VALUE_ADDED=${attribution.aiValueAddedQuote" in controller,
        "system verifier reports AI_AVOIDED_LOSS": "AI_AVOIDED_LOSS=${attribution.avoidedLossQuote" in controller,
        "system verifier reports AI_MISSED_PROFIT": "AI_MISSED_PROFIT=${attribution.missedProfitQuote" in controller,
        "system verifier reports AI_ROI": "AI_ROI=${attribution.aiRoi" in controller,
        "public attribution snapshot API": "loadAiValueAttributionSummary()" in controller,
        "M5 economics linked to M7": "aiValueAttribution.linkExecutionEconomics(" in advanced,
        "M5 target and stop linked to M7": "assessment.expectedWinQuote.divide" in engine and "assessment.expectedLossQuote.divide" in engine,
        "pre-cloud deterministic quote preserved": "val deterministicQuoteBeforeCloud = finalQuote" in advanced,
        "post-downstream deterministic notional reconstructed": "comparableDeterministicQuote" in advanced,
        "M5 remains final execution gate": "M5 trade economics blocked entry" in advanced,
        "M6 AI remains veto/reduce only": "cloudReview.riskMultiplier.coerceIn(BigDecimal.ZERO, BigDecimal.ONE)" in advanced,
        "losing rejection regression": "rejectedLosingTradeCountsAsAvoidedLossAfterAiCost" in tests,
        "winning rejection regression": "rejectedWinnerCountsAsMissedProfit" in tests,
        "approval cost regression": "fullApprovalWithNoSizeChangeIsWorthNegativeApiCostOnly" in tests,
        "Sol incremental regression": "solIncrementalValueIsMeasuredAgainstLunaPath" in tests,
        "reject zero exposure regression": "rejectAlwaysForcesZeroExposureMultiplier" in tests,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        raise SystemExit("M7 AI-value attribution verification failed: " + ", ".join(failed))

    print("\nPASS | M7 AI value attribution and counterfactual contracts satisfied.")

if __name__ == "__main__":
    main()
