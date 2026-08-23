package com.ksp.cryptobot.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NewsProviderHealthRegistryTest {
    @Before
    fun reset() = NewsProviderHealthRegistry.resetForTests()

    @Test
    fun http429CreatesCooldownAndSuppressesImmediateRetry() {
        val now = 1_800_000_000_000L
        assertTrue(NewsProviderHealthRegistry.shouldAttempt("GNews", now))
        NewsProviderHealthRegistry.recordAttempt("GNews", now)
        NewsProviderHealthRegistry.recordFailure("GNews", IllegalStateException("GNews HTTP 429: quota"), now)
        val health = requireNotNull(NewsProviderHealthRegistry.healthFor("GNews", now))
        assertEquals("COOLDOWN", health.status)
        assertFalse(NewsProviderHealthRegistry.shouldAttempt("GNews", now + 1_000L))
        assertTrue(health.cooldownUntilEpochMs >= now + 30L * 60L * 1_000L)
    }

    @Test
    fun successClearsFailureState() {
        val now = 1_800_000_000_000L
        NewsProviderHealthRegistry.recordFailure("GDELT", IllegalStateException("GDELT HTTP 503"), now)
        NewsProviderHealthRegistry.recordSuccess("GDELT", 7, now + 20L * 60L * 1_000L)
        val health = requireNotNull(NewsProviderHealthRegistry.healthFor("GDELT", now + 20L * 60L * 1_000L))
        assertEquals("HEALTHY", health.status)
        assertEquals(0, health.consecutiveFailures)
        assertEquals(7, health.lastArticleCount)
        assertTrue(NewsProviderHealthRegistry.shouldAttempt("GDELT", now + 20L * 60L * 1_000L))
    }
}
