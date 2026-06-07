package com.ksp.cryptobot.intelligence

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.TradeEntity
import java.math.BigDecimal
import java.math.RoundingMode

class AdvancedTradeMemoryEngine {
    fun similarTradeAdjustment(symbol: String, strategy: StrategyMode, regime: MarketRegime, trades: List<TradeEntity>): Pair<Int, String> {
        val recent = trades.filter { it.symbol == symbol }.take(75)
        if (recent.size < 8) return 0 to "Not enough previous similar trades; no memory adjustment."
        val pnls = recent.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }
        if (pnls.isEmpty()) return 0 to "Previous trades have no realized PnL yet."
        val wins = pnls.count { it > BigDecimal.ZERO }
        val losses = pnls.count { it < BigDecimal.ZERO }
        val grossProfit = pnls.filter { it > BigDecimal.ZERO }.fold(BigDecimal.ZERO, BigDecimal::add)
        val grossLoss = pnls.filter { it < BigDecimal.ZERO }.fold(BigDecimal.ZERO) { acc, v -> acc + v.abs() }
        val winRate = BigDecimal(wins).multiply(BigDecimal("100")).divide(BigDecimal(pnls.size), 2, RoundingMode.HALF_UP)
        val profitFactor = if (grossLoss > BigDecimal.ZERO) grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP) else BigDecimal("9.99")
        val adjustment = when {
            pnls.size >= 15 && winRate >= BigDecimal("62") && profitFactor >= BigDecimal("1.35") -> 8
            winRate >= BigDecimal("55") && profitFactor >= BigDecimal("1.15") -> 4
            losses >= 3 && pnls.take(3).all { it < BigDecimal.ZERO } -> -12
            profitFactor < BigDecimal("0.90") -> -7
            else -> 0
        }
        return adjustment to "Memory: $symbol had winRate=$winRate%, profitFactor=$profitFactor, adjustment=$adjustment for $strategy/$regime."
    }
}
