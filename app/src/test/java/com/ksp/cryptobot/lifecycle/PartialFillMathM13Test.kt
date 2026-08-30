package com.ksp.cryptobot.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class PartialFillMathM13Test {
    @Test fun cumulativeAverageProducesExactNewFillPrice() {
        val price = PartialFillMath.incrementalAveragePrice(
            exchangeCumulative = BigDecimal("0.70"),
            exchangeAveragePrice = BigDecimal("101.00"),
            alreadyRecorded = BigDecimal("0.40"),
            alreadyRecordedCost = BigDecimal("40.00"),
            fallbackPrice = BigDecimal("101.00")
        )
        assertEquals(0, price.compareTo(BigDecimal("102.333333333333")))
    }

    @Test fun noQuantityDeltaFallsBackWithoutDivision() {
        val price = PartialFillMath.incrementalAveragePrice(
            exchangeCumulative = BigDecimal("1.00"),
            exchangeAveragePrice = BigDecimal("100"),
            alreadyRecorded = BigDecimal("1.00"),
            alreadyRecordedCost = BigDecimal("100"),
            fallbackPrice = BigDecimal("99")
        )
        assertEquals(0, price.compareTo(BigDecimal("100")))
    }
}
