package com.ksp.cryptobot.tax

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Belgium-focused crypto tax estimator.
 * This is a conservative planning helper, not legal/tax advice.
 */
class BelgiumTaxEngine(
    private val annualAllowanceEur: BigDecimal = BigDecimal("10000.00"),
    private val capitalGainsTaxRate: BigDecimal = BigDecimal("0.10")
) {
    fun estimateRealizedGain(
        sellValueEur: BigDecimal,
        costBasisEur: BigDecimal,
        feesEur: BigDecimal
    ): BigDecimal = sellValueEur.subtract(costBasisEur).subtract(feesEur)

    fun estimateTaxAfterSale(
        realizedGainsThisYearEur: BigDecimal,
        newGainEur: BigDecimal
    ): TaxEstimate {
        val total = realizedGainsThisYearEur.add(newGainEur).max(BigDecimal.ZERO)
        val taxable = total.subtract(annualAllowanceEur).max(BigDecimal.ZERO)
        val tax = taxable.multiply(capitalGainsTaxRate).setScale(2, RoundingMode.HALF_UP)
        val remainingAllowance = annualAllowanceEur.subtract(total).max(BigDecimal.ZERO)
        val warning = when {
            newGainEur <= BigDecimal.ZERO -> "No capital-gain tax estimated because this sale is not profitable."
            remainingAllowance > BigDecimal.ZERO -> "Within annual allowance estimate. Keep records."
            else -> "This sale may exceed the annual allowance estimate. Consider waiting if tax optimization is priority."
        }
        return TaxEstimate(total, taxable, tax, remainingAllowance, warning)
    }
}

data class TaxEstimate(
    val yearlyRealizedGainsEur: BigDecimal,
    val taxableGainEur: BigDecimal,
    val estimatedTaxEur: BigDecimal,
    val remainingAllowanceEur: BigDecimal,
    val warning: String
)
