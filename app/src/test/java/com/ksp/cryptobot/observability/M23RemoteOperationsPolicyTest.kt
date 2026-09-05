package com.ksp.cryptobot.observability

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class M23RemoteOperationsPolicyTest {
    @Test
    fun unknownRuntimeBlocksNewBuy() {
        assertFalse(M23RemoteOperationsPolicy.entryAllowed(false, false, false).first)
    }

    @Test
    fun pauseAndKillSwitchBlockNewBuy() {
        assertFalse(M23RemoteOperationsPolicy.entryAllowed(true, true, false).first)
        assertFalse(M23RemoteOperationsPolicy.entryAllowed(true, false, true).first)
    }

    @Test
    fun cleanInitializedRuntimeCanPassOnlyItsOwnGate() {
        assertTrue(M23RemoteOperationsPolicy.entryAllowed(true, false, false).first)
    }

    @Test
    fun arbitraryTradingAuthorityCommandsAreRejected() {
        listOf(
            "execute", "start", "resume", "mode", "set", "buy", "sell",
            "force_buy", "force_sell", "ignore_risk", "ignore_security", "ignore_authority"
        ).forEach { command ->
            assertFalse("$command must not receive M23 remote authority", M23RemoteOperationsPolicy.commandAllowed(command))
        }
    }

    @Test
    fun safetyOrientedRemoteCommandsAreAllowed() {
        listOf("health", "diagnostics", "pause_entries", "kill_switch", "stop", "reconcile", "refresh_market")
            .forEach { assertTrue(M23RemoteOperationsPolicy.commandAllowed(it)) }
    }

    @Test
    fun pinComparisonIsExact() {
        assertTrue(M23RemoteOperationsPolicy.constantTimeEquals("123456", "123456"))
        assertFalse(M23RemoteOperationsPolicy.constantTimeEquals("123456", "123457"))
    }
}
