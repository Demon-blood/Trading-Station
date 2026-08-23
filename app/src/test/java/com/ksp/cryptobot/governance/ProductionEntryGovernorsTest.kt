package com.ksp.cryptobot.governance

import com.ksp.cryptobot.core.SignalAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductionEntryGovernorsTest {
    @Test
    fun killRiskAndSafeModeAreEntryOnly() {
        assertTrue(entryOnlyGovernorsBlock(SignalAction.BUY, killAllowed = false, riskBlocked = false, liveSafeBlocked = false))
        assertTrue(entryOnlyGovernorsBlock(SignalAction.SMALL_BUY, killAllowed = true, riskBlocked = true, liveSafeBlocked = false))
        assertTrue(entryOnlyGovernorsBlock(SignalAction.BUY, killAllowed = true, riskBlocked = false, liveSafeBlocked = true))
        assertFalse(entryOnlyGovernorsBlock(SignalAction.SELL, killAllowed = false, riskBlocked = true, liveSafeBlocked = true))
    }
}
