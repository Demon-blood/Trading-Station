package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.OrderType
import java.math.BigDecimal

enum class HandoffSideIntent { LONG_ENTRY, EXIT, REDUCE, AVOID, FILTER, CONTEXT, RESEARCH, BLOCKED_SOURCE_UNKNOWN }
enum class HandoffEntryKind { RESTING_STOP, LIMIT_RETEST, LIMIT, MARKET_CONFIRMATION, NONE }
enum class HandoffImplementationClass { SOURCE_FAITHFUL, SOURCE_FAITHFUL_WITH_DISCRETION, FORMALIZED_FROM_PUBLIC_CORE, CONCEPT_INSPIRED, PROPRIETARY_NOT_IMPLEMENTED }
enum class HandoffExecutionEligibility { PAPER_AND_TRUTH_GATED_LIVE, PAPER_ONLY, PROTECTIVE_LIVE_ALLOWED, RESEARCH_ONLY, BLOCKED }

data class HandoffUsageContext(
    val bestConditions: List<String> = emptyList(),
    val requiredConditions: List<String> = emptyList(),
    val acceptableConditions: List<String> = emptyList(),
    val avoidConditions: List<String> = emptyList(),
    val invalidConditions: List<String> = emptyList(),
    val marketRegime: List<String> = emptyList(),
    val volatilityRegime: List<String> = emptyList(),
    val liquidityRequirements: List<String> = emptyList(),
    val directionPolicy: String = "",
    val expectedHoldingPeriod: String = "UNKNOWN_UNLESS_SOURCE_VERIFIED",
    val sourceEvidence: List<String> = emptyList()
)

data class HandoffStrategyDefinition(
    val id: String,
    val trader: String,
    val name: String,
    val researchFreeze: String,
    val fidelity: String,
    val provenance: List<String>,
    val purpose: String,
    val timeframes: List<String>,
    val setupConditions: List<String>,
    val entryTrigger: List<String>,
    val invalidation: List<String>,
    val stopLogic: List<String>,
    val positionSizing: List<String>,
    val tradeManagement: List<String>,
    val targetExit: List<String>,
    val requiredData: List<String>,
    val belgiumKrakenSpotPolicy: String,
    val empiricalStatus: String,
    val discretionaryElements: String,
    val proprietaryUnknown: Boolean,
    val sourceRefs: List<String>,
    val mustNotClaim: String,
    val liveTruthGate: String,
    val usageContext: HandoffUsageContext,
    val usageContextSourceVerified: Boolean,
    val noTradeConditionsSourceVerified: Boolean
) {
    val implementationClass: HandoffImplementationClass
        get() = when {
            fidelity.equals("X", true) -> HandoffImplementationClass.PROPRIETARY_NOT_IMPLEMENTED
            fidelity.equals("A", true) && usageContextSourceVerified && noTradeConditionsSourceVerified -> HandoffImplementationClass.SOURCE_FAITHFUL
            fidelity.equals("A", true) -> HandoffImplementationClass.SOURCE_FAITHFUL_WITH_DISCRETION
            fidelity.equals("B", true) -> HandoffImplementationClass.SOURCE_FAITHFUL_WITH_DISCRETION
            fidelity.equals("C", true) -> HandoffImplementationClass.FORMALIZED_FROM_PUBLIC_CORE
            else -> HandoffImplementationClass.CONCEPT_INSPIRED
        }

    val positiveLiveTruthSatisfied: Boolean
        get() = liveTruthGate.equals("PASS", true) && usageContextSourceVerified && noTradeConditionsSourceVerified && fidelity.uppercase() in setOf("A", "B")
}

data class HandoffEntryPlan(
    val kind: HandoffEntryKind,
    val intendedPrice: BigDecimal?,
    val triggerPrice: BigDecimal? = null,
    val preferredOrderType: OrderType? = null,
    val resting: Boolean = false,
    val postOnlyPreferred: Boolean = false,
    val machineFormalization: Boolean = false,
    val reason: String
)

data class HandoffInvalidationPlan(
    val stopPrice: BigDecimal?,
    val closeBased: Boolean,
    val method: String,
    val sourceExact: Boolean,
    val reason: String
)

data class HandoffTargetPlan(
    val label: String,
    val price: BigDecimal,
    val fraction: BigDecimal,
    val sourceExact: Boolean,
    val reason: String
)

data class HandoffTradeCandidate(
    val strategyId: String,
    val strategyVersion: String,
    val creator: String,
    val strategyName: String,
    val symbol: String,
    val sideIntent: HandoffSideIntent,
    val signalTimeEpochMs: Long,
    val thesisTimeframe: String,
    val executionTimeframe: String,
    val entryPlan: HandoffEntryPlan,
    val invalidation: HandoffInvalidationPlan,
    val targets: List<HandoffTargetPlan>,
    val features: Map<String, String>,
    val provenance: List<String>,
    val fidelity: String,
    val implementationClass: HandoffImplementationClass,
    val executionEligibility: HandoffExecutionEligibility,
    val liveTruthGate: String,
    val setupDetected: Boolean,
    val triggerDetected: Boolean,
    val sourceContextSatisfied: Boolean,
    val explanation: String
)

data class HandoffCostAssessment(
    val allowed: Boolean,
    val entryFeeRate: BigDecimal,
    val exitFeeRate: BigDecimal,
    val spreadPct: BigDecimal,
    val entrySlippageQuote: BigDecimal,
    val exitSlippageQuote: BigDecimal,
    val expectedGrossProfitQuote: BigDecimal,
    val expectedTotalCostQuote: BigDecimal,
    val safetyMarginQuote: BigDecimal,
    val expectedNetProfitQuote: BigDecimal,
    val reason: String
)

data class HandoffRiskAssessment(
    val allowed: Boolean,
    val equityEstimateQuote: BigDecimal,
    val riskPercent: BigDecimal,
    val riskBudgetQuote: BigDecimal,
    val modeledLossPerUnit: BigDecimal,
    val quantityByRisk: BigDecimal,
    val quantityByCash: BigDecimal,
    val finalQuantity: BigDecimal,
    val maxNotionalQuote: BigDecimal,
    val actualModeledLossQuote: BigDecimal,
    val reason: String
)

data class HandoffCandidateEvaluation(
    val definition: HandoffStrategyDefinition,
    val candidate: HandoffTradeCandidate?,
    val status: String,
    val adjustment: Int,
    val sizeMultiplier: Double,
    val cost: HandoffCostAssessment?,
    val risk: HandoffRiskAssessment?,
    val allowedForPaperExecution: Boolean,
    val allowedForLiveEntry: Boolean,
    val allowedForProtectiveLiveAction: Boolean,
    val reason: String
)

data class HandoffResearchEvaluation(
    val evaluations: List<HandoffCandidateEvaluation>,
    val selectedEntry: HandoffCandidateEvaluation?,
    val protectiveAction: HandoffCandidateEvaluation?,
    val aggregateAdjustment: Int,
    val sizeMultiplier: Double,
    val hardEntryBlock: Boolean,
    val reason: String
)
