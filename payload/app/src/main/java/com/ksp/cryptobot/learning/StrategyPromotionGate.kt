package com.ksp.cryptobot.learning

import com.ksp.cryptobot.strategy.provenance.StrategyLifecycle
import java.math.BigDecimal

data class PromotionEvidence(
    val strategyId: String,
    val outOfSampleTrades: Int,
    val expectancyR: BigDecimal,
    val maxDrawdownPct: BigDecimal,
    val largestTradeContributionPct: BigDecimal,
    val largestSymbolContributionPct: BigDecimal,
    val walkForwardPassed: Boolean,
    val paperPassed: Boolean,
    val shadowLivePassed: Boolean,
    val paperLiveIntentParityPassed: Boolean,
    val unresolvedExecutionErrors: Int
)

data class PromotionPolicy(
    val minOutOfSampleTrades: Int = 50,
    val maxDrawdownPct: BigDecimal = BigDecimal("10.0"),
    val maxLargestTradeContributionPct: BigDecimal = BigDecimal("35.0"),
    val maxLargestSymbolContributionPct: BigDecimal = BigDecimal("70.0")
)

data class PromotionDecision(
    val eligible: Boolean,
    val nextLifecycle: StrategyLifecycle,
    val blockers: List<String>
)

/**
 * CTS policy gate. Thresholds are product policy defaults, not source claims and not proof of profitability.
 */
object StrategyPromotionGate {
    fun evaluate(e: PromotionEvidence, p: PromotionPolicy = PromotionPolicy()): PromotionDecision {
        val blockers = mutableListOf<String>()
        if (e.outOfSampleTrades < p.minOutOfSampleTrades) blockers += "OOS samples ${e.outOfSampleTrades}/${p.minOutOfSampleTrades}"
        if (e.expectancyR <= BigDecimal.ZERO) blockers += "OOS expectancyR must be >0 after costs"
        if (e.maxDrawdownPct > p.maxDrawdownPct) blockers += "drawdown ${e.maxDrawdownPct}% > ${p.maxDrawdownPct}%"
        if (e.largestTradeContributionPct > p.maxLargestTradeContributionPct) blockers += "edge overly dependent on one trade"
        if (e.largestSymbolContributionPct > p.maxLargestSymbolContributionPct) blockers += "edge overly dependent on one symbol"
        if (!e.walkForwardPassed) blockers += "walk-forward not passed"
        if (!e.paperPassed) blockers += "paper gate not passed"
        if (!e.shadowLivePassed) blockers += "shadow-live gate not passed"
        if (!e.paperLiveIntentParityPassed) blockers += "Paper/Validate/Live intent parity unresolved"
        if (e.unresolvedExecutionErrors > 0) blockers += "unresolved execution errors=${e.unresolvedExecutionErrors}"
        return PromotionDecision(blockers.isEmpty(), if (blockers.isEmpty()) StrategyLifecycle.ELIGIBLE_FOR_LIVE else StrategyLifecycle.DEGRADED, blockers)
    }
}
