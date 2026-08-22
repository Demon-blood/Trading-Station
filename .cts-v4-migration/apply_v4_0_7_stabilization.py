#!/usr/bin/env python3
"""Crypto TradeStation v4.0.7 execution-integrity stabilization.

Run after the cumulative v4 migration/source generators. The patch is intentionally
idempotent: rerunning it either leaves the already-patched source alone or fails if
the expected canonical source shape has changed.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

MARKER = "CTS_V407_EXECUTION_INTEGRITY"


def fail(msg: str) -> None:
    raise SystemExit(f"[CTS v4.0.7 stabilization] {msg}")


def read(path: Path) -> str:
    if not path.exists():
        fail(f"Required file missing: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected exactly one source match, found {count}")
    return text.replace(old, new, 1)


def replace_regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    if replacement in text:
        return text
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        fail(f"{label}: expected exactly one regex match, found {count}")
    return updated


INTEGRITY_SOURCE = r'''package com.ksp.cryptobot.execution

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Pure execution-integrity math shared by the controller and PAPER exchange.
 *
 * The functions deliberately contain no Android/Room dependencies so the release build
 * can regression-test hard exposure limits and deterministic deferred-fill identities.
 */
object PaperExecutionIntegrity {
    private val ZERO = BigDecimal.ZERO

    fun normalizeAmount(value: BigDecimal): String =
        value.stripTrailingZeros().toPlainString()

    /**
     * A deferred fill is identified by source order + cumulative execution progress.
     * Two legitimate partial fills have different progress ranges; a replay of the same
     * partial fill gets the same ID and is therefore detectable.
     */
    fun deferredFillId(
        sourceOrderId: String,
        executedBefore: BigDecimal,
        fillQuantity: BigDecimal
    ): String {
        val before = executedBefore.max(ZERO)
        val after = before.add(fillQuantity.max(ZERO))
        return "$sourceOrderId-paperfill-${normalizeAmount(before)}-${normalizeAmount(after)}"
    }

    fun currentExposure(
        currentBaseQuantity: BigDecimal,
        referencePrice: BigDecimal
    ): BigDecimal {
        if (currentBaseQuantity <= ZERO || referencePrice <= ZERO) return ZERO
        return currentBaseQuantity.multiply(referencePrice)
    }

    /** Remaining quote-value capacity after held exposure and already-open BUY orders. */
    fun remainingPositionCapacity(
        maxPositionEur: BigDecimal,
        currentBaseQuantity: BigDecimal,
        pendingBuyNotional: BigDecimal,
        referencePrice: BigDecimal
    ): BigDecimal {
        if (maxPositionEur <= ZERO || referencePrice <= ZERO) return ZERO
        val held = currentExposure(currentBaseQuantity.max(ZERO), referencePrice)
        return maxPositionEur
            .subtract(held)
            .subtract(pendingBuyNotional.max(ZERO))
            .max(ZERO)
    }

    /** Maximum additional base quantity allowed by the hard position cap. */
    fun maxAdditionalBuyQuantity(
        maxPositionEur: BigDecimal,
        currentBaseQuantity: BigDecimal,
        referencePrice: BigDecimal,
        scale: Int = 12
    ): BigDecimal {
        if (maxPositionEur <= ZERO || referencePrice <= ZERO) return ZERO
        val remaining = maxPositionEur
            .subtract(currentExposure(currentBaseQuantity.max(ZERO), referencePrice))
            .max(ZERO)
        return remaining.divide(referencePrice, scale.coerceIn(0, 18), RoundingMode.DOWN).max(ZERO)
    }
}
'''

TEST_SOURCE = r'''package com.ksp.cryptobot.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class PaperExecutionIntegrityTest {
    @Test
    fun deferredFillIdIsStableForReplayButChangesForNextPartialFill() {
        val first = PaperExecutionIntegrity.deferredFillId(
            "ksp-paxgeur-1", BigDecimal.ZERO, BigDecimal("0.01430919")
        )
        val replay = PaperExecutionIntegrity.deferredFillId(
            "ksp-paxgeur-1", BigDecimal.ZERO, BigDecimal("0.01430919")
        )
        val second = PaperExecutionIntegrity.deferredFillId(
            "ksp-paxgeur-1", BigDecimal("0.01430919"), BigDecimal("0.01430919")
        )
        assertEquals(first, replay)
        assertTrue(first != second)
    }

    @Test
    fun positionCapacitySubtractsHeldAndPendingExposure() {
        val remaining = PaperExecutionIntegrity.remainingPositionCapacity(
            maxPositionEur = BigDecimal("150"),
            currentBaseQuantity = BigDecimal("0.020"),
            pendingBuyNotional = BigDecimal("50"),
            referencePrice = BigDecimal("4000")
        )
        assertEquals(0, remaining.compareTo(BigDecimal("20")))
    }

    @Test
    fun positionCapacityNeverGoesNegative() {
        val remaining = PaperExecutionIntegrity.remainingPositionCapacity(
            maxPositionEur = BigDecimal("150"),
            currentBaseQuantity = BigDecimal("0.040"),
            pendingBuyNotional = BigDecimal.ZERO,
            referencePrice = BigDecimal("4000")
        )
        assertEquals(0, remaining.compareTo(BigDecimal.ZERO))
    }

    @Test
    fun fillTimeQuantityClampCannotCrossHardCap() {
        val maxQty = PaperExecutionIntegrity.maxAdditionalBuyQuantity(
            maxPositionEur = BigDecimal("150"),
            currentBaseQuantity = BigDecimal("0.030"),
            referencePrice = BigDecimal("4000")
        )
        assertEquals(0, maxQty.compareTo(BigDecimal("0.0075")))
        val finalExposure = BigDecimal("0.030").add(maxQty).multiply(BigDecimal("4000"))
        assertTrue(finalExposure <= BigDecimal("150"))
    }
}
'''


def patch_paper_client(path: Path) -> None:
    text = read(path)
    if MARKER in text:
        print(f"already patched: {path}")
        return

    text = replace_once(
        text,
        "import com.ksp.cryptobot.data.TradeEntity\n",
        "import com.ksp.cryptobot.data.TradeEntity\nimport com.ksp.cryptobot.execution.PaperExecutionIntegrity\nimport com.ksp.cryptobot.settings.AppSettingsStore\n",
        f"{path}: imports",
    )
    text = replace_once(
        text,
        "    private val tradeDao = appContext?.let { AppDatabase.get(it).dao() }\n",
        "    private val tradeDao = appContext?.let { AppDatabase.get(it).dao() }\n"
        "    private val settingsStore = appContext?.let { AppSettingsStore(it) }\n",
        f"{path}: settings store",
    )
    text = replace_once(
        text,
        "    private val orderMutex = Mutex()\n",
        "    // CTS_V407_EXECUTION_INTEGRITY: every PaperExchangeClient instance shares one\n"
        "    // mutex because all instances mutate the same SharedPreferences wallet/order state.\n"
        "    companion object {\n"
        "        private val globalOrderMutex = Mutex()\n"
        "    }\n",
        f"{path}: process-wide paper mutex",
    )
    text = text.replace("orderMutex.withLock", "globalOrderMutex.withLock")

    id_line = '        val id = request.clientOrderId.ifBlank { "paper-${clean.lowercase()}-$now" }\n'
    id_patch = id_line + (
        "        // CTS_V407_EXECUTION_INTEGRITY: a client order ID is single-use. Replaying an\n"
        "        // accepted/closed request must never mutate the PAPER wallet a second time.\n"
        "        if (loadPending().containsKey(id) || loadClosed().any { it.exchangeOrderId == id }) {\n"
        "            error(\"Paper duplicate clientOrderId blocked: $id\")\n"
        "        }\n"
    )
    text = replace_once(text, id_line, id_patch, f"{path}: duplicate order guard")

    remaining_anchor = (
        "        var remaining = requestedQuantity.max(BigDecimal.ZERO)\n"
        "        if (side == OrderSide.SELL) remaining = remaining.min(wallet[base] ?: BigDecimal.ZERO)\n"
    )
    remaining_patch = (
        "        var remaining = requestedQuantity.max(BigDecimal.ZERO)\n"
        "        if (side == OrderSide.SELL) remaining = remaining.min(wallet[base] ?: BigDecimal.ZERO)\n"
        "        val hardPositionCap = settingsStore?.load()?.maxPositionEur ?: BigDecimal.ZERO\n"
        "        val baseQuantityBeforeFill = wallet[base] ?: BigDecimal.ZERO\n"
    )
    text = replace_once(text, remaining_anchor, remaining_patch, f"{path}: paper hard cap setup")

    take_anchor = (
        "            var take = remaining.min(availableQty)\n"
        "            if (side == OrderSide.BUY) {\n"
        "                val grossPerUnit = price.multiply(BigDecimal.ONE.add(feeRate))\n"
    )
    take_patch = (
        "            var take = remaining.min(availableQty)\n"
        "            if (side == OrderSide.BUY) {\n"
        "                // CTS_V407_EXECUTION_INTEGRITY: enforce the hard max-position cap again\n"
        "                // at actual fill time. This protects resting orders from later exposure\n"
        "                // changes and from price movement between submit and fill.\n"
        "                if (hardPositionCap > BigDecimal.ZERO) {\n"
        "                    val riskPrice = price.max(ticker.ask).max(BigDecimal(\"0.00000001\"))\n"
        "                    val allowedByCap = PaperExecutionIntegrity.maxAdditionalBuyQuantity(\n"
        "                        hardPositionCap, baseQuantityBeforeFill.add(qty), riskPrice\n"
        "                    )\n"
        "                    take = take.min(allowedByCap)\n"
        "                }\n"
        "                val grossPerUnit = price.multiply(BigDecimal.ONE.add(feeRate))\n"
    )
    text = replace_once(text, take_anchor, take_patch, f"{path}: fill-time position cap")

    old_fill_id = '        val fillId = "${pending.id}-paperfill-${System.currentTimeMillis()}"\n'
    new_fill_id = (
        "        // CTS_V407_EXECUTION_INTEGRITY: deterministic progress-based ID. Legitimate\n"
        "        // partial fills remain distinct; replaying the same progress range is detectable.\n"
        "        val fillId = PaperExecutionIntegrity.deferredFillId(\n"
        "            pending.id, pending.executedQuantity, fill.quantity\n"
        "        )\n"
    )
    text = replace_once(text, old_fill_id, new_fill_id, f"{path}: deterministic deferred fill id")
    text = text.replace("dao.recentTradesSnapshot(500).any { it.exchangeOrderId == fillId }", "dao.recentTradesSnapshot(1000).any { it.exchangeOrderId == fillId }")

    # Make the two execution-critical state files synchronous on disk. SharedPreferences.apply()
    # is in-memory immediate but asynchronous on disk; commit() narrows restart replay windows.
    text = text.replace(
        '        edit.apply()\n    }\n\n    private fun loadPending()',
        '        check(edit.commit()) { "Paper wallet persistence failed." }\n    }\n\n    private fun loadPending()',
        1,
    )
    text = text.replace(
        '        orderPrefs.edit().putString("pending", arr.toString()).apply()\n',
        '        check(orderPrefs.edit().putString("pending", arr.toString()).commit()) { "Paper pending-order persistence failed." }\n',
        1,
    )

    write(path, text)
    print(f"patched: {path}")


def patch_controller(path: Path) -> None:
    text = read(path)
    if MARKER in text:
        print(f"already patched: {path}")
        return

    text = replace_once(
        text,
        "import com.ksp.cryptobot.execution.AdvancedRiskManager\n",
        "import com.ksp.cryptobot.execution.AdvancedRiskManager\nimport com.ksp.cryptobot.execution.PaperExecutionIntegrity\n",
        "BotController integrity import",
    )

    old_update = (
        "    private fun updateStatus(message: String, level: String = \"INFO\") {\n"
        "        _status.value = message\n"
        "        statusStore.write(message, level)\n"
        "    }\n"
    )
    new_update = (
        "    // CTS_V407_EXECUTION_INTEGRITY: detailed learning warm-up remains in the status log,\n"
        "    // but it no longer replaces the top-level engine headline for hours/days.\n"
        "    private fun updateStatus(message: String, level: String = \"INFO\") {\n"
        "        val learningTelemetry = message.contains(\"warm-up\", ignoreCase = true) ||\n"
        "            message.contains(\"warmup\", ignoreCase = true) ||\n"
        "            message.contains(\"not enough samples\", ignoreCase = true)\n"
        "        if (!learningTelemetry) _status.value = message\n"
        "        statusStore.write(message, level)\n"
        "    }\n"
    )
    text = replace_once(text, old_update, new_update, "BotController warmup headline separation")

    old_cap = (
        "        val adaptivePositionCap = if (settings.ultimateAutomationEnabled) adaptivePositionCapFor(settings, ticker.symbol) else settings.maxPositionEur\n"
        "        val perOrderCap = if (useMarketOrder) adaptivePositionCap.min(settings.maxMarketOrderEur) else adaptivePositionCap\n"
    )
    new_cap = (
        "        val adaptivePositionCap = if (settings.ultimateAutomationEnabled) adaptivePositionCapFor(settings, ticker.symbol) else settings.maxPositionEur\n"
        "        // CTS_V407_EXECUTION_INTEGRITY: Max Position is a total exposure cap, not merely\n"
        "        // a per-order spend cap. Include both held base and already-open BUY orders.\n"
        "        val pendingBuyExposure = if (side == OrderSide.BUY) {\n"
        "            runCatching {\n"
        "                exchange.getOpenOrders().asSequence()\n"
        "                    .filter { order ->\n"
        "                        order.side == OrderSide.BUY &&\n"
        "                            order.symbol.uppercase().replace(\"/\", \"\").replace(\"-\", \"\") ==\n"
        "                            ticker.symbol.uppercase().replace(\"/\", \"\").replace(\"-\", \"\")\n"
        "                    }\n"
        "                    .fold(BigDecimal.ZERO) { acc, order ->\n"
        "                        val orderPrice = order.price.takeIf { it > BigDecimal.ZERO } ?: price\n"
        "                        acc.add(order.remainingQuantity.max(BigDecimal.ZERO).multiply(orderPrice))\n"
        "                    }\n"
        "            }.getOrDefault(BigDecimal.ZERO)\n"
        "        } else BigDecimal.ZERO\n"
        "        val remainingPositionCapacity = if (side == OrderSide.BUY) {\n"
        "            PaperExecutionIntegrity.remainingPositionCapacity(\n"
        "                maxPositionEur = adaptivePositionCap,\n"
        "                currentBaseQuantity = availableBase.max(BigDecimal.ZERO),\n"
        "                pendingBuyNotional = pendingBuyExposure,\n"
        "                referencePrice = price\n"
        "            )\n"
        "        } else adaptivePositionCap\n"
        "        if (side == OrderSide.BUY && remainingPositionCapacity <= BigDecimal.ZERO) {\n"
        "            updateStatus(\"Trade blocked by total position cap: ${ticker.symbol} held≈${availableBase.multiply(price).setScale(2, RoundingMode.DOWN)} $quoteAsset, pendingBUY≈${pendingBuyExposure.setScale(2, RoundingMode.DOWN)} $quoteAsset, cap=${adaptivePositionCap.setScale(2, RoundingMode.DOWN)}.\", \"WARN\")\n"
        "            return ExecutionAttemptResult(false)\n"
        "        }\n"
        "        val exposureAwareCap = adaptivePositionCap.min(remainingPositionCapacity)\n"
        "        val perOrderCap = if (useMarketOrder) exposureAwareCap.min(settings.maxMarketOrderEur) else exposureAwareCap\n"
    )
    text = replace_once(text, old_cap, new_cap, "BotController total exposure cap")

    pattern = re.escape('        val executedQtyForRecord = result.executedQuantity.takeIf { it > BigDecimal.ZERO } ?: quantity\n') + r'.*?' + re.escape('        return ExecutionAttemptResult(true, quoteAsset, reservedAmount)\n')
    replacement = (
        "        val reservedAmount = if (side == OrderSide.BUY) targetNotional.multiply(feeReserveMultiplier).setScale(2, RoundingMode.UP) else BigDecimal.ZERO\n"
        "        // CTS_V407_EXECUTION_INTEGRITY: accepted/resting LIMIT orders are not trades.\n"
        "        // Never fabricate a fill by substituting requested quantity when the exchange\n"
        "        // reports executedQuantity=0. PAPER deferred fills and Kraken sync record later.\n"
        "        if (result.executedQuantity <= BigDecimal.ZERO) {\n"
        "            updateStatus(\"Order accepted/pending with no confirmed fill: ${result.side} ${result.symbol} orderId=${result.exchangeOrderId}. No trade/lifecycle fill was recorded.\", \"INFO\")\n"
        "            sendRemoteAlert(\n"
        "                settings,\n"
        "                \"Order accepted / pending\",\n"
        "                \"${result.side} ${result.symbol} $orderModeLabel accepted as orderId=${result.exchangeOrderId}; confirmed fill quantity=0, so accounting is deferred until a real/simulated fill exists.\"\n"
        "            )\n"
        "            return ExecutionAttemptResult(true, quoteAsset, reservedAmount)\n"
        "        }\n"
        "        val executedQtyForRecord = result.executedQuantity\n"
        "        val averagePriceForRecord = result.averagePrice.takeIf { it > BigDecimal.ZERO } ?: price\n"
        "        val feeForRecord = result.fee.takeIf { it > BigDecimal.ZERO }\n"
        "            ?: averagePriceForRecord.multiply(executedQtyForRecord).multiply(BigDecimal(\"0.001\")).setScale(8, RoundingMode.HALF_UP)\n"
        "        val notionalForRecord = averagePriceForRecord.multiply(executedQtyForRecord).setScale(8, RoundingMode.HALF_UP)\n"
        "        dao.insertTrade(\n"
        "            TradeEntity(\n"
        "                symbol = result.symbol,\n"
        "                side = result.side.name,\n"
        "                quantity = executedQtyForRecord.toPlainString(),\n"
        "                priceEur = averagePriceForRecord.toPlainString(),\n"
        "                feeEur = feeForRecord.toPlainString(),\n"
        "                paper = result.paper,\n"
        "                aiScore = decision.finalScore,\n"
        "                aiReason = decision.explanation,\n"
        "                clientOrderId = request.clientOrderId,\n"
        "                exchangeOrderId = result.exchangeOrderId,\n"
        "                timestampEpochMs = result.timestamp.toEpochMilli()\n"
        "            )\n"
        "        )\n"
        "        updateStatus(\"Order filled: ${result.side} ${result.symbol} ${if (result.paper) \"PAPER\" else \"LIVE\"}. qty=${executedQtyForRecord.stripTrailingZeros().toPlainString()} avg=${averagePriceForRecord.stripTrailingZeros().toPlainString()} fee=${feeForRecord.stripTrailingZeros().toPlainString()} orderId=${result.exchangeOrderId}\", if (result.paper) \"INFO\" else \"LIVE\")\n"
        "        sendRemoteAlert(\n"
        "            settings,\n"
        "            \"Order filled\",\n"
        "            buildString {\n"
        "                appendLine(\"${result.side} ${result.symbol} ${if (result.paper) \"PAPER\" else \"LIVE\"}\")\n"
        "                appendLine(\"orderType=$orderModeLabel\")\n"
        "                appendLine(\"amount=${executedQtyForRecord.stripTrailingZeros().toPlainString()}\")\n"
        "                appendLine(\"price=${averagePriceForRecord.stripTrailingZeros().toPlainString()} $quoteAsset\")\n"
        "                appendLine(\"notional≈${notionalForRecord.stripTrailingZeros().toPlainString()} $quoteAsset\")\n"
        "                appendLine(\"fee=${feeForRecord.stripTrailingZeros().toPlainString()} $quoteAsset\")\n"
        "                appendLine(\"orderId=${result.exchangeOrderId}\")\n"
        "            }\n"
        "        )\n"
        "        return ExecutionAttemptResult(true, quoteAsset, reservedAmount)\n"
    )
    text = replace_regex_once(text, pattern, replacement, "BotController zero-fill phantom-trade fix")

    write(path, text)
    print(f"patched: {path}")


def main() -> None:
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()
    print(f"CTS v4.0.7 stabilization root: {root}")

    utility = root / "app/src/main/java/com/ksp/cryptobot/execution/PaperExecutionIntegrity.kt"
    test = root / "app/src/test/java/com/ksp/cryptobot/execution/PaperExecutionIntegrityTest.kt"
    write(utility, INTEGRITY_SOURCE)
    write(test, TEST_SOURCE)
    print(f"wrote: {utility}")
    print(f"wrote: {test}")

    controller = root / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    patch_controller(controller)

    paper_candidates = [
        root / "app/src/main/java/com/ksp/cryptobot/exchange/PaperExchangeClient.kt",
        root / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/exchange/PaperExchangeClient.kt",
    ]
    patched_any = False
    for path in paper_candidates:
        if path.exists():
            patch_paper_client(path)
            patched_any = True
    if not patched_any:
        fail("No PaperExchangeClient.kt found in effective app or migration overlay")

    print("PASS | v4.0.7 stabilization source patch applied")


if __name__ == "__main__":
    main()
