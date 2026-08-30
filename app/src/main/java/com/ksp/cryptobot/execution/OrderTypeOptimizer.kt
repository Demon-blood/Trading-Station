package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.*
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * M16 execution-mode optimizer.
 *
 * Ordinary safe LIMIT entries are maker-oriented by default: passive L2 target +
 * post-only. Market execution is retained only when the user enabled it and L2
 * evidence says spread, depth, impact and adverse-selection risk are all acceptable.
 */
class OrderTypeOptimizer {
    private val microstructure = MarketMicrostructureEngine()

    fun suggest(
        settings: BotSettings,
        ticker: MarketTicker,
        orderBook: OrderBookSnapshot?,
        requestedQuote: BigDecimal,
        currentUseMarket: Boolean
    ): OrderTypeDecision {
        if (orderBook == null) {
            return OrderTypeDecision(
                orderType = OrderType.LIMIT,
                limitPrice = ticker.bid.takeIf { it > BigDecimal.ZERO } ?: ticker.ask,
                reasonCategory = "microstructure_unavailable",
                reason = "M16 L2 unavailable; market execution is not allowed without book evidence. Passive LIMIT fallback.",
                postOnly = true
            )
        }

        val micro = microstructure.evaluate(
            orderBook = orderBook,
            side = OrderSide.BUY,
            requestedQuote = requestedQuote,
            workingPrice = ticker.bid,
            fillHorizonSeconds = settings.staleOrderTimeoutSeconds
        )
        if (!micro.valid) {
            return OrderTypeDecision(
                OrderType.LIMIT,
                ticker.bid.takeIf { it > BigDecimal.ZERO } ?: ticker.ask,
                "microstructure_invalid",
                "M16 invalid L2 book; market execution blocked. ${micro.reason}",
                postOnly = true
            )
        }

        val depthRequirement = requestedQuote
            .multiply(settings.minOrderBookDepthMultiple.max(BigDecimal.ONE))
            .multiply(BigDecimal("2"))
        val deepEnough = micro.top5AskDepthQuote >= depthRequirement
        val maxImpactPercent = settings.marketOrderSlippageWarningPercent
            .min(settings.maxOrderBookSlippagePercent)
        val maxImpactBps = maxImpactPercent.multiply(BigDecimal("100")).toDouble()
        val maxSpreadBps = settings.maxSpreadPercent.multiply(BigDecimal("100")).toDouble()
        val veryTight = micro.spreadBps <= maxSpreadBps * 0.40
        val lowImpact = micro.marketImpactComplete && micro.marketImpactBps <= maxImpactBps
        val acceptableAdverseSelection = micro.adverseSelectionRisk < 0.55

        if (
            currentUseMarket &&
            settings.enableMarketOrders &&
            deepEnough &&
            veryTight &&
            lowImpact &&
            acceptableAdverseSelection
        ) {
            return OrderTypeDecision(
                OrderType.MARKET,
                null,
                "microstructure_market_ok",
                "M16 market retained: spread=${"%.2f".format(micro.spreadBps)}bps, impact=${"%.2f".format(micro.marketImpactBps)}bps, adverse=${"%.3f".format(micro.adverseSelectionRisk)}, askDepth5=${micro.top5AskDepthQuote.setScale(2, RoundingMode.DOWN)}.",
                postOnly = false
            )
        }

        val passive = micro.makerTargetPrice
            .takeIf { it > BigDecimal.ZERO && it < micro.bestAsk }
            ?: micro.bestBid

        val category = when {
            currentUseMarket && settings.enableMarketOrders && !deepEnough -> "market_depth_insufficient"
            currentUseMarket && settings.enableMarketOrders && !veryTight -> "market_spread_too_wide"
            currentUseMarket && settings.enableMarketOrders && !lowImpact -> "market_impact_too_high"
            currentUseMarket && settings.enableMarketOrders && !acceptableAdverseSelection -> "market_adverse_selection"
            micro.adverseSelectionRisk >= 0.70 -> "passive_adverse_selection"
            micro.makerFillProbability < 0.45 -> "passive_low_fill_probability"
            else -> "passive_maker"
        }

        return OrderTypeDecision(
            OrderType.LIMIT,
            passive,
            category,
            "M16 passive maker LIMIT: price=${passive.stripTrailingZeros().toPlainString()}, fillHeuristic=${"%.3f".format(micro.makerFillProbability)}, spread=${"%.2f".format(micro.spreadBps)}bps, imbalance=${"%.3f".format(micro.bookImbalance)}, pressure=${"%.2f".format(micro.microPricePressureBps)}bps, impact=${if (micro.marketImpactComplete) "%.2f".format(micro.marketImpactBps) else "INCOMPLETE"}bps, adverse=${"%.3f".format(micro.adverseSelectionRisk)}. L2 estimate is not exact queue position.",
            postOnly = true
        )
    }
}
