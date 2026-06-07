package com.ksp.cryptobot.exchange

import com.ksp.cryptobot.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal
import java.math.RoundingMode
import com.ksp.cryptobot.core.BalanceInfo

/**
 * v0.8 multi-exchange layer.
 *
 * The public market-data methods are implemented with resilient fallbacks so the app can keep scanning even when
 * a provider is unavailable. Live trading remains blocked unless the selected provider explicitly supports it and
 * credentials are present. This avoids bypassing jurisdiction restrictions while still making the app usable in
 * Belgium through compliant providers or manual execution.
 */
abstract class BaseExchangeClient(
    private val providerName: String,
    private val marketDataFallback: CryptoExchangeClient = PaperExchangeClient()
) : CryptoExchangeClient {
    protected val http = OkHttpClient.Builder().build()

    override suspend fun getTicker(symbol: String): MarketTicker = marketDataFallback.getTicker(symbol)

    override suspend fun getCandles(symbol: String, timeframe: Timeframe, limit: Int): List<Candle> =
        marketDataFallback.getCandles(symbol, timeframe, limit)

    override suspend fun placeOrder(request: OrderRequest): OrderResult {
        error("$providerName live order execution is not enabled in this build. Use PAPER, MANUAL, or enable a verified compliant connector.")
    }

    protected suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("$providerName HTTP ${res.code}")
            res.body?.string() ?: error("$providerName empty response")
        }
    }
}

class BinanceReadOnlyClient : CryptoExchangeClient {
    private val delegate = BinanceSpotClient(apiKey = "", secretKey = "")

    override suspend fun getTicker(symbol: String): MarketTicker = delegate.getTicker(symbol)

    override suspend fun getCandles(symbol: String, timeframe: Timeframe, limit: Int): List<Candle> =
        delegate.getCandles(symbol, timeframe, limit)

    override suspend fun placeOrder(request: OrderRequest): OrderResult {
        error("Binance is configured as READ ONLY for Belgium mode. Live spot trading is disabled for this connector.")
    }
}

class KrakenSpotClient(
    private val apiKey: String,
    private val secretKey: String
) : CryptoExchangeClient {
    private val http = OkHttpClient.Builder().build()

    override suspend fun getTicker(symbol: String): MarketTicker = withContext(Dispatchers.IO) {
        val pair = toKrakenPair(symbol)
        val req = Request.Builder()
            .url("https://api.kraken.com/0/public/Ticker?pair=$pair")
            .get()
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Kraken ticker HTTP ${res.code}")
            val body = res.body?.string() ?: error("Kraken ticker empty response")
            val root = org.json.JSONObject(body)
            val errors = root.optJSONArray("error")
            if (errors != null && errors.length() > 0) error("Kraken ticker error: $errors")
            val result = root.getJSONObject("result")
            val firstKey = result.keys().asSequence().firstOrNull() ?: error("Kraken ticker missing result")
            val item = result.getJSONObject(firstKey)
            val bid = item.getJSONArray("b").getString(0).toBigDecimal()
            val ask = item.getJSONArray("a").getString(0).toBigDecimal()
            val last = item.getJSONArray("c").getString(0).toBigDecimal()
            val volume = item.getJSONArray("v").getString(1).toBigDecimal()
            val open = item.getString("o").toBigDecimal()
            val changePct = if (open.compareTo(BigDecimal.ZERO) == 0) BigDecimal.ZERO else
                last.subtract(open).divide(open, 8, java.math.RoundingMode.HALF_UP).multiply(BigDecimal("100"))
            MarketTicker(
                symbol = symbol,
                lastPrice = last,
                bid = bid,
                ask = ask,
                volume24h = volume.multiply(last),
                priceChangePercent24h = changePct
            )
        }
    }

    override suspend fun getCandles(symbol: String, timeframe: Timeframe, limit: Int): List<Candle> = withContext(Dispatchers.IO) {
        val pair = toKrakenPair(symbol)
        val interval = toKrakenIntervalMinutes(timeframe)
        val req = Request.Builder()
            .url("https://api.kraken.com/0/public/OHLC?pair=$pair&interval=$interval")
            .get()
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Kraken OHLC HTTP ${res.code}")
            val body = res.body?.string() ?: error("Kraken OHLC empty response")
            val root = org.json.JSONObject(body)
            val errors = root.optJSONArray("error")
            if (errors != null && errors.length() > 0) error("Kraken OHLC error: $errors")
            val result = root.getJSONObject("result")
            val firstKey = result.keys().asSequence().firstOrNull { it != "last" } ?: error("Kraken OHLC missing result")
            val arr = result.getJSONArray(firstKey)
            val from = kotlin.math.max(0, arr.length() - limit)
            (from until arr.length()).map { idx ->
                val row = arr.getJSONArray(idx)
                Candle(
                    symbol = symbol,
                    timeframe = timeframe,
                    openTimeEpochMs = row.getLong(0) * 1000L,
                    open = row.getString(1).toBigDecimal(),
                    high = row.getString(2).toBigDecimal(),
                    low = row.getString(3).toBigDecimal(),
                    close = row.getString(4).toBigDecimal(),
                    volume = row.getString(6).toBigDecimal()
                )
            }
        }
    }

    override suspend fun getAvailableBalances(): Map<String, BigDecimal> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Kraken API key and private key are required to read balances.")

        val balanceEx = runCatching { privateJson("/0/private/BalanceEx", emptyMap()) }.getOrNull()
        if (balanceEx != null) {
            val result = balanceEx.optJSONObject("result") ?: return@withContext emptyMap()
            val out = linkedMapOf<String, BigDecimal>()
            result.keys().forEach { asset ->
                val item = result.optJSONObject(asset)
                if (item != null) {
                    val total = item.optString("balance", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                    val credit = item.optString("credit", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                    val creditUsed = item.optString("credit_used", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                    val heldTrade = item.optString("hold_trade", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                    val available = total.add(credit).subtract(creditUsed).subtract(heldTrade).max(BigDecimal.ZERO)
                    putBalanceAliases(out, asset, available)
                }
            }
            return@withContext out
        }

        val balance = privateJson("/0/private/Balance", emptyMap())
        val result = balance.optJSONObject("result") ?: return@withContext emptyMap()
        val out = linkedMapOf<String, BigDecimal>()
        result.keys().forEach { asset ->
            val value = result.optString(asset, "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            putBalanceAliases(out, asset, value)
        }
        out
    }


    override suspend fun getPortfolioBalances(): List<BalanceInfo> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Kraken API key and private key are required to read portfolio balances.")
        val lines = linkedMapOf<String, BalanceInfo>()
        val balanceEx = runCatching { privateJson("/0/private/BalanceEx", emptyMap()) }.getOrNull()
        if (balanceEx != null) {
            val result = balanceEx.optJSONObject("result") ?: return@withContext emptyList()
            result.keys().forEach { asset ->
                val item = result.optJSONObject(asset) ?: return@forEach
                val total = item.optString("balance", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                val credit = item.optString("credit", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                val creditUsed = item.optString("credit_used", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                val hold = item.optString("hold_trade", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                val free = total.add(credit).subtract(creditUsed).subtract(hold).max(BigDecimal.ZERO)
                if (total > BigDecimal.ZERO || free > BigDecimal.ZERO || hold > BigDecimal.ZERO) {
                    val normalized = normalizeKrakenAsset(asset)
                    val prev = lines[normalized]
                    lines[normalized] = if (prev == null) {
                        BalanceInfo(asset = normalized, total = total, free = free, holdTrade = hold)
                    } else {
                        prev.copy(total = prev.total.add(total), free = prev.free.add(free), holdTrade = prev.holdTrade.add(hold))
                    }
                }
            }
            return@withContext lines.values.sortedByDescending { it.total }
        }

        val balance = privateJson("/0/private/Balance", emptyMap())
        val result = balance.optJSONObject("result") ?: return@withContext emptyList()
        result.keys().forEach { asset ->
            val value = result.optString(asset, "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            if (value > BigDecimal.ZERO) {
                val normalized = normalizeKrakenAsset(asset)
                val prev = lines[normalized]
                lines[normalized] = if (prev == null) BalanceInfo(normalized, value, value) else prev.copy(total = prev.total.add(value), free = prev.free.add(value))
            }
        }
        lines.values.sortedByDescending { it.total }
    }

    override suspend fun getBalanceDiagnostics(): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) return@withContext listOf("Kraken diagnostics skipped: missing API credentials.")
        val lines = mutableListOf<String>()

        runCatching { privateJson("/0/private/BalanceEx", emptyMap()) }
            .onSuccess { root ->
                val result = root.optJSONObject("result")
                if (result != null) {
                    result.keys().forEach { asset ->
                        val item = result.optJSONObject(asset) ?: return@forEach
                        val total = item.optString("balance", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val hold = item.optString("hold_trade", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val credit = item.optString("credit", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val creditUsed = item.optString("credit_used", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val available = total.add(credit).subtract(creditUsed).subtract(hold).max(BigDecimal.ZERO)
                        if (total > BigDecimal.ZERO || available > BigDecimal.ZERO || hold > BigDecimal.ZERO) {
                            val normalized = normalizeKrakenAsset(asset)
                            lines += "Kraken balance detail: $asset normalized=$normalized total=${total.scale8()}, holdTrade=${hold.scale8()}, free=${available.scale8()}"
                        }
                    }
                }
            }
            .onFailure { lines += "Kraken BalanceEx diagnostics failed: ${it.message}" }



        runCatching { privateJson("/0/private/Balance", emptyMap()) }
            .onSuccess { root ->
                val result = root.optJSONObject("result")
                if (result != null) {
                    result.keys().forEach { asset ->
                        val value = result.optString(asset, "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        if (value > BigDecimal.ZERO) {
                            val normalized = normalizeKrakenAsset(asset)
                            lines += "Kraken raw Balance: $asset normalized=$normalized total=${value.scale2()}"
                        }
                    }
                }
            }
            .onFailure { lines += "Kraken raw Balance diagnostics failed: ${it.message}" }

        runCatching { privateJson("/0/private/TradeBalance", mapOf("asset" to "ZEUR")) }
            .onSuccess { root ->
                val result = root.optJSONObject("result")
                if (result != null) {
                    val eb = result.optString("eb", "0")
                    val tb = result.optString("tb", "0")
                    val m = result.optString("m", "0")
                    val n = result.optString("n", "0")
                    lines += "Kraken trade balance EUR: equivalentBalance=$eb, tradeBalance=$tb, margin=$m, unrealizedPnL=$n"
                }
            }
            .onFailure { lines += "Kraken TradeBalance diagnostics failed: ${it.message}" }

        runCatching { privateJson("/0/private/OpenOrders", mapOf("trades" to "false")) }
            .onSuccess { root ->
                val open = root.optJSONObject("result")?.optJSONObject("open")
                val count = open?.length() ?: 0
                var lockedEur = BigDecimal.ZERO
                val samples = mutableListOf<String>()
                if (open != null) {
                    open.keys().forEach { txid ->
                        val item = open.optJSONObject(txid) ?: return@forEach
                        val descr = item.optJSONObject("descr")
                        val type = descr?.optString("type", "")?.lowercase().orEmpty()
                        val pair = descr?.optString("pair", "").orEmpty()
                        val price = (descr?.optString("price", "0") ?: "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val vol = item.optString("vol", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val volExec = item.optString("vol_exec", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val remaining = vol.subtract(volExec).max(BigDecimal.ZERO)
                        if (type == "buy" && pair.uppercase().contains("EUR")) {
                            lockedEur = lockedEur.add(price.multiply(remaining))
                        }
                        if (samples.size < 3) {
                            samples += "$type $pair remaining=${remaining.stripTrailingZeros().toPlainString()} price=${price.stripTrailingZeros().toPlainString()}"
                        }
                    }
                }
                lines += "Kraken open orders: count=$count, estimated EUR locked=${lockedEur.setScale(2, RoundingMode.UP)}"
                samples.forEach { lines += "Open order: $it" }
            }
            .onFailure { lines += "Kraken open-order diagnostics failed: ${it.message}" }

        if (lines.isEmpty()) listOf("Kraken diagnostics: no EUR balance/open-order details returned.") else lines
    }


    override suspend fun placeOrder(request: OrderRequest): OrderResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Kraken API key and private key are required for live trading.")
        val path = "/0/private/AddOrder"
        val nonce = System.currentTimeMillis().toString()
        val orderType = if (request.orderType == OrderType.MARKET) "market" else "limit"
        val form = linkedMapOf(
            "nonce" to nonce,
            "pair" to toKrakenPair(request.symbol),
            "type" to if (request.side == OrderSide.BUY) "buy" else "sell",
            "ordertype" to orderType,
            "volume" to request.quantity.stripTrailingZeros().toPlainString(),
            "userref" to userRefFromClientOrderId(request.clientOrderId).toString(),
            "validate" to "false"
        )
        if (request.orderType == OrderType.LIMIT) {
            val limit = request.limitPrice ?: error("Limit price is required for Kraken limit orders.")
            form["price"] = limit.stripTrailingZeros().toPlainString()
        }
        val encoded = form.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val signature = krakenSignature(path, nonce, encoded, secretKey)
        val body = encoded.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url("https://api.kraken.com$path")
            .addHeader("API-Key", apiKey)
            .addHeader("API-Sign", signature)
            .post(body)
            .build()
        http.newCall(req).execute().use { res ->
            val responseBody = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("Kraken AddOrder HTTP ${res.code}: $responseBody")
            val root = org.json.JSONObject(responseBody)
            val errors = root.optJSONArray("error")
            if (errors != null && errors.length() > 0) error("Kraken AddOrder error: $errors")
            val result = root.getJSONObject("result")
            val txidArray = result.optJSONArray("txid")
            val txid = if (txidArray != null && txidArray.length() > 0) txidArray.getString(0) else request.clientOrderId
            OrderResult(
                exchangeOrderId = txid,
                symbol = request.symbol,
                side = request.side,
                executedQuantity = BigDecimal.ZERO,
                averagePrice = request.limitPrice ?: BigDecimal.ZERO,
                fee = BigDecimal.ZERO,
                paper = false
            )
        }
    }

    private fun privateJson(path: String, parameters: Map<String, String>): org.json.JSONObject {
        val nonce = System.currentTimeMillis().toString()
        val form = linkedMapOf("nonce" to nonce)
        form.putAll(parameters)
        val encoded = form.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val signature = krakenSignature(path, nonce, encoded, secretKey)
        val body = encoded.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url("https://api.kraken.com$path")
            .addHeader("API-Key", apiKey)
            .addHeader("API-Sign", signature)
            .post(body)
            .build()
        http.newCall(req).execute().use { res ->
            val responseBody = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("Kraken ${path.substringAfterLast('/')} HTTP ${res.code}: $responseBody")
            val root = org.json.JSONObject(responseBody)
            val errors = root.optJSONArray("error")
            if (errors != null && errors.length() > 0) error("Kraken ${path.substringAfterLast('/')} error: $errors")
            return root
        }
    }

    private fun putBalanceAliases(out: MutableMap<String, BigDecimal>, rawAsset: String, value: BigDecimal) {
        val raw = rawAsset.uppercase()
        val normalized = normalizeKrakenAsset(rawAsset)
        out[raw] = value
        // Sum canonical aliases so EUR reflects all Kraken EUR-coded free balances returned by the API.
        out[normalized] = (out[normalized] ?: BigDecimal.ZERO).add(value)
    }

    private fun normalizeKrakenAsset(rawAsset: String): String {
        val a = rawAsset.uppercase().substringBefore('.')
        return when (a) {
            "ZEUR", "EUR" -> "EUR"
            "XXBT", "XBT", "BTC" -> "BTC"
            "XETH", "ETH" -> "ETH"
            "XXRP", "XRP" -> "XRP"
            else -> a.removePrefix("X").removePrefix("Z")
        }
    }

    private fun BigDecimal.scale2(): String = setScale(2, RoundingMode.DOWN).toPlainString()

    private fun BigDecimal.scale8(): String = setScale(8, RoundingMode.DOWN).stripTrailingZeros().toPlainString()

    private fun toKrakenPair(symbol: String): String {
        val normalized = symbol.uppercase().replace("/", "")
        return when (normalized) {
            "BTCEUR", "XBTEUR" -> "XXBTZEUR"
            "ETHEUR" -> "XETHZEUR"
            "SOLEUR" -> "SOLEUR"
            "ADAEUR" -> "ADAEUR"
            "XRPEUR" -> "XXRPZEUR"
            "DOTEUR" -> "DOTEUR"
            else -> normalized.replace("BTC", "XBT")
        }
    }

    private fun toKrakenIntervalMinutes(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.M1 -> 1
        Timeframe.M5 -> 5
        Timeframe.M15 -> 15
        Timeframe.H1 -> 60
        Timeframe.H4 -> 240
    }

    private fun userRefFromClientOrderId(clientOrderId: String): Int {
        return clientOrderId.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }
    }

    private fun krakenSignature(path: String, nonce: String, postData: String, secret: String): String {
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        val sha256Digest = sha256.digest((nonce + postData).toByteArray(Charsets.UTF_8))
        val message = path.toByteArray(Charsets.UTF_8) + sha256Digest
        val mac = javax.crypto.Mac.getInstance("HmacSHA512")
        val secretBytes = android.util.Base64.decode(secret, android.util.Base64.DEFAULT)
        mac.init(javax.crypto.spec.SecretKeySpec(secretBytes, "HmacSHA512"))
        return android.util.Base64.encodeToString(mac.doFinal(message), android.util.Base64.NO_WRAP)
    }
}


class CoinbaseAdvancedClient(
    private val apiKey: String,
    private val secretKey: String
) : BaseExchangeClient("Coinbase Advanced") {
    override suspend fun placeOrder(request: OrderRequest): OrderResult {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Coinbase API key and secret are required for live trading.")
        error("Coinbase live order signing is intentionally disabled until the selected Belgian account capability is verified.")
    }
}

class BitvavoClient(
    private val apiKey: String,
    private val secretKey: String
) : BaseExchangeClient("Bitvavo") {
    override suspend fun placeOrder(request: OrderRequest): OrderResult {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Bitvavo API key and secret are required for live trading.")
        error("Bitvavo live order signing is intentionally disabled until the selected Belgian account capability is verified.")
    }
}

class ManualExecutionClient : CryptoExchangeClient {
    private val delegate = PaperExchangeClient()

    override suspend fun getTicker(symbol: String): MarketTicker = delegate.getTicker(symbol)
    override suspend fun getCandles(symbol: String, timeframe: Timeframe, limit: Int): List<Candle> =
        delegate.getCandles(symbol, timeframe, limit)

    override suspend fun placeOrder(request: OrderRequest): OrderResult {
        error("Manual execution mode: the app creates the trade plan, but you must place the order yourself in a compliant exchange app.")
    }
}

data class ExchangeCapability(
    val provider: ExchangeProvider,
    val displayName: String,
    val marketData: Boolean,
    val liveTrading: Boolean,
    val manualOnly: Boolean,
    val warning: String
)

object ExchangeCapabilityChecker {
    fun capability(provider: ExchangeProvider): ExchangeCapability = when (provider) {
        ExchangeProvider.PAPER -> ExchangeCapability(provider, "Paper Trading", true, false, false, "Simulation only. No real orders are sent.")
        ExchangeProvider.BINANCE_READ_ONLY -> ExchangeCapability(provider, "Binance Read-Only", true, false, true, "Belgium mode: Binance trading is disabled. Use signals/manual mode only.")
        ExchangeProvider.KRAKEN -> ExchangeCapability(provider, "Kraken", true, true, false, "Verify your Belgian account supports API spot trading before enabling LIVE_AUTO.")
        ExchangeProvider.COINBASE_ADVANCED -> ExchangeCapability(provider, "Coinbase Advanced", true, true, false, "Verify your Belgian account supports API spot trading before enabling LIVE_AUTO.")
        ExchangeProvider.BITVAVO -> ExchangeCapability(provider, "Bitvavo", true, true, false, "Verify your Belgian account supports API spot trading before enabling LIVE_AUTO.")
        ExchangeProvider.MANUAL -> ExchangeCapability(provider, "Manual Execution", true, false, true, "The app produces trade plans only; you execute them yourself.")
    }
}
