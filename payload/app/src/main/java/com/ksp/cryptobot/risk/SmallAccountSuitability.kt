package com.ksp.cryptobot.risk

import java.math.BigDecimal
import java.math.RoundingMode

data class SmallAccountInputs(
    val accountEquity: BigDecimal,
    val riskPct: BigDecimal,
    val minimumNotional: BigDecimal,
    val estimatedRoundTripFees: BigDecimal,
    val estimatedSlippage: BigDecimal,
    val averageHoldHours: BigDecimal,
    val estimatedTradesPerWeek: BigDecimal,
    val concurrentCapitalPct: BigDecimal,
    val liquidityScore: Int
)

data class SmallAccountSuitability(
    val score: Int,
    val riskBudget: BigDecimal,
    val feeRiskRatio: BigDecimal,
    val components: Map<String, Int>,
    val reason: String
)

object SmallAccountSuitabilityEngine {
    fun evaluate(i: SmallAccountInputs): SmallAccountSuitability {
        val riskBudget = i.accountEquity.multiply(i.riskPct).divide(BigDecimal("100"), 10, RoundingMode.DOWN)
        val feeRiskRatio = if (riskBudget > BigDecimal.ZERO)
            i.estimatedRoundTripFees.add(i.estimatedSlippage).divide(riskBudget, 8, RoundingMode.HALF_UP)
        else BigDecimal("999")
        val minFit = when {
            i.accountEquity <= BigDecimal.ZERO -> 0
            i.minimumNotional > i.accountEquity.multiply(BigDecimal("0.50")) -> 10
            i.minimumNotional > i.accountEquity.multiply(BigDecimal("0.20")) -> 45
            else -> 90
        }
        val feeScore = when {
            feeRiskRatio >= BigDecimal("1.0") -> 0
            feeRiskRatio >= BigDecimal("0.50") -> 25
            feeRiskRatio >= BigDecimal("0.25") -> 55
            else -> 90
        }
        val turnover = when {
            i.estimatedTradesPerWeek > BigDecimal("20") -> 20
            i.estimatedTradesPerWeek > BigDecimal("10") -> 45
            i.estimatedTradesPerWeek > BigDecimal("5") -> 70
            else -> 90
        }
        val hold = when {
            i.averageHoldHours < BigDecimal("1") -> 30
            i.averageHoldHours < BigDecimal("4") -> 55
            else -> 85
        }
        val concurrency = when {
            i.concurrentCapitalPct > BigDecimal("80") -> 20
            i.concurrentCapitalPct > BigDecimal("50") -> 55
            else -> 85
        }
        val components = linkedMapOf(
            "minimumNotionalFit" to minFit,
            "feeRiskRatio" to feeScore,
            "turnover" to turnover,
            "averageHoldTime" to hold,
            "liquidity" to i.liquidityScore.coerceIn(0,100),
            "slippage" to feeScore,
            "signalFrequency" to turnover,
            "capitalConcurrency" to concurrency
        )
        val score = components.values.average().toInt().coerceIn(0,100)
        return SmallAccountSuitability(score, riskBudget, feeRiskRatio, components,
            "CTS small-account score=$score/100; riskBudget=$riskBudget feeRiskRatio=${feeRiskRatio.setScale(3,RoundingMode.HALF_UP)}. This is a suitability heuristic, not expected return.")
    }
}
