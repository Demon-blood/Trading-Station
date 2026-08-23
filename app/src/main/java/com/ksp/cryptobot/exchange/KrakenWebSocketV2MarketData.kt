package com.ksp.cryptobot.exchange

import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.Timeframe
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

/**
 * Process-wide Kraken public WebSocket v2 market-data cache.
 *
 * Scope:
 * - ticker (BBO-triggered) for active markets;
 * - OHLC latest-candle updates for intervals actually requested by the strategy engine;
 * - status/heartbeat/pong health tracking;
 * - reconnect with backoff + jitter;
 * - subscription replay after reconnect;
 * - cache reset on reconnect so pre-gap data is never treated as a fresh snapshot.
 *
 * REST remains the historical/backfill/fallback source. Private order/account state
 * remains reconciled by the existing Kraken REST/lifecycle path.
 */
object KrakenRealtimeMarketDataRegistry {
    private const val WS_URL = "wss://ws.kraken.com/v2"
    private const val SILENCE_DEADLINE_MS = 8_000L
    private const val APP_PING_INTERVAL_MS = 30_000L
    private const val MAX_BACKOFF_MS = 60_000L
    private const val MAX_ACTIVE_SYMBOLS = 32

    private data class Subscription(
        val channel: String,
        val canonicalSymbol: String,
        val wsSymbol: String,
        val intervalMinutes: Int = 0
    )

    data class Health(
        val enabled: Boolean,
        val networkAvailable: Boolean,
        val state: String,
        val systemStatus: String,
        val connectionId: Long,
        val activeSymbols: Int,
        val subscriptions: Int,
        val acknowledgedSubscriptions: Int,
        val lastMessageAgeMs: Long,
        val reconnectAttempt: Int,
        val lastError: String
    ) {
        val healthy: Boolean
            get() = enabled &&
                networkAvailable &&
                state == "OPEN" &&
                systemStatus != "maintenance" &&
                lastMessageAgeMs in 0..SILENCE_DEADLINE_MS

        fun summary(): String =
            "state=$state,system=$systemStatus,healthy=$healthy,active=$activeSymbols,subs=$acknowledgedSubscriptions/$subscriptions,lastMessageAgeMs=$lastMessageAgeMs,reconnect=$reconnectAttempt,error=${lastError.ifBlank { "none" }}"
    }

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestIds = AtomicLong(1L)
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var enabled = false
    private var networkAvailable = true
    private var socket: WebSocket? = null
    private var state = "STOPPED"
    private var systemStatus = "unknown"
    private var connectionId = 0L
    private var reconnectAttempt = 0
    private var lastMessageMs = 0L
    private var lastError = ""
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null
    private var pingJob: Job? = null

    private val activeSymbols = linkedMapOf<String, String>()
    private val subscriptions = linkedSetOf<Subscription>()
    private val acknowledged = linkedSetOf<Subscription>()
    private val tickerCache = linkedMapOf<String, MarketTicker>()
    private val candleCache = linkedMapOf<Pair<String, Timeframe>, Candle>()

    fun start() {
        synchronized(lock) {
            enabled = true
            if (state == "STOPPED") state = "IDLE"
            startMaintenanceJobsLocked()
        }
        connectIfNeeded()
    }

    fun stop() {
        val ws: WebSocket?
        synchronized(lock) {
            enabled = false
            state = "STOPPED"
            systemStatus = "unknown"
            connectionId = 0L
            reconnectAttempt = 0
            lastMessageMs = 0L
            lastError = ""
            reconnectJob?.cancel()
            reconnectJob = null
            watchdogJob?.cancel()
            watchdogJob = null
            pingJob?.cancel()
            pingJob = null
            ws = socket
            socket = null
            activeSymbols.clear()
            subscriptions.clear()
            acknowledged.clear()
            tickerCache.clear()
            candleCache.clear()
        }
        runCatching { ws?.close(1000, "CTS host stopped") }
    }

    fun onNetworkAvailable(available: Boolean) {
        var wsToCancel: WebSocket? = null
        synchronized(lock) {
            if (networkAvailable == available) return
            networkAvailable = available
            if (!available) {
                state = "NETWORK_DOWN"
                systemStatus = "unknown"
                connectionId = 0L
                lastMessageMs = 0L
                acknowledged.clear()
                tickerCache.clear()
                candleCache.clear()
                wsToCancel = socket
                socket = null
                reconnectJob?.cancel()
                reconnectJob = null
            } else {
                state = if (enabled) "RECONNECT_PENDING" else "STOPPED"
            }
        }
        if (!available) {
            runCatching { wsToCancel?.cancel() }
        } else {
            scheduleReconnect(immediate = true)
        }
    }

    fun setActiveSymbols(symbols: Collection<String>) {
        val normalized = symbols.asSequence()
            .map { normalizeCanonical(it) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_ACTIVE_SYMBOLS)
            .associateWith { toWebSocketV2Symbol(it) }

        var wsToCancel: WebSocket? = null
        var reconnect = false
        synchronized(lock) {
            if (activeSymbols == normalized) return
            activeSymbols.clear()
            activeSymbols.putAll(normalized)

            val removed = subscriptions.filter { it.canonicalSymbol !in activeSymbols.keys }
            if (removed.isNotEmpty()) {
                subscriptions.removeAll(removed.toSet())
                acknowledged.removeAll(removed.toSet())
                removed.forEach { sub ->
                    tickerCache.remove(sub.canonicalSymbol)
                    candleCache.keys.removeAll { it.first == sub.canonicalSymbol }
                }
                if (socket != null) {
                    reconnect = true
                    wsToCancel = socket
                    socket = null
                    state = "ACTIVE_SET_CHANGED"
                    systemStatus = "unknown"
                    connectionId = 0L
                    acknowledged.clear()
                    tickerCache.clear()
                    candleCache.clear()
                }
            }
        }
        if (reconnect) {
            runCatching { wsToCancel?.cancel() }
            scheduleReconnect(immediate = true)
        } else {
            connectIfNeeded()
        }
    }

    fun ensureTicker(canonicalSymbol: String) {
        val canonical = normalizeCanonical(canonicalSymbol)
        val subscription: Subscription
        synchronized(lock) {
            val wsSymbol = activeSymbols[canonical] ?: return
            subscription = Subscription("ticker", canonical, wsSymbol)
            if (!subscriptions.add(subscription)) return
        }
        sendSubscriptionIfOpen(subscription)
        connectIfNeeded()
    }

    fun ensureOhlc(canonicalSymbol: String, timeframe: Timeframe) {
        val canonical = normalizeCanonical(canonicalSymbol)
        val subscription: Subscription
        synchronized(lock) {
            val wsSymbol = activeSymbols[canonical] ?: return
            subscription = Subscription("ohlc", canonical, wsSymbol, intervalMinutes(timeframe))
            if (!subscriptions.add(subscription)) return
        }
        sendSubscriptionIfOpen(subscription)
        connectIfNeeded()
    }

    fun freshTicker(canonicalSymbol: String): MarketTicker? = synchronized(lock) {
        if (!connectionHealthyLocked()) return@synchronized null
        tickerCache[normalizeCanonical(canonicalSymbol)]
    }

    fun mergeLatestCandle(
        canonicalSymbol: String,
        timeframe: Timeframe,
        restCandles: List<Candle>,
        limit: Int
    ): List<Candle> {
        val latest = synchronized(lock) {
            if (!connectionHealthyLocked()) null
            else candleCache[normalizeCanonical(canonicalSymbol) to timeframe]
        } ?: return restCandles.takeLast(limit.coerceAtLeast(1))

        val merged = restCandles.toMutableList()
        val sameIndex = merged.indexOfFirst { it.openTimeEpochMs == latest.openTimeEpochMs }
        when {
            sameIndex >= 0 -> merged[sameIndex] = latest
            merged.isEmpty() || latest.openTimeEpochMs > merged.last().openTimeEpochMs -> merged += latest
        }
        return merged.sortedBy { it.openTimeEpochMs }.takeLast(limit.coerceAtLeast(1))
    }

    fun health(): Health = synchronized(lock) {
        val age = if (lastMessageMs <= 0L) Long.MAX_VALUE else
            (System.currentTimeMillis() - lastMessageMs).coerceAtLeast(0L)
        Health(
            enabled = enabled,
            networkAvailable = networkAvailable,
            state = state,
            systemStatus = systemStatus,
            connectionId = connectionId,
            activeSymbols = activeSymbols.size,
            subscriptions = subscriptions.size,
            acknowledgedSubscriptions = acknowledged.size,
            lastMessageAgeMs = age,
            reconnectAttempt = reconnectAttempt,
            lastError = lastError
        )
    }

    private fun connectIfNeeded() {
        synchronized(lock) {
            if (!enabled || !networkAvailable || socket != null || subscriptions.isEmpty()) return
            state = "CONNECTING"
            lastError = ""
            val request = Request.Builder().url(WS_URL).build()
            socket = client.newWebSocket(request, listener)
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val replay: List<Subscription>
            synchronized(lock) {
                if (socket !== webSocket || !enabled || !networkAvailable) {
                    webSocket.close(1000, "stale connection")
                    return
                }
                state = "OPEN"
                systemStatus = "unknown"
                connectionId = 0L
                reconnectAttempt = 0
                lastMessageMs = System.currentTimeMillis()
                lastError = ""
                acknowledged.clear()
                tickerCache.clear()
                candleCache.clear()
                replay = subscriptions.toList()
            }
            replay.forEach { sendSubscription(webSocket, it) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(lock) {
                if (socket !== webSocket) return
                lastMessageMs = System.currentTimeMillis()
            }
            runCatching { handleMessage(text) }
                .onFailure { error ->
                    synchronized(lock) {
                        lastError = "Parse: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDisconnect(webSocket, "closed $code $reason")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleDisconnect(
                webSocket,
                "failure ${response?.code ?: "-"}: ${t.message ?: t.javaClass.simpleName}"
            )
        }
    }

    private fun handleMessage(raw: String) {
        val root = JSONObject(raw)

        if (root.optString("method") == "pong") return

        if (root.optString("method") == "subscribe") {
            val success = root.optBoolean("success", false)
            val result = root.optJSONObject("result")
            if (!success) {
                synchronized(lock) {
                    lastError = "Subscription: ${root.optString("error", "unknown")}"
                }
                return
            }
            if (result != null) {
                val channel = result.optString("channel")
                val wsSymbol = result.optString("symbol")
                val interval = result.opt("interval")?.toString()?.toIntOrNull() ?: 0
                synchronized(lock) {
                    subscriptions.firstOrNull {
                        it.channel == channel &&
                            it.wsSymbol == wsSymbol &&
                            (channel != "ohlc" || it.intervalMinutes == interval)
                    }?.let { acknowledged += it }
                }
            }
            return
        }

        when (root.optString("channel")) {
            "heartbeat" -> Unit
            "status" -> handleStatus(root.optJSONArray("data"))
            "ticker" -> handleTicker(root.optJSONArray("data"))
            "ohlc" -> handleOhlc(root.optJSONArray("data"))
        }
    }

    private fun handleStatus(data: JSONArray?) {
        val item = data?.optJSONObject(0) ?: return
        synchronized(lock) {
            systemStatus = item.optString("system", "unknown")
            connectionId = item.opt("connection_id")?.toString()?.toLongOrNull() ?: 0L
        }
    }

    private fun handleTicker(data: JSONArray?) {
        if (data == null) return
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val wsSymbol = item.optString("symbol")
            val canonical = synchronized(lock) {
                activeSymbols.entries.firstOrNull { it.value == wsSymbol }?.key
            } ?: continue

            val ask = decimal(item, "ask") ?: continue
            val bid = decimal(item, "bid") ?: continue
            val last = decimal(item, "last") ?: continue
            val baseVolume = decimal(item, "volume") ?: BigDecimal.ZERO
            val changePct = decimal(item, "change_pct") ?: BigDecimal.ZERO
            val timestamp = parseInstant(item.optString("timestamp"))

            val ticker = MarketTicker(
                symbol = canonical,
                lastPrice = last,
                bid = bid,
                ask = ask,
                volume24h = baseVolume.multiply(last),
                priceChangePercent24h = changePct,
                timestamp = timestamp
            )
            synchronized(lock) {
                if (canonical in activeSymbols.keys) tickerCache[canonical] = ticker
            }
        }
    }

    private fun handleOhlc(data: JSONArray?) {
        if (data == null) return
        for (index in 0 until data.length()) {
            val item = data.optJSONObject(index) ?: continue
            val wsSymbol = item.optString("symbol")
            val interval = item.opt("interval")?.toString()?.toIntOrNull() ?: continue
            val timeframe = timeframeForInterval(interval) ?: continue
            val canonical = synchronized(lock) {
                activeSymbols.entries.firstOrNull { it.value == wsSymbol }?.key
            } ?: continue

            val candle = Candle(
                symbol = canonical,
                timeframe = timeframe,
                openTimeEpochMs = parseInstant(item.optString("interval_begin")).toEpochMilli(),
                open = decimal(item, "open") ?: continue,
                high = decimal(item, "high") ?: continue,
                low = decimal(item, "low") ?: continue,
                close = decimal(item, "close") ?: continue,
                volume = decimal(item, "volume") ?: BigDecimal.ZERO
            )
            synchronized(lock) {
                if (canonical in activeSymbols.keys) candleCache[canonical to timeframe] = candle
            }
        }
    }

    private fun sendSubscriptionIfOpen(subscription: Subscription) {
        val ws = synchronized(lock) {
            if (state == "OPEN") socket else null
        } ?: return
        sendSubscription(ws, subscription)
    }

    private fun sendSubscription(webSocket: WebSocket, subscription: Subscription) {
        val params = JSONObject()
            .put("channel", subscription.channel)
            .put("symbol", JSONArray().put(subscription.wsSymbol))
            .put("snapshot", true)

        when (subscription.channel) {
            "ticker" -> params.put("event_trigger", "bbo")
            "ohlc" -> params.put("interval", subscription.intervalMinutes)
        }

        val request = JSONObject()
            .put("method", "subscribe")
            .put("params", params)
            .put("req_id", requestIds.getAndIncrement())

        if (!webSocket.send(request.toString())) {
            synchronized(lock) {
                lastError = "WebSocket send failed for ${subscription.channel}/${subscription.wsSymbol}"
            }
        }
    }

    private fun sendApplicationPing() {
        val ws = synchronized(lock) {
            if (state == "OPEN") socket else null
        } ?: return
        ws.send(
            JSONObject()
                .put("method", "ping")
                .put("req_id", requestIds.getAndIncrement())
                .toString()
        )
    }

    private fun handleDisconnect(webSocket: WebSocket, error: String) {
        val shouldReconnect: Boolean
        synchronized(lock) {
            if (socket !== webSocket) return
            socket = null
            state = if (networkAvailable) "DISCONNECTED" else "NETWORK_DOWN"
            systemStatus = "unknown"
            connectionId = 0L
            lastMessageMs = 0L
            lastError = error.take(300)
            acknowledged.clear()
            tickerCache.clear()
            candleCache.clear()
            shouldReconnect = enabled && networkAvailable && subscriptions.isNotEmpty()
        }
        if (shouldReconnect) scheduleReconnect(immediate = false)
    }

    private fun scheduleReconnect(immediate: Boolean) {
        synchronized(lock) {
            if (!enabled || !networkAvailable || subscriptions.isEmpty() || socket != null) return
            if (reconnectJob?.isActive == true) return

            reconnectAttempt = if (immediate) reconnectAttempt else reconnectAttempt + 1
            val exponent = min(reconnectAttempt.coerceAtLeast(1) - 1, 6)
            val base = if (immediate) 0L else min(MAX_BACKOFF_MS, 1_000L shl exponent)
            val jitter = if (base <= 0L) 0L
            else Random.nextLong((base / 10L).coerceAtLeast(1L))
            state = if (immediate) "RECONNECTING" else "BACKOFF"
            reconnectJob = scope.launch {
                delay(base + jitter)
                synchronized(lock) { reconnectJob = null }
                connectIfNeeded()
            }
        }
    }

    private fun startMaintenanceJobsLocked() {
        if (watchdogJob?.isActive != true) {
            watchdogJob = scope.launch {
                while (isActive) {
                    delay(1_000L)
                    val silent = synchronized(lock) {
                        enabled &&
                            networkAvailable &&
                            state == "OPEN" &&
                            lastMessageMs > 0L &&
                            System.currentTimeMillis() - lastMessageMs > SILENCE_DEADLINE_MS
                    }
                    if (silent) {
                        val ws = synchronized(lock) {
                            val current = socket
                            socket = null
                            state = "SILENT"
                            systemStatus = "unknown"
                            connectionId = 0L
                            lastError = "No Kraken WebSocket message within ${SILENCE_DEADLINE_MS}ms"
                            acknowledged.clear()
                            tickerCache.clear()
                            candleCache.clear()
                            current
                        }
                        runCatching { ws?.cancel() }
                        scheduleReconnect(immediate = false)
                    }
                }
            }
        }

        if (pingJob?.isActive != true) {
            pingJob = scope.launch {
                while (isActive) {
                    delay(APP_PING_INTERVAL_MS)
                    sendApplicationPing()
                }
            }
        }
    }

    private fun connectionHealthyLocked(): Boolean {
        if (!enabled || !networkAvailable || state != "OPEN") return false
        if (systemStatus == "maintenance") return false
        if (lastMessageMs <= 0L) return false
        return System.currentTimeMillis() - lastMessageMs <= SILENCE_DEADLINE_MS
    }

    private fun normalizeCanonical(symbol: String): String =
        symbol.uppercase().replace("/", "").replace("-", "").replace("_", "").trim()

    internal fun toWebSocketV2Symbol(canonicalSymbol: String): String {
        val canonical = normalizeCanonical(canonicalSymbol)
        val quotes = listOf("USDT", "USDC", "EUR", "USD", "GBP", "CHF", "AUD", "CAD", "JPY", "BTC", "ETH")
        val quote = quotes.firstOrNull {
            canonical.endsWith(it) && canonical.length > it.length
        } ?: return canonical

        var base = canonical.removeSuffix(quote)
        if (base == "XBT") base = "BTC"
        val wsQuote = if (quote == "XBT") "BTC" else quote
        return "$base/$wsQuote"
    }

    internal fun intervalMinutes(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.M1 -> 1
        Timeframe.M5 -> 5
        Timeframe.M15 -> 15
        Timeframe.H1 -> 60
        Timeframe.H4 -> 240
    }

    private fun timeframeForInterval(interval: Int): Timeframe? = when (interval) {
        1 -> Timeframe.M1
        5 -> Timeframe.M5
        15 -> Timeframe.M15
        60 -> Timeframe.H1
        240 -> Timeframe.H4
        else -> null
    }

    private fun decimal(item: JSONObject, name: String): BigDecimal? =
        item.opt(name)?.toString()?.toBigDecimalOrNull()

    private fun parseInstant(value: String): Instant =
        runCatching { Instant.parse(value) }.getOrElse { Instant.now() }
}
