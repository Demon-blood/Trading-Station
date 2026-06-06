package com.ksp.cryptobot.strategy

import com.ksp.cryptobot.core.Candle
import java.math.BigDecimal
import java.math.RoundingMode

object TechnicalIndicators {
    fun ema(values: List<BigDecimal>, period: Int): BigDecimal {
        if (values.isEmpty()) return BigDecimal.ZERO
        val safePeriod = period.coerceAtLeast(2)
        val k = BigDecimal("2").divide(BigDecimal(safePeriod + 1), 12, RoundingMode.HALF_UP)
        var ema = values.take(safePeriod).ifEmpty { values }.fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(values.take(safePeriod).ifEmpty { values }.size), 12, RoundingMode.HALF_UP)
        values.drop(safePeriod).forEach { value ->
            ema = value.multiply(k).add(ema.multiply(BigDecimal.ONE.subtract(k))).setScale(12, RoundingMode.HALF_UP)
        }
        return ema
    }

    fun obv(candles: List<Candle>): BigDecimal {
        if (candles.size < 2) return BigDecimal.ZERO
        var obv = BigDecimal.ZERO
        for (i in 1 until candles.size) {
            val prev = candles[i - 1]
            val current = candles[i]
            obv = when {
                current.close > prev.close -> obv.add(current.volume)
                current.close < prev.close -> obv.subtract(current.volume)
                else -> obv
            }
        }
        return obv
    }

    fun atr(candles: List<Candle>, period: Int): BigDecimal {
        if (candles.size < period + 1) return BigDecimal.ZERO
        val ranges = mutableListOf<BigDecimal>()
        for (i in 1 until candles.size) {
            val current = candles[i]
            val previousClose = candles[i - 1].close
            val highLow = current.high.subtract(current.low).abs()
            val highPrev = current.high.subtract(previousClose).abs()
            val lowPrev = current.low.subtract(previousClose).abs()
            ranges += maxOf(highLow, highPrev, lowPrev)
        }
        val selected = ranges.takeLast(period)
        return selected.fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(selected.size), 12, RoundingMode.HALF_UP)
    }

    fun percentChange(first: BigDecimal, last: BigDecimal): BigDecimal {
        if (first.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO
        return last.subtract(first)
            .divide(first, 8, RoundingMode.HALF_UP)
            .multiply(BigDecimal("100"))
    }
}
