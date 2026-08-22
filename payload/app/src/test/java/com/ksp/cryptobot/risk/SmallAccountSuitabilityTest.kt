package com.ksp.cryptobot.risk

import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class SmallAccountSuitabilityTest {
    @Test fun feeDragPenalizesTinyRiskBudget() {
        val x=SmallAccountSuitabilityEngine.evaluate(SmallAccountInputs(
            BigDecimal("46"),BigDecimal("0.5"),BigDecimal("5"),BigDecimal("0.30"),BigDecimal("0.05"),
            BigDecimal("0.25"),BigDecimal("30"),BigDecimal("80"),90))
        assertTrue(x.feeRiskRatio > BigDecimal.ONE)
        assertTrue(x.score < 60)
    }
}
