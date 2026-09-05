package com.ksp.cryptobot.execution

import org.junit.Assert.*
import org.junit.Test

class M24CrossPlatformAuthorityScenarioTest {
    private data class Actor(val id: String, val platform: M24AuthorityPlatform)
    private data class Lease(var owner: Actor? = null, var fence: Long = 0L, var expiresAt: Long = 0L)

    private class LeaseModel {
        val lease = Lease()

        fun acquire(actor: Actor, now: Long, ttl: Long = 75_000L): Pair<Boolean, Long> {
            val current = lease.owner
            if (current == actor && lease.expiresAt > now) {
                lease.expiresAt = now + ttl
                return true to lease.fence
            }
            if (lease.expiresAt > now && current != null) return false to lease.fence
            lease.fence += 1L
            lease.owner = actor
            lease.expiresAt = now + ttl
            return true to lease.fence
        }

        fun release(actor: Actor, fence: Long, now: Long): Boolean {
            if (lease.owner != actor || lease.fence != fence || lease.expiresAt <= now) return false
            // Preserve the row/fence; the next acquisition creates a strictly newer epoch.
            lease.expiresAt = now
            return true
        }

        fun transfer(from: Actor, oldFence: Long, target: Actor, now: Long, ttl: Long = 75_000L): Long? {
            if (lease.owner != from || lease.fence != oldFence || lease.expiresAt <= now) return null
            lease.fence += 1L
            lease.owner = target
            lease.expiresAt = now + ttl
            return lease.fence
        }

        fun owns(actor: Actor, fence: Long, now: Long): Boolean =
            lease.owner == actor && lease.fence == fence && lease.expiresAt > now
    }

    private val android = Actor("android-engine", M24AuthorityPlatform.ANDROID)
    private val windows = Actor("windows-engine", M24AuthorityPlatform.WINDOWS)

    @Test
    fun windowsVsAndroidContentionHasOneWinner() {
        val model = LeaseModel()
        val androidResult = model.acquire(android, 1_000L)
        val windowsResult = model.acquire(windows, 1_000L)
        assertTrue(androidResult.first)
        assertFalse(windowsResult.first)
        assertTrue(model.owns(android, androidResult.second, 1_001L))
        assertFalse(model.owns(windows, windowsResult.second, 1_001L))
    }

    @Test
    fun simultaneousLaunchStillProducesExactlyOneOwner() {
        val a = LeaseModel()
        val results = listOf(a.acquire(android, 5_000L), a.acquire(windows, 5_000L))
        assertEquals(1, results.count { it.first })
    }

    @Test
    fun authorityTransferFencesOldAndroidImmediately() {
        val model = LeaseModel()
        val oldFence = model.acquire(android, 10_000L).second
        val newFence = model.transfer(android, oldFence, windows, 11_000L)!!
        assertTrue(newFence > oldFence)
        assertFalse(model.owns(android, oldFence, 11_001L))
        assertTrue(model.owns(windows, newFence, 11_001L))
    }

    @Test
    fun oldProcessComingBackOnlineCannotUseStaleFence() {
        val model = LeaseModel()
        val oldFence = model.acquire(android, 20_000L).second
        val newFence = model.transfer(android, oldFence, windows, 21_000L)!!
        assertTrue(M24CrossPlatformAuthorityPolicy.staleFenceRejected(oldFence, newFence))
        assertFalse(model.owns(android, oldFence, 21_001L))
    }

    @Test
    fun staleOwnerAfterExpiryCannotMutateNewEpoch() {
        val model = LeaseModel()
        val oldFence = model.acquire(android, 30_000L, ttl = 100L).second
        val newFence = model.acquire(windows, 30_101L).second
        assertTrue(newFence > oldFence)
        assertFalse(model.release(android, oldFence, 30_102L))
        assertTrue(model.owns(windows, newFence, 30_102L))
    }

    @Test
    fun releasePreservesFenceHistoryAcrossCrossPlatformFailover() {
        val model = LeaseModel()
        val oldFence = model.acquire(android, 40_000L).second
        assertTrue(model.release(android, oldFence, 40_010L))
        val newFence = model.acquire(windows, 40_011L).second
        assertTrue(newFence > oldFence)
        assertTrue(model.owns(windows, newFence, 40_012L))
    }

    @Test
    fun networkPartitionIsRejectedBySubmissionPolicyEvenBeforeServerExpiry() {
        assertFalse(
            M24CrossPlatformAuthorityPolicy.remoteSubmissionValid(
                remoteReachable = false,
                owned = true,
                holderMatches = true,
                holderPlatform = "WINDOWS",
                expectedPlatform = M24AuthorityPlatform.WINDOWS,
                schemaVersion = 2,
                expectedFence = 100L,
                responseFence = 100L,
                conservativeRemainingMs = 60_000L
            )
        )
    }

    @Test
    fun crossPlatformFailoverAfterExpiryUsesNewFence() {
        val model = LeaseModel()
        val androidFence = model.acquire(android, 50_000L, ttl = 1_000L).second
        assertFalse(model.acquire(windows, 50_500L).first)
        val windowsResult = model.acquire(windows, 51_001L)
        assertTrue(windowsResult.first)
        assertTrue(windowsResult.second > androidFence)
        assertFalse(model.owns(android, androidFence, 51_002L))
    }
}
