package com.ksp.cryptobot.governance

import com.ksp.cryptobot.core.SignalAction
import org.junit.Assert.assertEquals
import org.junit.Test

class LearningMonotonicPolicyM19Test {
    @Test fun waitCanNeverBecomeEntry() {
        assertEquals(SignalAction.WAIT, LearningMonotonicPolicy.action(SignalAction.WAIT, 100, 72))
    }

    @Test fun watchCanNeverBecomeEntry() {
        assertEquals(SignalAction.WATCH, LearningMonotonicPolicy.action(SignalAction.WATCH, 100, 72))
    }

    @Test fun avoidCanNeverBecomeEntry() {
        assertEquals(SignalAction.AVOID, LearningMonotonicPolicy.action(SignalAction.AVOID, 100, 72))
    }

    @Test fun strongAvoidCanNeverBecomeEntry() {
        assertEquals(
            SignalAction.STRONG_AVOID,
            LearningMonotonicPolicy.action(SignalAction.STRONG_AVOID, 100, 72)
        )
    }

    @Test fun smallBuyCanNeverUpgradeToFullBuy() {
        assertEquals(SignalAction.SMALL_BUY, LearningMonotonicPolicy.action(SignalAction.SMALL_BUY, 100, 72))
    }

    @Test fun buyCanBeReducedWhenLearnedEvidenceWeakens() {
        assertEquals(SignalAction.SMALL_BUY, LearningMonotonicPolicy.action(SignalAction.BUY, 66, 72))
        assertEquals(SignalAction.WAIT, LearningMonotonicPolicy.action(SignalAction.BUY, 50, 72))
    }

    @Test fun sellIsPreserved() {
        assertEquals(SignalAction.SELL, LearningMonotonicPolicy.action(SignalAction.SELL, 0, 72))
    }
}
