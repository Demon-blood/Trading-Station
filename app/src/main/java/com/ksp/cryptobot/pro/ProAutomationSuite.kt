package com.ksp.cryptobot.pro

import android.content.Context
import android.os.BatteryManager
import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.TradeEntity
import com.ksp.cryptobot.exchange.CryptoExchangeClient
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.max

/**
 * v1.1 professional automation layer.
 *
 * This file intentionally keeps the implementations deterministic/explainable instead of hiding
 * live decisions inside an opaque model. The goal is to make automatic trading safer, auditable
 * and easier to debug from Live Status.
 */

data class ProReadinessReport(
    val allowed: Boolean,
    val level: String,
    val lines: List<String>
)

data class NetProfitCheck(
    val allowed: Boolean,
    val expectedMovePercent: BigDecimal,
    val estimatedCostPercent: BigDecimal,
    val minimumRequiredMovePercent: BigDecimal,
    val reason: String
)

data class SymbolRotationScore(
    val symbol: String,
    val score: Int,
    val liquidityScore: Int,
    val spreadScore: Int,
    val momentumScore: Int,
    val memoryScore: Int,
    val reason: String
)

data class ProfitLockPlan(
    val shouldExit: Boolean,
    val shouldPartialExit: Boolean,
    val exitPercent: BigDecimal,
    val stopPrice: BigDecimal,
    val trailingStopPrice: BigDecimal,
    val takeProfitOnePrice: BigDecimal,
    val takeProfitTwoPrice: BigDecimal,
    val reason: String
)

data class OptimizedStrategyParams(
    val symbol: String,
    val emaFast: Int,
    val emaSlow: Int,
    val takeProfitAtrMultiplier: BigDecimal,
    val stopLossAtrMultiplier: BigDecimal,
    val estimatedScore: Int,
    val reason: String
)

data class RemoteCommandResult(
    val accepted: Boolean,
    val command: String,
    val response: String
)

class ProAutomationSuite(private val context: Context? = null) {
    private val assumedKrakenTakerFeePercent = BigDecimal("0.40")
    private val assumedKrakenMakerFeePercent = BigDecimal("0.25")
    private val minimumNetEdgePercent = BigDecimal("0.20")

    fun readiness(settings: BotSettings): ProReadinessReport {
        val lines = mutableListOf<String>()
        var allowed = true
        fun add(ok: Boolean, text: String) {
            if (!ok) allowed = false
            lines += "${if (ok) "OK" else "BLOCK"}: $text"
        }
        add(settings.mode == BotMode.LIVE_AUTO || settings.mode == BotMode.PAPER, "Mode is ${settings.mode}; automatic loop can run")
        add(settings.maxPositionEur >= BigDecimal("5.00"), "Max position is at least practical Kraken minimum")
        add(settings.maxTradesPerDay >= 0, if (settings.maxTradesPerDay == 0) "Daily trade limit is unlimited" else "Daily trade limit configured")
        add(settings.symbols().isNotEmpty(), "Symbol list is not empty")
        add(settings.mode == BotMode.PAPER || settings.liveTradingAcknowledged, "Live acknowledgement present or paper mode active")
        add(!settings.manualExecutionMode, "Manual execution mode is OFF")
        add(settings.maxDailyLossEur > BigDecimal.ZERO && settings.maxWeeklyLossEur > BigDecimal.ZERO, "Daily/weekly loss guards are configured")
        context?.let {
            val batteryOk = batteryPercent(it) >= settings.pauseBelowBatteryPercent
            add(batteryOk, "Battery above ${settings.pauseBelowBatteryPercent}% watchdog threshold")
        }
        return ProReadinessReport(allowed, if (allowed) "READY" else "BLOCKED", lines)
    }

    fun rankSymbol(ticker: MarketTicker, recentTrades: List<TradeEntity>): SymbolRotationScore {
        val spreadPercent = if (ticker.lastPrice > BigDecimal.ZERO) {
            ticker.ask.subtract(ticker.bid).divide(ticker.lastPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
        } else BigDecimal("99")
        val spreadScore = (100 - spreadPercent.multiply(BigDecimal("180")).toInt()).coerceIn(0, 100)
        val volumeScore = when {
            ticker.volume24h >= BigDecimal("100000000") -> 100
            ticker.volume24h >= BigDecimal("25000000") -> 85
            ticker.volume24h >= BigDecimal("5000000") -> 70
            ticker.volume24h >= BigDecimal("1000000") -> 55
            else -> 25
        }
        val momentum = ticker.priceChangePercent24h
        val momentumScore = when {
            momentum > BigDecimal("8") -> 65
            momentum > BigDecimal("2") -> 90
            momentum > BigDecimal("0") -> 75
            momentum > BigDecimal("-3") -> 55
            else -> 20
        }
        val symbolTrades = recentTrades.filter { it.symbol.equals(ticker.symbol, ignoreCase = true) }.take(30)
        val memoryScore = if (symbolTrades.isEmpty()) 60 else {
            val winners = symbolTrades.count { (it.realizedPnlEur.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO }
            ((winners.toDouble() / symbolTrades.size.toDouble()) * 100.0).toInt().coerceIn(0, 100)
        }
        val total = (volumeScore * 0.30 + spreadScore * 0.30 + momentumScore * 0.25 + memoryScore * 0.15).toInt().coerceIn(0, 100)
        return SymbolRotationScore(
            symbol = ticker.symbol,
            score = total,
            liquidityScore = volumeScore,
            spreadScore = spreadScore,
            momentumScore = momentumScore,
            memoryScore = memoryScore,
            reason = "Liquidity=$volumeScore, spread=$spreadScore, momentum=$momentumScore, memory=$memoryScore, 24h=${momentum.setScale(2, RoundingMode.HALF_UP)}%"
        )
    }

    fun netProfitCheck(ticker: MarketTicker, decision: AiDecision, settings: BotSettings): NetProfitCheck {
        val spreadPercent = if (ticker.lastPrice > BigDecimal.ZERO) {
            ticker.ask.subtract(ticker.bid).divide(ticker.lastPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
        } else BigDecimal("99")
        val feePercent = if (settings.enableMarketOrders) assumedKrakenTakerFeePercent else assumedKrakenMakerFeePercent
        val slippageReserve = if (settings.enableMarketOrders) settings.marketOrderSlippageWarningPercent.divide(BigDecimal("2"), 4, RoundingMode.HALF_UP) else BigDecimal("0.05")
        val estimatedCost = spreadPercent.add(feePercent).add(slippageReserve)
        val expectedMove = when (decision.finalAction) {
            SignalAction.BUY, SignalAction.SMALL_BUY -> BigDecimal(decision.finalScore).divide(BigDecimal("25"), 2, RoundingMode.HALF_UP)
            SignalAction.SELL -> BigDecimal(decision.finalScore).divide(BigDecimal("35"), 2, RoundingMode.HALF_UP)
            else -> BigDecimal.ZERO
        }
        val minimumRequired = estimatedCost.add(minimumNetEdgePercent)
        val allowed = !settings.enableNetProfitFilter || expectedMove >= minimumRequired
        return NetProfitCheck(
            allowed = allowed,
            expectedMovePercent = expectedMove,
            estimatedCostPercent = estimatedCost,
            minimumRequiredMovePercent = minimumRequired,
            reason = if (allowed) {
                "Net-profit filter passed: expectedMove≈${expectedMove}% vs cost+edge≈${minimumRequired}%"
            } else {
                "Trade blocked by net-profit filter: expectedMove≈${expectedMove}% < cost+edge≈${minimumRequired}%"
            }
        )
    }

    fun explainTrade(ticker: MarketTicker, decision: AiDecision, ranking: SymbolRotationScore?, netCheck: NetProfitCheck): String {
        return buildString {
            append("Why decision for ${ticker.symbol}: ${decision.finalAction} score=${decision.finalScore}. ")
            append("AI: tech=${decision.technicalScore}, news=${decision.newsScore}, memory=${decision.memoryScore}. ")
            ranking?.let { append("Rotation: ${it.reason}. ") }
            append("Costs: ${netCheck.reason}. ")
            append("Ticker bid=${ticker.bid}, ask=${ticker.ask}, last=${ticker.lastPrice}, vol24h≈${ticker.volume24h.setScale(0, RoundingMode.HALF_UP)} EUR.")
        }
    }

    fun profitLockPlan(position: PositionInfo, settings: BotSettings): ProfitLockPlan {
        val entry = position.entryPrice.takeIf { it > BigDecimal.ZERO } ?: position.currentPrice
        val current = position.currentPrice
        val high = position.highestPrice.max(current)
        val pnlPct = if (entry > BigDecimal.ZERO) current.subtract(entry).divide(entry, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal.ZERO
        val highPnlPct = if (entry > BigDecimal.ZERO) high.subtract(entry).divide(entry, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal.ZERO
        val stop = entry.multiply(BigDecimal.ONE.subtract(settings.stopLossPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP))).setScale(8, RoundingMode.HALF_UP)
        val tp1 = entry.multiply(BigDecimal.ONE.add(settings.takeProfitPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP))).setScale(8, RoundingMode.HALF_UP)
        val tp2 = entry.multiply(BigDecimal.ONE.add(settings.takeProfitPercent.multiply(BigDecimal("2")).divide(BigDecimal("100"), 8, RoundingMode.HALF_UP))).setScale(8, RoundingMode.HALF_UP)
        val trailing = high.multiply(BigDecimal.ONE.subtract(settings.smartProfitLockTrailingDistancePercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP))).setScale(8, RoundingMode.HALF_UP)
        val trailActive = highPnlPct >= settings.smartProfitLockActivationPercent
        val partial = settings.smartProfitLockEnabled && pnlPct >= settings.smartProfitLockPartialTakeProfitPercent
        val shouldExit = current <= stop || (trailActive && current <= trailing)
        val exitPercent = when {
            shouldExit -> BigDecimal("100")
            partial -> settings.smartProfitLockPartialExitPercent
            else -> BigDecimal.ZERO
        }
        val reason = when {
            current <= stop -> "Stop-loss protection triggered."
            trailActive && current <= trailing -> "Dynamic trailing profit-lock triggered after high-water profit ${highPnlPct.setScale(2, RoundingMode.HALF_UP)}%."
            partial -> "Partial profit-lock eligible at pnl ${pnlPct.setScale(2, RoundingMode.HALF_UP)}%."
            else -> "Hold: profit-lock active, no exit trigger yet. pnl=${pnlPct.setScale(2, RoundingMode.HALF_UP)}%, high=${highPnlPct.setScale(2, RoundingMode.HALF_UP)}%."
        }
        return ProfitLockPlan(shouldExit, partial, exitPercent, stop, trailing, tp1, tp2, reason)
    }

    fun optimize(symbol: String, candles: List<Candle>): OptimizedStrategyParams {
        if (candles.size < 60) {
            return OptimizedStrategyParams(symbol, 9, 21, BigDecimal("1.4"), BigDecimal("1.0"), 50, "Not enough candles for optimizer; using safe defaults.")
        }
        val candidates = listOf(7 to 21, 9 to 21, 9 to 26, 12 to 26, 12 to 50)
        val scored = candidates.map { (fast, slow) ->
            val recent = candles.takeLast(50)
            val early = recent.first().close
            val late = recent.last().close
            val trend = if (early > BigDecimal.ZERO) late.subtract(early).divide(early, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal.ZERO
            val volatility = recent.map { it.high.subtract(it.low).abs() }.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(recent.size), 8, RoundingMode.HALF_UP)
            val score = (60 + trend.multiply(BigDecimal("4")).toInt() - volatility.multiply(BigDecimal("100")).toInt()).coerceIn(25, 95)
            Triple(fast, slow, score)
        }.maxByOrNull { it.third } ?: Triple(9, 21, 50)
        val tp = if (scored.third >= 75) BigDecimal("1.8") else BigDecimal("1.4")
        val sl = if (scored.third >= 75) BigDecimal("1.0") else BigDecimal("0.8")
        return OptimizedStrategyParams(symbol, scored.first, scored.second, tp, sl, scored.third, "Local optimizer selected EMA ${scored.first}/${scored.second} from recent Kraken candles.")
    }

    fun portfolioGuard(snapshot: PortfolioSnapshot, settings: BotSettings): List<String> {
        if (!settings.portfolioBalancerEnabled) return listOf("Portfolio balancer disabled.")
        val total = snapshot.totalValueEur
        if (total <= BigDecimal.ZERO) return listOf("Portfolio balancer: no live portfolio value.")
        val eurReservePct = snapshot.freeEur.divide(total, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
        val lines = mutableListOf("Portfolio reserve=${eurReservePct.setScale(2, RoundingMode.HALF_UP)}%, target reserve=${settings.minimumEurReservePercent}%")
        if (eurReservePct < settings.minimumEurReservePercent) lines += "Portfolio balancer blocks new BUY orders until EUR reserve recovers."
        snapshot.assets.filter { it.asset != "EUR" && it.asset != "ZEUR" && it.eurValue > BigDecimal.ZERO }.forEach { asset ->
            val pct = asset.eurValue.divide(total, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
            if (pct > settings.maxSingleAssetAllocationPercent) lines += "${asset.asset} allocation ${pct.setScale(1, RoundingMode.HALF_UP)}% exceeds max ${settings.maxSingleAssetAllocationPercent}%."
        }
        return lines
    }

    fun taxCsv(rows: List<com.ksp.cryptobot.data.TaxReportEntity>): String {
        val header = "timestampEpochMs,symbol,side,quantity,priceEur,feeEur,realizedGainEur,note"
        return buildString {
            appendLine(header)
            rows.forEach { r ->
                appendLine(listOf(r.timestampEpochMs, r.symbol, r.side, r.quantity, r.priceEur, r.feeEur, r.realizedGainEur, r.note.replace(',', ';')).joinToString(","))
            }
        }
    }

    fun remoteCommand(command: String, settings: BotSettings): RemoteCommandResult {
        val normalized = command.trim().lowercase()
        return when (normalized) {
            "/status" -> RemoteCommandResult(true, command, "Mode=${settings.mode}, provider=${settings.exchangeProvider}, symbols=${settings.symbolsCsv}")
            "/positions" -> RemoteCommandResult(true, command, "Open the Positions tab for live position details.")
            "/orders" -> RemoteCommandResult(true, command, "Open the Orders tab for live open order details.")
            "/kill" -> RemoteCommandResult(true, command, "Kill requested. Use the in-app Stop/Kill button to avoid unauthenticated remote shutdown.")
            else -> RemoteCommandResult(false, command, "Unknown command. Supported: /status, /positions, /orders, /kill")
        }
    }

    fun mirrorExitComparison(entryPrice: BigDecimal, currentPrice: BigDecimal, highPrice: BigDecimal, settings: BotSettings): List<String> {
        if (!settings.dryRunMirrorModeEnabled || entryPrice <= BigDecimal.ZERO) return emptyList()
        val pnl = currentPrice.subtract(entryPrice).divide(entryPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
        val highPnl = highPrice.subtract(entryPrice).divide(entryPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
        return listOf(
            "Mirror fixed TP would exit at +${settings.takeProfitPercent}%.",
            "Mirror trailing exit sees current pnl=${pnl.setScale(2, RoundingMode.HALF_UP)}%, high pnl=${highPnl.setScale(2, RoundingMode.HALF_UP)}%.",
            "Mirror AI exit stays armed for bearish decision score below ${settings.bearishAutoSellScore}."
        )
    }

    fun localAiScore(features: List<BigDecimal>): Int {
        if (features.isEmpty()) return 50
        val weighted = features.mapIndexed { idx, value -> value.multiply(BigDecimal((idx + 1).toString())) }
            .fold(BigDecimal.ZERO, BigDecimal::add)
        return weighted.abs().remainder(BigDecimal("100")).toInt().coerceIn(0, 100)
    }

    fun batteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return 100
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it >= 0 } ?: 100
    }
}
