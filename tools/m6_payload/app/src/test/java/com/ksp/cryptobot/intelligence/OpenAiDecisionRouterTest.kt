package com.ksp.cryptobot.intelligence

import com.ksp.cryptobot.core.AiDecision
import com.ksp.cryptobot.core.DecisionSource
import com.ksp.cryptobot.core.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class OpenAiDecisionRouterTest {
    private fun decision(
        action: SignalAction = SignalAction.SMALL_BUY,
        score: Int = 74,
        confidence: Int = 74,
        news: Int = 0,
        allowed: Boolean = true
    ) = AiDecision(
        symbol = "BTCEUR",
        finalAction = action,
        finalScore = score,
        confidencePercent = confidence,
        technicalScore = score,
        newsScore = news,
        memoryScore = 0,
        allowedToTrade = allowed,
        explanation = "deterministic",
        source = DecisionSource.COMBINED_AI,
        createdAt = Instant.ofEpochMilli(1_787_500_000_000L)
    )

    private fun review(
        base: AiDecision,
        verdict: CloudAiVerdict,
        multiplier: String = "1.0"
    ) = CloudAiReview(
        fingerprint = CloudAiRuntime.fingerprint(base),
        symbol = base.symbol,
        verdict = verdict,
        confidence = BigDecimal("0.80"),
        strategy = "TREND",
        regime = "TRENDING_UP",
        riskMultiplier = BigDecimal(multiplier),
        reason = "validator",
        invalidationConditions = emptyList(),
        modelPath = "LUNA",
        totalCostUsd = BigDecimal("0.001"),
        totalCostQuote = BigDecimal("0.001")
    )

    @Test
    fun clearHighConfidenceSmallCandidateStaysOnZeroCostPath() {
        val base = decision(score = 90, confidence = 90, news = 2)
        assertFalse(CloudAiDecisionPolicy.shouldValidateWithLuna(base, BigDecimal("10")))
    }

    @Test
    fun uncertainBuyRoutesToLuna() {
        val base = decision(score = 74, confidence = 70)
        assertTrue(CloudAiDecisionPolicy.shouldValidateWithLuna(base, BigDecimal("10")))
    }

    @Test
    fun waitCannotBePromotedByCloudApproval() {
        val base = decision(action = SignalAction.WAIT, allowed = false)
        val changed = CloudAiDecisionPolicy.applyReview(base, review(base, CloudAiVerdict.APPROVE))
        assertEquals(SignalAction.WAIT, changed.finalAction)
        assertFalse(changed.allowedToTrade)
    }

    @Test
    fun approvalCannotUpgradeSmallBuyToBuy() {
        val base = decision(action = SignalAction.SMALL_BUY)
        val changed = CloudAiDecisionPolicy.applyReview(base, review(base, CloudAiVerdict.APPROVE))
        assertEquals(SignalAction.SMALL_BUY, changed.finalAction)
        assertTrue(changed.allowedToTrade)
    }

    @Test
    fun rejectionVetoesApprovedBuy() {
        val base = decision(action = SignalAction.BUY)
        val changed = CloudAiDecisionPolicy.applyReview(base, review(base, CloudAiVerdict.REJECT))
        assertEquals(SignalAction.WAIT, changed.finalAction)
        assertFalse(changed.allowedToTrade)
    }

    @Test
    fun lowConfidenceLunaEscalates() {
        val payload = OpenAiValidatorPayload(
            verdict = CloudAiVerdict.APPROVE,
            confidence = BigDecimal("0.55"),
            strategy = "TREND",
            regime = "TRENDING_UP",
            riskMultiplier = BigDecimal.ONE,
            reason = "uncertain",
            invalidationConditions = emptyList()
        )
        assertTrue(CloudAiDecisionPolicy.shouldEscalateToSol(payload, BigDecimal("10")))
    }

    @Test
    fun currentLunaCostMathIncludesCachedAndOutputTokens() {
        val cost = OpenAiModelEconomics.costUsd(
            model = OpenAiModelEconomics.LUNA_MODEL,
            inputTokens = 1_000_000,
            cachedInputTokens = 500_000,
            cacheWriteTokens = 0,
            outputTokens = 1_000_000
        )
        // 500k ordinary @ $0.20/M + 500k cached @ $0.02/M + 1M output @ $1.20/M
        assertEquals(BigDecimal("1.310000000000"), cost)
    }

    @Test
    fun solIsMuchMoreExpensiveAndThereforeRare() {
        val luna = OpenAiModelEconomics.costUsd(
            OpenAiModelEconomics.LUNA_MODEL, 1000, 0, 0, 200
        )
        val sol = OpenAiModelEconomics.costUsd(
            OpenAiModelEconomics.SOL_MODEL, 1000, 0, 0, 200
        )
        assertTrue(sol > luna.multiply(BigDecimal("10")))
    }
}
