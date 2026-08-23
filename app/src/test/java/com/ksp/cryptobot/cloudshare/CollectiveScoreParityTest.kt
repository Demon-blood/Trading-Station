package com.ksp.cryptobot.cloudshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectiveScoreParityTest {
    @Test
    fun aggregateKeyMatchesDesktopVector() {
        val payload = linkedMapOf<String, Any?>(
            "day" to "2026-08-17",
            "strategy" to "AUTO",
            "symbol" to "BTCEUR",
            "mode" to "PAPER",
            "side" to "SELL",
            "trade_size_band" to "MIXED",
            "sample_count" to 30,
            "wins" to 19,
            "losses" to 11,
            "avg_net_return_pct" to 0.42
        )
        assertEquals(
            "658a810e62ecd3f7f4761ebe047545245c7d9baa22839b262c30f975cef739b8",
            CloudShareProtocol.sharedAggregateKey("shared_trade_daily", payload)
        )
    }

    @Test
    fun scoreMatchesDesktopVector() {
        val rows = listOf(
            CollectiveOutcomeRow(
                eventId = "e1", contributorId = "c1", sourceTable = "shared_trade_daily", aggregateKey = "a",
                symbol = "BTCEUR", strategy = "AUTO", regime = "", timeframe = "", eventType = "",
                sampleCount = 30, wins = 19, losses = 11, edgeSum = 12.6,
                eventTimestamp = "2026-08-17T00:00:00Z"
            )
        )
        val result = CollectiveScoreMath.score(rows, "BTCEUR", "AUTO", "", "", 25, 6, 1.0)
        assertTrue(result.ready)
        assertEquals(2, result.adjustment)
        assertEquals("symbol+strategy", result.matchTier)
        assertEquals(30, result.samples)
    }

    @Test
    fun insufficientEvidenceIsNeutral() {
        val rows = listOf(
            CollectiveOutcomeRow("e1", "c1", "shared_trade_daily", "a", "BTCEUR", "AUTO", "", "", "", 8, 7, 1, 4.0, "2026-08-17T00:00:00Z")
        )
        val result = CollectiveScoreMath.score(rows, "BTCEUR", "AUTO", "", "", 25, 6, 1.0)
        assertFalse(result.ready)
        assertEquals(0, result.adjustment)
    }

    @Test
    fun collectiveAdjustmentIsBounded() {
        val rows = listOf(
            CollectiveOutcomeRow("e1", "c1", "shared_trade_daily", "a", "BTCEUR", "AUTO", "", "", "", 1000, 1000, 0, 5000.0, "2026-08-17T00:00:00Z")
        )
        assertEquals(6, CollectiveScoreMath.score(rows, "BTCEUR", "AUTO", "", "", 25, 6, 1.0).adjustment)
    }
}
