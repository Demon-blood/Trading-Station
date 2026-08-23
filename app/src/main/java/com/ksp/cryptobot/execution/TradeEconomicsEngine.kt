package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.OrderBookSnapshot
import com.ksp.cryptobot.core.OrderSide
import com.ksp.cryptobot.core.OrderType
import com.ksp.cryptobot.data.TradeEntity
import com.ksp.cryptobot.exchange.TradingFeeSchedule
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap

/**
 * M5 authoritative entry economics model.
 *
 * This answers one question:
 *
 *   After probability-weighted gains/losses and every modeled trading friction,
 *   is this entry still expected to make money?
 *
 * AI/model confidence is intentionally NOT treated as P(win). P(win) comes only
 * from realized outcomes, shrunk toward a neutral 50% prior when evidence is thin.
 */
data class TradeEconomicsInput(
    val symbol: String,
    val strategyId: String,
    val notionalQuote: BigDecimal,
    val entryPrice: BigDecimal,
    val targetPrice: BigDecimal,
    val stopPrice: BigDecimal,
    val orderType: OrderType,
    val postOnly: Boolean,
    val ticker: MarketTicker,
    val orderBook: OrderBookSnapshot?,
    val recentTrades: List<TradeEntity>,
    val feeSchedule: TradingFeeSchedule?,
    /** Research can evaluate many candidates without replacing final execution diagnostics. */
    val publishRuntime: Boolean = true,
    /** Future M6 hook. Current deterministic/local routing should pass zero. */
    val externalDecisionCostQuote: BigDecimal = BigDecimal.ZERO,
    /** Model-risk reserve. 0.0025 = 25 bps of entry notional. */
    val safetyMarginRate: BigDecimal = BigDecimal("0.0025")
)

data class TradeEconomicsAssessment(
    val allowed: Boolean,
    val symbol: String,
    val strategyId: String,
    val notionalQuote: BigDecimal,
    val quantity: BigDecimal,
    val probabilityWin: BigDecimal,
    val probabilitySource: String,
    val outcomeSamples: Int,
    val makerEntry: Boolean,
    val feeSource: String,
    val entryFeeRate: BigDecimal,
    val exitFeeRate: BigDecimal,
    val spreadRate: BigDecimal,
    val expectedWinQuote: BigDecimal,
    val expectedLossQuote: BigDecimal,
    val grossExpectedValueQuote: BigDecimal,
    val entryFeeQuote: BigDecimal,
    val expectedExitFeeQuote: BigDecimal,
    val spreadCostQuote: BigDecimal,
    val entrySlippageQuote: BigDecimal,
    val expectedExitSlippageQuote: BigDecimal,
    val externalDecisionCostQuote: BigDecimal,
    val safetyReserveQuote: BigDecimal,
    val totalExpectedCostQuote: BigDecimal,
    val netExpectedValueQuote: BigDecimal,
    val netExpectedValueRate: BigDecimal,
    val breakEvenWinProbability: BigDecimal,
    val riskRewardRatio: BigDecimal,
    val reason: String,
    val evaluatedAtEpochMs: Long = System.currentTimeMillis()
)

object TradeEconomicsRuntime {
    private val latest = ConcurrentHashMap<String, TradeEconomicsAssessment>()
    private fun key(symbol: String): String =
        symbol.uppercase().replace("/", "").replace("-", "").replace("_", "")

    fun publish(assessment: TradeEconomicsAssessment) {
        latest[key(assessment.symbol)] = assessment
    }

    fun snapshot(symbol: String): TradeEconomicsAssessment? = latest[key(symbol)]

    fun all(): List<TradeEconomicsAssessment> =
        latest.values.sortedByDescending { it.evaluatedAtEpochMs }

    fun clear(symbol: String) {
        latest.remove(key(symbol))
    }

    fun clearAll() {
        latest.clear()
    }
}

class TradeEconomicsEngine {
    companion object {
        /**
         * Conservative Kraken Spot Crypto Tier-1 fallbacks current at M5 implementation.
         * Live account/pair fee data from Kraken TradeVolume takes precedence.
         */
        val KRAKEN_TIER1_MAKER_FALLBACK: BigDecimal = BigDecimal("0.0040")
        val KRAKEN_TIER1_TAKER_FALLBACK: BigDecimal = BigDecimal("0.0080")

        private val PRIOR_WINS = BigDecimal("10")
        private val PRIOR_SAMPLES = BigDecimal("20")
        private const val MIN_EXACT_SAMPLES = 8
        private const val MAX_OUTCOME_SAMPLES = 200
    }

    private data class ProbabilityEstimate(
        val probability: BigDecimal,
        val samples: Int,
        val source: String
    )

    fun evaluate(input: TradeEconomicsInput): TradeEconomicsAssessment {
        val notional = input.notionalQuote.max(BigDecimal.ZERO)
        val entry = input.entryPrice
        val target = input.targetPrice
        val stop = input.stopPrice

        if (notional <= BigDecimal.ZERO || entry <= BigDecimal.ZERO) {
            return blocked(input, "Trade economics requires positive notional and entry price.")
        }
        if (target <= entry) {
            return blocked(input, "Trade economics requires target > entry. target=$target entry=$entry")
        }
        if (stop <= BigDecimal.ZERO || stop >= entry) {
            return blocked(input, "Trade economics requires a protective stop below entry. stop=$stop entry=$entry")
        }

        val quantity = notional.divide(entry, 16, RoundingMode.DOWN)
        if (quantity <= BigDecimal.ZERO) {
            return blocked(input, "Trade economics calculated zero quantity.")
        }

        val probability = estimateWinProbability(
            rows = input.recentTrades,
            symbol = input.symbol,
            strategyId = input.strategyId
        )
        val pWin = probability.probability
        val pLoss = BigDecimal.ONE.subtract(pWin)

        val observedFee = observedLiveFeeRate(input.recentTrades)
        val makerEntry = input.postOnly && input.orderType == OrderType.LIMIT
        val entryFeeRate = input.feeSchedule?.let {
            if (makerEntry) it.makerRate else it.takerRate
        } ?: maxBd(
            if (makerEntry) KRAKEN_TIER1_MAKER_FALLBACK else KRAKEN_TIER1_TAKER_FALLBACK,
            observedFee ?: BigDecimal.ZERO
        )
        // Unknown/protective exits are conservatively taker.
        val exitFeeRate = input.feeSchedule?.takerRate
            ?: maxBd(KRAKEN_TIER1_TAKER_FALLBACK, observedFee ?: BigDecimal.ZERO)
        val feeSource = input.feeSchedule?.source ?: if (observedFee != null) {
            "KRAKEN_TIER1_FALLBACK+OBSERVED"
        } else {
            "KRAKEN_TIER1_FALLBACK"
        }

        val winNotional = quantity.multiply(target)
        val lossNotional = quantity.multiply(stop)
        val expectedExitNotional = weighted(pWin, winNotional, pLoss, lossNotional)

        val expectedWin = quantity.multiply(target.subtract(entry))
        val expectedLoss = quantity.multiply(entry.subtract(stop))
        val grossEv = pWin.multiply(expectedWin).subtract(pLoss.multiply(expectedLoss))

        val entryFee = notional.multiply(entryFeeRate)
        val expectedExitFee = expectedExitNotional.multiply(exitFeeRate)

        // One current-spread reserve for the round trip. Slippage is modeled separately
        // from top-of-book to depth-weighted execution, so it does not include spread.
        val spreadRate = spreadRate(input.ticker)
        val spreadCost = notional.multiply(spreadRate)

        val entrySlipRate = if (makerEntry) BigDecimal.ZERO else depthSlippageRate(
            input.orderBook, OrderSide.BUY, quantity, input.ticker.ask
        )
        val entrySlippage = notional.multiply(entrySlipRate)

        // We do not pretend to know the future exit book. Use current SELL depth impact
        // as the best immediately observable stress proxy and apply it to expected exit notional.
        val exitSlipRate = depthSlippageRate(
            input.orderBook, OrderSide.SELL, quantity, input.ticker.bid
        )
        val expectedExitSlippage = expectedExitNotional.multiply(exitSlipRate)

        val externalCost = input.externalDecisionCostQuote.max(BigDecimal.ZERO)
        val safetyRate = input.safetyMarginRate.coerceIn(BigDecimal.ZERO, BigDecimal("0.05"))
        val safety = notional.multiply(safetyRate)

        val totalCosts = entryFee
            .add(expectedExitFee)
            .add(spreadCost)
            .add(entrySlippage)
            .add(expectedExitSlippage)
            .add(externalCost)
            .add(safety)

        val netEv = grossEv.subtract(totalCosts)
        val netEvRate = if (notional > BigDecimal.ZERO) {
            netEv.divide(notional, 12, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        val grossRange = expectedWin.add(expectedLoss)
        val breakEvenP = if (grossRange > BigDecimal.ZERO) {
            expectedLoss.add(totalCosts)
                .divide(grossRange, 12, RoundingMode.HALF_UP)
                .coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
        } else BigDecimal.ONE

        val riskReward = if (expectedLoss > BigDecimal.ZERO) {
            expectedWin.divide(expectedLoss, 8, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        val allowed = netEv > BigDecimal.ZERO && pWin > breakEvenP

        val assessment = TradeEconomicsAssessment(
            allowed = allowed,
            symbol = normalize(input.symbol),
            strategyId = input.strategyId.ifBlank { "UNKNOWN" },
            notionalQuote = notional,
            quantity = quantity,
            probabilityWin = pWin,
            probabilitySource = probability.source,
            outcomeSamples = probability.samples,
            makerEntry = makerEntry,
            feeSource = feeSource,
            entryFeeRate = entryFeeRate,
            exitFeeRate = exitFeeRate,
            spreadRate = spreadRate,
            expectedWinQuote = expectedWin,
            expectedLossQuote = expectedLoss,
            grossExpectedValueQuote = grossEv,
            entryFeeQuote = entryFee,
            expectedExitFeeQuote = expectedExitFee,
            spreadCostQuote = spreadCost,
            entrySlippageQuote = entrySlippage,
            expectedExitSlippageQuote = expectedExitSlippage,
            externalDecisionCostQuote = externalCost,
            safetyReserveQuote = safety,
            totalExpectedCostQuote = totalCosts,
            netExpectedValueQuote = netEv,
            netExpectedValueRate = netEvRate,
            breakEvenWinProbability = breakEvenP,
            riskRewardRatio = riskReward,
            reason = buildReason(
                allowed = allowed,
                pWin = pWin,
                probabilitySource = probability.source,
                samples = probability.samples,
                expectedWin = expectedWin,
                expectedLoss = expectedLoss,
                grossEv = grossEv,
                costs = totalCosts,
                netEv = netEv,
                netEvRate = netEvRate,
                breakEven = breakEvenP,
                riskReward = riskReward,
                entryFeeRate = entryFeeRate,
                exitFeeRate = exitFeeRate,
                spreadRate = spreadRate,
                entrySlippage = entrySlippage,
                exitSlippage = expectedExitSlippage,
                safety = safety,
                externalCost = externalCost,
                feeSource = feeSource,
                makerEntry = makerEntry
            )
        )
        if (input.publishRuntime) TradeEconomicsRuntime.publish(assessment)
        return assessment
    }

    private fun estimateWinProbability(
        rows: List<TradeEntity>,
        symbol: String,
        strategyId: String
    ): ProbabilityEstimate {
        val realized = rows.asSequence()
            .filter { (it.realizedPnlEur.toBigDecimalOrNull() ?: BigDecimal.ZERO) != BigDecimal.ZERO }
            .sortedByDescending { it.timestampEpochMs }
            .take(MAX_OUTCOME_SAMPLES)
            .toList()

        val normalizedSymbol = normalize(symbol)
        val exact = if (strategyId.isBlank() || strategyId == "UNKNOWN" || strategyId == "HANDOFF_FILTERS") {
            emptyList()
        } else {
            realized.filter {
                normalize(it.symbol) == normalizedSymbol &&
                    it.aiReason.contains(strategyId, ignoreCase = true)
            }
        }
        val symbolRows = realized.filter { normalize(it.symbol) == normalizedSymbol }

        val selected: List<TradeEntity>
        val source: String
        when {
            exact.size >= MIN_EXACT_SAMPLES -> {
                selected = exact
                source = "STRATEGY_SYMBOL_REALIZED"
            }
            symbolRows.size >= MIN_EXACT_SAMPLES -> {
                selected = symbolRows
                source = "SYMBOL_REALIZED"
            }
            realized.isNotEmpty() -> {
                selected = realized
                source = "GLOBAL_REALIZED"
            }
            else -> {
                selected = emptyList()
                source = "NEUTRAL_PRIOR"
            }
        }

        val wins = selected.count {
            (it.realizedPnlEur.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO
        }
        val numerator = PRIOR_WINS.add(BigDecimal(wins))
        val denominator = PRIOR_SAMPLES.add(BigDecimal(selected.size))
        val p = numerator.divide(denominator, 12, RoundingMode.HALF_UP)
            .coerceIn(BigDecimal("0.10"), BigDecimal("0.90"))

        return ProbabilityEstimate(p, selected.size, source)
    }

    private fun observedLiveFeeRate(rows: List<TradeEntity>): BigDecimal? {
        val rates = rows.asSequence()
            .filter { !it.paper }
            .mapNotNull { row ->
                val fee = row.feeEur.toBigDecimalOrNull()?.abs() ?: return@mapNotNull null
                val qty = row.quantity.toBigDecimalOrNull()?.abs() ?: return@mapNotNull null
                val price = row.priceEur.toBigDecimalOrNull()?.abs() ?: return@mapNotNull null
                val n = qty.multiply(price)
                if (n <= BigDecimal.ZERO || fee <= BigDecimal.ZERO) null
                else fee.divide(n, 12, RoundingMode.HALF_UP)
            }
            .filter { it > BigDecimal.ZERO && it < BigDecimal("0.05") }
            .take(100)
            .sorted()
            .toList()
        return if (rates.isEmpty()) null else rates[rates.size / 2]
    }

    private fun depthSlippageRate(
        book: OrderBookSnapshot?,
        side: OrderSide,
        quantity: BigDecimal,
        fallbackTop: BigDecimal
    ): BigDecimal {
        if (quantity <= BigDecimal.ZERO || fallbackTop <= BigDecimal.ZERO) return BigDecimal("0.0025")
        if (book == null) return BigDecimal("0.0010")

        val levels = if (side == OrderSide.BUY) book.asks else book.bids
        var remaining = quantity
        var totalQuote = BigDecimal.ZERO
        var filled = BigDecimal.ZERO
        for (level in levels) {
            if (remaining <= BigDecimal.ZERO) break
            val take = level.quantity.min(remaining)
            if (take > BigDecimal.ZERO && level.price > BigDecimal.ZERO) {
                totalQuote = totalQuote.add(take.multiply(level.price))
                filled = filled.add(take)
                remaining = remaining.subtract(take)
            }
        }
        if (filled <= BigDecimal.ZERO || remaining > BigDecimal.ZERO) return BigDecimal("0.0025")
        val average = totalQuote.divide(filled, 16, RoundingMode.HALF_UP)
        val perUnit = if (side == OrderSide.BUY) {
            average.subtract(fallbackTop).max(BigDecimal.ZERO)
        } else {
            fallbackTop.subtract(average).max(BigDecimal.ZERO)
        }
        return perUnit.divide(fallbackTop, 12, RoundingMode.HALF_UP)
            .coerceIn(BigDecimal.ZERO, BigDecimal("0.05"))
    }

    private fun spreadRate(ticker: MarketTicker): BigDecimal {
        val reference = ticker.lastPrice.takeIf { it > BigDecimal.ZERO }
            ?: ticker.ask.add(ticker.bid).divide(BigDecimal("2"), 12, RoundingMode.HALF_UP)
        if (reference <= BigDecimal.ZERO) return BigDecimal("0.05")
        return ticker.ask.subtract(ticker.bid).abs()
            .divide(reference, 12, RoundingMode.HALF_UP)
            .coerceIn(BigDecimal.ZERO, BigDecimal("0.05"))
    }

    private fun blocked(input: TradeEconomicsInput, why: String): TradeEconomicsAssessment {
        val assessment = TradeEconomicsAssessment(
            allowed = false,
            symbol = normalize(input.symbol),
            strategyId = input.strategyId.ifBlank { "UNKNOWN" },
            notionalQuote = input.notionalQuote.max(BigDecimal.ZERO),
            quantity = BigDecimal.ZERO,
            probabilityWin = BigDecimal("0.50"),
            probabilitySource = "INVALID_INPUT",
            outcomeSamples = 0,
            makerEntry = input.postOnly && input.orderType == OrderType.LIMIT,
            feeSource = input.feeSchedule?.source ?: "KRAKEN_TIER1_FALLBACK",
            entryFeeRate = BigDecimal.ZERO,
            exitFeeRate = BigDecimal.ZERO,
            spreadRate = BigDecimal.ZERO,
            expectedWinQuote = BigDecimal.ZERO,
            expectedLossQuote = BigDecimal.ZERO,
            grossExpectedValueQuote = BigDecimal.ZERO,
            entryFeeQuote = BigDecimal.ZERO,
            expectedExitFeeQuote = BigDecimal.ZERO,
            spreadCostQuote = BigDecimal.ZERO,
            entrySlippageQuote = BigDecimal.ZERO,
            expectedExitSlippageQuote = BigDecimal.ZERO,
            externalDecisionCostQuote = input.externalDecisionCostQuote.max(BigDecimal.ZERO),
            safetyReserveQuote = BigDecimal.ZERO,
            totalExpectedCostQuote = BigDecimal.ZERO,
            netExpectedValueQuote = BigDecimal.ZERO,
            netExpectedValueRate = BigDecimal.ZERO,
            breakEvenWinProbability = BigDecimal.ONE,
            riskRewardRatio = BigDecimal.ZERO,
            reason = "BLOCK_INVALID_ECONOMICS | $why"
        )
        if (input.publishRuntime) TradeEconomicsRuntime.publish(assessment)
        return assessment
    }

    private fun buildReason(
        allowed: Boolean,
        pWin: BigDecimal,
        probabilitySource: String,
        samples: Int,
        expectedWin: BigDecimal,
        expectedLoss: BigDecimal,
        grossEv: BigDecimal,
        costs: BigDecimal,
        netEv: BigDecimal,
        netEvRate: BigDecimal,
        breakEven: BigDecimal,
        riskReward: BigDecimal,
        entryFeeRate: BigDecimal,
        exitFeeRate: BigDecimal,
        spreadRate: BigDecimal,
        entrySlippage: BigDecimal,
        exitSlippage: BigDecimal,
        safety: BigDecimal,
        externalCost: BigDecimal,
        feeSource: String,
        makerEntry: Boolean
    ): String =
        "${if (allowed) "PASS_POSITIVE_NET_EV" else "BLOCK_NON_POSITIVE_NET_EV"} | " +
            "pWin=${pct(pWin)} source=$probabilitySource samples=$samples breakEven=${pct(breakEven)} " +
            "win=${money(expectedWin)} loss=${money(expectedLoss)} RR=${riskReward.setScale(3, RoundingMode.HALF_UP)} " +
            "grossEV=${money(grossEv)} expectedCosts=${money(costs)} netEV=${money(netEv)} (${pct(netEvRate)}) | " +
            "entryFee=${pct(entryFeeRate)} exitFee=${pct(exitFeeRate)} spread=${pct(spreadRate)} " +
            "entrySlip=${money(entrySlippage)} exitSlip=${money(exitSlippage)} safety=${money(safety)} " +
            "decisionCost=${money(externalCost)} feeSource=$feeSource makerEntry=$makerEntry"

    private fun weighted(pA: BigDecimal, a: BigDecimal, pB: BigDecimal, b: BigDecimal): BigDecimal =
        pA.multiply(a).add(pB.multiply(b))

    private fun maxBd(a: BigDecimal, b: BigDecimal): BigDecimal = if (a >= b) a else b
    private fun normalize(symbol: String): String =
        symbol.uppercase().replace("/", "").replace("-", "").replace("_", "")

    private fun pct(value: BigDecimal): String =
        value.multiply(BigDecimal("100")).setScale(3, RoundingMode.HALF_UP).toPlainString() + "%"

    private fun money(value: BigDecimal): String =
        value.setScale(4, RoundingMode.HALF_UP).toPlainString()

    private fun BigDecimal.coerceIn(lo: BigDecimal, hi: BigDecimal): BigDecimal = when {
        this < lo -> lo
        this > hi -> hi
        else -> this
    }
}
