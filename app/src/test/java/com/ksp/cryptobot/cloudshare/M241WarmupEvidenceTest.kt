package com.ksp.cryptobot.cloudshare

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class M241WarmupEvidenceTest {
    private fun downloaded(
        source: String,
        payload: Map<String, Any?>,
        eventId: String = "event-1"
    ) = CloudShareDownloadedEvent(
        eventId = eventId,
        aggregateKey = "aggregate-$eventId",
        contributorId = "contributor-1",
        sourceTable = source,
        eventTimestamp = Instant.now().toString(),
        receivedAt = Instant.now().toString(),
        payload = payload
    )

    @Test
    fun decisionSamplesAreObservationalEvidenceNotOutcomes() {
        val indexed = CollectiveIntelligenceIndexer.toIndex(
            downloaded(
                "shared_learning_daily",
                mapOf(
                    "event_type" to "decision",
                    "symbol" to "BTCEUR",
                    "strategy" to "AUTO",
                    "sample_count" to 120,
                    "positive_pnl_count" to 0,
                    "negative_pnl_count" to 0,
                    "zero_pnl_count" to 0,
                    "pnl_sum" to 0.0
                )
            )
        )
        assertEquals(120, indexed.sampleCount)
        assertFalse(indexed.isOutcome)
        assertEquals(0, indexed.wins)
        assertEquals(0, indexed.losses)
    }

    @Test
    fun signalSamplesArePreservedWithoutBecomingOutcomes() {
        val indexed = CollectiveIntelligenceIndexer.toIndex(
            downloaded(
                "shared_signal_daily",
                mapOf(
                    "symbol" to "ETHEUR",
                    "action" to "WATCH",
                    "sample_count" to 88,
                    "avg_score" to 61.0
                )
            )
        )
        assertEquals(88, indexed.sampleCount)
        assertFalse(indexed.isOutcome)
    }

    @Test
    fun resolvedSellTradesRemainRealOutcomeEvidence() {
        val indexed = CollectiveIntelligenceIndexer.toIndex(
            downloaded(
                "shared_trade_daily",
                mapOf(
                    "symbol" to "BTCEUR",
                    "strategy" to "AUTO",
                    "side" to "SELL",
                    "sample_count" to 30,
                    "wins" to 19,
                    "losses" to 11,
                    "avg_net_return_pct" to 0.42
                )
            )
        )
        assertTrue(indexed.isOutcome)
        assertEquals(30, indexed.sampleCount)
        assertEquals(19, indexed.wins)
        assertEquals(11, indexed.losses)
    }

    @Test
    fun observationalEvidenceNeverChangesCollectiveScore() {
        val observation = CollectiveOutcomeRow(
            eventId = "obs-1",
            contributorId = "c1",
            sourceTable = "shared_learning_daily",
            aggregateKey = "a1",
            symbol = "BTCEUR",
            strategy = "AUTO",
            regime = "",
            timeframe = "",
            eventType = "decision",
            sampleCount = 10_000,
            wins = 10_000,
            losses = 0,
            edgeSum = 9999.0,
            eventTimestamp = Instant.now().toString(),
            isOutcome = false
        )
        val result = CollectiveScoreMath.score(
            listOf(observation), "BTCEUR", "AUTO", "", "", 25, 6, 1.0
        )
        assertFalse(result.ready)
        assertEquals(0, result.adjustment)
        assertEquals(0, result.samples)
    }

    @Test
    fun freshObservationsCanMakeDataReadyWhileOutcomeLearningStillCollects() {
        val observation = CollectiveOutcomeRow(
            eventId = "obs-2",
            contributorId = "c1",
            sourceTable = "shared_signal_daily",
            aggregateKey = "a2",
            symbol = "BTCEUR",
            strategy = "AUTO",
            regime = "",
            timeframe = "",
            eventType = "signal",
            sampleCount = 100,
            wins = 0,
            losses = 0,
            edgeSum = 0.0,
            eventTimestamp = Instant.now().toString(),
            isOutcome = false
        )
        CloudShareCollectiveCache.install(
            outcomeRows = listOf(observation),
            enabled = true,
            minSamples = 25,
            maxAdjustment = 6,
            weight = 1.0
        )
        val snapshot = CloudShareCollectiveCache.snapshot()
        assertTrue(snapshot.dataReady)
        assertEquals("READY", snapshot.dataState)
        assertEquals(100, snapshot.indexedSamples)
        assertEquals(100, snapshot.observationSamples)
        assertEquals(0, snapshot.outcomeSamples)
        assertEquals("COLLECTING_OUTCOMES", snapshot.outcomeState)
        assertEquals(0, snapshot.totalSamples)
    }

    @Test
    fun oldEvidenceCannotFalselySatisfyDataReadiness() {
        val staleObservation = CollectiveOutcomeRow(
            eventId = "obs-old",
            contributorId = "c1",
            sourceTable = "shared_signal_daily",
            aggregateKey = "old",
            symbol = "BTCEUR",
            strategy = "AUTO",
            regime = "",
            timeframe = "",
            eventType = "signal",
            sampleCount = 500,
            wins = 0,
            losses = 0,
            edgeSum = 0.0,
            eventTimestamp = "2020-01-01T00:00:00Z",
            isOutcome = false
        )
        CloudShareCollectiveCache.install(
            outcomeRows = listOf(staleObservation),
            enabled = true,
            minSamples = 25,
            maxAdjustment = 6,
            weight = 1.0
        )
        val snapshot = CloudShareCollectiveCache.snapshot()
        assertFalse(snapshot.dataReady)
        assertEquals("STALE_DATA", snapshot.dataState)
    }

    @Test
    fun resolvedOutcomeThresholdStillControlsAdjustmentReadiness() {
        val outcome = CollectiveOutcomeRow(
            eventId = "outcome-1",
            contributorId = "c1",
            sourceTable = "shared_trade_daily",
            aggregateKey = "o1",
            symbol = "BTCEUR",
            strategy = "AUTO",
            regime = "",
            timeframe = "",
            eventType = "trade",
            sampleCount = 25,
            wins = 16,
            losses = 9,
            edgeSum = 5.0,
            eventTimestamp = Instant.now().toString(),
            isOutcome = true
        )
        val result = CollectiveScoreMath.score(
            listOf(outcome), "BTCEUR", "AUTO", "", "", 25, 6, 1.0
        )
        assertTrue(result.ready)
        assertEquals(25, result.samples)
    }
}
