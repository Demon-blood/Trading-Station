package com.ksp.cryptobot.release

import org.junit.Assert.*
import org.junit.Test

class M25ReleaseReadinessTest {

    private fun codePass() = M25ReleaseEvidence(
        ciRegression = M25GateState.PASS,
        dependencySecurityScan = M25GateState.PASS,
        canonicalIdentitySchema12 = M25GateState.PASS,
        restartRecovery = M25GateState.PASS,
        networkRecovery = M25GateState.PASS,
        partialFillLifecycle = M25GateState.PASS,
        diagnosticsBundle = M25GateState.PASS
    )

    private fun preLivePass() = codePass().copy(
        releaseSigned = M25GateState.PASS,
        installUpgradeVerified = M25GateState.PASS,
        cloudShareProductionVerified = M25GateState.PASS,
        krakenPermissionsVerified = M25GateState.PASS,
        distributedAuthorityVerified = M25GateState.PASS,
        paperBurnInHours = M25ReleaseReadinessPolicy.MIN_PAPER_BURN_IN_HOURS,
        shadowBurnInHours = M25ReleaseReadinessPolicy.MIN_SHADOW_BURN_IN_HOURS
    )

    @Test
    fun codeRcDoesNotPretendExternalEvidenceIsReady() {
        val result = M25ReleaseReadinessPolicy.evaluate(codePass())
        assertEquals(M25ReadinessStage.CODE_RC, result.stage)
        assertTrue(result.codeRcReady)
        assertFalse(result.controlledLiveEligible)
        assertFalse(result.releaseReady)
        assertTrue(result.blockers.any { it.contains("CloudShare production") })
    }

    @Test
    fun burnInThresholdCannotBeBypassed() {
        val evidence = preLivePass().copy(
            paperBurnInHours = M25ReleaseReadinessPolicy.MIN_PAPER_BURN_IN_HOURS - 1
        )
        val result = M25ReleaseReadinessPolicy.evaluate(evidence)
        assertEquals(M25ReadinessStage.CODE_RC, result.stage)
        assertFalse(result.controlledLiveEligible)
        assertTrue(result.blockers.any { it.contains("PAPER burn-in") })
    }

    @Test
    fun productionCloudShareAndSignedArtifactAreRequiredBeforeControlledLive() {
        val evidence = preLivePass().copy(
            releaseSigned = M25GateState.PENDING,
            cloudShareProductionVerified = M25GateState.PENDING
        )
        val result = M25ReleaseReadinessPolicy.evaluate(evidence)
        assertEquals(M25ReadinessStage.CODE_RC, result.stage)
        assertTrue(result.blockers.any { it.contains("release signing") })
        assertTrue(result.blockers.any { it.contains("CloudShare production") })
    }

    @Test
    fun controlledLiveEligibilityPrecedesPostLiveReleaseReadiness() {
        val result = M25ReleaseReadinessPolicy.evaluate(preLivePass())
        assertEquals(M25ReadinessStage.CONTROLLED_LIVE_ELIGIBLE, result.stage)
        assertTrue(result.controlledLiveEligible)
        assertFalse(result.releaseReady)
        assertTrue(result.blockers.any { it.contains("tiny LIVE") })
    }

    @Test
    fun releaseReadyRequiresFullPostLiveLifecycleEvidence() {
        val evidence = preLivePass().copy(
            tinyLiveCompleted = M25GateState.PASS,
            protectiveExitVerified = M25GateState.PASS,
            networkFailureLifecycleVerified = M25GateState.PASS,
            feePnlReconciled = M25GateState.PASS,
            finalDiagnosticsExported = M25GateState.PASS
        )
        val result = M25ReleaseReadinessPolicy.evaluate(evidence)
        assertEquals(M25ReadinessStage.RELEASE_READY, result.stage)
        assertTrue(result.releaseReady)
        assertTrue(result.blockers.isEmpty())
    }

    @Test
    fun anyExplicitFailureBlocksTheCandidate() {
        val evidence = preLivePass().copy(
            krakenPermissionsVerified = M25GateState.FAIL
        )
        val result = M25ReleaseReadinessPolicy.evaluate(evidence)
        assertEquals(M25ReadinessStage.BLOCKED, result.stage)
        assertFalse(result.codeRcReady)
        assertFalse(result.controlledLiveEligible)
        assertFalse(result.releaseReady)
    }
}
