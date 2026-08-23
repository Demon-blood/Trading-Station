package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.AiDecision
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.SignalAction
import com.ksp.cryptobot.data.TradeEntity
import kotlin.math.abs
import kotlin.random.Random

/**
 * Direct behavioral ports of desktop v1.0.50 governance research helpers that were
 * previously replaced by different Android research math.  They remain advisory and
 * cannot place orders or bypass M3/M4 safety.
 */
class DesktopParityResearchEngine {
    fun walkForward(symbol: String, trades: List<TradeEntity>): Pair<Int, String> {
        val outcomes = trades
            .filter { it.symbol.equals(symbol, true) && (it.realizedPnlEur.toDoubleOrNull() ?: 0.0) != 0.0 }
            .sortedBy { it.timestampEpochMs }
            .takeLast(200)
        if (outcomes.size < 18) return 0 to "Desktop walk-forward warm-up for $symbol: ${outcomes.size}/18."
        val tail = outcomes.takeLast(18)
        val windows = listOf(tail.subList(0,6), tail.subList(6,12), tail.subList(12,18))
        val scores = windows.map { w ->
            val pnl = w.sumOf { it.realizedPnlEur.toDoubleOrNull() ?: 0.0 }
            val wins = w.count { (it.realizedPnlEur.toDoubleOrNull() ?: 0.0) > 0.0 }
            pnl + (wins.toDouble() / w.size - 0.5) * 10.0
        }
        val stable = scores.all { it > 0.0 }
        val deteriorating = scores[2] < scores[0] - abs(scores[0]) * 0.5 - 1.0
        return when {
            stable -> 3 to "Desktop walk-forward stable: windows=${scores.map { "%.2f".format(it) }}."
            deteriorating -> -5 to "Desktop walk-forward deterioration: windows=${scores.map { "%.2f".format(it) }}."
            else -> 0 to "Desktop walk-forward neutral: windows=${scores.map { "%.2f".format(it) }}."
        }
    }

    fun monteCarlo(settings: BotSettings, trades: List<TradeEntity>): Pair<Int, String> {
        val pnls = trades.mapNotNull { it.realizedPnlEur.toDoubleOrNull() }
            .filter { abs(it) > 1e-9 }
            .takeLast(250)
        if (pnls.size < 20) return 0 to "Desktop Monte Carlo warm-up: ${pnls.size}/20 realized outcomes."
        val rng = Random(1337)
        val maxDrawdowns = DoubleArray(250)
        var ruinHits = 0
        val dailyLimit = maxOf(1.0, settings.maxDailyLossEur.toDouble())
        repeat(250) { sim ->
            var equity = 0.0; var peak = 0.0; var maxDd = 0.0; var worst = 0.0
            repeat(60) {
                equity += pnls[rng.nextInt(pnls.size)]
                peak = maxOf(peak, equity)
                maxDd = minOf(maxDd, equity - peak)
                worst = minOf(worst, equity)
            }
            maxDrawdowns[sim] = abs(maxDd)
            if (abs(worst) > dailyLimit) ruinHits++
        }
        maxDrawdowns.sort()
        val p95 = maxDrawdowns[(maxDrawdowns.size * .95).toInt().coerceIn(0,maxDrawdowns.lastIndex)]
        val ruin = ruinHits / 250.0
        return when {
            ruin > .25 -> -8 to "Desktop Monte Carlo risk high: daily-limit hit probability ${"%.1f".format(ruin*100)}%, p95DD=€${"%.2f".format(p95)}."
            p95 < dailyLimit * .45 -> 2 to "Desktop Monte Carlo risk acceptable: p95DD=€${"%.2f".format(p95)}, daily-limit probability=${"%.1f".format(ruin*100)}%."
            else -> -2 to "Desktop Monte Carlo risk moderate: p95DD=€${"%.2f".format(p95)}, daily-limit probability=${"%.1f".format(ruin*100)}%."
        }
    }

    fun multiHorizonFusion(candles: List<Candle>, decision: AiDecision): Pair<Int, String> {
        val closes = StrategyMath.closes(candles)
        if (closes.size < 40) return 0 to "Desktop multi-horizon fusion warm-up."
        val windows = listOf("short" to 8, "mid" to 24, "long" to 60)
        val slopes = linkedMapOf<String,Double>()
        for ((name,w) in windows) if (closes.size >= w) {
            val start = closes[closes.size-w]
            slopes[name] = if (start != 0.0) (closes.last()-start)/start*100.0 else 0.0
        }
        val bullish = slopes.values.count { it > .15 }; val bearish = slopes.values.count { it < -.15 }
        val isBuy = decision.finalAction in setOf(SignalAction.BUY, SignalAction.SMALL_BUY)
        return when {
            isBuy && bullish >= 2 -> 4 to "Desktop multi-horizon agreement supports long: $slopes."
            isBuy && bearish >= 2 -> -6 to "Desktop multi-horizon disagreement warns against long: $slopes."
            else -> 0 to "Desktop multi-horizon mixed/neutral: $slopes."
        }
    }
}
