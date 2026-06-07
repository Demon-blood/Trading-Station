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
import com.ksp.cryptobot.core.SymbolDiscoveryCandidate

/**
 * v1.3 Kraken-live exchange layer.
 *
 * The public market-data methods are implemented with resilient fallbacks so the app can keep scanning even when
 * a provider is unavailable. Live trading remains blocked unless the selected provider explicitly supports it and
 * credentials are present. This avoids bypassing jurisdiction restrictions while still making the app usable in
 * Belgium through Kraken live trading, paper trading, or manual execution.
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

    private data class KrakenPairRule(
        val requestedSymbol: String,
        val canonicalSymbol: String,
        val exchangePair: String,
        val altName: String,
        val baseAsset: String,
        val quoteAsset: String,
        val minOrderSize: BigDecimal,
        val priceDecimals: Int,
        val quantityDecimals: Int,
        val tradable: Boolean,
        val status: String
    )

    @Volatile private var pairCache: Map<String, KrakenPairRule> = emptyMap()
    @Volatile private var pairCacheLoadedAtMs: Long = 0L

    override suspend fun validateSymbol(symbol: String): ExchangeSymbolInfo = withContext(Dispatchers.IO) {
        val rule = resolvePairRule(symbol)
        ExchangeSymbolInfo(
            requestedSymbol = symbol,
            normalizedSymbol = rule.canonicalSymbol,
            exchangePair = rule.exchangePair,
            altName = rule.altName,
            baseAsset = rule.baseAsset,
            quoteAsset = rule.quoteAsset,
            minOrderSize = rule.minOrderSize,
            priceDecimals = rule.priceDecimals,
            quantityDecimals = rule.quantityDecimals,
            tradable = rule.tradable,
            reason = if (rule.tradable) "Tradable on Kraken. status=${rule.status}" else "Not tradable on Kraken. status=${rule.status}"
        )
    }


    override suspend fun discoverTradableSymbols(quoteAsset: String, limit: Int): List<SymbolDiscoveryCandidate> = withContext(Dispatchers.IO) {
        val quote = quoteAsset.uppercase()
        ensurePairCache().values
            .distinctBy { it.canonicalSymbol }
            .filter { it.quoteAsset == quote && it.tradable }
            .filterNot { it.baseAsset.contains(".") || it.baseAsset.contains("FEE") }
            .sortedBy { it.canonicalSymbol }
            .take(limit.coerceAtLeast(1))
            .map { rule ->
                SymbolDiscoveryCandidate(
                    symbol = rule.canonicalSymbol,
                    exchangePair = rule.exchangePair,
                    baseAsset = rule.baseAsset,
                    quoteAsset = rule.quoteAsset,
                    tradable = rule.tradable,
                    minOrderSize = rule.minOrderSize,
                    reason = "Discovered from Kraken AssetPairs. status=${rule.status}, min=${rule.minOrderSize}, pair=${rule.exchangePair}"
                )
            }
    }

    override suspend fun getTicker(symbol: String): MarketTicker = withContext(Dispatchers.IO) {
        val rule = resolvePairRule(symbol)
        if (!rule.tradable) error("Kraken pair not tradable: ${rule.canonicalSymbol}. ${rule.status}")
        val req = Request.Builder()
            .url("https://api.kraken.com/0/public/Ticker?pair=${rule.exchangePair}")
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
                last.subtract(open).divide(open, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
            MarketTicker(
                symbol = rule.canonicalSymbol,
                lastPrice = last,
                bid = bid,
                ask = ask,
                volume24h = volume.multiply(last),
                priceChangePercent24h = changePct
            )
        }
    }

    override suspend fun getCandles(symbol: String, timeframe: Timeframe, limit: Int): List<Candle> = withContext(Dispatchers.IO) {
        val rule = resolvePairRule(symbol)
        if (!rule.tradable) error("Kraken pair not tradable: ${rule.canonicalSymbol}. ${rule.status}")
        val interval = toKrakenIntervalMinutes(timeframe)
        val req = Request.Builder()
            .url("https://api.kraken.com/0/public/OHLC?pair=${rule.exchangePair}&interval=$interval")
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
                    symbol = rule.canonicalSymbol,
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
                    lines[normalized] = if (prev == null) BalanceInfo(normalized, total, free, hold) else prev.copy(total = prev.total.add(total), free = prev.free.add(free), holdTrade = prev.holdTrade.add(hold))
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

    override suspend fun getOpenOrders(): List<LiveOrderInfo> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) return@withContext emptyList()
        val root = privateJson("/0/private/OpenOrders", mapOf("trades" to "false"))
        val open = root.optJSONObject("result")?.optJSONObject("open") ?: return@withContext emptyList()
        val orders = mutableListOf<LiveOrderInfo>()
        open.keys().forEach { txid ->
            val item = open.optJSONObject(txid) ?: return@forEach
            val descr = item.optJSONObject("descr")
            val pair = descr?.optString("pair", "") ?: ""
            val side = if ((descr?.optString("type", "buy") ?: "buy").lowercase() == "sell") OrderSide.SELL else OrderSide.BUY
            val orderTypeRaw = (descr?.optString("ordertype", "limit") ?: "limit").lowercase()
            val type = when (orderTypeRaw) {
                "market" -> OrderType.MARKET
                "stop-loss" -> OrderType.STOP_LOSS
                "take-profit" -> OrderType.TAKE_PROFIT
                else -> OrderType.LIMIT
            }
            val price = (descr?.optString("price", "0") ?: "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val vol = item.optString("vol", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val volExec = item.optString("vol_exec", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            orders += LiveOrderInfo(
                exchangeOrderId = txid,
                symbol = fromKrakenPair(pair),
                side = side,
                orderType = type,
                price = price,
                quantity = vol,
                executedQuantity = volExec,
                remainingQuantity = vol.subtract(volExec).max(BigDecimal.ZERO),
                status = item.optString("status", "open"),
                openedAtEpochSeconds = item.optLong("opentm", 0L),
                description = descr?.optString("order", "") ?: ""
            )
        }
        orders.sortedByDescending { it.openedAtEpochSeconds }
    }

    override suspend fun getClosedOrders(limit: Int): List<ClosedOrderInfo> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) return@withContext emptyList()
        val root = privateJson("/0/private/ClosedOrders", mapOf("trades" to "true", "closetime" to "close"))
        val closed = root.optJSONObject("result")?.optJSONObject("closed") ?: return@withContext emptyList()
        val orders = mutableListOf<ClosedOrderInfo>()
        closed.keys().forEach { txid ->
            if (orders.size >= limit) return@forEach
            val item = closed.optJSONObject(txid) ?: return@forEach
            val descr = item.optJSONObject("descr")
            val pair = descr?.optString("pair", "") ?: ""
            val side = if ((descr?.optString("type", "buy") ?: "buy").lowercase() == "sell") OrderSide.SELL else OrderSide.BUY
            val rawType = (descr?.optString("ordertype", "limit") ?: "limit").lowercase()
            val type = when (rawType) {
                "market" -> OrderType.MARKET
                "stop-loss" -> OrderType.STOP_LOSS
                "take-profit" -> OrderType.TAKE_PROFIT
                else -> OrderType.LIMIT
            }
            val price = item.optString("price", descr?.optString("price", "0") ?: "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val vol = item.optString("vol", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val volExec = item.optString("vol_exec", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val fee = item.optString("fee", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            orders += ClosedOrderInfo(
                exchangeOrderId = txid,
                symbol = fromKrakenPair(pair),
                side = side,
                orderType = type,
                price = price,
                quantity = vol,
                executedQuantity = volExec,
                fee = fee,
                closedAtEpochSeconds = item.optLong("closetm", item.optLong("opentm", 0L)),
                status = item.optString("status", "closed"),
                description = descr?.optString("order", "") ?: ""
            )
        }
        orders.sortedByDescending { it.closedAtEpochSeconds }.take(limit)
    }

    override suspend fun cancelOrder(orderId: String): Boolean = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Kraken API key and private key are required to cancel orders.")
        val root = privateJson("/0/private/CancelOrder", mapOf("txid" to orderId))
        val count = root.optJSONObject("result")?.optInt("count", 0) ?: 0
        count > 0
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
            }.onFailure { lines += "Kraken BalanceEx diagnostics failed: ${it.message}" }
        runCatching { privateJson("/0/private/Balance", emptyMap()) }
            .onSuccess { root ->
                val result = root.optJSONObject("result")
                if (result != null) {
                    result.keys().forEach { asset ->
                        val value = result.optString(asset, "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        if (value > BigDecimal.ZERO) lines += "Kraken raw Balance: $asset normalized=${normalizeKrakenAsset(asset)} total=${value.scale2()}"
                    }
                }
            }.onFailure { lines += "Kraken raw Balance diagnostics failed: ${it.message}" }
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
                        val type = descr?.optString("type", "").orEmpty().lowercase()
                        val pair = descr?.optString("pair", "").orEmpty()
                        val price = (descr?.optString("price", "0") ?: "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val vol = item.optString("vol", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val volExec = item.optString("vol_exec", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                        val remaining = vol.subtract(volExec).max(BigDecimal.ZERO)
                        if (type == "buy" && pair.uppercase().contains("EUR")) lockedEur = lockedEur.add(price.multiply(remaining))
                        if (samples.size < 3) samples += "$type $pair remaining=${remaining.stripTrailingZeros().toPlainString()} price=${price.stripTrailingZeros().toPlainString()}"
                    }
                }
                lines += "Kraken open orders: count=$count, estimated EUR locked=${lockedEur.setScale(2, RoundingMode.UP)}"
                samples.forEach { lines += "Open order: $it" }
            }.onFailure { lines += "Kraken open-order diagnostics failed: ${it.message}" }
        lines += "Kraken pair cache: ${ensurePairCache().size} EUR-capable pairs loaded from AssetPairs."
        if (lines.isEmpty()) listOf("Kraken diagnostics: no balance/open-order details returned.") else lines
    }

    override suspend fun placeOrder(request: OrderRequest): OrderResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Kraken API key and private key are required for live trading.")
        val rule = resolvePairRule(request.symbol)
        if (!rule.tradable) error("Kraken pair is not tradable: ${request.symbol}. ${rule.status}")
        val path = "/0/private/AddOrder"
        val nonce = System.currentTimeMillis().toString()
        val orderType = when (request.orderType) {
            OrderType.MARKET -> "market"
            OrderType.STOP_LOSS -> "stop-loss"
            OrderType.TAKE_PROFIT -> "take-profit"
            OrderType.LIMIT -> "limit"
        }
        val cleanQuantity = request.quantity.setScale(rule.quantityDecimals, RoundingMode.DOWN)
        if (cleanQuantity < rule.minOrderSize) error("Kraken order size too small for ${rule.canonicalSymbol}. quantity=$cleanQuantity min=${rule.minOrderSize}")
        val form = linkedMapOf(
            "nonce" to nonce,
            "pair" to rule.exchangePair,
            "type" to if (request.side == OrderSide.BUY) "buy" else "sell",
            "ordertype" to orderType,
            "volume" to cleanQuantity.stripTrailingZeros().toPlainString(),
            "userref" to userRefFromClientOrderId(request.clientOrderId).toString(),
            "validate" to "false"
        )
        if (request.orderType != OrderType.MARKET) {
            val price = request.limitPrice ?: error("Price/trigger price is required for Kraken ${request.orderType} orders.")
            form["price"] = price.setScale(rule.priceDecimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        }
        val encoded = encodeForm(form)
        val signature = krakenSignature(path, nonce, encoded, secretKey)
        val body = encoded.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
        val req = Request.Builder().url("https://api.kraken.com$path").addHeader("API-Key", apiKey).addHeader("API-Sign", signature).post(body).build()
        http.newCall(req).execute().use { res ->
            val responseBody = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("Kraken AddOrder HTTP ${res.code}: $responseBody")
            val root = org.json.JSONObject(responseBody)
            val errors = root.optJSONArray("error")
            if (errors != null && errors.length() > 0) error("Kraken AddOrder error: $errors")
            val result = root.getJSONObject("result")
            val txidArray = result.optJSONArray("txid")
            val txid = if (txidArray != null && txidArray.length() > 0) txidArray.getString(0) else request.clientOrderId
            OrderResult(txid, rule.canonicalSymbol, request.side, BigDecimal.ZERO, request.limitPrice ?: BigDecimal.ZERO, BigDecimal.ZERO, false)
        }
    }

    private fun privateJson(path: String, parameters: Map<String, String>): org.json.JSONObject {
        val nonce = System.currentTimeMillis().toString()
        val form = linkedMapOf("nonce" to nonce)
        form.putAll(parameters)
        val encoded = encodeForm(form)
        val signature = krakenSignature(path, nonce, encoded, secretKey)
        val body = encoded.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
        val req = Request.Builder().url("https://api.kraken.com$path").addHeader("API-Key", apiKey).addHeader("API-Sign", signature).post(body).build()
        http.newCall(req).execute().use { res ->
            val responseBody = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("Kraken ${path.substringAfterLast('/')} HTTP ${res.code}: $responseBody")
            val root = org.json.JSONObject(responseBody)
            val errors = root.optJSONArray("error")
            if (errors != null && errors.length() > 0) error("Kraken ${path.substringAfterLast('/')} error: $errors")
            return root
        }
    }

    private fun encodeForm(form: Map<String, String>): String = form.entries.joinToString("&") { (k, v) ->
        "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
    }

    private fun putBalanceAliases(out: MutableMap<String, BigDecimal>, rawAsset: String, value: BigDecimal) {
        val raw = rawAsset.uppercase()
        val normalized = normalizeKrakenAsset(rawAsset)
        out[raw] = value
        out[normalized] = (out[normalized] ?: BigDecimal.ZERO).add(value)
    }

    private fun normalizeKrakenAsset(rawAsset: String): String {
        val a = rawAsset.uppercase().substringBefore('.')
        return when (a) {
            "ZEUR", "EUR" -> "EUR"
            "XXBT", "XBT", "BTC" -> "BTC"
            "XETH", "ETH" -> "ETH"
            "XXRP", "XRP" -> "XRP"
            "XDG", "XXDG", "DOGE" -> "DOGE"
            else -> a.removePrefix("X").removePrefix("Z")
        }
    }

    private suspend fun resolvePairRule(symbol: String): KrakenPairRule {
        val cache = ensurePairCache()
        val normalized = symbol.uppercase().replace("/", "").replace("XBT", "BTC")
        return cache[normalized] ?: cache[toFallbackCanonical(normalized)] ?: fallbackRule(normalized)
    }

    private fun toFallbackCanonical(symbol: String): String = when (symbol.uppercase().replace("/", "").replace("XBT", "BTC")) {
        "BTCEUR" -> "BTCEUR"
        else -> symbol.uppercase().replace("/", "")
    }

    private fun fallbackRule(symbol: String): KrakenPairRule {
        val normalized = symbol.uppercase().replace("/", "").replace("XBT", "BTC")
        val pair = when (normalized) {
            "BTCEUR" -> "XXBTZEUR"
            "ETHEUR" -> "XETHZEUR"
            "XRPEUR" -> "XXRPZEUR"
            else -> normalized.replace("BTC", "XBT")
        }
        return KrakenPairRule(normalized, normalized, pair, normalized.replace("BTC", "XBT"), normalized.removeSuffix("EUR"), "EUR", BigDecimal.ZERO, 8, 8, true, "fallback")
    }

    private fun fromKrakenPair(pair: String): String {
        val normalized = pair.uppercase()
        return when {
            normalized.contains("XBT") -> "BTCEUR"
            normalized.contains("ETH") -> "ETHEUR"
            normalized.contains("XRP") -> "XRPEUR"
            normalized.endsWith("EUR") -> normalized.replace("ZEUR", "EUR").replace("Z", "").replace("X", "")
            else -> normalized
        }
    }

    private fun ensurePairCache(): Map<String, KrakenPairRule> {
        val age = System.currentTimeMillis() - pairCacheLoadedAtMs
        if (pairCache.isNotEmpty() && age < 6 * 60 * 60 * 1000L) return pairCache
        val req = Request.Builder().url("https://api.kraken.com/0/public/AssetPairs").get().build()
        val loaded = linkedMapOf<String, KrakenPairRule>()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Kraken AssetPairs HTTP ${res.code}")
            val body = res.body?.string() ?: error("Kraken AssetPairs empty response")
            val root = org.json.JSONObject(body)
            val errors = root.optJSONArray("error")
            if (errors != null && errors.length() > 0) error("Kraken AssetPairs error: $errors")
            val result = root.getJSONObject("result")
            result.keys().forEach { pairId ->
                val item = result.optJSONObject(pairId) ?: return@forEach
                val alt = item.optString("altname", pairId).uppercase()
                val ws = item.optString("wsname", "").uppercase()
                val status = item.optString("status", "online")
                val base = normalizeKrakenAsset(item.optString("base", alt.removeSuffix("EUR")))
                val quote = normalizeKrakenAsset(item.optString("quote", if (alt.endsWith("EUR")) "ZEUR" else ""))
                if (quote != "EUR") return@forEach
                val canonical = "${base}EUR".replace("XBTEUR", "BTCEUR")
                val rule = KrakenPairRule(
                    requestedSymbol = canonical,
                    canonicalSymbol = canonical,
                    exchangePair = pairId,
                    altName = alt,
                    baseAsset = base,
                    quoteAsset = quote,
                    minOrderSize = item.optString("ordermin", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    priceDecimals = item.optInt("pair_decimals", 8),
                    quantityDecimals = item.optInt("lot_decimals", 8),
                    tradable = status.equals("online", ignoreCase = true) || status.isBlank(),
                    status = status
                )
                loaded[canonical] = rule
                loaded[alt.replace("XBT", "BTC")] = rule
                if (ws.isNotBlank()) loaded[ws.replace("/", "").replace("XBT", "BTC")] = rule
            }
        }
        pairCache = loaded
        pairCacheLoadedAtMs = System.currentTimeMillis()
        return loaded
    }

    private fun toKrakenIntervalMinutes(timeframe: Timeframe): Int = when (timeframe) {
        Timeframe.M1 -> 1
        Timeframe.M5 -> 5
        Timeframe.M15 -> 15
        Timeframe.H1 -> 60
        Timeframe.H4 -> 240
    }

    private fun userRefFromClientOrderId(clientOrderId: String): Int = clientOrderId.hashCode().let { if (it == Int.MIN_VALUE) 0 else kotlin.math.abs(it) }

    private fun krakenSignature(path: String, nonce: String, postData: String, secret: String): String {
        val sha256 = java.security.MessageDigest.getInstance("SHA-256")
        val sha256Digest = sha256.digest((nonce + postData).toByteArray(Charsets.UTF_8))
        val message = path.toByteArray(Charsets.UTF_8) + sha256Digest
        val mac = javax.crypto.Mac.getInstance("HmacSHA512")
        val secretBytes = android.util.Base64.decode(secret, android.util.Base64.DEFAULT)
        mac.init(javax.crypto.spec.SecretKeySpec(secretBytes, "HmacSHA512"))
        return android.util.Base64.encodeToString(mac.doFinal(message), android.util.Base64.NO_WRAP)
    }

    private fun BigDecimal.scale2(): String = setScale(2, RoundingMode.DOWN).toPlainString()
    private fun BigDecimal.scale8(): String = setScale(8, RoundingMode.DOWN).stripTrailingZeros().toPlainString()
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
        ExchangeProvider.COINBASE_ADVANCED -> ExchangeCapability(provider, "Coinbase Advanced", true, false, true, "Coinbase live trading is not exposed in v1.5.0; use Kraken for live orders.")
        ExchangeProvider.BITVAVO -> ExchangeCapability(provider, "Bitvavo", true, false, true, "Bitvavo live trading is not exposed in v1.5.0; use Kraken for live orders.")
        ExchangeProvider.MANUAL -> ExchangeCapability(provider, "Manual Execution", true, false, true, "The app produces trade plans only; you execute them yourself.")
    }
}
