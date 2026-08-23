#!/usr/bin/env python3
"""
Canonical committed-source verifier for Crypto TradeStation v4.0.7.

This consolidates the key contract checks that were previously spread through
the source-mutating v4 workflow. It never modifies source.
"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path


DEFAULT_NAME = "4.0.7"
DEFAULT_CODE = 112


class Audit:
    def __init__(self) -> None:
        self.failed: list[str] = []

    def check(self, name: str, condition: bool, detail: str = "") -> None:
        status = "PASS" if condition else "FAIL"
        suffix = f" | {detail}" if detail else ""
        print(f"{status} | {name}{suffix}")
        if not condition:
            self.failed.append(name)

    def finish(self) -> None:
        if self.failed:
            raise SystemExit(
                "Canonical source verification failed: " + ", ".join(self.failed)
            )
        print("\nPASS | committed canonical Android source satisfies v4.0.7 contracts.")


def text(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""


def main() -> None:
    root = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    app = root / "app/src/main/java/com/ksp/cryptobot"
    version_name = os.environ.get("CTS_VERSION_NAME", DEFAULT_NAME).strip()
    version_code = int(os.environ.get("CTS_VERSION_CODE", str(DEFAULT_CODE)).strip())
    audit = Audit()

    gradle = text(root / "app/build.gradle.kts")
    database = text(app / "data/AppDatabase.kt")
    release = text(app / "release/V4ReleaseInfo.kt")
    main_activity = text(app / "MainActivity.kt")
    controller = text(app / "core/BotController.kt")
    models = text(app / "core/Models.kt")
    settings = text(app / "settings/AppSettingsStore.kt")
    ui = text(app / "PreviewReplicaUi.kt")
    lifecycle = text(app / "lifecycle/TradeLifecycleManager.kt")
    gdelt = text(app / "news/GdeltNewsClient.kt")
    cloud_screen = text(app / "ui/CloudShareScreen.kt")
    cloud_provisioner = text(app / "cloudshare/CloudShareProvisioner.kt")
    worker = text(root / "app/src/main/assets/cloudshare_setup/cloudshare-worker.js")
    schema = text(root / "app/src/main/assets/cloudshare_setup/schema.sql")
    risk_safe = text(app / "governance/RiskBudgetAndSafeMode.kt")
    prod_intel = text(app / "governance/ProductionIntelligenceEngine.kt")
    exec_guard = text(app / "execution/ExecutionGuard.kt")
    learning = text(app / "learning/TrueSelfLearningEngine.kt")
    verifier = text(app / "release/V4SystemVerifier.kt")
    min_policy = text(app / "execution/ExchangeMinimumOrderPolicy.kt")

    marker_path = root / "app/.cts-canonical-v407.json"
    marker_ok = False
    marker_detail = "missing"
    if marker_path.exists():
        try:
            marker = json.loads(marker_path.read_text(encoding="utf-8"))
            marker_ok = (
                marker.get("canonical_source") is True
                and marker.get("version_name") == version_name
                and int(marker.get("version_code", -1)) == version_code
            )
            marker_detail = (
                f"source={marker.get('source_commit')} run={marker.get('workflow_run_id')}"
            )
        except Exception as exc:
            marker_detail = f"invalid marker: {exc}"
    audit.check("canonical materialization marker", marker_ok, marker_detail)

    # Release identity / build basics.
    audit.check("applicationId com.ksp.cryptobot", 'applicationId = "com.ksp.cryptobot"' in gradle)
    audit.check("versionName", f'versionName = "{version_name}"' in gradle, version_name)
    audit.check("versionCode", f"versionCode = {version_code}" in gradle, str(version_code))
    audit.check(
        "V4ReleaseInfo identity",
        f'VERSION_NAME = "{version_name}"' in release
        and f"VERSION_CODE = {version_code}" in release,
    )
    audit.check("JUnit 4 dependency committed", 'testImplementation("junit:junit:4.13.2")' in gradle)
    audit.check("Room schema 11", "version = 11" in database)
    audit.check(
        "non-destructive Room",
        re.search(r"(?m)^\s*\.fallbackToDestructiveMigration\s*\(", database) is None,
    )

    # Materialized systems.
    required_files = [
        "PreviewReplicaUi.kt",
        "release/V4ReleaseInfo.kt",
        "execution/ExchangeMinimumOrderPolicy.kt",
        "execution/ProtectiveStopManager.kt",
        "research/ResearchHandoffEngine.kt",
        "news/NewsProviderHealth.kt",
        "ui/CloudShareScreen.kt",
        "cloudshare/CloudShareProvisioner.kt",
    ]
    for rel in required_files:
        audit.check(f"materialized {rel}", (app / rel).exists())

    # Exchange minimum order contracts.
    min_tests = text(root / "app/src/test/java/com/ksp/cryptobot/execution/ExchangeMinimumOrderPolicyTest.kt")
    audit.check("Kraken ordermin policy", "minOrderSize" in min_policy and "requiredMinimumQuantity" in min_policy)
    audit.check("Kraken costmin policy", "minOrderCost" in min_policy and "COST_MIN_BUFFER" in min_policy)
    audit.check(
        "pre-risk safe minimum sizing",
        "maxSpendableForExchangeMinimum" in controller
        and "allowUpsizeToMinimum = true" in controller,
    )
    audit.check(
        "post-risk no-force-upsize",
        "exchangeMinimumAfterRisk" in controller
        and "allowUpsizeToMinimum = false" in controller,
    )
    audit.check("final pre-submit minimum defense", "finalExchangeMinimumCheck" in controller)
    audit.check("minimum rejection spam guard", "deterministicMinimumRejection" in controller)
    audit.check("NEAR minimum regression", "nearEurThreePointSixSixIsRaisedToFourWhenSafe" in min_tests)
    audit.check("hard cap regression", "exchangeMinimumDoesNotOverrideHardRiskCap" in min_tests)
    audit.check("reserved cash regression", "exchangeMinimumDoesNotSpendReservedCash" in min_tests)

    # GDELT request/cache contract.
    audit.check("GDELT global request spacing", "MIN_REMOTE_INTERVAL_MS = 6_000L" in gdelt)
    audit.check("GDELT per-symbol cache", "CACHE_TTL_MS = 15L * 60L * 1000L" in gdelt)
    audit.check("GDELT non-blocking scan", "return@withContext decision.second.orEmpty()" in gdelt)
    audit.check("GDELT bounded cache", "MAX_CACHE_SYMBOLS = 500" in gdelt)
    audit.check("GDELT stale fallback", "STALE_CACHE_MAX_AGE_MS" in gdelt)

    # CloudShare setup/guided assistant contracts.
    audit.check("CloudShare create wizard", "Provision CloudShare Automatically" in cloud_screen)
    audit.check("CloudShare join wizard", "Register This Device" in cloud_screen)
    audit.check("CloudShare repair wizard", "Run Full CloudShare Test" in cloud_screen)
    audit.check("CloudShare manage wizard", "Create Invitation" in cloud_screen)
    audit.check(
        "Cloudflare token cleared",
        "tokenForAttempt" in cloud_screen
        and "finally" in cloud_screen
        and 'cloudflareToken = ""' in cloud_screen,
    )
    audit.check(
        "Cloudflare token not persisted",
        "saveCloudflare" not in cloud_screen and "saveCloudflare" not in cloud_provisioner,
    )
    audit.check("CloudShare D1 API", "/d1/database" in cloud_provisioner)
    audit.check("CloudShare R2 API", "/r2/buckets" in cloud_provisioner)
    audit.check("CloudShare Worker upload API", "/workers/scripts/" in cloud_provisioner and "MultipartBody" in cloud_provisioner)
    audit.check("CloudShare D1 binding", '"type", "d1"' in cloud_provisioner)
    audit.check("CloudShare R2 binding", '"type", "r2_bucket"' in cloud_provisioner)
    audit.check("CloudShare owner token encrypted", "store.saveAdminToken(adminToken)" in cloud_provisioner)
    audit.check("CloudShare first-device registration", "engine.register(firstInvite)" in cloud_provisioner)
    audit.check("CloudShare health endpoint", "/v1/health" in worker)
    audit.check("CloudShare register endpoint", "/v1/register" in worker)
    audit.check("CloudShare event upload endpoint", "/v1/events/batch" in worker)
    audit.check("CloudShare intelligence endpoint", "/v1/intelligence/events" in worker)
    audit.check("CloudShare bootstrap endpoint", "/v1/bootstrap" in worker)
    audit.check("CloudShare admin endpoints", "/v1/admin/invites" in worker and "/v1/admin/clients" in worker)
    audit.check(
        "CloudShare D1 schema",
        "CREATE TABLE IF NOT EXISTS invites" in schema
        and "CREATE TABLE IF NOT EXISTS clients" in schema
        and "CREATE TABLE IF NOT EXISTS events" in schema,
    )
    audit.check(
        "CloudShare guided steps",
        all(
            item in cloud_screen
            for item in [
                "CreateStep.WELCOME",
                "CreateStep.TOKEN",
                "CreateStep.ACCOUNT",
                "CreateStep.REVIEW",
                "CreateStep.PROVISIONING",
                "CreateStep.COMPLETE",
                "JoinStep.WORKER",
                "JoinStep.INVITE",
                "JoinStep.OPTIONS",
                "JoinStep.COMPLETE",
            ]
        ),
    )
    audit.check("CloudShare step counter", "Step $step of $total" in cloud_screen)
    audit.check("CloudShare token verification", "verifyProvisioningToken" in cloud_screen and "verifyProvisioningToken" in cloud_provisioner)
    audit.check("CloudShare permission verification", "verifyProvisioningAccess" in cloud_screen and "verifyProvisioningAccess" in cloud_provisioner)

    # Diagnostics and preview integration.
    audit.check("System segment works", "if (it == 1) onOpen(AppTab.SYSTEM_TEST)" in ui)
    audit.check("Backup segment works", "if (it == 0) onOpen(AppTab.BACKUP)" in ui)
    audit.check("diagnostics folder picker", "Select Diagnostics Folder" in ui)
    audit.check("diagnostics export", "Run & Save Full Diagnostics" in ui)
    audit.check("diagnostics path persistence", "diagnosticsDirectoryPath()" in settings)
    audit.check("diagnostics exporter", "exportFullDiagnosticsToFile" in controller)
    audit.check("diagnostics secret redaction", "secrets=EXCLUDED_AND_REDACTED" in controller)
    audit.check("diagnostics portfolio section", "[PORTFOLIO]" in controller)
    audit.check("diagnostics positions section", "[LIFECYCLE_POSITIONS]" in controller)
    audit.check("diagnostics orders section", "[OPEN_ORDERS]" in controller)
    audit.check("diagnostics trades section", "[RECENT_TRADES]" in controller)
    audit.check("diagnostics provider health section", "[NEWS_PROVIDER_HEALTH]" in controller)
    audit.check("MainActivity diagnostics wiring", "diagnosticsDirectoryPath = store.diagnosticsDirectoryPath()" in main_activity)

    audit.check("fixed bottom navigation", "PreviewBottomNavigation(currentTab = currentTab" in main_activity)
    audit.check("compact top bar", "PreviewAppTopBar(" in main_activity)
    audit.check("dashboard preview", "AppTab.DASHBOARD -> PreviewDashboardScreen" in main_activity)
    audit.check("portfolio preview", "AppTab.PORTFOLIO -> PreviewPortfolioScreen" in main_activity)
    audit.check("AI preview", "AppTab.AI -> PreviewAiHubScreen" in main_activity)
    audit.check("News preview", "AppTab.NEWS -> PreviewNewsScreen" in main_activity)
    audit.check("Settings preview", "AppTab.SETTINGS -> PreviewSettingsScreen" in main_activity)
    audit.check("System Test preview", "AppTab.SYSTEM_TEST -> PreviewSystemTestScreen" in main_activity)
    audit.check("Positions preview", "AppTab.POSITIONS -> PreviewPositionsScreen" in main_activity)
    audit.check("Backup preview", "AppTab.BACKUP -> PreviewBackupRecoveryScreen" in main_activity)
    audit.check("AI signal detail preview", "AppTab.AI_SIGNAL_DETAIL -> PreviewAiSignalDetailScreen" in main_activity)
    audit.check("area sparkline", "PreviewAreaSparkline" in ui)
    audit.check("allocation donut", "AssetAllocationDonut" in ui)
    audit.check("confidence gauge", "PreviewConfidenceGauge" in ui)
    audit.check("material icons extended", "material-icons-extended" in gradle)

    # Trading lifecycle / integration semantics from prior gates.
    audit.check(
        "AVOID is not SELL",
        "explicitSignalSell = isExplicitLifecycleSell(decision)" in lifecycle
        and "SignalAction.AVOID ||" not in lifecycle,
    )
    audit.check("soft signal churn protection", "Soft SELL deferred to prevent churn" in lifecycle)
    audit.check(
        "same-scan buy/sell context",
        "enteredSymbolsThisScan" in controller and "exitedSymbolsThisScan" in controller,
    )
    audit.check(
        "confirmed-fill lifecycle accounting",
        "Lifecycle SELL accepted without confirmed fill" in lifecycle,
    )
    audit.check(
        "reopened position entry reset",
        "latestBuyIsNewLifecycle" in lifecycle and "reopenedByLatestBuy" in lifecycle,
    )
    audit.check("provider health belongs to News", "NewsProviderHealthRegistry.snapshot()" in ui)
    audit.check("verified settings save/reload", "lastSaveVerification()" in settings and "return exactMatch" in settings)

    # Persistence audit for safety/execution critical BotSettings.
    bot_match = re.search(r"data class BotSettings\((.*?)\n\)", models, re.S)
    if bot_match and "fun load(): BotSettings" in settings and "fun save(" in settings:
        model_block = bot_match.group(1)
        fields = re.findall(r"(?m)^\s*val\s+([A-Za-z][A-Za-z0-9_]*)\s*:", model_block)
        load_block = settings.split("fun load(): BotSettings", 1)[1].split("fun save(", 1)[0]
        if "fun save(settings: BotSettings): Boolean" in settings:
            save_block = settings.split("fun save(settings: BotSettings): Boolean", 1)[1].split("fun lastSaveVerification", 1)[0]
        else:
            save_block = settings.split("fun save(settings: BotSettings)", 1)[1].split("fun lastSaveVerification", 1)[0]
        missing_load = [f for f in fields if re.search(r"\b" + re.escape(f) + r"\s*=", load_block) is None]
        missing_save = [f for f in fields if ("settings." + f) not in save_block]
        critical = {
            "mode", "exchangeProvider", "liveTradingAcknowledged", "maxPositionEur",
            "maxDailyLossEur", "maxTradesPerDay", "maxTradesPerHour",
            "maxSimultaneousLivePositions", "cooldownAfterBuyMinutes",
            "cooldownAfterSellMinutes", "forceSellOnBearishSignal",
            "liveLifecycleManagerEnabled", "autoExitManagerEnabled",
            "autoStopLossEnabled", "autoTakeProfitEnabled", "useNewsAi",
            "trueSelfLearningEnabled", "enableAutoSafeMode",
        }
        critical_missing = sorted((set(missing_load) | set(missing_save)) & critical)
        audit.check(
            "critical BotSettings round-trip persistence",
            not critical_missing,
            "missing=" + ",".join(critical_missing) if critical_missing else f"fields={len(fields)}",
        )
    else:
        audit.check("critical BotSettings round-trip persistence", False, "could not parse BotSettings/store")

    # Governance / production safety markers.
    audit.check("safe mode causative errors", "causativeErrorTypes" in risk_safe)
    audit.check("entry-only production governors", "entryOnlyGovernorsBlock" in prod_intel)
    audit.check("entry-only safe-mode execution guard", "productionSafeModeBlocks" in exec_guard)
    audit.check("completed learning outcomes only", "completedOutcomeTradesForLearning" in learning)
    audit.check("end-to-end v4 verifier", "End-to-end wiring evidence" in verifier)

    # The new normal build must never regenerate source.
    canonical_workflow = text(root / ".github/workflows/android-canonical-build.yml")
    audit.check(
        "canonical workflow is source-immutable",
        ".cts-v4-migration/apply_" not in canonical_workflow
        and "canonicalize_v407.py" not in canonical_workflow,
    )

    audit.finish()


if __name__ == "__main__":
    main()
