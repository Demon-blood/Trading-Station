package com.ksp.cryptobot.exchange

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KrakenApiKeySecurityRuntimeM22Test {
    private val apiKey = "test-api-key"
    private val fingerprint = KrakenApiKeySecurityRuntime.fingerprint(apiKey)

    private fun safeAssessment(checkedAt: Long) = KrakenApiKeySecurityAssessment(
        safeForLive = true,
        keyFingerprint = fingerprint,
        keyName = "test",
        permissions = KrakenApiKeySecurityPolicy.REQUIRED_LIVE_PERMISSIONS,
        dangerousPermissions = emptySet(),
        missingRequiredPermissions = emptySet(),
        extraPermissions = emptySet(),
        expired = false,
        ipRestricted = false,
        checkedAtEpochMs = checkedAt,
        reason = "SAFE"
    )

    @Test fun unknownRuntimeBlocksNewBuy() {
        KrakenApiKeySecurityRuntime.clearForTests()
        assertFalse(KrakenApiKeySecurityRuntime.gateForNewBuy(apiKey, 1_000L).first)
    }

    @Test fun freshSafeAssessmentAllowsPrerequisite() {
        KrakenApiKeySecurityRuntime.publish(safeAssessment(1_000L))
        assertTrue(KrakenApiKeySecurityRuntime.gateForNewBuy(apiKey, 2_000L).first)
    }

    @Test fun staleAssessmentBlocksNewBuy() {
        KrakenApiKeySecurityRuntime.publish(safeAssessment(1_000L))
        assertFalse(
            KrakenApiKeySecurityRuntime.gateForNewBuy(
                apiKey,
                1_000L + KrakenApiKeySecurityRuntime.MAX_ASSESSMENT_AGE_MS + 1L
            ).first
        )
    }

    @Test fun changedApiKeyBlocksUntilReinspection() {
        KrakenApiKeySecurityRuntime.publish(safeAssessment(1_000L))
        assertFalse(KrakenApiKeySecurityRuntime.gateForNewBuy("different-key", 2_000L).first)
    }

    @Test fun unsafeAssessmentBlocksNewBuy() {
        KrakenApiKeySecurityRuntime.publish(
            safeAssessment(1_000L).copy(
                safeForLive = false,
                dangerousPermissions = setOf("withdraw-funds"),
                reason = "BLOCKED"
            )
        )
        assertFalse(KrakenApiKeySecurityRuntime.gateForNewBuy(apiKey, 2_000L).first)
    }
}
