package com.ksp.cryptobot.lifecycle

import com.ksp.cryptobot.core.AiDecision
import com.ksp.cryptobot.core.SignalAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleExitSemanticsTest {
    private fun decision(action: SignalAction, allowed: Boolean = true) = AiDecision(
        symbol = "KASEUR",
        finalAction = action,
        finalScore = 60,
        confidencePercent = 60,
        technicalScore = 60,
        newsScore = 0,
        memoryScore = 0,
        allowedToTrade = allowed,
        explanation = "test"
    )

    @Test fun avoidMeansDoNotEnterNotSell() {
        assertFalse(isExplicitLifecycleSell(decision(SignalAction.AVOID)))
        assertFalse(isExplicitLifecycleSell(decision(SignalAction.STRONG_AVOID)))
        assertFalse(isExplicitLifecycleSell(decision(SignalAction.WAIT)))
    }

    @Test fun onlyExplicitAllowedSellIsSoftSellSignal() {
        assertTrue(isExplicitLifecycleSell(decision(SignalAction.SELL, true)))
        assertFalse(isExplicitLifecycleSell(decision(SignalAction.SELL, false)))
    }

    @Test fun newEntryDefersSoftSignalExit() {
        assertTrue(shouldDeferSoftLifecycleExitForChurn(true, false, true, false, 0, 15))
        assertTrue(shouldDeferSoftLifecycleExitForChurn(true, false, false, false, 2, 15))
        assertFalse(shouldDeferSoftLifecycleExitForChurn(true, false, false, false, 16, 15))
    }

    @Test fun protectiveExitIsNotDeferredBySoftChurnGuard() {
        assertFalse(shouldDeferSoftLifecycleExitForChurn(true, true, true, false, 0, 15))
    }

    @Test fun alreadyExitedSymbolCannotReceiveSecondSoftExitThisScan() {
        assertTrue(shouldDeferSoftLifecycleExitForChurn(true, false, false, true, 60, 15))
    }
}
