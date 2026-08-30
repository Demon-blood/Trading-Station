
package com.ksp.cryptobot.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class PartialFillMathTest {
    @Test fun cumulativeFillCreatesOnlyIncrementalJournalQuantity() {
        assertEquals(BigDecimal("0.40"), PartialFillMath.incrementalQuantity(BigDecimal("1.00"), BigDecimal("0.60")))
    }

    @Test fun replayedSameCumulativeFillCreatesNoDuplicateQuantity() {
        assertEquals(BigDecimal.ZERO, PartialFillMath.incrementalQuantity(BigDecimal("1.00"), BigDecimal("1.00")))
    }

    @Test fun cumulativeFeeCreatesOnlyIncrementalFee() {
        assertEquals(BigDecimal("0.12"), PartialFillMath.incrementalFee(BigDecimal("0.30"), BigDecimal("0.18")))
    }
}
