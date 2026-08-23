package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.ExchangeSymbolInfo
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.OrderBookSnapshot
import com.ksp.cryptobot.core.OrderSide
import com.ksp.cryptobot.data.TradeEntity
import com.ksp.cryptobot.exchange.TradingFeeSchedule
import com.ksp.cryptobot.execution.TradeEconomicsEngine
import com.ksp.cryptobot.execution.TradeEconomicsInput
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

class ResearchHandoffCostRiskEngine {
    private val tradeEconomics = TradeEconomicsEngine()

    companion object {
        private val MAKER_FALLBACK = BigDecimal("0.0040")
        private val TAKER_FALLBACK = BigDecimal("0.0080")
        private val PRACTICAL_EUR_COST_MIN = BigDecimal("5.00")
    }

    fun costGate(
        candidate: HandoffTradeCandidate,
        ticker: MarketTicker,
        orderBook: OrderBookSnapshot?,
        recentTrades: List<TradeEntity>,
        feeSchedule: TradingFeeSchedule?,
        symbolInfo: ExchangeSymbolInfo?,
        probeNotionalQuote: BigDecimal,
        safetyMarginPct: BigDecimal
    ): HandoffCostAssessment {
        val rawEntry = candidate.entryPlan.intendedPrice ?: candidate.entryPlan.triggerPrice ?: ticker.ask
        val rawTarget = candidate.targets.firstOrNull()?.price
        val rawStop = candidate.invalidation.stopPrice

        val entry = normalizeEntryPrice(candidate, rawEntry, symbolInfo)
        val target = rawTarget?.let { normalizeTargetPrice(it, symbolInfo) }
        val stop = rawStop?.let { normalizeStopPrice(it, symbolInfo) }

        if (candidate.sideIntent != HandoffSideIntent.LONG_ENTRY ||
            entry <= BigDecimal.ZERO ||
            target == null || target <= entry ||
            stop == null || stop <= BigDecimal.ZERO || stop >= entry
        ) {
            return HandoffCostAssessment(
                false,
                TAKER_FALLBACK,
                TAKER_FALLBACK,
                spreadPct(ticker),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "M5 economics requires LONG_ENTRY with entry, target above entry, and protective stop below entry."
            )
        }

        val orderType = candidate.entryPlan.preferredOrderType
            ?: com.ksp.cryptobot.core.OrderType.LIMIT
        val postOnly = candidate.entryPlan.postOnlyPreferred &&
            orderType == com.ksp.cryptobot.core.OrderType.LIMIT
        val probe = probeNotionalQuote.coerceAtLeast(PRACTICAL_EUR_COST_MIN)
        val safetyRate = safetyMarginPct
            .divide(BigDecimal("100"), 12, RoundingMode.HALF_UP)
            .coerceIn(BigDecimal.ZERO, BigDecimal("0.05"))

        val economics = tradeEconomics.evaluate(
            TradeEconomicsInput(
                symbol = candidate.symbol,
                strategyId = candidate.strategyId,
                notionalQuote = probe,
                entryPrice = entry,
                targetPrice = target,
                stopPrice = stop,
                orderType = orderType,
                postOnly = postOnly,
                ticker = ticker,
                orderBook = orderBook,
                recentTrades = recentTrades,
                feeSchedule = feeSchedule,
                publishRuntime = false,
                externalDecisionCostQuote = BigDecimal.ZERO,
                safetyMarginRate = safetyRate
            )
        )

        return HandoffCostAssessment(
            allowed = economics.allowed,
            entryFeeRate = economics.entryFeeRate,
            exitFeeRate = economics.exitFeeRate,
            spreadPct = economics.spreadRate.multiply(BigDecimal("100")),
            entrySlippageQuote = economics.entrySlippageQuote,
            exitSlippageQuote = economics.expectedExitSlippageQuote,
            expectedGrossProfitQuote = economics.expectedWinQuote,
            expectedTotalCostQuote = economics.totalExpectedCostQuote,
            safetyMarginQuote = economics.safetyReserveQuote,
            expectedNetProfitQuote = economics.netExpectedValueQuote,
            reason = economics.reason
        )
    }

    fun riskGate(
        candidate: HandoffTradeCandidate,
        ticker: MarketTicker,
        symbolInfo: ExchangeSymbolInfo?,
        recentTrades: List<TradeEntity>,
        availableCashQuote: BigDecimal,
        equityEstimateQuote: BigDecimal,
        riskPercent: BigDecimal,
        orderBook: OrderBookSnapshot?,
        feeSchedule: TradingFeeSchedule?
    ): HandoffRiskAssessment {
        val rawEntry = candidate.entryPlan.intendedPrice ?: candidate.entryPlan.triggerPrice ?: ticker.ask
        val entry = normalizeEntryPrice(candidate, rawEntry, symbolInfo)
        val stop = candidate.invalidation.stopPrice?.let { normalizeStopPrice(it, symbolInfo) }
        if (candidate.sideIntent != HandoffSideIntent.LONG_ENTRY || entry <= BigDecimal.ZERO || stop == null || stop <= BigDecimal.ZERO || stop >= entry) {
            return blockedRisk(equityEstimateQuote, riskPercent, "Risk gate requires a long entry with a technical stop below entry.")
        }
        val observed = observedFeeRate(recentTrades)
        val makerLike = candidate.entryPlan.postOnlyPreferred && candidate.entryPlan.preferredOrderType == com.ksp.cryptobot.core.OrderType.LIMIT
        val fe = feeSchedule?.let { if (makerLike) it.makerRate else it.takerRate }
            ?: maxBd(if (makerLike) MAKER_FALLBACK else TAKER_FALLBACK, observed ?: BigDecimal.ZERO)
        val fx = feeSchedule?.takerRate ?: maxBd(TAKER_FALLBACK, observed ?: BigDecimal.ZERO) // protective stop must survive taker economics
        val unitProbe = BigDecimal.ONE
        val se = bookSlippagePerUnit(orderBook, OrderSide.BUY, unitProbe, ticker.ask).abs()
        val sx = bookSlippagePerUnit(orderBook, OrderSide.SELL, unitProbe, ticker.bid).abs()
        val lossPerUnit = entry.subtract(stop).abs() + entry.multiply(fe) + stop.multiply(fx) + se + sx
        val equity = equityEstimateQuote.max(BigDecimal.ZERO)
        val rp = riskPercent.coerceIn(BigDecimal("0.0005"), BigDecimal("0.01"))
        val budget = equity.multiply(rp)
        if (budget <= BigDecimal.ZERO || availableCashQuote <= BigDecimal.ZERO || lossPerUnit <= BigDecimal.ZERO) {
            return blockedRisk(equity, rp, "Risk gate has no usable risk budget/cash or loss-per-unit.")
        }
        val qtyRisk = budget.divide(lossPerUnit, 16, RoundingMode.DOWN)
        val cashCostPerUnit = entry.multiply(BigDecimal.ONE + fe) + se
        val qtyCash = availableCashQuote.divide(cashCostPerUnit, 16, RoundingMode.DOWN)
        val decimals = symbolInfo?.quantityDecimals?.coerceIn(0, 12) ?: 8
        var qty = qtyRisk.min(qtyCash).setScale(decimals, RoundingMode.DOWN)
        val minQty = symbolInfo?.minOrderSize ?: BigDecimal.ZERO
        val exchangeMinCost = symbolInfo?.minOrderCost ?: BigDecimal.ZERO
        val practicalMinCost = PRACTICAL_EUR_COST_MIN.max(exchangeMinCost)
        var requiredQty = minQty.max(
            if (entry > BigDecimal.ZERO && practicalMinCost > BigDecimal.ZERO)
                practicalMinCost.divide(entry, decimals.coerceAtLeast(8), RoundingMode.UP).setScale(decimals, RoundingMode.UP)
            else BigDecimal.ZERO
        )
        if (qty < requiredQty && requiredQty > BigDecimal.ZERO) {
            val minLoss = requiredQty.multiply(lossPerUnit)
            val minCash = requiredQty.multiply(cashCostPerUnit)
            if (minLoss > budget || minCash > availableCashQuote || requiredQty > qtyRisk || requiredQty > qtyCash) {
                return HandoffRiskAssessment(false, equity, rp, budget, lossPerUnit, qtyRisk, qtyCash, qty, qty.multiply(entry), qty.multiply(lossPerUnit),
                    "SKIP_EXCHANGE_MIN_EXCEEDS_RISK: requiredQty=${requiredQty.stripTrailingZeros().toPlainString()} orderMin=${minQty.stripTrailingZeros().toPlainString()} costMin=${exchangeMinCost.s4()} practicalMin=${practicalMinCost.s4()} minLoss=${minLoss.s4()} budget=${budget.s4()} minCash=${minCash.s4()} available=${availableCashQuote.s2()}")
            }
            qty = requiredQty
        }
        val notional = qty.multiply(entry)
        val actualLoss = qty.multiply(lossPerUnit)
        if (notional < practicalMinCost) {
            return HandoffRiskAssessment(false, equity, rp, budget, lossPerUnit, qtyRisk, qtyCash, qty, notional, actualLoss,
                "SKIP_MIN_COST: modeled notional ${notional.s2()} is below required ${practicalMinCost.s2()} (exchangeCostMin=${exchangeMinCost.s2()}, productFloor=${PRACTICAL_EUR_COST_MIN.s2()}).")
        }
        if (actualLoss > budget + BigDecimal("0.0001")) {
            return HandoffRiskAssessment(false, equity, rp, budget, lossPerUnit, qtyRisk, qtyCash, qty, notional, actualLoss,
                "SKIP_RISK_AFTER_ROUNDING: actual modeled loss ${actualLoss.s4()} > budget ${budget.s4()}.")
        }
        return HandoffRiskAssessment(true, equity, rp, budget, lossPerUnit, qtyRisk, qtyCash, qty, notional, actualLoss,
            "PASS risk sizing: equity=${equity.s2()} risk=${rp.multiply(BigDecimal("100")).s3()}% budget=${budget.s4()} loss/unit=${lossPerUnit.s8()} qtyRisk=${qtyRisk.s8()} qtyCash=${qtyCash.s8()} finalQty=${qty.stripTrailingZeros().toPlainString()} notional=${notional.s2()} modeledLoss=${actualLoss.s4()}.")
    }

    private fun normalizeEntryPrice(candidate: HandoffTradeCandidate, price: BigDecimal, info: ExchangeSymbolInfo?): BigDecimal {
        val tick = info?.tickSize?.takeIf { it > BigDecimal.ZERO } ?: return price
        val mode = when (candidate.entryPlan.kind) {
            HandoffEntryKind.RESTING_STOP -> RoundingMode.UP
            HandoffEntryKind.LIMIT, HandoffEntryKind.LIMIT_RETEST -> RoundingMode.DOWN
            else -> RoundingMode.HALF_UP
        }
        return roundToTick(price, tick, mode)
    }

    private fun normalizeStopPrice(price: BigDecimal, info: ExchangeSymbolInfo?): BigDecimal {
        val tick = info?.tickSize?.takeIf { it > BigDecimal.ZERO } ?: return price
        return roundToTick(price, tick, RoundingMode.DOWN)
    }

    private fun normalizeTargetPrice(price: BigDecimal, info: ExchangeSymbolInfo?): BigDecimal {
        val tick = info?.tickSize?.takeIf { it > BigDecimal.ZERO } ?: return price
        return roundToTick(price, tick, RoundingMode.UP)
    }

    private fun roundToTick(value: BigDecimal, tick: BigDecimal, mode: RoundingMode): BigDecimal {
        if (tick <= BigDecimal.ZERO) return value
        val units = value.divide(tick, 0, mode)
        return units.multiply(tick)
    }

    private fun observedFeeRate(rows: List<TradeEntity>): BigDecimal? {
        val rates = rows.asSequence()
            .filter { !it.paper }
            .mapNotNull { row ->
                val fee = row.feeEur.toBigDecimalOrNull()?.abs() ?: return@mapNotNull null
                val qty = row.quantity.toBigDecimalOrNull()?.abs() ?: return@mapNotNull null
                val price = row.priceEur.toBigDecimalOrNull()?.abs() ?: return@mapNotNull null
                val notional = qty.multiply(price)
                if (notional <= BigDecimal.ZERO || fee <= BigDecimal.ZERO) null else fee.divide(notional, 10, RoundingMode.HALF_UP)
            }.filter { it > BigDecimal.ZERO && it < BigDecimal("0.05") }.take(50).sorted().toList()
        if (rates.isEmpty()) return null
        return rates[rates.size / 2]
    }

    private fun bookSlippagePerUnit(book: OrderBookSnapshot?, side: OrderSide, quantity: BigDecimal, fallbackTop: BigDecimal): BigDecimal {
        if (book == null || quantity <= BigDecimal.ZERO || fallbackTop <= BigDecimal.ZERO) return fallbackTop.multiply(BigDecimal("0.0010"))
        val levels = if (side == OrderSide.BUY) book.asks else book.bids
        var remaining = quantity
        var totalQuote = BigDecimal.ZERO
        var filled = BigDecimal.ZERO
        for (level in levels) {
            if (remaining <= BigDecimal.ZERO) break
            val take = level.quantity.min(remaining)
            totalQuote += take.multiply(level.price)
            filled += take
            remaining -= take
        }
        if (filled <= BigDecimal.ZERO || remaining > BigDecimal.ZERO) return fallbackTop.multiply(BigDecimal("0.0025"))
        val avg = totalQuote.divide(filled, 16, RoundingMode.HALF_UP)
        return if (side == OrderSide.BUY) (avg - fallbackTop).max(BigDecimal.ZERO) else (fallbackTop - avg).max(BigDecimal.ZERO)
    }

    private fun spreadPct(ticker: MarketTicker): BigDecimal {
        val last = ticker.lastPrice
        if (last <= BigDecimal.ZERO) return BigDecimal("100")
        return ticker.ask.subtract(ticker.bid).abs().divide(last, 12, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
    }

    private fun blockedRisk(equity: BigDecimal, rp: BigDecimal, reason: String) = HandoffRiskAssessment(false, equity, rp, equity.multiply(rp), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, reason)
    private fun maxBd(a: BigDecimal, b: BigDecimal) = if (a >= b) a else b
    private fun pct(v: BigDecimal) = v.multiply(BigDecimal("100")).setScale(3, RoundingMode.HALF_UP).toPlainString() + "%"
    private fun BigDecimal.s2() = setScale(2, RoundingMode.HALF_UP).toPlainString()
    private fun BigDecimal.s3() = setScale(3, RoundingMode.HALF_UP).toPlainString()
    private fun BigDecimal.s4() = setScale(4, RoundingMode.HALF_UP).toPlainString()
    private fun BigDecimal.s8() = setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    private fun BigDecimal.coerceIn(lo: BigDecimal, hi: BigDecimal) = when { this < lo -> lo; this > hi -> hi; else -> this }
}
