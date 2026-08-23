package com.ksp.cryptobot.execution

import java.math.BigDecimal
import java.math.RoundingMode

data class ExchangeMinimumSizingDecision(
    val allowed: Boolean,
    val targetNotional: BigDecimal,
    val quantity: BigDecimal,
    val requiredMinimumQuantity: BigDecimal,
    val requiredMinimumNotional: BigDecimal,
    val adjustedToMinimum: Boolean,
    val reason: String
)

object ExchangeMinimumOrderPolicy {
    private val ZERO = BigDecimal.ZERO
    private val COST_MIN_BUFFER = BigDecimal("1.005")

    fun evaluate(
        targetNotional: BigDecimal,
        price: BigDecimal,
        quantityDecimals: Int,
        minOrderSize: BigDecimal,
        minOrderCost: BigDecimal,
        hardCapNotional: BigDecimal,
        maxSpendableNotional: BigDecimal,
        allowUpsizeToMinimum: Boolean
    ): ExchangeMinimumSizingDecision {
        if (price <= ZERO) {
            return blocked("Price is zero/negative; cannot calculate an exchange-compliant quantity.")
        }

        val scale = quantityDecimals.coerceIn(0, 12)
        val target = targetNotional.max(ZERO)
        val hardCap = hardCapNotional.max(ZERO)
        val spendable = maxSpendableNotional.max(ZERO)
        val minQtyBySize = minOrderSize.max(ZERO).setScale(scale, RoundingMode.CEILING)
        val bufferedCostMin = if (minOrderCost > ZERO) minOrderCost.multiply(COST_MIN_BUFFER) else ZERO
        val minQtyByCost = if (bufferedCostMin > ZERO) {
            bufferedCostMin.divide(price, scale, RoundingMode.CEILING)
        } else {
            ZERO.setScale(scale)
        }
        val requiredQty = minQtyBySize.max(minQtyByCost)
        val requiredNotional = requiredQty.multiply(price)
        val targetQty = target.divide(price, scale, RoundingMode.DOWN)
        val targetCost = targetQty.multiply(price)

        val sizeSatisfied = targetQty >= minQtyBySize
        val costSatisfied = bufferedCostMin <= ZERO || targetCost >= bufferedCostMin
        if (sizeSatisfied && costSatisfied) {
            return ExchangeMinimumSizingDecision(
                allowed = true,
                targetNotional = target,
                quantity = targetQty,
                requiredMinimumQuantity = requiredQty,
                requiredMinimumNotional = requiredNotional,
                adjustedToMinimum = false,
                reason = "Exchange minimum satisfied: quantity=$targetQty, requiredQty=$requiredQty, cost=$targetCost."
            )
        }

        if (!allowUpsizeToMinimum) {
            return blocked(
                "Risk-sized BUY is below the exchange minimum: targetQty=$targetQty, " +
                    "requiredQty=$requiredQty, targetCost=$targetCost, requiredNotional=$requiredNotional."
            )
        }
        if (requiredNotional > hardCap) {
            return blocked(
                "Exchange minimum would exceed the configured hard position/order cap: " +
                    "requiredNotional=$requiredNotional, hardCap=$hardCap."
            )
        }
        if (requiredNotional > spendable) {
            return blocked(
                "Exchange minimum cannot be funded after the configured cash reserve and current-scan reservations: " +
                    "requiredNotional=$requiredNotional, maxSpendable=$spendable."
            )
        }

        return ExchangeMinimumSizingDecision(
            allowed = true,
            targetNotional = requiredNotional,
            quantity = requiredQty,
            requiredMinimumQuantity = requiredQty,
            requiredMinimumNotional = requiredNotional,
            adjustedToMinimum = true,
            reason = "BUY raised to exchange minimum: requestedQty=$targetQty -> requiredQty=$requiredQty, " +
                "requestedNotional=$target -> requiredNotional=$requiredNotional."
        )
    }

    private fun blocked(reason: String) = ExchangeMinimumSizingDecision(
        allowed = false,
        targetNotional = ZERO,
        quantity = ZERO,
        requiredMinimumQuantity = ZERO,
        requiredMinimumNotional = ZERO,
        adjustedToMinimum = false,
        reason = reason
    )
}
