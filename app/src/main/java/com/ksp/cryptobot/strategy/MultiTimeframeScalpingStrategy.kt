package com.ksp.cryptobot.strategy

import com.ksp.cryptobot.core.*
import java.math.BigDecimal
import java.math.RoundingMode

class MultiTimeframeScalpingStrategy {
    fun evaluate(
        symbol: String,
        candlesByTimeframe: Map<Timeframe, List<Candle>>,
        settings: BotSettings
    ): StrategySignal {
        val frames = listOf(Timeframe.M5, Timeframe.M15, Timeframe.H1)
        var trendAgreement = 0
        var emaScore = 0
        var obvScore = 0
        var momentumScore = 0
        var atrRiskScore = 20
        var stopLossPercent = BigDecimal("0.75")
        var takeProfitPercent = BigDecimal("1.05")
        val notes = mutableListOf<String>()

        frames.forEach { tf ->
            val candles = candlesByTimeframe[tf].orEmpty()
            if (candles.size < settings.emaSlowPeriod + 5) {
                notes += "${tf.name}: not enough candles"
                return@forEach
            }
            val closes = candles.map { it.close }
            val emaFast = TechnicalIndicators.ema(closes, settings.emaFastPeriod)
            val emaSlow = TechnicalIndicators.ema(closes, settings.emaSlowPeriod)
            val recentMomentum = TechnicalIndicators.percentChange(closes.takeLast(8).first(), closes.last())
            val obvRecent = TechnicalIndicators.obv(candles.takeLast(settings.obvLookback))
            val obvPrevious = TechnicalIndicators.obv(candles.takeLast(settings.obvLookback * 2).dropLast(settings.obvLookback))
            val atr = TechnicalIndicators.atr(candles, settings.atrPeriod)
            val atrPercent = if (closes.last() > BigDecimal.ZERO) {
                atr.divide(closes.last(), 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
            } else BigDecimal.ZERO

            if (emaFast > emaSlow) {
                trendAgreement += 1
                emaScore += 14
                notes += "${tf.name}: EMA${settings.emaFastPeriod} above EMA${settings.emaSlowPeriod}"
            } else {
                emaScore -= 10
                notes += "${tf.name}: EMA trend not confirmed"
            }

            if (obvRecent > obvPrevious) obvScore += 8 else obvScore -= 6

            when {
                recentMomentum > BigDecimal("0.45") -> momentumScore += 10
                recentMomentum > BigDecimal("0.10") -> momentumScore += 5
                recentMomentum < BigDecimal("-0.45") -> momentumScore -= 12
                recentMomentum < BigDecimal("-0.10") -> momentumScore -= 6
            }

            when {
                atrPercent > BigDecimal("4.0") -> atrRiskScore -= 16
                atrPercent > BigDecimal("2.5") -> atrRiskScore -= 8
                atrPercent < BigDecimal("1.5") -> atrRiskScore += 4
            }

            if (tf == Timeframe.M15 && atrPercent > BigDecimal.ZERO) {
                stopLossPercent = atrPercent.multiply(settings.stopLossAtrMultiplier).setScale(2, RoundingMode.HALF_UP)
                takeProfitPercent = atrPercent.multiply(settings.takeProfitAtrMultiplier).setScale(2, RoundingMode.HALF_UP)
            }
        }

        val score = (35 + emaScore + obvScore + momentumScore + atrRiskScore).coerceIn(0, 100)
        val action = when {
            trendAgreement < settings.minTrendAgreement -> SignalAction.WAIT
            score >= settings.minStrategyScoreToBuy + 10 -> SignalAction.BUY
            score >= settings.minStrategyScoreToBuy -> SignalAction.SMALL_BUY
            score >= 58 -> SignalAction.WATCH
            score >= 45 -> SignalAction.WAIT
            else -> SignalAction.AVOID
        }

        return StrategySignal(
            symbol = symbol,
            strategyScore = score,
            trendAgreement = trendAgreement,
            emaScore = emaScore.coerceIn(-40, 50),
            obvScore = obvScore.coerceIn(-25, 25),
            atrRiskScore = atrRiskScore.coerceIn(0, 30),
            momentumScore = momentumScore.coerceIn(-35, 35),
            suggestedTakeProfitPercent = takeProfitPercent,
            suggestedStopLossPercent = stopLossPercent,
            action = action,
            explanation = "Defined MTF EMA/OBV scalper: ${trendAgreement}/3 timeframe trend agreement, score=$score, TP≈$takeProfitPercent%, SL≈$stopLossPercent%. ${notes.joinToString("; ")}"
        )
    }
}
