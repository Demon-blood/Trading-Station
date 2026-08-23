package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.OrderType
import java.math.BigDecimal

data class CapitalProtectionDecision(
    val level: Int,
    val allowed: Boolean,
    val sizeMultiplier: BigDecimal,
    val realizedTodayEur: BigDecimal,
    val reason: String
)

data class PortfolioAllocationDecision(
    val allowed: Boolean,
    val finalQuote: BigDecimal,
    val multiplier: BigDecimal,
    val reason: String
)

data class LiquiditySizingDecision(
    val finalQuote: BigDecimal,
    val multiplier: BigDecimal,
    val depthQuote: BigDecimal,
    val reasonCategory: String,
    val requestedSizeBand: String,
    val reason: String
)

data class OrderTypeDecision(
    val orderType: OrderType,
    val limitPrice: BigDecimal?,
    val reasonCategory: String,
    val reason: String
)

data class AdvancedEntryPlan(
    val allowed: Boolean,
    val finalQuote: BigDecimal,
    val orderType: OrderType,
    val limitPrice: BigDecimal?,
    val combinedMultiplier: BigDecimal,
    val protectionLevel: Int,
    val reason: String,
    val postOnly: Boolean = false
)

data class ExitOptimizationPlan(
    val shouldExit: Boolean,
    val sellFraction: BigDecimal,
    val orderType: OrderType,
    val method: String,
    val qualityTier: String,
    val reason: String
)

data class ReconciliationSummary(
    val adjusted: Int,
    val removed: Int,
    val openOrders: Int,
    val messages: List<String>
)
