package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.*
import java.math.BigDecimal
import java.math.RoundingMode

/** Order-type optimizer that remains subordinate to enableMarketOrders and existing market-order guards. */
class OrderTypeOptimizer {
    fun suggest(settings: BotSettings, ticker: MarketTicker, orderBook: OrderBookSnapshot?, requestedQuote: BigDecimal, currentUseMarket: Boolean): OrderTypeDecision {
        val spread = if (ticker.lastPrice > BigDecimal.ZERO) ticker.ask.subtract(ticker.bid).abs().divide(ticker.lastPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal("999")
        val depth5 = orderBook?.asks?.take(5)?.fold(BigDecimal.ZERO) { a, l -> a.add(l.price.multiply(l.quantity)) } ?: BigDecimal.ZERO
        val elevated = spread > settings.maxSpreadPercent.multiply(BigDecimal("0.75"))
        if (elevated) {
            val passive = midpoint(ticker.bid, ticker.ask).takeIf { it > BigDecimal.ZERO } ?: ticker.ask
            return OrderTypeDecision(OrderType.LIMIT, passive, "spread_elevated", "spread ${spread.setScale(3, RoundingMode.HALF_UP)}% elevated; passive limit preferred")
        }
        if (currentUseMarket && settings.enableMarketOrders) {
            val deepEnough = depth5 >= requestedQuote.multiply(settings.minOrderBookDepthMultiple.max(BigDecimal.ONE)).multiply(BigDecimal("2"))
            val veryTight = spread <= settings.marketOrderSlippageWarningPercent.multiply(BigDecimal("0.40"))
            if (deepEnough && veryTight) {
                return OrderTypeDecision(OrderType.MARKET, null, "deep_liquidity", "market kept: very tight spread and deep top-5 liquidity")
            }
            return OrderTypeDecision(OrderType.LIMIT, ticker.ask, if (!deepEnough) "depth_insufficient_for_market" else "spread_not_tight_enough", "market downgraded to limit by execution optimizer")
        }
        return OrderTypeDecision(OrderType.LIMIT, ticker.ask, "configured_limit", "configured/safe limit order kept")
    }
    private fun midpoint(bid: BigDecimal, ask: BigDecimal): BigDecimal = if (bid > BigDecimal.ZERO && ask > BigDecimal.ZERO) bid.add(ask).divide(BigDecimal("2"), 8, RoundingMode.HALF_UP) else BigDecimal.ZERO
}
