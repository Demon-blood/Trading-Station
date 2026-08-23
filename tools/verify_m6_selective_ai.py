#!/usr/bin/env python3
from pathlib import Path
import sys

def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""

def main():
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    router = read(repo / "app/src/main/java/com/ksp/cryptobot/intelligence/OpenAiDecisionRouter.kt")
    settings = read(repo / "app/src/main/java/com/ksp/cryptobot/settings/AppSettingsStore.kt")
    main_ui = read(repo / "app/src/main/java/com/ksp/cryptobot/MainActivity.kt")
    controller = read(repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    advanced = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt")
    tests = read(repo / "app/src/test/java/com/ksp/cryptobot/intelligence/OpenAiDecisionRouterTest.kt")

    checks = {
        "Luna model id": 'const val LUNA_MODEL = "gpt-5.6-luna"' in router,
        "Sol model id": 'const val SOL_MODEL = "gpt-5.6-sol"' in router,
        "current Luna input price": 'LUNA_INPUT = BigDecimal("0.20")' in router,
        "current Luna cached price": 'LUNA_CACHED = BigDecimal("0.02")' in router,
        "current Luna output price": 'LUNA_OUTPUT = BigDecimal("1.20")' in router,
        "current Sol discounted input price": 'SOL_INPUT = BigDecimal("4.00")' in router,
        "current Sol cached price": 'SOL_CACHED = BigDecimal("0.40")' in router,
        "current Sol discounted output price": 'SOL_OUTPUT = BigDecimal("20.00")' in router,
        "cache write cost accounted": "cacheWriteTokens" in router and "LUNA_CACHE_WRITE" in router and "SOL_CACHE_WRITE" in router,
        "Responses API endpoint": 'https://api.openai.com/v1/responses' in router,
        "responses are not stored": '.put("store", false)' in router,
        "low reasoning effort": '.put("reasoning", JSONObject().put("effort", "low"))' in router,
        "strict structured output": '.put("type", "json_schema")' in router and '.put("strict", true)' in router,
        "bounded output tokens": '.put("max_output_tokens", 500)' in router,
        "veto/reduce policy": "Never create or strengthen a trade" in router,
        "non-buy cannot route": "decision.finalAction != SignalAction.BUY && decision.finalAction != SignalAction.SMALL_BUY" in router,
        "clear candidate zero-cost path": "clearlyDeterministic" in router,
        "Luna first": "shouldValidateWithLuna" in router and "LUNA_MODEL" in router,
        "Sol only escalation": "shouldEscalateToSol" in router and "SOL_MODEL" in router,
        "Sol daily cap": "maxSolCallsPerDay" in router and "solCallsToday" in router,
        "monthly API budget": "monthlyBudgetUsd" in router and "spentUsd" in router,
        "API errors preserve deterministic decision": "Deterministic decision preserved" in router,
        "reject vetoes": "CloudAiVerdict.REJECT -> base.copy" in router and "finalAction = SignalAction.WAIT" in router,
        "approval cannot promote": "CloudAiVerdict.REJECT ->" in router and "else -> base.copy(" in router,
        "risk multiplier clamped": "riskMultiplier.coerceIn(BigDecimal.ZERO, BigDecimal.ONE)" in router,
        "secure OpenAI key": 'secure.saveEncryptedString("openai_api_key"' in settings and 'secure.readEncryptedString("openai_api_key")' in settings,
        "cloud AI disabled by default": 'val enabled: Boolean = false' in settings,
        "default budget two dollars": 'val monthlyBudgetUsd: BigDecimal = BigDecimal("2.00")' in settings,
        "UI OpenAI key is password field": '"OpenAI API key' in main_ui and "PasswordVisualTransformation()" in main_ui,
        "UI cloud enable toggle": 'ToggleRow("Enable selective cloud AI"' in main_ui,
        "UI monthly budget": '"Monthly OpenAI API budget (USD)"' in main_ui,
        "UI Sol escalation toggle": 'ToggleRow("Allow rare GPT-5.6 Sol escalation"' in main_ui,
        "controller routes after production intelligence": "val deterministicDecision = productionResult.first" in controller and "cloudAiRouter.reviewIfUseful(" in controller,
        "system verifier makes no paid call": '"This check makes no paid API call."' in controller,
        "cloud AI not a LIVE startup dependency": "Selective Cloud AI Router" in controller and "cloudKeyConfigured" in controller,
        "M6 runtime size cap": "CloudAiRuntime.snapshotFor(decision)" in advanced and "cloudMultiplier" in advanced,
        "AI cannot increase final quote": "cloudReview.riskMultiplier.coerceIn(BigDecimal.ZERO, BigDecimal.ONE)" in advanced,
        "actual AI cost charged to M5": "externalDecisionCostQuote = cloudReview?.totalCostQuote ?: BigDecimal.ZERO" in advanced,
        "M5 economics remains final gate": "M5 trade economics blocked entry" in advanced,
        "WAIT promotion regression": "waitCannotBePromotedByCloudApproval" in tests,
        "approval no-upgrade regression": "approvalCannotUpgradeSmallBuyToBuy" in tests,
        "reject regression": "rejectionVetoesApprovedBuy" in tests,
        "Luna cost regression": "currentLunaCostMathIncludesCachedAndOutputTokens" in tests,
        "Sol cost escalation regression": "solIsMuchMoreExpensiveAndThereforeRare" in tests,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        raise SystemExit("M6 selective-AI verification failed: " + ", ".join(failed))

    print("\nPASS | M6 selective cloud-AI routing contracts satisfied.")

if __name__ == "__main__":
    main()
