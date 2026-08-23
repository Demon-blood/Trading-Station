package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.AdvancedExecutionEventEntity
import com.ksp.cryptobot.data.GovernanceDao
import com.ksp.cryptobot.research.HandoffSideIntent
import com.ksp.cryptobot.research.ResearchExecutionRuntime
import java.math.BigDecimal
import java.math.RoundingMode

class AdvancedExitOptimizer(private val governanceDao: GovernanceDao? = null) {
    suspend fun optimize(settings: BotSettings, position: PositionInfo, decision: AiDecision?, triggerReason: String): ExitOptimizationPlan {
        val text = triggerReason.lowercase()
        val researchDirective = ResearchExecutionRuntime.snapshot(position.symbol)
        val researchIntent = researchDirective?.sideIntent
        val hardStop = "stop-loss" in text
        val hardRisk = hardStop || "risk-off" in text || "bearish" in text || researchIntent == HandoffSideIntent.EXIT
        val pnl = position.unrealizedPnlPercent
        val method: String
        val fraction: BigDecimal
        when {
            hardStop -> { method = "HARD_STOP_FULL"; fraction = BigDecimal.ONE }
            researchIntent == HandoffSideIntent.EXIT -> { method = "HANDOFF_SOURCE_EXIT_FULL"; fraction = BigDecimal.ONE }
            researchIntent == HandoffSideIntent.REDUCE -> {
                method = "HANDOFF_SOURCE_REDUCE"
                fraction = settings.partialExitPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP).coerceIn(BigDecimal("0.05"), BigDecimal.ONE)
            }
            hardRisk -> { method = "HARD_RISK_FULL"; fraction = BigDecimal.ONE }
            "trailing" in text -> { method = "TRAIL_FULL"; fraction = BigDecimal.ONE }
            "spike-exhaustion" in text -> { method = "SPIKE_EXHAUSTION_PARTIAL"; fraction = BigDecimal("0.50") }
            "take-profit" in text && settings.enablePartialTakeProfit -> {
                method = "TP_PARTIAL"
                fraction = settings.partialExitPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP).coerceIn(BigDecimal("0.05"), BigDecimal.ONE)
            }
            else -> { method = "SIGNAL_FULL"; fraction = BigDecimal.ONE }
        }
        // Avoid fee/spread churn only on soft profit exits. Hard risk exits are never delayed here.
        val economicFloor = BigDecimal("0.25")
        val handoffProtective = researchIntent in setOf(HandoffSideIntent.EXIT, HandoffSideIntent.REDUCE)
        val shouldExit = hardRisk || handoffProtective || !(method == "TP_PARTIAL" && pnl < economicFloor)
        val finalFraction = if (shouldExit) fraction else BigDecimal.ZERO
        val orderType = if (hardRisk && settings.enableMarketOrders) OrderType.MARKET else OrderType.LIMIT
        val qualityTier = when {
            pnl >= BigDecimal("2.0") -> "strong_profit"
            pnl >= BigDecimal("0.5") -> "profit"
            pnl >= BigDecimal.ZERO -> "near_breakeven"
            hardRisk -> "risk_exit"
            else -> "loss"
        }
        val sourceNote = if (handoffProtective) ", handoff=${researchDirective?.strategyId}/${researchIntent}, truth=${researchDirective?.liveTruthGate}" else ""
        val reason = "exit optimizer: method=$method, pnl=${pnl.setScale(2, RoundingMode.HALF_UP)}%, fraction=${finalFraction.setScale(2, RoundingMode.HALF_UP)}, orderType=$orderType, trigger=$triggerReason$sourceNote"
        governanceDao?.insertAdvancedExecution(AdvancedExecutionEventEntity(
            eventType = "exit_optimization", symbol = position.symbol, strategy = settings.strategyMode.name,
            mode = if (settings.mode == BotMode.PAPER) "PAPER" else "LIVE", side = "SELL",
            severity = if (hardRisk) "HIGH" else "INFO", finalQuote = position.currentPrice.multiply(position.freeQuantity).multiply(finalFraction).toDouble(),
            multiplier = finalFraction.toDouble(), recommendedOrderType = orderType.name,
            reasonCategory = if (hardRisk) "hard_risk" else "profit_management", requestedSizeBand = "exit",
            exitMethod = method, qualityTier = qualityTier, blocked = !shouldExit, reason = reason
        ))
        return ExitOptimizationPlan(shouldExit, finalFraction, orderType, method, qualityTier, reason)
    }
    private fun BigDecimal.coerceIn(min: BigDecimal, max: BigDecimal): BigDecimal = when { this < min -> min; this > max -> max; else -> this }
}
