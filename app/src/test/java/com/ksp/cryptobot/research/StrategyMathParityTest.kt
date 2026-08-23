package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Numeric regression vectors generated from desktop Crypto TradeStation v1.0.50. */
class StrategyMathParityTest {
    private fun candles(): List<Candle> {
        val out = mutableListOf<Candle>()
        var previousClose = 100.0
        repeat(96) { i ->
            val trend = 100.0 + 0.16 * i
            val cyc = 1.35 * sin(i / 5.0) + 0.42 * cos(i / 11.0)
            val close = trend + cyc
            val open = previousClose + 0.10 * sin(i / 3.0)
            val high = max(open, close) + 0.45 + 0.08 * (i % 4)
            val low = min(open, close) - 0.38 - 0.05 * (i % 3)
            val volume = 1200.0 + (i % 10) * 75.0 + if (i % 17 == 0) 240.0 else 0.0
            out += Candle(
                symbol = "BTCEUR",
                timeframe = Timeframe.M15,
                openTimeEpochMs = 1_700_000_000_000L + i * 900_000L,
                open = BigDecimal.valueOf(open),
                high = BigDecimal.valueOf(high),
                low = BigDecimal.valueOf(low),
                close = BigDecimal.valueOf(close),
                volume = BigDecimal.valueOf(volume)
            )
            previousClose = close
        }
        return out
    }

    @Test
    fun desktopIndicatorMathMatchesV1050RegressionVector() {
        val candles = candles()
        val closes = StrategyMath.closes(candles)
        val bb = StrategyMath.bollinger(closes, 20, 2.0)

        assertEquals(113.92592611984475, StrategyMath.ema(closes, 9), 1e-10)
        assertEquals(113.19447895808104, StrategyMath.ema(closes, 20), 1e-10)
        assertEquals(111.19651206633812, StrategyMath.ema(closes, 50), 1e-10)
        assertEquals(93.66648746552822, StrategyMath.rsi(closes, 14), 1e-10)
        assertEquals(0.17796349311790138, StrategyMath.volatilityPct(closes, 20), 1e-10)
        assertEquals(2.4692613528604324, StrategyMath.momentumPct(closes, 12), 1e-10)
        assertEquals(1.1291648125580864, StrategyMath.atrPct(candles, 14), 1e-10)
        assertEquals(111.28847933960404, StrategyMath.vwap(candles, 48), 1e-10)
        assertEquals(111.50861636624533, bb.lower, 1e-10)
        assertEquals(113.10459115714386, bb.mid, 1e-10)
        assertEquals(114.70056594804238, bb.upper, 1e-10)
        assertEquals(2.4692613528604324, StrategyMath.slopePct(closes, 12), 1e-10)
        assertEquals(82.57264243490748, StrategyMath.stochasticK(candles, 14), 1e-10)
        assertEquals(223.4767981358232, StrategyMath.cci(candles, 20), 1e-9)
        assertEquals(80.59266481251404, StrategyMath.desktopAdx(candles, 14), 1e-10)
    }
}
