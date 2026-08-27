#!/usr/bin/env python3
from __future__ import annotations

import os
import sys
from pathlib import Path

NEW_ENGINE = "app/src/main/java/com/ksp/cryptobot/intelligence/AiAdaptiveGovernanceEngine.kt"
NEW_TEST = "app/src/test/java/com/ksp/cryptobot/intelligence/AiAdaptiveGovernanceEngineTest.kt"


def fail(message: str):
    raise SystemExit("ERROR | " + message)


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


def main():
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app/ tree:\n" + dirty)

    payload_root = Path(__file__).resolve().parent / "m8_payload"
    for rel in (NEW_ENGINE, NEW_TEST):
        source = payload_root / rel
        target = repo / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
        print("WRITE |", rel)

    controller_path = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    controller = controller_path.read_text(encoding="utf-8")

    controller = replace_once(
        controller,
        '''import com.ksp.cryptobot.intelligence.AiValueAttributionSummary\n''',
        '''import com.ksp.cryptobot.intelligence.AiValueAttributionSummary\nimport com.ksp.cryptobot.intelligence.AiAdaptiveGovernanceEngine\nimport com.ksp.cryptobot.intelligence.AiAdaptiveGovernanceDecision\nimport com.ksp.cryptobot.intelligence.AiAdaptiveGovernanceState\nimport com.ksp.cryptobot.intelligence.AiAdaptiveAction\n''',
        "BotController M8 imports"
    )

    controller = replace_once(
        controller,
        '''    private val aiValueAttribution = AiValueAttributionEngine(AppDatabase.get(appContext).governanceDao())\n    private val remoteAlertClient = RemoteAlertClient()\n''',
        '''    private val aiValueAttribution = AiValueAttributionEngine(AppDatabase.get(appContext).governanceDao())\n    private val aiAdaptiveGovernance = AiAdaptiveGovernanceEngine(\n        appContext,\n        AppDatabase.get(appContext).governanceDao(),\n        settingsStore\n    )\n    private val remoteAlertClient = RemoteAlertClient()\n''',
        "BotController M8 engine property"
    )

    controller = replace_once(
        controller,
        '''    suspend fun loadAiValueAttributionRows(limit: Int = 100): List<AiValueAttributionEntity> =\n        aiValueAttribution.recent(limit)\n\n    suspend fun sendTelegramTestAlert(settings: BotSettings = settingsStore.load()): Boolean {\n''',
        '''    suspend fun loadAiValueAttributionRows(limit: Int = 100): List<AiValueAttributionEntity> =\n        aiValueAttribution.recent(limit)\n\n    suspend fun loadAiAdaptiveGovernanceDecision(): AiAdaptiveGovernanceDecision =\n        aiAdaptiveGovernance.inspect()\n\n    fun loadAiAdaptiveGovernanceState(): AiAdaptiveGovernanceState =\n        aiAdaptiveGovernance.state()\n\n    suspend fun sendTelegramTestAlert(settings: BotSettings = settingsStore.load()): Boolean {\n''',
        "BotController M8 public snapshot methods"
    )

    old_settlement = '''                if (settledAiCounterfactuals > 0) {\n                    val attributionSummary = aiValueAttribution.summary()\n                    updateStatus(\n                        "[$symbol] M7 AI attribution resolved=$settledAiCounterfactuals. AI value=${attributionSummary.aiValueAddedQuote.setScale(4, RoundingMode.HALF_UP)}, avoided=${attributionSummary.avoidedLossQuote.setScale(4, RoundingMode.HALF_UP)}, missed=${attributionSummary.missedProfitQuote.setScale(4, RoundingMode.HALF_UP)}, ROI=${attributionSummary.aiRoi?.setScale(3, RoundingMode.HALF_UP) ?: "n/a"}, verdict=${attributionSummary.verdict}",\n                        if (attributionSummary.aiValueAddedQuote < BigDecimal.ZERO) "WARN" else "INFO"\n                    )\n                }\n'''
    new_settlement = '''                if (settledAiCounterfactuals > 0) {\n                    val attributionSummary = aiValueAttribution.summary()\n                    updateStatus(\n                        "[$symbol] M7 AI attribution resolved=$settledAiCounterfactuals. AI value=${attributionSummary.aiValueAddedQuote.setScale(4, RoundingMode.HALF_UP)}, avoided=${attributionSummary.avoidedLossQuote.setScale(4, RoundingMode.HALF_UP)}, missed=${attributionSummary.missedProfitQuote.setScale(4, RoundingMode.HALF_UP)}, ROI=${attributionSummary.aiRoi?.setScale(3, RoundingMode.HALF_UP) ?: "n/a"}, verdict=${attributionSummary.verdict}",\n                        if (attributionSummary.aiValueAddedQuote < BigDecimal.ZERO) "WARN" else "INFO"\n                    )\n\n                    val adaptiveDecision = runCatching {\n                        aiAdaptiveGovernance.evaluateAndApply()\n                    }.getOrNull()\n                    if (adaptiveDecision != null) {\n                        updateStatus(\n                            "[$symbol] M8 AI adaptive governance=${adaptiveDecision.action}. overallN=${adaptiveDecision.overall.samples}, overallUpper95=${adaptiveDecision.overall.upper95.setScale(5, RoundingMode.HALF_UP)}, solN=${adaptiveDecision.sol.samples}, solUpper95=${adaptiveDecision.sol.upper95.setScale(5, RoundingMode.HALF_UP)}, excluded=${adaptiveDecision.excludedLowIntegrityRows}. ${adaptiveDecision.reason.take(280)}",\n                            if (adaptiveDecision.action == AiAdaptiveAction.HOLD) "INFO" else "WARN"\n                        )\n                        if (adaptiveDecision.action != AiAdaptiveAction.HOLD) {\n                            sendRemoteAlert(\n                                settings,\n                                "AI adaptive governance",\n                                "${adaptiveDecision.action}: ${adaptiveDecision.reason}"\n                            )\n                        }\n                    }\n                }\n'''
    controller = replace_once(
        controller,
        old_settlement,
        new_settlement,
        "BotController M8 post-counterfactual adaptation"
    )

    verifier_anchor = '''        try {\n            V4SystemVerifier(appContext).verify(settings).forEach { check ->\n'''
    verifier_insert = '''        val adaptiveInspection = runCatching { aiAdaptiveGovernance.inspect() }.getOrNull()\n        if (adaptiveInspection == null) {\n            add("WARN", "AI Adaptive Governance", "Unable to inspect M8 adaptive-governance evidence.")\n        } else {\n            val adaptiveState = aiAdaptiveGovernance.state()\n            add(\n                "PASS",\n                "AI Adaptive Governance",\n                "action=${adaptiveInspection.action}, overallN=${adaptiveInspection.overall.samples}, overall95=[${adaptiveInspection.overall.lower95.setScale(5, RoundingMode.HALF_UP)},${adaptiveInspection.overall.upper95.setScale(5, RoundingMode.HALF_UP)}], solN=${adaptiveInspection.sol.samples}, sol95=[${adaptiveInspection.sol.lower95.setScale(5, RoundingMode.HALF_UP)},${adaptiveInspection.sol.upper95.setScale(5, RoundingMode.HALF_UP)}], excludedLowIntegrity=${adaptiveInspection.excludedLowIntegrityRows}, lastApplied=${adaptiveState.lastAction}. Inspection is read-only and makes no paid AI call."\n            )\n        }\n\n'''
    controller = replace_once(
        controller,
        verifier_anchor,
        verifier_insert + verifier_anchor,
        "BotController M8 read-only system verifier"
    )

    controller_path.write_text(controller, encoding="utf-8")
    print("PATCH |", controller_path.relative_to(repo))

    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    all_changed = changed | untracked
    allowed = {
        NEW_ENGINE,
        NEW_TEST,
        "app/src/main/java/com/ksp/cryptobot/core/BotController.kt",
    }
    unexpected = sorted(all_changed - allowed)
    missing = sorted(allowed - all_changed)
    if unexpected:
        fail("Unexpected M8 app changes: " + ", ".join(unexpected))
    if missing:
        fail("Expected M8 app changes missing: " + ", ".join(missing))

    print("PASS | M8 patch changed only approved adaptive-governance files.")


if __name__ == "__main__":
    main()
