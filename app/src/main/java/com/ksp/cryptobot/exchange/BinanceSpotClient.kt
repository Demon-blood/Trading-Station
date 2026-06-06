package com.ksp.cryptobot.exchange

import com.ksp.cryptobot.core.*
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BinanceSpotClient(
    private val apiKey: String,
    private val secretKey: String,
    private val baseUrl: String = "https://api.binance.com"
) : CryptoExchangeClient {
    private val client = OkHttpClient.Builder().build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val tickerAdapter = moshi.adapter(BinanceTickerResponse::class.java)
    private val orderAdapter = moshi.adapter(BinanceOrderResponse::class.java)

    override suspend fun getTicker(symbol: String): MarketTicker = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/v3/ticker/24hr?symbol=$symbol")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Binance ticker failed: HTTP ${response.code}")
            val body = response.body?.string() ?: error("Empty Binance response")
            val dto = tickerAdapter.fromJson(body) ?: error("Invalid Binance ticker JSON")
            MarketTicker(
                symbol = dto.symbol,
                lastPrice = dto.lastPrice.toBigDecimal(),
                bid = dto.bidPrice.toBigDecimal(),
                ask = dto.askPrice.toBigDecimal(),
                volume24h = dto.quoteVolume.toBigDecimal(),
                priceChangePercent24h = dto.priceChangePercent.toBigDecimal()
            )
        }
    }


    override suspend fun getCandles(symbol: String, timeframe: Timeframe, limit: Int): List<Candle> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(50, 500)
        val request = Request.Builder()
            .url("$baseUrl/api/v3/klines?symbol=$symbol&interval=${timeframe.binanceInterval}&limit=$safeLimit")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Binance candles failed: HTTP ${response.code}")
            val body = response.body?.string() ?: error("Empty Binance candle response")
            val adapter = moshi.adapter(List::class.java)
            val raw = adapter.fromJson(body) ?: emptyList<Any>()
            raw.mapNotNull { row ->
                val values = row as? List<*> ?: return@mapNotNull null
                Candle(
                    symbol = symbol,
                    timeframe = timeframe,
                    openTimeEpochMs = (values.getOrNull(0) as? Number)?.toLong() ?: return@mapNotNull null,
                    open = values.getOrNull(1).toString().toBigDecimal(),
                    high = values.getOrNull(2).toString().toBigDecimal(),
                    low = values.getOrNull(3).toString().toBigDecimal(),
                    close = values.getOrNull(4).toString().toBigDecimal(),
                    volume = values.getOrNull(5).toString().toBigDecimal()
                )
            }
        }
    }

    override suspend fun placeOrder(request: OrderRequest): OrderResult = withContext(Dispatchers.IO) {
        val price = request.limitPrice ?: error("Live order requires a limit price. Market orders are intentionally disabled.")
        val timestamp = System.currentTimeMillis()
        val query = listOf(
            "symbol=${request.symbol}",
            "side=${request.side.name}",
            "type=LIMIT",
            "timeInForce=GTC",
            "quantity=${request.quantity.stripTrailingZeros().toPlainString()}",
            "price=${price.stripTrailingZeros().toPlainString()}",
            "newClientOrderId=${request.clientOrderId}",
            "recvWindow=5000",
            "timestamp=$timestamp"
        ).joinToString("&")
        val signature = hmacSha256(query, secretKey)
        val body = "$query&signature=$signature".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val httpRequest = Request.Builder()
            .url("$baseUrl/api/v3/order")
            .addHeader("X-MBX-APIKEY", apiKey)
            .post(body)
            .build()
        client.newCall(httpRequest).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Binance order failed: HTTP ${response.code} $responseText")
            val parsed = orderAdapter.fromJson(responseText)
            OrderResult(
                exchangeOrderId = parsed?.orderId?.toString() ?: request.clientOrderId,
                symbol = request.symbol,
                side = request.side,
                executedQuantity = parsed?.executedQty?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                averagePrice = price,
                fee = BigDecimal.ZERO,
                paper = false
            )
        }
    }

    private fun hmacSha256(payload: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

data class BinanceTickerResponse(
    val symbol: String,
    @Json(name = "lastPrice") val lastPrice: String,
    @Json(name = "bidPrice") val bidPrice: String,
    @Json(name = "askPrice") val askPrice: String,
    @Json(name = "quoteVolume") val quoteVolume: String,
    @Json(name = "priceChangePercent") val priceChangePercent: String
)

data class BinanceOrderResponse(
    val symbol: String?,
    val orderId: Long?,
    val clientOrderId: String?,
    val transactTime: Long?,
    val price: String?,
    val origQty: String?,
    val executedQty: String?,
    val status: String?
)
