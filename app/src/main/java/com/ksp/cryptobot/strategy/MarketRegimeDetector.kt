package com.ksp.cryptobot.strategy

import com.ksp.cryptobot.core.*
import java.math.BigDecimal
import java.math.RoundingMode

class MarketRegimeDetector {
    fun detect(symbol: String, candlesByTimeframe: Map<Timeframe, List<Candle>>): RegimeAnalysis {
        val h1 = candlesByTimeframe[Timeframe.H1].orEmpty()
        val m15 = candlesByTimeframe[Timeframe.M15].orEmpty()
        val base = if (h1.size >= 60) h1 else m15
        if (base.size < 30) {
            return RegimeAnalysis(symbol, MarketRegime.SIDEWAYS, 30, BigDecimal.ZERO, BigDecimal.ZERO, "Not enough candles; defaulting to sideways/safe mode.")
        }

        val closes = base.map { it.close }
        val latest = closes.last()
        val emaFast = TechnicalIndicators.ema(closes, 21)
        val emaSlow = TechnicalIndicators.ema(closes, 55.coerceAtMost(closes.size - 1))
        val atr = TechnicalIndicators.atr(base, 14)
        val atrPct = percent(atr, latest)
        val trendPct = if (emaSlow > BigDecimal.ZERO) {
            emaFast.subtract(emaSlow).abs().divide(emaSlow, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
        } else BigDecimal.ZERO

        val lastChange = TechnicalIndicators.percentChange(closes.takeLast(12).first(), latest)
        val regime = when {
            atrPct > BigDecimal("3.8") -> MarketRegime.HIGH_VOLATILITY
            atrPct < BigDecimal("0.8") && trendPct < BigDecimal("0.7") -> MarketRegime.LOW_VOLATILITY
            emaFast > emaSlow && lastChange > BigDecimal("0.2") -> MarketRegime.TRENDING_UP
            emaFast < emaSlow && lastChange < BigDecimal("-0.2") -> MarketRegime.TRENDING_DOWN
            else -> MarketRegime.SIDEWAYS
        }
        val confidence = when (regime) {
            MarketRegime.HIGH_VOLATILITY -> (55 + atrPct.toInt() * 8).coerceIn(55, 95)
            MarketRegime.LOW_VOLATILITY -> 62
            MarketRegime.TRENDING_UP, MarketRegime.TRENDING_DOWN -> (58 + trendPct.multiply(BigDecimal("8")).toInt()).coerceIn(58, 94)
            else -> 55
        }
        return RegimeAnalysis(
            symbol = symbol,
            regime = regime,
            confidencePercent = confidence,
            volatilityPercent = atrPct.setScale(2, RoundingMode.HALF_UP),
            trendStrengthPercent = trendPct.setScale(2, RoundingMode.HALF_UP),
            explanation = "Regime=$regime, ATR≈${atrPct.setScale(2, RoundingMode.HALF_UP)}%, trend≈${trendPct.setScale(2, RoundingMode.HALF_UP)}%."
        )
    }

    private fun percent(value: BigDecimal, price: BigDecimal): BigDecimal {
        return if (price <= BigDecimal.ZERO) BigDecimal.ZERO else value.divide(price, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
    }
}
