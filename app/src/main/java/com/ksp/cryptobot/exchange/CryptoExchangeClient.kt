package com.ksp.cryptobot.exchange

import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.Timeframe
import com.ksp.cryptobot.core.OrderRequest
import com.ksp.cryptobot.core.OrderResult

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

    suspend fun placeOrder(request: OrderRequest): OrderResult
}
