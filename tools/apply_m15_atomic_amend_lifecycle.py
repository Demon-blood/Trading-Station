#!/usr/bin/env python3
from pathlib import Path
import os, sys

PAYLOAD_FILES = [
    "app/src/main/java/com/ksp/cryptobot/exchange/AtomicOrderAmend.kt",
    "app/src/main/java/com/ksp/cryptobot/execution/SmartOrderLifecycleManager.kt",
    "app/src/test/java/com/ksp/cryptobot/execution/SmartOrderLifecyclePolicyTest.kt",
    "app/src/test/java/com/ksp/cryptobot/execution/ExecutionCalibrationMathTest.kt",
]

def fail(msg):
    raise SystemExit("ERROR | " + msg)

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

def main():
    print("INFO | M15 applier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app tree:\\n" + dirty)

    payload = Path(__file__).resolve().parent / "m15_payload"
    for rel in PAYLOAD_FILES:
        src = payload / rel
        dst = repo / rel
        if not src.exists():
            fail(f"M15 payload missing: {rel}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(src.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
        if dst.read_text(encoding="utf-8").endswith("\\n"):
            fail(f"M15 payload copy produced literal backslash-n EOF: {rel}")
        print("WRITE |", rel)

    p = repo / "app/src/main/java/com/ksp/cryptobot/exchange/CryptoExchangeClient.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''    /** Cancel one live order when the exchange supports it. */
    suspend fun cancelOrder(orderId: String): Boolean = false

    /** Closed orders/trades as reported by the exchange. Used by lifecycle sync. */
''',
        '''    /**
     * Atomically amend a working order without cancel/recreate when the connector supports it.
     * Unsupported connectors return supported=false and must never simulate success.
     */
    suspend fun amendOrder(request: AtomicOrderAmendRequest): AtomicOrderAmendResult =
        AtomicOrderAmendResult(
            supported = false,
            amended = false,
            exchangeOrderId = request.exchangeOrderId,
            clientOrderId = request.clientOrderId,
            reason = "Atomic amend is not supported by this exchange connector."
        )

    /** Cancel one live order when the exchange supports it. */
    suspend fun cancelOrder(orderId: String): Boolean = false

    /** Closed orders/trades as reported by the exchange. Used by lifecycle sync. */
''',
        "M15 generic atomic amend contract"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt"
    t = p.read_text(encoding="utf-8")
    amend_method = r'''    override suspend fun amendOrder(request: AtomicOrderAmendRequest): AtomicOrderAmendResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            error("Kraken API key and private key are required to amend orders.")
        }
        require(request.exchangeOrderId.isNotBlank() || request.clientOrderId.isNotBlank()) {
            "Kraken AmendOrder requires txid or cl_ord_id."
        }

        val rule = resolvePairRule(request.symbol)
        if (!rule.tradable) error("Kraken pair is not tradable: ${request.symbol}. ${rule.status}")

        val form = linkedMapOf<String, String>()
        if (request.clientOrderId.isNotBlank()) {
            form["cl_ord_id"] = KrakenClientOrderId.normalize(request.clientOrderId)
        } else {
            form["txid"] = request.exchangeOrderId
        }

        request.newTotalQuantity?.let { raw ->
            val total = raw.setScale(rule.quantityDecimals, RoundingMode.DOWN)
            require(total >= rule.minOrderSize) {
                "Kraken amended total quantity too small for ${rule.canonicalSymbol}. quantity=$total min=${rule.minOrderSize}"
            }
            form["order_qty"] = total.stripTrailingZeros().toPlainString()
        }

        request.newLimitPrice?.let { raw ->
            require(request.orderType == OrderType.LIMIT) {
                "M15 limit_price amend requires LIMIT order type."
            }
            val price = roundKrakenPriceToTick(
                raw,
                rule.tickSize,
                rule.priceDecimals,
                request.side,
                OrderType.LIMIT
            )
            form["limit_price"] = price.stripTrailingZeros().toPlainString()
        }

        request.newTriggerPrice?.let { raw ->
            require(request.orderType == OrderType.STOP_LOSS || request.orderType == OrderType.TAKE_PROFIT) {
                "M15 trigger_price amend requires a triggered order type."
            }
            val trigger = roundKrakenPriceToTick(
                raw,
                rule.tickSize,
                rule.priceDecimals,
                request.side,
                request.orderType
            )
            form["trigger_price"] = trigger.stripTrailingZeros().toPlainString()
        }

        request.postOnly?.let { post ->
            require(request.newLimitPrice != null && request.orderType == OrderType.LIMIT) {
                "Kraken post_only amend is valid only with a LIMIT price amendment."
            }
            form["post_only"] = post.toString()
        }
        request.deadline?.let { deadline ->
            val now = java.time.Instant.now()
            require(!deadline.isBefore(now.plusSeconds(2)) && !deadline.isAfter(now.plusSeconds(60))) {
                "Kraken AmendOrder deadline must be at least 2 seconds and no more than 60 seconds ahead."
            }
            form["deadline"] = deadline.toString()
        }

        require(form.keys.any { it in setOf("order_qty", "limit_price", "trigger_price") }) {
            "Kraken AmendOrder requires at least one mutable order field."
        }

        val root = privateJson("/0/private/AmendOrder", form)
        val result = root.optJSONObject("result") ?: error("Kraken AmendOrder returned no result.")
        val amendId = result.optString("amend_id", "")
        require(amendId.isNotBlank()) { "Kraken AmendOrder returned no amend_id." }

        AtomicOrderAmendResult(
            supported = true,
            amended = true,
            amendId = amendId,
            exchangeOrderId = request.exchangeOrderId,
            clientOrderId = request.clientOrderId,
            reason = "Kraken atomic amend accepted; order identifiers remain stable."
        )
    }

'''
    anchor = '''    override suspend fun cancelOrder(orderId: String): Boolean = withContext(Dispatchers.IO) {
'''
    if t.count(anchor) != 1:
        fail(f"M15 Kraken cancel anchor: expected one match, got {t.count(anchor)}")
    t = t.replace(anchor, amend_method + anchor, 1)
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''import com.ksp.cryptobot.execution.ProtectiveStopManager
''',
        '''import com.ksp.cryptobot.execution.ProtectiveStopManager
import com.ksp.cryptobot.execution.SmartOrderLifecycleManager
''',
        "M15 BotController lifecycle import"
    )
    t = replace_once(
        t,
        '''    private val advancedExecution = AdvancedExecutionCoordinator(dao, AppDatabase.get(appContext).governanceDao())
    private val protectiveStops = ProtectiveStopManager(dao, AppDatabase.get(appContext).governanceDao())
''',
        '''    private val advancedExecution = AdvancedExecutionCoordinator(dao, AppDatabase.get(appContext).governanceDao())
    private val protectiveStops = ProtectiveStopManager(dao, AppDatabase.get(appContext).governanceDao())
    private val smartOrderLifecycle = SmartOrderLifecycleManager(appContext)
''',
        "M15 BotController lifecycle field"
    )

    old_cancel = '''            if (settings.smartLimitRequote && order.orderType == OrderType.LIMIT && age >= settings.staleOrderTimeoutSeconds) {
                val cancelled = runCatching { exchange.cancelOrder(order.exchangeOrderId) }.getOrDefault(false)
                if (cancelled) {
                    updateStatus("Stale order cancelled for requote: ${order.exchangeOrderId} ${order.symbol} age=${age}s", "LIVE")
                } else {
                    updateStatus("Stale order cancel failed or not supported: ${order.exchangeOrderId}", "WARN")
                }
            }
'''
    if t.count(old_cancel) != 1:
        fail(f"M15 old cancel-only lifecycle block: expected one match, got {t.count(old_cancel)}")
    t = t.replace(old_cancel, "", 1)

    lifecycle_tail = '''            updateStatus("Open order: ${order.side} ${order.symbol} ${order.orderType} remaining=${order.remainingQuantity.stripTrailingZeros().toPlainString()} price=${order.price.stripTrailingZeros().toPlainString()} age=${age}s status=${order.status}", "INFO")
        }
    }
'''
    lifecycle_tail_new = '''            updateStatus("Open order: ${order.side} ${order.symbol} ${order.orderType} remaining=${order.remainingQuantity.stripTrailingZeros().toPlainString()} price=${order.price.stripTrailingZeros().toPlainString()} age=${age}s status=${order.status}", "INFO")
        }
        smartOrderLifecycle.manage(settings, exchange, orders).forEach { event ->
            updateStatus("M15 order lifecycle: ${event.message}", event.severity)
        }
    }
'''
    if t.count(lifecycle_tail) != 1:
        fail(f"M15 manageExistingLiveOrders tail anchor: expected one match, got {t.count(lifecycle_tail)}")
    t = t.replace(lifecycle_tail, lifecycle_tail_new, 1)
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    actual = changed | untracked
    allowed = set(PAYLOAD_FILES) | {
        "app/src/main/java/com/ksp/cryptobot/exchange/CryptoExchangeClient.kt",
        "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt",
        "app/src/main/java/com/ksp/cryptobot/core/BotController.kt",
    }
    if actual - allowed:
        fail("Unexpected M15 app changes: " + ",".join(sorted(actual - allowed)))
    if allowed - actual:
        fail("Expected M15 changes missing: " + ",".join(sorted(allowed - actual)))

    print("PASS | M15 controlled app diff.")

if __name__ == "__main__":
    main()
