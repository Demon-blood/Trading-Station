package com.ksp.cryptobot.governance

data class AnomalyAssessment(
    val allowed: Boolean,
    val severity: String,
    val reason: String
)

data class SafeModeAssessment(
    val level: String,
    val reason: String,
    val scoreAdjustment: Int,
    val sizeMultiplier: Double,
    val blockLiveEntries: Boolean
)

data class KillSwitchAssessment(
    val allowed: Boolean,
    val severity: String,
    val reason: String
)

data class RiskBudgetAssessment(
    val multiplier: Double,
    val blocked: Boolean,
    val usedEur: Double,
    val remainingEur: Double,
    val reason: String
)

data class ExecutionQualityAssessment(
    val samples: Int,
    val averageSlippagePct: Double,
    val worstSlippagePct: Double,
    val scoreAdjustment: Int,
    val reason: String
)

data class ProductionDecisionAssessment(
    val scoreAdjustment: Int,
    val blocked: Boolean,
    val sizeMultiplier: Double,
    val severity: String,
    val reason: String,
    val anomaly: AnomalyAssessment,
    val safeMode: SafeModeAssessment,
    val killSwitch: KillSwitchAssessment,
    val riskBudget: RiskBudgetAssessment,
    val executionQuality: ExecutionQualityAssessment,
    val counterfactualAdjustment: Int,
    val counterfactualReason: String
)

data class ProductionRuntimeSnapshot(
    val safeModeLevel: String = "NORMAL",
    val blockLiveEntries: Boolean = false,
    val sizeMultiplier: Double = 1.0,
    val lastReason: String = "not evaluated",
    val updatedAtEpochMs: Long = 0L
)

object ProductionIntelligenceRuntime {
    @Volatile private var snapshot = ProductionRuntimeSnapshot()
    fun snapshot(): ProductionRuntimeSnapshot = snapshot
    fun install(value: ProductionRuntimeSnapshot) { snapshot = value }
    fun reset() { snapshot = ProductionRuntimeSnapshot() }
}
