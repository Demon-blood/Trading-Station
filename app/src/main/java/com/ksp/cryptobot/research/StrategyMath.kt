package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Indicator math shared by the desktop-parity and practitioner strategy engines.
 *
 * The parity helpers intentionally mirror Crypto TradeStation desktop v1.0.50
 * semantics (including its EMA seed and simplified ADX/Supertrend helpers) so
 * strategy behavior can be compared deterministically across platforms.
 */
internal object StrategyMath {
    data class Bollinger(val lower: Double, val mid: Double, val upper: Double)
    data class Macd(val bullishCross: Boolean, val bearishCross: Boolean, val macd: Double, val signal: Double)
    data class Dmi(val plusDi: Double, val minusDi: Double, val adx: Double)
    data class Supertrend(val bullish: Boolean, val band: Double)
    data class Psar(val value: Double, val bullish: Boolean, val flippedBullish: Boolean, val flippedBearish: Boolean)

    fun closes(candles: List<Candle>): List<Double> = candles.mapNotNull { it.close.toDouble().takeIf { v -> v > 0.0 } }

    /** Exact desktop src/indicators.py EMA: first observed value is the seed. */
    fun emaSeries(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty()) return emptyList()
        val p = period.coerceAtLeast(1)
        val k = 2.0 / (p + 1.0)
        val out = ArrayList<Double>(values.size)
        out += values.first()
        for (i in 1 until values.size) out += values[i] * k + out.last() * (1.0 - k)
        return out
    }

    fun ema(values: List<Double>, period: Int): Double = emaSeries(values, period).lastOrNull() ?: 0.0

    /** Exact desktop simple-period RSI calculation. */
    fun rsi(values: List<Double>, period: Int = 14): Double {
        if (values.size <= period) return 50.0
        var gains = 0.0
        var losses = 0.0
        val start = values.size - period - 1
        for (i in start until values.lastIndex) {
            val change = values[i + 1] - values[i]
            if (change >= 0) gains += change else losses += abs(change)
        }
        val avgGain = gains / period
        val avgLoss = losses / period
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    fun volatilityPct(values: List<Double>, lookback: Int = 20): Double {
        if (values.size < 2) return 0.0
        val w = values.takeLast(lookback)
        if (w.size < 2) return 0.0
        val returns = w.zipWithNext().mapNotNull { (a, b) -> if (a > 0.0) (b - a) / a else null }
        if (returns.isEmpty()) return 0.0
        val avg = returns.average()
        val variance = returns.sumOf { (it - avg) * (it - avg) } / returns.size
        return sqrt(variance) * 100.0
    }

    fun momentumPct(values: List<Double>, lookback: Int = 12): Double {
        if (values.size <= lookback) return 0.0
        val start = values[values.size - lookback]
        if (start == 0.0) return 0.0
        return (values.last() - start) / start * 100.0
    }

    /** Exact desktop strategy_expansion._atr_pct(). */
    fun atrPct(candles: List<Candle>, period: Int = 14): Double {
        if (candles.size < period + 1) return 0.0
        val trs = ArrayList<Double>(period)
        var prevClose = candles[candles.size - period - 1].close.toDouble()
        for (c in candles.takeLast(period)) {
            val high = c.high.toDouble(); val low = c.low.toDouble(); val close = c.close.toDouble()
            val tr = max(high - low, max(abs(high - prevClose), abs(low - prevClose)))
            if (close > 0.0) trs += tr / close * 100.0
            prevClose = close
        }
        return trs.average().takeIf { !it.isNaN() } ?: 0.0
    }

    fun atrAbs(candles: List<Candle>, period: Int = 14): Double {
        if (candles.size < period + 1) return 0.0
        val trs = ArrayList<Double>(period)
        var prevClose = candles[candles.size - period - 1].close.toDouble()
        for (c in candles.takeLast(period)) {
            val h = c.high.toDouble(); val l = c.low.toDouble()
            trs += max(h - l, max(abs(h - prevClose), abs(l - prevClose)))
            prevClose = c.close.toDouble()
        }
        return trs.average().takeIf { !it.isNaN() } ?: 0.0
    }

    fun vwap(candles: List<Candle>, lookback: Int = 48): Double {
        val w = candles.takeLast(lookback)
        val volSum = w.sumOf { it.volume.toDouble().coerceAtLeast(0.0) }
        if (volSum <= 0.0) return 0.0
        return w.sumOf {
            val typical = (it.high.toDouble() + it.low.toDouble() + it.close.toDouble()) / 3.0
            typical * it.volume.toDouble().coerceAtLeast(0.0)
        } / volSum
    }

    /** Anchored VWAP from an index in the supplied candle list. */
    fun anchoredVwap(candles: List<Candle>, anchorIndex: Int): Double {
        if (candles.isEmpty()) return 0.0
        val idx = anchorIndex.coerceIn(0, candles.lastIndex)
        val w = candles.subList(idx, candles.size)
        val volume = w.sumOf { it.volume.toDouble().coerceAtLeast(0.0) }
        if (volume <= 0.0) return 0.0
        return w.sumOf {
            ((it.high.toDouble() + it.low.toDouble() + it.close.toDouble()) / 3.0) * it.volume.toDouble().coerceAtLeast(0.0)
        } / volume
    }

    fun bollinger(values: List<Double>, period: Int = 20, mult: Double = 2.0): Bollinger {
        if (values.size < period) return Bollinger(0.0, 0.0, 0.0)
        val w = values.takeLast(period)
        val mid = w.average()
        val sd = if (w.size > 1) sqrt(w.sumOf { (it - mid) * (it - mid) } / w.size) else 0.0
        return Bollinger(mid - mult * sd, mid, mid + mult * sd)
    }

    fun slopePct(values: List<Double>, lookback: Int = 12): Double {
        if (values.size <= lookback) return 0.0
        val start = values[values.size - lookback]
        if (start <= 0.0) return 0.0
        return (values.last() - start) / start * 100.0
    }

    fun bodyPct(c: Candle): Double {
        val close = max(c.close.toDouble(), 1e-12)
        return abs(c.close.toDouble() - c.open.toDouble()) / close * 100.0
    }

    /** Exact desktop simplified Supertrend helper. */
    fun desktopSupertrendState(candles: List<Candle>, period: Int = 10, multiplier: Double = 3.0): Supertrend {
        if (candles.size < period + 3) return Supertrend(false, 0.0)
        val atr = ArrayList<Double>(period)
        var prevClose = candles[candles.size - period - 1].close.toDouble()
        for (c in candles.takeLast(period)) {
            val h = c.high.toDouble(); val l = c.low.toDouble(); val cl = c.close.toDouble()
            atr += max(h - l, max(abs(h - prevClose), abs(l - prevClose)))
            prevClose = cl
        }
        val atrAbs = atr.average().takeIf { !it.isNaN() } ?: 0.0
        val last = candles.last()
        val hl2 = (last.high.toDouble() + last.low.toDouble()) / 2.0
        val lower = hl2 - multiplier * atrAbs
        return Supertrend(last.close.toDouble() > lower, lower)
    }

    fun hasBullishDivergence(candles: List<Candle>, lookback: Int = 28): Boolean {
        if (candles.size < lookback + 16) return false
        val cs = closes(candles)
        val w = candles.takeLast(lookback)
        val candidates = (2 until w.size - 2).sortedBy { w[it].low.toDouble() }.take(6).sorted()
        if (candidates.size < 2) return false
        val first = candidates.first(); val second = candidates.last()
        val priceLowerLow = w[second].low.toDouble() < w[first].low.toDouble()
        fun rsiAt(localIndex: Int): Double {
            val cut = lookback - localIndex
            val end = if (cut > 0) (cs.size - cut).coerceAtLeast(0) else cs.size
            return rsi(cs.take(end), 14)
        }
        return priceLowerLow && rsiAt(second) > rsiAt(first) + 3.0
    }

    fun highestHigh(candles: List<Candle>, lookback: Int): Double =
        if (candles.size >= lookback) candles.takeLast(lookback).maxOf { it.high.toDouble() } else 0.0

    fun lowestLow(candles: List<Candle>, lookback: Int): Double =
        if (candles.size >= lookback) candles.takeLast(lookback).minOf { it.low.toDouble() } else 0.0

    fun macdCross(candles: List<Candle>): Macd {
        val cs = closes(candles)
        if (cs.size < 35) return Macd(false, false, 0.0, 0.0)
        val fast = emaSeries(cs, 12); val slow = emaSeries(cs, 26)
        val macd = fast.zip(slow) { f, s -> f - s }
        if (macd.size < 10) return Macd(false, false, 0.0, 0.0)
        val sig = emaSeries(macd, 9)
        val bull = macd[macd.lastIndex - 1] <= sig[sig.lastIndex - 1] && macd.last() > sig.last()
        val bear = macd[macd.lastIndex - 1] >= sig[sig.lastIndex - 1] && macd.last() < sig.last()
        return Macd(bull, bear, macd.last(), sig.last())
    }

    fun stochasticK(candles: List<Candle>, period: Int = 14): Double {
        if (candles.size < period) return 50.0
        val hi = highestHigh(candles, period); val lo = lowestLow(candles, period)
        return (candles.last().close.toDouble() - lo) / max(hi - lo, 1e-12) * 100.0
    }

    fun cci(candles: List<Candle>, period: Int = 20): Double {
        if (candles.size < period) return 0.0
        val tp = candles.takeLast(period).map { (it.high.toDouble() + it.low.toDouble() + it.close.toDouble()) / 3.0 }
        val sma = tp.average(); val dev = tp.sumOf { abs(it - sma) } / tp.size
        return (tp.last() - sma) / (0.015 * max(dev, 1e-12))
    }

    /** Exact desktop strategy_expansion._adx(): one-window DX, not Wilder ADX. */
    fun desktopAdx(candles: List<Candle>, period: Int = 14): Double {
        if (candles.size < period + 2) return 0.0
        var plus = 0.0; var minus = 0.0; var tr = 0.0
        for (i in candles.size - period until candles.size) {
            val cur = candles[i]; val prev = candles[i - 1]
            val up = cur.high.toDouble() - prev.high.toDouble()
            val down = prev.low.toDouble() - cur.low.toDouble()
            if (up > down && up > 0) plus += up
            if (down > up && down > 0) minus += down
            tr += max(cur.high.toDouble() - cur.low.toDouble(), max(abs(cur.high.toDouble() - prev.close.toDouble()), abs(cur.low.toDouble() - prev.close.toDouble())))
        }
        val pdi = 100.0 * plus / max(tr, 1e-12)
        val mdi = 100.0 * minus / max(tr, 1e-12)
        return 100.0 * abs(pdi - mdi) / max(pdi + mdi, 1e-12)
    }

    /** Wilder-smoothed DMI/ADX used by the practitioner strategy layer. */
    fun wilderDmi(candles: List<Candle>, period: Int = 14): Dmi {
        if (candles.size < period * 2 + 1) return Dmi(0.0, 0.0, 0.0)
        val trs = mutableListOf<Double>(); val plusDm = mutableListOf<Double>(); val minusDm = mutableListOf<Double>()
        for (i in 1 until candles.size) {
            val cur = candles[i]; val prev = candles[i - 1]
            val up = cur.high.toDouble() - prev.high.toDouble()
            val down = prev.low.toDouble() - cur.low.toDouble()
            plusDm += if (up > down && up > 0.0) up else 0.0
            minusDm += if (down > up && down > 0.0) down else 0.0
            trs += max(cur.high.toDouble() - cur.low.toDouble(), max(abs(cur.high.toDouble() - prev.close.toDouble()), abs(cur.low.toDouble() - prev.close.toDouble())))
        }
        var trSm = trs.take(period).sum(); var plusSm = plusDm.take(period).sum(); var minusSm = minusDm.take(period).sum()
        val dx = mutableListOf<Double>()
        fun pushDx() {
            val pdi = 100.0 * plusSm / max(trSm, 1e-12)
            val mdi = 100.0 * minusSm / max(trSm, 1e-12)
            dx += 100.0 * abs(pdi - mdi) / max(pdi + mdi, 1e-12)
        }
        pushDx()
        for (i in period until trs.size) {
            trSm = trSm - trSm / period + trs[i]
            plusSm = plusSm - plusSm / period + plusDm[i]
            minusSm = minusSm - minusSm / period + minusDm[i]
            pushDx()
        }
        if (dx.isEmpty()) return Dmi(0.0, 0.0, 0.0)
        var adx = dx.take(period).average().takeIf { !it.isNaN() } ?: dx.first()
        for (v in dx.drop(period)) adx = (adx * (period - 1) + v) / period
        val plusDi = 100.0 * plusSm / max(trSm, 1e-12)
        val minusDi = 100.0 * minusSm / max(trSm, 1e-12)
        return Dmi(plusDi, minusDi, adx)
    }

    /** Full iterative Supertrend for professional confirmation; desktop parity uses desktopSupertrendState(). */
    fun fullSupertrend(candles: List<Candle>, period: Int = 10, multiplier: Double = 3.0): Supertrend {
        if (candles.size < period + 3) return Supertrend(false, 0.0)
        val tr = DoubleArray(candles.size)
        for (i in 1 until candles.size) {
            val c = candles[i]; val prev = candles[i - 1]
            tr[i] = max(c.high.toDouble() - c.low.toDouble(), max(abs(c.high.toDouble() - prev.close.toDouble()), abs(c.low.toDouble() - prev.close.toDouble())))
        }
        val atr = DoubleArray(candles.size)
        atr[period] = tr.slice(1..period).average()
        for (i in period + 1 until candles.size) atr[i] = (atr[i - 1] * (period - 1) + tr[i]) / period
        var finalUpper = 0.0; var finalLower = 0.0; var band = 0.0; var bullish = true
        for (i in period until candles.size) {
            val hl2 = (candles[i].high.toDouble() + candles[i].low.toDouble()) / 2.0
            val basicUpper = hl2 + multiplier * atr[i]
            val basicLower = hl2 - multiplier * atr[i]
            if (i == period) {
                finalUpper = basicUpper; finalLower = basicLower
                bullish = candles[i].close.toDouble() >= finalLower
                band = if (bullish) finalLower else finalUpper
                continue
            }
            finalUpper = if (basicUpper < finalUpper || candles[i - 1].close.toDouble() > finalUpper) basicUpper else finalUpper
            finalLower = if (basicLower > finalLower || candles[i - 1].close.toDouble() < finalLower) basicLower else finalLower
            val close = candles[i].close.toDouble()
            bullish = if (bullish) close >= finalLower else close > finalUpper
            band = if (bullish) finalLower else finalUpper
        }
        return Supertrend(bullish, band)
    }

    /** Standard Parabolic SAR approximation with acceleration 0.02 -> 0.20. */
    fun parabolicSar(candles: List<Candle>, step: Double = 0.02, maxStep: Double = 0.20): Psar {
        if (candles.size < 5) return Psar(0.0, false, false, false)
        var bullish = candles[1].close.toDouble() >= candles[0].close.toDouble()
        var sar = if (bullish) min(candles[0].low.toDouble(), candles[1].low.toDouble()) else max(candles[0].high.toDouble(), candles[1].high.toDouble())
        var ep = if (bullish) max(candles[0].high.toDouble(), candles[1].high.toDouble()) else min(candles[0].low.toDouble(), candles[1].low.toDouble())
        var af = step
        var flippedBull = false; var flippedBear = false
        for (i in 2 until candles.size) {
            val prevBull = bullish
            sar += af * (ep - sar)
            if (bullish) {
                sar = min(sar, min(candles[i - 1].low.toDouble(), candles[i - 2].low.toDouble()))
                if (candles[i].low.toDouble() < sar) {
                    bullish = false; sar = ep; ep = candles[i].low.toDouble(); af = step
                } else if (candles[i].high.toDouble() > ep) {
                    ep = candles[i].high.toDouble(); af = min(maxStep, af + step)
                }
            } else {
                sar = max(sar, max(candles[i - 1].high.toDouble(), candles[i - 2].high.toDouble()))
                if (candles[i].high.toDouble() > sar) {
                    bullish = true; sar = ep; ep = candles[i].high.toDouble(); af = step
                } else if (candles[i].low.toDouble() < ep) {
                    ep = candles[i].low.toDouble(); af = min(maxStep, af + step)
                }
            }
            if (!prevBull && bullish) flippedBull = true
            if (prevBull && !bullish) flippedBear = true
            if (i != candles.lastIndex) { flippedBull = false; flippedBear = false }
        }
        return Psar(sar, bullish, flippedBull, flippedBear)
    }

    fun volumeRatio(candles: List<Candle>, recent: Int = 4, baseline: Int = 30): Double {
        if (candles.isEmpty()) return 0.0
        val r = candles.takeLast(recent).map { it.volume.toDouble().coerceAtLeast(0.0) }.average().takeIf { !it.isNaN() } ?: 0.0
        val prior = candles.dropLast(recent).takeLast(baseline).map { it.volume.toDouble().coerceAtLeast(0.0) }
        val b = prior.average().takeIf { prior.isNotEmpty() && !it.isNaN() } ?: r
        return if (b > 0.0) r / b else 0.0
    }

    fun spreadPct(last: Double, bid: Double, ask: Double): Double = if (last > 0.0) abs(ask - bid) / last * 100.0 else 999.0
    /** On-balance-volume series for participation/accumulation confirmation. */
    fun obvSeries(candles: List<Candle>): List<Double> {
        if (candles.isEmpty()) return emptyList()
        val out = MutableList(candles.size) { 0.0 }
        var obv = 0.0
        for (i in 1 until candles.size) {
            val cur = candles[i].close.toDouble()
            val prev = candles[i - 1].close.toDouble()
            val volume = candles[i].volume.toDouble().coerceAtLeast(0.0)
            when {
                cur > prev -> obv += volume
                cur < prev -> obv -= volume
            }
            out[i] = obv
        }
        return out
    }

    /** Population z-score of the latest observation versus the requested rolling window. */
    fun zScore(values: List<Double>, period: Int = 30): Double {
        if (values.isEmpty()) return 0.0
        val window = values.takeLast(period.coerceAtLeast(2))
        if (window.size < 2) return 0.0
        val mean = window.average()
        val variance = window.sumOf { (it - mean) * (it - mean) } / window.size
        val std = kotlin.math.sqrt(variance)
        return if (std > 1e-12) (window.last() - mean) / std else 0.0
    }

    /** Simple market-structure confirmation: newer swing has both a higher high and higher low. */
    fun higherHighHigherLow(candles: List<Candle>, swing: Int = 8): Boolean {
        val n = swing.coerceAtLeast(2)
        if (candles.size < n * 2) return false
        val older = candles.dropLast(n).takeLast(n)
        val newer = candles.takeLast(n)
        return newer.maxOf { it.high.toDouble() } > older.maxOf { it.high.toDouble() } &&
            newer.minOf { it.low.toDouble() } > older.minOf { it.low.toDouble() }
    }

}
