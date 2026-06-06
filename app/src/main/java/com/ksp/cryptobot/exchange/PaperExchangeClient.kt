package com.ksp.cryptobot.exchange

import com.ksp.cryptobot.core.*
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

class PaperExchangeClient : CryptoExchangeClient {
    override suspend fun getTicker(symbol: String): MarketTicker {
        delay(120)
        val base = when {
            symbol.startsWith("BTC") -> BigDecimal("62000")
            symbol.startsWith("ETH") -> BigDecimal("3200")
            symbol.startsWith("SOL") -> BigDecimal("145")
            symbol.startsWith("XRP") -> BigDecimal("0.52")
            else -> BigDecimal("100")
        }
        val change = BigDecimal(Random.nextDouble(-3.0, 3.0)).setScale(2, RoundingMode.HALF_UP)
        val last = base.multiply(BigDecimal.ONE.add(change.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP))).setScale(2, RoundingMode.HALF_UP)
        val spread = last.multiply(BigDecimal("0.0015")).setScale(2, RoundingMode.HALF_UP)
        return MarketTicker(
            symbol = symbol,
            lastPrice = last,
            bid = last.subtract(spread),
            ask = last.add(spread),
            volume24h = BigDecimal("50000000"),
            priceChangePercent24h = change
        )
    }


    override suspend fun getCandles(symbol: String, timeframe: Timeframe, limit: Int): List<Candle> {
        delay(120)
        val ticker = getTicker(symbol)
        val candles = mutableListOf<Candle>()
        var price = ticker.lastPrice.multiply(BigDecimal("0.985"))
        repeat(limit.coerceIn(50, 300)) { index ->
            val drift = BigDecimal(Random.nextDouble(-0.003, 0.004)).setScale(6, RoundingMode.HALF_UP)
            val open = price
            val close = open.multiply(BigDecimal.ONE.add(drift)).setScale(8, RoundingMode.HALF_UP)
            val high = open.max(close).multiply(BigDecimal("1.0020")).setScale(8, RoundingMode.HALF_UP)
            val low = open.min(close).multiply(BigDecimal("0.9980")).setScale(8, RoundingMode.HALF_UP)
            price = close
            candles += Candle(
                symbol = symbol,
                timeframe = timeframe,
                openTimeEpochMs = System.currentTimeMillis() - (limit - index) * 60_000L,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = BigDecimal(Random.nextDouble(100.0, 5000.0)).setScale(3, RoundingMode.HALF_UP)
            )
        }
        return candles
    }

    override suspend fun placeOrder(request: OrderRequest): OrderResult {
        delay(150)
        val price = request.limitPrice ?: BigDecimal.ONE
        return OrderResult(
            exchangeOrderId = request.clientOrderId,
            symbol = request.symbol,
            side = request.side,
            executedQuantity = request.quantity,
            averagePrice = price,
            fee = price.multiply(request.quantity).multiply(BigDecimal("0.001")).setScale(2, RoundingMode.HALF_UP),
            paper = true
        )
    }
}
