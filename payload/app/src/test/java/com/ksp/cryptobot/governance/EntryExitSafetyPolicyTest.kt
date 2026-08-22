package com.ksp.cryptobot.governance

import com.ksp.cryptobot.core.SignalAction
import org.junit.Assert.*
import org.junit.Test

class EntryExitSafetyPolicyTest {
    @Test fun highKillBlocksBuyButAllowsProtectiveSell() {
        assertTrue(EntryExitSafetyPolicy.evaluate(SignalAction.BUY,KillSeverity.HIGH,false).blockNewExposure)
        val sell=EntryExitSafetyPolicy.evaluate(SignalAction.SELL,KillSeverity.CRITICAL,true)
        assertTrue(sell.allowProtectiveExit); assertFalse(sell.blockNewExposure)
    }
}
