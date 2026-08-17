package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.AppDao
import com.ksp.cryptobot.governance.ProductionIntelligenceRuntime
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

class ExecutionGuard(private val dao: AppDao) {
    suspend fun canExecute(settings: BotSettings, decision: AiDecision): Pair<Boolean, String> {
        // Decision validity must be checked in every mode, including PAPER. The previous
        // implementation returned true for PAPER before checking allowedToTrade.
        if (!decision.allowedToTrade) return false to "AI/production decision is not tradable: ${decision.finalAction}."
        if (decision.finalAction !in setOf(SignalAction.BUY, SignalAction.SMALL_BUY, SignalAction.SELL)) {
            return false to "Decision action ${decision.finalAction} is not executable."
        }
        if (decision.confidencePercent < 65) return false to "AI confidence too low: ${decision.confidencePercent}%."

        val production = ProductionIntelligenceRuntime.snapshot()
        if (settings.mode != BotMode.PAPER && production.blockLiveEntries) {
            return false to "Production intelligence blocked live entries: safeMode=${production.safeModeLevel}; ${production.lastReason.take(500)}"
        }
        if (settings.mode == BotMode.PAPER) return true to "Paper execution allowed after decision/production guards."
        if (!settings.liveTradingAcknowledged) return false to "Live trading is blocked until explicit acknowledgement is enabled."

        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val todaysTrades = dao.tradesBetween(start, end)
        if (settings.maxTradesPerDay > 0 && todaysTrades.size >= settings.maxTradesPerDay) {
            return false to "Daily trade limit reached: ${todaysTrades.size}/${settings.maxTradesPerDay}."
        }

        val oneHourAgo = now - 60L * 60L * 1000L
        val tradesLastHour = dao.tradesBetween(oneHourAgo, now)
        if (tradesLastHour.size >= settings.maxTradesPerHour) {
            return false to "Hourly trade limit reached: ${tradesLastHour.size}/${settings.maxTradesPerHour}."
        }

        val realizedLoss = todaysTrades.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }
            .filter { it < BigDecimal.ZERO }
            .fold(BigDecimal.ZERO) { acc, pnl -> acc + pnl.abs() }
        if (realizedLoss >= settings.maxDailyLossEur) {
            return false to "Daily loss guard active: €$realizedLoss >= €${settings.maxDailyLossEur}."
        }

        val last = dao.lastTradeForSymbol(decision.symbol)
        if (last != null) {
            val ageMs = abs(now - last.timestampEpochMs)
            val sideCooldownMinutes = if (last.side.equals("BUY", ignoreCase = true)) settings.cooldownAfterBuyMinutes else settings.cooldownAfterSellMinutes
            val lossCooldownMinutes = if ((last.realizedPnlEur.toBigDecimalOrNull() ?: BigDecimal.ZERO) < BigDecimal.ZERO) settings.cooldownAfterLossMinutes else 0
            val cooldownMinutes = maxOf(sideCooldownMinutes, lossCooldownMinutes)
            if (cooldownMinutes > 0 && ageMs < cooldownMinutes * 60L * 1000L) {
                val remaining = cooldownMinutes - (ageMs / 60000L)
                return false to "Symbol cooldown active for ${decision.symbol}: ${remaining.coerceAtLeast(1)} minute(s) remaining after recent ${last.side}${if (lossCooldownMinutes > 0) " loss" else ""}."
            }
        }
        return true to "Execution guards passed."
    }
}
