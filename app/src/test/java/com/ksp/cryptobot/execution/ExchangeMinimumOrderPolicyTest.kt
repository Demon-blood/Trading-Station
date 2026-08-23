package com.ksp.cryptobot.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ExchangeMinimumOrderPolicyTest {
    @Test
    fun nearEurThreePointSixSixIsRaisedToFourWhenSafe() {
        val result = ExchangeMinimumOrderPolicy.evaluate(
            targetNotional = BigDecimal("9.15"),
            price = BigDecimal("2.50"),
            quantityDecimals = 8,
            minOrderSize = BigDecimal("4"),
            minOrderCost = BigDecimal.ZERO,
            hardCapNotional = BigDecimal("25"),
            maxSpendableNotional = BigDecimal("30"),
            allowUpsizeToMinimum = true
        )
        assertTrue(result.allowed)
        assertTrue(result.adjustedToMinimum)
        assertEquals(0, result.quantity.compareTo(BigDecimal("4.00000000")))
    }

    @Test
    fun exchangeMinimumDoesNotOverrideHardRiskCap() {
        val result = ExchangeMinimumOrderPolicy.evaluate(
            BigDecimal("9.15"), BigDecimal("2.50"), 8, BigDecimal("4"), BigDecimal.ZERO,
            BigDecimal("9.50"), BigDecimal("30"), true
        )
        assertFalse(result.allowed)
        assertTrue(result.reason.contains("hard position/order cap"))
    }

    @Test
    fun exchangeMinimumDoesNotSpendReservedCash() {
        val result = ExchangeMinimumOrderPolicy.evaluate(
            BigDecimal("9.15"), BigDecimal("2.50"), 8, BigDecimal("4"), BigDecimal.ZERO,
            BigDecimal("25"), BigDecimal("9.80"), true
        )
        assertFalse(result.allowed)
        assertTrue(result.reason.contains("cannot be funded"))
    }

    @Test
    fun postRiskSizingBelowMinimumIsSkippedNotUpsized() {
        val result = ExchangeMinimumOrderPolicy.evaluate(
            BigDecimal("9.15"), BigDecimal("2.50"), 8, BigDecimal("4"), BigDecimal.ZERO,
            BigDecimal("25"), BigDecimal("30"), false
        )
        assertFalse(result.allowed)
        assertTrue(result.reason.contains("Risk-sized BUY is below"))
    }

    @Test
    fun costMinimumIsAlsoRespected() {
        val result = ExchangeMinimumOrderPolicy.evaluate(
            BigDecimal("9.00"), BigDecimal("2.00"), 4, BigDecimal("1"), BigDecimal("10"),
            BigDecimal("25"), BigDecimal("25"), true
        )
        assertTrue(result.allowed)
        assertTrue(result.adjustedToMinimum)
        assertTrue(result.targetNotional >= BigDecimal("10.05"))
    }
}
