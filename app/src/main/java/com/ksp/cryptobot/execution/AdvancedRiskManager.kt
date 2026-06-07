package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.AppDao
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

class AdvancedRiskManager(private val dao: AppDao) {
    suspend fun riskState(settings: BotSettings): AdvancedRiskState {
        val zone = ZoneId.systemDefault()
        val now = LocalDate.now(zone)
        val dayStart = now.atStartOfDay(zone).toInstant().toEpochMilli()
        val weekStart = now.minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = now.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val today = dao.tradesBetween(dayStart, end)
        val week = dao.tradesBetween(weekStart, end)
        val dailyLoss = today.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }.filter { it < BigDecimal.ZERO }.fold(BigDecimal.ZERO) { a, b -> a + b.abs() }
        val weeklyLoss = week.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }.filter { it < BigDecimal.ZERO }.fold(BigDecimal.ZERO) { a, b -> a + b.abs() }
        val consecutiveLosses = week.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }.takeLast(5).reversed().takeWhile { it < BigDecimal.ZERO }.size
        if (dailyLoss >= settings.maxDailyLossEur) {
            return AdvancedRiskState(false, AutomationLockReason.DAILY_LOSS, dailyLoss, weeklyLoss, BigDecimal.ZERO, consecutiveLosses, 0, "Daily loss limit reached: €$dailyLoss.")
        }
        if (weeklyLoss >= settings.maxWeeklyLossEur) {
            return AdvancedRiskState(false, AutomationLockReason.WEEKLY_LOSS, dailyLoss, weeklyLoss, BigDecimal.ZERO, consecutiveLosses, 0, "Weekly loss limit reached: €$weeklyLoss.")
        }
        if (consecutiveLosses >= 3) {
            return AdvancedRiskState(false, AutomationLockReason.DRAWDOWN, dailyLoss, weeklyLoss, BigDecimal.ZERO, consecutiveLosses, 0, "Three consecutive losing trades; cooldown required.")
        }
        return AdvancedRiskState(true, AutomationLockReason.NONE, dailyLoss, weeklyLoss, BigDecimal.ZERO, consecutiveLosses, 0, "Advanced risk state passed.")
    }
}
