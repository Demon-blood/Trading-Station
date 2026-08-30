
package com.ksp.cryptobot.lifecycle

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.*
import com.ksp.cryptobot.exchange.CryptoExchangeClient
import com.ksp.cryptobot.execution.ExecutionTruthGate
import com.ksp.cryptobot.execution.ProtectiveStopManager
import com.ksp.cryptobot.research.HandoffPositionPlanCodec
import com.ksp.cryptobot.status.BotStatusStore
import java.math.BigDecimal
import java.math.RoundingMode

object PartialFillMath {
    fun incrementalQuantity(exchangeCumulative: BigDecimal, alreadyRecorded: BigDecimal): BigDecimal =
        exchangeCumulative.subtract(alreadyRecorded).max(BigDecimal.ZERO)

    fun incrementalFee(exchangeCumulativeFee: BigDecimal, alreadyRecordedFee: BigDecimal): BigDecimal =
        exchangeCumulativeFee.subtract(alreadyRecordedFee).max(BigDecimal.ZERO)
}

/**
 * Synchronizes cumulative Kraken open-order partial fills into incremental journal rows
 * and local position exposure. It never treats an API failure as an empty open-order set.
 */
class PartialFillSynchronizer(
    private val dao: AppDao,
    private val statusStore: BotStatusStore,
    private val protectiveStops: ProtectiveStopManager?
) {
    suspend fun sync(settings: BotSettings, exchange: CryptoExchangeClient) {
        if (settings.mode == BotMode.PAPER) return

        val openOrders = ExecutionTruthGate.requireAuthoritative(
            "partial-fill open orders",
            runCatching { exchange.getOpenOrders() }
        )
        val candidates = openOrders.filter {
            it.side == OrderSide.BUY &&
                it.executedQuantity > BigDecimal.ZERO &&
                it.remainingQuantity > BigDecimal.ZERO
        }
        if (candidates.isEmpty()) return

        for (order in candidates) {
            val previousRows = dao.recentTradesSnapshot(500).filter {
                it.exchangeOrderId == order.exchangeOrderId && it.side.equals(OrderSide.BUY.name, true)
            }
            val recordedQty = previousRows.fold(BigDecimal.ZERO) { a, row ->
                a + (row.quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO)
            }
            val recordedFee = previousRows.fold(BigDecimal.ZERO) { a, row ->
                a + (row.feeEur.toBigDecimalOrNull() ?: BigDecimal.ZERO)
            }
            val deltaQty = PartialFillMath.incrementalQuantity(order.executedQuantity, recordedQty)
            val deltaFee = PartialFillMath.incrementalFee(order.fee, recordedFee)
            val fillPrice = order.averageFillPrice.takeIf { it > BigDecimal.ZERO } ?: order.price
            if (deltaQty > BigDecimal.ZERO && fillPrice > BigDecimal.ZERO) {
                dao.insertTrade(
                    TradeEntity(
                        symbol = order.symbol,
                        side = OrderSide.BUY.name,
                        quantity = deltaQty.toPlainString(),
                        priceEur = fillPrice.toPlainString(),
                        feeEur = deltaFee.toPlainString(),
                        paper = false,
                        realizedPnlEur = "0",
                        aiScore = 0,
                        aiReason = "Kraken incremental partial fill sync",
                        clientOrderId = order.clientOrderId,
                        exchangeOrderId = order.exchangeOrderId,
                        timestampEpochMs = System.currentTimeMillis()
                    )
                )
            }

            val existing = dao.positionForSymbol(order.symbol)
            val handoff = HandoffPositionPlanCodec.decode(existing?.source)
            val sameEntryOrder = handoff?.entryOrderId == order.exchangeOrderId ||
                existing?.source?.contains("M12_PARTIAL_ORDER=${order.exchangeOrderId}") == true
            if (existing == null || existing.status == "PENDING_ENTRY" || sameEntryOrder) {
                val entry = fillPrice.takeIf { it > BigDecimal.ZERO }
                    ?: existing?.entryPriceEur?.toBigDecimalOrNull()
                    ?: continue
                val stop = existing?.stopPriceEur?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO && it < entry }
                    ?: entry.multiply(
                        BigDecimal.ONE.subtract(
                            settings.stopLossPercent.divide(BigDecimal("100"), 12, RoundingMode.HALF_UP)
                        )
                    )
                val takeProfit = existing?.takeProfitPriceEur?.toBigDecimalOrNull()?.takeIf { it > entry }
                    ?: entry.multiply(
                        BigDecimal.ONE.add(
                            settings.takeProfitPercent.divide(BigDecimal("100"), 12, RoundingMode.HALF_UP)
                        )
                    )
                val now = System.currentTimeMillis()
                val openedAt = existing?.openedAtEpochMs ?: now
                val source = existing?.source?.takeIf { it.isNotBlank() }
                    ?: "M12_PARTIAL_ORDER=${order.exchangeOrderId};CL=${order.clientOrderId}"
                dao.upsertPosition(
                    PositionEntity(
                        symbol = order.symbol,
                        baseAsset = existing?.baseAsset ?: baseAsset(order.symbol),
                        quantity = order.executedQuantity.toPlainString(),
                        entryPriceEur = entry.toPlainString(),
                        highestPriceEur = maxOf(existing?.highestPriceEur?.toBigDecimalOrNull() ?: entry, entry).toPlainString(),
                        stopPriceEur = stop.toPlainString(),
                        takeProfitPriceEur = takeProfit.toPlainString(),
                        trailingStopPriceEur = existing?.trailingStopPriceEur ?: "0",
                        openedAtEpochMs = openedAt,
                        updatedAtEpochMs = now,
                        status = "OPEN_PARTIAL",
                        source = source
                    )
                )

                val strategy = handoff?.strategyId ?: "KRAKEN_PARTIAL_FILL"
                val protection = protectiveStops?.protectOrFlatten(
                    settings = settings,
                    exchange = exchange,
                    symbol = order.symbol,
                    quantity = order.executedQuantity,
                    entryPrice = entry,
                    stopPrice = stop,
                    strategyId = strategy,
                    paper = false
                )
                statusStore.write(
                    "Partial fill synced ${order.symbol}: cumulative=${order.executedQuantity}, delta=$deltaQty, avg=$entry, remaining=${order.remainingQuantity}, protected=${protection?.protected}.",
                    if (protection == null || protection.protected) "LIVE" else "ERROR"
                )
            } else {
                statusStore.write(
                    "Partial fill ${order.exchangeOrderId} observed for ${order.symbol}, but an unrelated local position already exists. Journal delta was recorded; position quantity remains governed by authoritative balance reconciliation.",
                    "WARN"
                )
            }
        }
    }

    private fun baseAsset(symbol: String): String {
        val s = symbol.uppercase().replace("/", "").replace("-", "").replace("_", "")
        val quotes = listOf("USDT", "USDC", "EUR", "USD", "GBP", "CHF", "AUD", "CAD", "JPY", "BTC", "ETH")
        val quote = quotes.firstOrNull { s.endsWith(it) && s.length > it.length }.orEmpty()
        return s.removeSuffix(quote).replace("XBT", "BTC")
    }
}
