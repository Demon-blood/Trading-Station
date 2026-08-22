#!/usr/bin/env python3
"""Crypto TradeStation v4.0.7 execution-integrity and portfolio-truth stabilization.

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


OPERATIONAL_HEALTH_SOURCE = r'''package com.ksp.cryptobot.governance

data class OperationalEventFact(
    val eventType: String,
    val severity: String,
    val reason: String,
    val timestampEpochMs: Long
)

data class OperationalHealthAssessment(
    val weightedCriticalScore: Int,
    val criticalEvents: Int,
    val ignoredProviderNoise: Int,
    val consideredEvents: Int
)

object OperationalErrorClassifier {
    private val providerNames = listOf(
        "gdelt", "gnews", "guardian", "marketaux", "newsapi", "newsdata", "cryptopanic", "rss"
    )
    private val quotaTerms = listOf(
        "rate limit", "ratelimit", "too many requests", "request limit", "quota", "usage_limit",
        "usage limit", "api credits", "apilimitexceeded", "http 429", "http 402"
    )

    fun isProviderQuotaNoise(eventType: String, reason: String): Boolean {
        val type = eventType.lowercase()
        val body = reason.lowercase()
        val providerNamed = providerNames.any(body::contains)
        val quotaNamed = quotaTerms.any(body::contains)
        return providerNamed && quotaNamed && type !in setOf(
            "order_error", "handoff_protective_exit_failure", "execution_integrity_failure", "database_error"
        )
    }

    fun assess(events: List<OperationalEventFact>, sinceEpochMs: Long): OperationalHealthAssessment {
        var score = 0
        var critical = 0
        var ignored = 0
        var considered = 0
        events.asSequence().filter { it.timestampEpochMs >= sinceEpochMs }.forEach { event ->
            if (isProviderQuotaNoise(event.eventType, event.reason)) {
                ignored += 1
                return@forEach
            }
            considered += 1
            val weight = when (event.eventType.lowercase()) {
                "execution_integrity_failure", "database_error" -> 20
                "handoff_protective_exit_failure" -> 10
                "order_error" -> 5
                "watchdog_error" -> 2
                "anomaly_event" -> 0
                else -> when (event.severity.uppercase()) {
                    "CRITICAL" -> 5
                    "HIGH" -> 2
                    else -> 0
                }
            }
            if (weight > 0) critical += 1
            score += weight
        }
        return OperationalHealthAssessment(score, critical, ignored, considered)
    }
}
'''

OPERATIONAL_HEALTH_TEST_SOURCE = r'''package com.ksp.cryptobot.governance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperationalErrorClassifierTest {
    @Test
    fun newsQuotaErrorsDoNotTripExecutionKillScore() {
        val now = 10_000L
        val result = OperationalErrorClassifier.assess(
            listOf(
                OperationalEventFact("watchdog_error", "HIGH", "NewsAPI-1 HTTP 429: rate limit exceeded", now),
                OperationalEventFact("watchdog_error", "HIGH", "Marketaux HTTP 402: usage_limit_reached", now)
            ),
            sinceEpochMs = 0L
        )
        assertEquals(0, result.weightedCriticalScore)
        assertEquals(2, result.ignoredProviderNoise)
    }

    @Test
    fun realOrderFailureTripsHighThreshold() {
        val result = OperationalErrorClassifier.assess(
            listOf(OperationalEventFact("order_error", "HIGH", "Kraken AddOrder failed", 100L)),
            sinceEpochMs = 0L
        )
        assertEquals(5, result.weightedCriticalScore)
        assertEquals(1, result.criticalEvents)
    }

    @Test
    fun genericHttp429WithoutNewsProviderNameIsNotSilentlyIgnored() {
        assertFalse(OperationalErrorClassifier.isProviderQuotaNoise("watchdog_error", "Kraken HTTP 429"))
        assertTrue(OperationalErrorClassifier.isProviderQuotaNoise("watchdog_error", "GDELT HTTP 429 rate limit"))
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
        "    private val settingsStore = appContext?.let { AppSettingsStore(it) }\n"
        "    private val repairPrefs = appContext?.getSharedPreferences(\"paper_repair_v407\", Context.MODE_PRIVATE)\n",
        f"{path}: settings store",
    )
    text = replace_once(
        text,
        "    private val orderMutex = Mutex()\n",
        "    // CTS_V407_EXECUTION_INTEGRITY: every PaperExchangeClient instance shares one\n"
        "    // mutex because all instances mutate the same SharedPreferences wallet/order state.\n"
        "    companion object {\n"
        "        val STARTING_BALANCE_EUR: BigDecimal = BigDecimal(\"1000.00\")\n"
        "        private val globalOrderMutex = Mutex()\n"
        "        private val repairMutex = Mutex()\n"
        "    }\n",
        f"{path}: process-wide paper mutex + starting balance source of truth",
    )
    text = text.replace(
        'private val memoryBalances = linkedMapOf("EUR" to BigDecimal("1000.00"))',
        'private val memoryBalances = linkedMapOf("EUR" to STARTING_BALANCE_EUR)'
    )
    text = text.replace(
        'walletPrefs.edit().putBoolean("seeded", true).putString("EUR", "1000.00").apply()',
        'walletPrefs.edit().putBoolean("seeded", true).putString("EUR", STARTING_BALANCE_EUR.toPlainString()).apply()'
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


def patch_app_dao(path: Path) -> None:
    text = read(path)
    if "deletePaperTradeById" in text:
        print(f"paper repair DAO already patched: {path}")
        return
    anchor = '    @Query("DELETE FROM trades")\n    suspend fun clearTradesForRestore()\n'
    patch = (
        '    // CTS_V407_LEGACY_PAPER_REPAIR: used only by the one-time duplicate-fill repair.\n'
        '    @Query("DELETE FROM trades WHERE id = :id AND paper = 1")\n'
        '    suspend fun deletePaperTradeById(id: Long): Int\n\n' + anchor
    )
    text = replace_once(text, anchor, patch, f"{path}: paper trade delete query")
    write(path, text)
    print(f"patched paper-repair DAO: {path}")


def patch_paper_legacy_repair(path: Path) -> None:
    text = read(path)
    # v3 can be applied on top of the earlier v2 pack. Ensure the new repair fields exist
    # even when the v2 execution marker makes patch_paper_client() return early.
    if "private val repairPrefs" not in text:
        anchor = "    private val settingsStore = appContext?.let { AppSettingsStore(it) }\n"
        text = replace_once(text, anchor, anchor + "    private val repairPrefs = appContext?.getSharedPreferences(\"paper_repair_v407\", Context.MODE_PRIVATE)\n", f"{path}: repair prefs")
    if "private val repairMutex" not in text:
        anchor = "        private val globalOrderMutex = Mutex()\n"
        text = replace_once(text, anchor, anchor + "        private val repairMutex = Mutex()\n", f"{path}: repair mutex")
    if "CTS_V407_LEGACY_PAPER_REPAIR" in text:
        write(path, text)
        print(f"legacy PAPER repair already patched: {path}")
        return

    old_ticker = (
        "    override suspend fun getTicker(symbol: String): MarketTicker {\n"
        "        val ticker = rawTicker(symbol)\n"
    )
    new_ticker = (
        "    override suspend fun getTicker(symbol: String): MarketTicker {\n"
        "        repairLegacyDuplicateDeferredFillsIfNeeded()\n"
        "        val ticker = rawTicker(symbol)\n"
    )
    text = replace_once(text, old_ticker, new_ticker, f"{path}: repair before ticker/pending processing")

    old_available = "    override suspend fun getAvailableBalances(): Map<String, BigDecimal> = balances().filterValues { it > BigDecimal.ZERO }\n"
    new_available = (
        "    override suspend fun getAvailableBalances(): Map<String, BigDecimal> {\n"
        "        repairLegacyDuplicateDeferredFillsIfNeeded()\n"
        "        return balances().filterValues { it > BigDecimal.ZERO }\n"
        "    }\n"
    )
    text = replace_once(text, old_available, new_available, f"{path}: repair before available balances")

    old_portfolio = (
        "    override suspend fun getPortfolioBalances(): List<BalanceInfo> {\n"
        "        return balances()\n"
    )
    new_portfolio = (
        "    override suspend fun getPortfolioBalances(): List<BalanceInfo> {\n"
        "        repairLegacyDuplicateDeferredFillsIfNeeded()\n"
        "        return balances()\n"
    )
    text = replace_once(text, old_portfolio, new_portfolio, f"{path}: repair before portfolio")

    old_place = "    override suspend fun placeOrder(request: OrderRequest): OrderResult = globalOrderMutex.withLock {\n"
    new_place = (
        "    override suspend fun placeOrder(request: OrderRequest): OrderResult {\n"
        "        repairLegacyDuplicateDeferredFillsIfNeeded()\n"
        "        return globalOrderMutex.withLock {\n"
    )
    text = replace_once(text, old_place, new_place, f"{path}: repair before order")
    result_anchor = (
        "        OrderResult(\n"
        "            exchangeOrderId = id,\n"
        "            symbol = clean,\n"
        "            side = request.side,\n"
        "            executedQuantity = fill.quantity,\n"
        "            averagePrice = fill.averagePrice,\n"
        "            fee = fill.fee,\n"
        "            paper = true,\n"
        "            realizedPnlQuote = realizedPnl\n"
        "        )\n"
        "    }\n\n"
        "    override suspend fun getOpenOrders()"
    )
    result_patch = result_anchor.replace("    }\n\n    override suspend fun getOpenOrders()", "        }\n    }\n\n    override suspend fun getOpenOrders()")
    text = replace_once(text, result_anchor, result_patch, f"{path}: close placeOrder wrapper")

    method_anchor = "    override suspend fun getTradingFeeSchedule(symbol: String): TradingFeeSchedule = TradingFeeSchedule(\n"
    repair_methods = r'''    // CTS_V407_LEGACY_PAPER_REPAIR
    suspend fun repairLegacyDuplicateDeferredFillsIfNeeded(): String = repairMutex.withLock {
        val prefs = repairPrefs ?: return@withLock "PAPER repair not applicable (no Android context)."
        if (prefs.getBoolean("completed", false)) return@withLock legacyRepairStatus()
        val dao = tradeDao ?: return@withLock "PAPER repair unavailable (database not attached)."
        val pendingRepair = prefs.getBoolean("pending_rebuild", false)
        val allPaper = dao.allTradesSnapshot().filter { it.paper }.sortedBy { it.timestampEpochMs }

        fun sourceOrder(trade: TradeEntity): String? = Regex("Deferred PAPER fill: source order=([^;]+)")
            .find(trade.aiReason)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
        fun normalized(value: String): String = value.toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString() ?: value.trim()
        fun fingerprint(trade: TradeEntity, source: String): String = listOf(
            source, normalizeSymbol(trade.symbol), trade.side.uppercase(), normalized(trade.quantity),
            normalized(trade.priceEur), normalized(trade.feeEur)
        ).joinToString("|")

        val duplicates = mutableListOf<TradeEntity>()
        val keptByFingerprint = mutableMapOf<String, TradeEntity>()
        allPaper.forEach { trade ->
            val source = sourceOrder(trade) ?: return@forEach
            val key = fingerprint(trade, source)
            val previous = keptByFingerprint[key]
            val withinReplayWindow = previous != null && kotlin.math.abs(trade.timestampEpochMs - previous.timestampEpochMs) <= 2_000L
            val legacyId = trade.exchangeOrderId.startsWith("$source-paperfill-")
            val previousLegacy = previous?.exchangeOrderId?.startsWith("$source-paperfill-") == true
            if (previous != null && withinReplayWindow && legacyId && previousLegacy) duplicates += trade
            else keptByFingerprint[key] = trade
        }

        if (duplicates.isEmpty() && !pendingRepair) {
            check(prefs.edit()
                .putBoolean("completed", true)
                .putLong("completed_epoch_ms", System.currentTimeMillis())
                .putInt("removed_count", 0)
                .putString("status", "No legacy duplicate deferred PAPER fills detected.")
                .commit()) { "Could not persist PAPER repair status." }
            return@withLock legacyRepairStatus()
        }

        val duplicateIds = duplicates.map { it.id }.toSet()
        val cleanTrades = allPaper.filterNot { it.id in duplicateIds }
        val rebuiltWallet = linkedMapOf<String, BigDecimal>("EUR" to STARTING_BALANCE_EUR)
        val rebuiltBasis = linkedMapOf<String, PaperCostBasis>()
        val tolerance = BigDecimal("0.00000001")
        cleanTrades.forEach { trade ->
            val qty = trade.quantity.toBigDecimalOrNull()?.max(BigDecimal.ZERO) ?: BigDecimal.ZERO
            val price = trade.priceEur.toBigDecimalOrNull()?.max(BigDecimal.ZERO) ?: BigDecimal.ZERO
            val fee = trade.feeEur.toBigDecimalOrNull()?.max(BigDecimal.ZERO) ?: BigDecimal.ZERO
            if (qty <= BigDecimal.ZERO || price <= BigDecimal.ZERO) return@forEach
            val symbol = normalizeSymbol(trade.symbol)
            val base = baseAsset(symbol)
            val quote = quoteAsset(symbol)
            val key = "$base|$quote"
            val basis = rebuiltBasis[key] ?: PaperCostBasis(BigDecimal.ZERO, BigDecimal.ZERO)
            val notional = qty.multiply(price)
            if (trade.side.equals(OrderSide.BUY.name, ignoreCase = true)) {
                val debit = notional.add(fee)
                val availableQuote = rebuiltWallet[quote] ?: BigDecimal.ZERO
                if (debit > availableQuote.add(tolerance)) error("PAPER repair refused: journal replay requires $debit $quote but only $availableQuote is available before ${trade.id}.")
                rebuiltWallet[quote] = availableQuote.subtract(debit).max(BigDecimal.ZERO)
                rebuiltWallet[base] = (rebuiltWallet[base] ?: BigDecimal.ZERO).add(qty)
                rebuiltBasis[key] = PaperCostBasis(basis.quantity.add(qty), basis.totalCostQuote.add(debit))
            } else if (trade.side.equals(OrderSide.SELL.name, ignoreCase = true)) {
                val availableBase = rebuiltWallet[base] ?: BigDecimal.ZERO
                if (qty > availableBase.add(tolerance)) error("PAPER repair refused: journal replay sells $qty $base but only $availableBase is available before ${trade.id}.")
                val avgCost = if (basis.quantity > BigDecimal.ZERO) basis.totalCostQuote.divide(basis.quantity, 16, RoundingMode.HALF_UP) else BigDecimal.ZERO
                val allocated = avgCost.multiply(qty)
                rebuiltWallet[base] = availableBase.subtract(qty).max(BigDecimal.ZERO)
                rebuiltWallet[quote] = (rebuiltWallet[quote] ?: BigDecimal.ZERO).add(notional.subtract(fee)).max(BigDecimal.ZERO)
                val remainQty = basis.quantity.subtract(qty).max(BigDecimal.ZERO)
                val remainCost = basis.totalCostQuote.subtract(allocated).max(BigDecimal.ZERO)
                rebuiltBasis[key] = PaperCostBasis(remainQty, if (remainQty > BigDecimal.ZERO) remainCost else BigDecimal.ZERO)
            }
        }

        check(prefs.edit().putBoolean("pending_rebuild", true).commit()) { "Could not persist PAPER repair checkpoint." }
        duplicates.forEach { duplicate -> dao.deletePaperTradeById(duplicate.id) }
        saveBalances(rebuiltWallet)
        costBasisPrefs?.let { basisPrefs ->
            val edit = basisPrefs.edit().clear()
            rebuiltBasis.forEach { (key, basis) ->
                if (basis.quantity > BigDecimal.ZERO) edit.putString(key, "${basis.quantity.toPlainString()}|${basis.totalCostQuote.toPlainString()}")
            }
            check(edit.commit()) { "Could not persist rebuilt PAPER cost basis." }
        }

        dao.openPositionsSnapshot().forEach { position ->
            val base = baseAsset(position.symbol)
            val quantity = rebuiltWallet[base] ?: BigDecimal.ZERO
            if (quantity > BigDecimal.ZERO) dao.upsertPosition(position.copy(quantity = quantity.toPlainString(), updatedAtEpochMs = System.currentTimeMillis(), status = "OPEN"))
            else dao.updatePositionStatus(position.symbol, "CLOSED", System.currentTimeMillis())
        }

        val removed = duplicates.size
        check(prefs.edit()
            .putBoolean("pending_rebuild", false)
            .putBoolean("completed", true)
            .putLong("completed_epoch_ms", System.currentTimeMillis())
            .putInt("removed_count", removed)
            .putString("removed_ids", duplicates.joinToString(",") { it.id.toString() })
            .putString("status", "Rebuilt PAPER wallet/cost basis after removing $removed legacy duplicate deferred fill(s).")
            .commit()) { "Could not finalize PAPER repair status." }
        legacyRepairStatus()
    }

    fun legacyRepairStatus(): String {
        val prefs = repairPrefs ?: return "PAPER repair not applicable (no Android context)."
        return buildString {
            append(prefs.getString("status", "PAPER repair has not run yet.") ?: "PAPER repair has not run yet.")
            append(" completed=").append(prefs.getBoolean("completed", false))
            append(" pending=").append(prefs.getBoolean("pending_rebuild", false))
            append(" removed=").append(prefs.getInt("removed_count", 0))
            append(" completedAt=").append(prefs.getLong("completed_epoch_ms", 0L))
        }
    }

'''
    text = replace_once(text, method_anchor, repair_methods + method_anchor, f"{path}: legacy duplicate repair methods")
    write(path, text)
    print(f"patched legacy PAPER repair: {path}")


def patch_operational_health_engine(path: Path) -> None:
    text = read(path)
    if "operationalHealth = OperationalErrorClassifier.assess" in text:
        print(f"operational classifier already patched: {path}")
        return
    old = "        val operationalErrors = dao.recentOperationalErrorCount(now - 60L * 60L * 1000L)\n"
    new = (
        "        val operationalHealth = OperationalErrorClassifier.assess(\n"
        "            recentEvents.map { event -> OperationalEventFact(event.eventType, event.severity, event.reason, event.timestampEpochMs) },\n"
        "            sinceEpochMs = now - 60L * 60L * 1000L\n"
        "        )\n"
        "        val operationalErrors = operationalHealth.weightedCriticalScore\n"
    )
    text = replace_once(text, old, new, f"{path}: classified operational score")
    reason_anchor = "            anomaly.reason, safe.reason, kill.reason, risk.reason, quality.reason, counterReason\n"
    reason_patch = "            anomaly.reason, safe.reason, kill.reason + \" Operational classification: score=${operationalHealth.weightedCriticalScore}, critical=${operationalHealth.criticalEvents}, ignoredNewsQuota=${operationalHealth.ignoredProviderNoise}.\", risk.reason, quality.reason, counterReason\n"
    text = replace_once(text, reason_anchor, reason_patch, f"{path}: operational diagnostic reason")
    write(path, text)
    print(f"patched operational health engine: {path}")


def patch_kill_switch_wording(path: Path) -> None:
    text = read(path)
    old = '            return KillSwitchAssessment(false, "HIGH", "Operational kill-switch: $recentOperationalErrors recent API/runtime errors detected.")\n'
    new = '            return KillSwitchAssessment(false, "HIGH", "Operational kill-switch: weighted critical error score=$recentOperationalErrors (news provider quota/rate-limit noise excluded).")\n'
    if old in text:
        text = text.replace(old, new, 1)
        write(path, text)
        print(f"patched kill-switch wording: {path}")
    elif "weighted critical error score" in text:
        print(f"kill-switch wording already patched: {path}")
    else:
        fail(f"{path}: kill-switch operational wording anchor not found")


def patch_safe_mode_provider_noise(path: Path) -> None:
    text = read(path)
    if "providerQuotaNoise" in text:
        print(f"safe-mode provider noise already patched: {path}")
        return
    old = (
        "        val recentBad = recentEvents.take(80).count {\n"
        "            it.eventType in setOf(\"anomaly_event\", \"watchdog_error\", \"order_error\", \"handoff_protective_exit_failure\") || it.severity in setOf(\"HIGH\", \"CRITICAL\")\n"
        "        }\n"
    )
    if old in text:
        new = (
            "        val recentBad = recentEvents.take(80).count { event ->\n"
            "            val providerQuotaNoise = OperationalErrorClassifier.isProviderQuotaNoise(event.eventType, event.reason)\n"
            "            !providerQuotaNoise && (event.eventType in setOf(\"anomaly_event\", \"watchdog_error\", \"order_error\", \"handoff_protective_exit_failure\", \"execution_integrity_failure\", \"database_error\") || event.severity in setOf(\"HIGH\", \"CRITICAL\"))\n"
            "        }\n"
        )
        text = text.replace(old, new, 1)
        write(path, text)
        print(f"patched safe mode provider-noise exclusion: {path}")
        return
    # Newer diagnostics patches already narrow safe-mode causative event types.
    # Do not make this stabilization release brittle to that newer shape: the
    # ProductionIntelligence kill-switch is independently patched to use the
    # weighted classifier below, which is the source of the 79/80-error HIGH
    # state seen in the 4.0.6 diagnostic.
    if "causativeErrorTypes" in text:
        print(f"safe-mode uses newer causative-error filtering; no legacy rewrite needed: {path}")
        return
    print(f"WARN: safe-mode recentBad shape changed; leaving it untouched while weighted production classifier remains enforced: {path}")


def patch_database_and_repair_diagnostics(path: Path) -> None:
    text = read(path)
    if "databaseTableStorageDiagnostics" in text and "[PAPER_REPAIR]" in text:
        print(f"database/PAPER diagnostics already patched: {path}")
        return
    method_anchor = "    suspend fun exportFullDiagnosticsToFile(\n"
    helper = r'''    // CTS_V407_STORAGE_VISIBILITY
    private fun databaseTableStorageDiagnostics(): List<String> {
        val db = AppDatabase.get(appContext).openHelper.readableDatabase
        fun scalarLong(sql: String): Long? = runCatching {
            db.query(sql).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        }.getOrNull()
        val pageSize = scalarLong("PRAGMA page_size") ?: 0L
        val pageCount = scalarLong("PRAGMA page_count") ?: 0L
        val freePages = scalarLong("PRAGMA freelist_count") ?: 0L
        val lines = mutableListOf<String>()
        lines += "SUMMARY|pageSize=$pageSize|pageCount=$pageCount|freePages=$freePages|dbBytes=${pageSize * pageCount}|reclaimableBytes=${pageSize * freePages}"
        val tables = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table' ORDER BY name").use { cursor ->
            while (cursor.moveToNext()) tables += cursor.getString(0)
        }
        fun retention(name: String): String = when (name) {
            "trades", "tax_lots", "tax_report_rows" -> "PERMANENT_LEDGER"
            "positions", "learned_symbol_profiles", "learned_strategy_profiles", "learned_hold_profiles", "production_intelligence_state", "research_profiles" -> "STATE_KEEP_CURRENT_HISTORY_BOUNDED"
            "signals", "ai_decisions", "news_articles", "learning_feature_snapshots", "self_learning_audit", "governance_events", "execution_quality_events", "advanced_execution_events", "research_events" -> "ROLLING_TELEMETRY_CANDIDATE"
            else -> "REVIEW"
        }
        tables.forEach { table ->
            val quoted = table.replace("\"", "\"\"")
            val literal = table.replace("'", "''")
            val rows = scalarLong("SELECT COUNT(*) FROM \"$quoted\"") ?: -1L
            val bytes = runCatching {
                db.query("SELECT COALESCE(SUM(pgsize),0) FROM dbstat WHERE name='$literal'").use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }
            }.getOrNull()
            var timeColumn: String? = null
            db.query("PRAGMA table_info(\"$quoted\")").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                val candidates = listOf("timestampEpochMs", "fetchedAtEpochMs", "updatedAtEpochMs", "createdAtEpochMs", "openedAtEpochMs")
                val columns = mutableSetOf<String>()
                while (cursor.moveToNext() && nameIndex >= 0) columns += cursor.getString(nameIndex)
                timeColumn = candidates.firstOrNull { it in columns }
            }
            val oldest = timeColumn?.let { col -> scalarLong("SELECT MIN(\"$col\") FROM \"$quoted\"") }
            val newest = timeColumn?.let { col -> scalarLong("SELECT MAX(\"$col\") FROM \"$quoted\"") }
            lines += "table=$table|rows=$rows|bytesApprox=${bytes?.toString() ?: "UNAVAILABLE"}|timeColumn=${timeColumn ?: "NONE"}|oldest=${oldest ?: 0L}|newest=${newest ?: 0L}|retention=${retention(table)}"
        }
        return lines
    }

'''
    text = replace_once(text, method_anchor, helper + method_anchor, f"{path}: table diagnostics helper")
    provider_anchor = (
        "            val providerHealth = runCatching {\n"
        "                com.ksp.cryptobot.news.NewsProviderHealthRegistry.snapshot().map { it.toString() }\n"
        "            }.getOrDefault(emptyList())\n"
    )
    provider_patch = provider_anchor + (
        "            val databaseTables = runCatching { databaseTableStorageDiagnostics() }\n"
        "                .getOrElse { error -> listOf(\"table diagnostics unavailable: ${error.message}\") }\n"
        "            val paperRepairStatus = if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) {\n"
        "                runCatching { PaperExchangeClient(appContext).legacyRepairStatus() }.getOrElse { \"PAPER repair status unavailable: ${it.message}\" }\n"
        "            } else \"Not applicable outside PAPER mode.\"\n"
    )
    text = replace_once(text, provider_anchor, provider_patch, f"{path}: collect table/repair diagnostics")
    news_section = (
        "                appendLine(\"[NEWS_PROVIDER_HEALTH]\")\n"
        "                if (providerHealth.isEmpty()) appendLine(\"no provider health snapshot\")\n"
    )
    new_sections = (
        "                appendLine(\"[PAPER_REPAIR]\")\n"
        "                appendLine(sanitize(paperRepairStatus))\n"
        "                appendLine()\n\n"
        "                appendLine(\"[DATABASE_TABLES]\")\n"
        "                databaseTables.forEach { appendLine(sanitize(it)) }\n"
        "                appendLine()\n\n" + news_section
    )
    text = replace_once(text, news_section, new_sections, f"{path}: report table/repair sections")
    write(path, text)
    print(f"patched database/PAPER diagnostics: {path}")


def patch_runtime_invariant_verification(path: Path) -> None:
    text = read(path)
    if "Position Exposure Invariant" in text and "PAPER Legacy Duplicate Repair" in text:
        print(f"runtime invariant verification already patched: {path}")
        return
    anchor = '        add("PASS", "Duplicate Position Protection", if (settings.duplicatePositionProtectionEnabled) "Enabled. Blocks additional BUY entries when an OPEN position or existing base holding exists; SELL remains allowed." else "Disabled.")\n'
    patch = anchor + r'''        if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) {
            val paper = PaperExchangeClient(appContext)
            val repair = runCatching { paper.repairLegacyDuplicateDeferredFillsIfNeeded() }
            repair.onSuccess { detail -> add("PASS", "PAPER Legacy Duplicate Repair", detail) }
                .onFailure { error -> add("FAIL", "PAPER Legacy Duplicate Repair", error.message ?: "Repair failed") }
            runCatching { paper.getPortfolioBalances() }
                .onSuccess { assets ->
                    val violations = assets.filter { it.asset.uppercase() !in setOf("EUR", "ZEUR") && it.eurValue > BigDecimal.ZERO }.mapNotNull { asset ->
                        val symbol = "${asset.asset.uppercase()}EUR".replace("XBTEUR", "BTCEUR")
                        val cap = settings.effectiveMaxPositionFor(symbol)
                        if (asset.eurValue > cap.add(BigDecimal("0.05"))) "$symbol=${asset.eurValue}>$cap" else null
                    }
                    if (violations.isEmpty()) add("PASS", "Position Exposure Invariant", "Every PAPER asset is within its effective Max Position cap.")
                    else add("FAIL", "Position Exposure Invariant", "Exceeded: ${violations.joinToString(", ")}")
                }
                .onFailure { error -> add("FAIL", "Position Exposure Invariant", "Could not evaluate PAPER exposure: ${error.message}") }
        }
'''
    text = replace_once(text, anchor, patch, f"{path}: real position invariant verification")
    write(path, text)
    print(f"patched runtime invariant verification: {path}")


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




def patch_portfolio_model(path: Path) -> None:
    text = read(path)
    if "performanceBaselineEur" in text:
        print(f"already patched: {path}")
        return
    old = (
        "data class PortfolioSnapshot(\n"
        "    val provider: ExchangeProvider,\n"
        "    val totalValueEur: BigDecimal,\n"
        "    val freeEur: BigDecimal,\n"
        "    val assets: List<BalanceInfo>,\n"
        "    val refreshedAt: Instant = Instant.now(),\n"
        "    val warning: String = \"\"\n"
        ")"
    )
    new = (
        "data class PortfolioSnapshot(\n"
        "    val provider: ExchangeProvider,\n"
        "    val totalValueEur: BigDecimal,\n"
        "    val freeEur: BigDecimal,\n"
        "    val assets: List<BalanceInfo>,\n"
        "    val refreshedAt: Instant = Instant.now(),\n"
        "    // PAPER has a deterministic seeded starting balance. LIVE providers do not\n"
        "    // claim an all-time baseline unless one is explicitly tracked.\n"
        "    val performanceBaselineEur: BigDecimal? = null,\n"
        "    val warning: String = \"\"\n"
        ")"
    )
    text = replace_once(text, old, new, "PortfolioSnapshot performance baseline")
    write(path, text)
    print(f"patched: {path}")


def patch_controller_portfolio_baseline(path: Path) -> None:
    text = read(path)
    if "performanceBaselineEur = portfolioPerformanceBaseline" in text:
        print(f"portfolio baseline already patched: {path}")
        return

    method_anchor = (
        "    suspend fun loadPortfolioSnapshot(settings: BotSettings = settingsStore.load()): PortfolioSnapshot {\n"
        "        updateStatus(\"Portfolio refresh started. Provider=${settings.exchangeProvider}\")\n"
        "        val exchange = createExchange(settings)\n"
    )
    method_patch = method_anchor + (
        "        val portfolioPerformanceBaseline = if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) {\n"
        "            PaperExchangeClient.STARTING_BALANCE_EUR\n"
        "        } else null\n"
    )
    text = replace_once(text, method_anchor, method_patch, "BotController paper performance baseline")

    empty_anchor = (
        "                assets = emptyList(),\n"
        "                warning = \"No portfolio balances returned. Check API permissions or selected exchange.\"\n"
    )
    empty_patch = (
        "                assets = emptyList(),\n"
        "                performanceBaselineEur = portfolioPerformanceBaseline,\n"
        "                warning = \"No portfolio balances returned. Check API permissions or selected exchange.\"\n"
    )
    text = replace_once(text, empty_anchor, empty_patch, "BotController empty portfolio baseline")

    normal_anchor = "        val snapshot = PortfolioSnapshot(settings.exchangeProvider, total, freeEur, priced, warning = warning)\n"
    normal_patch = (
        "        val snapshot = PortfolioSnapshot(\n"
        "            provider = settings.exchangeProvider,\n"
        "            totalValueEur = total,\n"
        "            freeEur = freeEur,\n"
        "            assets = priced,\n"
        "            performanceBaselineEur = portfolioPerformanceBaseline,\n"
        "            warning = warning\n"
        "        )\n"
    )
    text = replace_once(text, normal_anchor, normal_patch, "BotController populated portfolio baseline")
    write(path, text)
    print(f"patched portfolio baseline: {path}")


def patch_portfolio_ui(path: Path) -> None:
    text = read(path)
    if "All-Time P/L" in text and "24H Realized P/L" in text:
        print(f"portfolio P/L UI already patched: {path}")
        return

    vars_anchor = (
        "    val total = snapshot?.totalValueEur ?: BigDecimal.ZERO\n"
        "    val pnl24 = realized24h(trades)\n"
        "    val pct24 = if (total.subtract(pnl24) > BigDecimal.ZERO) pnl24.multiply(BigDecimal(\"100\")).divide(total.subtract(pnl24), 4, RoundingMode.HALF_UP) else BigDecimal.ZERO\n"
    )
    vars_patch = vars_anchor + (
        "    val allTimeBaseline = snapshot?.performanceBaselineEur\n"
        "    val allTimePnl = allTimeBaseline?.let { total.subtract(it) }\n"
        "    val allTimePct = if (allTimeBaseline != null && allTimeBaseline > BigDecimal.ZERO && allTimePnl != null) {\n"
        "        allTimePnl.multiply(BigDecimal(\"100\")).divide(allTimeBaseline, 4, RoundingMode.HALF_UP)\n"
        "    } else null\n"
    )
    text = replace_once(text, vars_anchor, vars_patch, "Portfolio screen all-time variables")

    card_pattern = (
        r'(?m)^[ \t]*Text\(\"24H P/L\", color = PreviewMuted, fontSize = 9\.sp\)\n'
        r'[ \t]*Text\(\(if \(pnl24 >= BigDecimal\.ZERO\) \"\+\" else \"\"\) \+ euro\(pnl24\) \+ \" \(\" \+ \(if \(pct24 >= BigDecimal\.ZERO\) \"\+\" else \"\"\) \+ pct\(pct24\) \+ \"\)\", color = if \(pnl24 >= BigDecimal\.ZERO\) PreviewGreen else PreviewRed, fontSize = 10\.sp\)\n'
        r'[ \t]*Spacer\(Modifier\.height\(10\.dp\)\)\n'
    )
    card_patch = (
        "                            Text(\"24H Realized P/L\", color = PreviewMuted, fontSize = 9.sp)\n"
        "                            Text((if (pnl24 >= BigDecimal.ZERO) \"+\" else \"\") + euro(pnl24), color = if (pnl24 >= BigDecimal.ZERO) PreviewGreen else PreviewRed, fontSize = 10.sp)\n"
        "                            Spacer(Modifier.height(5.dp))\n"
        "                            Text(\"All-Time P/L\", color = PreviewMuted, fontSize = 9.sp)\n"
        "                            if (allTimePnl != null && allTimePct != null) {\n"
        "                                Text((if (allTimePnl >= BigDecimal.ZERO) \"+\" else \"\") + euro(allTimePnl) + \" (\" + (if (allTimePct >= BigDecimal.ZERO) \"+\" else \"\") + pct(allTimePct) + \")\", color = if (allTimePnl >= BigDecimal.ZERO) PreviewGreen else PreviewRed, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)\n"
        "                            } else {\n"
        "                                Text(\"N/A — baseline not recorded\", color = PreviewMuted2, fontSize = 9.sp)\n"
        "                            }\n"
        "                            Spacer(Modifier.height(10.dp))\n"
    )
    text = replace_regex_once(text, card_pattern, card_patch, "Portfolio card P/L truth labels")

    # The dashboard used the same ambiguous label. Make its semantics truthful too,
    # without adding another large metric block there.
    text = text.replace('Text("24H P/L", color = PreviewMuted, fontSize = 10.sp)', 'Text("24H Realized P/L", color = PreviewMuted, fontSize = 10.sp)', 1)

    write(path, text)
    print(f"patched portfolio P/L UI: {path}")

def main() -> None:
    root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()
    print(f"CTS v4.0.7 stabilization root: {root}")

    utility = root / "app/src/main/java/com/ksp/cryptobot/execution/PaperExecutionIntegrity.kt"
    test = root / "app/src/test/java/com/ksp/cryptobot/execution/PaperExecutionIntegrityTest.kt"
    operational = root / "app/src/main/java/com/ksp/cryptobot/governance/OperationalErrorClassifier.kt"
    operational_test = root / "app/src/test/java/com/ksp/cryptobot/governance/OperationalErrorClassifierTest.kt"
    write(utility, INTEGRITY_SOURCE)
    write(test, TEST_SOURCE)
    write(operational, OPERATIONAL_HEALTH_SOURCE)
    write(operational_test, OPERATIONAL_HEALTH_TEST_SOURCE)
    print(f"wrote: {utility}")
    print(f"wrote: {test}")
    print(f"wrote: {operational}")
    print(f"wrote: {operational_test}")

    dao_candidates = [
        root / "app/src/main/java/com/ksp/cryptobot/data/AppDao.kt",
        root / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/data/AppDao.kt",
    ]
    dao_patched = False
    for dao_path in dao_candidates:
        if dao_path.exists():
            patch_app_dao(dao_path)
            dao_patched = True
    if not dao_patched:
        fail("No AppDao.kt found for one-time PAPER repair")

    controller = root / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    patch_controller(controller)

    models = root / "app/src/main/java/com/ksp/cryptobot/core/Models.kt"
    patch_portfolio_model(models)
    patch_controller_portfolio_baseline(controller)

    preview_ui = root / "app/src/main/java/com/ksp/cryptobot/PreviewReplicaUi.kt"
    if preview_ui.exists():
        patch_portfolio_ui(preview_ui)
    else:
        fail("Generated PreviewReplicaUi.kt is missing; run v4 UI migration before stabilization")

    paper_candidates = [
        root / "app/src/main/java/com/ksp/cryptobot/exchange/PaperExchangeClient.kt",
        root / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/exchange/PaperExchangeClient.kt",
    ]
    patched_any = False
    for path in paper_candidates:
        if path.exists():
            patch_paper_client(path)
            patch_paper_legacy_repair(path)
            patched_any = True
    if not patched_any:
        fail("No PaperExchangeClient.kt found in effective app or migration overlay")

    production_candidates = [
        root / "app/src/main/java/com/ksp/cryptobot/governance/ProductionIntelligenceEngine.kt",
        root / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/governance/ProductionIntelligenceEngine.kt",
    ]
    kill_candidates = [
        root / "app/src/main/java/com/ksp/cryptobot/governance/KillSwitchEngine.kt",
        root / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/governance/KillSwitchEngine.kt",
    ]
    safe_candidates = [
        root / "app/src/main/java/com/ksp/cryptobot/governance/RiskBudgetAndSafeMode.kt",
        root / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/governance/RiskBudgetAndSafeMode.kt",
    ]
    for path in production_candidates:
        if path.exists(): patch_operational_health_engine(path)
    for path in kill_candidates:
        if path.exists(): patch_kill_switch_wording(path)
    for path in safe_candidates:
        if path.exists(): patch_safe_mode_provider_noise(path)

    patch_database_and_repair_diagnostics(controller)
    patch_runtime_invariant_verification(controller)

    print("PASS | v4.0.7 stabilization source patch applied")


if __name__ == "__main__":
    main()
