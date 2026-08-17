package com.ksp.cryptobot.governance

import com.ksp.cryptobot.core.AiDecision
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.data.TradeEntity
import kotlin.math.abs

class KillSwitchEngine {
    private fun notional(trade: TradeEntity): Double {
        val qty = trade.quantity.toDoubleOrNull() ?: 0.0
        val price = trade.priceEur.toDoubleOrNull() ?: 0.0
        return abs(qty * price)
    }

    private fun isMeaningfulLoss(trade: TradeEntity): Boolean {
        val pnl = trade.realizedPnlEur.toDoubleOrNull() ?: 0.0
        if (pnl >= 0.0) return false
        val value = notional(trade)
        val lossPct = if (value > 0.0) abs(pnl / value * 100.0) else 0.0
        return abs(pnl) >= 0.25 || lossPct >= 0.35
    }

    fun evaluate(
        settings: BotSettings,
        decision: AiDecision,
        recentTrades: List<TradeEntity>,
        realizedToday: Double,
        recentOperationalErrors: Int
    ): KillSwitchAssessment {
        val dailyLimit = abs(settings.maxDailyLossEur.toDouble())
        if (dailyLimit > 0.0 && realizedToday <= -dailyLimit) {
            return KillSwitchAssessment(false, "CRITICAL", "Daily loss kill-switch active: realized_today €%.2f <= -€%.2f.".format(realizedToday, dailyLimit))
        }
        if (recentOperationalErrors >= 5) {
            return KillSwitchAssessment(false, "HIGH", "Operational kill-switch: $recentOperationalErrors recent API/runtime errors detected.")
        }

        val closed = recentTrades.filter { it.side.equals("SELL", ignoreCase = true) }
            .sortedBy { it.timestampEpochMs }
            .takeLast(8)
        if (closed.size < 5) return KillSwitchAssessment(true, "OK", "Kill-switch checks passed.")
        val losers = closed.filter { (it.realizedPnlEur.toDoubleOrNull() ?: 0.0) < 0.0 }
        if (losers.size < 5) return KillSwitchAssessment(true, "OK", "Kill-switch checks passed.")
        val meaningful = losers.filter(::isMeaningfulLoss)
        val materialDaily = dailyLimit > 0.0 && realizedToday <= -(dailyLimit * 0.50)
        if (materialDaily) {
            return KillSwitchAssessment(false, "HIGH", "Account-wide recent-loss kill-switch: realized_today €%.2f reached 50%% of daily loss budget.".format(realizedToday))
        }

        val symbol = decision.symbol.uppercase().replace("/", "").replace("-", "")
        val sameSymbol = meaningful.count { it.symbol.uppercase().replace("/", "").replace("-", "") == symbol }
        val highQuality = decision.finalScore >= 82 && decision.confidencePercent >= 66
        if (sameSymbol >= 2 && !highQuality) {
            return KillSwitchAssessment(false, "MEDIUM", "Symbol recent-loss guard: $symbol has $sameSymbol meaningful recent losses; high-quality candidate required.")
        }
        if (meaningful.size >= 5 && !highQuality) {
            return KillSwitchAssessment(false, "HIGH", "Broad recent-loss kill-switch: ${meaningful.size}/${closed.size} recent exits were meaningful losses.")
        }
        return if (highQuality && losers.size >= 5) {
            KillSwitchAssessment(true, "WARN", "Recent-loss warning overridden by high-quality candidate: score=${decision.finalScore}, confidence=${decision.confidencePercent}%; losses=${losers.size}/${closed.size}, meaningful=${meaningful.size}.")
        } else {
            KillSwitchAssessment(true, "WARN", "Recent losses are mostly small: ${losers.size}/${closed.size} exits lost, ${meaningful.size} meaningful; scoped guards passed.")
        }
    }
}
