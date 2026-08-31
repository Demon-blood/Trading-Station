package com.ksp.cryptobot.backtest

import com.ksp.cryptobot.core.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class BacktestTruthGateM18Test {
    private fun candles(tf: Timeframe, count: Int = 120): List<Candle> =
        (0 until count).map { i ->
            val base = BigDecimal("100").add(BigDecimal(i).multiply(BigDecimal("0.05")))
            Candle(
                symbol = "BTCEUR",
                timeframe = tf,
                openTimeEpochMs = i * 3_600_000L,
                open = base,
                high = base.add(BigDecimal("0.50")),
                low = base.subtract(BigDecimal("0.50")),
                close = base.add(BigDecimal("0.10")),
                volume = BigDecimal("1000")
            )
        }

    @Test fun architectureRequiredGridCanNeverPassProxyBacktest() {
        val report = BacktestEngine().run(
            "BTCEUR",
            Timeframe.M15,
            StrategyMode.RANGE_GRID,
            candles(Timeframe.M15),
            BotSettings()
        )
        assertFalse(report.passedLiveGate)
        assertEquals(0, report.trades)
        assertTrue(report.summary.contains("TRUTH_BLOCKED"))
    }

    @Test fun pairsRelativeStrengthCannotBeBacktestedWithoutBenchmarkSeries() {
        val report = BacktestEngine().run(
            "BTCEUR",
            Timeframe.M15,
            StrategyMode.PAIRS_RELATIVE_STRENGTH,
            candles(Timeframe.M15),
            BotSettings()
        )
        assertFalse(report.passedLiveGate)
        assertTrue(report.summary.contains("Required="))
    }

    @Test fun wrongTimeframeCannotPretendToValidateDonchianVariant() {
        val report = BacktestEngine().run(
            "BTCEUR",
            Timeframe.M15,
            StrategyMode.DONCHIAN_BREAKOUT,
            candles(Timeframe.M15),
            BotSettings()
        )
        assertFalse(report.passedLiveGate)
        assertTrue(report.summary.contains("requires H1"))
    }

    @Test fun autoIsNotAStandAloneBacktestStrategy() {
        val report = BacktestEngine().run(
            "BTCEUR",
            Timeframe.H1,
            StrategyMode.AUTO,
            candles(Timeframe.H1),
            BotSettings()
        )
        assertFalse(report.passedLiveGate)
        assertTrue(report.summary.contains("selector"))
    }
}
