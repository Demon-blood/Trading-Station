
package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.BotMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineAuthorityPolicyTest {
    @Test fun paperDoesNotRequireDistributedLease() {
        assertTrue(EngineAuthorityPolicy.entryAuthorized(BotMode.PAPER, false))
    }

    @Test fun liveAutoRequiresDistributedLease() {
        assertFalse(EngineAuthorityPolicy.entryAuthorized(BotMode.LIVE_AUTO, false))
        assertTrue(EngineAuthorityPolicy.entryAuthorized(BotMode.LIVE_AUTO, true))
    }

    @Test fun liveConfirmAlsoRequiresDistributedLease() {
        assertFalse(EngineAuthorityPolicy.entryAuthorized(BotMode.LIVE_CONFIRM, false))
    }
}
