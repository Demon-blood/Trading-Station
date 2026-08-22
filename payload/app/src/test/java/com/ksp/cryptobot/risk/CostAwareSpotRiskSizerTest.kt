package com.ksp.cryptobot.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CostAwareSpotRiskSizerTest {
    @Test
    fun halfPercentRiskIncludesFeesAndSlippage() {
        val result = CostAwareSpotRiskSizer.size(
            CostAwareRiskInput(
                BigDecimal("1000"), BigDecimal("0.005"),
                BigDecimal("100"), BigDecimal("95"),
                BigDecimal("0.008"), BigDecimal("0.008"), BigDecimal("0.002"),
                BigDecimal("150"), BigDecimal("800")
            )
        )
        assertTrue(result.allowed)
        assertEquals(0, result.riskBudgetQuote.compareTo(BigDecimal("5.000")))
        assertTrue(result.estimatedWorstCaseLossQuote <= BigDecimal("5.00000001"))
        assertTrue(result.notionalQuote <= BigDecimal("150"))
        assertTrue(result.estimatedRoundTripFeesQuote > BigDecimal.ZERO)
    }

    @Test
    fun notionalCapStillWinsWhenRiskAllowsMore() {
        val result = CostAwareSpotRiskSizer.size(
            CostAwareRiskInput(
                BigDecimal("10000"), BigDecimal("0.005"),
                BigDecimal("100"), BigDecimal("99"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal("50"), BigDecimal("1000")
            )
        )
        assertTrue(result.allowed)
        assertTrue(result.notionalQuote <= BigDecimal("50"))
    }

    @Test
    fun invalidStopFailsClosed() {
        val result = CostAwareSpotRiskSizer.size(
            CostAwareRiskInput(
                BigDecimal("1000"), BigDecimal("0.005"),
                BigDecimal("100"), BigDecimal("101"),
                BigDecimal("0.008"), BigDecimal("0.008"), BigDecimal("0.002"),
                BigDecimal("150"), BigDecimal("800")
            )
        )
        assertFalse(result.allowed)
    }
}
