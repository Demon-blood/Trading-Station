package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.SignalAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionGuardSafeModeTest {
    @Test
    fun safeModeBlocksNewLiveEntriesButNotSellExits() {
        assertTrue(productionSafeModeBlocks(BotMode.LIVE_AUTO, SignalAction.BUY, true))
        assertTrue(productionSafeModeBlocks(BotMode.LIVE_AUTO, SignalAction.SMALL_BUY, true))
        assertFalse(productionSafeModeBlocks(BotMode.LIVE_AUTO, SignalAction.SELL, true))
        assertFalse(productionSafeModeBlocks(BotMode.PAPER, SignalAction.BUY, true))
        assertTrue(isNewEntryAction(SignalAction.BUY))
        assertFalse(isNewEntryAction(SignalAction.SELL))
    }
}
