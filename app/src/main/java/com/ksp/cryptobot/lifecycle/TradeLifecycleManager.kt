package com.ksp.cryptobot.lifecycle

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.AppDao
import com.ksp.cryptobot.data.PositionEntity
import com.ksp.cryptobot.data.TaxReportEntity
import com.ksp.cryptobot.data.TradeEntity
import com.ksp.cryptobot.data.GovernanceDao
import com.ksp.cryptobot.data.GovernanceEventEntity
import com.ksp.cryptobot.data.ProductionIntelligenceStateEntity
import com.ksp.cryptobot.execution.AdvancedExitOptimizer
import com.ksp.cryptobot.execution.ProtectiveStopManager
import com.ksp.cryptobot.research.HandoffSideIntent
import com.ksp.cryptobot.research.ResearchExecutionRuntime
import com.ksp.cryptobot.research.HandoffPositionPlanCodec
import com.ksp.cryptobot.exchange.CryptoExchangeClient
import com.ksp.cryptobot.learning.TrueSelfLearningEngine
import com.ksp.cryptobot.learning.SpikeProfitTimingEngine
import com.ksp.cryptobot.status.BotStatusStore
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

internal fun isExplicitLifecycleSell(decision: AiDecision?): Boolean =
    decision?.finalAction == SignalAction.SELL && decision.allowedToTrade

internal fun shouldDeferSoftLifecycleExitForChurn(
    explicitSell: Boolean,
    handoffProtective: Boolean,
    enteredThisScan: Boolean,
    exitedThisScan: Boolean,
    positionAgeMinutes: Long,
    minHoldMinutes: Int
): Boolean = explicitSell && !handoffProtective && (
    enteredThisScan || exitedThisScan || positionAgeMinutes < minHoldMinutes.coerceAtLeast(0)
)

/**
 * Full live lifecycle layer.
 *
 * This is intentionally conservative: it does not promise maximum possible profit, because no bot can know that.
 * It maximizes *captured* profit by monitoring live positions, updating high-water marks, taking partial profit,
 * moving to trailing exits, and selling automatically on bearish or risk-off conditions when enabled.
 */
class TradeLifecycleManager(
    private val dao: AppDao,
    private val statusStore: BotStatusStore,
    private val governanceDao: GovernanceDao? = null
) {
    private val selfLearningEngine = TrueSelfLearningEngine()
    private val spikeProfitTimingEngine = SpikeProfitTimingEngine()
    private val advancedExitOptimizer = AdvancedExitOptimizer(governanceDao)
    private val protectiveStops = governanceDao?.let { ProtectiveStopManager(dao, it) }
    private fun log(message: String, level: String = "INFO") = statusStore.write(message, level)

    suspend fun runPreScanMaintenance(settings: BotSettings, exchange: CryptoExchangeClient) {
        if (!settings.liveLifecycleManagerEnabled) return
        if (settings.syncKrakenHistory && settings.mode != BotMode.PAPER) syncClosedOrders(settings, exchange)
        refreshPositionRows(settings, exchange)
    }

    suspend fun runPostDecisionManagement(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        decisions: List<AiDecision>,
        enteredSymbolsThisScan: Set<String> = emptySet(),
        exitedSymbolsThisScan: Set<String> = emptySet()
    ): LifecycleSnapshot {
        val messages = mutableListOf<String>()
        if (!settings.liveLifecycleManagerEnabled || (settings.mode != BotMode.LIVE_AUTO && settings.mode != BotMode.PAPER)) {
            val snapshot = snapshot(settings, exchange, listOf("Lifecycle manager not active for mode=${settings.mode}. Paper mode uses the lifecycle manager when enabled."))
            return snapshot
        }
        val positions = refreshPositionRows(settings, exchange)
        val openOrders = runCatching { exchange.getOpenOrders() }.getOrDefault(emptyList())
        val decisionBySymbol = decisions.associateBy { it.symbol.uppercase() }
        positions.forEach { position ->
            val decision = decisionBySymbol[position.symbol.uppercase()]
            val managedMessages = managePosition(settings, exchange, position, decision, openOrders, enteredSymbolsThisScan, exitedSymbolsThisScan)
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
        openOrders: List<LiveOrderInfo>,
        enteredSymbolsThisScan: Set<String>,
        exitedSymbolsThisScan: Set<String>
    ): List<String> {
        val out = mutableListOf<String>()
        if (position.quantity <= BigDecimal.ZERO) return out
        val symbol = position.symbol
        val persistedPosition = dao.positionForSymbol(symbol)
        val persistedHandoffPlan = HandoffPositionPlanCodec.decode(persistedPosition?.source)
        val protectiveStopOrders = openOrders.filter { it.symbol == symbol && it.side == OrderSide.SELL && it.orderType == OrderType.STOP_LOSS && it.remainingQuantity > BigDecimal.ZERO }
        val hasExchangeProtectiveStop = protectiveStopOrders.isNotEmpty()
        val hasSellOrder = openOrders.any { it.symbol == symbol && it.side == OrderSide.SELL && it.orderType != OrderType.STOP_LOSS && it.remainingQuantity > BigDecimal.ZERO }
        // Action contract: AVOID/STRONG_AVOID mean "do not open a new entry".
        // Only an explicit, allowed SELL is a discretionary signal exit.
        val explicitSignalSell = isExplicitLifecycleSell(decision)
        val riskOffSell = settings.forceSellOnBearishSignal && explicitSignalSell
        val handoffDirective = ResearchExecutionRuntime.snapshot(symbol)
        val handoffIntent = handoffDirective?.sideIntent
        val handoffProtective = handoffIntent == HandoffSideIntent.EXIT || handoffIntent == HandoffSideIntent.REDUCE
        val normalizedSymbol = symbol.uppercase().replace("/", "").replace("-", "")
        val enteredThisScan = normalizedSymbol in enteredSymbolsThisScan
        val exitedThisScan = normalizedSymbol in exitedSymbolsThisScan
        val trackedForChurn = dao.positionForSymbol(symbol)
        val positionAgeMinutes = trackedForChurn?.openedAtEpochMs?.let { opened ->
            ((System.currentTimeMillis() - opened).coerceAtLeast(0L) / 60_000L)
        } ?: Long.MAX_VALUE
        val softSignalExitDeferred = shouldDeferSoftLifecycleExitForChurn(
            explicitSell = riskOffSell,
            handoffProtective = handoffProtective,
            enteredThisScan = enteredThisScan,
            exitedThisScan = exitedThisScan,
            positionAgeMinutes = positionAgeMinutes,
            minHoldMinutes = settings.cooldownAfterBuyMinutes
        )
        val hitStop = settings.autoStopLossEnabled && position.currentPrice <= position.stopPrice && position.stopPrice > BigDecimal.ZERO
        val hitTrailing = settings.profitMaximizerEnabled && settings.enableTrailingStop && position.currentPrice <= position.trailingStopPrice && position.trailingStopPrice > position.entryPrice
        val hitTakeProfit = settings.autoTakeProfitEnabled && position.currentPrice >= position.takeProfitPrice && position.takeProfitPrice > BigDecimal.ZERO
        if (hitStop && hasExchangeProtectiveStop && settings.mode != BotMode.PAPER) {
            out += "[$symbol] Exchange protective stop remains authoritative at technical stop=${position.stopPrice}. ExistingStopOrders=${protectiveStopOrders.joinToString(",") { it.exchangeOrderId }}. No duplicate app-side SELL is sent while Kraken owns the stop."
            log(out.last(), "LIVE")
            return out
        }

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
            hitStop -> "stop-loss protection"
            handoffIntent == HandoffSideIntent.EXIT -> "handoff-source protective full exit"
            handoffIntent == HandoffSideIntent.REDUCE -> "handoff-source protective reduction"
            hitTrailing && !spikeTiming.shouldHold -> "trailing-stop profit capture"
            hitTakeProfit && !spikeTiming.shouldHold -> "take-profit target reached"
            riskOffSell && !softSignalExitDeferred -> "explicit AI SELL signal"
            spikeTiming.shouldSellNow -> "spike-exhaustion profit capture"
            else -> null
        }
        if (softSignalExitDeferred) {
            val minHold = settings.cooldownAfterBuyMinutes.coerceAtLeast(0)
            val why = when {
                exitedThisScan -> "an exit was already submitted for this symbol in the current scan"
                enteredThisScan -> "the position was entered in the current scan"
                else -> "position age ${positionAgeMinutes}m is below the ${minHold}m soft-signal hold floor"
            }
            out += "[$symbol] Soft SELL deferred to prevent churn: $why. Hard stop-loss, source-protective and profit-protection exits remain active."
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

        if (!handoffProtective) {
            val learnedHold = selfLearningEngine.evaluateLearnedHoldExit(dao, settings, position, reason, decision)
            if (learnedHold.shouldHold) {
                out += "[$symbol] Learned HOLD instead of sell: ${learnedHold.explanation}"
                log(out.last(), "LEARN")
                return out
            } else if (settings.learnedHoldForProfitEnabled && learnedHold.explanation.isNotBlank()) {
                out += "[$symbol] Learned hold check: ${learnedHold.explanation}"
            }
        } else {
            out += "[$symbol] Learned-hold deferral bypassed for handoff protective ${handoffIntent}; source protection cannot be converted into a hold."
        }

        if (hasSellOrder && !settings.enableMarketOrders) {
            out += "[$symbol] Exit condition hit ($reason), but an existing SELL order is already open. No duplicate order sent."
            log(out.last(), "WARN")
            return out
        }

        val exitPlan = advancedExitOptimizer.optimize(settings, position, decision, reason)
        out += "[$symbol] ${exitPlan.reason}"
        val sourceRequiredExit = persistedHandoffPlan != null && (hitStop || handoffProtective || hitTakeProfit)
        if ((!exitPlan.shouldExit || exitPlan.sellFraction <= BigDecimal.ZERO) && !sourceRequiredExit) {
            out += "[$symbol] Advanced exit optimizer deferred this soft exit."
            log(out.last(), "LEARN")
            return out
        }
        val sellPercent = when {
            hitStop -> BigDecimal.ONE
            handoffIntent == HandoffSideIntent.EXIT -> BigDecimal.ONE
            handoffIntent == HandoffSideIntent.REDUCE -> exitPlan.sellFraction.coerceIn(BigDecimal("0.25"), BigDecimal("0.50"))
            persistedHandoffPlan != null && hitTakeProfit -> BigDecimal.ONE
            else -> exitPlan.sellFraction
        }
        var sellableQuantity = position.freeQuantity
        val sourceManagedLiveExit = persistedHandoffPlan != null && settings.mode != BotMode.PAPER
        var cancelledProtectiveStop = false
        if (sourceManagedLiveExit && hasExchangeProtectiveStop) {
            val cancelled = protectiveStops?.cancelProtectiveStops(exchange, symbol)
            if (cancelled == null || !cancelled.first) {
                val msg = "[$symbol] Source-managed exit BLOCKED: existing Kraken protective stop could not be cancelled safely; refusing a competing SELL that could oversell the position."
                out += msg; log(msg, "ERROR")
                governanceDao?.insertEvent(GovernanceEventEntity(eventType="protective_stop_cancel_failure",symbol=symbol,strategy=(persistedHandoffPlan?.strategyId ?: handoffDirective?.strategyId ?: settings.strategyMode.name),mode=settings.mode.name,severity="CRITICAL",blocked=true,sizeMultiplier=0.0,reason=msg))
                return out
            }
            cancelledProtectiveStop = cancelled.second.isNotEmpty()
            val refreshed = runCatching { exchange.getPortfolioBalances() }.getOrDefault(emptyList())
            sellableQuantity = refreshed.firstOrNull { it.asset.equals(position.baseAsset, ignoreCase = true) }?.free ?: position.quantity
        }
        if (!sourceManagedLiveExit && sellableQuantity <= BigDecimal.ZERO) return out
        if (sourceManagedLiveExit && sellableQuantity <= BigDecimal.ZERO) {
            val restore = protectiveStops?.restoreAfterManagedExit(settings,exchange,symbol,position.quantity,position.entryPrice,position.stopPrice,(persistedHandoffPlan?.strategyId ?: handoffDirective?.strategyId ?: settings.strategyMode.name),false)
            val msg = "[$symbol] Protective stop cancelled but no sellable quantity became available; protection restoration=${restore?.protected}. ${restore?.reason.orEmpty()}"
            out += msg; log(msg,"ERROR"); return out
        }
        val qty = sellableQuantity.multiply(sellPercent).setScale(8, RoundingMode.DOWN)
        if (qty <= BigDecimal.ZERO) return out
        val lifecycleOrderType = if (sourceManagedLiveExit) OrderType.MARKET else exitPlan.orderType
        val request = OrderRequest(
            symbol = symbol,
            side = OrderSide.SELL,
            quantity = qty,
            limitPrice = if (lifecycleOrderType == OrderType.MARKET) null else position.currentPrice,
            orderType = lifecycleOrderType,
            clientOrderId = "ksp-exit-${symbol.lowercase()}-${System.currentTimeMillis()}",
            reduceOnly = true,
            purpose = "$reason; ${exitPlan.method}; strategy=${persistedHandoffPlan?.strategyId ?: handoffDirective?.strategyId ?: settings.strategyMode.name}"
        )
        val result = runCatching { exchange.placeOrder(request) }
            .onSuccess { placed ->
                val fillConfirmed = placed.executedQuantity > BigDecimal.ZERO && placed.averagePrice > BigDecimal.ZERO
                val strategyId = persistedHandoffPlan?.strategyId ?: handoffDirective?.strategyId ?: settings.strategyMode.name
                if (fillConfirmed) {
                    val actualQty = placed.executedQuantity.min(sellableQuantity).setScale(8, RoundingMode.DOWN)
                    val actualPrice = placed.averagePrice
                    val actualFee = placed.fee.max(BigDecimal.ZERO)
                    val realized = if (placed.realizedPnlQuote != BigDecimal.ZERO) placed.realizedPnlQuote
                        else actualPrice.subtract(position.entryPrice).multiply(actualQty).subtract(actualFee)
                    val msg = "[$symbol] Automatic SELL filled by lifecycle manager: reason=$reason/${exitPlan.method}, strategy=$strategyId, qty=$actualQty, avg=$actualPrice, fee=$actualFee, realized=$realized, orderId=${placed.exchangeOrderId}."
                    out += msg
                    log(msg, "LIVE")
                    recordTradeFromLifecycle(symbol, OrderSide.SELL, actualQty, actualPrice, actualFee, realized, placed.paper, placed.exchangeOrderId, "$reason; ${exitPlan.method}", strategyId)
                    val remainingAfterFill = position.quantity.subtract(actualQty).max(BigDecimal.ZERO)
                    if (sellPercent >= BigDecimal.ONE || remainingAfterFill <= BigDecimal.ZERO) {
                        dao.updatePositionStatus(symbol, "EXIT_SUBMITTED", System.currentTimeMillis())
                    } else if (hitTakeProfit && persistedHandoffPlan != null && persistedPosition != null) {
                        val hitTarget = persistedHandoffPlan.remainingTargets.firstOrNull { position.currentPrice >= it }
                        if (hitTarget != null) {
                            val nextPlan = HandoffPositionPlanCodec.afterTarget(persistedHandoffPlan, hitTarget)
                            val nextTarget = nextPlan.remainingTargets.firstOrNull() ?: BigDecimal.ZERO
                            dao.upsertPosition(persistedPosition.copy(takeProfitPriceEur = nextTarget.toPlainString(), source = HandoffPositionPlanCodec.encode(nextPlan), updatedAtEpochMs = System.currentTimeMillis()))
                            out += "[$symbol] Handoff target $hitTarget consumed after confirmed fill; nextTarget=${if (nextTarget > BigDecimal.ZERO) nextTarget.stripTrailingZeros().toPlainString() else "none"}."
                        }
                    }
                    if (sourceManagedLiveExit && remainingAfterFill > BigDecimal.ZERO && persistedHandoffPlan != null) {
                        val restored = protectiveStops?.restoreAfterManagedExit(settings,exchange,symbol,remainingAfterFill,position.entryPrice,position.stopPrice,(persistedHandoffPlan?.strategyId ?: handoffDirective?.strategyId ?: settings.strategyMode.name),false)
                        out += "[$symbol] Protective stop restored after managed partial exit: protected=${restored?.protected}. ${restored?.reason.orEmpty()}"
                        log(out.last(), if (restored?.protected == true) "LIVE" else "ERROR")
                    }
                } else {
                    val msg = "[$symbol] Lifecycle SELL accepted without confirmed fill: reason=$reason/${exitPlan.method}, strategy=$strategyId, submittedQty=$qty, type=${request.orderType}, orderId=${placed.exchangeOrderId}. No realized trade/PnL is recorded until exchange fill evidence arrives."
                    out += msg
                    log(msg, "LIVE")
                    if (sourceManagedLiveExit && cancelledProtectiveStop && persistedHandoffPlan != null) {
                        val restored = protectiveStops?.restoreAfterManagedExit(settings,exchange,symbol,position.quantity,position.entryPrice,position.stopPrice,(persistedHandoffPlan?.strategyId ?: handoffDirective?.strategyId ?: settings.strategyMode.name),false)
                        out += "[$symbol] Exit not yet filled; original protective stop restoration=${restored?.protected}. ${restored?.reason.orEmpty()}"
                        log(out.last(), if (restored?.protected == true) "LIVE" else "ERROR")
                    }
                    if (sellPercent >= BigDecimal.ONE) dao.updatePositionStatus(symbol, "EXIT_SUBMITTED", System.currentTimeMillis())
                }
            }
            .onFailure { error ->
                val msg = "[$symbol] Automatic SELL failed ($reason): ${error.message}"
                out += msg
                log(msg, "ERROR")
                // Restore source protection after managed exit submission failure if we cancelled it first.
                if (sourceManagedLiveExit && cancelledProtectiveStop && persistedHandoffPlan != null) {
                    val restored = protectiveStops?.restoreAfterManagedExit(settings,exchange,symbol,position.quantity,position.entryPrice,position.stopPrice,(persistedHandoffPlan?.strategyId ?: handoffDirective?.strategyId ?: settings.strategyMode.name),false)
                    out += "[$symbol] Exit submission failed; protective stop restoration=${restored?.protected}. ${restored?.reason.orEmpty()}"
                    log(out.last(), if (restored?.protected == true) "LIVE" else "ERROR")
                }
                if (handoffProtective) {
                    governanceDao?.insertEvent(GovernanceEventEntity(
                        eventType = "handoff_protective_exit_failure", symbol = symbol, strategy = handoffDirective?.strategyId ?: settings.strategyMode.name,
                        mode = settings.mode.name, severity = "CRITICAL", blocked = true, sizeMultiplier = 0.0,
                        reason = "UNPROTECTED_POSITION: Protective handoff ${handoffIntent} failed to execute: ${error.message}. New entries must enter safe mode until the operational error is cleared."
                    ))
                    governanceDao?.putState(ProductionIntelligenceStateEntity(
                        key = "UNPROTECTED_POSITION:${symbol.uppercase()}",
                        value = "strategy=${handoffDirective?.strategyId ?: settings.strategyMode.name}; intent=$handoffIntent; error=${error.message}; timestamp=${System.currentTimeMillis()}"
                    ))
                }
            }
            .getOrNull()
        return out
    }

    suspend fun refreshPositionRows(settings: BotSettings, exchange: CryptoExchangeClient): List<PositionInfo> {
        val positions = buildLivePositions(settings, exchange)
        val now = System.currentTimeMillis()
        val recentLifecycleTrades = dao.recentTradesSnapshot(500)
        positions.forEach { p ->
            val prev = dao.positionForSymbol(p.symbol)
            val latestBuy = recentLifecycleTrades.firstOrNull { it.symbol.equals(p.symbol, ignoreCase = true) && it.side.equals(OrderSide.BUY.name, ignoreCase = true) }
            val reopenedByLatestBuy = latestBuy != null && (prev == null || !prev.status.equals("OPEN", ignoreCase = true) || latestBuy.timestampEpochMs > prev.openedAtEpochMs)
            val highest = if (reopenedByLatestBuy) p.currentPrice else listOf(prev?.highestPriceEur?.toBigDecimalOrNull() ?: BigDecimal.ZERO, p.highestPrice, p.currentPrice).maxOrNull() ?: p.currentPrice
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
                    openedAtEpochMs = if (reopenedByLatestBuy) latestBuy!!.timestampEpochMs else prev?.openedAtEpochMs ?: now,
                    updatedAtEpochMs = now,
                    status = "OPEN",
                    source = prev?.source?.takeIf { it.startsWith("HANDOFF_V1|") } ?: "LIVE_PORTFOLIO"
                )
            )
        }
        runCatching { exchange.getPortfolioBalances() }.onSuccess { balances ->
            val heldBases = balances.filter { it.total > BigDecimal.ZERO }.map { it.asset.uppercase() }.toSet()
            dao.openPositionsSnapshot().filter { row ->
                row.baseAsset.uppercase() !in heldBases && !row.status.equals("PENDING_ENTRY", ignoreCase = true)
            }.forEach { stale ->
                dao.updatePositionStatus(stale.symbol, "CLOSED", System.currentTimeMillis())
                log("[${stale.symbol}] Stale lifecycle position closed after confirmed zero exchange balance for ${stale.baseAsset}.", "INFO")
            }
        }.onFailure { error ->
            log("Lifecycle stale-position reconciliation skipped because portfolio balance verification failed: ${error.message}", "WARN")
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
            val previousEntry = prev?.entryPriceEur?.toBigDecimalOrNull()
            val confirmedBuyEntry = lastBuy?.priceEur?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            val latestBuyIsNewLifecycle = lastBuy != null && (
                prev == null ||
                    (!prev.status.equals("OPEN", ignoreCase = true) && !prev.status.equals("PENDING_ENTRY", ignoreCase = true)) ||
                    lastBuy.timestampEpochMs > prev.openedAtEpochMs
            )
            val entry = when {
                prev?.status.equals("PENDING_ENTRY", true) && confirmedBuyEntry != null -> confirmedBuyEntry
                latestBuyIsNewLifecycle && confirmedBuyEntry != null -> confirmedBuyEntry
                previousEntry != null && previousEntry > BigDecimal.ZERO -> previousEntry
                confirmedBuyEntry != null -> confirmedBuyEntry
                else -> current
            }
            val highest = listOf(prev?.highestPriceEur?.toBigDecimalOrNull() ?: current, current).maxOrNull() ?: current
            val pnl = current.subtract(entry).multiply(asset.total)
            val pnlPct = if (entry > BigDecimal.ZERO) current.subtract(entry).divide(entry, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal.ZERO
            val persistedPlan = HandoffPositionPlanCodec.decode(prev?.source)
            val defaultStop = entry.multiply(BigDecimal.ONE.subtract(settings.stopLossPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
            val defaultTake = entry.multiply(BigDecimal.ONE.add(settings.takeProfitPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
            val stop = persistedPlan?.stopPrice?.takeIf { it > BigDecimal.ZERO && it < entry }
                ?: prev?.stopPriceEur?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO && it < entry }
                ?: defaultStop
            val take = if (persistedPlan != null) persistedPlan.remainingTargets.firstOrNull() ?: BigDecimal.ZERO
                else prev?.takeProfitPriceEur?.toBigDecimalOrNull()?.takeIf { it > entry } ?: defaultTake
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

    private suspend fun syncClosedOrders(settings: BotSettings, exchange: CryptoExchangeClient) {
        val closed = runCatching { exchange.getClosedOrders(50) }.getOrDefault(emptyList())
        closed.take(50).forEach { order ->
            val pendingPosition = dao.positionForSymbol(order.symbol)
            val pendingPlan = HandoffPositionPlanCodec.decode(pendingPosition?.source)
            if (pendingPosition != null && pendingPlan != null && pendingPlan.entryOrderId == order.exchangeOrderId) {
                if (order.side == OrderSide.BUY && order.executedQuantity > BigDecimal.ZERO && order.price > BigDecimal.ZERO) {
                    val stop = pendingPlan.stopPrice?.takeIf { it > BigDecimal.ZERO && it < order.price }
                        ?: pendingPosition.stopPriceEur.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO && it < order.price }
                        ?: BigDecimal.ZERO
                    val nextTarget = pendingPlan.remainingTargets.firstOrNull { it > order.price } ?: BigDecimal.ZERO
                    dao.upsertPosition(pendingPosition.copy(
                        quantity = order.executedQuantity.toPlainString(), entryPriceEur = order.price.toPlainString(), highestPriceEur = order.price.toPlainString(),
                        stopPriceEur = stop.toPlainString(), takeProfitPriceEur = nextTarget.toPlainString(), status = "OPEN", updatedAtEpochMs = System.currentTimeMillis()
                    ))
                    log("Pending handoff entry reconciled from Kraken fill: ${order.symbol} qty=${order.executedQuantity} price=${order.price} strategy=${pendingPlan.strategyId}", "LIVE")
                    val protection = protectiveStops?.protectOrFlatten(
                        settings = settings, exchange = exchange, symbol = order.symbol,
                        quantity = order.executedQuantity, entryPrice = order.price,
                        stopPrice = stop, strategyId = pendingPlan.strategyId, paper = false
                    )
                    if (protection != null) log("Deferred handoff fill protection: protected=${protection.protected}, flattened=${protection.flattened}, pendingEmergency=${protection.pendingEmergencyExit}. ${protection.reason}", if (protection.protected) "LIVE" else "ERROR")
                } else if (order.executedQuantity <= BigDecimal.ZERO) {
                    dao.updatePositionStatus(order.symbol, "ENTRY_CANCELLED", System.currentTimeMillis())
                    log("Pending handoff entry closed without fill: ${order.symbol} order=${order.exchangeOrderId} status=${order.status}.", "INFO")
                }
            }
            if (order.executedQuantity <= BigDecimal.ZERO) return@forEach
            val syncedStrategyId = pendingPlan?.strategyId ?: HandoffPositionPlanCodec.decode(pendingPosition?.source)?.strategyId ?: "KRAKEN_SYNC"
            val syncedEntry = pendingPosition?.entryPriceEur?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val syncedRealized = if (order.side == OrderSide.SELL && syncedEntry > BigDecimal.ZERO && order.price > BigDecimal.ZERO) order.price.subtract(syncedEntry).multiply(order.executedQuantity).subtract(order.fee) else BigDecimal.ZERO
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
                        realizedPnlEur = syncedRealized.toPlainString(),
                        aiScore = 0,
                        aiReason = "Synced Kraken fill [$syncedStrategyId]: ${order.description}",
                        clientOrderId = "kraken-sync-${order.exchangeOrderId}",
                        exchangeOrderId = order.exchangeOrderId,
                        timestampEpochMs = order.closedAtEpochSeconds * 1000L
                    )
                )
            }
        }
        if (closed.isNotEmpty()) log("Closed-order sync complete. Synced/checked ${closed.size} Kraken closed order(s).", "INFO")
    }

    private suspend fun recordTradeFromLifecycle(symbol: String, side: OrderSide, quantity: BigDecimal, price: BigDecimal, fee: BigDecimal, realized: BigDecimal, paper: Boolean, orderId: String, reason: String, strategyId: String) {
        dao.insertTrade(
            TradeEntity(
                symbol = symbol,
                side = side.name,
                quantity = quantity.toPlainString(),
                priceEur = price.toPlainString(),
                feeEur = fee.toPlainString(),
                paper = paper,
                realizedPnlEur = realized.toPlainString(),
                aiReason = "Lifecycle exit [$strategyId]: $reason",
                clientOrderId = "lifecycle-${System.currentTimeMillis()}",
                exchangeOrderId = orderId,
                timestampEpochMs = System.currentTimeMillis()
            )
        )
        if (!paper) {
            dao.insertTaxReportRow(
                TaxReportEntity(
                    timestampEpochMs = System.currentTimeMillis(),
                    symbol = symbol,
                    side = side.name,
                    quantity = quantity.toPlainString(),
                    priceEur = price.toPlainString(),
                    feeEur = fee.toPlainString(),
                    realizedGainEur = realized.toPlainString(),
                    note = "Lifecycle managed exit [$strategyId]: $reason. Exchange fill fee and realized P/L recorded from confirmed execution evidence."
                )
            )
        }
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
