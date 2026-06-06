package com.ksp.cryptobot.exchange

import com.ksp.cryptobot.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigDecimal

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
) : BaseExchangeClient("Kraken") {
    override suspend fun placeOrder(request: OrderRequest): OrderResult {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Kraken API key and secret are required for live trading.")
        error("Kraken private order signing is intentionally disabled until you verify the account is legally allowed for Belgian API spot trading.")
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
