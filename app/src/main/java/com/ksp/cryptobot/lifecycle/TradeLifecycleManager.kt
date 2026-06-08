package com.ksp.cryptobot.lifecycle

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.AppDao
import com.ksp.cryptobot.data.PositionEntity
import com.ksp.cryptobot.data.TaxReportEntity
import com.ksp.cryptobot.data.TradeEntity
import com.ksp.cryptobot.exchange.CryptoExchangeClient
import com.ksp.cryptobot.learning.TrueSelfLearningEngine
import com.ksp.cryptobot.learning.SpikeProfitTimingEngine
import com.ksp.cryptobot.status.BotStatusStore
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

/**
 * Full live lifecycle layer.
 *
 * This is intentionally conservative: it does not promise maximum possible profit, because no bot can know that.
 * It maximizes *captured* profit by monitoring live positions, updating high-water marks, taking partial profit,
 * moving to trailing exits, and selling automatically on bearish or risk-off conditions when enabled.
 */
class TradeLifecycleManager(
    private val dao: AppDao,
    private val statusStore: BotStatusStore
) {
    private val selfLearningEngine = TrueSelfLearningEngine()
    private val spikeProfitTimingEngine = SpikeProfitTimingEngine()
    private fun log(message: String, level: String = "INFO") = statusStore.write(message, level)

    suspend fun runPreScanMaintenance(settings: BotSettings, exchange: CryptoExchangeClient) {
        if (!settings.liveLifecycleManagerEnabled) return
        if (settings.syncKrakenHistory) syncClosedOrders(exchange)
        refreshPositionRows(settings, exchange)
    }

    suspend fun runPostDecisionManagement(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        decisions: List<AiDecision>
    ): LifecycleSnapshot {
        val messages = mutableListOf<String>()
        if (!settings.liveLifecycleManagerEnabled || settings.mode != BotMode.LIVE_AUTO) {
            val snapshot = snapshot(settings, exchange, listOf("Lifecycle manager not active for mode=${settings.mode}."))
            return snapshot
        }
        val positions = refreshPositionRows(settings, exchange)
        val openOrders = runCatching { exchange.getOpenOrders() }.getOrDefault(emptyList())
        val decisionBySymbol = decisions.associateBy { it.symbol.uppercase() }
        positions.forEach { position ->
            val decision = decisionBySymbol[position.symbol.uppercase()]
            val managedMessages = managePosition(settings, exchange, position, decision, openOrders)
            messages.addAll(managedMessages)
        }
        return snapshot(settings, exchange, messages)
    }

    suspend fun snapshot(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        extraMessages: List<String> = emptyList()
    ): LifecycleSnapshot {
        val positions = buildLivePositions(settings, exchange)
        val openOrders = runCatching { exchange.getOpenOrders() }.getOrDefault(emptyList())
        val performance = performanceSummary()
        return LifecycleSnapshot(positions, openOrders, performance, extraMessages)
    }

    private suspend fun managePosition(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        position: PositionInfo,
        decision: AiDecision?,
        openOrders: List<LiveOrderInfo>
    ): List<String> {
        val out = mutableListOf<String>()
        if (position.freeQuantity <= BigDecimal.ZERO) return out
        val symbol = position.symbol
        val hasSellOrder = openOrders.any { it.symbol == symbol && it.side == OrderSide.SELL && it.remainingQuantity > BigDecimal.ZERO }
        val bearish = decision?.finalAction == SignalAction.SELL || decision?.finalAction == SignalAction.AVOID || decision?.finalAction == SignalAction.STRONG_AVOID
        val riskOffSell = settings.forceSellOnBearishSignal && bearish && decision?.allowedToTrade == true
        val hitStop = settings.autoStopLossEnabled && position.currentPrice <= position.stopPrice && position.stopPrice > BigDecimal.ZERO
        val hitTrailing = settings.profitMaximizerEnabled && settings.enableTrailingStop && position.currentPrice <= position.trailingStopPrice && position.trailingStopPrice > position.entryPrice
        val hitTakeProfit = settings.autoTakeProfitEnabled && position.currentPrice >= position.takeProfitPrice && position.takeProfitPrice > BigDecimal.ZERO

        val spikeTiming = if (settings.spikeProfitTimingEnabled && position.unrealizedPnlPercent >= settings.spikeTimingMinProfitPercent) {
            val h1 = runCatching { exchange.getCandles(symbol, Timeframe.H1, settings.spikeTimingLookbackCandles.coerceIn(80, 720)) }.getOrDefault(emptyList())
            val h4 = runCatching { exchange.getCandles(symbol, Timeframe.H4, settings.spikeTimingLookbackCandles.coerceIn(80, 720)) }.getOrDefault(emptyList())
            spikeProfitTimingEngine.evaluate(settings, position, h1, h4, decision)
        } else {
            spikeProfitTimingEngine.evaluate(settings.copy(spikeProfitTimingEnabled = false), position, emptyList(), emptyList(), decision)
        }
        if (settings.spikeProfitTimingEnabled && spikeTiming.explanation.isNotBlank()) {
            out += "[$symbol] ${spikeTiming.explanation}"
        }

        val reason = when {
            hitTrailing && !spikeTiming.shouldHold -> "trailing-stop profit capture"
            hitTakeProfit && !spikeTiming.shouldHold -> "take-profit target reached"
            hitStop -> "stop-loss protection"
            riskOffSell -> "AI bearish/risk-off sell signal"
            spikeTiming.shouldSellNow -> "spike-exhaustion profit capture"
            else -> null
        }
        if (reason == null) {
            val spikeNote = if (spikeTiming.shouldHold) " Spike timing is holding for continuation." else ""
            out += "[$symbol] Lifecycle hold. PnL=${position.unrealizedPnlPercent.setScale(2, RoundingMode.HALF_UP)}%, TP=${position.takeProfitPrice.scale2()}, SL=${position.stopPrice.scale2()}, trail=${position.trailingStopPrice.scale2()}.$spikeNote"
            return out
        }

        if ((hitTrailing || hitTakeProfit) && spikeTiming.shouldHold) {
            out += "[$symbol] Spike timing HOLD instead of sell: ${spikeTiming.explanation}"
            log(out.last(), "LEARN")
            return out
        }

        val learnedHold = selfLearningEngine.evaluateLearnedHoldExit(dao, settings, position, reason, decision)
        if (learnedHold.shouldHold) {
            out += "[$symbol] Learned HOLD instead of sell: ${learnedHold.explanation}"
            log(out.last(), "LEARN")
            return out
        } else if (settings.learnedHoldForProfitEnabled && learnedHold.explanation.isNotBlank()) {
            out += "[$symbol] Learned hold check: ${learnedHold.explanation}"
        }

        if (hasSellOrder && !settings.enableMarketOrders) {
            out += "[$symbol] Exit condition hit ($reason), but an existing SELL order is already open. No duplicate order sent."
            log(out.last(), "WARN")
            return out
        }

        val sellPercent = if (hitTakeProfit && settings.enablePartialTakeProfit && position.unrealizedPnlPercent > BigDecimal.ZERO) {
            settings.partialExitPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP).coerceIn(BigDecimal("0.05"), BigDecimal.ONE)
        } else BigDecimal.ONE
        val qty = position.freeQuantity.multiply(sellPercent).setScale(8, RoundingMode.DOWN)
        if (qty <= BigDecimal.ZERO) return out
        val request = OrderRequest(
            symbol = symbol,
            side = OrderSide.SELL,
            quantity = qty,
            limitPrice = if (settings.enableMarketOrders) null else position.currentPrice,
            orderType = if (settings.enableMarketOrders) OrderType.MARKET else OrderType.LIMIT,
            clientOrderId = "ksp-exit-${symbol.lowercase()}-${System.currentTimeMillis()}",
            reduceOnly = true,
            purpose = reason
        )
        val result = runCatching { exchange.placeOrder(request) }
            .onSuccess { placed ->
                val msg = "[$symbol] Automatic SELL submitted by lifecycle manager: reason=$reason, qty=$qty, type=${request.orderType}, orderId=${placed.exchangeOrderId}."
                out += msg
                log(msg, "LIVE")
                recordTradeFromLifecycle(symbol, OrderSide.SELL, qty, position.currentPrice, placed.exchangeOrderId, reason)
                if (sellPercent >= BigDecimal.ONE) dao.updatePositionStatus(symbol, "EXIT_SUBMITTED", System.currentTimeMillis())
            }
            .onFailure { error ->
                val msg = "[$symbol] Automatic SELL failed ($reason): ${error.message}"
                out += msg
                log(msg, "ERROR")
            }
            .getOrNull()
        return out
    }

    suspend fun refreshPositionRows(settings: BotSettings, exchange: CryptoExchangeClient): List<PositionInfo> {
        val positions = buildLivePositions(settings, exchange)
        val now = System.currentTimeMillis()
        positions.forEach { p ->
            val prev = dao.positionForSymbol(p.symbol)
            val highest = listOf(prev?.highestPriceEur?.toBigDecimalOrNull() ?: BigDecimal.ZERO, p.highestPrice, p.currentPrice).maxOrNull() ?: p.currentPrice
            dao.upsertPosition(
                PositionEntity(
                    symbol = p.symbol,
                    baseAsset = p.baseAsset,
                    quantity = p.quantity.toPlainString(),
                    entryPriceEur = p.entryPrice.toPlainString(),
                    highestPriceEur = highest.toPlainString(),
                    stopPriceEur = p.stopPrice.toPlainString(),
                    takeProfitPriceEur = p.takeProfitPrice.toPlainString(),
                    trailingStopPriceEur = p.trailingStopPrice.toPlainString(),
                    openedAtEpochMs = prev?.openedAtEpochMs ?: now,
                    updatedAtEpochMs = now,
                    status = "OPEN",
                    source = "LIVE_PORTFOLIO"
                )
            )
        }
        return positions
    }

    private suspend fun buildLivePositions(settings: BotSettings, exchange: CryptoExchangeClient): List<PositionInfo> {
        val balances = runCatching { exchange.getPortfolioBalances() }.getOrDefault(emptyList())
        return balances.filter { it.asset.uppercase() !in setOf("EUR", "ZEUR") && it.total > BigDecimal.ZERO }.mapNotNull { asset ->
            val symbol = "${asset.asset.uppercase()}EUR".replace("XBTEUR", "BTCEUR")
            val ticker = runCatching { exchange.getTicker(symbol) }.getOrNull() ?: return@mapNotNull null
            val current = ticker.bid.takeIf { it > BigDecimal.ZERO } ?: ticker.lastPrice
            val prev = dao.positionForSymbol(symbol)
            val lastBuy = dao.recentTradesSnapshot(200).firstOrNull { it.symbol == symbol && it.side == OrderSide.BUY.name }
            val entry = prev?.entryPriceEur?.toBigDecimalOrNull()
                ?: lastBuy?.priceEur?.toBigDecimalOrNull()
                ?: current
            val highest = listOf(prev?.highestPriceEur?.toBigDecimalOrNull() ?: current, current).maxOrNull() ?: current
            val pnl = current.subtract(entry).multiply(asset.total)
            val pnlPct = if (entry > BigDecimal.ZERO) current.subtract(entry).divide(entry, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal.ZERO
            val stop = entry.multiply(BigDecimal.ONE.subtract(settings.stopLossPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
            val take = entry.multiply(BigDecimal.ONE.add(settings.takeProfitPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
            val trailArmed = pnlPct >= settings.trailingActivationPercent
            val trailing = if (trailArmed) highest.multiply(BigDecimal.ONE.subtract(settings.trailingDistancePercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP))) else BigDecimal.ZERO
            PositionInfo(
                symbol = symbol,
                baseAsset = asset.asset.uppercase(),
                quantity = asset.total,
                freeQuantity = asset.free,
                entryPrice = entry,
                currentPrice = current,
                highestPrice = highest,
                unrealizedPnlEur = pnl,
                unrealizedPnlPercent = pnlPct,
                stopPrice = stop,
                takeProfitPrice = take,
                trailingStopPrice = trailing,
                managed = settings.autoExitManagerEnabled,
                reason = if (trailArmed) "Trailing armed" else "Waiting for TP/SL/trailing/AI exit"
            )
        }
    }

    private suspend fun syncClosedOrders(exchange: CryptoExchangeClient) {
        val closed = runCatching { exchange.getClosedOrders(50) }.getOrDefault(emptyList())
        closed.take(50).forEach { order ->
            if (order.executedQuantity <= BigDecimal.ZERO) return@forEach
            val exists = dao.recentTradesSnapshot(300).any { it.exchangeOrderId == order.exchangeOrderId }
            if (!exists) {
                dao.insertTrade(
                    TradeEntity(
                        symbol = order.symbol,
                        side = order.side.name,
                        quantity = order.executedQuantity.toPlainString(),
                        priceEur = order.price.toPlainString(),
                        feeEur = order.fee.toPlainString(),
                        paper = false,
                        aiScore = 0,
                        aiReason = "Synced from Kraken closed orders: ${order.description}",
                        clientOrderId = "kraken-sync-${order.exchangeOrderId}",
                        exchangeOrderId = order.exchangeOrderId,
                        timestampEpochMs = order.closedAtEpochSeconds * 1000L
                    )
                )
            }
        }
        if (closed.isNotEmpty()) log("Closed-order sync complete. Synced/checked ${closed.size} Kraken closed order(s).", "INFO")
    }

    private suspend fun recordTradeFromLifecycle(symbol: String, side: OrderSide, quantity: BigDecimal, price: BigDecimal, orderId: String, reason: String) {
        dao.insertTrade(
            TradeEntity(
                symbol = symbol,
                side = side.name,
                quantity = quantity.toPlainString(),
                priceEur = price.toPlainString(),
                feeEur = "0.00",
                paper = false,
                aiReason = "Lifecycle exit: $reason",
                clientOrderId = "lifecycle-${System.currentTimeMillis()}",
                exchangeOrderId = orderId,
                timestampEpochMs = System.currentTimeMillis()
            )
        )
        dao.insertTaxReportRow(
            TaxReportEntity(
                timestampEpochMs = System.currentTimeMillis(),
                symbol = symbol,
                side = side.name,
                quantity = quantity.toPlainString(),
                priceEur = price.toPlainString(),
                feeEur = "0.00",
                realizedGainEur = "0.00",
                note = "Lifecycle managed exit: $reason. Review realized gain with Kraken export/accountant."
            )
        )
    }

    private suspend fun performanceSummary(): PerformanceSummary {
        val trades = dao.allTradesSnapshot()
        val realized = trades.fold(BigDecimal.ZERO) { acc, t -> acc.add(t.realizedPnlEur.toBigDecimalOrNull() ?: BigDecimal.ZERO) }
        val fees = trades.fold(BigDecimal.ZERO) { acc, t -> acc.add(t.feeEur.toBigDecimalOrNull() ?: BigDecimal.ZERO) }
        val wins = trades.count { (it.realizedPnlEur.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO }
        val losses = trades.count { (it.realizedPnlEur.toBigDecimalOrNull() ?: BigDecimal.ZERO) < BigDecimal.ZERO }
        val winRate = if (wins + losses > 0) BigDecimal(wins).multiply(BigDecimal("100")).divide(BigDecimal(wins + losses), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
        return PerformanceSummary(
            totalTrades = trades.size,
            winningTrades = wins,
            losingTrades = losses,
            winRatePercent = winRate,
            realizedPnlEur = realized,
            estimatedFeesEur = fees,
            profitFactor = BigDecimal.ZERO,
            bestSymbol = trades.groupBy { it.symbol }.maxByOrNull { it.value.size }?.key ?: "n/a",
            worstSymbol = "n/a"
        )
    }

    private fun BigDecimal.scale2(): String = setScale(2, RoundingMode.DOWN).toPlainString()

    private fun <T : Comparable<T>> T.coerceIn(min: T, max: T): T = when {
        this < min -> min
        this > max -> max
        else -> this
    }
}
