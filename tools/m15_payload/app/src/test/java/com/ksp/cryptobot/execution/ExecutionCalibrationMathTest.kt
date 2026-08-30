package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.OrderSide
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class ExecutionCalibrationMathTest {
    @Test fun arithmeticMeanUpdatesFromRealFillOutcome() {
        assertEquals(50.0, ExecutionCalibrationMath.nextMean(2, 40.0, 70.0), 0.000001)
    }

    @Test fun buyPositiveSlippageMeansWorseExecution() {
        assertEquals(
            10.0,
            ExecutionCalibrationMath.slippageBps(
                OrderSide.BUY,
                BigDecimal("100.00"),
                BigDecimal("100.10")
            ),
            0.000001
        )
    }

    @Test fun makerPriceImprovementIsNegativeSlippage() {
        assertEquals(
            -10.0,
            ExecutionCalibrationMath.slippageBps(
                OrderSide.BUY,
                BigDecimal("100.00"),
                BigDecimal("99.90")
            ),
            0.000001
        )
    }
}
