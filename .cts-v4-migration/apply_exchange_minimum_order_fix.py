#!/usr/bin/env python3
"""Apply exchange-reported minimum-order sizing before CTS submits BUY orders.

Run AFTER apply_milestone6.py and apply_full_integration_cleanup.py.
"""
from __future__ import annotations

import sys
from pathlib import Path

POLICY_SOURCE = r'''package com.ksp.cryptobot.execution

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
'''

TEST_SOURCE = r'''package com.ksp.cryptobot.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ExchangeMinimumOrderPolicyTest {
    @Test
    fun nearEurThreePointSixSixIsRaisedToFourWhenSafe() {
        val result = ExchangeMinimumOrderPolicy.evaluate(
            targetNotional = BigDecimal("9.15"),
            price = BigDecimal("2.50"),
            quantityDecimals = 8,
            minOrderSize = BigDecimal("4"),
            minOrderCost = BigDecimal.ZERO,
            hardCapNotional = BigDecimal("25"),
            maxSpendableNotional = BigDecimal("30"),
            allowUpsizeToMinimum = true
        )
        assertTrue(result.allowed)
        assertTrue(result.adjustedToMinimum)
        assertEquals(0, result.quantity.compareTo(BigDecimal("4.00000000")))
    }

    @Test
    fun exchangeMinimumDoesNotOverrideHardRiskCap() {
        val result = ExchangeMinimumOrderPolicy.evaluate(
            BigDecimal("9.15"), BigDecimal("2.50"), 8, BigDecimal("4"), BigDecimal.ZERO,
            BigDecimal("9.50"), BigDecimal("30"), true
        )
        assertFalse(result.allowed)
        assertTrue(result.reason.contains("hard position/order cap"))
    }

    @Test
    fun exchangeMinimumDoesNotSpendReservedCash() {
        val result = ExchangeMinimumOrderPolicy.evaluate(
            BigDecimal("9.15"), BigDecimal("2.50"), 8, BigDecimal("4"), BigDecimal.ZERO,
            BigDecimal("25"), BigDecimal("9.80"), true
        )
        assertFalse(result.allowed)
        assertTrue(result.reason.contains("cannot be funded"))
    }

    @Test
    fun postRiskSizingBelowMinimumIsSkippedNotUpsized() {
        val result = ExchangeMinimumOrderPolicy.evaluate(
            BigDecimal("9.15"), BigDecimal("2.50"), 8, BigDecimal("4"), BigDecimal.ZERO,
            BigDecimal("25"), BigDecimal("30"), false
        )
        assertFalse(result.allowed)
        assertTrue(result.reason.contains("Risk-sized BUY is below"))
    }

    @Test
    fun costMinimumIsAlsoRespected() {
        val result = ExchangeMinimumOrderPolicy.evaluate(
            BigDecimal("9.00"), BigDecimal("2.00"), 4, BigDecimal("1"), BigDecimal("10"),
            BigDecimal("25"), BigDecimal("25"), true
        )
        assertTrue(result.allowed)
        assertTrue(result.adjustedToMinimum)
        assertTrue(result.targetNotional >= BigDecimal("10.05"))
    }
}
'''


def fail(message: str) -> None:
    raise SystemExit(f"[CTS exchange minimum-order fix] {message}")


def require(path: Path) -> None:
    if not path.exists():
        fail(f"Required file missing: {path}")


def patch_controller(path: Path) -> None:
    text = path.read_text(encoding="utf-8")

    if "import com.ksp.cryptobot.execution.ExchangeMinimumOrderPolicy" not in text:
        anchor = "import com.ksp.cryptobot.execution.ExecutionGuard\n"
        if anchor not in text:
            fail("ExecutionGuard import anchor missing in BotController.kt")
        text = text.replace(anchor, anchor + "import com.ksp.cryptobot.execution.ExchangeMinimumOrderPolicy\n", 1)

    pre_marker = "        var plannedEntryOrderType: OrderType? = null\n"
    if "val maxSpendableForExchangeMinimum" not in text:
        if pre_marker not in text:
            fail("Advanced-entry marker missing; Milestone 4 execution patch is not active")
        pre_block = '''        // Exchange minimum preflight. Kraken exposes ordermin and costmin; use both
        // before the research/risk planner so a safely fundable minimum-sized order
        // can be considered. This never exceeds perOrderCap or the post-reserve quote budget.
        val maxSpendableForExchangeMinimum = if (side == OrderSide.BUY) {
            if (settings.mode == BotMode.PAPER) {
                perOrderCap
            } else {
                val freeQuoteForMinimum = availableQuote ?: BigDecimal.ZERO
                val afterReserveForMinimum = freeQuoteForMinimum
                    .subtract(quoteReserve)
                    .subtract(quoteReservedThisScan)
                    .max(BigDecimal.ZERO)
                    .divide(feeReserveMultiplier, 8, RoundingMode.DOWN)
                val quoteBudgetForMinimum = when {
                    quoteAsset in setOf("EUR", "USD", "USDT", "USDC") -> afterReserveForMinimum
                    settings.nonEurQuoteBuyEnabled -> {
                        val cryptoQuoteCapForMinimum = freeQuoteForMinimum
                            .multiply(settings.maxNonEurQuoteSpendPercent)
                            .divide(BigDecimal("100"), 8, RoundingMode.DOWN)
                        afterReserveForMinimum.min(cryptoQuoteCapForMinimum)
                    }
                    else -> BigDecimal.ZERO
                }
                perOrderCap.min(quoteBudgetForMinimum)
            }
        } else BigDecimal.ZERO

        if (side == OrderSide.BUY && pairInfo != null) {
            val exchangeMinimumPreflight = ExchangeMinimumOrderPolicy.evaluate(
                targetNotional = targetNotional,
                price = price,
                quantityDecimals = pairInfo.quantityDecimals,
                minOrderSize = pairInfo.minOrderSize,
                minOrderCost = pairInfo.minOrderCost,
                hardCapNotional = perOrderCap,
                maxSpendableNotional = maxSpendableForExchangeMinimum,
                allowUpsizeToMinimum = true
            )
            if (!exchangeMinimumPreflight.allowed) {
                val minimumReason = "[${ticker.symbol}] BUY skipped before submission: ${exchangeMinimumPreflight.reason}"
                updateStatus(minimumReason, "WARN")
                productionIntelligence.recordWhyNotTrade(decision, settings, minimumReason)
                return ExecutionAttemptResult(false)
            }
            if (exchangeMinimumPreflight.adjustedToMinimum) {
                targetNotional = exchangeMinimumPreflight.targetNotional
                updateStatus(
                    "[${ticker.symbol}] Exchange minimum BUY budget adjusted safely: " +
                        "qty=${exchangeMinimumPreflight.quantity.stripTrailingZeros().toPlainString()}, " +
                        "notional≈${exchangeMinimumPreflight.targetNotional.setScale(2, RoundingMode.UP)} $quoteAsset. " +
                        "Kraken ordermin=${pairInfo.minOrderSize}, costmin=${pairInfo.minOrderCost}.",
                    "INFO"
                )
            }
        }

'''
        text = text.replace(pre_marker, pre_block + pre_marker, 1)

    if "val exchangeMinimumAfterRisk" not in text:
        anchor = "            targetNotional = advancedPlan.finalQuote\n"
        if anchor not in text:
            fail("Advanced-plan finalQuote anchor missing")
        repl = anchor + '''            if (pairInfo != null) {
                val exchangeMinimumAfterRisk = ExchangeMinimumOrderPolicy.evaluate(
                    targetNotional = targetNotional,
                    price = price,
                    quantityDecimals = pairInfo.quantityDecimals,
                    minOrderSize = pairInfo.minOrderSize,
                    minOrderCost = pairInfo.minOrderCost,
                    hardCapNotional = perOrderCap,
                    maxSpendableNotional = maxSpendableForExchangeMinimum,
                    allowUpsizeToMinimum = false
                )
                if (!exchangeMinimumAfterRisk.allowed) {
                    val minimumReason = "[${ticker.symbol}] BUY skipped after AI/research risk sizing: ${exchangeMinimumAfterRisk.reason}"
                    updateStatus(minimumReason, "WARN")
                    productionIntelligence.recordWhyNotTrade(decision, settings, minimumReason)
                    return ExecutionAttemptResult(false)
                }
            }
'''
        text = text.replace(anchor, repl, 1)

    old_quantity = '''        } else {
            targetNotional.divide(price, 8, RoundingMode.DOWN)
        }
'''
    if "finalExchangeMinimumCheck" not in text:
        if old_quantity not in text:
            fail("BUY quantity calculation anchor missing")
        new_quantity = '''        } else {
            val finalExchangeMinimumCheck = if (pairInfo != null) {
                ExchangeMinimumOrderPolicy.evaluate(
                    targetNotional = targetNotional,
                    price = price,
                    quantityDecimals = pairInfo.quantityDecimals,
                    minOrderSize = pairInfo.minOrderSize,
                    minOrderCost = pairInfo.minOrderCost,
                    hardCapNotional = perOrderCap,
                    maxSpendableNotional = maxSpendableForExchangeMinimum,
                    allowUpsizeToMinimum = false
                )
            } else null
            if (finalExchangeMinimumCheck != null && !finalExchangeMinimumCheck.allowed) {
                val minimumReason = "[${ticker.symbol}] BUY skipped at final quantity preflight: ${finalExchangeMinimumCheck.reason}"
                updateStatus(minimumReason, "WARN")
                productionIntelligence.recordWhyNotTrade(decision, settings, minimumReason)
                return ExecutionAttemptResult(false)
            }
            finalExchangeMinimumCheck?.quantity
                ?: targetNotional.divide(price, 8, RoundingMode.DOWN)
        }
'''
        text = text.replace(old_quantity, new_quantity, 1)

    alert_anchor = '            sendRemoteAlert(settings, "Order submit failed", "${request.side} ${request.symbol}: ${error.message}")\n'
    if "deterministicMinimumRejection" not in text and alert_anchor in text:
        replacement = '''            val deterministicMinimumRejection =
                error.message?.contains("order size too small", ignoreCase = true) == true ||
                    error.message?.contains("order cost too small", ignoreCase = true) == true
            if (deterministicMinimumRejection) {
                updateStatus(
                    "Exchange minimum rejection escaped local preflight for ${request.symbol}; remote-alert spam suppressed. " +
                        "The next scan will re-read pair metadata and skip unless it can satisfy the new minimum.",
                    "WARN"
                )
            } else {
                sendRemoteAlert(settings, "Order submit failed", "${request.side} ${request.symbol}: ${error.message}")
            }
'''
        text = text.replace(alert_anchor, replacement, 1)

    path.write_text(text, encoding="utf-8")


def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: apply_exchange_minimum_order_fix.py <repo-root>")
    repo = Path(sys.argv[1]).resolve()
    controller = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    models = repo / "app/src/main/java/com/ksp/cryptobot/core/Models.kt"
    require(controller)
    require(models)

    model_text = models.read_text(encoding="utf-8")
    if "val minOrderCost: BigDecimal = BigDecimal.ZERO" not in model_text:
        fail("ExchangeSymbolInfo.minOrderCost is missing; run Milestone 6 before this fix")

    policy = repo / "app/src/main/java/com/ksp/cryptobot/execution/ExchangeMinimumOrderPolicy.kt"
    test = repo / "app/src/test/java/com/ksp/cryptobot/execution/ExchangeMinimumOrderPolicyTest.kt"
    policy.parent.mkdir(parents=True, exist_ok=True)
    test.parent.mkdir(parents=True, exist_ok=True)
    policy.write_text(POLICY_SOURCE, encoding="utf-8")
    test.write_text(TEST_SOURCE, encoding="utf-8")
    patch_controller(controller)

    effective = controller.read_text(encoding="utf-8")
    required = [
        "ExchangeMinimumOrderPolicy.evaluate",
        "maxSpendableForExchangeMinimum",
        "exchangeMinimumAfterRisk",
        "finalExchangeMinimumCheck",
        "BUY skipped before submission",
        "BUY skipped after AI/research risk sizing",
    ]
    missing = [marker for marker in required if marker not in effective]
    if missing:
        fail("Controller minimum-order integration incomplete: " + ", ".join(missing))

    print("[CTS exchange minimum-order fix] PASS")
    print("  - Kraken ordermin enforced before submit")
    print("  - Kraken costmin enforced before submit")
    print("  - safe minimum upsize respects hard cap and cash reserve")
    print("  - post-risk undersized orders are skipped, not force-upsized")
    print("  - deterministic minimum rejection Telegram spam suppressed")


if __name__ == "__main__":
    main()
