package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.AiDecision
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.data.PositionEntity
import com.ksp.cryptobot.data.TradeEntity
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Capital-efficiency layer.
 *
 * M17 keeps every positive signal subordinate to:
 * - controller-requested spend;
 * - per-position cap;
 * - fresh EUR cash reserve;
 * - configured single-asset concentration;
 * - 70% broad common-factor concentration ceiling;
 * - empirical H1 return correlation when enough data exists.
 *
 * It may only reduce or block requested capital. It can never manufacture spend.
 */
class PortfolioAllocationEngine {
    fun allocate(
        settings: BotSettings,
        decision: AiDecision,
        requestedQuote: BigDecimal,
        recentTrades: List<TradeEntity>,
        positions: List<PositionEntity>,
        correlation: PortfolioCorrelationContext? = null
    ): PortfolioAllocationDecision {
        if (requestedQuote <= BigDecimal.ZERO) {
            return PortfolioAllocationDecision(
                false, BigDecimal.ZERO, BigDecimal.ZERO, "base amount is zero"
            )
        }

        val symbol = decision.symbol.uppercase().replace("/", "").replace("-", "")
        val base = PortfolioCorrelationMath.baseAsset(symbol)
        val open = positions.filter { it.status == "OPEN" }

        if (settings.duplicatePositionProtectionEnabled) {
            val sameSymbol = open.firstOrNull {
                it.symbol.uppercase().replace("/", "").replace("-", "") == symbol
            }
            if (sameSymbol != null) {
                return PortfolioAllocationDecision(
                    false,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    "M17 blocked duplicate open position for $symbol."
                )
            }
        }

        val duplicateBase = open.firstOrNull {
            it.symbol.uppercase().replace("/", "").replace("-", "") != symbol &&
                PortfolioCorrelationMath.baseAsset(it.symbol) == base
        }
        if (duplicateBase != null) {
            return PortfolioAllocationDecision(
                false,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "portfolio optimizer blocked duplicate base exposure for $base via ${duplicateBase.symbol}"
            )
        }

        val openCountMultiplier = BigDecimal.valueOf(
            (1.0 - ((open.size - 1).coerceAtLeast(0) * 0.08)).coerceAtLeast(0.60)
        )
        val confidenceMultiplier = BigDecimal.valueOf(
            (0.60 + decision.confidencePercent.coerceIn(0, 100) / 100.0 * 0.75)
                .coerceIn(0.50, 1.25)
        )
        val scoreMultiplier = BigDecimal.valueOf(
            (0.70 + decision.finalScore.coerceAtLeast(0) / 250.0)
                .coerceIn(0.70, 1.15)
        )

        val symbolTrades = recentTrades
            .filter {
                it.symbol.equals(symbol, ignoreCase = true) &&
                    (it.realizedPnlEur.toBigDecimalOrNull() ?: BigDecimal.ZERO) != BigDecimal.ZERO
            }
            .take(80)
        val performanceMultiplier = if (symbolTrades.size >= 8) {
            val outcomes = symbolTrades.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }
            val pnl = outcomes.fold(BigDecimal.ZERO, BigDecimal::add)
            val winRate = outcomes.count { it > BigDecimal.ZERO }.toDouble() /
                outcomes.size.coerceAtLeast(1)
            when {
                pnl > BigDecimal.ZERO && winRate > 0.55 -> BigDecimal("1.10")
                pnl < BigDecimal.ZERO && winRate < 0.45 -> BigDecimal("0.65")
                else -> BigDecimal.ONE
            }
        } else BigDecimal.ONE

        val correlationMultiplier = correlation?.correlationMultiplier ?: BigDecimal.ONE
        val rawMultiplier = confidenceMultiplier
            .multiply(scoreMultiplier)
            .multiply(openCountMultiplier)
            .multiply(performanceMultiplier)
            .multiply(correlationMultiplier)
            .coerceIn(BigDecimal("0.05"), BigDecimal("1.35"))

        // Start from requestedQuote and only apply downward caps.
        var amount = requestedQuote
            .multiply(rawMultiplier)
            .min(settings.effectiveMaxPositionFor(symbol))
            .min(requestedQuote)

        correlation?.let { context ->
            if (context.freeCashQuote > BigDecimal.ZERO) {
                amount = amount.min(context.availableNewSpendQuote)
            }
            context.singleAssetRemainingQuote?.let { amount = amount.min(it) }
            context.factorRemainingQuote?.let { amount = amount.min(it) }
        }

        amount = amount.max(BigDecimal.ZERO).setScale(2, RoundingMode.DOWN)

        val effectiveMultiplier = if (requestedQuote > BigDecimal.ZERO) {
            amount.divide(requestedQuote, 6, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        val hardBlock = when {
            correlation != null &&
                correlation.freeCashQuote > BigDecimal.ZERO &&
                correlation.availableNewSpendQuote <= BigDecimal.ZERO ->
                "M17 cash reserve blocks new spend: freeEUR=${correlation.freeCashQuote.s2()}, requiredReserve=${correlation.requiredCashReserveQuote.s2()}."

            correlation?.singleAssetRemainingQuote != null &&
                correlation.singleAssetRemainingQuote <= BigDecimal.ZERO ->
                "M17 single-asset allocation ceiling is already exhausted."

            correlation?.factorRemainingQuote != null &&
                correlation.factorRemainingQuote <= BigDecimal.ZERO ->
                "M17 common-factor allocation ceiling is already exhausted for ${correlation.candidateFactorGroup}."

            else -> null
        }
        if (hardBlock != null) {
            return PortfolioAllocationDecision(
                false, BigDecimal.ZERO, BigDecimal.ZERO,
                "$hardBlock ${correlation?.reason.orEmpty()}".trim()
            )
        }

        val reason = buildString {
            append("M17 portfolio allocation: requested=")
            append(requestedQuote.s2())
            append(", confidence×")
            append(confidenceMultiplier.s2())
            append(", score×")
            append(scoreMultiplier.s2())
            append(", open-count×")
            append(openCountMultiplier.s2())
            append(", history×")
            append(performanceMultiplier.s2())
            append(", correlation×")
            append(correlationMultiplier.s2())
            append(", effective×")
            append(effectiveMultiplier.s2())
            append(", final=")
            append(amount.s2())
            correlation?.let {
                append(" | ")
                append(it.reason)
                it.singleAssetRemainingQuote?.let { cap ->
                    append(" singleRemaining=")
                    append(cap.s2())
                }
                it.factorRemainingQuote?.let { cap ->
                    append(" factorRemaining=")
                    append(cap.s2())
                }
            }
        }

        return PortfolioAllocationDecision(
            allowed = amount > BigDecimal.ZERO,
            finalQuote = amount,
            multiplier = effectiveMultiplier,
            reason = reason
        )
    }

    private fun BigDecimal.coerceIn(min: BigDecimal, max: BigDecimal): BigDecimal = when {
        this < min -> min
        this > max -> max
        else -> this
    }

    private fun BigDecimal.s2(): String =
        setScale(2, RoundingMode.HALF_UP).toPlainString()
}
