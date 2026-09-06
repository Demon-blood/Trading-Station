package com.ksp.cryptobot.release

enum class M25GateState {
    PASS,
    PENDING,
    FAIL
}

enum class M25ReadinessStage {
    BLOCKED,
    CODE_RC,
    CONTROLLED_LIVE_ELIGIBLE,
    RELEASE_READY
}

data class M25ReleaseEvidence(
    val ciRegression: M25GateState = M25GateState.PENDING,
    val dependencySecurityScan: M25GateState = M25GateState.PENDING,
    val canonicalIdentitySchema12: M25GateState = M25GateState.PENDING,
    val restartRecovery: M25GateState = M25GateState.PENDING,
    val networkRecovery: M25GateState = M25GateState.PENDING,
    val partialFillLifecycle: M25GateState = M25GateState.PENDING,
    val diagnosticsBundle: M25GateState = M25GateState.PENDING,

    val releaseSigned: M25GateState = M25GateState.PENDING,
    val installUpgradeVerified: M25GateState = M25GateState.PENDING,
    val cloudShareProductionVerified: M25GateState = M25GateState.PENDING,
    val krakenPermissionsVerified: M25GateState = M25GateState.PENDING,
    val distributedAuthorityVerified: M25GateState = M25GateState.PENDING,
    val paperBurnInHours: Int = 0,
    val shadowBurnInHours: Int = 0,

    val tinyLiveCompleted: M25GateState = M25GateState.PENDING,
    val protectiveExitVerified: M25GateState = M25GateState.PENDING,
    val networkFailureLifecycleVerified: M25GateState = M25GateState.PENDING,
    val feePnlReconciled: M25GateState = M25GateState.PENDING,
    val finalDiagnosticsExported: M25GateState = M25GateState.PENDING
)

data class M25ReadinessResult(
    val stage: M25ReadinessStage,
    val blockers: List<String>,
    val codeRcReady: Boolean,
    val controlledLiveEligible: Boolean,
    val releaseReady: Boolean
)

object M25ReleaseReadinessPolicy {
    const val MIN_PAPER_BURN_IN_HOURS = 24
    const val MIN_SHADOW_BURN_IN_HOURS = 24

    private data class Gate(val name: String, val state: M25GateState)

    fun evaluate(evidence: M25ReleaseEvidence): M25ReadinessResult {
        val allGates = listOf(
            Gate("CI regression", evidence.ciRegression),
            Gate("dependency security scan", evidence.dependencySecurityScan),
            Gate("canonical Room schema 12 identity", evidence.canonicalIdentitySchema12),
            Gate("restart recovery", evidence.restartRecovery),
            Gate("network recovery", evidence.networkRecovery),
            Gate("partial-fill lifecycle", evidence.partialFillLifecycle),
            Gate("diagnostics bundle", evidence.diagnosticsBundle),
            Gate("release signing", evidence.releaseSigned),
            Gate("install/upgrade", evidence.installUpgradeVerified),
            Gate("CloudShare production", evidence.cloudShareProductionVerified),
            Gate("Kraken API permissions", evidence.krakenPermissionsVerified),
            Gate("distributed authority", evidence.distributedAuthorityVerified),
            Gate("tiny LIVE", evidence.tinyLiveCompleted),
            Gate("protective exit", evidence.protectiveExitVerified),
            Gate("network-failure lifecycle", evidence.networkFailureLifecycleVerified),
            Gate("fee/PnL reconciliation", evidence.feePnlReconciled),
            Gate("final diagnostics", evidence.finalDiagnosticsExported)
        )

        val hardFailures = allGates.filter { it.state == M25GateState.FAIL }
        if (hardFailures.isNotEmpty()) {
            return M25ReadinessResult(
                stage = M25ReadinessStage.BLOCKED,
                blockers = hardFailures.map { "${it.name}: FAIL" },
                codeRcReady = false,
                controlledLiveEligible = false,
                releaseReady = false
            )
        }

        val codeGates = listOf(
            Gate("CI regression", evidence.ciRegression),
            Gate("dependency security scan", evidence.dependencySecurityScan),
            Gate("canonical Room schema 12 identity", evidence.canonicalIdentitySchema12),
            Gate("restart recovery", evidence.restartRecovery),
            Gate("network recovery", evidence.networkRecovery),
            Gate("partial-fill lifecycle", evidence.partialFillLifecycle),
            Gate("diagnostics bundle", evidence.diagnosticsBundle)
        )
        val codeBlockers = codeGates.filter { it.state != M25GateState.PASS }
            .map { "${it.name}: ${it.state}" }
        if (codeBlockers.isNotEmpty()) {
            return M25ReadinessResult(
                stage = M25ReadinessStage.BLOCKED,
                blockers = codeBlockers,
                codeRcReady = false,
                controlledLiveEligible = false,
                releaseReady = false
            )
        }

        val preLiveGates = listOf(
            Gate("release signing", evidence.releaseSigned),
            Gate("install/upgrade", evidence.installUpgradeVerified),
            Gate("CloudShare production", evidence.cloudShareProductionVerified),
            Gate("Kraken API permissions", evidence.krakenPermissionsVerified),
            Gate("distributed authority", evidence.distributedAuthorityVerified)
        )
        val preLiveBlockers = preLiveGates.filter { it.state != M25GateState.PASS }
            .map { "${it.name}: ${it.state}" }
            .toMutableList()

        if (evidence.paperBurnInHours < MIN_PAPER_BURN_IN_HOURS) {
            preLiveBlockers += "PAPER burn-in: ${evidence.paperBurnInHours}/${MIN_PAPER_BURN_IN_HOURS}h"
        }
        if (evidence.shadowBurnInHours < MIN_SHADOW_BURN_IN_HOURS) {
            preLiveBlockers += "shadow burn-in: ${evidence.shadowBurnInHours}/${MIN_SHADOW_BURN_IN_HOURS}h"
        }

        if (preLiveBlockers.isNotEmpty()) {
            return M25ReadinessResult(
                stage = M25ReadinessStage.CODE_RC,
                blockers = preLiveBlockers,
                codeRcReady = true,
                controlledLiveEligible = false,
                releaseReady = false
            )
        }

        val postLiveGates = listOf(
            Gate("tiny LIVE", evidence.tinyLiveCompleted),
            Gate("protective exit", evidence.protectiveExitVerified),
            Gate("network-failure lifecycle", evidence.networkFailureLifecycleVerified),
            Gate("fee/PnL reconciliation", evidence.feePnlReconciled),
            Gate("final diagnostics", evidence.finalDiagnosticsExported)
        )
        val postLiveBlockers = postLiveGates.filter { it.state != M25GateState.PASS }
            .map { "${it.name}: ${it.state}" }

        if (postLiveBlockers.isNotEmpty()) {
            return M25ReadinessResult(
                stage = M25ReadinessStage.CONTROLLED_LIVE_ELIGIBLE,
                blockers = postLiveBlockers,
                codeRcReady = true,
                controlledLiveEligible = true,
                releaseReady = false
            )
        }

        return M25ReadinessResult(
            stage = M25ReadinessStage.RELEASE_READY,
            blockers = emptyList(),
            codeRcReady = true,
            controlledLiveEligible = true,
            releaseReady = true
        )
    }
}
