package com.ksp.cryptobot.strategy.structure

import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.SignalAction
import com.ksp.cryptobot.core.StrategyCandidate
import com.ksp.cryptobot.core.StrategyMode
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * CTS_REFERENCE formalization inspired by the public Koroush 7/30/100 framework.
 * Numeric fan, lookback and retest thresholds are CTS-defined hypotheses and
 * must never be presented as Koroush's exact rules.
 */
data class KakReferenceConfig(
    val fanThresholdPct: BigDecimal = BigDecimal("0.75"),
    val breakoutLookback: Int = 20,
    val retestTolerancePct: BigDecimal = BigDecimal("0.35"),
    val minNetR: BigDecimal = BigDecimal("2.0")
)

data class KakReferenceEvaluation(
    val trendUp: Boolean,
    val rangeLike: Boolean,
    val breakoutClose: Boolean,
    val retestHeld: Boolean,
    val resistance: BigDecimal,
    val invalidation: BigDecimal,
    val target: BigDecimal,
    val action: SignalAction,
    val reason: String
)

class KoroushCtsReferenceStrategy(private val cfg: KakReferenceConfig = KakReferenceConfig()) {
    fun evaluate(ticker: MarketTicker, committed: List<Candle>): KakReferenceEvaluation {
        if (committed.size < 110) return empty("CTS KAK reference needs >=110 committed candles; have ${committed.size}.")
        val closes = committed.map { it.close }
        val ma7 = sma(closes, 7)
        val ma30 = sma(closes, 30)
        val ma100 = sma(closes, 100)
        val max = maxOf(ma7, ma30, ma100)
        val min = minOf(ma7, ma30, ma100)
        val normalizedSpreadPct = if (ticker.lastPrice > BigDecimal.ZERO) {
            max.subtract(min).divide(ticker.lastPrice, 10, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
        } else BigDecimal.ZERO
        val trendUp = ma7 > ma30 && ma30 > ma100 && normalizedSpreadPct >= cfg.fanThresholdPct
        val rangeLike = normalizedSpreadPct < cfg.fanThresholdPct
        val lookback = committed.dropLast(1).takeLast(cfg.breakoutLookback.coerceAtLeast(5))
        val resistance = lookback.maxOf { it.high }
        val last = committed.last()
        val breakout = trendUp && last.close > resistance
        val tol = resistance.multiply(cfg.retestTolerancePct).divide(BigDecimal("100"), 10, RoundingMode.HALF_UP)
        val retestHeld = breakout && last.low <= resistance.add(tol) && last.close >= resistance
        val invalidation = lookback.takeLast(5).minOf { it.low }
        val risk = last.close.subtract(invalidation).max(BigDecimal.ZERO)
        val target = if (risk > BigDecimal.ZERO) last.close.add(risk.multiply(cfg.minNetR)) else BigDecimal.ZERO
        val action = if (retestHeld) SignalAction.BUY else SignalAction.WAIT
        val reason = "CTS_REFERENCE KAK 7/30/100: trendUp=$trendUp rangeLike=$rangeLike spread=${normalizedSpreadPct.setScale(3,RoundingMode.HALF_UP)}%, closeBreak=$breakout retestHeld=$retestHeld. fanThreshold=${cfg.fanThresholdPct}%/lookback=${cfg.breakoutLookback}/retestTolerance=${cfg.retestTolerancePct}% are CTS-defined, not source-exact."
        return KakReferenceEvaluation(trendUp, rangeLike, breakout, retestHeld, resistance, invalidation, target, action, reason)
    }

    fun candidate(ticker: MarketTicker, committed: List<Candle>): StrategyCandidate {
        val e = evaluate(ticker, committed)
        val score = when {
            e.retestHeld -> 80
            e.breakoutClose -> 68
            e.trendUp -> 58
            e.rangeLike -> 35
            else -> 45
        }
        return StrategyCandidate(
            StrategyMode.valueOf("CTS_KAK_CLOSE_BREAK_RETEST_V1"),
            score,
            if (e.retestHeld) SignalAction.BUY else SignalAction.WAIT,
            e.reason + " This BUY is a CTS_REFERENCE research/backtest hypothesis; registry promotion gates block Paper/Live. Signal score is not calibrated win probability.",
            BigDecimal.ZERO,
            BigDecimal.ZERO
        )
    }

    private fun sma(values: List<BigDecimal>, period: Int): BigDecimal =
        values.takeLast(period).fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(period), 12, RoundingMode.HALF_UP)

    private fun empty(reason: String) = KakReferenceEvaluation(
        false,false,false,false,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,SignalAction.WAIT,reason
    )
}
