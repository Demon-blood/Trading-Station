package com.ksp.cryptobot.governance

import com.ksp.cryptobot.data.AiValueAttributionEntity
import com.ksp.cryptobot.data.TradeEntity
import org.junit.Assert.*
import org.junit.Test

class LearningGovernanceStatisticsM19Test {
    private val engine = LearningGovernanceEngine()

    private fun trade(i: Int, pnl: String, score: Int) = TradeEntity(
        symbol = "BTCEUR",
        side = "SELL",
        quantity = "1",
        priceEur = "100",
        feeEur = "0.4",
        paper = true,
        realizedPnlEur = pnl,
        aiScore = score,
        timestampEpochMs = i.toLong()
    )

    private fun ai(i: Int, value: String, cost: String) = AiValueAttributionEntity(
        fingerprint = "f$i",
        createdAtEpochMs = i.toLong(),
        updatedAtEpochMs = i.toLong(),
        resolvedAtEpochMs = i.toLong(),
        symbol = "BTCEUR",
        strategy = "TREND",
        regime = "TRENDING_UP",
        modelPath = "LUNA",
        deterministicAction = "BUY",
        deterministicNotionalQuote = "20",
        lunaVerdict = "APPROVE",
        lunaRiskMultiplier = "1",
        finalVerdict = "APPROVE",
        finalRiskMultiplier = "1",
        entryPrice = "100",
        targetPrice = "102",
        stopPrice = "99",
        horizonMinutes = 60,
        estimatedRoundTripCostRate = "0.008",
        lunaCostQuote = cost,
        solCostQuote = "0",
        totalAiCostQuote = cost,
        status = "RESOLVED",
        resolution = "test",
        aiValueAddedQuote = value
    )

    @Test fun poorConfidenceCalibrationIsDetected() {
        val rows = (0 until 30).map { i ->
            // Very high confidence paired with losses.
            trade(i, "-1.0", 95)
        }
        val result = engine.confidenceCalibration(rows)
        assertTrue(result.known)
        assertTrue(result.severe)
        assertTrue(result.brierScore > 0.32)
    }

    @Test fun unstableThreeChunkOutcomesDoNotPassStability() {
        val rows = buildList {
            repeat(10) { add(trade(it, "1.0", 70)) }
            repeat(10) { add(trade(10 + it, "-1.0", 70)) }
            repeat(10) { add(trade(20 + it, "1.0", 70)) }
        }
        val result = engine.parameterStability(rows)
        assertTrue(result.known)
        assertFalse(result.stable)
    }

    @Test fun consistentlyNegativeAiValueBecomesDefensive() {
        val rows = (0 until 30).map { ai(it, "-0.20", "0.01") }
        val result = engine.modelAttribution(rows)
        assertTrue(result.known)
        assertTrue(result.defensive)
        assertTrue(result.upper95MeanValueQuote.signum() < 0)
    }
}
