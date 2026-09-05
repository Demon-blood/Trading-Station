#!/usr/bin/env python3
from pathlib import Path
import os
import sys

PAYLOAD_FILES = [
    "app/src/main/java/com/ksp/cryptobot/exchange/RecoveryIntegrityPolicy.kt",
    "app/src/test/java/com/ksp/cryptobot/exchange/RecoveryIntegrityPolicyM21Test.kt",
    "app/src/test/java/com/ksp/cryptobot/exchange/PrivateExecutionSequencePolicyM21Test.kt",
    "app/src/test/java/com/ksp/cryptobot/exchange/RecoveryChaosMatrixM21Test.kt",
    "app/src/test/java/com/ksp/cryptobot/exchange/DurableQuarantineCodecM21Test.kt",
]

def fail(msg):
    raise SystemExit("ERROR | " + msg)

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

def main():
    print("INFO | M21 applier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")

    if not (repo / "tools/verify_m20_net_profit_optimizer.py").exists():
        fail("M20 prerequisite missing from main")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app tree:\n" + dirty)

    payload = Path(__file__).resolve().parent / "m21_payload"
    for rel in PAYLOAD_FILES:
        src = payload / rel
        dst = repo / rel
        if not src.exists():
            fail(f"M21 payload missing: {rel}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(src.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
        copied = dst.read_bytes()
        if copied.endswith(b"\\n"):
            fail(f"M21 payload copy produced literal backslash-n EOF: {rel}")
        if not copied.endswith(b"\n"):
            fail(f"M21 payload copy missing real newline EOF: {rel}")
        print("WRITE |", rel)

    p = repo / "app/src/main/java/com/ksp/cryptobot/exchange/KrakenDurableExecutionQuarantine.kt"
    t = p.read_text(encoding="utf-8")

    t = replace_once(
        t,
        '''            persistLocked()
        }
    }

    fun markAmbiguous(clientOrderId: String, reason: String) {
''',
        '''            check(persistLocked()) {
                "Refusing Kraken AddOrder boundary because unresolved intent could not be durably committed."
            }
        }
    }

    fun markAmbiguous(clientOrderId: String, reason: String) {
''',
        "M21 pre-submit durable commit result"
    )

    t = replace_once(
        t,
        '''            persistLocked()
        }
    }

    fun clear(clientOrderId: String) {
        synchronized(lock) {
            if (rows.remove(clientOrderId) != null) persistLocked()
        }
    }
''',
        '''            check(persistLocked()) {
                "Unable to durably persist ambiguous Kraken AddOrder state."
            }
        }
    }

    fun clear(clientOrderId: String) {
        synchronized(lock) {
            val removed = rows.remove(clientOrderId) ?: return
            if (!persistLocked()) {
                rows[clientOrderId] = removed
                throw IllegalStateException(
                    "Unable to durably clear Kraken execution quarantine; keeping entry fail-closed."
                )
            }
        }
    }
''',
        "M21 durable ambiguous/clear semantics"
    )

    t = replace_once(
        t,
        '''    private fun persistLocked() {
        // commit(), not apply(): the unresolved intent must reach durable storage before
        // the network AddOrder call can cross the process-crash boundary.
        prefs?.edit()
            ?.putString(KEY_ROWS, KrakenDurableSubmissionCodec.encode(rows.values))
            ?.commit()
    }
''',
        '''    private fun persistLocked(): Boolean {
        // commit(), not apply(): the unresolved intent must reach durable storage before
        // the network AddOrder call can cross the process-crash boundary.
        return prefs?.edit()
            ?.putString(KEY_ROWS, KrakenDurableSubmissionCodec.encode(rows.values))
            ?.commit() == true
    }
''',
        "M21 durable persistence boolean"
    )
    p.write_text(t.rstrip() + "\n", encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/exchange/KrakenPrivateExecutionState.kt"
    t = p.read_text(encoding="utf-8")

    t = replace_once(
        t,
        '''    private var lastRestReconciliationMs = 0L
    private var lastRestOpenOrderCount = 0

    private var connectJob: Job? = null
''',
        '''    private var lastRestReconciliationMs = 0L
    private var lastRestOpenOrderCount = 0
    private var recoveryReady = false
    private var recoveryReason = "Process start requires authoritative reconciliation."

    private var connectJob: Job? = null
''',
        "M21 private recovery fence fields"
    )

    t = replace_once(
        t,
        '''    private val reportsByOrder = linkedMapOf<String, ExecutionReport>()
    private val recentReports = ArrayDeque<ExecutionReport>()
    private val pending = linkedMapOf<String, PendingSubmission>()
''',
        '''    private val reportsByOrder = linkedMapOf<String, ExecutionReport>()
    private val recentReports = ArrayDeque<ExecutionReport>()
    private val executionIds = IdempotentExecutionIdFilter()
    private val pending = linkedMapOf<String, PendingSubmission>()
''',
        "M21 exec id filter"
    )

    t = replace_once(
        t,
        '''    fun markRestReconciled(openOrders: Int) {
        synchronized(lock) {
            lastRestReconciliationMs = System.currentTimeMillis()
            lastRestOpenOrderCount = openOrders.coerceAtLeast(0)
            if (state == "REST_ONLY" || state == "ERROR") lastError = ""
        }
    }

    fun markSubmissionPending(clientOrderId: String, symbol: String, side: OrderSide) {
''',
        '''    fun markRestReconciled(openOrders: Int) {
        synchronized(lock) {
            lastRestReconciliationMs = System.currentTimeMillis()
            lastRestOpenOrderCount = openOrders.coerceAtLeast(0)
            if (state == "REST_ONLY" || state == "ERROR") lastError = ""
        }
    }

    fun markRecoveryUnknown(reason: String) {
        synchronized(lock) {
            recoveryReady = false
            recoveryReason = reason.take(300)
        }
    }

    fun markRecoveryReconciled(reason: String) {
        synchronized(lock) {
            recoveryReady = true
            recoveryReason = reason.take(300)
        }
    }

    fun recoveryFence(): Pair<Boolean, String> = synchronized(lock) {
        recoveryReady to recoveryReason
    }

    fun markSubmissionPending(clientOrderId: String, symbol: String, side: OrderSide) {
''',
        "M21 recovery fence API"
    )

    t = replace_once(
        t,
        '''    fun canSubmitNewEntry(symbol: String, side: OrderSide): Pair<Boolean, String> = synchronized(lock) {
        if (side != OrderSide.BUY) return@synchronized true to "Protective/exit side is not entry-gated."

        val canonical = normalizeSymbol(symbol)
''',
        '''    fun canSubmitNewEntry(symbol: String, side: OrderSide): Pair<Boolean, String> = synchronized(lock) {
        if (side != OrderSide.BUY) return@synchronized true to "Protective/exit side is not entry-gated."

        if (!recoveryReady) {
            return@synchronized false to "M21 recovery fence blocks new BUY: $recoveryReason"
        }

        val canonical = normalizeSymbol(symbol)
''',
        "M21 BUY recovery fence"
    )

    t = replace_once(
        t,
        '''            if (credentialsChanged) {
                resetSocket = socket
                socket = null
                state = "CREDENTIALS_CHANGED"
                subscribed = false
                snapshotComplete = false
                lastSequence = 0L
                reportsByOrder.clear()
                recentReports.clear()
            } else if (state == "STOPPED") {
''',
        '''            if (credentialsChanged) {
                resetSocket = socket
                socket = null
                state = "CREDENTIALS_CHANGED"
                subscribed = false
                snapshotComplete = false
                lastSequence = 0L
                recoveryReady = false
                recoveryReason = "Kraken credentials changed; full authoritative reconciliation required."
                reportsByOrder.clear()
                recentReports.clear()
                executionIds.clear()
            } else if (state == "STOPPED") {
''',
        "M21 credential change resets recovery fence"
    )

    t = replace_once(
        t,
        '''            reportsByOrder.clear()
            recentReports.clear()
            pending.clear()
            ambiguous.clear()
''',
        '''            reportsByOrder.clear()
            recentReports.clear()
            executionIds.clear()
            pending.clear()
            ambiguous.clear()
            recoveryReady = false
            recoveryReason = "Private execution host stopped; reconciliation required before BUY."
''',
        "M21 stop resets fence"
    )

    t = replace_once(
        t,
        '''            if (!available) {
                state = "NETWORK_DOWN"
                subscribed = false
                snapshotComplete = false
                lastSequence = 0L
                lastMessageMs = 0L
                reportsByOrder.clear()
''',
        '''            if (!available) {
                state = "NETWORK_DOWN"
                subscribed = false
                snapshotComplete = false
                lastSequence = 0L
                lastMessageMs = 0L
                recoveryReady = false
                recoveryReason = "Validated network lost; authoritative reconciliation required."
                reportsByOrder.clear()
''',
        "M21 network loss fence"
    )

    t = replace_once(
        t,
        '''        synchronized(lock) {
            if (type == "update" && sequence > 0L && lastSequence > 0L && sequence != lastSequence + 1L) {
                state = "SEQUENCE_GAP"
                snapshotComplete = false
                subscribed = false
                lastError = "Kraken executions sequence gap: expected=${lastSequence + 1L}, actual=$sequence"
                reportsByOrder.clear()
                socket = null
                runCatching { webSocket.cancel() }
                scheduleReconnect(immediate = false)
                return
            }

            if (type == "snapshot") {
''',
        '''        synchronized(lock) {
            when (PrivateExecutionSequencePolicy.classify(lastSequence, sequence, type)) {
                PrivateSequenceDisposition.DUPLICATE -> return
                PrivateSequenceDisposition.STALE_OR_OUT_OF_ORDER,
                PrivateSequenceDisposition.GAP -> {
                    state = "SEQUENCE_GAP"
                    snapshotComplete = false
                    subscribed = false
                    recoveryReady = false
                    recoveryReason = "Private execution sequence continuity lost; full reconciliation required."
                    lastError = "Kraken executions sequence discontinuity: previous=$lastSequence actual=$sequence"
                    reportsByOrder.clear()
                    socket = null
                    runCatching { webSocket.cancel() }
                    scheduleReconnect(immediate = false)
                    return
                }
                PrivateSequenceDisposition.INITIAL,
                PrivateSequenceDisposition.ACCEPT_NEXT -> Unit
            }

            if (type == "snapshot") {
''',
        "M21 explicit sequence policy"
    )

    t = replace_once(
        t,
        '''                val item = data.optJSONObject(i) ?: continue
                val report = parseReport(item, sequence)
                if (report.orderId.isNotBlank()) reportsByOrder[report.orderId] = report
''',
        '''                val item = data.optJSONObject(i) ?: continue
                val report = parseReport(item, sequence)
                if (!executionIds.accept(report.executionId)) continue
                if (report.orderId.isNotBlank()) reportsByOrder[report.orderId] = report
''',
        "M21 exec id idempotence"
    )

    t = replace_once(
        t,
        '''            state = if (networkAvailable) "DISCONNECTED" else "NETWORK_DOWN"
            subscribed = false
            snapshotComplete = false
            lastSequence = 0L
''',
        '''            state = if (networkAvailable) "DISCONNECTED" else "NETWORK_DOWN"
            subscribed = false
            snapshotComplete = false
            recoveryReady = false
            recoveryReason = "Private execution transport disconnected; reconciliation required."
            lastSequence = 0L
''',
        "M21 disconnect fence"
    )

    t = replace_once(
        t,
        '''                        state = "SILENT"
                        subscribed = false
                        snapshotComplete = false
                        lastSequence = 0L
''',
        '''                        state = "SILENT"
                        subscribed = false
                        snapshotComplete = false
                        recoveryReady = false
                        recoveryReason = "Private execution stream became silent; reconciliation required."
                        lastSequence = 0L
''',
        "M21 silent-stream fence"
    )

    p.write_text(t.rstrip() + "\n", encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt"
    t = p.read_text(encoding="utf-8")

    t = replace_once(
        t,
        '''                if (!network.usable) {
                    lastNetworkUsable = false
                    hostStore.recovery("PAUSED_NETWORK")
''',
        '''                if (!network.usable) {
                    lastNetworkUsable = false
                    KrakenPrivateExecutionRegistry.markRecoveryUnknown(
                        "Validated network unavailable during service cycle."
                    )
                    hostStore.recovery("PAUSED_NETWORK")
''',
        "M21 service network recovery fence"
    )

    t = replace_once(
        t,
        '''        hostStore.recovery("RECONCILING:$reason")
        return runCatching {
''',
        '''        hostStore.recovery("RECONCILING:$reason")
        if (settings.exchangeProvider == ExchangeProvider.KRAKEN) {
            KrakenPrivateExecutionRegistry.markRecoveryUnknown(
                "Authoritative reconciliation in progress: $reason"
            )
        }
        return runCatching {
''',
        "M21 reconciliation starts unknown"
    )

    t = replace_once(
        t,
        '''            if (settings.exchangeProvider == ExchangeProvider.KRAKEN) {
                KrakenPrivateExecutionRegistry.markRestReconciled(openOrders.size)
            }

            hostStore.reconciliationSucceeded(
''',
        '''            if (settings.exchangeProvider == ExchangeProvider.KRAKEN) {
                KrakenPrivateExecutionRegistry.markRestReconciled(openOrders.size)
                KrakenPrivateExecutionRegistry.markRecoveryReconciled(
                    "Full service reconciliation passed after $reason: orders=${openOrders.size}, positions=${lifecycle.positions.size}, assets=${portfolio.assets.size}"
                )
            }

            hostStore.reconciliationSucceeded(
''',
        "M21 reconciliation success releases fence"
    )

    t = replace_once(
        t,
        '''        }.getOrElse { error ->
            val failures = hostStore.failure("Reconciliation failed after $reason: ${error.message}")
''',
        '''        }.getOrElse { error ->
            if (settings.exchangeProvider == ExchangeProvider.KRAKEN) {
                KrakenPrivateExecutionRegistry.markRecoveryUnknown(
                    "Reconciliation failed after $reason: ${error.message ?: error.javaClass.simpleName}"
                )
            }
            val failures = hostStore.failure("Reconciliation failed after $reason: ${error.message}")
''',
        "M21 reconciliation failure keeps fence"
    )

    p.write_text(t.rstrip() + "\n", encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    actual = changed | untracked
    allowed = set(PAYLOAD_FILES) | {
        "app/src/main/java/com/ksp/cryptobot/exchange/KrakenDurableExecutionQuarantine.kt",
        "app/src/main/java/com/ksp/cryptobot/exchange/KrakenPrivateExecutionState.kt",
        "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt",
    }
    if actual - allowed:
        fail("Unexpected M21 app changes: " + ",".join(sorted(actual - allowed)))
    if allowed - actual:
        fail("Expected M21 changes missing: " + ",".join(sorted(allowed - actual)))

    print("PASS | M21 controlled app diff.")

if __name__ == "__main__":
    main()
