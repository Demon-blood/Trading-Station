package com.ksp.cryptobot.exchange

import com.ksp.cryptobot.core.OrderSide
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.random.Random

object KrakenNonceSequencer {
    private val last = AtomicLong(0L)

    fun nextLong(): Long {
        while (true) {
            val previous = last.get()
            val candidate = maxOf(System.currentTimeMillis(), previous + 1L)
            if (last.compareAndSet(previous, candidate)) return candidate
        }
    }

    fun next(): String = nextLong().toString()
}

object KrakenClientOrderId {
    private val longUuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val shortUuid = Regex("^[0-9a-fA-F]{32}$")

    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (longUuid.matches(trimmed) || shortUuid.matches(trimmed)) return trimmed
        val ascii = trimmed.filter { it.code in 33..126 }
        if (ascii.isNotBlank() && ascii.length <= 18) return ascii
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(trimmed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "cts-" + digest.take(14)
    }
}

object KrakenPrivateExecutionRegistry {
    private const val WS_URL = "wss://ws-auth.kraken.com/v2"
    private const val SILENCE_DEADLINE_MS = 15_000L
    private const val MAX_BACKOFF_MS = 60_000L
    private const val REST_TRUTH_TTL_MS = 60_000L
    private const val MAX_REPORTS = 500

    data class ExecutionReport(
        val orderId: String,
        val clientOrderId: String,
        val symbol: String,
        val side: OrderSide,
        val execType: String,
        val orderStatus: String,
        val orderQuantity: BigDecimal,
        val cumulativeQuantity: BigDecimal,
        val lastQuantity: BigDecimal,
        val averagePrice: BigDecimal,
        val lastPrice: BigDecimal,
        val feeQuantity: BigDecimal,
        val sequence: Long,
        val observedAtEpochMs: Long = System.currentTimeMillis()
    ) {
        val open: Boolean
            get() = orderStatus in setOf("pending_new", "new", "partially_filled")
    }

    data class Health(
        val enabled: Boolean,
        val networkAvailable: Boolean,
        val state: String,
        val subscribed: Boolean,
        val snapshotComplete: Boolean,
        val lastSequence: Long,
        val lastMessageAgeMs: Long,
        val openOrders: Int,
        val partialOrders: Int,
        val ambiguousSubmissions: Int,
        val recentRestTruthAgeMs: Long,
        val reconnectAttempt: Int,
        val lastError: String
    ) {
        val privateReady: Boolean
            get() = enabled &&
                networkAvailable &&
                state == "OPEN" &&
                subscribed &&
                snapshotComplete &&
                lastMessageAgeMs in 0..SILENCE_DEADLINE_MS

        val recentRestTruth: Boolean
            get() = recentRestTruthAgeMs in 0..REST_TRUTH_TTL_MS

        val knownForEntries: Boolean
            get() = (privateReady || recentRestTruth) && ambiguousSubmissions == 0

        fun summary(): String =
            "state=$state,privateReady=$privateReady,restTruth=$recentRestTruth,snapshot=$snapshotComplete,seq=$lastSequence,open=$openOrders,partial=$partialOrders,ambiguous=$ambiguousSubmissions,lastMessageAgeMs=$lastMessageAgeMs,reconnect=$reconnectAttempt,error=${lastError.ifBlank { "none" }}"
    }

    private data class PendingSubmission(
        val clientOrderId: String,
        val symbol: String,
        val side: OrderSide,
        val startedAtEpochMs: Long,
        val reason: String = ""
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestIds = AtomicLong(1L)
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var enabled = false
    private var networkAvailable = true
    private var apiKey = ""
    private var secretKey = ""
    private var socket: WebSocket? = null
    private var state = "STOPPED"
    private var subscribed = false
    private var snapshotComplete = false
    private var lastSequence = 0L
    private var lastMessageMs = 0L
    private var reconnectAttempt = 0
    private var lastError = ""
    private var lastRestReconciliationMs = 0L
    private var lastRestOpenOrderCount = 0

    private var connectJob: Job? = null
    private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null

    private val reportsByOrder = linkedMapOf<String, ExecutionReport>()
    private val recentReports = ArrayDeque<ExecutionReport>()
    private val pending = linkedMapOf<String, PendingSubmission>()
    private val ambiguous = linkedMapOf<String, PendingSubmission>()

    fun start(newApiKey: String, newSecretKey: String) {
        if (newApiKey.isBlank() || newSecretKey.isBlank()) {
            stop()
            return
        }

        var resetSocket: WebSocket? = null
        synchronized(lock) {
            val credentialsChanged = apiKey != newApiKey || secretKey != newSecretKey
            apiKey = newApiKey
            secretKey = newSecretKey
            enabled = true
            if (credentialsChanged) {
                resetSocket = socket
                socket = null
                state = "CREDENTIALS_CHANGED"
                subscribed = false
                snapshotComplete = false
                lastSequence = 0L
                reportsByOrder.clear()
                recentReports.clear()
            } else if (state == "STOPPED") {
                state = "IDLE"
            }
            startWatchdogLocked()
        }
        runCatching { resetSocket?.cancel() }
        connectIfNeeded()
    }

    fun stop() {
        val ws: WebSocket?
        synchronized(lock) {
            enabled = false
            apiKey = ""
            secretKey = ""
            state = "STOPPED"
            subscribed = false
            snapshotComplete = false
            lastSequence = 0L
            lastMessageMs = 0L
            reconnectAttempt = 0
            lastError = ""
            connectJob?.cancel()
            connectJob = null
            reconnectJob?.cancel()
            reconnectJob = null
            watchdogJob?.cancel()
            watchdogJob = null
            ws = socket
            socket = null
            reportsByOrder.clear()
            recentReports.clear()
            pending.clear()
            ambiguous.clear()
        }
        runCatching { ws?.close(1000, "CTS private execution host stopped") }
    }

    fun onNetworkAvailable(available: Boolean) {
        var wsToCancel: WebSocket? = null
        synchronized(lock) {
            if (networkAvailable == available) return
            networkAvailable = available
            if (!available) {
                state = "NETWORK_DOWN"
                subscribed = false
                snapshotComplete = false
                lastSequence = 0L
                lastMessageMs = 0L
                reportsByOrder.clear()
                wsToCancel = socket
                socket = null
                connectJob?.cancel()
                connectJob = null
                reconnectJob?.cancel()
                reconnectJob = null
            } else if (enabled) {
                state = "RECONNECT_PENDING"
            }
        }
        runCatching { wsToCancel?.cancel() }
        if (available) scheduleReconnect(immediate = true)
    }

    fun markRestReconciled(openOrders: Int) {
        synchronized(lock) {
            lastRestReconciliationMs = System.currentTimeMillis()
            lastRestOpenOrderCount = openOrders.coerceAtLeast(0)
            if (state == "REST_ONLY" || state == "ERROR") lastError = ""
        }
    }

    fun markSubmissionPending(clientOrderId: String, symbol: String, side: OrderSide) {
        val id = KrakenClientOrderId.normalize(clientOrderId)
        synchronized(lock) {
            pending[id] = PendingSubmission(
                clientOrderId = id,
                symbol = normalizeSymbol(symbol),
                side = side,
                startedAtEpochMs = System.currentTimeMillis()
            )
        }
    }

    fun markSubmissionAcknowledged(clientOrderId: String, exchangeOrderId: String) {
        val id = KrakenClientOrderId.normalize(clientOrderId)
        synchronized(lock) {
            pending.remove(id)
            ambiguous.remove(id)
            lastError = ""
        }
    }

    fun markFailureIfPending(clientOrderId: String, reason: String) {
        val id = KrakenClientOrderId.normalize(clientOrderId)
        synchronized(lock) {
            val item = pending.remove(id) ?: return
            ambiguous[id] = item.copy(reason = reason.take(300))
            lastError = "Ambiguous AddOrder result for $id: ${reason.take(200)}"
        }
    }

    fun clearSubmission(clientOrderId: String) {
        val id = KrakenClientOrderId.normalize(clientOrderId)
        synchronized(lock) {
            pending.remove(id)
            ambiguous.remove(id)
        }
    }

    fun canSubmitNewEntry(symbol: String, side: OrderSide): Pair<Boolean, String> = synchronized(lock) {
        if (side != OrderSide.BUY) return@synchronized true to "Protective/exit side is not entry-gated."

        val canonical = normalizeSymbol(symbol)
        if (ambiguous.isNotEmpty()) {
            val ids = ambiguous.keys.take(3).joinToString(",")
            return@synchronized false to "Kraken execution state contains ambiguous AddOrder result(s): $ids. Reconcile before another entry."
        }

        val duplicate = reportsByOrder.values.firstOrNull {
            it.open && it.symbol == canonical && it.side == OrderSide.BUY
        }
        if (duplicate != null) {
            return@synchronized false to "Open Kraken BUY already exists for $canonical: ${duplicate.orderId} status=${duplicate.orderStatus} cumQty=${duplicate.cumulativeQuantity}."
        }

        val age = if (lastRestReconciliationMs <= 0L) Long.MAX_VALUE
        else (System.currentTimeMillis() - lastRestReconciliationMs).coerceAtLeast(0L)
        val messageAge = if (lastMessageMs <= 0L) Long.MAX_VALUE
        else (System.currentTimeMillis() - lastMessageMs).coerceAtLeast(0L)

        val privateReady = enabled &&
            networkAvailable &&
            state == "OPEN" &&
            subscribed &&
            snapshotComplete &&
            messageAge <= SILENCE_DEADLINE_MS
        val restReady = age <= REST_TRUTH_TTL_MS

        if (!privateReady && !restReady) {
            return@synchronized false to "Kraken private execution state is unknown: no healthy executions snapshot and REST reconciliation is stale."
        }

        true to if (privateReady) "Kraken private executions snapshot is current."
        else "Kraken private WebSocket unavailable; recent full REST reconciliation is the active truth fallback."
    }

    fun health(): Health = synchronized(lock) {
        val now = System.currentTimeMillis()
        val msgAge = if (lastMessageMs <= 0L) Long.MAX_VALUE else (now - lastMessageMs).coerceAtLeast(0L)
        val restAge = if (lastRestReconciliationMs <= 0L) Long.MAX_VALUE else (now - lastRestReconciliationMs).coerceAtLeast(0L)
        val open = reportsByOrder.values.count { it.open }
        val partial = reportsByOrder.values.count { it.orderStatus == "partially_filled" }
        Health(
            enabled = enabled,
            networkAvailable = networkAvailable,
            state = state,
            subscribed = subscribed,
            snapshotComplete = snapshotComplete,
            lastSequence = lastSequence,
            lastMessageAgeMs = msgAge,
            openOrders = maxOf(open, if (restAge <= REST_TRUTH_TTL_MS) lastRestOpenOrderCount else 0),
            partialOrders = partial,
            ambiguousSubmissions = ambiguous.size,
            recentRestTruthAgeMs = restAge,
            reconnectAttempt = reconnectAttempt,
            lastError = lastError
        )
    }

    fun recentExecutionReports(limit: Int = 100): List<ExecutionReport> = synchronized(lock) {
        recentReports.takeLast(limit.coerceIn(1, MAX_REPORTS)).toList()
    }

    private fun connectIfNeeded() {
        val credentials: Pair<String, String>
        synchronized(lock) {
            if (!enabled || !networkAvailable || socket != null || connectJob?.isActive == true) return
            credentials = apiKey to secretKey
            state = "TOKEN"
            connectJob = scope.launch {
                val result = runCatching {
                    KrakenSpotClient(credentials.first, credentials.second).getWebSocketsToken()
                }
                val token = result.getOrElse { error ->
                    synchronized(lock) {
                        connectJob = null
                        state = "REST_ONLY"
                        lastError = "GetWebSocketsToken failed: ${error.message ?: error.javaClass.simpleName}"
                    }
                    scheduleReconnect(immediate = false)
                    return@launch
                }

                synchronized(lock) {
                    connectJob = null
                    if (!enabled || !networkAvailable ||
                        apiKey != credentials.first || secretKey != credentials.second) return@launch
                    state = "CONNECTING"
                    subscribed = false
                    snapshotComplete = false
                    lastSequence = 0L
                    lastMessageMs = 0L
                    reportsByOrder.clear()
                    val request = Request.Builder().url(WS_URL).build()
                    socket = http.newWebSocket(request, listener(token))
                }
            }
        }
    }

    private fun listener(token: String) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (socket !== webSocket || !enabled || !networkAvailable) {
                    webSocket.close(1000, "stale private connection")
                    return
                }
                state = "OPEN"
                reconnectAttempt = 0
                lastMessageMs = System.currentTimeMillis()
                lastError = ""
                subscribed = false
                snapshotComplete = false
                lastSequence = 0L
                reportsByOrder.clear()
            }
            val params = JSONObject()
                .put("channel", "executions")
                .put("token", token)
                .put("snap_orders", true)
                .put("snap_trades", true)
                .put("order_status", true)
            webSocket.send(
                JSONObject()
                    .put("method", "subscribe")
                    .put("params", params)
                    .put("req_id", requestIds.getAndIncrement())
                    .toString()
            )
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(lock) {
                if (socket !== webSocket) return
                lastMessageMs = System.currentTimeMillis()
            }
            runCatching { handleMessage(webSocket, text) }
                .onFailure { error ->
                    synchronized(lock) {
                        lastError = "Private WS parse: ${error.message ?: error.javaClass.simpleName}"
                    }
                }
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

    private fun handleMessage(webSocket: WebSocket, raw: String) {
        val root = JSONObject(raw)

        if (root.optString("method") == "subscribe") {
            val success = root.optBoolean("success", false)
            synchronized(lock) {
                subscribed = success &&
                    root.optJSONObject("result")?.optString("channel") == "executions"
                if (!success) {
                    state = "REST_ONLY"
                    lastError = "Executions subscribe failed: ${root.optString("error", "unknown")}"
                }
            }
            if (!success) runCatching { webSocket.cancel() }
            return
        }

        if (root.optString("channel") == "heartbeat") return
        if (root.optString("channel") != "executions") return

        val sequence = root.optLong("sequence", 0L)
        val type = root.optString("type")
        val data = root.optJSONArray("data") ?: JSONArray()

        synchronized(lock) {
            if (type == "update" && sequence > 0L && lastSequence > 0L && sequence != lastSequence + 1L) {
                state = "SEQUENCE_GAP"
                snapshotComplete = false
                subscribed = false
                lastError = "Kraken executions sequence gap: expected=${lastSequence + 1L}, actual=$sequence"
                reportsByOrder.clear()
                socket = null
                runCatching { webSocket.cancel() }
                scheduleReconnect(immediate = false)
                return
            }

            if (type == "snapshot") {
                reportsByOrder.clear()
                snapshotComplete = false
            }

            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val report = parseReport(item, sequence)
                if (report.orderId.isNotBlank()) reportsByOrder[report.orderId] = report
                if (report.clientOrderId.isNotBlank()) {
                    pending.remove(report.clientOrderId)
                    ambiguous.remove(report.clientOrderId)
                }
                recentReports.addLast(report)
                while (recentReports.size > MAX_REPORTS) recentReports.removeFirst()
            }

            if (sequence > 0L) lastSequence = sequence
            if (type == "snapshot") snapshotComplete = true
            if (state != "OPEN") state = "OPEN"
        }
    }

    private fun parseReport(item: JSONObject, sequence: Long): ExecutionReport {
        val fees = item.optJSONArray("fees")
        var fee = BigDecimal.ZERO
        if (fees != null) {
            for (i in 0 until fees.length()) {
                fee = fee.add(decimal(fees.optJSONObject(i), "qty"))
            }
        }

        val side = if (item.optString("side").equals("sell", ignoreCase = true)) {
            OrderSide.SELL
        } else {
            OrderSide.BUY
        }

        return ExecutionReport(
            orderId = item.optString("order_id"),
            clientOrderId = item.optString("cl_ord_id"),
            symbol = normalizeSymbol(item.optString("symbol")),
            side = side,
            execType = item.optString("exec_type"),
            orderStatus = item.optString("order_status"),
            orderQuantity = decimal(item, "order_qty"),
            cumulativeQuantity = decimal(item, "cum_qty"),
            lastQuantity = decimal(item, "last_qty"),
            averagePrice = decimal(item, "avg_price"),
            lastPrice = decimal(item, "last_price"),
            feeQuantity = fee,
            sequence = sequence
        )
    }

    private fun handleDisconnect(webSocket: WebSocket, reason: String) {
        val reconnect: Boolean
        synchronized(lock) {
            if (socket !== webSocket) return
            socket = null
            state = if (networkAvailable) "DISCONNECTED" else "NETWORK_DOWN"
            subscribed = false
            snapshotComplete = false
            lastSequence = 0L
            lastMessageMs = 0L
            reportsByOrder.clear()
            lastError = reason.take(300)
            reconnect = enabled && networkAvailable
        }
        if (reconnect) scheduleReconnect(immediate = false)
    }

    private fun scheduleReconnect(immediate: Boolean) {
        synchronized(lock) {
            if (!enabled || !networkAvailable || socket != null) return
            if (reconnectJob?.isActive == true || connectJob?.isActive == true) return

            reconnectAttempt = if (immediate) reconnectAttempt else reconnectAttempt + 1
            val exponent = min(reconnectAttempt.coerceAtLeast(1) - 1, 6)
            val base = if (immediate) 0L else min(MAX_BACKOFF_MS, 1_000L shl exponent)
            val jitter = if (base <= 0L) 0L else Random.nextLong((base / 10L).coerceAtLeast(1L))
            state = if (immediate) "RECONNECTING" else "BACKOFF"
            reconnectJob = scope.launch {
                delay(base + jitter)
                synchronized(lock) { reconnectJob = null }
                connectIfNeeded()
            }
        }
    }

    private fun startWatchdogLocked() {
        if (watchdogJob?.isActive == true) return
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
                        subscribed = false
                        snapshotComplete = false
                        lastSequence = 0L
                        lastError = "No authenticated Kraken message within ${SILENCE_DEADLINE_MS}ms"
                        reportsByOrder.clear()
                        current
                    }
                    runCatching { ws?.cancel() }
                    scheduleReconnect(immediate = false)
                }
            }
        }
    }

    private fun decimal(item: JSONObject?, field: String): BigDecimal =
        item?.opt(field)?.toString()?.toBigDecimalOrNull() ?: BigDecimal.ZERO

    private fun normalizeSymbol(symbol: String): String =
        symbol.uppercase().replace("/", "").replace("-", "").replace("_", "").trim()
}
