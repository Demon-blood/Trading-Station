package com.ksp.cryptobot.strategy

import com.ksp.cryptobot.core.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class StrategyTruthRulesTest {
    private fun candle(
        i: Int,
        open: String,
        high: String,
        low: String,
        close: String,
        volume: String = "100"
    ) = Candle(
        symbol = "BTCEUR",
        timeframe = Timeframe.M15,
        openTimeEpochMs = i * 900_000L,
        open = BigDecimal(open),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        volume = BigDecimal(volume)
    )

    @Test fun breakoutExcludesCurrentBarFromResistanceAndNeedsVolume() {
        val history = (0 until 34).map { i ->
            candle(i, "99.8", "101.0", "99.0", "100.0", "100")
        }
        val breakout = candle(34, "100.0", "103.0", "99.8", "102.0", "150")
        val result = StrategyTruthRules.evaluate(
            StrategyMode.BREAKOUT,
            history + breakout,
            BotSettings()
        )
        assertTrue(result.enoughData)
        assertTrue(result.entry)

        val weakVolume = StrategyTruthRules.evaluate(
            StrategyMode.BREAKOUT,
            history + breakout.copy(volume = BigDecimal("110")),
            BotSettings()
        )
        assertFalse(weakVolume.entry)
    }

    @Test fun donchianUsesPriorTwentyBarHigh() {
        val history = (0 until 39).map { i ->
            candle(i, "100", "101", "99", "100")
        }
        val last = candle(39, "100", "103", "100", "102")
        val result = StrategyTruthRules.evaluate(
            StrategyMode.DONCHIAN_BREAKOUT,
            history + last,
            BotSettings()
        )
        assertTrue(result.entry)
        assertTrue(result.reason.contains("20-bar"))
    }

    @Test fun unsupportedNamedStrategyNeverGetsProxyEntryRule() {
        val candles = (0 until 80).map { i ->
            candle(i, "100", "101", "99", "100")
        }
        val result = StrategyTruthRules.evaluate(
            StrategyMode.PAIRS_RELATIVE_STRENGTH,
            candles,
            BotSettings()
        )
        assertFalse(result.enoughData)
        assertFalse(result.entry)
        assertTrue(result.reason.contains("TRUTH_BLOCKED"))
    }

    @Test fun momentumContinuationRequiresSeparateImpulseAndFollowThroughBars() {
        val baseline = (0 until 38).map { i ->
            candle(i, "100.0", "100.4", "99.8", "100.1", "100")
        }
        val impulse = candle(38, "100.0", "102.0", "99.5", "101.7", "180")
        val follow = candle(39, "101.6", "103.0", "100.9", "102.5", "100")
        val result = StrategyTruthRules.evaluate(
            StrategyMode.MOMENTUM_SPIKE_CONTINUATION,
            baseline + impulse + follow,
            BotSettings()
        )
        assertTrue(result.entry)

        val noFollow = StrategyTruthRules.evaluate(
            StrategyMode.MOMENTUM_SPIKE_CONTINUATION,
            baseline + impulse + follow.copy(close = BigDecimal("101.8"), high = BigDecimal("102.1")),
            BotSettings()
        )
        assertFalse(noFollow.entry)
    }
}
