package com.ksp.cryptobot.learning

import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.data.TradeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedLearningOutcomeTest {
    private fun trade(side: String, paper: Boolean, pnl: String, ts: Long) = TradeEntity(
        symbol = "BTCEUR",
        side = side,
        quantity = "0.001",
        priceEur = "100000",
        feeEur = "0.10",
        paper = paper,
        realizedPnlEur = pnl,
        timestampEpochMs = ts
    )

    @Test
    fun oneBuySellRoundTripCountsAsOneOutcomeNotTwoSamples() {
        val rows = listOf(
            trade("BUY", true, "0.00", 1L),
            trade("SELL", true, "2.50", 2L)
        )
        val outcomes = completedOutcomeTradesForLearning(rows, BotSettings(mode = BotMode.PAPER, selfLearningPaperAndLiveSeparated = true))
        assertEquals(1, outcomes.size)
        assertEquals("SELL", outcomes.single().side)
        assertEquals("2.50", outcomes.single().realizedPnlEur)
    }

    @Test
    fun separatedLearningUsesOnlyCurrentPaperLiveMode() {
        val rows = listOf(
            trade("SELL", true, "1.00", 1L),
            trade("SELL", false, "-1.00", 2L)
        )
        val paper = completedOutcomeTradesForLearning(rows, BotSettings(mode = BotMode.PAPER, selfLearningPaperAndLiveSeparated = true))
        val live = completedOutcomeTradesForLearning(rows, BotSettings(mode = BotMode.LIVE_AUTO, selfLearningPaperAndLiveSeparated = true))
        assertEquals(1, paper.size)
        assertTrue(paper.single().paper)
        assertEquals(1, live.size)
        assertFalse(live.single().paper)
    }

    private fun assertFalse(value: Boolean) = org.junit.Assert.assertFalse(value)
}
