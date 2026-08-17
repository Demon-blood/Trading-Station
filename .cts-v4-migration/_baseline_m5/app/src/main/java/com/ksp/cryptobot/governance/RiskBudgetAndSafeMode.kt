package com.ksp.cryptobot.governance

import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.data.GovernanceEventEntity
import com.ksp.cryptobot.data.TradeEntity
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

class RiskBudgetManager {
    fun evaluate(settings: BotSettings, recentTrades: List<TradeEntity>, requestedQuoteEur: Double = 0.0): RiskBudgetAssessment {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val todayPnl = recentTrades.asSequence()
            .filter { it.timestampEpochMs >= start && it.side.equals("SELL", ignoreCase = true) }
            .sumOf { it.realizedPnlEur.toDoubleOrNull() ?: 0.0 }
        val configuredLossLimit = abs(settings.maxDailyLossEur.toDouble())
        val dailyBudget = if (configuredLossLimit > 0.0) minOf(configuredLossLimit, 10.0) else 10.0
        val used = maxOf(0.0, -todayPnl)
        val remaining = maxOf(0.0, dailyBudget - used)
        if (remaining <= 0.0) {
            return RiskBudgetAssessment(0.0, true, used, remaining, "daily risk budget exhausted: used €%.2f/€%.2f".format(used, dailyBudget))
        }
        val multiplier = (remaining / maxOf(1.0, dailyBudget)).coerceIn(0.10, 1.0)
        return RiskBudgetAssessment(multiplier, false, used, remaining, "daily risk budget remaining €%.2f/€%.2f; size multiplier %.2f; requested €%.2f".format(remaining, dailyBudget, multiplier, requestedQuoteEur))
    }
}

class SafeModeController {
    fun evaluate(
        settings: BotSettings,
        recentEvents: List<GovernanceEventEntity>,
        realizedToday: Double,
        anomaly: AnomalyAssessment
    ): SafeModeAssessment {
        val modeLive = settings.mode == BotMode.LIVE_AUTO || settings.mode == BotMode.LIVE_CONFIRM
        val dailyLimit = abs(settings.maxDailyLossEur.toDouble())
        val recentBad = recentEvents.take(80).count {
            it.eventType in setOf("anomaly_event", "watchdog_error", "order_error") || it.severity in setOf("HIGH", "CRITICAL")
        }
        return when {
            modeLive && dailyLimit > 0.0 && realizedToday <= -dailyLimit ->
                SafeModeAssessment("PAUSED", "live daily loss limit reached; real new entries paused", -10, 0.0, true)
            recentBad >= 6 ->
                SafeModeAssessment("PAPER_ONLY", "multiple recent errors/anomalies", -8, 0.0, true)
            !anomaly.allowed && anomaly.severity in setOf("HIGH", "CRITICAL") ->
                SafeModeAssessment("CONSERVATIVE", "market-data anomaly detected", -6, 0.45, false)
            else -> SafeModeAssessment("NORMAL", "normal", 0, 1.0, false)
        }
    }
}
