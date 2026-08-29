#!/usr/bin/env python3
from pathlib import Path
import os, sys

NEW_FILES = [
    "app/src/main/java/com/ksp/cryptobot/execution/ExecutionTruthGate.kt",
    "app/src/main/java/com/ksp/cryptobot/exchange/KrakenDurableExecutionQuarantine.kt",
    "app/src/test/java/com/ksp/cryptobot/execution/ExecutionTruthGateTest.kt",
    "app/src/test/java/com/ksp/cryptobot/exchange/KrakenDurableSubmissionCodecTest.kt",
]

def fail(msg):
    raise SystemExit("ERROR | " + msg)

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

def main():
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app tree:\n" + dirty)

    payload = Path(__file__).resolve().parent / "m11_payload"
    for rel in NEW_FILES:
        src = payload / rel
        dst = repo / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(src.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
        print("WRITE |", rel)

    p = repo / "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''        val balances = runCatching { exchange.getPortfolioBalances() }.getOrElse { emptyList() }
        val openOrders = runCatching { exchange.getOpenOrders() }.getOrElse { emptyList() }
''',
        '''        val balances = ExecutionTruthGate.requireAuthoritative(
            "portfolio balances",
            runCatching { exchange.getPortfolioBalances() }
        )
        val openOrders = ExecutionTruthGate.requireAuthoritative(
            "open orders",
            runCatching { exchange.getOpenOrders() }
        )
''',
        "M11 authoritative reconciliation snapshots"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''    @Volatile var running: Boolean = false
        private set


    suspend fun loadAiValueAttributionSummary(): AiValueAttributionSummary =
''',
        '''    @Volatile var running: Boolean = false
        private set

    init {
        KrakenPrivateExecutionRegistry.initialize(appContext)
    }

    suspend fun loadAiValueAttributionSummary(): AiValueAttributionSummary =
''',
        "M11 durable Kraken initialization"
    )

    anchor = '''    suspend fun loadLifecycleSnapshot(settings: BotSettings = settingsStore.load()): LifecycleSnapshot {
'''
    strict_method = '''    suspend fun reconcileLiveExecutionState(
        settings: BotSettings = settingsStore.load()
    ): com.ksp.cryptobot.execution.ReconciliationSummary {
        if (settings.mode != BotMode.LIVE_AUTO && settings.mode != BotMode.LIVE_CONFIRM) {
            return com.ksp.cryptobot.execution.ReconciliationSummary(0, 0, 0, emptyList())
        }
        val exchange = createExchange(settings)
        lifecycleManager.runPreScanMaintenance(settings, exchange)
        val reconciliation = advancedExecution.reconcileLive(settings, exchange)
        if (settings.exchangeProvider == ExchangeProvider.KRAKEN) {
            KrakenPrivateExecutionRegistry.markRestReconciled(reconciliation.openOrders)
        }
        updateStatus(
            "Strict live execution reconciliation passed: adjusted=${reconciliation.adjusted}, removed=${reconciliation.removed}, openOrders=${reconciliation.openOrders}.",
            if (reconciliation.removed > 0) "WARN" else "INFO"
        )
        return reconciliation
    }

'''
    if anchor not in t:
        fail("M11 strict reconcile insertion anchor missing")
    t = t.replace(anchor, strict_method + anchor, 1)
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''            if (!reconcileAfterRecovery(startSettings, "startup:$recoveryReason")) {
                updateNotification("Waiting for safe exchange reconciliation")
            }

            controller.start()
''',
        '''            var startupReconciled = reconcileAfterRecovery(startSettings, "startup:$recoveryReason")
            while (isActive && hostStore.snapshot().desiredRunning && !startupReconciled) {
                hostStore.recovery("STARTUP_RECONCILIATION_BLOCKED:$recoveryReason")
                updateNotification("Waiting for safe exchange reconciliation")
                delay(RECONCILIATION_RETRY_MS)
                if (!connectivity.refresh().usable) {
                    if (!awaitUsableNetwork("startup-reconciliation")) return@launch
                }
                startupReconciled = reconcileAfterRecovery(
                    settingsStore.load(),
                    "startup-retry:$recoveryReason"
                )
            }
            if (!isActive || !hostStore.snapshot().desiredRunning || !startupReconciled) {
                statusStore.write("Trading controller was not started because authoritative startup reconciliation never completed.", "ERROR")
                return@launch
            }

            controller.start()
''',
        "M11 startup fail-closed loop"
    )

    t = replace_once(
        t,
        '''            val openOrders = controller.loadOpenOrdersSnapshot(settings)
            val lifecycle = controller.loadLifecycleSnapshot(settings)
            val portfolio = controller.loadPortfolioSnapshot(settings)
            if (settings.exchangeProvider == ExchangeProvider.KRAKEN) {
                KrakenPrivateExecutionRegistry.markRestReconciled(openOrders.size)
            }
''',
        '''            val executionTruth = controller.reconcileLiveExecutionState(settings)
            val openOrders = controller.loadOpenOrdersSnapshot(settings)
            require(openOrders.size == executionTruth.openOrders) {
                "Open-order diagnostics disagree with strict execution truth: diagnostics=${openOrders.size}, authoritative=${executionTruth.openOrders}"
            }
            val lifecycle = controller.loadLifecycleSnapshot(settings)
            val portfolio = controller.loadPortfolioSnapshot(settings)
            if (settings.exchangeProvider == ExchangeProvider.KRAKEN) {
                KrakenPrivateExecutionRegistry.markRestReconciled(openOrders.size)
            }
''',
        "M11 strict recovery truth"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/exchange/KrakenPrivateExecutionState.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''package com.ksp.cryptobot.exchange

import com.ksp.cryptobot.core.OrderSide
''',
        '''package com.ksp.cryptobot.exchange

import android.content.Context
import com.ksp.cryptobot.core.OrderSide
''',
        "M11 registry Context import"
    )
    t = replace_once(
        t,
        '''    private val pending = linkedMapOf<String, PendingSubmission>()
    private val ambiguous = linkedMapOf<String, PendingSubmission>()

    fun start(newApiKey: String, newSecretKey: String) {
''',
        '''    private val pending = linkedMapOf<String, PendingSubmission>()
    private val ambiguous = linkedMapOf<String, PendingSubmission>()

    fun initialize(context: Context) {
        KrakenDurableExecutionQuarantine.initialize(context)
        synchronized(lock) {
            restoreDurableQuarantineLocked()
        }
    }

    private fun restoreDurableQuarantineLocked() {
        KrakenDurableExecutionQuarantine.unresolved().forEach { row ->
            val id = KrakenClientOrderId.normalize(row.clientOrderId)
            ambiguous[id] = PendingSubmission(
                clientOrderId = id,
                symbol = normalizeSymbol(row.symbol),
                side = row.side,
                startedAtEpochMs = row.startedAtEpochMs,
                reason = "Recovered unresolved ${row.status} across process boundary: ${row.reason}".take(300)
            )
        }
        if (ambiguous.isNotEmpty()) {
            lastError = "Recovered ${ambiguous.size} unresolved Kraken AddOrder intent(s) from durable quarantine."
        }
    }

    fun start(newApiKey: String, newSecretKey: String) {
''',
        "M11 durable quarantine restore"
    )
    t = replace_once(
        t,
        '''            apiKey = newApiKey
            secretKey = newSecretKey
            enabled = true
''',
        '''            apiKey = newApiKey
            secretKey = newSecretKey
            enabled = true
            restoreDurableQuarantineLocked()
''',
        "M11 restore quarantine on registry start"
    )
    t = replace_once(
        t,
        '''            pending[id] = PendingSubmission(
                clientOrderId = id,
                symbol = normalizeSymbol(symbol),
                side = side,
                startedAtEpochMs = System.currentTimeMillis()
            )
''',
        '''            val startedAt = System.currentTimeMillis()
            pending[id] = PendingSubmission(
                clientOrderId = id,
                symbol = normalizeSymbol(symbol),
                side = side,
                startedAtEpochMs = startedAt
            )
            KrakenDurableExecutionQuarantine.markPending(
                clientOrderId = id,
                symbol = normalizeSymbol(symbol),
                side = side,
                startedAtEpochMs = startedAt
            )
''',
        "M11 persist pending before transport"
    )
    t = replace_once(
        t,
        '''            pending.remove(id)
            ambiguous.remove(id)
            lastError = ""
''',
        '''            pending.remove(id)
            ambiguous.remove(id)
            KrakenDurableExecutionQuarantine.clear(id)
            lastError = ""
''',
        "M11 clear durable on acknowledgement"
    )
    t = replace_once(
        t,
        '''            val item = pending.remove(id) ?: return
            ambiguous[id] = item.copy(reason = reason.take(300))
            lastError = "Ambiguous AddOrder result for $id: ${reason.take(200)}"
''',
        '''            val item = pending.remove(id) ?: return
            ambiguous[id] = item.copy(reason = reason.take(300))
            KrakenDurableExecutionQuarantine.markAmbiguous(id, reason)
            lastError = "Ambiguous AddOrder result for $id: ${reason.take(200)}"
''',
        "M11 persist ambiguous result"
    )
    t = replace_once(
        t,
        '''            pending.remove(id)
            ambiguous.remove(id)
        }
    }

    fun canSubmitNewEntry''',
        '''            pending.remove(id)
            ambiguous.remove(id)
            KrakenDurableExecutionQuarantine.clear(id)
        }
    }

    fun canSubmitNewEntry''',
        "M11 clear durable explicit submission"
    )
    t = replace_once(
        t,
        '''                if (report.clientOrderId.isNotBlank()) {
                    pending.remove(report.clientOrderId)
                    ambiguous.remove(report.clientOrderId)
                }
''',
        '''                if (report.clientOrderId.isNotBlank()) {
                    pending.remove(report.clientOrderId)
                    ambiguous.remove(report.clientOrderId)
                    KrakenDurableExecutionQuarantine.clear(report.clientOrderId)
                }
''',
        "M11 clear durable from execution report"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    actual = changed | untracked
    allowed = set(NEW_FILES) | {
        "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt",
        "app/src/main/java/com/ksp/cryptobot/core/BotController.kt",
        "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt",
        "app/src/main/java/com/ksp/cryptobot/exchange/KrakenPrivateExecutionState.kt",
    }
    if actual - allowed:
        fail("Unexpected M11 app changes: " + ",".join(sorted(actual - allowed)))
    if allowed - actual:
        fail("Expected M11 changes missing: " + ",".join(sorted(allowed - actual)))
    print("PASS | M11 controlled app diff.")

if __name__ == "__main__":
    main()
