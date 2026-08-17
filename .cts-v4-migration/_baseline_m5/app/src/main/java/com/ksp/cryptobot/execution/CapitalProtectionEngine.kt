package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.data.TradeEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

/** Desktop-v1.0.50-style capital protection ladder, expressed through existing Android risk settings. */
class CapitalProtectionEngine {
    fun evaluate(settings: BotSettings, recentTrades: List<TradeEntity>, mode: String): CapitalProtectionDecision {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        val realizedToday = recentTrades.asSequence()
            .filter { it.timestampEpochMs >= start && it.side.equals("SELL", ignoreCase = true) }
            .mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }
            .fold(BigDecimal.ZERO, BigDecimal::add)
        val loss = realizedToday.min(BigDecimal.ZERO).abs()
        val limit = settings.maxDailyLossEur.max(BigDecimal("0.01"))
        val ratio = loss.divide(limit, 8, RoundingMode.HALF_UP)
        val live = mode.equals("LIVE", ignoreCase = true) || settings.mode == BotMode.LIVE_AUTO
        return when {
            ratio >= BigDecimal.ONE -> CapitalProtectionDecision(4, false, BigDecimal.ZERO, realizedToday,
                "capital protection level 4: daily loss limit exhausted; manage exits only")
            live && ratio >= BigDecimal("0.75") -> CapitalProtectionDecision(3, false, BigDecimal.ZERO, realizedToday,
                "capital protection level 3: >=75% of daily loss budget used; block new live entries")
            ratio >= BigDecimal("0.50") -> CapitalProtectionDecision(2, true, BigDecimal("0.50"), realizedToday,
                "capital protection level 2: >=50% of daily loss budget used; reduce size 50%")
            ratio >= BigDecimal("0.25") -> CapitalProtectionDecision(1, true, BigDecimal("0.75"), realizedToday,
                "capital protection level 1: >=25% of daily loss budget used; reduce size 25%")
            else -> CapitalProtectionDecision(0, true, BigDecimal.ONE, realizedToday,
                "capital protection level 0: normal sizing")
        }
    }
}
