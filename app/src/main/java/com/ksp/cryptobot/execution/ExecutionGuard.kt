package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.AppDao
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

class ExecutionGuard(private val dao: AppDao) {
    suspend fun canExecute(settings: BotSettings, decision: AiDecision): Pair<Boolean, String> {
        if (settings.mode == BotMode.PAPER) return true to "Paper execution allowed."
        if (!settings.liveTradingAcknowledged) return false to "Live trading is blocked until explicit acknowledgement is enabled."
        if (!decision.allowedToTrade) return false to "AI decision is not tradable: ${decision.finalAction}."
        if (decision.confidencePercent < 65) return false to "AI confidence too low: ${decision.confidencePercent}%."

        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val todaysTrades = dao.tradesBetween(start, end)
        if (todaysTrades.size >= settings.maxTradesPerDay) {
            return false to "Daily trade limit reached: ${todaysTrades.size}/${settings.maxTradesPerDay}."
        }

        val realizedLoss = todaysTrades.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }
            .filter { it < BigDecimal.ZERO }
            .fold(BigDecimal.ZERO) { acc, pnl -> acc + pnl.abs() }
        if (realizedLoss >= settings.maxDailyLossEur) {
            return false to "Daily loss guard active: €$realizedLoss >= €${settings.maxDailyLossEur}."
        }

        val last = dao.lastTradeForSymbol(decision.symbol)
        if (last != null && abs(System.currentTimeMillis() - last.timestampEpochMs) < 60 * 60 * 1000) {
            return false to "Duplicate-order guard: ${decision.symbol} traded less than 1 hour ago."
        }
        return true to "Execution guards passed."
    }
}
