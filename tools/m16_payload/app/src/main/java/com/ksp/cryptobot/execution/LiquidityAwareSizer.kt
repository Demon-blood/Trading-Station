package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.OrderBookSnapshot
import com.ksp.cryptobot.core.OrderSide
import java.math.BigDecimal
import java.math.RoundingMode

class LiquidityAwareSizer {
    private val microstructure = MarketMicrostructureEngine()

    fun size(
        settings: BotSettings,
        requestedQuote: BigDecimal,
        ticker: MarketTicker,
        orderBook: OrderBookSnapshot?
    ): LiquiditySizingDecision {
        val band = when {
            requestedQuote < BigDecimal("10") -> "micro"
            requestedQuote < BigDecimal("25") -> "small"
            requestedQuote < BigDecimal("100") -> "medium"
            else -> "large"
        }
        if (requestedQuote <= BigDecimal.ZERO) {
            return LiquiditySizingDecision(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "zero_request", band, "liquidity sizing: requested amount is zero"
            )
        }

        if (orderBook == null) {
            val failClosed = settings.orderBookDepthGuardEnabled && settings.mode != BotMode.PAPER
            val final = if (failClosed) BigDecimal.ZERO else requestedQuote
            return LiquiditySizingDecision(
                final,
                if (final > BigDecimal.ZERO) BigDecimal.ONE else BigDecimal.ZERO,
                BigDecimal.ZERO,
                if (failClosed) "microstructure_unavailable" else "no_depth",
                band,
                if (failClosed) {
                    "M16 microstructure unavailable in LIVE while order-book guard is enabled; entry sizing fails closed."
                } else {
                    "M16 microstructure unavailable; PAPER/non-guard mode keeps requested size."
                }
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
            val failClosed = settings.orderBookDepthGuardEnabled && settings.mode != BotMode.PAPER
            val final = if (failClosed) BigDecimal.ZERO else requestedQuote.multiply(BigDecimal("0.50"))
            return LiquiditySizingDecision(
                final.setScale(2, RoundingMode.DOWN),
                if (requestedQuote > BigDecimal.ZERO) final.divide(requestedQuote, 6, RoundingMode.HALF_UP) else BigDecimal.ZERO,
                BigDecimal.ZERO,
                "microstructure_invalid",
                band,
                "M16 invalid L2 book. ${micro.reason}"
            )
        }

        val depth = micro.top10AskDepthQuote
        val usageDivisor = settings.minOrderBookDepthMultiple.max(BigDecimal.ONE)
        val liquidityCap = if (depth > BigDecimal.ZERO) {
            depth.divide(usageDivisor, 8, RoundingMode.DOWN)
        } else BigDecimal.ZERO

        val maxImpactBps = settings.maxOrderBookSlippagePercent.multiply(BigDecimal("100")).toDouble()
        if (
            settings.orderBookDepthGuardEnabled &&
            (!micro.marketImpactComplete || micro.marketImpactBps > maxImpactBps)
        ) {
            return LiquiditySizingDecision(
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                depth,
                if (!micro.marketImpactComplete) "depth_incomplete" else "impact_too_high",
                band,
                "M16 market-impact gate blocked entry: complete=${micro.marketImpactComplete}, impact=${"%.2f".format(micro.marketImpactBps)}bps, max=${"%.2f".format(maxImpactBps)}bps. ${micro.reason}"
            )
        }

        val spreadPercent = BigDecimal.valueOf(micro.spreadBps)
            .divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)
        val elevated = spreadPercent > settings.maxSpreadPercent.multiply(BigDecimal("0.80"))
        val spreadFactor = if (elevated) BigDecimal("0.50") else BigDecimal.ONE
        val adverseFactor = when {
            micro.adverseSelectionRisk >= 0.80 -> BigDecimal("0.50")
            micro.adverseSelectionRisk >= 0.60 -> BigDecimal("0.75")
            else -> BigDecimal.ONE
        }

        val finalQuote = requestedQuote
            .min(liquidityCap)
            .multiply(spreadFactor)
            .multiply(adverseFactor)
            .setScale(2, RoundingMode.DOWN)
            .max(BigDecimal.ZERO)

        val multiplier = if (requestedQuote > BigDecimal.ZERO) {
            finalQuote.divide(requestedQuote, 6, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        val category = when {
            micro.adverseSelectionRisk >= 0.80 -> "adverse_selection_high"
            elevated -> "elevated_spread"
            finalQuote < requestedQuote -> "microstructure_capped"
            else -> "microstructure_ok"
        }

        return LiquiditySizingDecision(
            finalQuote,
            multiplier,
            depth,
            category,
            band,
            "M16 liquidity: requested=${requestedQuote.s2()}, askDepth10=${depth.s2()}, spread=${"%.2f".format(micro.spreadBps)}bps, imbalance=${"%.3f".format(micro.bookImbalance)}, microPressure=${"%.2f".format(micro.microPricePressureBps)}bps, impact=${"%.2f".format(micro.marketImpactBps)}bps, makerFill=${"%.3f".format(micro.makerFillProbability)}, adverse=${"%.3f".format(micro.adverseSelectionRisk)}, final=${finalQuote.s2()}. ${micro.reason}"
        )
    }

    private fun BigDecimal.s2(): String =
        setScale(2, RoundingMode.DOWN).toPlainString()
}
