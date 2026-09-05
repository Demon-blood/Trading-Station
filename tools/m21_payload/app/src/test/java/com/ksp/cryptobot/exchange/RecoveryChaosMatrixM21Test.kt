package com.ksp.cryptobot.exchange

import java.math.BigDecimal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryChaosMatrixM21Test {
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

    private fun blocked(snapshot: RecoveryIntegritySnapshot) =
        !RecoveryIntegrityPolicy.canSubmitNewBuy(snapshot).first

    @Test fun killAfterAddOrderBeforeAckBlocksDuplicateBuy() {
        assertTrue(blocked(safe().copy(
            authoritativeReconciliationComplete = false,
            durableSubmissionAmbiguities = 1
        )))
    }

    @Test fun killAfterFillBeforeDbWriteBlocksUntilReconcile() {
        assertTrue(blocked(safe().copy(
            authoritativeReconciliationComplete = false,
            databaseHealthy = false
        )))
    }

    @Test fun wifiLossBlocksEntries() {
        assertTrue(blocked(safe().copy(
            networkUsable = false,
            authoritativeReconciliationComplete = false
        )))
    }

    @Test fun wsAndRestTruthFailureBlocksEntries() {
        assertTrue(blocked(safe().copy(
            privateExecutionContinuous = false,
            recentRestTruth = false
        )))
    }

    @Test fun amendResponseLostBlocksAdditionalMutationPath() {
        assertTrue(blocked(safe().copy(ambiguousOrderMutations = 1)))
    }

    @Test fun cancelAckLostBlocksReplacementUntilTruth() {
        assertTrue(blocked(safe().copy(ambiguousOrderMutations = 1)))
    }

    @Test fun sequenceGapBlocksUntilSnapshotAndReconcile() {
        assertTrue(blocked(safe().copy(
            privateExecutionContinuous = false,
            recentRestTruth = false,
            authoritativeReconciliationComplete = false
        )))
    }

    @Test fun deviceRebootStartsUnknown() {
        assertTrue(blocked(safe().copy(authoritativeReconciliationComplete = false)))
    }

    @Test fun distributedLeaseFailureBlocksBuy() {
        assertTrue(blocked(safe().copy(distributedAuthorityHeld = false)))
    }

    @Test fun protectiveStopAckLostBlocksNewRiskButCanReduceExposure() {
        val unsafe = safe().copy(unprotectedPositions = 1)
        assertTrue(blocked(unsafe))
        assertTrue(
            RecoveryIntegrityPolicy.protectiveSellQuantity(
                BigDecimal("0.50"),
                BigDecimal("0.50")
            ) > BigDecimal.ZERO
        )
    }

    @Test fun lateFillAfterCancelCanBeAppliedExactlyOnce() {
        val filter = IdempotentExecutionIdFilter()
        assertTrue(filter.accept("late-fill-exec"))
        assertFalse(filter.accept("late-fill-exec"))
    }

    @Test fun duplicatePrivateFillCannotBeAppliedTwice() {
        val filter = IdempotentExecutionIdFilter()
        assertTrue(filter.accept("dup-exec"))
        assertFalse(filter.accept("dup-exec"))
    }

    @Test fun dozeLikeNetworkSuspensionBlocksNewBuy() {
        assertTrue(blocked(safe().copy(
            networkUsable = false,
            authoritativeReconciliationComplete = false
        )))
    }

    @Test fun clockJumpBackwardBlocksAgeDependentTrading() {
        assertTrue(blocked(safe().copy(wallClockSane = false)))
    }
}
