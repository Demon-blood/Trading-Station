package com.ksp.cryptobot.pro

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/** Lightweight Kraken WebSocket v2 ticker monitor.
 *
 * The REST scanner remains the authoritative order loop. This monitor is designed as a live
 * price-feed companion: it can be started by future UI/service hooks and keeps the latest raw
 * ticker payload per symbol for faster diagnostics and stale-feed detection.
 */
class KrakenWebSocketMonitor {
    private val client = OkHttpClient.Builder().build()
    private var socket: WebSocket? = null
    private val latest = ConcurrentHashMap<String, String>()
    @Volatile var connected: Boolean = false
        private set
    @Volatile var lastMessageAtMs: Long = 0L
        private set

    fun start(symbols: List<String>) {
        if (socket != null) return
        val request = Request.Builder().url("wss://ws.kraken.com/v2").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                val pairs = symbols.map { toWsPair(it) }.distinct()
                val payload = """
                    {"method":"subscribe","params":{"channel":"ticker","symbol":${JSONArray(pairs)}}}
                """.trimIndent()
                webSocket.send(payload)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastMessageAtMs = System.currentTimeMillis()
                symbols.forEach { symbol ->
                    if (text.contains(toWsPair(symbol), ignoreCase = true)) latest[symbol.uppercase()] = text
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { connected = false; socket = null }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { connected = false; socket = null }
        })
    }

    fun stop() { socket?.close(1000, "Stopped by user"); socket = null; connected = false }
    fun latestRaw(symbol: String): String? = latest[symbol.uppercase()]
    fun stale(maxAgeMs: Long = 30_000L): Boolean = connected && lastMessageAtMs > 0L && System.currentTimeMillis() - lastMessageAtMs > maxAgeMs

    private fun toWsPair(symbol: String): String {
        val upper = symbol.uppercase().replace("/", "")
        return when {
            upper.endsWith("EUR") -> upper.removeSuffix("EUR") + "/EUR"
            upper.endsWith("USD") -> upper.removeSuffix("USD") + "/USD"
            else -> upper
        }
    }
}
