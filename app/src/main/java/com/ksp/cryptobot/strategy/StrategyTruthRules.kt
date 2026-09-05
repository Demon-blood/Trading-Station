package com.ksp.cryptobot.strategy

import com.ksp.cryptobot.core.*
import java.math.BigDecimal
import java.math.RoundingMode

data class StrategyRuleSnapshot(
    val enoughData: Boolean,
    val entry: Boolean,
    val score: Int,
    val takeProfitPercent: BigDecimal,
    val stopLossPercent: BigDecimal,
    val reason: String
)

object StrategyTruthRules {
    fun primaryCandles(
        mode: StrategyMode,
        candlesByTimeframe: Map<Timeframe, List<Candle>>
    ): List<Candle> {
        val preferred = StrategyTruthRegistry.spec(mode)?.primaryTimeframe
        return when {
            preferred != null && candlesByTimeframe[preferred].orEmpty().isNotEmpty() ->
                candlesByTimeframe[preferred].orEmpty()
            candlesByTimeframe[Timeframe.M15].orEmpty().isNotEmpty() ->
                candlesByTimeframe[Timeframe.M15].orEmpty()
            else -> candlesByTimeframe[Timeframe.H1].orEmpty()
        }
    }

    fun evaluate(
        mode: StrategyMode,
        candles: List<Candle>,
        settings: BotSettings
    ): StrategyRuleSnapshot {
        return when (mode) {
            StrategyMode.TREND -> trend(candles, settings)
            StrategyMode.BREAKOUT -> breakout(candles, settings)
            StrategyMode.REVERSAL -> reversal(candles, settings)
            StrategyMode.MEAN_REVERSION_RSI_BOLLINGER -> meanReversion(candles, settings)
            StrategyMode.VWAP_PULLBACK -> vwapPullback(candles, settings)
            StrategyMode.DONCHIAN_BREAKOUT -> donchian(candles, settings)
            StrategyMode.MOMENTUM_SPIKE_CONTINUATION -> momentumContinuation(candles, settings)
            else -> StrategyRuleSnapshot(
                enoughData = false,
                entry = false,
                score = 0,
                takeProfitPercent = BigDecimal.ZERO,
                stopLossPercent = BigDecimal.ZERO,
                reason = StrategyTruthRegistry.truthBlockedReason(mode)
            )
        }
    }

    fun shouldExit(
        mode: StrategyMode,
        candles: List<Candle>,
        entryPrice: BigDecimal,
        settings: BotSettings
    ): Boolean {
        if (candles.size < 25) return false
        val closes = candles.map { it.close }
        val last = candles.last().close
        return when (mode) {
            StrategyMode.TREND -> {
                val e21 = TechnicalIndicators.ema(closes, 21)
                val e55 = TechnicalIndicators.ema(closes, 55.coerceAtMost(closes.size - 1))
                last < e21 || e21 < e55
            }
            StrategyMode.BREAKOUT -> {
                val priorHigh = TechnicalIndicators.highestHigh(candles.dropLast(1), 20)
                last < priorHigh
            }
            StrategyMode.REVERSAL,
            StrategyMode.MEAN_REVERSION_RSI_BOLLINGER -> {
                val basis = TechnicalIndicators.sma(closes, 20)
                val rsi = TechnicalIndicators.rsi(closes, 14)
                last >= basis || rsi >= BigDecimal("55")
            }
            StrategyMode.VWAP_PULLBACK -> {
                val vwap = TechnicalIndicators.vwap(candles, 30)
                val e21 = TechnicalIndicators.ema(closes, 21)
                last < vwap || last < e21
            }
            StrategyMode.DONCHIAN_BREAKOUT -> {
                val exitLow = TechnicalIndicators.lowestLow(candles.dropLast(1), 10)
                last < exitLow
            }
            StrategyMode.MOMENTUM_SPIKE_CONTINUATION -> {
                val prev = candles[candles.lastIndex - 1]
                val midpoint = prev.low.add(prev.high).divide(BigDecimal("2"), 12, RoundingMode.HALF_UP)
                last < midpoint
            }
            else -> false
        }
    }

    private fun trend(c: List<Candle>, settings: BotSettings): StrategyRuleSnapshot {
        if (c.size < 60) return insufficient("TREND requires at least 60 candles.")
        val closes = c.map { it.close }
        val last = closes.last()
        val e21 = TechnicalIndicators.ema(closes, 21)
        val e55 = TechnicalIndicators.ema(closes, 55)
        val recent = TechnicalIndicators.percentChange(closes.takeLast(6).first(), last)
        val entry = last > e21 && e21 > e55 && recent > BigDecimal("0.10")
        val score = (
            45 +
                (if (last > e21) 12 else -8) +
                (if (e21 > e55) 24 else -18) +
                (if (recent > BigDecimal("0.10")) 14 else -6)
            ).coerceIn(0, 100)
        val atrPct = atrPct(c, settings)
        return snapshot(entry, score, atrPct.multiply(BigDecimal("2.2")), atrPct.multiply(BigDecimal("1.2")),
            "EMA21/55 trend: close=$last, EMA21=$e21, EMA55=$e55, recentMove=${recent.s2()}%, entry=$entry.")
    }

    private fun breakout(c: List<Candle>, settings: BotSettings): StrategyRuleSnapshot {
        if (c.size < 35) return insufficient("BREAKOUT requires at least 35 candles.")
        val last = c.last()
        val prior20 = c.dropLast(1).takeLast(20)
        val resistance = prior20.maxOf { it.high }
        val avgVol = averageVolume(prior20)
        val volumeConfirm = avgVol > BigDecimal.ZERO && last.volume >= avgVol.multiply(BigDecimal("1.25"))
        val range = last.high.subtract(last.low)
        val closeLocation = if (range > BigDecimal.ZERO)
            last.close.subtract(last.low).divide(range, 8, RoundingMode.HALF_UP)
        else BigDecimal.ZERO
        val strongClose = closeLocation >= BigDecimal("0.50")
        val entry = last.close > resistance && volumeConfirm && strongClose
        val score = (
            40 +
                (if (last.close > resistance) 25 else 0) +
                (if (volumeConfirm) 20 else 0) +
                (if (strongClose) 10 else 0)
            ).coerceIn(0, 100)
        val atrPct = atrPct(c, settings)
        return snapshot(entry, score, atrPct.multiply(BigDecimal("1.8")), atrPct.multiply(BigDecimal("1.0")),
            "20-bar breakout: resistance=$resistance, close=${last.close}, volumeConfirm=$volumeConfirm, closeLocation=${closeLocation.s2()}, entry=$entry.")
    }

    private fun reversal(c: List<Candle>, settings: BotSettings): StrategyRuleSnapshot {
        if (c.size < 40) return insufficient("REVERSAL requires at least 40 candles.")
        val prevWindow = c.dropLast(1)
        val prevCloses = prevWindow.map { it.close }
        val currentCloses = c.map { it.close }
        val prev = prevWindow.last()
        val last = c.last()
        val prevBasis = TechnicalIndicators.sma(prevCloses, 20)
        val prevDev = TechnicalIndicators.standardDeviation(prevCloses, 20)
        val prevLower = prevBasis.subtract(prevDev.multiply(BigDecimal("2")))
        val currentBasis = TechnicalIndicators.sma(currentCloses, 20)
        val currentDev = TechnicalIndicators.standardDeviation(currentCloses, 20)
        val currentLower = currentBasis.subtract(currentDev.multiply(BigDecimal("2")))
        val prevRsi = TechnicalIndicators.rsi(prevCloses, 14)
        val oversold = prevRsi <= BigDecimal("30") && prev.close <= prevLower
        val bullishConfirm = last.close > last.open && last.close > prev.close && last.close > currentLower
        val entry = oversold && bullishConfirm
        val score = (
            35 +
                (if (oversold) 30 else 0) +
                (if (bullishConfirm) 30 else 0)
            ).coerceIn(0, 100)
        val targetPct = if (last.close > BigDecimal.ZERO && currentBasis > last.close)
            TechnicalIndicators.percentChange(last.close, currentBasis).max(BigDecimal("0.50")).min(BigDecimal("3.00"))
        else BigDecimal("0.80")
        return snapshot(entry, score, targetPct, atrPct(c, settings).max(BigDecimal("0.60")),
            "Oversold reversal: prevRSI=$prevRsi, prevBelowLower=${prev.close <= prevLower}, bullishReclaim=$bullishConfirm, entry=$entry.")
    }

    private fun meanReversion(c: List<Candle>, settings: BotSettings): StrategyRuleSnapshot {
        if (c.size < 40) return insufficient("MEAN_REVERSION requires at least 40 candles.")
        val prev = c.dropLast(1)
        val prevCloses = prev.map { it.close }
        val closes = c.map { it.close }
        val prevBasis = TechnicalIndicators.sma(prevCloses, 20)
        val prevDev = TechnicalIndicators.standardDeviation(prevCloses, 20)
        val prevLower = prevBasis.subtract(prevDev.multiply(BigDecimal("2")))
        val basis = TechnicalIndicators.sma(closes, 20)
        val dev = TechnicalIndicators.standardDeviation(closes, 20)
        val lower = basis.subtract(dev.multiply(BigDecimal("2")))
        val prevRsi = TechnicalIndicators.rsi(prevCloses, 14)
        val rsi = TechnicalIndicators.rsi(closes, 14)
        val stretched = prev.last().close <= prevLower && prevRsi <= BigDecimal("30")
        val reentered = c.last().close > lower && rsi > prevRsi
        val entry = stretched && reentered
        val score = (
            35 +
                (if (stretched) 30 else 0) +
                (if (reentered) 30 else 0)
            ).coerceIn(0, 100)
        val targetPct = if (c.last().close > BigDecimal.ZERO && basis > c.last().close)
            TechnicalIndicators.percentChange(c.last().close, basis).max(BigDecimal("0.40")).min(BigDecimal("3.00"))
        else BigDecimal("0.75")
        return snapshot(entry, score, targetPct, atrPct(c, settings).max(BigDecimal("0.55")),
            "RSI/Bollinger reversion: stretched=$stretched, prevRSI=$prevRsi, RSI=$rsi, reentered=$reentered, basis=$basis, entry=$entry.")
    }

    private fun vwapPullback(c: List<Candle>, settings: BotSettings): StrategyRuleSnapshot {
        if (c.size < 60) return insufficient("VWAP_PULLBACK requires at least 60 candles.")
        val closes = c.map { it.close }
        val prev = c.dropLast(1)
        val e21 = TechnicalIndicators.ema(closes, 21)
        val e55 = TechnicalIndicators.ema(closes, 55)
        val currentVwap = TechnicalIndicators.vwap(c, 30)
        val prevVwap = TechnicalIndicators.vwap(prev, 30)
        val last = c.last()
        val previous = prev.last()
        val trend = e21 > e55
        val pulledBack = previous.close <= prevVwap || previous.low <= prevVwap
        val reclaim = last.close > currentVwap && last.low <= currentVwap.multiply(BigDecimal("1.003"))
        val entry = trend && pulledBack && reclaim
        val score = (
            35 +
                (if (trend) 25 else -10) +
                (if (pulledBack) 18 else 0) +
                (if (reclaim) 25 else 0)
            ).coerceIn(0, 100)
        return snapshot(entry, score, atrPct(c, settings).multiply(BigDecimal("1.6")), atrPct(c, settings).max(BigDecimal("0.55")),
            "VWAP pullback: EMA21>$e55?=$trend, previousAtVWAP=$pulledBack, reclaim=$reclaim, VWAP=$currentVwap, entry=$entry.")
    }

    private fun donchian(c: List<Candle>, settings: BotSettings): StrategyRuleSnapshot {
        if (c.size < 40) return insufficient("DONCHIAN_BREAKOUT requires at least 40 candles.")
        val last = c.last()
        val priorHigh = TechnicalIndicators.highestHigh(c.dropLast(1), 20)
        val entry = last.close > priorHigh
        val score = (45 + (if (entry) 38 else 0)).coerceIn(0, 100)
        val atrPct = atrPct(c, settings)
        return snapshot(entry, score, atrPct.multiply(BigDecimal("2.0")), atrPct.multiply(BigDecimal("1.0")),
            "Donchian 20-bar entry: priorHigh=$priorHigh, close=${last.close}, entry=$entry; exit uses prior 10-bar low.")
    }

    private fun momentumContinuation(c: List<Candle>, settings: BotSettings): StrategyRuleSnapshot {
        if (c.size < 40) return insufficient("MOMENTUM_SPIKE_CONTINUATION requires at least 40 candles.")
        val baseline = c.dropLast(2).takeLast(30)
        val impulse = c[c.lastIndex - 1]
        val continuation = c.last()
        val avgVol = averageVolume(baseline)
        val atr = TechnicalIndicators.atr(c.dropLast(1), 14)
        val impulseRange = impulse.high.subtract(impulse.low)
        val impulseBody = impulse.close.subtract(impulse.open)
        val genuineImpulse =
            impulseBody > BigDecimal.ZERO &&
            atr > BigDecimal.ZERO &&
            impulseRange >= atr.multiply(BigDecimal("0.80")) &&
            avgVol > BigDecimal.ZERO &&
            impulse.volume >= avgVol.multiply(BigDecimal("1.50"))
        val midpoint = impulse.low.add(impulse.high).divide(BigDecimal("2"), 12, RoundingMode.HALF_UP)
        val followThrough =
            continuation.low >= midpoint &&
            continuation.close > impulse.high &&
            continuation.volume >= avgVol.multiply(BigDecimal("0.90"))
        val entry = genuineImpulse && followThrough
        val score = (
            35 +
                (if (genuineImpulse) 30 else 0) +
                (if (followThrough) 35 else 0)
            ).coerceIn(0, 100)
        val atrPct = atrPct(c, settings)
        return snapshot(entry, score, atrPct.multiply(BigDecimal("1.8")), atrPct.multiply(BigDecimal("1.0")),
            "Impulse continuation: genuineImpulse=$genuineImpulse, followThrough=$followThrough, impulseVol=${impulse.volume}, baselineVol=$avgVol, entry=$entry.")
    }

    private fun atrPct(c: List<Candle>, settings: BotSettings): BigDecimal {
        val last = c.lastOrNull()?.close ?: return BigDecimal("1.0")
        val atr = TechnicalIndicators.atr(c, settings.atrPeriod)
        return if (last > BigDecimal.ZERO && atr > BigDecimal.ZERO)
            atr.divide(last, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")).max(BigDecimal("0.25"))
        else BigDecimal("1.0")
    }

    private fun averageVolume(c: List<Candle>): BigDecimal =
        if (c.isEmpty()) BigDecimal.ZERO
        else c.fold(BigDecimal.ZERO) { a, x -> a.add(x.volume) }
            .divide(BigDecimal(c.size), 12, RoundingMode.HALF_UP)

    private fun insufficient(reason: String) =
        StrategyRuleSnapshot(false, false, 0, BigDecimal.ZERO, BigDecimal.ZERO, reason)

    private fun snapshot(
        entry: Boolean,
        score: Int,
        tp: BigDecimal,
        sl: BigDecimal,
        reason: String
    ) = StrategyRuleSnapshot(
        true,
        entry,
        score.coerceIn(0, 100),
        tp.max(BigDecimal("0.25")).setScale(3, RoundingMode.HALF_UP),
        sl.max(BigDecimal("0.25")).setScale(3, RoundingMode.HALF_UP),
        reason
    )

    private fun BigDecimal.s2(): String =
        setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
