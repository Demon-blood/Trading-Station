package com.ksp.cryptobot.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class AiValueAttributionEngineTest {
    @Test
    fun rejectedLosingTradeCountsAsAvoidedLossAfterAiCost() {
        val out = AiValueAttributionEngine.calculatePathOutcome(
            deterministicNotionalQuote = BigDecimal("25"),
            netReturnRate = BigDecimal("-0.05"),
            lunaVerdict = CloudAiVerdict.REJECT,
            lunaRiskMultiplier = BigDecimal.ZERO,
            finalVerdict = CloudAiVerdict.REJECT,
            finalRiskMultiplier = BigDecimal.ZERO,
            lunaCostQuote = BigDecimal("0.01"),
            solCostQuote = BigDecimal.ZERO
        )
        assertEquals(BigDecimal("-1.25"), out.deterministicNetPnlQuote)
        assertTrue(out.aiValueAddedQuote > BigDecimal.ZERO)
        assertTrue(out.avoidedLossQuote > BigDecimal.ZERO)
        assertEquals(BigDecimal.ZERO, out.missedProfitQuote)
    }

    @Test
    fun rejectedWinnerCountsAsMissedProfit() {
        val out = AiValueAttributionEngine.calculatePathOutcome(
            deterministicNotionalQuote = BigDecimal("25"),
            netReturnRate = BigDecimal("0.04"),
            lunaVerdict = CloudAiVerdict.REJECT,
            lunaRiskMultiplier = BigDecimal.ZERO,
            finalVerdict = CloudAiVerdict.REJECT,
            finalRiskMultiplier = BigDecimal.ZERO,
            lunaCostQuote = BigDecimal("0.01"),
            solCostQuote = BigDecimal.ZERO
        )
        assertTrue(out.aiValueAddedQuote < BigDecimal.ZERO)
        assertTrue(out.missedProfitQuote > BigDecimal.ZERO)
        assertEquals(BigDecimal.ZERO, out.avoidedLossQuote)
    }

    @Test
    fun fullApprovalWithNoSizeChangeIsWorthNegativeApiCostOnly() {
        val out = AiValueAttributionEngine.calculatePathOutcome(
            deterministicNotionalQuote = BigDecimal("25"),
            netReturnRate = BigDecimal("0.03"),
            lunaVerdict = CloudAiVerdict.APPROVE,
            lunaRiskMultiplier = BigDecimal.ONE,
            finalVerdict = CloudAiVerdict.APPROVE,
            finalRiskMultiplier = BigDecimal.ONE,
            lunaCostQuote = BigDecimal("0.01"),
            solCostQuote = BigDecimal.ZERO
        )
        assertEquals(BigDecimal("-0.01"), out.aiValueAddedQuote)
    }

    @Test
    fun solIncrementalValueIsMeasuredAgainstLunaPath() {
        val out = AiValueAttributionEngine.calculatePathOutcome(
            deterministicNotionalQuote = BigDecimal("25"),
            netReturnRate = BigDecimal("-0.04"),
            lunaVerdict = CloudAiVerdict.APPROVE,
            lunaRiskMultiplier = BigDecimal.ONE,
            finalVerdict = CloudAiVerdict.REJECT,
            finalRiskMultiplier = BigDecimal.ZERO,
            lunaCostQuote = BigDecimal("0.01"),
            solCostQuote = BigDecimal("0.02")
        )
        assertTrue(out.solIncrementalValueQuote > BigDecimal.ZERO)
        assertTrue(out.aiValueAddedQuote > BigDecimal.ZERO)
    }

    @Test
    fun rejectAlwaysForcesZeroExposureMultiplier() {
        assertEquals(
            BigDecimal.ZERO,
            AiValueAttributionEngine.effectiveMultiplier(
                CloudAiVerdict.REJECT,
                BigDecimal.ONE
            )
        )
    }
}
