package com.ksp.cryptobot.execution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineAuthorityFencingPolicyTest {
    @Test fun acquisitionRequiresV2FenceAndPositiveServerRemainingTime() {
        assertTrue(
            EngineAuthorityFencePolicy.acquisitionValid(
                acquired = true,
                holderMatches = true,
                schemaVersion = 2,
                fencingToken = 7L,
                remainingMs = 75_000L
            )
        )
        assertFalse(
            EngineAuthorityFencePolicy.acquisitionValid(
                acquired = true,
                holderMatches = true,
                schemaVersion = 1,
                fencingToken = 7L,
                remainingMs = 75_000L
            )
        )
        assertFalse(
            EngineAuthorityFencePolicy.acquisitionValid(
                acquired = true,
                holderMatches = true,
                schemaVersion = 2,
                fencingToken = 0L,
                remainingMs = 75_000L
            )
        )
    }

    @Test fun heartbeatRejectsOldFencingToken() {
        assertFalse(
            EngineAuthorityFencePolicy.heartbeatValid(
                renewed = true,
                holderMatches = true,
                schemaVersion = 2,
                expectedToken = 8L,
                responseToken = 9L,
                remainingMs = 75_000L
            )
        )
    }

    @Test fun localMonotonicDeadlineExpiresAuthority() {
        assertTrue(
            EngineAuthorityFencePolicy.runtimeLeaseValid(
                schemaVersion = 2,
                fencingToken = 5L,
                localDeadlineElapsedMs = 10_000L,
                nowElapsedMs = 9_999L
            )
        )
        assertFalse(
            EngineAuthorityFencePolicy.runtimeLeaseValid(
                schemaVersion = 2,
                fencingToken = 5L,
                localDeadlineElapsedMs = 10_000L,
                nowElapsedMs = 10_000L
            )
        )
    }
}
