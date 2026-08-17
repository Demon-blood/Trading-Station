package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.OrderBookSnapshot
import java.math.BigDecimal
import java.math.RoundingMode

class LiquidityAwareSizer {
    fun size(settings: BotSettings, requestedQuote: BigDecimal, ticker: MarketTicker, orderBook: OrderBookSnapshot?): LiquiditySizingDecision {
        val band = when {
            requestedQuote < BigDecimal("10") -> "micro"
            requestedQuote < BigDecimal("25") -> "small"
            requestedQuote < BigDecimal("100") -> "medium"
            else -> "large"
        }
        if (requestedQuote <= BigDecimal.ZERO) return LiquiditySizingDecision(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "zero_request", band, "liquidity sizing: requested amount is zero")
        val depth = orderBook?.asks?.take(10)?.fold(BigDecimal.ZERO) { acc, l -> acc.add(l.price.multiply(l.quantity)) } ?: BigDecimal.ZERO
        // Existing order-book guard requires depth >= target * minOrderBookDepthMultiple.
        // Reuse the same configured safety relation as the sizing cap.
        val usageDivisor = settings.minOrderBookDepthMultiple.max(BigDecimal.ONE)
        val liquidityCap = if (depth > BigDecimal.ZERO) depth.divide(usageDivisor, 8, RoundingMode.DOWN) else requestedQuote
        val spread = if (ticker.lastPrice > BigDecimal.ZERO) ticker.ask.subtract(ticker.bid).abs().divide(ticker.lastPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal("999")
        val elevated = spread > settings.maxSpreadPercent.multiply(BigDecimal("0.80"))
        val spreadFactor = if (elevated) BigDecimal("0.50") else BigDecimal.ONE
        val finalQuote = requestedQuote.min(liquidityCap).multiply(spreadFactor).setScale(2, RoundingMode.DOWN).max(BigDecimal.ZERO)
        val multiplier = if (requestedQuote > BigDecimal.ZERO) finalQuote.divide(requestedQuote, 6, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val category = when {
            depth <= BigDecimal.ZERO -> "no_depth"
            elevated -> "elevated_spread"
            finalQuote < requestedQuote -> "depth_capped"
            else -> "liquidity_ok"
        }
        return LiquiditySizingDecision(finalQuote, multiplier, depth, category, band,
            "liquidity sizing: requested=${requestedQuote.s2()}, depth=${depth.s2()}, spread=${spread.setScale(3, RoundingMode.HALF_UP)}%, final=${finalQuote.s2()}")
    }
    private fun BigDecimal.s2(): String = setScale(2, RoundingMode.DOWN).toPlainString()
}
