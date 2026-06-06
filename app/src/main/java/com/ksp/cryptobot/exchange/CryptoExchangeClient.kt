package com.ksp.cryptobot.exchange

import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.Timeframe
import com.ksp.cryptobot.core.OrderRequest
import com.ksp.cryptobot.core.OrderResult

interface CryptoExchangeClient {
    suspend fun getTicker(symbol: String): MarketTicker
    suspend fun getCandles(symbol: String, timeframe: Timeframe, limit: Int = 120): List<Candle>
    suspend fun placeOrder(request: OrderRequest): OrderResult
}
