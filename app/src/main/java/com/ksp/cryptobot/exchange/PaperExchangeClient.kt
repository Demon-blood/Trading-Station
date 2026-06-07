package com.ksp.cryptobot.exchange

import android.content.Context
import com.ksp.cryptobot.core.*
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

/**
 * Local paper-trading exchange.
 *
 * This client intentionally never sends real orders. When a Context is supplied it persists a
 * small simulated wallet in SharedPreferences so the Portfolio tab and paper execution state
 * survive app restarts. When no Context is supplied it falls back to an in-memory simulation.
 */
class PaperExchangeClient(context: Context? = null) : CryptoExchangeClient {
    private val prefs = context?.getSharedPreferences("paper_wallet", Context.MODE_PRIVATE)
    private val memoryBalances = linkedMapOf("EUR" to BigDecimal("1000.00"))

    init {
        ensureSeeded()
    }

    override suspend fun getTicker(symbol: String): MarketTicker {
        delay(120)
        val clean = symbol.uppercase().replace("/", "").replace("-", "")
        val base = when {
            clean.startsWith("BTC") || clean.startsWith("XBT") -> BigDecimal("62000")
            clean.startsWith("ETH") -> BigDecimal("3200")
            clean.startsWith("SOL") -> BigDecimal("145")
            clean.startsWith("XRP") -> BigDecimal("0.52")
            clean.startsWith("ADA") -> BigDecimal("0.42")
            clean.startsWith("DOGE") -> BigDecimal("0.12")
            clean.startsWith("LINK") -> BigDecimal("14.00")
            clean.startsWith("DOT") -> BigDecimal("6.00")
            clean.startsWith("AVAX") -> BigDecimal("28.00")
            clean.startsWith("LTC") -> BigDecimal("85.00")
            else -> BigDecimal("100")
        }
        val change = BigDecimal(Random.nextDouble(-3.0, 3.0)).setScale(2, RoundingMode.HALF_UP)
        val last = base.multiply(BigDecimal.ONE.add(change.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP))).setScale(8, RoundingMode.HALF_UP)
        val spread = last.multiply(BigDecimal("0.0015")).setScale(8, RoundingMode.HALF_UP)
        return MarketTicker(
            symbol = clean,
            lastPrice = last,
            bid = last.subtract(spread).max(BigDecimal("0.00000001")),
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
                symbol = symbol.uppercase().replace("/", "").replace("-", ""),
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

    override suspend fun getAvailableBalances(): Map<String, BigDecimal> = balances().filterValues { it > BigDecimal.ZERO }

    override suspend fun getPortfolioBalances(): List<BalanceInfo> {
        return balances()
            .filterValues { it > BigDecimal.ZERO }
            .map { (asset, amount) ->
                val eurValue = if (asset == "EUR") amount else runCatching {
                    amount.multiply(getTicker("${asset}EUR").bid).setScale(2, RoundingMode.DOWN)
                }.getOrDefault(BigDecimal.ZERO)
                BalanceInfo(asset = asset, total = amount, free = amount, eurValue = eurValue)
            }
            .sortedByDescending { it.eurValue }
    }

    override suspend fun placeOrder(request: OrderRequest): OrderResult {
        delay(150)
        ensureSeeded()
        val clean = request.symbol.uppercase().replace("/", "").replace("-", "")
        val base = baseAsset(clean)
        val quote = quoteAsset(clean)
        val ticker = getTicker(clean)
        val price = request.limitPrice?.takeIf { it > BigDecimal.ZERO }
            ?: if (request.side == OrderSide.BUY) ticker.ask else ticker.bid
        val requestedQty = request.quantity.max(BigDecimal.ZERO)
        val notional = price.multiply(requestedQty).setScale(8, RoundingMode.HALF_UP)
        val fee = notional.multiply(BigDecimal("0.001")).setScale(8, RoundingMode.HALF_UP)
        val wallet = balances().toMutableMap()

        val executedQty = when (request.side) {
            OrderSide.BUY -> {
                val spendable = wallet[quote] ?: BigDecimal.ZERO
                val maxQty = if (price > BigDecimal.ZERO) spendable.divide(price.multiply(BigDecimal("1.001")), 8, RoundingMode.DOWN) else BigDecimal.ZERO
                val qty = requestedQty.min(maxQty).setScale(8, RoundingMode.DOWN)
                if (qty <= BigDecimal.ZERO) error("Paper BUY blocked: insufficient paper $quote balance. Available=${spendable.stripTrailingZeros().toPlainString()} $quote")
                val cost = price.multiply(qty).setScale(8, RoundingMode.HALF_UP)
                val buyFee = cost.multiply(BigDecimal("0.001")).setScale(8, RoundingMode.HALF_UP)
                wallet[quote] = spendable.subtract(cost).subtract(buyFee).max(BigDecimal.ZERO)
                wallet[base] = (wallet[base] ?: BigDecimal.ZERO).add(qty)
                qty
            }
            OrderSide.SELL -> {
                val freeBase = wallet[base] ?: BigDecimal.ZERO
                val qty = requestedQty.min(freeBase).setScale(8, RoundingMode.DOWN)
                if (qty <= BigDecimal.ZERO) error("Paper SELL blocked: insufficient paper $base balance. Available=${freeBase.stripTrailingZeros().toPlainString()} $base")
                val proceeds = price.multiply(qty).setScale(8, RoundingMode.HALF_UP)
                val sellFee = proceeds.multiply(BigDecimal("0.001")).setScale(8, RoundingMode.HALF_UP)
                wallet[base] = freeBase.subtract(qty).max(BigDecimal.ZERO)
                wallet[quote] = (wallet[quote] ?: BigDecimal.ZERO).add(proceeds).subtract(sellFee).max(BigDecimal.ZERO)
                qty
            }
        }
        saveBalances(wallet)
        return OrderResult(
            exchangeOrderId = request.clientOrderId,
            symbol = clean,
            side = request.side,
            executedQuantity = executedQty,
            averagePrice = price,
            fee = price.multiply(executedQty).multiply(BigDecimal("0.001")).setScale(8, RoundingMode.HALF_UP),
            paper = true
        )
    }

    private fun ensureSeeded() {
        if (prefs == null) return
        if (!prefs.contains("seeded")) {
            prefs.edit()
                .putBoolean("seeded", true)
                .putString("EUR", "1000.00")
                .apply()
        }
    }

    private fun balances(): Map<String, BigDecimal> {
        val p = prefs ?: return memoryBalances.toMap()
        ensureSeeded()
        val out = linkedMapOf<String, BigDecimal>()
        p.all.forEach { (key, value) ->
            if (key != "seeded") {
                val bd = value?.toString()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
                if (bd > BigDecimal.ZERO) out[key.uppercase()] = bd
            }
        }
        if (out.isEmpty()) out["EUR"] = BigDecimal("1000.00")
        return out
    }

    private fun saveBalances(newBalances: Map<String, BigDecimal>) {
        val p = prefs
        if (p == null) {
            memoryBalances.clear()
            newBalances.forEach { (asset, amount) -> if (amount > BigDecimal.ZERO) memoryBalances[asset.uppercase()] = amount }
            return
        }
        val edit = p.edit().clear().putBoolean("seeded", true)
        newBalances.forEach { (asset, amount) ->
            if (amount > BigDecimal.ZERO) edit.putString(asset.uppercase(), amount.setScale(8, RoundingMode.DOWN).stripTrailingZeros().toPlainString())
        }
        edit.apply()
    }

    private fun baseAsset(symbol: String): String {
        val upper = symbol.uppercase().replace("/", "").replace("-", "")
        return upper.removeSuffix(quoteAsset(upper)).ifBlank { upper.take(3) }
    }

    private fun quoteAsset(symbol: String): String {
        val upper = symbol.uppercase().replace("/", "").replace("-", "")
        val knownQuotes = listOf("USDT", "USDC", "EUR", "USD", "GBP", "CHF", "AUD", "CAD", "JPY", "BTC", "ETH")
        return knownQuotes.firstOrNull { upper.endsWith(it) && upper.length > it.length } ?: "EUR"
    }
}
