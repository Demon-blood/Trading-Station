package com.ksp.cryptobot.strategy

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.tax.BelgiumTaxEngine
import java.math.BigDecimal

class RecommendationEngine(
    private val riskEngine: RiskEngine = RiskEngine(),
    private val taxEngine: BelgiumTaxEngine = BelgiumTaxEngine(),
    private val scalpingStrategy: MultiTimeframeScalpingStrategy = MultiTimeframeScalpingStrategy(),
    private val regimeDetector: MarketRegimeDetector = MarketRegimeDetector(),
    private val multiStrategyEngine: MultiStrategyEngine = MultiStrategyEngine()
) {
    fun recommend(
        ticker: MarketTicker,
        settings: BotSettings,
        realizedGainsThisYearEur: BigDecimal = BigDecimal.ZERO,
        candlesByTimeframe: Map<Timeframe, List<Candle>> = emptyMap()
    ): Recommendation {
        val tradable = riskEngine.isTradable(ticker, settings)
        val risk = riskEngine.riskPercent(ticker)
        val taxEstimate = taxEngine.estimateTaxAfterSale(realizedGainsThisYearEur, BigDecimal.ZERO)

        if (!tradable.first) {
            return Recommendation(
                symbol = ticker.symbol,
                action = SignalAction.AVOID,
                score = 0,
                riskPercent = risk,
                taxWarning = taxEstimate.warning,
                reason = tradable.second
            )
        }

        if (settings.recoveredScalpingStrategyEnabled && candlesByTimeframe.isNotEmpty()) {
            val regime = regimeDetector.detect(ticker.symbol, candlesByTimeframe)
            val selected = multiStrategyEngine.chooseBest(ticker, candlesByTimeframe, settings, regime)
            val liquidityBoost = if (ticker.volume24h > BigDecimal("10000000")) 5 else 0
            val spreadPenalty = riskEngine.spreadPercent(ticker).toDouble().let { if (it > 0.20) 5 else 0 }
            val finalScore = (selected.score + liquidityBoost - spreadPenalty).coerceIn(0, 100)
            val selectedEntryAllowed =
                selected.action == SignalAction.BUY || selected.action == SignalAction.SMALL_BUY
            val action = when {
                risk > BigDecimal("15") -> SignalAction.WAIT
                selected.action == SignalAction.SELL -> SignalAction.SELL
                !selectedEntryAllowed -> selected.action
                selected.action == SignalAction.SMALL_BUY &&
                    finalScore >= settings.minStrategyScoreToBuy -> SignalAction.SMALL_BUY
                selected.action == SignalAction.BUY &&
                    finalScore >= settings.minStrategyScoreToBuy + 10 -> SignalAction.BUY
                selected.action == SignalAction.BUY &&
                    finalScore >= settings.minStrategyScoreToBuy -> SignalAction.SMALL_BUY
                finalScore >= 58 -> SignalAction.WATCH
                finalScore >= 45 -> SignalAction.WAIT
                else -> SignalAction.AVOID
            }
            return Recommendation(
                symbol = ticker.symbol,
                action = action,
                score = finalScore,
                riskPercent = risk,
                taxWarning = taxEstimate.warning,
                reason = "Selected ${selected.mode.name}: ${selected.reason} Liquidity boost=$liquidityBoost, spread penalty=$spreadPenalty, 24h risk=$risk%."
            )
        }

        val fallbackScore = computeFallbackScore(ticker, risk)
        val fallbackAction = when {
            risk > BigDecimal("12") -> SignalAction.WAIT
            fallbackScore >= 60 -> SignalAction.WATCH
            fallbackScore >= 45 -> SignalAction.WAIT
            else -> SignalAction.AVOID
        }

        return Recommendation(
            symbol = ticker.symbol,
            action = fallbackAction,
            score = fallbackScore,
            riskPercent = risk,
            taxWarning = taxEstimate.warning,
            reason = "M18 non-trading fallback: no truth-validated strategy candidate/candle set is available. 24h momentum=${ticker.priceChangePercent24h}%, estimated risk=$risk%, spread=${riskEngine.spreadPercent(ticker)}%. Fallback may observe but cannot authorize BUY."
        )
    }

    private fun computeFallbackScore(ticker: MarketTicker, risk: BigDecimal): Int {
        var score = 50
        val change = ticker.priceChangePercent24h
        if (change > BigDecimal("0.5")) score += 10
        if (change > BigDecimal("2.0")) score += 10
        if (change < BigDecimal("-2.0")) score -= 15
        if (risk < BigDecimal("5")) score += 10
        if (risk > BigDecimal("10")) score -= 20
        if (ticker.volume24h > BigDecimal("10000000")) score += 10
        return score.coerceIn(0, 100)
    }
}
