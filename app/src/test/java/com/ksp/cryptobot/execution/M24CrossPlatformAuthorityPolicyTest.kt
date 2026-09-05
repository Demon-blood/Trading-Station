package com.ksp.cryptobot.execution

import org.junit.Assert.*
import org.junit.Test

class M24CrossPlatformAuthorityPolicyTest {
    @Test
    fun androidAndWindowsAreTheOnlyLiveAuthorityPlatforms() {
        assertTrue(M24CrossPlatformAuthorityPolicy.supportedPlatform("ANDROID"))
        assertTrue(M24CrossPlatformAuthorityPolicy.supportedPlatform("windows"))
        assertFalse(M24CrossPlatformAuthorityPolicy.supportedPlatform("UNKNOWN"))
    }

    @Test
    fun responseLatencyCanNeverExtendServerLease() {
        val remaining = M24CrossPlatformAuthorityPolicy.conservativeRemainingMs(
            serverRemainingMs = 75_000L,
            roundTripMs = 4_000L
        )
        assertEquals(69_500L, remaining)
        assertEquals(
            0L,
            M24CrossPlatformAuthorityPolicy.conservativeRemainingMs(1_000L, 2_000L)
        )
    }

    @Test
    fun authoritativeSubmissionRequiresExactOwnerPlatformAndFence() {
        assertTrue(
            M24CrossPlatformAuthorityPolicy.remoteSubmissionValid(
                remoteReachable = true,
                owned = true,
                holderMatches = true,
                holderPlatform = "ANDROID",
                expectedPlatform = M24AuthorityPlatform.ANDROID,
                schemaVersion = 2,
                expectedFence = 42L,
                responseFence = 42L,
                conservativeRemainingMs = 20_000L
            )
        )
        assertFalse(
            M24CrossPlatformAuthorityPolicy.remoteSubmissionValid(
                true, true, true, "WINDOWS", M24AuthorityPlatform.ANDROID,
                2, 42L, 42L, 20_000L
            )
        )
        assertFalse(
            M24CrossPlatformAuthorityPolicy.remoteSubmissionValid(
                true, true, true, "ANDROID", M24AuthorityPlatform.ANDROID,
                2, 42L, 43L, 20_000L
            )
        )
    }

    @Test
    fun partitionFailsClosed() {
        assertFalse(
            M24CrossPlatformAuthorityPolicy.remoteSubmissionValid(
                remoteReachable = false,
                owned = true,
                holderMatches = true,
                holderPlatform = "ANDROID",
                expectedPlatform = M24AuthorityPlatform.ANDROID,
                schemaVersion = 2,
                expectedFence = 9L,
                responseFence = 9L,
                conservativeRemainingMs = 40_000L
            )
        )
    }

    @Test
    fun transferMustAdvanceFenceAndMatchTarget() {
        assertTrue(
            M24CrossPlatformAuthorityPolicy.transferAccepted(
                transferred = true,
                oldFence = 7L,
                newFence = 8L,
                targetEngineId = "windows-engine",
                responseHolderEngineId = "windows-engine",
                targetPlatform = M24AuthorityPlatform.WINDOWS,
                responseHolderPlatform = "WINDOWS",
                conservativeRemainingMs = 30_000L
            )
        )
        assertFalse(
            M24CrossPlatformAuthorityPolicy.transferAccepted(
                true, 7L, 7L, "windows-engine", "windows-engine",
                M24AuthorityPlatform.WINDOWS, "WINDOWS", 30_000L
            )
        )
    }
}
