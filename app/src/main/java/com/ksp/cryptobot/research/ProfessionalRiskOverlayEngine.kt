package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.data.TradeEntity
import kotlin.math.abs

/**
 * Practitioner-style risk overlay. It is deliberately one-way: multiplier <= 1.0.
 * It can reduce an M4-approved order, never increase it.
 */
data class ProfessionalRiskOverlay(
    val sizeMultiplier: Double,
    val atrPercent: Double,
    val suggestedInitialStopAtr: Double,
    val suggestedTrailingStopAtr: Double,
    val reason: String
)

class ProfessionalRiskOverlayEngine {
    fun evaluate(
        strategy: String,
        ticker: MarketTicker,
        candles: List<Candle>,
        recentTrades: List<TradeEntity>,
        externalAdjustment: Int
    ): ProfessionalRiskOverlay {
        if (candles.size < 35) return ProfessionalRiskOverlay(.75, 0.0, 1.5, 2.0, "Risk overlay warm-up: insufficient candles; defensive 0.75x.")
        val atr = StrategyMath.atrPct(candles, 14)
        val historicalAtr = buildList {
            val start = (candles.size - 100).coerceAtLeast(20)
            for (end in start..candles.size step 5) {
                val slice = candles.take(end)
                val v = StrategyMath.atrPct(slice, 14)
                if (v > 0.0) add(v)
            }
        }.sorted()
        val medianAtr = historicalAtr.takeIf { it.isNotEmpty() }?.let { xs -> if (xs.size % 2 == 1) xs[xs.size/2] else (xs[xs.size/2-1]+xs[xs.size/2])/2.0 } ?: atr
        val spread = StrategyMath.spreadPct(ticker.lastPrice.toDouble(), ticker.bid.toDouble(), ticker.ask.toDouble())
        val dmi = StrategyMath.wilderDmi(candles, 14)
        var mult = 1.0; val reasons = mutableListOf<String>()

        if (medianAtr > 0.0) {
            val ratio = atr / medianAtr
            when {
                ratio >= 2.0 -> { mult *= .50; reasons += "ATRP ${"%.2f".format(ratio)}x median" }
                ratio >= 1.50 -> { mult *= .67; reasons += "ATRP ${"%.2f".format(ratio)}x median" }
                ratio >= 1.25 -> { mult *= .85; reasons += "ATRP moderately elevated" }
            }
        }
        if (atr > 0.0) {
            val spreadToAtr = spread / atr
            when {
                spreadToAtr >= .40 -> { mult *= .50; reasons += "spread consumes ${"%.0f".format(spreadToAtr*100)}% of ATR" }
                spreadToAtr >= .25 -> { mult *= .75; reasons += "spread consumes ${"%.0f".format(spreadToAtr*100)}% of ATR" }
            }
        }
        val trendFamily = strategy.contains("TREND") || strategy.contains("BREAKOUT") || strategy.contains("SUPERTREND") || strategy.contains("DONCHIAN") || strategy.contains("ICHIMOKU")
        if (trendFamily && dmi.adx < 20.0) { mult *= .70; reasons += "trend strategy with ADX<20" }
        else if (trendFamily && dmi.adx < 25.0) { mult *= .90; reasons += "trend strength below ADX 25" }

        if (externalAdjustment <= -8) { mult *= .70; reasons += "strong adverse external context" }
        else if (externalAdjustment <= -4) { mult *= .85; reasons += "adverse external context" }

        val outcomes = recentTrades.filter { abs(it.realizedPnlEur.toDoubleOrNull() ?: 0.0) > 1e-9 }.takeLast(30)
        if (outcomes.size >= 10) {
            val wins = outcomes.count { (it.realizedPnlEur.toDoubleOrNull() ?: 0.0) > 0.0 }
            val wr = wins.toDouble() / outcomes.size
            if (wr < .40) { mult *= .75; reasons += "recent realized win rate ${"%.0f".format(wr*100)}%" }
        }
        mult = mult.coerceIn(.15, 1.0)
        val stop = when { atr >= 2.0 -> 2.0; atr >= 1.0 -> 1.75; else -> 1.5 }
        val trail = when { dmi.adx >= 30 -> 2.5; dmi.adx >= 25 -> 2.0; else -> 1.5 }
        return ProfessionalRiskOverlay(mult, atr, stop, trail, if (reasons.isEmpty()) "Professional risk overlay neutral; 1.00x." else "Professional risk shrink ${"%.2f".format(mult)}x: ${reasons.joinToString("; ")}.")
    }
}
