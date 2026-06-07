package com.ksp.cryptobot.autonomous

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.ksp.cryptobot.core.AiDecision
import com.ksp.cryptobot.core.AutonomousSymbolAssessment
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.RemoteCommandResult
import com.ksp.cryptobot.core.SignalAction
import com.ksp.cryptobot.core.StrategyMode
import com.ksp.cryptobot.core.TaxExportSummary
import com.ksp.cryptobot.core.TradeReplaySnapshot
import com.ksp.cryptobot.data.TaxReportEntity
import com.ksp.cryptobot.data.TradeEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max

/**
 * v1.2 autonomous layer.
 *
 * This class is intentionally deterministic and explainable. It does not bypass any live-order
 * safety gate. It only adds symbol-level strategy selection, bad-symbol suppression, shadow-paper
 * comparison, replay explanations, tax export helpers, portfolio reserve checks and remote-command
 * parsing that the controller/UI can surface.
 */
class AutonomousIntelligencePack(private val context: Context? = null) {

    fun assessSymbol(symbol: String, recentTrades: List<TradeEntity>, settings: BotSettings): AutonomousSymbolAssessment {
        val symbolTrades = recentTrades.filter { it.symbol.equals(symbol, ignoreCase = true) }.take(settings.optimizerLookbackTrades.coerceAtLeast(5))
        if (symbolTrades.isEmpty()) {
            return AutonomousSymbolAssessment(
                symbol = symbol,
                allowed = true,
                selectedStrategy = StrategyMode.AUTO,
                winRatePercent = BigDecimal.ZERO,
                profitFactor = BigDecimal("1.00"),
                disableReason = "No historical trades yet; symbol is allowed and will be learned.",
                optimizerHint = "Start with AUTO strategy and conservative position size."
            )
        }

        val wins = symbolTrades.count { it.realizedPnlEur.toBigDecimalOrNull()?.let { pnl -> pnl > BigDecimal.ZERO } == true }
        val losses = symbolTrades.count { it.realizedPnlEur.toBigDecimalOrNull()?.let { pnl -> pnl < BigDecimal.ZERO } == true }
        val winRate = BigDecimal(wins * 100).divide(BigDecimal(symbolTrades.size), 2, RoundingMode.HALF_UP)
        val grossWins = symbolTrades.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }.filter { it > BigDecimal.ZERO }.fold(BigDecimal.ZERO, BigDecimal::add)
        val grossLossesAbs = symbolTrades.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }.filter { it < BigDecimal.ZERO }.fold(BigDecimal.ZERO) { acc, v -> acc.add(v.abs()) }
        val profitFactor = if (grossLossesAbs > BigDecimal.ZERO) grossWins.divide(grossLossesAbs, 2, RoundingMode.HALF_UP) else BigDecimal("9.99")
        val badByWinRate = winRate < BigDecimal(settings.minSymbolWinRatePercent)
        val badByProfit = profitFactor < settings.minSymbolProfitFactor
        val allowed = !(settings.autoDisableBadSymbolsEnabled && symbolTrades.size >= 5 && (badByWinRate || badByProfit))
        val selectedStrategy = when {
            !settings.autonomousStrategyPerSymbolEnabled -> settings.strategyMode
            winRate >= BigDecimal("60") && profitFactor >= BigDecimal("1.25") -> StrategyMode.TREND
            losses >= wins && symbolTrades.size >= 5 -> StrategyMode.SCALPING
            symbol.uppercase().startsWith("BTC") || symbol.uppercase().startsWith("ETH") -> StrategyMode.AUTO
            else -> StrategyMode.BREAKOUT
        }
        val reason = if (allowed) {
            "Allowed. Recent winRate=$winRate%, profitFactor=$profitFactor."
        } else {
            "Auto-disabled for ${settings.badSymbolDisableHours}h logic: winRate=$winRate%, profitFactor=$profitFactor."
        }
        val hint = when (selectedStrategy) {
            StrategyMode.TREND -> "Optimizer selected TREND because recent outcomes are stable."
            StrategyMode.SCALPING -> "Optimizer selected SCALPING with tighter risk because recent performance is weak."
            StrategyMode.BREAKOUT -> "Optimizer selected BREAKOUT for altcoin-style volatility."
            StrategyMode.REVERSAL -> "Optimizer selected REVERSAL."
            StrategyMode.NEWS_MOMENTUM -> "Optimizer selected NEWS_MOMENTUM."
            StrategyMode.AUTO -> "Optimizer left strategy in AUTO."
        }
        return AutonomousSymbolAssessment(symbol, allowed, selectedStrategy, winRate, profitFactor, reason, hint)
    }

    fun enrichDecision(decision: AiDecision, ticker: MarketTicker, settings: BotSettings, assessment: AutonomousSymbolAssessment): AiDecision {
        if (!settings.selfOptimizationEnabled) return decision
        var score = decision.finalScore
        val notes = mutableListOf<String>()
        if (!assessment.allowed) {
            notes += "v1.2 auto-disable blocked symbol: ${assessment.disableReason}"
            return decision.copy(allowedToTrade = false, explanation = decision.explanation + " | " + notes.joinToString(" | "))
        }
        if (assessment.profitFactor >= BigDecimal("1.25")) {
            score += 3
            notes += "v1.2 memory boost: profitFactor=${assessment.profitFactor}"
        }
        if (assessment.winRatePercent < BigDecimal("45") && assessment.winRatePercent > BigDecimal.ZERO) {
            score -= 5
            notes += "v1.2 symbol penalty: winRate=${assessment.winRatePercent}%"
        }
        val spreadPct = if (ticker.lastPrice > BigDecimal.ZERO) ticker.ask.subtract(ticker.bid).divide(ticker.lastPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal.ZERO
        if (spreadPct > settings.maxSpreadPercent.divide(BigDecimal("2"), 4, RoundingMode.HALF_UP)) {
            score -= 3
            notes += "v1.2 spread penalty: spread=${spreadPct.setScale(3, RoundingMode.HALF_UP)}%"
        }
        if (assessment.selectedStrategy != StrategyMode.AUTO) notes += "v1.2 per-symbol strategy=${assessment.selectedStrategy}"
        val bounded = score.coerceIn(0, 100)
        return decision.copy(
            finalScore = bounded,
            confidencePercent = bounded,
            allowedToTrade = decision.allowedToTrade && assessment.allowed,
            explanation = decision.explanation + " | " + notes.joinToString(" | ")
        )
    }

    fun buildTradeReplay(decision: AiDecision, ticker: MarketTicker, settings: BotSettings): TradeReplaySnapshot {
        val mirror = if (!settings.shadowPaperComparisonEnabled) {
            "Shadow paper comparison disabled."
        } else {
            when (decision.finalAction) {
                SignalAction.BUY, SignalAction.SMALL_BUY -> "Shadow path: compare current entry against fixed TP, smart trailing TP, and bearish-AI exit after this signal."
                SignalAction.SELL -> "Shadow path: compare immediate SELL against holding with trailing profit-lock."
                else -> "Shadow path: no live trade; record skipped-signal outcome for future optimizer."
            }
        }
        return TradeReplaySnapshot(
            symbol = decision.symbol,
            action = decision.finalAction,
            score = decision.finalScore,
            reason = "price=${ticker.lastPrice}, bid=${ticker.bid}, ask=${ticker.ask}, explanation=${decision.explanation.take(220)}",
            mirrorExitComparison = mirror
        )
    }

    fun portfolioReserveWarning(totalEur: BigDecimal, freeEur: BigDecimal, settings: BotSettings): String {
        if (!settings.portfolioReserveManagerV12Enabled || totalEur <= BigDecimal.ZERO) return "Portfolio reserve manager disabled or no portfolio value loaded."
        val minReserve = totalEur.multiply(settings.minimumEurReservePercent).divide(BigDecimal("100"), 2, RoundingMode.HALF_UP)
        return if (freeEur < minReserve) {
            "Reserve warning: free EUR €${freeEur.setScale(2, RoundingMode.DOWN)} is below target reserve €${minReserve.setScale(2, RoundingMode.DOWN)}. New buys should be reduced or blocked."
        } else {
            "Reserve OK: free EUR €${freeEur.setScale(2, RoundingMode.DOWN)} meets target reserve €${minReserve.setScale(2, RoundingMode.DOWN)}."
        }
    }

    fun watchdogLines(settings: BotSettings): List<String> {
        val lines = mutableListOf<String>()
        if (!settings.crashRecoveryWatchdogV12Enabled) return listOf("v1.2 watchdog disabled.")
        lines += "v1.2 watchdog enabled: service heartbeat, battery guard and API-error lockout are active in diagnostics."
        val batteryPct = readBatteryPercent()
        if (batteryPct != null) {
            if (batteryPct <= settings.pauseBelowBatteryPercent) lines += "BLOCK: battery $batteryPct% <= ${settings.pauseBelowBatteryPercent}%." else lines += "OK: battery $batteryPct%."
        } else lines += "WARN: battery percentage unavailable from Android system."
        return lines
    }

    private fun readBatteryPercent(): Int? {
        val ctx = context ?: return null
        val intent = ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return ((level * 100f) / scale).toInt()
    }

    fun parseRemoteCommand(raw: String, settings: BotSettings): RemoteCommandResult {
        if (!settings.remoteCommandParserEnabled) return RemoteCommandResult(false, raw, "Remote command parser disabled.")
        val normalized = raw.trim().lowercase()
        return when (normalized) {
            "/status" -> RemoteCommandResult(true, raw, "Status command accepted: return bot mode, provider, risk state and last heartbeat.")
            "/pause" -> RemoteCommandResult(true, raw, "Pause command accepted: caller should stop foreground service.")
            "/resume" -> RemoteCommandResult(true, raw, "Resume command accepted: caller should start foreground service.")
            "/orders" -> RemoteCommandResult(true, raw, "Orders command accepted: caller should sync Kraken open orders.")
            "/positions" -> RemoteCommandResult(true, raw, "Positions command accepted: caller should sync live positions.")
            "/profit" -> RemoteCommandResult(true, raw, "Profit command accepted: caller should return local P/L summary.")
            "/kill" -> RemoteCommandResult(true, raw, "Emergency kill accepted: caller should stop service and disable LIVE_AUTO until manually restarted.")
            else -> RemoteCommandResult(false, raw, "Unknown command. Supported: /status /pause /resume /orders /positions /profit /kill")
        }
    }

    fun exportBelgianTaxCsv(year: Int, rows: List<TaxReportEntity>, fallbackTrades: List<TradeEntity>): TaxExportSummary {
        val header = "date_iso,symbol,side,quantity,price_eur,fee_eur,realized_gain_eur,note"
        val dtf = DateTimeFormatter.ISO_INSTANT
        val csvRows = if (rows.isNotEmpty()) {
            rows.map { row ->
                listOf(
                    dtf.format(Instant.ofEpochMilli(row.timestampEpochMs)), row.symbol, row.side, row.quantity, row.priceEur, row.feeEur, row.realizedGainEur, row.note
                ).joinToString(",") { it.csvEscape() }
            }
        } else {
            fallbackTrades.map { trade ->
                listOf(
                    dtf.format(Instant.ofEpochMilli(trade.timestampEpochMs)), trade.symbol, trade.side, trade.quantity, trade.priceEur, trade.feeEur, trade.realizedPnlEur, "fallback_trade_row"
                ).joinToString(",") { it.csvEscape() }
            }
        }
        val realized = (if (rows.isNotEmpty()) rows.mapNotNull { it.realizedGainEur.toBigDecimalOrNull() } else fallbackTrades.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() })
            .fold(BigDecimal.ZERO, BigDecimal::add)
        return TaxExportSummary(year, csvRows.size, realized, (listOf(header) + csvRows).joinToString("\n"))
    }

    private fun String.csvEscape(): String {
        val clean = replace("\n", " ").replace("\r", " ")
        return if (clean.contains(',') || clean.contains('"')) "\"${clean.replace("\"", "\"\"")}\"" else clean
    }
}
