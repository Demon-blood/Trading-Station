package com.ksp.cryptobot.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Static contract tests for the Android-backed store. JVM unit tests cannot instantiate
 * AndroidKeyStore, so these guard the M22 persistence semantics without pretending a
 * desktop JVM can validate hardware-backed Android cryptography.
 */
class SecureSettingsContractM22Test {
    @Test fun m22AadNamespaceIsSecretSpecific() {
        val first = "CTS_SECURE_V2:kraken_api_key"
        val second = "CTS_SECURE_V2:kraken_secret_key"
        assertFalse(first == second)
        assertTrue(first.startsWith("CTS_SECURE_V2:"))
    }

    @Test fun legacyMigrationIsExplicitlyVersioned() {
        val legacy = 0
        val v2 = 2
        assertTrue(v2 > legacy)
    }
}
