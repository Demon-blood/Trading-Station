#!/usr/bin/env python3
from pathlib import Path
import os
import sys

PAYLOAD_FILES = [
    "app/src/main/java/com/ksp/cryptobot/observability/M23RemoteOperations.kt",
    "app/src/main/java/com/ksp/cryptobot/observability/M23DecisionLineage.kt",
    "app/src/main/java/com/ksp/cryptobot/observability/M23Observability.kt",
    "app/src/main/java/com/ksp/cryptobot/observability/M23DiagnosticBundleExporter.kt",
    "app/src/test/java/com/ksp/cryptobot/observability/M23RemoteOperationsPolicyTest.kt",
    "app/src/test/java/com/ksp/cryptobot/observability/M23RedactionAndLineageTest.kt",
    "app/src/test/java/com/ksp/cryptobot/observability/M23DiagnosticContractTest.kt",
]


def fail(message):
    raise SystemExit("ERROR | " + message)


def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected exactly one anchor, got {count}")
    return text.replace(old, new, 1)


def main():
    print("INFO | M23 applier revision v1.0")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")
    if not (repo / "tools/verify_m22_security_release_integrity.py").exists():
        fail("M22 prerequisite missing from main")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty M23 app target tree:\n" + dirty)

    payload = Path(__file__).resolve().parent / "m23_payload"
    for rel in PAYLOAD_FILES:
        src = payload / rel
        dst = repo / rel
        if not src.exists():
            fail(f"M23 payload missing: {rel}")
        if dst.exists():
            fail(f"M23 target unexpectedly already exists: {rel}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(src.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
        print("WRITE |", rel)

    # Kraken is the final LIVE submission boundary. The M23 pause/kill gate is
    # checked here as well as in BotController so another caller cannot bypass it.
    p = repo / "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        "import com.ksp.cryptobot.core.SymbolDiscoveryCandidate\n",
        "import com.ksp.cryptobot.core.SymbolDiscoveryCandidate\nimport com.ksp.cryptobot.observability.M23RemoteOperationsRuntime\n",
        "M23 Kraken remote-entry gate import",
    )
    t = replace_once(
        t,
        '''        if (request.side == OrderSide.BUY) {
            val securityGate = KrakenApiKeySecurityRuntime.gateForNewBuy(apiKey)
            if (!securityGate.first) {
                error("M22 Kraken API-key security gate blocks BUY: ${securityGate.second}")
            }
        }
        val rule = resolvePairRule(request.symbol)
''',
        '''        if (request.side == OrderSide.BUY) {
            val securityGate = KrakenApiKeySecurityRuntime.gateForNewBuy(apiKey)
            if (!securityGate.first) {
                error("M22 Kraken API-key security gate blocks BUY: ${securityGate.second}")
            }
            val remoteSafetyGate = M23RemoteOperationsRuntime.canSubmitNewEntry()
            if (!remoteSafetyGate.first) {
                error("M23 remote-operations safety gate blocks BUY: ${remoteSafetyGate.second}")
            }
        }
        val rule = resolvePairRule(request.symbol)
''',
        "M23 Kraken final BUY safety gate",
    )
    p.write_text(t.rstrip() + "\n", encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # BotController: replace legacy remote authority commands with authenticated,
    # replay-resistant safety operations and add decision/order lineage.
    p = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        "import com.ksp.cryptobot.execution.SmartOrderLifecycleManager\n",
        """import com.ksp.cryptobot.execution.SmartOrderLifecycleManager
import com.ksp.cryptobot.observability.M23DecisionLineageRuntime
import com.ksp.cryptobot.observability.M23DiagnosticBundleExporter
import com.ksp.cryptobot.observability.M23HealthSnapshotBuilder
import com.ksp.cryptobot.observability.M23RemoteOperationsPolicy
import com.ksp.cryptobot.observability.M23RemoteOperationsRuntime
""",
        "M23 BotController imports",
    )

    handler_start = t.find("    private suspend fun handleRemoteCommand(message: RemoteCommandMessage, settings: BotSettings): String {")
    remote_status_start = t.find("    private fun remoteStatus(): String {", handler_start)
    if handler_start < 0 or remote_status_start < 0:
        fail("M23 remote-command replacement anchors missing")
    new_handler = r'''    private suspend fun handleRemoteCommand(message: RemoteCommandMessage, settings: BotSettings): String {
        val raw = message.text.trim()
        if (!raw.startsWith("/cts", ignoreCase = true) && !raw.startsWith("!cts", ignoreCase = true)) {
            return "Ignored. Commands must start with /cts or !cts."
        }
        val tokens = raw.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        if (tokens.isEmpty()) return remoteHelp()
        tokens.removeAt(0)
        if (tokens.firstOrNull()?.equals("help", ignoreCase = true) == true || tokens.isEmpty()) return remoteHelp()

        val configuredPin = if (settings.remoteCommandRequirePin) settingsStore.remoteCommandPin().orEmpty() else ""
        val suppliedPin = if (settings.remoteCommandRequirePin) tokens.firstOrNull().orEmpty() else ""
        if (settings.remoteCommandRequirePin && tokens.isNotEmpty()) tokens.removeAt(0)

        val auth = M23RemoteOperationsRuntime.authenticateAndReserveMessage(
            source = message.source,
            messageId = message.id,
            sourceIdentity = message.chatId,
            suppliedPin = suppliedPin,
            configuredPin = configuredPin,
            requirePin = settings.remoteCommandRequirePin
        )
        if (!auth.accepted) {
            updateStatus("M23 remote command rejected from ${message.source}: ${auth.reason}", "WARN")
            return auth.reason
        }

        val command = tokens.firstOrNull()?.lowercase().orEmpty()
        if (command.isBlank()) return remoteHelp()
        M23RemoteOperationsRuntime.auditCommand(auth.sourceId, command, true, "Authenticated command dispatch", "DISPATCHED")

        if (command in M23RemoteOperationsPolicy.FORBIDDEN_AUTHORITY_COMMANDS) {
            val result = "Rejected by M23: remote commands cannot create trading authority, execute orders, resume LIVE, change mode/risk settings, or bypass safety gates."
            M23RemoteOperationsRuntime.auditCommand(auth.sourceId, command, false, "Remote trading authority is forbidden", "REJECTED")
            return result
        }

        return when (command) {
            "status" -> remoteStatus()
            "health" -> M23HealthSnapshotBuilder.build(appContext).toRemoteText()
            "settings" -> remoteSettings(settingsStore.load())
            "portfolio" -> remotePortfolio(settingsStore.load())
            "positions" -> remotePositions(settingsStore.load())
            "orders" -> remoteOrders(settingsStore.load())
            "diagnostics" -> runCatching {
                val bundle = M23DiagnosticBundleExporter.export(appContext)
                "M23 diagnostics created. file=${bundle.filePath}\nsha256=${bundle.archiveSha256}\nmanifestEntries=${bundle.manifestEntries}\nsizeBytes=${bundle.sizeBytes}"
            }.getOrElse { error ->
                "M23 diagnostics failed: ${error.message ?: error.javaClass.simpleName}"
            }
            "pause", "pause_entries" -> M23RemoteOperationsRuntime.pauseEntries(auth.sourceId)
            "kill", "kill_switch" -> M23RemoteOperationsRuntime.activateKillSwitch(auth.sourceId)
            "stop" -> M23RemoteOperationsRuntime.requestStop(auth.sourceId)
            "reconcile" -> M23RemoteOperationsRuntime.requestReconciliation(auth.sourceId)
            "refresh_market" -> M23RemoteOperationsRuntime.requestMarketRefresh(auth.sourceId)
            else -> {
                M23RemoteOperationsRuntime.auditCommand(auth.sourceId, command, false, "Unknown/not allowlisted command", "REJECTED")
                "Unknown or disallowed command: $command\n${remoteHelp()}"
            }
        }
    }

    private fun remoteHelp(): String = """
Crypto TradeStation M23 remote operations:
/cts <PIN> health
/cts <PIN> diagnostics
/cts <PIN> status
/cts <PIN> settings
/cts <PIN> portfolio
/cts <PIN> positions
/cts <PIN> orders
/cts <PIN> pause_entries
/cts <PIN> kill_switch
/cts <PIN> stop
/cts <PIN> reconcile
/cts <PIN> refresh_market
/cts help

Remote BUY/SELL/execute/resume/mode/risk/security/authority override commands are intentionally unavailable.
""".trimIndent()

'''
    t = t[:handler_start] + new_handler + t[remote_status_start:]

    t = replace_once(
        t,
        '''        val request = OrderRequest(
''',
        '''        M23DecisionLineageRuntime.recordCandidate(
            symbol = ticker.symbol,
            strategy = settings.strategyMode.name,
            mode = settings.mode.name,
            action = decision.finalAction.name,
            confidencePercent = decision.confidencePercent,
            marketPrice = ticker.lastPrice
        )
        val request = OrderRequest(
''',
        "M23 candidate lineage",
    )

    t = replace_once(
        t,
        '''            val dms = com.ksp.cryptobot.execution.KrakenDmsSafetyRuntime.canSubmitNewEntry(settings.mode)
            if (!dms.first) {
                updateStatus("LIVE entry blocked by Kraken DMS safety gate: ${dms.second}", "ERROR")
                return ExecutionAttemptResult(false)
            }
''',
        '''            val dms = com.ksp.cryptobot.execution.KrakenDmsSafetyRuntime.canSubmitNewEntry(settings.mode)
            if (!dms.first) {
                updateStatus("LIVE entry blocked by Kraken DMS safety gate: ${dms.second}", "ERROR")
                return ExecutionAttemptResult(false)
            }
            val m23RemoteSafety = M23RemoteOperationsRuntime.canSubmitNewEntry()
            if (!m23RemoteSafety.first) {
                updateStatus("LIVE entry blocked by M23 remote-operations safety gate: ${m23RemoteSafety.second}", "ERROR")
                return ExecutionAttemptResult(false)
            }
''',
        "M23 BotController entry gate",
    )

    t = replace_once(
        t,
        '''        updateStatus("Submitting ${settings.exchangeProvider} ${request.side} $orderModeLabel order: ${request.symbol}, notional≈${submittedNotionalEstimate.setScale(2, RoundingMode.DOWN)} $quoteAsset, qty=${request.quantity}, price=${request.limitPrice ?: "market"}, id=${request.clientOrderId}", "LIVE")
''',
        '''        M23DecisionLineageRuntime.recordOrderSubmission(
            symbol = request.symbol,
            strategy = settings.strategyMode.name,
            mode = settings.mode.name,
            action = request.side.name,
            orderType = request.orderType.name,
            clientOrderId = request.clientOrderId
        )
        updateStatus("Submitting ${settings.exchangeProvider} ${request.side} $orderModeLabel order: ${request.symbol}, notional≈${submittedNotionalEstimate.setScale(2, RoundingMode.DOWN)} $quoteAsset, qty=${request.quantity}, price=${request.limitPrice ?: "market"}, id=${request.clientOrderId}", "LIVE")
''',
        "M23 order-submission lineage",
    )

    t = replace_once(
        t,
        '''        if (fillConfirmed) {
            dao.insertTrade(
''',
        '''        M23DecisionLineageRuntime.recordOrderResult(
            symbol = result.symbol,
            clientOrderId = request.clientOrderId,
            exchangeOrderId = result.exchangeOrderId,
            side = result.side.name,
            fillConfirmed = fillConfirmed,
            realizedPnlQuote = realizedPnlForRecord
        )
        if (fillConfirmed) {
            dao.insertTrade(
''',
        "M23 fill lineage",
    )
    p.write_text(t.rstrip() + "\n", encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # Advanced execution already has a single event recorder for M5/M20/portfolio/etc.
    # Reuse that seam instead of creating duplicate persistence.
    p = repo / "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        "import com.ksp.cryptobot.research.ResearchExecutionRuntime\n",
        "import com.ksp.cryptobot.research.ResearchExecutionRuntime\nimport com.ksp.cryptobot.observability.M23DecisionLineageRuntime\n",
        "M23 advanced-execution lineage import",
    )
    t = replace_once(
        t,
        '''        governanceDao.insertAdvancedExecution(AdvancedExecutionEventEntity(
''',
        '''        M23DecisionLineageRuntime.recordAdvancedExecution(
            eventType = eventType,
            symbol = symbol,
            strategy = settings.strategyMode.name,
            mode = mode,
            requested = requested,
            final = final,
            metric = multiplier,
            orderType = orderType,
            category = category,
            blocked = blocked,
            reason = reason
        )
        governanceDao.insertAdvancedExecution(AdvancedExecutionEventEntity(
''',
        "M23 advanced-execution lineage hook",
    )
    p.write_text(t.rstrip() + "\n", encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # Foreground service owns lifecycle/recovery orchestration. Remote command requests
    # are consumed here and routed through the already authoritative recovery methods.
    p = repo / "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        "import com.ksp.cryptobot.status.BotStatusStore\n",
        """import com.ksp.cryptobot.status.BotStatusStore
import com.ksp.cryptobot.observability.M23RemoteOperationsRuntime
import com.ksp.cryptobot.observability.M23ServiceRuntime
""",
        "M23 service imports",
    )
    t = replace_once(
        t,
        '''    override fun onCreate() {
        super.onCreate()
        controller = BotController(applicationContext)
''',
        '''    override fun onCreate() {
        super.onCreate()
        M23RemoteOperationsRuntime.initialize(applicationContext)
        M23ServiceRuntime.starting("BotForegroundService.onCreate")
        controller = BotController(applicationContext)
''',
        "M23 service runtime initialization",
    )
    t = replace_once(
        t,
        '''            controller.start()

            var lastNetworkUsable = connectivity.snapshot.usable
''',
        '''            controller.start()
            M23ServiceRuntime.running("Controller started after authoritative startup reconciliation.")

            var lastNetworkUsable = connectivity.snapshot.usable
''',
        "M23 service running state",
    )
    t = replace_once(
        t,
        '''                    controller.processRemoteCommands(current)
                    if (!controller.running || !hostStore.snapshot().desiredRunning) break

                    val afterCommands = settingsStore.load()
''',
        '''                    controller.processRemoteCommands(current)

                    if (M23RemoteOperationsRuntime.consumeStopRequest()) {
                        hostStore.requestStop("Authenticated M23 remote STOP_ENGINE")
                        statusStore.write("Authenticated M23 remote STOP_ENGINE accepted. Shutting down safely.", "WARN")
                        M23ServiceRuntime.stopped("Authenticated remote STOP_ENGINE")
                        controller.stop()
                        break
                    }

                    if (M23RemoteOperationsRuntime.consumeMarketRefreshRequest()) {
                        statusStore.write("Authenticated M23 market refresh requested. Execution truth is UNKNOWN until reconciliation completes.", "WARN")
                        KrakenPrivateExecutionRegistry.markRecoveryUnknown("M23 remote market/private connection refresh requested.")
                        KrakenRealtimeMarketDataRegistry.stop()
                        KrakenPrivateExecutionRegistry.stop()
                        configureRealtimeMarketData(current, network.usable)
                        configurePrivateExecutionState(current, network.usable)
                        if (!reconcileAfterRecovery(settingsStore.load(), "m23-remote-market-refresh")) {
                            updateNotification("M23 market refresh: waiting for safe reconciliation")
                            delay(RECONCILIATION_RETRY_MS)
                            continue
                        }
                    }

                    if (M23RemoteOperationsRuntime.consumeReconciliationRequest()) {
                        KrakenPrivateExecutionRegistry.markRecoveryUnknown("M23 authenticated remote reconciliation requested.")
                        if (!reconcileAfterRecovery(settingsStore.load(), "m23-remote-command")) {
                            updateNotification("M23 reconciliation request did not reach safe truth")
                            delay(RECONCILIATION_RETRY_MS)
                            continue
                        }
                    }

                    if (!controller.running || !hostStore.snapshot().desiredRunning) break

                    val afterCommands = settingsStore.load()
''',
        "M23 service remote-operation consumption",
    )
    t = replace_once(
        t,
        '''    private fun stopBot() {
        statusStore.write("Stop requested. Trading host shutting down.", "WARN")
''',
        '''    private fun stopBot() {
        M23ServiceRuntime.stopped("Service stop requested")
        statusStore.write("Stop requested. Trading host shutting down.", "WARN")
''',
        "M23 stop state",
    )
    t = replace_once(
        t,
        '''    override fun onDestroy() {
        KrakenRealtimeMarketDataRegistry.stop()
''',
        '''    override fun onDestroy() {
        M23ServiceRuntime.stopped("BotForegroundService destroyed")
        KrakenRealtimeMarketDataRegistry.stop()
''',
        "M23 destroy state",
    )
    p.write_text(t.rstrip() + "\n", encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    actual = changed | untracked
    allowed = set(PAYLOAD_FILES) | {
        "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt",
        "app/src/main/java/com/ksp/cryptobot/core/BotController.kt",
        "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt",
        "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt",
    }
    if actual - allowed:
        fail("Unexpected M23 app changes: " + ",".join(sorted(actual - allowed)))
    if allowed - actual:
        fail("Expected M23 app changes missing: " + ",".join(sorted(allowed - actual)))

    print("PASS | M23 controlled observability/diagnostics/remote-operations diff.")


if __name__ == "__main__":
    main()
