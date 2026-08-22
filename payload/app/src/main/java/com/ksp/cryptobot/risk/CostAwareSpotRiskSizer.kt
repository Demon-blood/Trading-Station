package com.ksp.cryptobot.risk

import java.math.BigDecimal
import java.math.RoundingMode

data class CostAwareRiskInput(
    val equityQuote: BigDecimal,
    val riskFraction: BigDecimal,
    val entryPrice: BigDecimal,
    val stopPrice: BigDecimal,
    val entryFeeRate: BigDecimal,
    val exitFeeRate: BigDecimal,
    val roundTripSlippageRate: BigDecimal,
    val hardNotionalCap: BigDecimal,
    val spendableQuote: BigDecimal
)

data class CostAwareRiskDecision(
    val allowed: Boolean,
    val riskBudgetQuote: BigDecimal,
    val stopDistancePerUnit: BigDecimal,
    val estimatedCostPerUnit: BigDecimal,
    val quantity: BigDecimal,
    val notionalQuote: BigDecimal,
    val estimatedRoundTripFeesQuote: BigDecimal,
    val estimatedSlippageQuote: BigDecimal,
    val estimatedWorstCaseLossQuote: BigDecimal,
    val reason: String
)

/**
 * Sizes from loss-at-stop INCLUDING estimated entry/exit fees and slippage.
 */
object CostAwareSpotRiskSizer {
    private val ZERO = BigDecimal.ZERO

    fun size(input: CostAwareRiskInput): CostAwareRiskDecision {
        val equity = input.equityQuote.max(ZERO)
        val fraction = input.riskFraction.max(ZERO).min(BigDecimal.ONE)
        val entry = input.entryPrice
        val stop = input.stopPrice
        val cap = input.hardNotionalCap.max(ZERO).min(input.spendableQuote.max(ZERO))
        if (equity <= ZERO || fraction <= ZERO || entry <= ZERO || stop <= ZERO || stop >= entry || cap <= ZERO) {
            return blocked("Invalid/unsafe risk inputs: equity=$equity fraction=$fraction entry=$entry stop=$stop cap=$cap")
        }

        val riskBudget = equity.multiply(fraction)
        val stopDistance = entry.subtract(stop)
        val entryFeePerUnit = entry.multiply(input.entryFeeRate.max(ZERO))
        val exitFeePerUnit = stop.multiply(input.exitFeeRate.max(ZERO))
        val slippagePerUnit = entry.multiply(input.roundTripSlippageRate.max(ZERO))
        val lossPerUnit = stopDistance.add(entryFeePerUnit).add(exitFeePerUnit).add(slippagePerUnit)
        if (lossPerUnit <= ZERO) return blocked("Estimated cost/loss per unit is not positive.")

        val riskQty = riskBudget.divide(lossPerUnit, 12, RoundingMode.DOWN)
        val capQty = cap.divide(entry, 12, RoundingMode.DOWN)
        val qty = riskQty.min(capQty).max(ZERO)
        val notional = qty.multiply(entry)
        if (qty <= ZERO || notional <= ZERO) return blocked("Risk/cap sizing produced zero quantity.")

        val fees = qty.multiply(entryFeePerUnit.add(exitFeePerUnit))
        val slip = qty.multiply(slippagePerUnit)
        val worst = qty.multiply(lossPerUnit)
        if (worst > riskBudget.add(BigDecimal("0.00000001"))) {
            return blocked("Invariant failure: estimated worst-case loss=$worst exceeds risk budget=$riskBudget.")
        }
        return CostAwareRiskDecision(
            true, riskBudget, stopDistance, lossPerUnit, qty, notional, fees, slip, worst,
            "Cost-aware size: riskBudget=$riskBudget, stopDistance/unit=$stopDistance, fees≈$fees, slippage≈$slip, worstCase≈$worst, notional=$notional."
        )
    }

    private fun blocked(reason: String) = CostAwareRiskDecision(
        false, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, reason
    )
}

/**
 * Conservative CTS fallback for ordinary Kraken spot-crypto pairs.
 * Source snapshot checked 2026-08-22: Kraken public Spot Crypto Tier 1
 * maker 0.40%, taker 0.80%. CTS deliberately assumes taker on both sides
 * when account-specific fee data is unavailable.
 */
object KrakenCostFallback20260822 {
    val conservativeEntryFeeRate: BigDecimal = BigDecimal("0.0080")
    val conservativeExitFeeRate: BigDecimal = BigDecimal("0.0080")
    const val sourceLabel: String = "KRAKEN_SPOT_CRYPTO_TIER1_PUBLIC_2026-08-22_CONSERVATIVE_TAKER_BOTH_SIDES"
}
