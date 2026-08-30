package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.BotMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KrakenDmsSafetyPolicyTest {
    @Test fun paperNeverNeedsDmsConfirmation() {
        assertTrue(
            KrakenDmsSafetyPolicy.entryAllowed(
                BotMode.PAPER,
                "UNKNOWN",
                Long.MAX_VALUE
            )
        )
    }

    @Test fun liveRequiresFreshConfirmedDisarm() {
        assertTrue(
            KrakenDmsSafetyPolicy.entryAllowed(
                BotMode.LIVE_AUTO,
                "DISARMED",
                20_000L
            )
        )
        assertFalse(
            KrakenDmsSafetyPolicy.entryAllowed(
                BotMode.LIVE_AUTO,
                "UNKNOWN",
                20_000L
            )
        )
        assertFalse(
            KrakenDmsSafetyPolicy.entryAllowed(
                BotMode.LIVE_AUTO,
                "DISARMED",
                KrakenDmsSafetyPolicy.CONFIRMATION_MAX_AGE_MS + 1L
            )
        )
    }
}
