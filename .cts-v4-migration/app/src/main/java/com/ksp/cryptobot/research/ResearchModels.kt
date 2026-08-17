package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.SignalAction

data class AdvancedRegimeProfile(
    val regime: String,
    val trend: String,
    val volatility: String,
    val risk: String,
    val score: Double,
    val allowedFamilies: Set<String>,
    val blockedFamilies: Set<String>,
    val reason: String
)

data class ResearchStrategyVote(
    val name: String,
    val action: SignalAction,
    val score: Int,
    val confidence: Double,
    val adjustment: Int,
    val reason: String
)

data class WalkForwardAssessment(
    val ready: Boolean,
    val status: String,
    val score: Double,
    val profitableWindows: Int,
    val windows: Int,
    val sampleCount: Int,
    val trainWindow: String,
    val testWindow: String,
    val reason: String
)

data class MonteCarloAssessment(
    val ready: Boolean,
    val score: Double,
    val probabilityPositive: Double,
    val p05Pnl: Double,
    val medianPnl: Double,
    val p95Pnl: Double,
    val p95MaxDrawdown: Double,
    val simulations: Int,
    val sampleCount: Int,
    val reason: String
)

data class MetaModelAssessment(
    val allowed: Boolean,
    val adjustment: Int,
    val confidenceMultiplier: Double,
    val sizeMultiplier: Double,
    val sampleCount: Int,
    val reason: String
)

data class CrossSymbolAssessment(
    val allowed: Boolean,
    val adjustment: Int,
    val multiplier: Double,
    val broadMomentumPct: Double,
    val reason: String
)

data class ContextAssessment(
    val allowed: Boolean,
    val adjustment: Int,
    val multiplier: Double,
    val provider: String,
    val status: String,
    val reason: String
)

data class SequenceModelAssessment(
    val adjustment: Int,
    val probabilityProfit: Double,
    val samples: Int,
    val reason: String
)

data class RlSandboxAssessment(
    val adjustment: Int,
    val state: String,
    val bestAction: String,
    val confidence: Double,
    val reason: String
)

data class MutationCandidate(
    val variant: String,
    val adjustment: Int,
    val reason: String
)

data class ResearchDecisionSummary(
    val selectedStrategy: String,
    val selectedVote: ResearchStrategyVote?,
    val scoreAdjustment: Int,
    val confidenceMultiplier: Double,
    val sizeMultiplier: Double,
    val allowed: Boolean,
    val promotedFromResearch: Boolean,
    val regime: AdvancedRegimeProfile,
    val walkForward: WalkForwardAssessment,
    val monteCarlo: MonteCarloAssessment,
    val meta: MetaModelAssessment,
    val crossSymbol: CrossSymbolAssessment,
    val futuresContext: ContextAssessment,
    val labeledWallet: ContextAssessment,
    val crossMarket: ContextAssessment,
    val sequence: SequenceModelAssessment,
    val rlSandbox: RlSandboxAssessment,
    val mutation: MutationCandidate,
    val parameterSuggestion: String,
    val explanation: String
)

data class BroadMarketContext(
    val btcMomentumPct: Double = 0.0,
    val ethMomentumPct: Double = 0.0,
    val updatedAtEpochMs: Long = 0L
) {
    val broadMomentumPct: Double
        get() = when {
            btcMomentumPct != 0.0 && ethMomentumPct != 0.0 -> (btcMomentumPct + ethMomentumPct) / 2.0
            btcMomentumPct != 0.0 -> btcMomentumPct
            else -> ethMomentumPct
        }
}
