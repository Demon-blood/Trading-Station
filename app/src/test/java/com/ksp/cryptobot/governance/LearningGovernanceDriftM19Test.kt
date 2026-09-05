package com.ksp.cryptobot.governance

import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.data.ExecutionQualityEntity
import com.ksp.cryptobot.data.LearningFeatureSnapshotEntity
import com.ksp.cryptobot.data.TradeEntity
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class LearningGovernanceDriftM19Test {
    private val engine = LearningGovernanceEngine()

    private fun snapshot(
        i: Int,
        score: Int = 70,
        spread: String = "0.05",
        volume: String = "1000000",
        move: String = "1.0",
        regime: String? = "TRENDING_UP"
    ) = LearningFeatureSnapshotEntity(
        timestampEpochMs = i.toLong(),
        symbol = "BTCEUR",
        strategyMode = "TREND",
        mode = "PAPER",
        action = "BUY",
        finalScore = score,
        technicalScore = score,
        newsScore = 50,
        memoryScore = 50,
        spreadPercent = spread,
        volume24h = volume,
        priceChange24hPercent = move,
        allowedToTrade = true,
        traded = true,
        reason = regime?.let { "regime=$it test" } ?: "no regime label"
    )

    private fun execution(i: Int, slippage: Double) = ExecutionQualityEntity(
        timestampEpochMs = i.toLong(),
        symbol = "BTCEUR",
        side = "BUY",
        mode = "LIVE",
        orderType = "LIMIT",
        expectedPrice = 100.0,
        actualPrice = 100.0,
        slippagePct = slippage,
        notionalQuote = 20.0
    )

    private fun outcome(i: Int, pnl: String, score: Int = 75) = TradeEntity(
        symbol = "BTCEUR",
        side = "SELL",
        quantity = "0.1",
        priceEur = "100",
        feeEur = "0.04",
        paper = true,
        realizedPnlEur = pnl,
        aiScore = score,
        aiReason = "[TREND]",
        timestampEpochMs = i.toLong()
    )

    @Test fun featureDriftStaysUnknownWithInsufficientEvidence() {
        val drift = engine.featureDrift((0 until 40).map { snapshot(it) })
        assertFalse(drift.known)
        assertFalse(drift.severe)
    }

    @Test fun regimeDriftStaysUnknownWithoutLabels() {
        val drift = engine.regimeDrift((0 until 100).map { snapshot(it, regime = null) })
        assertFalse(drift.known)
    }

    @Test fun severeExecutionDeteriorationTriggersRollback() {
        val execution = buildList {
            repeat(50) { add(execution(it, 0.03)) }
            repeat(25) { add(execution(50 + it, 0.35)) }
        }
        val outcomes = (0 until 60).map { outcome(it, if (it % 4 == 0) "-0.10" else "0.30") }
        val snapshots = (0 until 160).map { snapshot(it) }

        val assessment = engine.assess(
            BotMode.PAPER,
            snapshots,
            outcomes,
            execution,
            emptyList()
        )
        assertTrue(assessment.executionDrift.severe)
        assertTrue(assessment.rollbackRequired)
        assertEquals(LearningGovernanceStage.ROLLBACK, assessment.stage)
        assertTrue(assessment.bounds.fillProbabilityOffset <= 0.0)
        assertTrue(assessment.bounds.amendFillProbabilityThreshold <= 0.60)
    }

    @Test fun liveSizeCeilingIsNeverAboveOne() {
        val assessment = engine.assess(
            BotMode.LIVE_AUTO,
            emptyList(),
            emptyList(),
            emptyList(),
            emptyList()
        )
        assertTrue(assessment.bounds.liveSizeMultiplierCeiling <= BigDecimal.ONE)
        assertEquals(0, assessment.bounds.scoreBoostCeiling)
    }

    @Test fun positiveLearningNeedsStatisticalEvidenceNotOneGoodTrade() {
        val assessment = engine.assess(
            BotMode.PAPER,
            (0 until 160).map { snapshot(it) },
            listOf(outcome(1, "10.00")),
            (0 until 80).map { execution(it, 0.03) },
            emptyList()
        )
        assertFalse(assessment.positiveLearningEnabled)
    }
}
