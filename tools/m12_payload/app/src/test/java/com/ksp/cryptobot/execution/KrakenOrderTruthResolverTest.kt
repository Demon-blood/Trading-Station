
package com.ksp.cryptobot.execution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KrakenOrderTruthResolverTest {
    @Test fun authoritativeNotFoundStaysQuarantinedDuringConsistencyGrace() {
        val start = 1_000_000L
        assertFalse(KrakenOrderTruthResolver.canClearAuthoritativeNotFound(start, start + 9L * 60L * 1000L))
    }

    @Test fun authoritativeNotFoundCanClearAfterTenMinuteGrace() {
        val start = 1_000_000L
        assertTrue(KrakenOrderTruthResolver.canClearAuthoritativeNotFound(start, start + 10L * 60L * 1000L))
    }
}
