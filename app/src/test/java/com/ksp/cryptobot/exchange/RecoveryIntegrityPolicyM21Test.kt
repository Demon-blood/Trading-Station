package com.ksp.cryptobot.exchange

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryIntegrityPolicyM21Test {
    private fun safe() = RecoveryIntegritySnapshot(
        authoritativeReconciliationComplete = true,
        networkUsable = true,
        privateExecutionContinuous = true,
        recentRestTruth = true,
        durableSubmissionAmbiguities = 0,
        ambiguousOrderMutations = 0,
        databaseHealthy = true,
        wallClockSane = true,
        distributedAuthorityHeld = true,
        unprotectedPositions = 0
    )

    @Test fun baselineSafeStateAllowsPrerequisite() {
        assertTrue(RecoveryIntegrityPolicy.canSubmitNewBuy(safe()).first)
    }

    @Test fun unresolvedAddOrderBlocksBuy() {
        assertFalse(
            RecoveryIntegrityPolicy.canSubmitNewBuy(
                safe().copy(durableSubmissionAmbiguities = 1)
            ).first
        )
    }

    @Test fun databaseFailureBlocksBuy() {
        assertFalse(
            RecoveryIntegrityPolicy.canSubmitNewBuy(
                safe().copy(databaseHealthy = false)
            ).first
        )
    }

    @Test fun unprotectedPositionBlocksNewBuy() {
        assertFalse(
            RecoveryIntegrityPolicy.canSubmitNewBuy(
                safe().copy(unprotectedPositions = 1)
            ).first
        )
    }

    @Test fun recoveryCanStillSellButNeverOversell() {
        assertEquals(
            BigDecimal("1.25"),
            RecoveryIntegrityPolicy.protectiveSellQuantity(
                BigDecimal("1.25"),
                BigDecimal("2.00")
            )
        )
    }

    @Test fun materialClockRegressionBlocksBuy() {
        assertFalse(RecoveryClockPolicy.sane(100_000L, 90_000L))
        assertTrue(RecoveryClockPolicy.sane(100_000L, 96_000L))
    }
}
