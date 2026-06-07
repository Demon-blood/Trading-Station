package com.ksp.cryptobot.exchange

import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.Timeframe
import com.ksp.cryptobot.core.OrderRequest
import com.ksp.cryptobot.core.OrderResult
import com.ksp.cryptobot.core.BalanceInfo
import com.ksp.cryptobot.core.ExchangeSymbolInfo
import com.ksp.cryptobot.core.LiveOrderInfo
import com.ksp.cryptobot.core.ClosedOrderInfo
import com.ksp.cryptobot.core.SymbolDiscoveryCandidate

interface CryptoExchangeClient {
    suspend fun getTicker(symbol: String): MarketTicker
    suspend fun getCandles(symbol: String, timeframe: Timeframe, limit: Int = 120): List<Candle>

    /**
     * Available balances by asset/currency code, when the selected connector supports it.
     *
     * The bot uses this before live orders to avoid sending orders larger than the real
     * free EUR balance. Connectors that do not support private balance checks return
     * an empty map, which causes live execution to use the configured max position only.
     */
    suspend fun getAvailableBalances(): Map<String, java.math.BigDecimal> = emptyMap()

    /**
     * Human-readable diagnostics for live status. Connectors can expose raw balance
     * fields and open-order holds so the app can explain why a balance is blocked.
     */
    suspend fun getBalanceDiagnostics(): List<String> = emptyList()

    /**
     * Validate a user-facing symbol such as BTCEUR against exchange trading rules.
     * Live connectors should return real pair metadata, minimums and precision.
     */
    suspend fun validateSymbol(symbol: String): ExchangeSymbolInfo = ExchangeSymbolInfo(
        requestedSymbol = symbol,
        normalizedSymbol = symbol.uppercase().replace("/", ""),
        exchangePair = symbol.uppercase().replace("/", ""),
        altName = symbol.uppercase().replace("/", ""),
        baseAsset = symbol.uppercase().replace("EUR", ""),
        quoteAsset = "EUR",
        minOrderSize = java.math.BigDecimal.ZERO,
        priceDecimals = 8,
        quantityDecimals = 8,
        tradable = true,
        reason = "No exchange-specific validator available."
    )

    /**
     * Discover tradable symbols from the selected exchange. Live connectors should return
     * real pair metadata so the bot can build a market universe automatically.
     * Pass quoteAsset = "ALL" to request the full spot universe instead of a single quote.
     */
    suspend fun discoverTradableSymbols(quoteAsset: String = "ALL", limit: Int = 50): List<SymbolDiscoveryCandidate> = emptyList()

    /** Open live orders as reported by the exchange. */
    suspend fun getOpenOrders(): List<LiveOrderInfo> = emptyList()

    /** Cancel one live order when the exchange supports it. */
    suspend fun cancelOrder(orderId: String): Boolean = false

    /** Closed orders/trades as reported by the exchange. Used by lifecycle sync. */
    suspend fun getClosedOrders(limit: Int = 50): List<ClosedOrderInfo> = emptyList()

    /**
     * Full portfolio balances, preferably using total + free + held amounts.
     * Used by the Portfolio tab and by automatic SELL sizing.
     */
    suspend fun getPortfolioBalances(): List<BalanceInfo> = getAvailableBalances().map { (asset, free) ->
        BalanceInfo(asset = asset, total = free, free = free)
    }

    suspend fun placeOrder(request: OrderRequest): OrderResult
}
