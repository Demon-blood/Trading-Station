package com.ksp.cryptobot.intelligence

import com.ksp.cryptobot.data.TradeEntity
import java.math.BigDecimal

class TradeMemoryEngine {
    fun score(symbol: String, trades: List<TradeEntity>): Int {
        val relevant = trades.filter { it.symbol == symbol }.take(25)
        if (relevant.size < 3) return 0

        val wins = relevant.count { it.realizedPnlEur.toBigDecimalOrNull()?.let { pnl -> pnl > BigDecimal.ZERO } == true }
        val losses = relevant.count { it.realizedPnlEur.toBigDecimalOrNull()?.let { pnl -> pnl < BigDecimal.ZERO } == true }
        val avgScore = relevant.map { it.aiScore }.average().takeIf { !it.isNaN() } ?: 0.0

        val winLossScore = (wins - losses) * 4
        val qualityScore = ((avgScore - 50.0) / 5.0).toInt()
        return (winLossScore + qualityScore).coerceIn(-30, 30)
    }

    fun explain(score: Int): String = when {
        score >= 15 -> "Previous trades on this symbol were favorable."
        score <= -15 -> "Previous trades on this symbol were unfavorable; the AI layer is defensive."
        score == 0 -> "Not enough previous trade data for a memory signal."
        score > 0 -> "Previous trade memory is mildly positive."
        else -> "Previous trade memory is mildly negative."
    }
}
