package com.ksp.cryptobot.exchange

import android.content.Context
import android.content.SharedPreferences
import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.AppDatabase
import com.ksp.cryptobot.data.TradeEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.random.Random

/**
 * Truthful local paper exchange.
 *
 * Rules:
 * - PAPER never sends a private/real order.
 * - Public price/OHLC/depth/symbol rules come from Kraken when a Context is available.
 * - Resting LIMIT orders remain pending until price actually reaches the limit.
 * - STOP_LOSS/TAKE_PROFIT orders remain pending until their trigger is observed.
 * - Post-only LIMIT orders are rejected if they would cross immediately.
 * - Wallet and trade history change only for simulated executed quantity, never merely because
 *   an order was accepted.
 * - Deferred fills are written to the paper trade journal so research/self-learning sees them.
 *
 * The simulator cannot reconstruct historical queue position from public snapshots. For resting
 * orders it therefore uses observable Kraken depth at/inside the order price and labels those
 * fills maker-style. Conditional orders become taker-style market executions only after trigger.
 */
class PaperExchangeClient(
    context: Context? = null,
    private val useKrakenPublicMarketData: Boolean = context != null
) : CryptoExchangeClient {
    private val appContext = context?.applicationContext
    private val walletPrefs = appContext?.getSharedPreferences("paper_wallet", Context.MODE_PRIVATE)
    private val orderPrefs = appContext?.getSharedPreferences("paper_orders_v4", Context.MODE_PRIVATE)
    private val costBasisPrefs = appContext?.getSharedPreferences("paper_cost_basis_v4", Context.MODE_PRIVATE)
    private val tradeDao = appContext?.let { AppDatabase.get(it).dao() }
    private val memoryBalances = linkedMapOf("EUR" to BigDecimal("1000.00"))
    private val memoryPending = linkedMapOf<String, PendingPaperOrder>()
    private val memoryClosed = mutableListOf<ClosedOrderInfo>()
    private val orderMutex = Mutex()

    private val makerFeeRate = BigDecimal("0.0040") // conservative current Kraken Tier-1 fallback
    private val takerFeeRate = BigDecimal("0.0080")

    private val krakenPublicMarketData: KrakenSpotClient? by lazy {
        if (useKrakenPublicMarketData) KrakenSpotClient(apiKey = "", secretKey = "") else null
    }

    private data class PendingPaperOrder(
        val id: String,
        val symbol: String,
        val side: OrderSide,
        val orderType: OrderType,
        val originalQuantity: BigDecimal,
        val remainingQuantity: BigDecimal,
        val price: BigDecimal,
        val postOnly: Boolean,
        val purpose: String,
        val createdAtMs: Long,
        val executedQuantity: BigDecimal = BigDecimal.ZERO,
        val cumulativeCost: BigDecimal = BigDecimal.ZERO,
        val cumulativeFee: BigDecimal = BigDecimal.ZERO,
        val restingMaker: Boolean = false
    )

    private data class PaperCostBasis(val quantity: BigDecimal, val totalCostQuote: BigDecimal)

    private data class SimulatedFill(
        val quantity: BigDecimal,
        val averagePrice: BigDecimal,
        val fee: BigDecimal,
        val maker: Boolean,
        val slippagePercent: BigDecimal,
        val usedDepth: Boolean
    ) {
        val notional: BigDecimal get() = averagePrice.multiply(quantity)
    }

    init {
        ensureSeeded()
    }

    override suspend fun getTradingFeeSchedule(symbol: String): TradingFeeSchedule = TradingFeeSchedule(
        makerRate = makerFeeRate,
        takerRate = takerFeeRate,
        source = "PAPER_KRAKEN_CONSERVATIVE_FALLBACK"
    )

    override suspend fun getTicker(symbol: String): MarketTicker {
        val ticker = rawTicker(symbol)
        processPendingOrders(ticker.symbol, ticker)
        return ticker
    }

    private suspend fun rawTicker(symbol: String): MarketTicker {
        krakenPublicMarketData?.let { live ->
            return runCatching { live.getTicker(symbol) }.getOrElse { simulatedTicker(symbol) }
        }
        return simulatedTicker(symbol)
    }

    private suspend fun simulatedTicker(symbol: String): MarketTicker {
        delay(120)
        val clean = normalizeSymbol(symbol)
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
        krakenPublicMarketData?.let { live ->
            return runCatching { live.getCandles(symbol, timeframe, limit) }.getOrElse {
                simulatedCandles(symbol, timeframe, limit)
            }
        }
        return simulatedCandles(symbol, timeframe, limit)
    }

    override suspend fun getOrderBook(symbol: String, depth: Int): OrderBookSnapshot? {
        return krakenPublicMarketData?.let { live -> runCatching { live.getOrderBook(symbol, depth) }.getOrNull() }
    }

    private suspend fun simulatedCandles(symbol: String, timeframe: Timeframe, limit: Int): List<Candle> {
        delay(120)
        val ticker = rawTicker(symbol)
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
                symbol = normalizeSymbol(symbol),
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
                    amount.multiply(rawTicker("${asset}EUR").bid).setScale(2, RoundingMode.DOWN)
                }.getOrDefault(BigDecimal.ZERO)
                BalanceInfo(asset = asset, total = amount, free = amount, eurValue = eurValue)
            }
            .sortedByDescending { it.eurValue }
    }

    override suspend fun validateSymbol(symbol: String): ExchangeSymbolInfo {
        return krakenPublicMarketData?.let { live -> runCatching { live.validateSymbol(symbol) }.getOrNull() }
            ?: super.validateSymbol(symbol)
    }

    override suspend fun discoverTradableSymbols(quoteAsset: String, limit: Int): List<SymbolDiscoveryCandidate> {
        return krakenPublicMarketData?.let { live ->
            runCatching { live.discoverTradableSymbols(quoteAsset, limit) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    override suspend fun placeOrder(request: OrderRequest): OrderResult = orderMutex.withLock {
        ensureSeeded()
        val clean = normalizeSymbol(request.symbol)
        val ticker = rawTicker(clean)
        val pairRule = runCatching { validateSymbol(clean) }.getOrNull()
        val qtyScale = pairRule?.quantityDecimals?.coerceIn(0, 12) ?: 8
        val requestedQty = request.quantity.max(BigDecimal.ZERO).setScale(qtyScale, RoundingMode.DOWN)
        if (requestedQty <= BigDecimal.ZERO) error("Paper order blocked: quantity is zero after exchange precision rounding.")
        val minQty = pairRule?.minOrderSize ?: BigDecimal.ZERO
        if (minQty > BigDecimal.ZERO && requestedQty < minQty) {
            error("Paper order blocked by Kraken ordermin: quantity=$requestedQty < min=$minQty. Risk is not increased to satisfy the minimum.")
        }
        val price = request.limitPrice?.takeIf { it > BigDecimal.ZERO }
        if (request.orderType != OrderType.MARKET && price == null) {
            error("Paper ${request.orderType} requires a limit/trigger price.")
        }
        if (request.postOnly) {
            if (request.orderType != OrderType.LIMIT) error("Paper post-only is valid only for LIMIT orders.")
            val limit = price!!
            val wouldCross = when (request.side) {
                OrderSide.BUY -> limit >= ticker.ask
                OrderSide.SELL -> limit <= ticker.bid
            }
            if (wouldCross) error("Paper post-only rejected: the limit would immediately cross the book and become taker.")
        }

        val reference = when (request.orderType) {
            OrderType.MARKET -> if (request.side == OrderSide.BUY) ticker.ask else ticker.bid
            else -> price!!
        }
        val minCost = pairRule?.minOrderCost ?: BigDecimal.ZERO
        if (minCost > BigDecimal.ZERO && requestedQty.multiply(reference) < minCost) {
            error("Paper order blocked by Kraken costmin: estimated cost=${requestedQty.multiply(reference)} < min=$minCost. Risk is not increased to satisfy the minimum.")
        }

        val now = System.currentTimeMillis()
        val id = request.clientOrderId.ifBlank { "paper-${clean.lowercase()}-$now" }
        val immediatelyExecutable = when (request.orderType) {
            OrderType.MARKET -> true
            OrderType.LIMIT -> when (request.side) {
                OrderSide.BUY -> price!! >= ticker.ask
                OrderSide.SELL -> price!! <= ticker.bid
            }
            OrderType.STOP_LOSS -> stopTriggered(request.side, price!!, ticker)
            OrderType.TAKE_PROFIT -> takeProfitTriggered(request.side, price!!, ticker)
        }

        if (!immediatelyExecutable) {
            val pending = PendingPaperOrder(
                id = id,
                symbol = clean,
                side = request.side,
                orderType = request.orderType,
                originalQuantity = requestedQty,
                remainingQuantity = requestedQty,
                price = price ?: BigDecimal.ZERO,
                postOnly = request.postOnly,
                purpose = request.purpose,
                createdAtMs = now,
                restingMaker = request.orderType == OrderType.LIMIT
            )
            savePending(pending)
            return@withLock OrderResult(
                exchangeOrderId = id,
                symbol = clean,
                side = request.side,
                executedQuantity = BigDecimal.ZERO,
                averagePrice = price ?: BigDecimal.ZERO,
                fee = BigDecimal.ZERO,
                paper = true
            )
        }

        val maker = request.orderType == OrderType.LIMIT && request.postOnly
        val effectiveMaker = maker // marketable non-post LIMIT and conditionals are taker-style
        val fill = simulateFill(
            symbol = clean,
            side = request.side,
            requestedQuantity = requestedQty,
            ticker = ticker,
            maker = effectiveMaker,
            limitPrice = if (request.orderType == OrderType.LIMIT) price else null
        )
        if (fill.quantity <= BigDecimal.ZERO) {
            error("Paper order could not obtain a simulated fill within balance/depth constraints.")
        }
        val realizedPnl = applyWalletFill(clean, request.side, fill)
        val remaining = requestedQty.subtract(fill.quantity).max(BigDecimal.ZERO).setScale(qtyScale, RoundingMode.DOWN)
        if (remaining > BigDecimal.ZERO && request.orderType == OrderType.LIMIT) {
            savePending(
                PendingPaperOrder(
                    id = id,
                    symbol = clean,
                    side = request.side,
                    orderType = request.orderType,
                    originalQuantity = requestedQty,
                    remainingQuantity = remaining,
                    price = price!!,
                    postOnly = request.postOnly,
                    purpose = request.purpose,
                    createdAtMs = now,
                    executedQuantity = fill.quantity,
                    cumulativeCost = fill.notional,
                    cumulativeFee = fill.fee,
                    restingMaker = true
                )
            )
        } else {
            appendClosed(
                ClosedOrderInfo(
                    exchangeOrderId = id,
                    symbol = clean,
                    side = request.side,
                    orderType = request.orderType,
                    price = fill.averagePrice,
                    quantity = requestedQty,
                    executedQuantity = fill.quantity,
                    fee = fill.fee,
                    closedAtEpochSeconds = now / 1000L,
                    status = if (remaining > BigDecimal.ZERO) "partial" else "closed",
                    description = "PAPER ${if (fill.maker) "maker" else "taker"} fill; slippage=${fill.slippagePercent}% depth=${fill.usedDepth}; purpose=${request.purpose}"
                )
            )
        }
        OrderResult(
            exchangeOrderId = id,
            symbol = clean,
            side = request.side,
            executedQuantity = fill.quantity,
            averagePrice = fill.averagePrice,
            fee = fill.fee,
            paper = true,
            realizedPnlQuote = realizedPnl
        )
    }

    override suspend fun getOpenOrders(): List<LiveOrderInfo> = orderMutex.withLock {
        loadPending().values.sortedByDescending { it.createdAtMs }.map { p ->
            LiveOrderInfo(
                exchangeOrderId = p.id,
                symbol = p.symbol,
                side = p.side,
                orderType = p.orderType,
                price = p.price,
                quantity = p.originalQuantity,
                executedQuantity = p.executedQuantity,
                remainingQuantity = p.remainingQuantity,
                status = "open",
                openedAtEpochSeconds = p.createdAtMs / 1000L,
                description = "PAPER pending; postOnly=${p.postOnly}; purpose=${p.purpose}"
            )
        }
    }

    override suspend fun cancelOrder(orderId: String): Boolean = orderMutex.withLock {
        val pending = loadPending()[orderId] ?: return@withLock false
        removePending(orderId)
        val avg = if (pending.executedQuantity > BigDecimal.ZERO) {
            pending.cumulativeCost.divide(pending.executedQuantity, 12, RoundingMode.HALF_UP)
        } else pending.price
        appendClosed(
            ClosedOrderInfo(
                exchangeOrderId = pending.id,
                symbol = pending.symbol,
                side = pending.side,
                orderType = pending.orderType,
                price = avg,
                quantity = pending.originalQuantity,
                executedQuantity = pending.executedQuantity,
                fee = pending.cumulativeFee,
                closedAtEpochSeconds = System.currentTimeMillis() / 1000L,
                status = "cancelled",
                description = "PAPER cancelled with remaining=${pending.remainingQuantity}; purpose=${pending.purpose}"
            )
        )
        true
    }

    override suspend fun getClosedOrders(limit: Int): List<ClosedOrderInfo> = orderMutex.withLock {
        loadClosed().sortedByDescending { it.closedAtEpochSeconds }.take(limit.coerceAtLeast(1))
    }

    private suspend fun processPendingOrders(symbol: String, ticker: MarketTicker) = orderMutex.withLock {
        val clean = normalizeSymbol(symbol)
        val snapshot = loadPending()
        snapshot.values.filter { it.symbol == clean }.forEach { pending ->
            val trigger = when (pending.orderType) {
                OrderType.LIMIT -> when (pending.side) {
                    OrderSide.BUY -> ticker.ask <= pending.price
                    OrderSide.SELL -> ticker.bid >= pending.price
                }
                OrderType.STOP_LOSS -> stopTriggered(pending.side, pending.price, ticker)
                OrderType.TAKE_PROFIT -> takeProfitTriggered(pending.side, pending.price, ticker)
                OrderType.MARKET -> true
            }
            if (!trigger) return@forEach

            val isRestingLimit = pending.orderType == OrderType.LIMIT && pending.restingMaker
            val fill = simulateFill(
                symbol = clean,
                side = pending.side,
                requestedQuantity = pending.remainingQuantity,
                ticker = ticker,
                maker = isRestingLimit,
                limitPrice = if (pending.orderType == OrderType.LIMIT) pending.price else null
            )
            if (fill.quantity <= BigDecimal.ZERO) return@forEach
            val realizedPnl = applyWalletFill(clean, pending.side, fill)
            recordDeferredFill(pending, fill, realizedPnl)

            val newRemaining = pending.remainingQuantity.subtract(fill.quantity).max(BigDecimal.ZERO)
            val newExecuted = pending.executedQuantity.add(fill.quantity)
            val newCost = pending.cumulativeCost.add(fill.notional)
            val newFee = pending.cumulativeFee.add(fill.fee)
            if (newRemaining > BigDecimal.ZERO && pending.orderType == OrderType.LIMIT) {
                savePending(
                    pending.copy(
                        remainingQuantity = newRemaining,
                        executedQuantity = newExecuted,
                        cumulativeCost = newCost,
                        cumulativeFee = newFee,
                        restingMaker = true
                    )
                )
            } else {
                removePending(pending.id)
                val avg = if (newExecuted > BigDecimal.ZERO) newCost.divide(newExecuted, 12, RoundingMode.HALF_UP) else pending.price
                appendClosed(
                    ClosedOrderInfo(
                        exchangeOrderId = pending.id,
                        symbol = pending.symbol,
                        side = pending.side,
                        orderType = pending.orderType,
                        price = avg,
                        quantity = pending.originalQuantity,
                        executedQuantity = newExecuted,
                        fee = newFee,
                        closedAtEpochSeconds = System.currentTimeMillis() / 1000L,
                        status = if (newRemaining > BigDecimal.ZERO) "partial" else "closed",
                        description = "PAPER deferred ${if (fill.maker) "maker" else "taker"} fill; trigger=${pending.price}; purpose=${pending.purpose}"
                    )
                )
            }
        }
    }

    private suspend fun simulateFill(
        symbol: String,
        side: OrderSide,
        requestedQuantity: BigDecimal,
        ticker: MarketTicker,
        maker: Boolean,
        limitPrice: BigDecimal?
    ): SimulatedFill {
        val feeRate = if (maker) makerFeeRate else takerFeeRate
        val wallet = balances()
        val base = baseAsset(symbol)
        val quote = quoteAsset(symbol)
        var remaining = requestedQuantity.max(BigDecimal.ZERO)
        if (side == OrderSide.SELL) remaining = remaining.min(wallet[base] ?: BigDecimal.ZERO)
        if (remaining <= BigDecimal.ZERO) return SimulatedFill(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, maker, BigDecimal.ZERO, false)

        val book = getOrderBook(symbol, 100)
        val levels = when (side) {
            OrderSide.BUY -> book?.asks.orEmpty()
            OrderSide.SELL -> book?.bids.orEmpty()
        }.filter { level ->
            if (limitPrice == null) true
            else when (side) {
                OrderSide.BUY -> level.price <= limitPrice
                OrderSide.SELL -> level.price >= limitPrice
            }
        }

        var qty = BigDecimal.ZERO
        var notional = BigDecimal.ZERO
        var cash = wallet[quote] ?: BigDecimal.ZERO
        fun consume(price: BigDecimal, availableQty: BigDecimal) {
            if (remaining <= BigDecimal.ZERO || availableQty <= BigDecimal.ZERO || price <= BigDecimal.ZERO) return
            var take = remaining.min(availableQty)
            if (side == OrderSide.BUY) {
                val grossPerUnit = price.multiply(BigDecimal.ONE.add(feeRate))
                val affordable = if (grossPerUnit > BigDecimal.ZERO) cash.divide(grossPerUnit, 12, RoundingMode.DOWN) else BigDecimal.ZERO
                take = take.min(affordable)
            }
            if (take <= BigDecimal.ZERO) return
            val chunk = price.multiply(take)
            qty = qty.add(take)
            notional = notional.add(chunk)
            remaining = remaining.subtract(take)
            if (side == OrderSide.BUY) cash = cash.subtract(chunk.add(chunk.multiply(feeRate))).max(BigDecimal.ZERO)
        }

        levels.forEach { level -> consume(level.price, level.quantity) }
        val usedDepth = levels.isNotEmpty() && qty > BigDecimal.ZERO
        if (qty <= BigDecimal.ZERO) {
            val fallback = when (side) {
                OrderSide.BUY -> ticker.ask
                OrderSide.SELL -> ticker.bid
            }.let { observed ->
                if (limitPrice == null) observed
                else when (side) {
                    OrderSide.BUY -> observed.min(limitPrice)
                    OrderSide.SELL -> observed.max(limitPrice)
                }
            }
            consume(fallback, remaining)
        }
        if (qty <= BigDecimal.ZERO) return SimulatedFill(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, maker, BigDecimal.ZERO, usedDepth)
        val avg = notional.divide(qty, 12, RoundingMode.HALF_UP)
        val fee = notional.multiply(feeRate).setScale(12, RoundingMode.HALF_UP)
        val reference = if (side == OrderSide.BUY) ticker.ask else ticker.bid
        val slippage = if (reference > BigDecimal.ZERO) {
            val raw = if (side == OrderSide.BUY) avg.subtract(reference) else reference.subtract(avg)
            raw.divide(reference, 12, RoundingMode.HALF_UP).multiply(BigDecimal("100")).max(BigDecimal.ZERO)
        } else BigDecimal.ZERO
        return SimulatedFill(qty, avg, fee, maker, slippage, usedDepth)
    }

    private fun applyWalletFill(symbol: String, side: OrderSide, fill: SimulatedFill): BigDecimal {
        val wallet = balances().toMutableMap()
        val base = baseAsset(symbol)
        val quote = quoteAsset(symbol)
        val basisKey = "${base}|${quote}"
        val basis = loadCostBasis(basisKey)
        val realized = when (side) {
            OrderSide.BUY -> {
                val available = wallet[quote] ?: BigDecimal.ZERO
                val debit = fill.notional.add(fill.fee)
                if (debit > available.add(BigDecimal("0.00000001"))) error("Paper BUY fill exceeds available $quote balance.")
                wallet[quote] = available.subtract(debit).max(BigDecimal.ZERO)
                wallet[base] = (wallet[base] ?: BigDecimal.ZERO).add(fill.quantity)
                saveCostBasis(basisKey, PaperCostBasis(basis.quantity.add(fill.quantity), basis.totalCostQuote.add(debit)))
                BigDecimal.ZERO
            }
            OrderSide.SELL -> {
                val available = wallet[base] ?: BigDecimal.ZERO
                if (fill.quantity > available.add(BigDecimal("0.00000001"))) error("Paper SELL fill exceeds available $base balance.")
                val avgCost = if (basis.quantity > BigDecimal.ZERO) basis.totalCostQuote.divide(basis.quantity, 16, RoundingMode.HALF_UP) else BigDecimal.ZERO
                val allocatedCost = avgCost.multiply(fill.quantity)
                val netProceeds = fill.notional.subtract(fill.fee)
                wallet[base] = available.subtract(fill.quantity).max(BigDecimal.ZERO)
                wallet[quote] = (wallet[quote] ?: BigDecimal.ZERO).add(netProceeds).max(BigDecimal.ZERO)
                val remainingBasisQty = basis.quantity.subtract(fill.quantity).max(BigDecimal.ZERO)
                val remainingBasisCost = basis.totalCostQuote.subtract(allocatedCost).max(BigDecimal.ZERO)
                saveCostBasis(basisKey, PaperCostBasis(remainingBasisQty, if (remainingBasisQty > BigDecimal.ZERO) remainingBasisCost else BigDecimal.ZERO))
                if (avgCost > BigDecimal.ZERO) netProceeds.subtract(allocatedCost) else BigDecimal.ZERO
            }
        }
        saveBalances(wallet)
        return realized.setScale(12, RoundingMode.HALF_UP)
    }

    private fun loadCostBasis(key: String): PaperCostBasis {
        val p = costBasisPrefs ?: return PaperCostBasis(BigDecimal.ZERO, BigDecimal.ZERO)
        val raw = p.getString(key, null) ?: return PaperCostBasis(BigDecimal.ZERO, BigDecimal.ZERO)
        val parts = raw.split('|')
        return if (parts.size == 2) PaperCostBasis(parts[0].toBigDecimalOrNull() ?: BigDecimal.ZERO, parts[1].toBigDecimalOrNull() ?: BigDecimal.ZERO)
        else PaperCostBasis(BigDecimal.ZERO, BigDecimal.ZERO)
    }

    private fun saveCostBasis(key: String, basis: PaperCostBasis) {
        val p = costBasisPrefs ?: return
        if (basis.quantity <= BigDecimal.ZERO) p.edit().putString(key, null).apply()
        else p.edit().putString(key, "${basis.quantity.toPlainString()}|${basis.totalCostQuote.toPlainString()}").apply()
    }

    private suspend fun recordDeferredFill(pending: PendingPaperOrder, fill: SimulatedFill, realizedPnl: BigDecimal) {
        val dao = tradeDao ?: return
        val fillId = "${pending.id}-paperfill-${System.currentTimeMillis()}"
        val exists = runCatching { dao.recentTradesSnapshot(500).any { it.exchangeOrderId == fillId } }.getOrDefault(false)
        if (exists) return
        runCatching {
            dao.insertTrade(
                TradeEntity(
                    symbol = pending.symbol,
                    side = pending.side.name,
                    quantity = fill.quantity.toPlainString(),
                    priceEur = fill.averagePrice.toPlainString(),
                    feeEur = fill.fee.toPlainString(),
                    paper = true,
                    realizedPnlEur = realizedPnl.toPlainString(),
                    aiReason = "Deferred PAPER fill: source order=${pending.id}; type=${pending.orderType}; maker=${fill.maker}; purpose=${pending.purpose}; slippage=${fill.slippagePercent}%",
                    clientOrderId = pending.id,
                    exchangeOrderId = fillId,
                    timestampEpochMs = System.currentTimeMillis()
                )
            )
        }
    }

    private fun stopTriggered(side: OrderSide, trigger: BigDecimal, ticker: MarketTicker): Boolean = when (side) {
        OrderSide.BUY -> ticker.ask >= trigger
        OrderSide.SELL -> ticker.bid <= trigger
    }

    private fun takeProfitTriggered(side: OrderSide, trigger: BigDecimal, ticker: MarketTicker): Boolean = when (side) {
        OrderSide.BUY -> ticker.ask <= trigger
        OrderSide.SELL -> ticker.bid >= trigger
    }

    private fun ensureSeeded() {
        if (walletPrefs == null) return
        if (!walletPrefs.contains("seeded")) {
            walletPrefs.edit().putBoolean("seeded", true).putString("EUR", "1000.00").apply()
        }
    }

    private fun balances(): Map<String, BigDecimal> {
        val p = walletPrefs ?: return memoryBalances.toMap()
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
        val p = walletPrefs
        if (p == null) {
            memoryBalances.clear()
            newBalances.forEach { (asset, amount) -> if (amount > BigDecimal.ZERO) memoryBalances[asset.uppercase()] = amount }
            return
        }
        val edit = p.edit().clear().putBoolean("seeded", true)
        newBalances.forEach { (asset, amount) ->
            if (amount > BigDecimal.ZERO) edit.putString(asset.uppercase(), amount.setScale(12, RoundingMode.DOWN).stripTrailingZeros().toPlainString())
        }
        edit.apply()
    }

    private fun loadPending(): MutableMap<String, PendingPaperOrder> {
        if (orderPrefs == null) return memoryPending.toMutableMap()
        val raw = orderPrefs.getString("pending", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = linkedMapOf<String, PendingPaperOrder>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val p = runCatching { pendingFromJson(o) }.getOrNull() ?: continue
            out[p.id] = p
        }
        return out
    }

    private fun savePending(order: PendingPaperOrder) {
        val all = loadPending()
        all[order.id] = order
        persistPending(all.values)
    }

    private fun removePending(id: String) {
        val all = loadPending()
        all.remove(id)
        persistPending(all.values)
    }

    private fun persistPending(values: Collection<PendingPaperOrder>) {
        if (orderPrefs == null) {
            memoryPending.clear(); values.forEach { memoryPending[it.id] = it }; return
        }
        val arr = JSONArray(); values.forEach { arr.put(pendingToJson(it)) }
        orderPrefs.edit().putString("pending", arr.toString()).apply()
    }

    private fun pendingToJson(p: PendingPaperOrder) = JSONObject().apply {
        put("id", p.id); put("symbol", p.symbol); put("side", p.side.name); put("orderType", p.orderType.name)
        put("originalQuantity", p.originalQuantity.toPlainString()); put("remainingQuantity", p.remainingQuantity.toPlainString())
        put("price", p.price.toPlainString()); put("postOnly", p.postOnly); put("purpose", p.purpose); put("createdAtMs", p.createdAtMs)
        put("executedQuantity", p.executedQuantity.toPlainString()); put("cumulativeCost", p.cumulativeCost.toPlainString())
        put("cumulativeFee", p.cumulativeFee.toPlainString()); put("restingMaker", p.restingMaker)
    }

    private fun pendingFromJson(o: JSONObject) = PendingPaperOrder(
        id = o.getString("id"), symbol = o.getString("symbol"), side = OrderSide.valueOf(o.getString("side")),
        orderType = OrderType.valueOf(o.getString("orderType")),
        originalQuantity = o.optString("originalQuantity", "0").toBigDecimal(),
        remainingQuantity = o.optString("remainingQuantity", "0").toBigDecimal(),
        price = o.optString("price", "0").toBigDecimal(), postOnly = o.optBoolean("postOnly", false),
        purpose = o.optString("purpose", "ENTRY"), createdAtMs = o.optLong("createdAtMs", System.currentTimeMillis()),
        executedQuantity = o.optString("executedQuantity", "0").toBigDecimal(),
        cumulativeCost = o.optString("cumulativeCost", "0").toBigDecimal(),
        cumulativeFee = o.optString("cumulativeFee", "0").toBigDecimal(), restingMaker = o.optBoolean("restingMaker", false)
    )

    private fun loadClosed(): MutableList<ClosedOrderInfo> {
        if (orderPrefs == null) return memoryClosed.toMutableList()
        val raw = orderPrefs.getString("closed", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = mutableListOf<ClosedOrderInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            runCatching { closedFromJson(o) }.getOrNull()?.let(out::add)
        }
        return out
    }

    private fun appendClosed(order: ClosedOrderInfo) {
        val all = loadClosed()
        all.removeAll { it.exchangeOrderId == order.exchangeOrderId }
        all.add(order)
        val trimmed = all.sortedByDescending { it.closedAtEpochSeconds }.take(500)
        if (orderPrefs == null) {
            memoryClosed.clear(); memoryClosed.addAll(trimmed); return
        }
        val arr = JSONArray(); trimmed.forEach { arr.put(closedToJson(it)) }
        orderPrefs.edit().putString("closed", arr.toString()).apply()
    }

    private fun closedToJson(c: ClosedOrderInfo) = JSONObject().apply {
        put("id", c.exchangeOrderId); put("symbol", c.symbol); put("side", c.side.name); put("orderType", c.orderType.name)
        put("price", c.price.toPlainString()); put("quantity", c.quantity.toPlainString()); put("executedQuantity", c.executedQuantity.toPlainString())
        put("fee", c.fee.toPlainString()); put("closedAt", c.closedAtEpochSeconds); put("status", c.status); put("description", c.description)
    }

    private fun closedFromJson(o: JSONObject) = ClosedOrderInfo(
        exchangeOrderId = o.getString("id"), symbol = o.getString("symbol"), side = OrderSide.valueOf(o.getString("side")),
        orderType = OrderType.valueOf(o.getString("orderType")), price = o.optString("price", "0").toBigDecimal(),
        quantity = o.optString("quantity", "0").toBigDecimal(), executedQuantity = o.optString("executedQuantity", "0").toBigDecimal(),
        fee = o.optString("fee", "0").toBigDecimal(), closedAtEpochSeconds = o.optLong("closedAt", 0L),
        status = o.optString("status", "closed"), description = o.optString("description", "")
    )

    private fun normalizeSymbol(symbol: String): String = symbol.uppercase().replace("/", "").replace("-", "").replace("XBT", "BTC")

    private fun baseAsset(symbol: String): String {
        val upper = normalizeSymbol(symbol)
        return upper.removeSuffix(quoteAsset(upper)).ifBlank { upper.take(3) }
    }

    private fun quoteAsset(symbol: String): String {
        val upper = normalizeSymbol(symbol)
        val knownQuotes = listOf("USDT", "USDC", "EUR", "USD", "GBP", "CHF", "AUD", "CAD", "JPY", "BTC", "ETH")
        return knownQuotes.firstOrNull { upper.endsWith(it) && upper.length > it.length } ?: "EUR"
    }
}
