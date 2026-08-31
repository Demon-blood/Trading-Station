package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.*
import com.ksp.cryptobot.exchange.CryptoExchangeClient
import com.ksp.cryptobot.exchange.TradingFeeSchedule
import com.ksp.cryptobot.governance.ProductionIntelligenceRuntime
import com.ksp.cryptobot.intelligence.CloudAiRuntime
import com.ksp.cryptobot.intelligence.AiValueAttributionEngine
import com.ksp.cryptobot.research.HandoffSideIntent
import com.ksp.cryptobot.research.ResearchExecutionRuntime
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

class AdvancedExecutionCoordinator(
    private val appDao: AppDao,
    private val governanceDao: GovernanceDao
) {
    private val capitalProtection = CapitalProtectionEngine()
    private val portfolioAllocation = PortfolioAllocationEngine()
    private val portfolioCorrelation = PortfolioCorrelationEngine()
    private val liquiditySizer = LiquidityAwareSizer()
    private val orderTypeOptimizer = OrderTypeOptimizer()
    private val tradeEconomics = TradeEconomicsEngine()
    private val aiValueAttribution = AiValueAttributionEngine(governanceDao)

    suspend fun prepareEntry(
        settings: BotSettings,
        ticker: MarketTicker,
        decision: AiDecision,
        requestedQuote: BigDecimal,
        orderBook: OrderBookSnapshot?,
        mode: String,
        currentUseMarket: Boolean,
        feeSchedule: TradingFeeSchedule? = null,
        exchange: CryptoExchangeClient? = null
    ): AdvancedEntryPlan {
        if (requestedQuote <= BigDecimal.ZERO) return AdvancedEntryPlan(false, BigDecimal.ZERO, OrderType.LIMIT, ticker.ask, BigDecimal.ZERO, 0, "requested quote is zero")
        val directive = ResearchExecutionRuntime.snapshot(decision.symbol)
        if (directive != null && (
                !directive.allowedEntry ||
                directive.sideIntent in setOf(HandoffSideIntent.EXIT, HandoffSideIntent.REDUCE, HandoffSideIntent.AVOID, HandoffSideIntent.BLOCKED_SOURCE_UNKNOWN) ||
                !directive.costGatePassed || !directive.riskGatePassed
            )) {
            val reason = "research handoff hard-blocked new entry: side=${directive.sideIntent}, allowed=${directive.allowedEntry}, cost=${directive.costGatePassed}, risk=${directive.riskGatePassed}, strategy=${directive.strategyId}. ${directive.reason}"
            record("research_execution_cap", decision.symbol, settings, mode, requestedQuote, BigDecimal.ZERO, BigDecimal.ZERO, directive.preferredOrderType?.name ?: "", "handoff_block", sizeBand(requestedQuote), "", "blocked", true, reason, "HIGH")
            return AdvancedEntryPlan(false, BigDecimal.ZERO, OrderType.LIMIT, ticker.ask, BigDecimal.ZERO, 0, reason)
        }
        var researchCappedQuote = requestedQuote
        if (directive != null) {
            val researchMultiplier = BigDecimal.valueOf(directive.sizeMultiplier.coerceIn(0.0, 1.0))
            researchCappedQuote = researchCappedQuote.min(requestedQuote.multiply(researchMultiplier))
            directive.maxNotionalQuote?.takeIf { it > BigDecimal.ZERO }?.let { researchCappedQuote = researchCappedQuote.min(it) }
            researchCappedQuote = researchCappedQuote.setScale(2, RoundingMode.DOWN)
            val mult = if (requestedQuote > BigDecimal.ZERO) researchCappedQuote.divide(requestedQuote, 8, RoundingMode.HALF_UP).coerceIn(BigDecimal.ZERO, BigDecimal.ONE) else BigDecimal.ZERO
            record("research_execution_cap", decision.symbol, settings, mode, requestedQuote, researchCappedQuote, mult, directive.preferredOrderType?.name ?: "", "handoff_cap", sizeBand(requestedQuote), "", "normal", false, "strategy=${directive.strategyId}; truth=${directive.liveTruthGate}; ${directive.reason}", if (researchCappedQuote < requestedQuote) "WARN" else "INFO")
            if (researchCappedQuote < BigDecimal("5.00")) {
                val reason = "research handoff cap ${researchCappedQuote.s2()} is below practical €5 order minimum; never raise size to satisfy exchange minimum"
                return AdvancedEntryPlan(false, researchCappedQuote, OrderType.LIMIT, ticker.ask, mult, 0, reason)
            }
        }
        val trades = appDao.recentTradesSnapshot(500)
        val positions = appDao.openPositionsSnapshot()

        var portfolioCorrelationContext: PortfolioCorrelationContext? = null
        if (settings.portfolioBalancerEnabled && exchange != null) {
            val portfolioContextResult = runCatching {
                portfolioCorrelation.assess(
                    settings = settings,
                    exchange = exchange,
                    candidateSymbol = decision.symbol,
                    requestedQuote = researchCappedQuote,
                    positions = positions
                )
            }
            if (portfolioContextResult.isFailure) {
                val error = portfolioContextResult.exceptionOrNull()
                val reason = "M17 portfolio context unavailable: ${error?.message ?: error?.javaClass?.simpleName ?: "unknown"}. LIVE entry fails closed because account-level reserve/exposure state is not authoritative."
                record("portfolio_correlation", decision.symbol, settings, mode, requestedQuote, BigDecimal.ZERO, BigDecimal.ZERO,
                    "", "context_unavailable", sizeBand(requestedQuote), "", "blocked", settings.mode != BotMode.PAPER, reason,
                    if (settings.mode == BotMode.PAPER) "WARN" else "HIGH")
                if (settings.mode != BotMode.PAPER) {
                    return AdvancedEntryPlan(false, BigDecimal.ZERO, OrderType.LIMIT, ticker.ask, BigDecimal.ZERO, 0, reason)
                }
            } else {
                portfolioCorrelationContext = portfolioContextResult.getOrNull()
                portfolioCorrelationContext?.let { context ->
                    record("portfolio_correlation", decision.symbol, settings, mode, requestedQuote, requestedQuote,
                        context.correlationMultiplier, "", "correlation_context", sizeBand(requestedQuote), "",
                        "normal", false, context.reason, "INFO")
                }
            }
        }

        val protection = capitalProtection.evaluate(settings, trades, mode)
        record("capital_protection", decision.symbol, settings, mode, requestedQuote, requestedQuote.multiply(protection.sizeMultiplier), protection.sizeMultiplier,
            "", "capital_protection", sizeBand(requestedQuote), "", if (protection.allowed) "normal" else "blocked", !protection.allowed, protection.reason, if (protection.allowed) "INFO" else "HIGH")
        if (!protection.allowed) return AdvancedEntryPlan(false, BigDecimal.ZERO, OrderType.LIMIT, ticker.ask, BigDecimal.ZERO, protection.level, protection.reason)

        val productionMultiplier = BigDecimal.valueOf(ProductionIntelligenceRuntime.snapshot().sizeMultiplier.coerceIn(0.0, 1.0))
        val afterProduction = researchCappedQuote.multiply(productionMultiplier).multiply(protection.sizeMultiplier).setScale(2, RoundingMode.DOWN)
        val allocation = portfolioAllocation.allocate(
            settings = settings,
            decision = decision,
            requestedQuote = afterProduction,
            recentTrades = trades,
            positions = positions,
            correlation = portfolioCorrelationContext
        )
        record("portfolio_allocation", decision.symbol, settings, mode, afterProduction, allocation.finalQuote, allocation.multiplier,
            "", "portfolio", sizeBand(afterProduction), "", if (allocation.allowed) "normal" else "blocked", !allocation.allowed, allocation.reason, if (allocation.allowed) "INFO" else "HIGH")
        if (!allocation.allowed) return AdvancedEntryPlan(false, BigDecimal.ZERO, OrderType.LIMIT, ticker.ask, BigDecimal.ZERO, protection.level, allocation.reason)

        val liquidity = liquiditySizer.size(settings, allocation.finalQuote, ticker, orderBook)
        record("liquidity_sizing", decision.symbol, settings, mode, allocation.finalQuote, liquidity.finalQuote, liquidity.multiplier,
            "", liquidity.reasonCategory, liquidity.requestedSizeBand, "", "normal", false, liquidity.reason, if (liquidity.finalQuote < allocation.finalQuote) "WARN" else "INFO")

        var finalQuote = liquidity.finalQuote
        val deterministicQuoteBeforeCloud = finalQuote
        val cloudReview = CloudAiRuntime.snapshotFor(decision)
        if (cloudReview != null) {
            val cloudMultiplier = cloudReview.riskMultiplier.coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
            val beforeCloud = finalQuote
            finalQuote = finalQuote.multiply(cloudMultiplier).setScale(2, RoundingMode.DOWN)
            if (cloudMultiplier < BigDecimal.ONE || cloudReview.totalCostQuote > BigDecimal.ZERO) {
                record(
                    "cloud_ai_cap",
                    decision.symbol,
                    settings,
                    mode,
                    beforeCloud,
                    finalQuote,
                    cloudMultiplier,
                    "",
                    cloudReview.modelPath,
                    sizeBand(beforeCloud),
                    "",
                    if (finalQuote < beforeCloud) "reduced" else "normal",
                    false,
                    "Selective cloud AI ${cloudReview.verdict}; risk×${cloudMultiplier}; API cost reserve=${cloudReview.totalCostQuote}; ${cloudReview.reason}",
                    if (finalQuote < beforeCloud) "WARN" else "INFO"
                )
            }
        }
        // Economic minimum from desktop v1.0.50, made fail-safe for Android:
        // never raise a size after governance/liquidity reduced it. If a normal-size live
        // request is pushed below the economic floor, skip instead of undoing the safety reduction.
        val economicFloor = BigDecimal("12.00")
        if (directive?.maxNotionalQuote == null && mode.equals("LIVE", true) && requestedQuote >= economicFloor && finalQuote >= BigDecimal("5.00") && finalQuote < economicFloor) {
            val reason = "advanced execution blocked: fee-efficient €12 floor is not safely reachable after production/portfolio/liquidity sizing; calculated=${finalQuote.s2()}"
            record("entry_plan", decision.symbol, settings, mode, requestedQuote, finalQuote, finalQuote.divide(requestedQuote, 6, RoundingMode.HALF_UP), "", "fee_floor_unreachable", sizeBand(requestedQuote), "", "blocked", true, reason, "WARN")
            return AdvancedEntryPlan(false, finalQuote, OrderType.LIMIT, ticker.ask, BigDecimal.ZERO, protection.level, reason)
        }
        if (finalQuote < BigDecimal("5.00")) {
            val reason = "advanced execution blocked: final quote ${finalQuote.s2()} below practical €5 minimum after production/portfolio/liquidity sizing"
            record("entry_plan", decision.symbol, settings, mode, requestedQuote, finalQuote, BigDecimal.ZERO, "", "below_minimum", sizeBand(requestedQuote), "", "blocked", true, reason, "WARN")
            return AdvancedEntryPlan(false, finalQuote, OrderType.LIMIT, ticker.ask, BigDecimal.ZERO, protection.level, reason)
        }
        val optimizedOrder = orderTypeOptimizer.suggest(settings, ticker, orderBook, finalQuote, currentUseMarket)
        var finalOrderType = optimizedOrder.orderType
        var finalLimitOrTrigger = optimizedOrder.limitPrice
        if (directive?.preferredOrderType != null) {
            val sourceType = directive.preferredOrderType
            if (sourceType == OrderType.MARKET) {
                if (mode.equals("PAPER", true)) {
                    finalOrderType = OrderType.MARKET
                    finalLimitOrTrigger = null
                } else if (settings.enableMarketOrders) {
                    finalOrderType = OrderType.MARKET
                    finalLimitOrTrigger = null
                } else {
                    val reason = "handoff source requires MARKET execution for ${directive.strategyId}, but live market orders are disabled. Fidelity rule: block rather than silently substitute LIMIT."
                    record("order_type", decision.symbol, settings, mode, requestedQuote, finalQuote, BigDecimal.ZERO, sourceType.name, "source_market_disabled", sizeBand(requestedQuote), "", "blocked", true, reason, "WARN")
                    return AdvancedEntryPlan(false, finalQuote, sourceType, null, BigDecimal.ZERO, protection.level, reason)
                }
            } else {
                finalOrderType = sourceType
                finalLimitOrTrigger = directive.preferredLimitOrTriggerPrice ?: optimizedOrder.limitPrice ?: ticker.ask
            }
        }
        if (finalOrderType == OrderType.MARKET) finalQuote = finalQuote.min(settings.maxMarketOrderEur)

        val finalPostOnly = finalOrderType == OrderType.LIMIT && when {
            directive?.preferredOrderType != null -> directive.postOnlyPreferred == true
            else -> optimizedOrder.postOnly
        }
        val entryReference = (finalLimitOrTrigger ?: ticker.ask).takeIf { it > BigDecimal.ZERO } ?: ticker.lastPrice
        val targetPrice = directive?.targets
            ?.firstOrNull { it > entryReference }
            ?: entryReference.multiply(
                BigDecimal.ONE.add(settings.takeProfitPercent.divide(BigDecimal("100"), 12, RoundingMode.HALF_UP))
            )
        val stopPrice = directive?.stopPrice
            ?.takeIf { it > BigDecimal.ZERO && it < entryReference }
            ?: entryReference.multiply(
                BigDecimal.ONE.subtract(settings.stopLossPercent.divide(BigDecimal("100"), 12, RoundingMode.HALF_UP))
            )

        val directiveFeeSchedule = directive?.let { d ->
            val maker = d.makerFeeRate
            val taker = d.takerFeeRate
            if (maker != null && taker != null) {
                TradingFeeSchedule(
                    makerRate = maker,
                    takerRate = taker,
                    source = d.feeSource.ifBlank { "RESEARCH_HANDOFF" }
                )
            } else null
        }
        val economics = tradeEconomics.evaluate(
            TradeEconomicsInput(
                symbol = decision.symbol,
                strategyId = directive?.strategyId
                    ?.takeUnless { it.equals("HANDOFF_FILTERS", ignoreCase = true) }
                    ?: settings.strategyMode.name,
                notionalQuote = finalQuote,
                entryPrice = entryReference,
                targetPrice = targetPrice,
                stopPrice = stopPrice,
                orderType = finalOrderType,
                postOnly = finalPostOnly,
                ticker = ticker,
                orderBook = orderBook,
                recentTrades = trades,
                feeSchedule = feeSchedule ?: directiveFeeSchedule,
                publishRuntime = true,
                externalDecisionCostQuote = cloudReview?.totalCostQuote ?: BigDecimal.ZERO,
                safetyMarginRate = BigDecimal("0.0025")
            )
        )
        if (cloudReview?.lunaUsage != null) {
            runCatching {
                val effectiveCloudMultiplier = cloudReview.riskMultiplier.coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
                val comparableDeterministicQuote = if (effectiveCloudMultiplier > BigDecimal.ZERO) {
                    economics.notionalQuote
                        .divide(effectiveCloudMultiplier, 8, RoundingMode.HALF_UP)
                        .min(deterministicQuoteBeforeCloud)
                } else {
                    deterministicQuoteBeforeCloud
                }
                aiValueAttribution.linkExecutionEconomics(
                    fingerprint = cloudReview.fingerprint,
                    deterministicNotionalQuote = comparableDeterministicQuote,
                    assessment = economics
                )
            }
        }
        record(
            "entry_economics",
            decision.symbol,
            settings,
            mode,
            requestedQuote,
            finalQuote,
            economics.netExpectedValueRate,
            finalOrderType.name,
            if (economics.allowed) "positive_net_ev" else "non_positive_net_ev",
            sizeBand(requestedQuote),
            "",
            if (economics.allowed) "normal" else "blocked",
            !economics.allowed,
            economics.reason,
            if (economics.allowed) "INFO" else "WARN"
        )
        if (!economics.allowed) {
            val reason = "M5 trade economics blocked entry: ${economics.reason}"
            return AdvancedEntryPlan(false, finalQuote, finalOrderType, finalLimitOrTrigger, BigDecimal.ZERO, protection.level, reason)
        }

        val combined = finalQuote.divide(requestedQuote, 6, RoundingMode.HALF_UP).coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
        record("order_type", decision.symbol, settings, mode, requestedQuote, finalQuote, combined,
            finalOrderType.name, if (directive?.preferredOrderType != null) "handoff_source_order" else optimizedOrder.reasonCategory, sizeBand(requestedQuote), "", "normal", false, optimizedOrder.reason + " | " + economics.reason, "INFO")
        val postOnly = finalPostOnly
        val handoff = directive?.let { " | handoff=${it.strategyId}/${it.fidelity}/truth=${it.liveTruthGate}/fee=${it.feeSource}/postOnly=$postOnly" }.orEmpty()
        val reason = "advanced entry plan: requested=${requestedQuote.s2()}, researchCap=${researchCappedQuote.s2()}, final=${finalQuote.s2()}, combined×${combined.setScale(3, RoundingMode.HALF_UP)}, protection=${protection.level}, order=$finalOrderType.$handoff ${allocation.reason} | ${liquidity.reason} | ${optimizedOrder.reason} | ${economics.reason}"
        record("entry_plan", decision.symbol, settings, mode, requestedQuote, finalQuote, combined,
            finalOrderType.name, "final_plan", sizeBand(requestedQuote), "", "normal", false, reason, "INFO")
        return AdvancedEntryPlan(true, finalQuote, finalOrderType, finalLimitOrTrigger, combined, protection.level, reason, postOnly = postOnly)
    }

    suspend fun reconcileLive(settings: BotSettings, exchange: CryptoExchangeClient): ReconciliationSummary {
        if (settings.mode != BotMode.LIVE_AUTO && settings.mode != BotMode.LIVE_CONFIRM) return ReconciliationSummary(0, 0, 0, emptyList())
        val balances = ExecutionTruthGate.requireAuthoritative(
            "portfolio balances",
            runCatching { exchange.getPortfolioBalances() }
        )
        val openOrders = ExecutionTruthGate.requireAuthoritative(
            "open orders",
            runCatching { exchange.getOpenOrders() }
        )
        val byAsset = balances.associateBy { normalizeAsset(it.asset) }
        val positions = appDao.openPositionsSnapshot()
        val messages = mutableListOf<String>()
        var adjusted = 0
        var removed = 0
        for (position in positions) {
            val asset = normalizeAsset(position.baseAsset)
            val actual = byAsset[asset]?.total ?: BigDecimal.ZERO
            val local = position.quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO
            if (actual <= BigDecimal.ZERO) {
                appDao.updatePositionStatus(position.symbol, "RECONCILED_ZERO", System.currentTimeMillis())
                removed++
                messages += "Removed local ${position.symbol}: exchange base balance is zero."
            } else if (local > BigDecimal.ZERO) {
                val diff = actual.subtract(local).abs().divide(local, 8, RoundingMode.HALF_UP)
                if (diff > BigDecimal("0.02")) {
                    appDao.upsertPosition(position.copy(quantity = actual.toPlainString(), updatedAtEpochMs = System.currentTimeMillis(), source = "LIVE_RECONCILED"))
                    adjusted++
                    messages += "Adjusted ${position.symbol} quantity from ${local.stripTrailingZeros().toPlainString()} to ${actual.stripTrailingZeros().toPlainString()}."
                }
            }
        }
        if (messages.isEmpty()) messages += "Reconciliation OK: ${positions.size} local open positions match exchange balances within 2%."
        messages += "Open exchange orders observed: ${openOrders.size}."
        val severity = if (removed > 0) "HIGH" else if (adjusted > 0) "WARN" else "INFO"
        record("reconciliation", "", settings, "LIVE", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE,
            "", if (removed > 0) "position_removed" else if (adjusted > 0) "quantity_adjusted" else "matched", "n/a", "", "normal", false,
            "adjusted=$adjusted removed=$removed openOrders=${openOrders.size}; ${messages.joinToString(" | ").take(2400)}", severity)
        return ReconciliationSummary(adjusted, removed, openOrders.size, messages)
    }

    suspend fun diagnostics(limit: Int = 100): List<AdvancedExecutionEventEntity> = governanceDao.recentAdvancedExecution(limit)

    private suspend fun record(eventType: String, symbol: String, settings: BotSettings, mode: String, requested: BigDecimal, final: BigDecimal, multiplier: BigDecimal,
                               orderType: String, category: String, band: String, exitMethod: String, qualityTier: String, blocked: Boolean, reason: String, severity: String) {
        governanceDao.insertAdvancedExecution(AdvancedExecutionEventEntity(
            eventType = eventType, symbol = symbol, strategy = settings.strategyMode.name, mode = mode, side = if (eventType == "exit_optimization") "SELL" else "BUY",
            severity = severity, requestedQuote = requested.toDouble(), finalQuote = final.toDouble(), multiplier = multiplier.toDouble(), recommendedOrderType = orderType,
            reasonCategory = category, requestedSizeBand = band, exitMethod = exitMethod, qualityTier = qualityTier, blocked = blocked, reason = reason.take(3000)
        ))
    }
    private fun normalizeAsset(asset: String): String = asset.uppercase().removePrefix("X").removePrefix("Z").let { if (it == "XBT") "BTC" else it }
    private fun sizeBand(v: BigDecimal): String = when { v < BigDecimal("10") -> "micro"; v < BigDecimal("25") -> "small"; v < BigDecimal("100") -> "medium"; else -> "large" }
    private fun BigDecimal.s2(): String = setScale(2, RoundingMode.DOWN).toPlainString()
    private fun BigDecimal.coerceIn(min: BigDecimal, max: BigDecimal): BigDecimal = when { this < min -> min; this > max -> max; else -> this }
}
