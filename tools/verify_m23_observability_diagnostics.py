#!/usr/bin/env python3
from pathlib import Path
import sys


def read(path):
    p = Path(path)
    return p.read_text(encoding="utf-8") if p.exists() else ""


def main():
    print("INFO | M23 verifier revision v1.0")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    remote = read(repo / "app/src/main/java/com/ksp/cryptobot/observability/M23RemoteOperations.kt")
    lineage = read(repo / "app/src/main/java/com/ksp/cryptobot/observability/M23DecisionLineage.kt")
    health = read(repo / "app/src/main/java/com/ksp/cryptobot/observability/M23Observability.kt")
    diagnostics = read(repo / "app/src/main/java/com/ksp/cryptobot/observability/M23DiagnosticBundleExporter.kt")
    controller = read(repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    exchange = read(repo / "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt")
    service = read(repo / "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt")
    advanced = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt")
    database = read(repo / "app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt")
    remote_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/observability/M23RemoteOperationsPolicyTest.kt")
    lineage_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/observability/M23RedactionAndLineageTest.kt")
    diagnostic_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/observability/M23DiagnosticContractTest.kt")

    diagnostic_surface = health + "\n" + diagnostics + "\n" + lineage
    forbidden_diagnostic_getters = [
        "exchangeApiKey(",
        "exchangeSecretKey(",
        "openAiApiKey(",
        "telegramBotToken(",
        "telegramChatId(",
        "discordBotToken(",
        "discordWebhookUrl(",
        "remoteCommandPin(",
        "secureBackupMap(",
    ]

    checks = {
        "M22 prerequisite exists":
            (repo / "tools/verify_m22_security_release_integrity.py").exists(),

        "no Room schema bump":
            "version = 12" in database,

        "unified health model exists":
            "data class UnifiedHealthSnapshot" in health and
            "object M23HealthSnapshotBuilder" in health,

        "runtime connectivity recovery authority security sections exposed":
            all(term in health for term in [
                '"foreground_service_state"', '"public_ws_state"', '"private_ws_state"',
                '"recovery_fence"', '"fencing_token"', '"dms_state"', '"assessment"'
            ]),

        "market execution economics portfolio learning AI sections exposed":
            all(term in health for term in [
                '"microstructure"', '"execution_calibration"',
                '"latest_m5_expected_net_ev"', '"latest_m20_adjusted_net_ev"',
                '"portfolio_heat"', '"m19_drift_state"', '"model_path"'
            ]),

        "unknown security state is UNKNOWN not SAFE":
            'apiSecurity == null -> "UNKNOWN"' in health and
            '"unknownStatesAreSafe=false"' in health,

        "API key diagnostics use fingerprint only":
            '"key_fingerprint"' in health and
            "apiSecurity?.keyFingerprint" in health and
            "apiSecurity?.keyName" not in health,

        "diagnostic surface never calls raw secret getters":
            not any(token in diagnostic_surface for token in forbidden_diagnostic_getters),

        "diagnostic bundle is ZIP with manifest and SHA-256":
            "ZipOutputStream" in diagnostics and
            'files["manifest.json"]' in diagnostics and
            '"sha256"' in diagnostics and
            "archiveSha256" in diagnostics,

        "diagnostic manifest explicitly excludes secret classes":
            all(term in diagnostics for term in [
                '"rawKrakenApiKey", false', '"rawKrakenSecret", false',
                '"rawOpenAiApiKey", false', '"rawTelegramToken", false',
                '"rawDiscordSecret", false', '"rawRemoteCommandPin", false',
                '"rawSigningMaterial", false', '"rawAndroidKeystoreMaterial", false'
            ]),

        "decision lineage uses stable correlation IDs":
            "correlationId" in lineage and
            "correlationByClientOrderId" in lineage and
            'stage = "ORDER_SUBMISSION"' in lineage and
            'stage = if (fillConfirmed) "FILL"' in lineage,

        "M5 and M20 economics remain distinct":
            '"entry_economics"' in lineage and
            '"net_profit_optimizer"' in lineage and
            "m5ExpectedNetEvRate" in lineage and
            "m20AdjustedExpectedNetEvRate" in lineage,

        "advanced execution publishes M23 lineage":
            "M23DecisionLineageRuntime.recordAdvancedExecution" in advanced,

        "controller links candidate order and fill":
            "M23DecisionLineageRuntime.recordCandidate" in controller and
            "M23DecisionLineageRuntime.recordOrderSubmission" in controller and
            "M23DecisionLineageRuntime.recordOrderResult" in controller,

        "remote auth is constant-time and replay-resistant":
            "MessageDigest.isEqual" in remote and
            "Replay detected" in remote and
            "replay protection persistence failed" in remote.lower(),

        "remote audit includes required fields":
            all(term in remote for term in [
                "timestampEpochMs", "command", "sourceId", "accepted", "reason", "result"
            ]),

        "remote safe command allowlist is safety-oriented":
            all(term in remote for term in [
                '"health"', '"diagnostics"', '"pause_entries"', '"kill_switch"',
                '"stop"', '"reconcile"', '"refresh_market"'
            ]),

        "remote trading-authority commands are explicitly forbidden":
            all(term in remote for term in [
                '"execute"', '"force_buy"', '"force_sell"',
                '"ignore_risk"', '"ignore_security"', '"ignore_authority"'
            ]) and
            "FORBIDDEN_AUTHORITY_COMMANDS" in controller,

        "legacy remote execute/resume/mode mutation removed":
            '"execute" -> {' not in controller and
            '"resume" -> {' not in controller and
            '"mode" -> {' not in controller and
            '"set" -> remoteSet' not in controller,

        "pause and kill are monotonic entry blockers":
            'if (killSwitch) return false' in remote and
            'if (pauseNewEntries) return false' in remote and
            "protective/exit SELL" in remote,

        "Kraken final BUY boundary enforces M23 gate":
            "M23RemoteOperationsRuntime.canSubmitNewEntry()" in exchange and
            "M23 remote-operations safety gate blocks BUY" in exchange and
            "if (request.side == OrderSide.BUY)" in exchange,

        "protective SELL is not wrapped by M23 gate":
            exchange.count("M23RemoteOperationsRuntime.canSubmitNewEntry()") == 1,

        "controller exposes pre-network M23 gate reason":
            "LIVE entry blocked by M23 remote-operations safety gate" in controller,

        "service initializes M23 before trading host":
            "M23RemoteOperationsRuntime.initialize(applicationContext)" in service and
            service.index("M23RemoteOperationsRuntime.initialize(applicationContext)") < service.index("controller = BotController(applicationContext)"),

        "service consumes stop reconcile and market refresh requests":
            all(term in service for term in [
                "consumeStopRequest()", "consumeReconciliationRequest()", "consumeMarketRefreshRequest()",
                'reconcileAfterRecovery(settingsStore.load(), "m23-remote-command")',
                'reconcileAfterRecovery(settingsStore.load(), "m23-remote-market-refresh")'
            ]),

        "remote market refresh marks execution truth unknown first":
            "M23 remote market/private connection refresh requested." in service and
            service.index("M23 remote market/private connection refresh requested.") <
                service.index('reconcileAfterRecovery(settingsStore.load(), "m23-remote-market-refresh")'),

        "remote stop pauses entry before service shutdown":
            "persistSafetyLocked(pause = true" in remote and
            "pendingStop = true" in remote,

        "tests cover unknown pause kill and forbidden authority":
            "unknownRuntimeBlocksNewBuy" in remote_tests and
            "pauseAndKillSwitchBlockNewBuy" in remote_tests and
            "arbitraryTradingAuthorityCommandsAreRejected" in remote_tests,

        "tests cover redaction fingerprint and candidate-order-fill lineage":
            "diagnosticsTextRedactsSecretAssignments" in lineage_tests and
            "sanitizedIdentityIsFingerprintOnly" in lineage_tests and
            "candidateOrderAndFillShareCorrelationId" in lineage_tests and
            "m5AndM20EconomicsRemainDistinct" in lineage_tests,

        "tests cover checksum primitive":
            "sha256IsDeterministicAndContentSensitive" in diagnostic_tests,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        raise SystemExit("M23 observability/diagnostics/remote-operations verification failed: " + ", ".join(failed))

    print()
    print("PASS | M23 observability, sanitized diagnostics, lineage, replay-resistant remote safety operations and final BUY gate contracts satisfied.")


if __name__ == "__main__":
    main()
