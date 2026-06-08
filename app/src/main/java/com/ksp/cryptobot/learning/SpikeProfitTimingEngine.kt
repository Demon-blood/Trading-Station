package com.ksp.cryptobot.learning

import com.ksp.cryptobot.core.AiDecision
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.PositionInfo
import com.ksp.cryptobot.core.SignalAction
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max

/**
 * v1.7.9 spike/profit-cycle timing layer.
 *
 * This does not try to predict a perfect market top. Instead it compares the current move
 * against historical spike/run patterns for the same symbol and decides whether the bot should:
 * - defer a normal take-profit/trailing exit because the run still looks early/healthy, or
 * - sell/lock profit because the run looks exhausted compared with historical spike behavior.
 */
class SpikeProfitTimingEngine {
    data class SpikeTimingDecision(
        val shouldHold: Boolean,
        val shouldSellNow: Boolean,
        val confidencePercent: Int,
        val progressPercentOfTypicalSpike: BigDecimal,
        val dynamicTrailingDistancePercent: BigDecimal,
        val explanation: String
    )

    fun evaluate(
        settings: BotSettings,
        position: PositionInfo,
        h1Candles: List<Candle>,
        h4Candles: List<Candle>,
        decision: AiDecision?
    ): SpikeTimingDecision {
        if (!settings.spikeProfitTimingEnabled) {
            return neutral("Spike profit timing disabled.")
        }
        if (position.unrealizedPnlPercent < settings.spikeTimingMinProfitPercent) {
            return neutral("Spike timing neutral: profit ${position.unrealizedPnlPercent.scale2()}% below minimum ${settings.spikeTimingMinProfitPercent.scale2()}%.")
        }

        val candles = normalizeCandles(h1Candles, h4Candles)
        if (candles.size < 60) {
            return neutral("Spike timing neutral: only ${candles.size} candles available; need at least 60.")
        }

        val history = candles.takeLast(settings.spikeTimingLookbackCandles.coerceIn(80, 720))
        val typical = typicalSpike(history, settings)
        if (typical.sampleSize < settings.spikeTimingMinPatternSamples) {
            return neutral("Spike timing warm-up: only ${typical.sampleSize}/${settings.spikeTimingMinPatternSamples} historical spike samples for ${position.symbol}.")
        }

        val currentRun = currentRunPercent(history, position.currentPrice)
        val progress = if (typical.averageSpikePercent > BigDecimal.ZERO) {
            currentRun.multiply(BigDecimal("100")).divide(typical.averageSpikePercent, 2, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO
        val momentum = momentumState(history)
        val pullbackFromHigh = if (position.highestPrice > BigDecimal.ZERO) {
            position.highestPrice.subtract(position.currentPrice).divide(position.highestPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
        } else BigDecimal.ZERO

        val dynamicTrail = typical.averagePullbackPercent
            .multiply(settings.spikeTimingTrailingFlexMultiplier)
            .coerceIn(settings.spikeTimingMinDynamicTrailPercent, settings.spikeTimingMaxDynamicTrailPercent)

        val currentScore = decision?.finalScore ?: 0
        val technicalStillStrong = currentScore >= settings.minStrategyScoreToBuy || decision?.finalAction == SignalAction.BUY || decision?.finalAction == SignalAction.SMALL_BUY
        val earlyEnough = progress < settings.spikeTimingHoldUntilProgressPercent
        val exhausted = progress >= settings.spikeTimingExhaustionProgressPercent
        val momentumWeak = momentum < -1
        val hardPullback = pullbackFromHigh >= dynamicTrail

        val holdConfidence = listOf(
            if (earlyEnough) 25 else 0,
            if (momentum > 0) 20 else 0,
            if (technicalStillStrong) 20 else 0,
            if (position.unrealizedPnlPercent > BigDecimal.ZERO) 15 else 0,
            if (typical.averageSpikePercent >= BigDecimal("3.0")) 10 else 0,
            if (pullbackFromHigh < dynamicTrail.divide(BigDecimal("2"), 2, RoundingMode.HALF_UP)) 10 else 0
        ).sum().coerceIn(0, 100)

        val sellConfidence = listOf(
            if (exhausted) 35 else 0,
            if (momentumWeak) 25 else 0,
            if (hardPullback) 25 else 0,
            if ((decision?.finalAction == SignalAction.SELL || decision?.finalAction == SignalAction.AVOID || decision?.finalAction == SignalAction.STRONG_AVOID)) 15 else 0
        ).sum().coerceIn(0, 100)

        val shouldHold = holdConfidence >= settings.spikeTimingHoldConfidenceThresholdPercent && !hardPullback && !momentumWeak && technicalStillStrong
        val shouldSell = sellConfidence >= settings.spikeTimingSellConfidenceThresholdPercent && position.unrealizedPnlPercent > BigDecimal.ZERO

        val explanation = buildString {
            append("Spike timing ${position.symbol}: currentRun=${currentRun.scale2()}%, typicalSpike=${typical.averageSpikePercent.scale2()}%, progress=${progress.scale2()}%, ")
            append("avgDuration=${typical.averageDurationCandles} candles, pullbackFromHigh=${pullbackFromHigh.scale2()}%, dynTrail=${dynamicTrail.scale2()}%, ")
            append("momentum=$momentum, aiScore=$currentScore, holdConf=$holdConfidence, sellConf=$sellConfidence. ")
            append(when {
                shouldSell -> "Action: SELL/lock profit because the run looks exhausted or weakening compared with prior spikes."
                shouldHold -> "Action: HOLD because historical spike behavior suggests this move may still have continuation potential."
                else -> "Action: no spike override; use normal lifecycle/learned-hold logic."
            })
        }

        return SpikeTimingDecision(
            shouldHold = shouldHold,
            shouldSellNow = shouldSell,
            confidencePercent = max(holdConfidence, sellConfidence),
            progressPercentOfTypicalSpike = progress,
            dynamicTrailingDistancePercent = dynamicTrail,
            explanation = explanation
        )
    }

    private data class HistoricalSpikeStats(
        val sampleSize: Int,
        val averageSpikePercent: BigDecimal,
        val averagePullbackPercent: BigDecimal,
        val averageDurationCandles: Int
    )

    private fun normalizeCandles(h1: List<Candle>, h4: List<Candle>): List<Candle> {
        // Prefer H1 for timing precision. Include H4 only when H1 history is too short.
        val chosen = if (h1.size >= 80) h1 else (h1 + h4).distinctBy { it.openTimeEpochMs }
        return chosen.sortedBy { it.openTimeEpochMs }.filter { it.close > BigDecimal.ZERO && it.high > BigDecimal.ZERO && it.low > BigDecimal.ZERO }
    }

    private fun typicalSpike(candles: List<Candle>, settings: BotSettings): HistoricalSpikeStats {
        val horizon = settings.spikeTimingPatternHorizonCandles.coerceIn(6, 72)
        val minSpike = settings.spikeTimingHistoricalSpikeThresholdPercent
        val samples = mutableListOf<Pair<BigDecimal, Int>>()
        val pullbacks = mutableListOf<BigDecimal>()

        for (i in 0 until candles.size - horizon) {
            val start = candles[i].low.takeIf { it > BigDecimal.ZERO } ?: continue
            val window = candles.subList(i + 1, i + horizon + 1)
            val maxHigh = window.maxOfOrNull { it.high } ?: continue
            val maxIndex = window.indexOfFirst { it.high == maxHigh }.takeIf { it >= 0 } ?: continue
            val spike = maxHigh.subtract(start).divide(start, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
            if (spike >= minSpike) {
                samples += spike to (maxIndex + 1)
                val afterHigh = window.drop(maxIndex).take(settings.spikeTimingPullbackWindowCandles.coerceIn(3, 24))
                val lowestAfter = afterHigh.minOfOrNull { it.low } ?: maxHigh
                val pullback = maxHigh.subtract(lowestAfter).divide(maxHigh, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
                pullbacks += pullback.max(BigDecimal.ZERO)
            }
        }

        if (samples.isEmpty()) return HistoricalSpikeStats(0, BigDecimal.ZERO, BigDecimal.ZERO, 0)
        val avgSpike = samples.map { it.first }.averageBigDecimal()
        val avgDuration = samples.map { BigDecimal(it.second) }.averageBigDecimal().setScale(0, RoundingMode.HALF_UP).toInt()
        val avgPullback = if (pullbacks.isEmpty()) BigDecimal("1.00") else pullbacks.averageBigDecimal().max(BigDecimal("0.25"))
        return HistoricalSpikeStats(samples.size, avgSpike, avgPullback, avgDuration)
    }

    private fun currentRunPercent(candles: List<Candle>, currentPrice: BigDecimal): BigDecimal {
        val lookback = candles.takeLast(48)
        val recentLow = lookback.minOfOrNull { it.low } ?: currentPrice
        return if (recentLow > BigDecimal.ZERO) {
            currentPrice.subtract(recentLow).divide(recentLow, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
        } else BigDecimal.ZERO
    }

    private fun momentumState(candles: List<Candle>): Int {
        val recent = candles.takeLast(8)
        if (recent.size < 8) return 0
        val first = recent.take(4).map { it.close }.averageBigDecimal()
        val second = recent.takeLast(4).map { it.close }.averageBigDecimal()
        val risingCloses = recent.zipWithNext().count { it.second.close > it.first.close }
        return when {
            second > first && risingCloses >= 4 -> 2
            second > first -> 1
            second < first && risingCloses <= 2 -> -2
            second < first -> -1
            else -> 0
        }
    }

    private fun List<BigDecimal>.averageBigDecimal(): BigDecimal {
        if (isEmpty()) return BigDecimal.ZERO
        return fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(size), 6, RoundingMode.HALF_UP)
    }

    private fun neutral(reason: String) = SpikeTimingDecision(
        shouldHold = false,
        shouldSellNow = false,
        confidencePercent = 0,
        progressPercentOfTypicalSpike = BigDecimal.ZERO,
        dynamicTrailingDistancePercent = BigDecimal.ZERO,
        explanation = reason
    )

    private fun BigDecimal.scale2(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()
    private fun BigDecimal.coerceIn(minimum: BigDecimal, maximum: BigDecimal): BigDecimal = max(minimum).min(maximum)
}
