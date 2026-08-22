package com.ksp.cryptobot.learning

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class StrategyPromotionGateTest {
    @Test fun backtestAloneCannotPromoteToLive() {
        val e=PromotionEvidence("X",100,BigDecimal("0.2"),BigDecimal("5"),BigDecimal("20"),BigDecimal("40"),
            walkForwardPassed=true,paperPassed=false,shadowLivePassed=false,paperLiveIntentParityPassed=true,unresolvedExecutionErrors=0)
        val d=StrategyPromotionGate.evaluate(e)
        assertFalse(d.eligible); assertTrue(d.blockers.any{it.contains("paper")})
    }
}
