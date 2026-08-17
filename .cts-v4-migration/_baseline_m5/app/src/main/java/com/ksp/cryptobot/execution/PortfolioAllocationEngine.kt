package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.AiDecision
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.data.PositionEntity
import com.ksp.cryptobot.data.TradeEntity
import java.math.BigDecimal
import java.math.RoundingMode

/** Capital-efficiency layer. It may reduce/promote within the existing max-position cap, never above it. */
class PortfolioAllocationEngine {
    fun allocate(
        settings: BotSettings,
        decision: AiDecision,
        requestedQuote: BigDecimal,
        recentTrades: List<TradeEntity>,
        positions: List<PositionEntity>
    ): PortfolioAllocationDecision {
        if (requestedQuote <= BigDecimal.ZERO) return PortfolioAllocationDecision(false, BigDecimal.ZERO, BigDecimal.ZERO, "base amount is zero")
        val symbol = decision.symbol.uppercase()
        val base = baseAsset(symbol)
        val duplicateBase = positions.firstOrNull { it.status == "OPEN" && it.symbol != symbol && baseAsset(it.symbol) == base }
        if (duplicateBase != null) {
            return PortfolioAllocationDecision(false, BigDecimal.ZERO, BigDecimal.ZERO, "portfolio optimizer blocked duplicate base exposure for $base via ${duplicateBase.symbol}")
        }
        val openCountMultiplier = BigDecimal.valueOf((1.0 - ((positions.count { it.status == "OPEN" } - 1).coerceAtLeast(0) * 0.08)).coerceAtLeast(0.60))
        val confidenceMultiplier = BigDecimal.valueOf((0.60 + decision.confidencePercent.coerceIn(0, 100) / 100.0 * 0.75).coerceIn(0.50, 1.25))
        val scoreMultiplier = BigDecimal.valueOf((0.70 + decision.finalScore.coerceAtLeast(0) / 250.0).coerceIn(0.70, 1.15))
        val symbolTrades = recentTrades.filter { it.symbol.equals(symbol, ignoreCase = true) && (it.realizedPnlEur.toBigDecimalOrNull() ?: BigDecimal.ZERO) != BigDecimal.ZERO }.take(80)
        val performanceMultiplier = if (symbolTrades.size >= 8) {
            val outcomes = symbolTrades.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }
            val pnl = outcomes.fold(BigDecimal.ZERO, BigDecimal::add)
            val winRate = outcomes.count { it > BigDecimal.ZERO }.toDouble() / outcomes.size.coerceAtLeast(1)
            when {
                pnl > BigDecimal.ZERO && winRate > 0.55 -> BigDecimal("1.10")
                pnl < BigDecimal.ZERO && winRate < 0.45 -> BigDecimal("0.65")
                else -> BigDecimal.ONE
            }
        } else BigDecimal.ONE
        val rawMultiplier = confidenceMultiplier.multiply(scoreMultiplier).multiply(openCountMultiplier).multiply(performanceMultiplier)
            .coerceIn(BigDecimal("0.10"), BigDecimal("1.35"))
        val cap = settings.effectiveMaxPositionFor(symbol)
        // requestedQuote already reflects the controller's balance/reserve/per-order ceiling.
        // Never increase above that value; positive historical evidence may prevent a reduction,
        // but cannot manufacture spendable quote or bypass the existing Android cap.
        val amount = requestedQuote.multiply(rawMultiplier).min(cap).min(requestedQuote).setScale(2, RoundingMode.DOWN)
        val effectiveMultiplier = if (requestedQuote > BigDecimal.ZERO) amount.divide(requestedQuote, 6, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val reason = "portfolio allocation: requested=${requestedQuote.setScale(2, RoundingMode.DOWN)}, confidence×${confidenceMultiplier.s2()}, score×${scoreMultiplier.s2()}, open-count×${openCountMultiplier.s2()}, history×${performanceMultiplier.s2()}, effective×${effectiveMultiplier.s2()}, final=${amount.setScale(2, RoundingMode.DOWN)}"
        return PortfolioAllocationDecision(amount > BigDecimal.ZERO, amount, effectiveMultiplier, reason)
    }

    private fun baseAsset(symbol: String): String {
        val upper = symbol.uppercase().replace("/", "").replace("-", "")
        return listOf("USDT", "USDC", "EUR", "USD", "GBP", "CHF", "BTC", "ETH").firstOrNull { upper.endsWith(it) && upper.length > it.length }?.let { upper.removeSuffix(it) } ?: upper.take(3)
    }
    private fun BigDecimal.coerceIn(min: BigDecimal, max: BigDecimal): BigDecimal = when { this < min -> min; this > max -> max; else -> this }
    private fun BigDecimal.s2(): String = setScale(2, RoundingMode.HALF_UP).toPlainString()
}
