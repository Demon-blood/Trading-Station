#!/usr/bin/env python3
from pathlib import Path
import os, sys

NEW_FILES = [
    "app/src/test/java/com/ksp/cryptobot/exchange/KrakenClientOrderIdM13Test.kt",
    "app/src/test/java/com/ksp/cryptobot/lifecycle/PartialFillMathM13Test.kt",
]

def fail(msg):
    raise SystemExit("ERROR | " + msg)

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

def main():
    print("INFO | M13 applier revision v1.1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")
    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app tree:\n" + dirty)

    payload = Path(__file__).resolve().parent / "m13_payload"
    for rel in NEW_FILES:
        src = payload / rel
        dst = repo / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        content = src.read_text(encoding="utf-8").rstrip() + "\n"
        dst.write_text(content, encoding="utf-8")
        if dst.read_text(encoding="utf-8").endswith("\\n"):
            fail(f"M13 payload copy produced literal backslash-n EOF: {rel}")
        print("WRITE |", rel)

    p = repo / "app/src/main/java/com/ksp/cryptobot/exchange/KrakenPrivateExecutionState.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(t,
'''import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
''',
'''import java.util.concurrent.atomic.AtomicLong
import java.util.UUID
import kotlin.math.min
''', "M13 UUID import")
    t = replace_once(t,
'''    fun normalize(raw: String): String {
''',
'''    fun newId(): String = UUID.randomUUID().toString()

    fun normalize(raw: String): String {
''', "M13 UUID cl_ord_id factory")
    t = replace_once(t,
'''}

object KrakenPrivateExecutionRegistry {
''',
'''}

object KrakenPrivatePermissionHints {
    fun describe(raw: String): String {
        val message = raw.trim()
        val permissionLike =
            message.contains("permission", ignoreCase = true) ||
            message.contains("EGeneral:Permission denied", ignoreCase = true)
        return if (permissionLike) {
            "$message. Kraken API key requirement: enable 'WebSocket interface - On' for authenticated private executions."
        } else message
    }
}

object KrakenPrivateExecutionRegistry {
''', "M13 permission hints")
    t = replace_once(t,
'''    data class ExecutionReport(
        val orderId: String,
        val clientOrderId: String,
        val symbol: String,
''',
'''    data class ExecutionReport(
        val orderId: String,
        val clientOrderId: String,
        val executionId: String,
        val symbol: String,
''', "M13 ExecutionReport execution id model")
    t = replace_once(t,
'''                        lastError = "GetWebSocketsToken failed: ${error.message ?: error.javaClass.simpleName}"
''',
'''                        val rawError = error.message ?: error.javaClass.simpleName
                        lastError = "GetWebSocketsToken failed: ${KrakenPrivatePermissionHints.describe(rawError)}"
''', "M13 token permission hint")
    t = replace_once(t,
'''                    lastError = "Executions subscribe failed: ${root.optString("error", "unknown")}"
''',
'''                    lastError = "Executions subscribe failed: ${KrakenPrivatePermissionHints.describe(root.optString("error", "unknown"))}"
''', "M13 subscribe permission hint")
    t = replace_once(t,
'''            clientOrderId = item.optString("cl_ord_id"),
            symbol = normalizeSymbol(item.optString("symbol")),
''',
'''            clientOrderId = item.optString("cl_ord_id"),
            executionId = item.optString("exec_id"),
            symbol = normalizeSymbol(item.optString("symbol")),
''', "M13 parse exec_id")
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(t,
'''import com.ksp.cryptobot.exchange.KrakenPrivateExecutionRegistry
''',
'''import com.ksp.cryptobot.exchange.KrakenPrivateExecutionRegistry
import com.ksp.cryptobot.exchange.KrakenClientOrderId
''', "M13 BotController import")
    t = replace_once(t,
'''            clientOrderId = "ksp-${ticker.symbol.lowercase()}-${System.currentTimeMillis()}",
''',
'''            clientOrderId = KrakenClientOrderId.newId(),
''', "M13 primary UUID")
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/lifecycle/TradeLifecycleManager.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(t,
'''import com.ksp.cryptobot.exchange.CryptoExchangeClient
''',
'''import com.ksp.cryptobot.exchange.CryptoExchangeClient
import com.ksp.cryptobot.exchange.KrakenClientOrderId
''', "M13 lifecycle import")
    t = replace_once(t,
'''            clientOrderId = "ksp-exit-${symbol.lowercase()}-${System.currentTimeMillis()}",
''',
'''            clientOrderId = KrakenClientOrderId.newId(),
''', "M13 lifecycle UUID")
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/execution/ProtectiveStopManager.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(t,
'''import com.ksp.cryptobot.exchange.CryptoExchangeClient
''',
'''import com.ksp.cryptobot.exchange.CryptoExchangeClient
import com.ksp.cryptobot.exchange.KrakenClientOrderId
''', "M13 protective import")
    t = replace_once(t,
'''                clientOrderId="ksp-protect-${symbol.lowercase()}-${System.currentTimeMillis()}",
''',
'''                clientOrderId=KrakenClientOrderId.newId(),
''', "M13 protective UUID")
    t = replace_once(t,
'''            clientOrderId="ksp-emergency-${symbol.lowercase()}-${System.currentTimeMillis()}",reduceOnly=true,
''',
'''            clientOrderId=KrakenClientOrderId.newId(),reduceOnly=true,
''', "M13 emergency UUID")
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/lifecycle/PartialFillSynchronizer.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(t,
'''import com.ksp.cryptobot.exchange.CryptoExchangeClient
''',
'''import com.ksp.cryptobot.exchange.CryptoExchangeClient
import com.ksp.cryptobot.exchange.KrakenPrivateExecutionRegistry
''', "M13 private registry import")
    t = replace_once(t,
'''    fun incrementalFee(exchangeCumulativeFee: BigDecimal, alreadyRecordedFee: BigDecimal): BigDecimal =
        exchangeCumulativeFee.subtract(alreadyRecordedFee).max(BigDecimal.ZERO)
}
''',
'''    fun incrementalFee(exchangeCumulativeFee: BigDecimal, alreadyRecordedFee: BigDecimal): BigDecimal =
        exchangeCumulativeFee.subtract(alreadyRecordedFee).max(BigDecimal.ZERO)

    fun incrementalAveragePrice(
        exchangeCumulative: BigDecimal,
        exchangeAveragePrice: BigDecimal,
        alreadyRecorded: BigDecimal,
        alreadyRecordedCost: BigDecimal,
        fallbackPrice: BigDecimal
    ): BigDecimal {
        val delta = incrementalQuantity(exchangeCumulative, alreadyRecorded)
        if (delta <= BigDecimal.ZERO) {
            return exchangeAveragePrice.takeIf { it > BigDecimal.ZERO }
                ?: fallbackPrice.max(BigDecimal.ZERO)
        }
        val cumulativePrice = exchangeAveragePrice.takeIf { it > BigDecimal.ZERO } ?: fallbackPrice
        if (cumulativePrice <= BigDecimal.ZERO) return BigDecimal.ZERO
        val cumulativeCost = cumulativePrice.multiply(exchangeCumulative)
        val deltaCost = cumulativeCost.subtract(alreadyRecordedCost).max(BigDecimal.ZERO)
        return if (deltaCost > BigDecimal.ZERO) {
            deltaCost.divide(delta, 12, RoundingMode.HALF_UP)
        } else fallbackPrice.max(BigDecimal.ZERO)
    }
}
''', "M13 incremental price math")
    t = replace_once(t,
'''        if (settings.mode == BotMode.PAPER) return

        val openOrders = ExecutionTruthGate.requireAuthoritative(
''',
'''        if (settings.mode == BotMode.PAPER) return

        syncPrivateExecutionReports()

        val openOrders = ExecutionTruthGate.requireAuthoritative(
''', "M13 private fast path")
    t = replace_once(t,
'''        val candidates = openOrders.filter {
            it.side == OrderSide.BUY &&
                it.executedQuantity > BigDecimal.ZERO &&
                it.remainingQuantity > BigDecimal.ZERO
        }
''',
'''        val candidates = openOrders.filter {
            it.executedQuantity > BigDecimal.ZERO &&
                it.remainingQuantity > BigDecimal.ZERO
        }
''', "M13 both-side REST partials")
    t = replace_once(t,
'''            val previousRows = dao.recentTradesSnapshot(500).filter {
                it.exchangeOrderId == order.exchangeOrderId && it.side.equals(OrderSide.BUY.name, true)
            }
''',
'''            val previousRows = dao.recentTradesSnapshot(1000).filter {
                it.exchangeOrderId == order.exchangeOrderId && it.side.equals(order.side.name, true)
            }
''', "M13 side-aware rows")
    t = replace_once(t,
'''            val deltaQty = PartialFillMath.incrementalQuantity(order.executedQuantity, recordedQty)
            val deltaFee = PartialFillMath.incrementalFee(order.fee, recordedFee)
            val fillPrice = order.averageFillPrice.takeIf { it > BigDecimal.ZERO } ?: order.price
            if (deltaQty > BigDecimal.ZERO && fillPrice > BigDecimal.ZERO) {
''',
'''            val recordedCost = previousRows.fold(BigDecimal.ZERO) { a, row ->
                val qty = row.quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO
                val px = row.priceEur.toBigDecimalOrNull() ?: BigDecimal.ZERO
                a + qty.multiply(px)
            }
            val deltaQty = PartialFillMath.incrementalQuantity(order.executedQuantity, recordedQty)
            val deltaFee = PartialFillMath.incrementalFee(order.fee, recordedFee)
            val fillPrice = PartialFillMath.incrementalAveragePrice(
                exchangeCumulative = order.executedQuantity,
                exchangeAveragePrice = order.averageFillPrice,
                alreadyRecorded = recordedQty,
                alreadyRecordedCost = recordedCost,
                fallbackPrice = order.price
            )
            val trackedEntry = dao.positionForSymbol(order.symbol)?.entryPriceEur?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val realizedDelta = if (order.side == OrderSide.SELL && trackedEntry > BigDecimal.ZERO && fillPrice > BigDecimal.ZERO) {
                fillPrice.subtract(trackedEntry).multiply(deltaQty).subtract(deltaFee)
            } else BigDecimal.ZERO
            if (deltaQty > BigDecimal.ZERO && fillPrice > BigDecimal.ZERO) {
''', "M13 exact REST partial economics")
    t = replace_once(t,
'''                        side = OrderSide.BUY.name,
                        quantity = deltaQty.toPlainString(),
                        priceEur = fillPrice.toPlainString(),
                        feeEur = deltaFee.toPlainString(),
                        paper = false,
                        realizedPnlEur = "0",
                        aiScore = 0,
                        aiReason = "Kraken incremental partial fill sync",
''',
'''                        side = order.side.name,
                        quantity = deltaQty.toPlainString(),
                        priceEur = fillPrice.toPlainString(),
                        feeEur = deltaFee.toPlainString(),
                        paper = false,
                        realizedPnlEur = realizedDelta.toPlainString(),
                        aiScore = 0,
                        aiReason = "Kraken REST cumulative partial fill sync",
''', "M13 side-aware REST journal")

    buy_anchor = '''            val existing = dao.positionForSymbol(order.symbol)
            val handoff = HandoffPositionPlanCodec.decode(existing?.source)
'''
    if buy_anchor not in t:
        fail("M13 BUY position anchor missing")
    t = t.replace(buy_anchor,
'''            if (order.side != OrderSide.BUY) {
                statusStore.write(
                    "Partial SELL fill synced ${order.symbol}: cumulative=${order.executedQuantity}, delta=$deltaQty, avg=$fillPrice, remaining=${order.remainingQuantity}, realizedDelta=$realizedDelta. Position quantity will be refreshed from authoritative balances.",
                    "LIVE"
                )
                continue
            }

''' + buy_anchor, 1)

    method_anchor = '''    private fun baseAsset(symbol: String): String {
'''
    private_method = '''    private suspend fun syncPrivateExecutionReports() {
        val reports = KrakenPrivateExecutionRegistry.recentExecutionReports(500)
            .filter {
                it.execType == "trade" &&
                    it.orderId.isNotBlank() &&
                    it.executionId.isNotBlank() &&
                    it.lastQuantity > BigDecimal.ZERO &&
                    it.lastPrice > BigDecimal.ZERO
            }

        val seenExecMarkers = dao.recentTradesSnapshot(2000)
            .asSequence()
            .mapNotNull { row ->
                val markerStart = row.aiReason.indexOf("kraken_exec_id=")
                if (markerStart < 0) null else row.aiReason.substring(markerStart).substringBefore(' ')
            }
            .toMutableSet()

        for (report in reports) {
            val execMarker = "kraken_exec_id=${report.executionId}"
            if (!seenExecMarkers.add(execMarker)) continue

            val trackedEntry = dao.positionForSymbol(report.symbol)?.entryPriceEur?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val realized = if (report.side == OrderSide.SELL && trackedEntry > BigDecimal.ZERO) {
                report.lastPrice.subtract(trackedEntry)
                    .multiply(report.lastQuantity)
                    .subtract(report.feeQuantity.max(BigDecimal.ZERO))
            } else BigDecimal.ZERO

            dao.insertTrade(
                TradeEntity(
                    symbol = report.symbol,
                    side = report.side.name,
                    quantity = report.lastQuantity.toPlainString(),
                    priceEur = report.lastPrice.toPlainString(),
                    feeEur = report.feeQuantity.max(BigDecimal.ZERO).toPlainString(),
                    paper = false,
                    realizedPnlEur = realized.toPlainString(),
                    aiScore = 0,
                    aiReason = "Kraken private execution fill; $execMarker",
                    clientOrderId = report.clientOrderId,
                    exchangeOrderId = report.orderId,
                    timestampEpochMs = report.observedAtEpochMs
                )
            )
        }
    }

'''
    if method_anchor not in t:
        fail("M13 private fill method anchor missing")
    t = t.replace(method_anchor, private_method + method_anchor, 1)
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    actual = changed | untracked
    allowed = set(NEW_FILES) | {
        "app/src/main/java/com/ksp/cryptobot/exchange/KrakenPrivateExecutionState.kt",
        "app/src/main/java/com/ksp/cryptobot/core/BotController.kt",
        "app/src/main/java/com/ksp/cryptobot/lifecycle/TradeLifecycleManager.kt",
        "app/src/main/java/com/ksp/cryptobot/execution/ProtectiveStopManager.kt",
        "app/src/main/java/com/ksp/cryptobot/lifecycle/PartialFillSynchronizer.kt",
    }
    if actual - allowed:
        fail("Unexpected M13 app changes: " + ",".join(sorted(actual - allowed)))
    if allowed - actual:
        fail("Expected M13 changes missing: " + ",".join(sorted(allowed - actual)))
    print("PASS | M13 controlled app diff.")

if __name__ == "__main__":
    main()
