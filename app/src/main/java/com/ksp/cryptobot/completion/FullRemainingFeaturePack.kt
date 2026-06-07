package com.ksp.cryptobot.completion

import android.content.Context
import android.os.BatteryManager
import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.TaxReportEntity
import com.ksp.cryptobot.data.TradeEntity
import com.ksp.cryptobot.exchange.CryptoExchangeClient
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * v1.6 completion pack.
 *
 * This module converts the remaining advanced ideas into concrete app-level services:
 * WebSocket market streaming, optimizer scoring, paper/live shadow comparison,
 * remote notifications/commands, watchdog diagnostics, tax CSV export, and
 * completion-level live feature status reporting.
 */
data class LiveCompletionStatus(
    val feature: String,
    val implemented: Boolean,
    val liveCapable: Boolean,
    val status: String,
    val notes: String
)

data class StrategyOptimizationResult(
    val symbol: String,
    val emaFast: Int,
    val emaSlow: Int,
    val takeProfitPercent: BigDecimal,
    val stopLossPercent: BigDecimal,
    val minimumScore: Int,
    val tradeCount: Int,
    val winRatePercent: BigDecimal,
    val profitFactor: BigDecimal,
    val maxDrawdownPercent: BigDecimal,
    val netReturnPercent: BigDecimal,
    val reason: String
)

data class ShadowComparisonResult(
    val symbol: String,
    val liveExitReason: String,
    val shadowFixedTpPercent: BigDecimal,
    val shadowTrailingPercent: BigDecimal,
    val shadowAiExitPercent: BigDecimal,
    val recommendedExitStyle: String,
    val note: String
)

data class RemoteNotificationConfig(
    val telegramBotToken: String = "",
    val telegramChatId: String = "",
    val discordWebhookUrl: String = ""
)

data class WatchdogReport(
    val allowed: Boolean,
    val severity: String,
    val lines: List<String>
)

class KrakenTickerWebSocketFeed(private val symbols: List<String>) {
    private val client = OkHttpClient.Builder().build()

    fun stream(): Flow<MarketTicker> = callbackFlow {
        val request = Request.Builder().url("wss://ws.kraken.com/v2").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                val krakenSymbols = symbols.map { toKrakenWsSymbol(it) }
                val msg = JSONObject()
                    .put("method", "subscribe")
                    .put("params", JSONObject().put("channel", "ticker").put("symbol", org.json.JSONArray(krakenSymbols)))
                    .toString()
                webSocket.send(msg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text)
                    if (root.optString("channel") != "ticker") return
                    val data = root.optJSONArray("data") ?: return
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i) ?: continue
                        val symbol = item.optString("symbol", "").replace("/", "")
                        val bid = item.optString("bid", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val ask = item.optString("ask", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val last = item.optString("last", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val volume = item.optString("volume", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        trySend(MarketTicker(symbol.replace("XBT", "BTC"), last, bid, ask, volume, BigDecimal.ZERO))
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                close(t)
            }
        })
        awaitClose { ws.close(1000, "app closed") }
    }

    private fun toKrakenWsSymbol(symbol: String): String {
        val clean = symbol.uppercase().replace("/", "").replace("EUR", "/EUR")
        return clean.replace("BTC/", "XBT/")
    }
}

class FullStrategyOptimizer {
    fun optimize(symbol: String, candles: List<Candle>, settings: BotSettings): StrategyOptimizationResult {
        if (candles.size < 80) {
            return StrategyOptimizationResult(
                symbol, settings.emaFastPeriod, settings.emaSlowPeriod, settings.takeProfitPercent,
                settings.stopLossPercent, settings.minStrategyScoreToBuy, 0, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                "Not enough candles for a full optimizer pass; keep current safe settings."
            )
        }
        val fasts = listOf(7, 9, 12)
        val slows = listOf(21, 26, 50)
        val tps = listOf(BigDecimal("1.2"), BigDecimal("1.6"), BigDecimal("2.0"), BigDecimal("2.8"))
        val sls = listOf(BigDecimal("0.8"), BigDecimal("1.0"), BigDecimal("1.3"))
        val scores = listOf(65, 70, 75, 80)
        return fasts.flatMap { f -> slows.filter { it > f }.flatMap { s -> tps.flatMap { tp -> sls.flatMap { sl -> scores.map { minScore -> simulate(symbol, candles, f, s, tp, sl, minScore) } } } } }
            .maxWith(compareBy<StrategyOptimizationResult> { it.profitFactor }.thenBy { it.netReturnPercent }.thenByDescending { it.maxDrawdownPercent })
    }

    private fun simulate(symbol: String, candles: List<Candle>, fast: Int, slow: Int, tp: BigDecimal, sl: BigDecimal, minScore: Int): StrategyOptimizationResult {
        var inTrade = false
        var entry = BigDecimal.ZERO
        var wins = 0
        var losses = 0
        var grossWin = BigDecimal.ZERO
        var grossLoss = BigDecimal.ZERO
        var equity = BigDecimal("100.0")
        var peak = equity
        var maxDd = BigDecimal.ZERO
        val closes = candles.map { it.close }
        for (i in slow until closes.size) {
            val fastAvg = closes.subList(i - fast, i).averageBig()
            val slowAvg = closes.subList(i - slow, i).averageBig()
            val price = closes[i]
            val bullishScore = if (fastAvg > slowAvg) minScore + 10 else minScore - 20
            if (!inTrade && bullishScore >= minScore) {
                inTrade = true
                entry = price
            } else if (inTrade) {
                val pnlPct = price.subtract(entry).divide(entry, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
                if (pnlPct >= tp || pnlPct <= sl.negate() || fastAvg < slowAvg) {
                    inTrade = false
                    equity = equity.add(pnlPct)
                    if (pnlPct >= BigDecimal.ZERO) { wins++; grossWin = grossWin.add(pnlPct) } else { losses++; grossLoss = grossLoss.add(pnlPct.abs()) }
                    if (equity > peak) peak = equity
                    val dd = peak.subtract(equity).divide(peak, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
                    if (dd > maxDd) maxDd = dd
                }
            }
        }
        val trades = wins + losses
        val winRate = if (trades == 0) BigDecimal.ZERO else BigDecimal(wins).divide(BigDecimal(trades), 4, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
        val pf = if (grossLoss == BigDecimal.ZERO) grossWin.max(BigDecimal.ONE) else grossWin.divide(grossLoss, 4, RoundingMode.HALF_UP)
        val net = equity.subtract(BigDecimal("100.0"))
        return StrategyOptimizationResult(symbol, fast, slow, tp, sl, minScore, trades, winRate, pf, maxDd, net, "Grid-tested EMA $fast/$slow TP=$tp% SL=$sl% minScore=$minScore on ${candles.size} candles.")
    }

    private fun List<BigDecimal>.averageBig(): BigDecimal = if (isEmpty()) BigDecimal.ZERO else fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(size), 8, RoundingMode.HALF_UP)
}

class ShadowPaperLiveComparator {
    fun compare(symbol: String, entryPrice: BigDecimal, highPrice: BigDecimal, currentPrice: BigDecimal, settings: BotSettings): ShadowComparisonResult {
        if (entryPrice <= BigDecimal.ZERO) {
            return ShadowComparisonResult(symbol, "No valid entry", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "WAIT", "No open position to compare.")
        }
        val fixedTpExit = entryPrice.multiply(BigDecimal.ONE.add(settings.takeProfitPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
        val trailingExit = highPrice.multiply(BigDecimal.ONE.subtract(settings.trailingDistancePercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
        val fixedPct = fixedTpExit.subtract(entryPrice).divide(entryPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
        val trailingPct = trailingExit.subtract(entryPrice).divide(entryPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
        val currentPct = currentPrice.subtract(entryPrice).divide(entryPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
        val recommended = when {
            trailingPct > fixedPct && currentPct > BigDecimal.ZERO -> "TRAILING_PROFIT_LOCK"
            fixedPct > BigDecimal.ZERO -> "FIXED_TAKE_PROFIT"
            else -> "AI_EXIT_ONLY"
        }
        return ShadowComparisonResult(symbol, "Live engine manages current exit", fixedPct, trailingPct, currentPct, recommended, "Shadow comparison generated from live high-water/current price; use over repeated trades to tune exit mode.")
    }
}

class RemoteNotifier(private val config: RemoteNotificationConfig) {
    private val http = OkHttpClient.Builder().build()

    fun send(message: String): List<String> {
        val results = mutableListOf<String>()
        if (config.telegramBotToken.isNotBlank() && config.telegramChatId.isNotBlank()) {
            results += runCatching { sendTelegram(message); "Telegram notification sent." }.getOrElse { "Telegram failed: ${it.message}" }
        }
        if (config.discordWebhookUrl.isNotBlank()) {
            results += runCatching { sendDiscord(message); "Discord notification sent." }.getOrElse { "Discord failed: ${it.message}" }
        }
        if (results.isEmpty()) results += "No remote notifier configured."
        return results
    }

    private fun sendTelegram(message: String) {
        val body = JSONObject().put("chat_id", config.telegramChatId).put("text", message).toString().toRequestBodyJson()
        val req = Request.Builder().url("https://api.telegram.org/bot${config.telegramBotToken}/sendMessage").post(body).build()
        http.newCall(req).execute().use { if (!it.isSuccessful) error("HTTP ${it.code}: ${it.body?.string().orEmpty()}") }
    }

    private fun sendDiscord(message: String) {
        val body = JSONObject().put("content", message).toString().toRequestBodyJson()
        val req = Request.Builder().url(config.discordWebhookUrl).post(body).build()
        http.newCall(req).execute().use { if (!it.isSuccessful) error("HTTP ${it.code}: ${it.body?.string().orEmpty()}") }
    }

    private fun String.toRequestBodyJson() = this.toRequestBody("application/json; charset=utf-8".toMediaType())
}

class CompletionWatchdog(private val context: Context? = null) {
    fun inspect(lastStatusEpochMs: Long, consecutiveApiErrors: Int, settings: BotSettings): WatchdogReport {
        val lines = mutableListOf<String>()
        var allowed = true
        val staleMs = System.currentTimeMillis() - lastStatusEpochMs
        if (staleMs > settings.scanIntervalSeconds * 3000L) { allowed = false; lines += "Bot loop appears stale for ${staleMs / 1000}s." } else lines += "Bot loop freshness OK."
        if (consecutiveApiErrors >= 5) { allowed = false; lines += "Repeated API errors detected: $consecutiveApiErrors." } else lines += "API error count acceptable: $consecutiveApiErrors."
        context?.let {
            val bm = it.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (battery in 0 until settings.pauseBelowBatteryPercent) { allowed = false; lines += "Battery $battery% below threshold ${settings.pauseBelowBatteryPercent}%." } else lines += "Battery watchdog OK: $battery%."
        }
        return WatchdogReport(allowed, if (allowed) "OK" else "PAUSE_RECOMMENDED", lines)
    }
}

class BelgianTaxCsvExporter {
    private val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)
    fun export(rows: List<TaxReportEntity>, year: Int): String {
        return buildString {
            appendLine("year,timestamp_utc,symbol,side,quantity,price_eur,fee_eur,realized_gain_eur,note")
            rows.filter { Instant.ofEpochMilli(it.timestampEpochMs).atZone(ZoneOffset.UTC).year == year }.forEach { r ->
                appendLine(listOf(year, fmt.format(Instant.ofEpochMilli(r.timestampEpochMs)), r.symbol, r.side, r.quantity, r.priceEur, r.feeEur, r.realizedGainEur, r.note.replace(',', ';')).joinToString(","))
            }
        }
    }
}

class LiveCompletionRegistry {
    fun allStatuses(): List<LiveCompletionStatus> = listOf(
        LiveCompletionStatus("Kraken live spot trading", true, true, "LIVE", "Primary live exchange path."),
        LiveCompletionStatus("Bitvavo live REST trading", true, true, "IMPLEMENTED", "Requires user account/API permission validation."),
        LiveCompletionStatus("Coinbase Advanced JWT trading", true, true, "IMPLEMENTED", "Requires CDP API key/private key in compatible PKCS8 PEM format."),
        LiveCompletionStatus("Kraken WebSocket ticker", true, true, "IMPLEMENTED", "Streams ticker channel from Kraken WebSocket v2."),
        LiveCompletionStatus("Strategy optimizer", true, false, "LOCAL", "Runs local candle-grid simulation; does not guarantee future performance."),
        LiveCompletionStatus("Shadow paper/live comparison", true, false, "LOCAL", "Compares exit styles from stored/live price state."),
        LiveCompletionStatus("Telegram/Discord notifier", true, true, "IMPLEMENTED", "Requires tokens/webhook configured externally."),
        LiveCompletionStatus("Belgian tax CSV", true, false, "EXPORT", "Recordkeeping helper; not accountant-certified tax advice."),
        LiveCompletionStatus("Watchdog/crash safety", true, false, "LOCAL", "Detects stale loop, repeated API errors, and low battery."),
        LiveCompletionStatus("Failproof trading", false, false, "IMPOSSIBLE", "No implementation can guarantee profits or avoid all losses."),
        LiveCompletionStatus("Regulatory bypass", false, false, "REFUSED", "The app does not bypass Belgian or exchange restrictions.")
    )
}
