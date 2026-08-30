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
import java.security.MessageDigest
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
        val status: String,
        val minOrderCost: BigDecimal = BigDecimal.ZERO,
        val tickSize: BigDecimal = BigDecimal.ZERO
    )

    @Volatile private var pairCache: Map<String, KrakenPairRule> = emptyMap()
    @Volatile private var pairCacheLoadedAtMs: Long = 0L

    suspend fun getWebSocketsToken(): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            error("Kraken API key and private key are required for authenticated WebSocket execution state.")
        }
        val root = privateJson("/0/private/GetWebSocketsToken", emptyMap())
        root.optJSONObject("result")?.optString("token")?.takeIf { it.isNotBlank() }
            ?: error("Kraken GetWebSocketsToken returned no token.")
    }


    suspend fun accountAuthorityIdentity(): KrakenAccountAuthorityIdentity = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Kraken credentials are required for account authority identity.")
        val root = privateJson("/0/private/GetApiKeyInfo", emptyMap())
        val result = root.optJSONObject("result") ?: error("Kraken GetApiKeyInfo returned no result.")
        val iban = result.optString("iban", "").trim()
        require(iban.isNotBlank()) {
            "Kraken GetApiKeyInfo returned no account IIBAN. M12 refuses a key-specific fallback because different API keys on the same account must not create independent LIVE leases."
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("KRAKEN-IIBAN:" + iban).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        KrakenAccountAuthorityIdentity(
            accountKey = digest,
            source = "KRAKEN_IIBAN"
        )
    }

    suspend fun resolveClientOrderId(rawClientOrderId: String): KrakenClientOrderResolution = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Kraken credentials are required for client-order resolution.")
        val clientOrderId = KrakenClientOrderId.normalize(rawClientOrderId)

        fun parse(item: org.json.JSONObject, txid: String, isOpen: Boolean): KrakenClientOrderResolution {
            val descr = item.optJSONObject("descr")
            val pair = descr?.optString("pair", "").orEmpty()
            val side = if (descr?.optString("type", "buy").equals("sell", true)) OrderSide.SELL else OrderSide.BUY
            val orderType = when (descr?.optString("ordertype", "limit")?.lowercase()) {
                "market" -> OrderType.MARKET
                "stop-loss", "stop-loss-limit" -> OrderType.STOP_LOSS
                "take-profit", "take-profit-limit" -> OrderType.TAKE_PROFIT
                else -> OrderType.LIMIT
            }
            val qty = item.optString("vol", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val executed = item.optString("vol_exec", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val avg = item.optString("price", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val fee = item.optString("fee", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            return KrakenClientOrderResolution(
                found = true,
                open = isOpen,
                exchangeOrderId = txid,
                clientOrderId = item.optString("cl_ord_id", clientOrderId).ifBlank { clientOrderId },
                symbol = fromKrakenPair(pair),
                side = side,
                orderType = orderType,
                status = item.optString("status", if (isOpen) "open" else "closed"),
                quantity = qty,
                executedQuantity = executed,
                averageFillPrice = avg,
                fee = fee
            )
        }

        val openRoot = privateJson(
            "/0/private/OpenOrders",
            mapOf("trades" to "true", "cl_ord_id" to clientOrderId)
        )
        val open = openRoot.optJSONObject("result")?.optJSONObject("open")
        val openTxid = open?.keys()?.asSequence()?.firstOrNull()
        if (openTxid != null) {
            return@withContext parse(open.getJSONObject(openTxid), openTxid, true)
        }

        val closedRoot = privateJson(
            "/0/private/ClosedOrders",
            mapOf("trades" to "true", "closetime" to "close", "cl_ord_id" to clientOrderId)
        )
        val closed = closedRoot.optJSONObject("result")?.optJSONObject("closed")
        val closedTxid = closed?.keys()?.asSequence()?.firstOrNull()
        if (closedTxid != null) {
            return@withContext parse(closed.getJSONObject(closedTxid), closedTxid, false)
        }

        KrakenClientOrderResolution(found = false, open = false, clientOrderId = clientOrderId)
    }

    suspend fun setDeadMansSwitch(timeoutSeconds: Int): KrakenDeadManSwitchStatus = withContext(Dispatchers.IO) {
        require(timeoutSeconds in 0 until 86400) { "Kraken CancelAllOrdersAfter timeout must be 0..86399 seconds." }
        val root = privateJson(
            "/0/private/CancelAllOrdersAfter",
            mapOf("timeout" to timeoutSeconds.toString())
        )
        val result = root.optJSONObject("result") ?: error("Kraken CancelAllOrdersAfter returned no result.")
        KrakenDeadManSwitchStatus(
            timeoutSeconds = timeoutSeconds,
            currentTime = result.optString("currentTime", ""),
            triggerTime = result.optString("triggerTime", ""),
            enabled = timeoutSeconds > 0
        )
    }

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
            reason = if (rule.tradable) "Tradable on Kraken. status=${rule.status}; ordermin=${rule.minOrderSize}; costmin=${rule.minOrderCost}; tick=${rule.tickSize}" else "Not tradable on Kraken. status=${rule.status}",
            minOrderCost = rule.minOrderCost,
            tickSize = rule.tickSize
        )
    }


    override suspend fun discoverTradableSymbols(quoteAsset: String, limit: Int): List<SymbolDiscoveryCandidate> = withContext(Dispatchers.IO) {
        val quote = quoteAsset.uppercase().ifBlank { "ALL" }
        val allQuotes = quote == "ALL" || quote == "*" || quote == "ANY"
        ensurePairCache().values
            .distinctBy { it.exchangePair }
            .filter { (allQuotes || it.quoteAsset == quote) && it.tradable }
            .filterNot { it.baseAsset.contains(".") || it.quoteAsset.contains(".") || it.baseAsset.contains("FEE") || it.quoteAsset.contains("FEE") }
            .sortedWith(compareBy<KrakenPairRule>({ quotePriority(it.quoteAsset) }, { it.canonicalSymbol }))
            .take(limit.coerceAtLeast(1))
            .map { rule ->
                SymbolDiscoveryCandidate(
                    symbol = rule.canonicalSymbol,
                    exchangePair = rule.exchangePair,
                    baseAsset = rule.baseAsset,
                    quoteAsset = rule.quoteAsset,
                    tradable = rule.tradable,
                    minOrderSize = rule.minOrderSize,
                    minOrderCost = rule.minOrderCost,
                    tickSize = rule.tickSize,
                    reason = "Discovered from Kraken AssetPairs. quote=${rule.quoteAsset}, status=${rule.status}, min=${rule.minOrderSize}, costmin=${rule.minOrderCost}, tick=${rule.tickSize}, pair=${rule.exchangePair}"
                )
            }
    }

    override suspend fun getTicker(symbol: String): MarketTicker = withContext(Dispatchers.IO) {
        val rule = resolvePairRule(symbol)
        if (!rule.tradable) error("Kraken pair not tradable: ${rule.canonicalSymbol}. ${rule.status}")
        KrakenRealtimeMarketDataRegistry.ensureTicker(rule.canonicalSymbol)
        KrakenRealtimeMarketDataRegistry.freshTicker(rule.canonicalSymbol)?.let { return@withContext it }
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

    override suspend fun getOrderBook(symbol: String, depth: Int): OrderBookSnapshot? = withContext(Dispatchers.IO) {
        val rule = resolvePairRule(symbol)
        if (!rule.tradable) error("Kraken pair not tradable: ${rule.canonicalSymbol}. ${rule.status}")
        val safeDepth = depth.coerceIn(5, 100)
        val req = Request.Builder()
            .url("https://api.kraken.com/0/public/Depth?pair=${rule.exchangePair}&count=$safeDepth")
            .get()
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Kraken depth HTTP ${res.code}")
            val body = res.body?.string() ?: error("Kraken depth empty response")
            val root = org.json.JSONObject(body)
            val errors = root.optJSONArray("error")
            if (errors != null && errors.length() > 0) error("Kraken depth error: $errors")
            val result = root.getJSONObject("result")
            val firstKey = result.keys().asSequence().firstOrNull() ?: error("Kraken depth missing result")
            val item = result.getJSONObject(firstKey)

            fun parseLevels(name: String): List<OrderBookLevel> {
                val rows = item.getJSONArray(name)
                return (0 until rows.length()).mapNotNull { idx ->
                    val row = rows.optJSONArray(idx) ?: return@mapNotNull null
                    val price = row.optString(0).toBigDecimalOrNull() ?: return@mapNotNull null
                    val qty = row.optString(1).toBigDecimalOrNull() ?: return@mapNotNull null
                    OrderBookLevel(price = price, quantity = qty)
                }
            }

            OrderBookSnapshot(
                symbol = rule.canonicalSymbol,
                bids = parseLevels("bids"),
                asks = parseLevels("asks")
            )
        }
    }

    override suspend fun getCandles(symbol: String, timeframe: Timeframe, limit: Int): List<Candle> = withContext(Dispatchers.IO) {
        val rule = resolvePairRule(symbol)
        if (!rule.tradable) error("Kraken pair not tradable: ${rule.canonicalSymbol}. ${rule.status}")
        KrakenRealtimeMarketDataRegistry.ensureOhlc(rule.canonicalSymbol, timeframe)
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
            val restCandles = (from until arr.length()).map { idx ->
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
            KrakenRealtimeMarketDataRegistry.mergeLatestCandle(
                canonicalSymbol = rule.canonicalSymbol,
                timeframe = timeframe,
                restCandles = restCandles,
                limit = limit
            )
        }
    }

    override suspend fun getTradingFeeSchedule(symbol: String): TradingFeeSchedule? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) return@withContext null
        val rule = resolvePairRule(symbol)
        // Kraken Get Trade Volume returns the current account/pair fee tier. It requires Query Funds permission.
        val root = privateJson("/0/private/TradeVolume", mapOf("pair" to rule.exchangePair))
        val result = root.optJSONObject("result") ?: return@withContext null
        fun feeRate(section: String): BigDecimal? {
            val group = result.optJSONObject(section) ?: return null
            val direct = group.optJSONObject(rule.exchangePair)
                ?: group.optJSONObject(rule.altName)
                ?: group.keys().asSequence().firstOrNull()?.let { group.optJSONObject(it) }
                ?: return null
            // Kraken returns fee as percentage units (e.g. 0.40), convert to decimal rate (0.0040).
            return direct.optString("fee", "").toBigDecimalOrNull()?.divide(BigDecimal("100"), 12, RoundingMode.HALF_UP)
        }
        val taker = feeRate("fees") ?: return@withContext null
        val maker = feeRate("fees_maker") ?: taker
        TradingFeeSchedule(
            makerRate = maker.max(BigDecimal.ZERO),
            takerRate = taker.max(BigDecimal.ZERO),
            rollingVolumeUsd = result.optString("volume", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
            source = "KRAKEN_TRADE_VOLUME"
        )
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
            val avgFill = item.optString("price", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val fee = item.optString("fee", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val clientOrderId = item.optString("cl_ord_id", "")
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
                description = descr?.optString("order", "") ?: "",
                clientOrderId = clientOrderId,
                averageFillPrice = avgFill,
                fee = fee
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
                description = descr?.optString("order", "") ?: "",
                clientOrderId = item.optString("cl_ord_id", "")
            )
        }
        orders.sortedByDescending { it.closedAtEpochSeconds }.take(limit)
    }

    override suspend fun amendOrder(request: AtomicOrderAmendRequest): AtomicOrderAmendResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            error("Kraken API key and private key are required to amend orders.")
        }
        require(request.exchangeOrderId.isNotBlank() || request.clientOrderId.isNotBlank()) {
            "Kraken AmendOrder requires txid or cl_ord_id."
        }

        val rule = resolvePairRule(request.symbol)
        if (!rule.tradable) error("Kraken pair is not tradable: ${request.symbol}. ${rule.status}")

        val form = linkedMapOf<String, String>()
        if (request.clientOrderId.isNotBlank()) {
            form["cl_ord_id"] = KrakenClientOrderId.normalize(request.clientOrderId)
        } else {
            form["txid"] = request.exchangeOrderId
        }

        request.newTotalQuantity?.let { raw ->
            val total = raw.setScale(rule.quantityDecimals, RoundingMode.DOWN)
            require(total >= rule.minOrderSize) {
                "Kraken amended total quantity too small for ${rule.canonicalSymbol}. quantity=$total min=${rule.minOrderSize}"
            }
            form["order_qty"] = total.stripTrailingZeros().toPlainString()
        }

        request.newLimitPrice?.let { raw ->
            require(request.orderType == OrderType.LIMIT) {
                "M15 limit_price amend requires LIMIT order type."
            }
            val price = roundKrakenPriceToTick(
                raw,
                rule.tickSize,
                rule.priceDecimals,
                request.side,
                OrderType.LIMIT
            )
            form["limit_price"] = price.stripTrailingZeros().toPlainString()
        }

        request.newTriggerPrice?.let { raw ->
            require(request.orderType == OrderType.STOP_LOSS || request.orderType == OrderType.TAKE_PROFIT) {
                "M15 trigger_price amend requires a triggered order type."
            }
            val trigger = roundKrakenPriceToTick(
                raw,
                rule.tickSize,
                rule.priceDecimals,
                request.side,
                request.orderType
            )
            form["trigger_price"] = trigger.stripTrailingZeros().toPlainString()
        }

        request.postOnly?.let { post ->
            require(request.newLimitPrice != null && request.orderType == OrderType.LIMIT) {
                "Kraken post_only amend is valid only with a LIMIT price amendment."
            }
            form["post_only"] = post.toString()
        }
        request.deadline?.let { deadline ->
            val now = java.time.Instant.now()
            require(!deadline.isBefore(now.plusSeconds(2)) && !deadline.isAfter(now.plusSeconds(60))) {
                "Kraken AmendOrder deadline must be at least 2 seconds and no more than 60 seconds ahead."
            }
            form["deadline"] = deadline.toString()
        }

        require(form.keys.any { it in setOf("order_qty", "limit_price", "trigger_price") }) {
            "Kraken AmendOrder requires at least one mutable order field."
        }

        val root = privateJson("/0/private/AmendOrder", form)
        val result = root.optJSONObject("result") ?: error("Kraken AmendOrder returned no result.")
        val amendId = result.optString("amend_id", "")
        require(amendId.isNotBlank()) { "Kraken AmendOrder returned no amend_id." }

        AtomicOrderAmendResult(
            supported = true,
            amended = true,
            amendId = amendId,
            exchangeOrderId = request.exchangeOrderId,
            clientOrderId = request.clientOrderId,
            reason = "Kraken atomic amend accepted; order identifiers remain stable."
        )
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
        val nonce = KrakenNonceSequencer.next()
        val krakenClientOrderId = KrakenClientOrderId.normalize(request.clientOrderId)

        if (request.side == OrderSide.BUY) {
            val existingBuy = getOpenOrders().firstOrNull {
                it.symbol.equals(rule.canonicalSymbol, ignoreCase = true) && it.side == OrderSide.BUY
            }
            if (existingBuy != null) {
                error("Kraken duplicate entry blocked: open BUY already exists for ${rule.canonicalSymbol}; txid=${existingBuy.exchangeOrderId}, status=${existingBuy.status}, remaining=${existingBuy.remainingQuantity}.")
            }
        }

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
            "cl_ord_id" to krakenClientOrderId,
            "validate" to "false"
        )
        val orderPriceForMinimum = if (request.orderType == OrderType.MARKET) {
            val liveTicker = getTicker(rule.canonicalSymbol)
            if (request.side == OrderSide.BUY) liveTicker.ask else liveTicker.bid
        } else request.limitPrice ?: error("Price/trigger price is required for Kraken ${request.orderType} orders.")
        val estimatedOrderCost = cleanQuantity.multiply(orderPriceForMinimum)
        if (rule.minOrderCost > BigDecimal.ZERO && estimatedOrderCost < rule.minOrderCost) {
            error("Kraken order cost too small for ${rule.canonicalSymbol}. cost=$estimatedOrderCost minCost=${rule.minOrderCost}; the bot will not increase size above its risk ceiling to satisfy the exchange minimum.")
        }
        if (request.orderType != OrderType.MARKET) {
            val rawPrice = request.limitPrice ?: error("Price/trigger price is required for Kraken ${request.orderType} orders.")
            val price = roundKrakenPriceToTick(rawPrice, rule.tickSize, rule.priceDecimals, request.side, request.orderType)
            form["price"] = price.stripTrailingZeros().toPlainString()
        }
        if (request.postOnly) {
            if (request.orderType != OrderType.LIMIT) error("Kraken post-only is valid only for ordinary LIMIT orders; conditional stop/take-profit orders cannot silently use maker-only semantics.")
            form["oflags"] = "post"
        }
        request.protectiveStopPrice?.takeIf { request.side == OrderSide.BUY && it > BigDecimal.ZERO }?.let { rawStop ->
            val stop = roundKrakenPriceToTick(rawStop, rule.tickSize, rule.priceDecimals, OrderSide.SELL, OrderType.STOP_LOSS)
            if (stop >= orderPriceForMinimum) error("Protective stop must be below the BUY entry reference. stop=$stop entryRef=$orderPriceForMinimum")
            form["close[ordertype]"] = "stop-loss"
            form["close[price]"] = stop.stripTrailingZeros().toPlainString()
        }
        val encoded = encodeForm(form)
        val signature = krakenSignature(path, nonce, encoded, secretKey)
        val body = encoded.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
        val req = Request.Builder().url("https://api.kraken.com$path").addHeader("API-Key", apiKey).addHeader("API-Sign", signature).post(body).build()
        KrakenPrivateExecutionRegistry.markSubmissionPending(
            clientOrderId = krakenClientOrderId,
            symbol = rule.canonicalSymbol,
            side = request.side
        )
        http.newCall(req).execute().use { res ->
            val responseBody = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                if (res.code >= 500) {
                    KrakenPrivateExecutionRegistry.markFailureIfPending(
                        krakenClientOrderId,
                        "Kraken AddOrder HTTP ${res.code}"
                    )
                } else {
                    KrakenPrivateExecutionRegistry.clearSubmission(krakenClientOrderId)
                }
                error("Kraken AddOrder HTTP ${res.code}: $responseBody")
            }
            val root = org.json.JSONObject(responseBody)
            val errors = root.optJSONArray("error")
            if (errors != null && errors.length() > 0) {
                KrakenPrivateExecutionRegistry.clearSubmission(krakenClientOrderId)
                error("Kraken AddOrder error: $errors")
            }
            val result = root.getJSONObject("result")
            val txidArray = result.optJSONArray("txid")
            val txid = if (txidArray != null && txidArray.length() > 0) txidArray.getString(0) else request.clientOrderId
            KrakenPrivateExecutionRegistry.markSubmissionAcknowledged(krakenClientOrderId, txid)

            // Kraken AddOrder usually only returns txid/description, not the actual fill.
            // QueryOrders gives vol_exec, cost, price and fee so alerts/history do not show 0 values.
            val queried = runCatching { queryOrderFill(txid, rule, request) }.getOrNull()
            queried ?: OrderResult(
                exchangeOrderId = txid,
                symbol = rule.canonicalSymbol,
                side = request.side,
                executedQuantity = BigDecimal.ZERO,
                averagePrice = request.limitPrice ?: BigDecimal.ZERO,
                fee = BigDecimal.ZERO,
                paper = false
            )
        }
    }


    private fun roundKrakenPriceToTick(value: BigDecimal, tick: BigDecimal, decimals: Int, side: OrderSide, type: OrderType): BigDecimal {
        if (tick <= BigDecimal.ZERO) return value.setScale(decimals, RoundingMode.HALF_UP)
        val rounding = when (type) {
            OrderType.LIMIT -> if (side == OrderSide.BUY) RoundingMode.DOWN else RoundingMode.UP
            OrderType.STOP_LOSS -> if (side == OrderSide.BUY) RoundingMode.UP else RoundingMode.DOWN
            OrderType.TAKE_PROFIT -> if (side == OrderSide.BUY) RoundingMode.DOWN else RoundingMode.UP
            OrderType.MARKET -> RoundingMode.HALF_UP
        }
        return value.divide(tick, 0, rounding).multiply(tick).setScale(decimals, RoundingMode.HALF_UP)
    }

    private fun queryOrderFill(txid: String, rule: KrakenPairRule, request: OrderRequest): OrderResult {
        val root = privateJson("/0/private/QueryOrders", mapOf("txid" to txid, "trades" to "true"))
        val result = root.getJSONObject("result")
        val order = result.optJSONObject(txid) ?: result.keys().asSequence().asIterable().firstOrNull()?.let { result.optJSONObject(it) }
        val executed = order?.optString("vol_exec", "0")?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val cost = order?.optString("cost", "0")?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val priceFromOrder = order?.optString("price", "0")?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val fee = order?.optString("fee", "0")?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val avg = when {
            priceFromOrder > BigDecimal.ZERO -> priceFromOrder
            executed > BigDecimal.ZERO && cost > BigDecimal.ZERO -> cost.divide(executed, rule.priceDecimals.coerceAtLeast(8), RoundingMode.HALF_UP)
            else -> request.limitPrice ?: BigDecimal.ZERO
        }
        return OrderResult(
            exchangeOrderId = txid,
            symbol = rule.canonicalSymbol,
            side = request.side,
            executedQuantity = executed,
            averagePrice = avg,
            fee = fee,
            paper = false
        )
    }

    private fun privateJson(path: String, parameters: Map<String, String>): org.json.JSONObject {
        val nonce = KrakenNonceSequencer.next()
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
        val quote = inferQuoteAsset(normalized)
        return KrakenPairRule(normalized, normalized, pair, normalized.replace("BTC", "XBT"), normalized.removeSuffix(quote), quote, BigDecimal.ZERO, 8, 8, true, "fallback")
    }

    private fun inferQuoteAsset(symbol: String): String {
        val upper = symbol.uppercase().replace("/", "").replace("-", "")
        val knownQuotes = listOf("USDT", "USDC", "EUR", "USD", "GBP", "CHF", "AUD", "CAD", "JPY", "BTC", "ETH")
        return knownQuotes.firstOrNull { upper.endsWith(it) && upper.length > it.length } ?: "EUR"
    }

    private fun quotePriority(quote: String): Int = when (quote.uppercase()) {
        "EUR" -> 0
        "USD", "USDT", "USDC" -> 1
        "BTC", "ETH" -> 2
        else -> 3
    }

    private fun fromKrakenPair(pair: String): String {
        val normalized = pair.uppercase()
        val cache = runCatching { ensurePairCache() }.getOrDefault(emptyMap())
        val direct = cache[normalized.replace("XBT", "BTC")]
        if (direct != null) return direct.canonicalSymbol
        val byExchangePair = cache.values.firstOrNull { it.exchangePair.equals(pair, ignoreCase = true) }
        if (byExchangePair != null) return byExchangePair.canonicalSymbol
        return normalized.replace("ZEUR", "EUR").replace("ZUSD", "USD").replace("XBT", "BTC").replace("/", "")
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
                if (base.isBlank() || quote.isBlank()) return@forEach
                val canonical = "${base}${quote}".replace("XBTEUR", "BTCEUR").replace("XBT", "BTC")
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
                    status = status,
                    minOrderCost = item.optString("costmin", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    tickSize = item.optString("tick_size", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                )
                loaded[canonical] = rule
                loaded[pairId.uppercase().replace("XBT", "BTC")] = rule
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
) : CryptoExchangeClient {
    private val http = OkHttpClient.Builder().build()
    private val baseUrl = "https://api.coinbase.com"

    override suspend fun getTicker(symbol: String): MarketTicker = withContext(Dispatchers.IO) {
        val product = toCoinbaseProduct(symbol)
        val req = Request.Builder().url("$baseUrl/api/v3/brokerage/products/$product/ticker").get().build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Coinbase ticker HTTP ${res.code}")
            val root = org.json.JSONObject(res.body?.string() ?: error("Coinbase ticker empty body"))
            val trades = root.optJSONArray("trades")
            val last = if (trades != null && trades.length() > 0) trades.getJSONObject(0).optString("price", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO else BigDecimal.ZERO
            val bid = root.optString("best_bid", last.toPlainString()).toBigDecimalOrNull() ?: last
            val ask = root.optString("best_ask", last.toPlainString()).toBigDecimalOrNull() ?: last
            MarketTicker(product.replace("-", ""), last, bid, ask, BigDecimal.ZERO, BigDecimal.ZERO)
        }
    }
    override suspend fun getCandles(symbol: String, timeframe: Timeframe, limit: Int): List<Candle> = withContext(Dispatchers.IO) {
        val product = toCoinbaseProduct(symbol)
        val end = System.currentTimeMillis() / 1000L
        val seconds = when (timeframe) {
            Timeframe.M1 -> 60L; Timeframe.M5 -> 300L; Timeframe.M15 -> 900L; Timeframe.H1 -> 3600L; Timeframe.H4 -> 14400L
        }
        val start = end - seconds * limit.coerceIn(20, 300)
        val granularity = when (timeframe) {
            Timeframe.M1 -> "ONE_MINUTE"; Timeframe.M5 -> "FIVE_MINUTE"; Timeframe.M15 -> "FIFTEEN_MINUTE"; Timeframe.H1 -> "ONE_HOUR"; Timeframe.H4 -> "FOUR_HOUR"
        }
        val path = "/api/v3/brokerage/products/$product/candles?start=$start&end=$end&granularity=$granularity"
        val req = Request.Builder().url("$baseUrl$path").get().build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Coinbase candles HTTP ${res.code}")
            val root = org.json.JSONObject(res.body?.string() ?: error("Coinbase candles empty body"))
            val arr = root.optJSONArray("candles") ?: org.json.JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                Candle(
                    symbol = product.replace("-", ""), timeframe = timeframe,
                    openTimeEpochMs = (c.optString("start", "0").toLongOrNull() ?: 0L) * 1000L,
                    open = c.optString("open", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    high = c.optString("high", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    low = c.optString("low", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    close = c.optString("close", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    volume = c.optString("volume", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                )
            }.sortedBy { it.openTimeEpochMs }
        }
    }

    override suspend fun getAvailableBalances(): Map<String, BigDecimal> = withContext(Dispatchers.IO) {
        val body = coinbasePrivate("GET", "/api/v3/brokerage/accounts", "")
        val root = org.json.JSONObject(body)
        val accounts = root.optJSONArray("accounts") ?: org.json.JSONArray()
        val out = linkedMapOf<String, BigDecimal>()
        for (i in 0 until accounts.length()) {
            val a = accounts.optJSONObject(i) ?: continue
            val currency = a.optString("currency", "").uppercase()
            val available = a.optJSONObject("available_balance")?.optString("value", "0")?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            if (currency.isNotBlank() && available > BigDecimal.ZERO) out[currency] = available
        }
        out
    }

    override suspend fun getPortfolioBalances(): List<BalanceInfo> = getAvailableBalances().map { (asset, free) -> BalanceInfo(asset, free, free, BigDecimal.ZERO, BigDecimal.ZERO) }

    override suspend fun validateSymbol(symbol: String): ExchangeSymbolInfo {
        val product = toCoinbaseProduct(symbol)
        return ExchangeSymbolInfo(symbol, product.replace("-", ""), product, product, product.substringBefore("-"), product.substringAfter("-"), BigDecimal("0"), 8, 8, true, "Coinbase product syntax validated locally. Live endpoint verifies account availability.")
    }

    override suspend fun placeOrder(request: OrderRequest): OrderResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Coinbase Advanced key name and EC private key are required.")
        val product = toCoinbaseProduct(request.symbol)
        val side = request.side.name
        val body = org.json.JSONObject().apply {
            put("client_order_id", request.clientOrderId)
            put("product_id", product)
            put("side", side)
            put("order_configuration", org.json.JSONObject().apply {
                if (request.orderType == OrderType.MARKET) {
                    put("market_market_ioc", org.json.JSONObject().apply { put("base_size", request.quantity.stripTrailingZeros().toPlainString()) })
                } else {
                    put("limit_limit_gtc", org.json.JSONObject().apply {
                        put("base_size", request.quantity.stripTrailingZeros().toPlainString())
                        put("limit_price", (request.limitPrice ?: BigDecimal.ZERO).stripTrailingZeros().toPlainString())
                        put("post_only", false)
                    })
                }
            })
        }.toString()
        val response = coinbasePrivate("POST", "/api/v3/brokerage/orders", body)
        val root = org.json.JSONObject(response)
        val orderId = root.optJSONObject("success_response")?.optString("order_id")
            ?: root.optString("order_id", request.clientOrderId)
        OrderResult(orderId, product.replace("-", ""), request.side, BigDecimal.ZERO, request.limitPrice ?: BigDecimal.ZERO, BigDecimal.ZERO, paper = false)
    }

    private fun toCoinbaseProduct(symbol: String): String {
        val clean = symbol.uppercase().replace("/", "").replace("-", "")
        return when {
            clean.endsWith("EUR") -> clean.removeSuffix("EUR") + "-EUR"
            clean.endsWith("USD") -> clean.removeSuffix("USD") + "-USD"
            else -> clean
        }
    }

    private fun coinbasePrivate(method: String, path: String, body: String): String {
        val jwt = coinbaseJwt(method, path)
        val reqBody = if (method == "GET") null else body.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder().url("$baseUrl$path").header("Authorization", "Bearer $jwt")
        val req = when (method) { "POST" -> builder.post(reqBody!!).build(); else -> builder.get().build() }
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("Coinbase $method $path HTTP ${res.code}: $text")
            return text
        }
    }

    private fun coinbaseJwt(method: String, path: String): String {
        val now = System.currentTimeMillis() / 1000L
        val header = org.json.JSONObject().put("alg", "ES256").put("typ", "JWT").put("kid", apiKey).toString()
        val payload = org.json.JSONObject()
            .put("sub", apiKey).put("iss", "coinbase-cloud").put("nbf", now).put("exp", now + 120)
            .put("uri", "$method api.coinbase.com$path").toString()
        val signingInput = base64Url(header.toByteArray()) + "." + base64Url(payload.toByteArray())
        val signature = java.security.Signature.getInstance("SHA256withECDSA")
        signature.initSign(loadEcPrivateKey(secretKey))
        signature.update(signingInput.toByteArray(Charsets.UTF_8))
        return signingInput + "." + base64Url(derToJose(signature.sign()))
    }

    private fun loadEcPrivateKey(raw: String): java.security.PrivateKey {
        val cleaned = raw.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val bytes = android.util.Base64.decode(cleaned, android.util.Base64.DEFAULT)
        return java.security.KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(bytes))
    }

    private fun base64Url(bytes: ByteArray): String = android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)

    private fun derToJose(der: ByteArray): ByteArray {
        // Minimal DER ECDSA signature to JOSE R||S conversion for P-256.
        if (der.isEmpty() || der[0].toInt() != 0x30) return der
        var idx = 2
        if (der[idx].toInt() != 0x02) return der
        val rLen = der[idx + 1].toInt(); idx += 2
        val r = der.copyOfRange(idx, idx + rLen); idx += rLen
        if (der[idx].toInt() != 0x02) return der
        val sLen = der[idx + 1].toInt(); idx += 2
        val s = der.copyOfRange(idx, idx + sLen)
        fun pad32(x: ByteArray): ByteArray {
            val stripped = x.dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(32 - stripped.size.coerceAtMost(32)) + stripped.takeLast(32).toByteArray()
        }
        return pad32(r) + pad32(s)
    }
}

class BitvavoClient(
    private val apiKey: String,
    private val secretKey: String
) : CryptoExchangeClient {
    private val http = OkHttpClient.Builder().build()
    private val baseUrl = "https://api.bitvavo.com/v2"

    override suspend fun getTicker(symbol: String): MarketTicker = withContext(Dispatchers.IO) {
        val market = toBitvavoMarket(symbol)
        val tickerBody = publicText("/ticker/24h?market=$market")
        val root = org.json.JSONObject(tickerBody)
        val last = root.optString("last", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
        val bid = root.optString("bid", last.toPlainString()).toBigDecimalOrNull() ?: last
        val ask = root.optString("ask", last.toPlainString()).toBigDecimalOrNull() ?: last
        val open = root.optString("open", last.toPlainString()).toBigDecimalOrNull() ?: last
        val volumeQuote = root.optString("volumeQuote", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
        val change = if (open > BigDecimal.ZERO) last.subtract(open).divide(open, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal.ZERO
        MarketTicker(market.replace("-", ""), last, bid, ask, volumeQuote, change)
    }
    override suspend fun getCandles(symbol: String, timeframe: Timeframe, limit: Int): List<Candle> = withContext(Dispatchers.IO) {
        val market = toBitvavoMarket(symbol)
        val interval = when (timeframe) { Timeframe.M1 -> "1m"; Timeframe.M5 -> "5m"; Timeframe.M15 -> "15m"; Timeframe.H1 -> "1h"; Timeframe.H4 -> "4h" }
        val arr = org.json.JSONArray(publicText("/candles?market=$market&interval=$interval&limit=${limit.coerceIn(20, 200)}"))
        (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONArray(i) ?: return@mapNotNull null
            Candle(market.replace("-", ""), timeframe, c.optLong(0), c.optString(1, "0").toBigDecimal(), c.optString(2, "0").toBigDecimal(), c.optString(3, "0").toBigDecimal(), c.optString(4, "0").toBigDecimal(), c.optString(5, "0").toBigDecimal())
        }.sortedBy { it.openTimeEpochMs }
    }

    override suspend fun getAvailableBalances(): Map<String, BigDecimal> = withContext(Dispatchers.IO) {
        val arr = org.json.JSONArray(privateText("GET", "/balance", ""))
        val out = linkedMapOf<String, BigDecimal>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val asset = item.optString("symbol", "").uppercase()
            val free = item.optString("available", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            if (asset.isNotBlank() && free > BigDecimal.ZERO) out[asset] = free
        }
        out
    }

    override suspend fun getPortfolioBalances(): List<BalanceInfo> = withContext(Dispatchers.IO) {
        val arr = org.json.JSONArray(privateText("GET", "/balance", ""))
        buildList {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val asset = item.optString("symbol", "").uppercase()
                val free = item.optString("available", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                val inOrder = item.optString("inOrder", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
                val total = free.add(inOrder)
                if (asset.isNotBlank() && total > BigDecimal.ZERO) add(BalanceInfo(asset, total, free, inOrder, BigDecimal.ZERO))
            }
        }
    }

    override suspend fun getOpenOrders(): List<LiveOrderInfo> = withContext(Dispatchers.IO) {
        val arr = org.json.JSONArray(privateText("GET", "/ordersOpen", ""))
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val market = o.optString("market")
                val side = if (o.optString("side").equals("sell", true)) OrderSide.SELL else OrderSide.BUY
                add(LiveOrderInfo(
                    exchangeOrderId = "$market:${o.optString("orderId")}",
                    symbol = market.replace("-", ""), side = side,
                    orderType = if (o.optString("orderType").equals("market", true)) OrderType.MARKET else OrderType.LIMIT,
                    price = o.optString("price", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    quantity = o.optString("amount", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    executedQuantity = (o.optString("amount", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO).subtract(o.optString("amountRemaining", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO).max(BigDecimal.ZERO),
                    remainingQuantity = o.optString("amountRemaining", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    status = o.optString("status", "open"),
                    openedAtEpochSeconds = o.optLong("created", System.currentTimeMillis()) / 1000L,
                    description = "Bitvavo open order"
                ))
            }
        }
    }

    override suspend fun cancelOrder(orderId: String): Boolean = withContext(Dispatchers.IO) {
        val parts = orderId.split(":", limit = 2)
        if (parts.size != 2) error("Bitvavo cancel requires market:orderId, got $orderId")
        privateText("DELETE", "/order?market=${parts[0]}&orderId=${parts[1]}", "")
        true
    }

    override suspend fun validateSymbol(symbol: String): ExchangeSymbolInfo {
        val market = toBitvavoMarket(symbol)
        return ExchangeSymbolInfo(symbol, market.replace("-", ""), market, market, market.substringBefore("-"), market.substringAfter("-"), BigDecimal("5"), 8, 8, true, "Bitvavo market syntax validated locally; live endpoint validates actual availability.")
    }

    override suspend fun discoverTradableSymbols(quoteAsset: String, limit: Int): List<SymbolDiscoveryCandidate> = withContext(Dispatchers.IO) {
        val arr = org.json.JSONArray(publicText("/markets"))
        buildList {
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i) ?: continue
                val market = m.optString("market")
                val quote = m.optString("quote", "").uppercase()
                if (quote != quoteAsset.uppercase()) continue
                add(SymbolDiscoveryCandidate(symbol = market.replace("-", ""), exchangePair = market, baseAsset = m.optString("base", ""), quoteAsset = quote, tradable = true, minOrderSize = BigDecimal("5"), reason = "Discovered from Bitvavo markets endpoint."))
                if (size >= limit.coerceAtLeast(1)) break
            }
        }
    }

    override suspend fun placeOrder(request: OrderRequest): OrderResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Bitvavo API key and secret are required for live trading.")
        val market = toBitvavoMarket(request.symbol)
        val side = request.side.name.lowercase()
        val bodyJson = org.json.JSONObject().apply {
            put("market", market); put("side", side)
            put("orderType", if (request.orderType == OrderType.MARKET) "market" else "limit")
            put("amount", request.quantity.stripTrailingZeros().toPlainString())
            if (request.orderType != OrderType.MARKET) put("price", (request.limitPrice ?: BigDecimal.ZERO).stripTrailingZeros().toPlainString())
        }.toString()
        val response = privateText("POST", "/order", bodyJson)
        val root = org.json.JSONObject(response)
        OrderResult(root.optString("orderId", request.clientOrderId), market.replace("-", ""), request.side, BigDecimal.ZERO, request.limitPrice ?: BigDecimal.ZERO, BigDecimal.ZERO, paper = false)
    }

    private fun toBitvavoMarket(symbol: String): String {
        val clean = symbol.uppercase().replace("/", "").replace("-", "")
        return when {
            clean.endsWith("EUR") -> clean.removeSuffix("EUR") + "-EUR"
            clean.endsWith("USDT") -> clean.removeSuffix("USDT") + "-USDT"
            else -> clean
        }
    }

    private fun publicText(pathWithQuery: String): String {
        val req = Request.Builder().url("$baseUrl$pathWithQuery").get().build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("Bitvavo public $pathWithQuery HTTP ${res.code}: $text")
            return text
        }
    }

    private fun privateText(method: String, endpoint: String, body: String): String {
        val timestamp = System.currentTimeMillis().toString()
        val signaturePayload = timestamp + method + "/v2" + endpoint + body
        val signature = hmacSha256Hex(secretKey, signaturePayload)
        val reqBody = if (method == "GET" || method == "DELETE") null else body.toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder().url("$baseUrl$endpoint")
            .header("Bitvavo-Access-Key", apiKey)
            .header("Bitvavo-Access-Timestamp", timestamp)
            .header("Bitvavo-Access-Signature", signature)
            .header("Bitvavo-Access-Window", "10000")
        val req = when (method) { "POST" -> builder.post(reqBody!!).build(); "DELETE" -> builder.delete().build(); else -> builder.get().build() }
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("Bitvavo $method $endpoint HTTP ${res.code}: $text")
            return text
        }
    }

    private fun hmacSha256Hex(secret: String, payload: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
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
        ExchangeProvider.PAPER -> ExchangeCapability(provider, "Paper Trading", true, true, false, "Simulation only. Orders are simulated locally; no real exchange order is sent.")
        ExchangeProvider.BINANCE_READ_ONLY -> ExchangeCapability(provider, "Binance Read-Only", true, false, true, "Belgium mode: Binance trading is disabled. Use signals/manual mode only.")
        ExchangeProvider.KRAKEN -> ExchangeCapability(provider, "Kraken", true, true, false, "Verify your Belgian account supports API spot trading before enabling LIVE_AUTO.")
        ExchangeProvider.COINBASE_ADVANCED -> ExchangeCapability(provider, "Coinbase Advanced", true, true, false, "Coinbase Advanced live trading is implemented through JWT signing. Verify Belgian account/API permissions before use.")
        ExchangeProvider.BITVAVO -> ExchangeCapability(provider, "Bitvavo", true, true, false, "Bitvavo REST live trading is implemented. Verify Belgian account/API permissions before use.")
        ExchangeProvider.MANUAL -> ExchangeCapability(provider, "Manual Execution", true, false, true, "The app produces trade plans only; you execute them yourself.")
    }
}
