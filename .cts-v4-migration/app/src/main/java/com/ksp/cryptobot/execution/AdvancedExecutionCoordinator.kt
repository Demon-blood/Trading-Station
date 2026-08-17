package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.*
import com.ksp.cryptobot.exchange.CryptoExchangeClient
import com.ksp.cryptobot.governance.ProductionIntelligenceRuntime
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

class AdvancedExecutionCoordinator(
    private val appDao: AppDao,
    private val governanceDao: GovernanceDao
) {
    private val capitalProtection = CapitalProtectionEngine()
    private val portfolioAllocation = PortfolioAllocationEngine()
    private val liquiditySizer = LiquidityAwareSizer()
    private val orderTypeOptimizer = OrderTypeOptimizer()

    suspend fun prepareEntry(
        settings: BotSettings,
        ticker: MarketTicker,
        decision: AiDecision,
        requestedQuote: BigDecimal,
        orderBook: OrderBookSnapshot?,
        mode: String,
        currentUseMarket: Boolean
    ): AdvancedEntryPlan {
        if (requestedQuote <= BigDecimal.ZERO) return AdvancedEntryPlan(false, BigDecimal.ZERO, OrderType.LIMIT, ticker.ask, BigDecimal.ZERO, 0, "requested quote is zero")
        val trades = appDao.recentTradesSnapshot(500)
        val positions = appDao.openPositionsSnapshot()
        val protection = capitalProtection.evaluate(settings, trades, mode)
        record("capital_protection", decision.symbol, settings, mode, requestedQuote, requestedQuote.multiply(protection.sizeMultiplier), protection.sizeMultiplier,
            "", "capital_protection", sizeBand(requestedQuote), "", if (protection.allowed) "normal" else "blocked", !protection.allowed, protection.reason, if (protection.allowed) "INFO" else "HIGH")
        if (!protection.allowed) return AdvancedEntryPlan(false, BigDecimal.ZERO, OrderType.LIMIT, ticker.ask, BigDecimal.ZERO, protection.level, protection.reason)

        val productionMultiplier = BigDecimal.valueOf(ProductionIntelligenceRuntime.snapshot().sizeMultiplier.coerceIn(0.0, 1.0))
        val afterProduction = requestedQuote.multiply(productionMultiplier).multiply(protection.sizeMultiplier).setScale(2, RoundingMode.DOWN)
        val allocation = portfolioAllocation.allocate(settings, decision, afterProduction, trades, positions)
        record("portfolio_allocation", decision.symbol, settings, mode, afterProduction, allocation.finalQuote, allocation.multiplier,
            "", "portfolio", sizeBand(afterProduction), "", if (allocation.allowed) "normal" else "blocked", !allocation.allowed, allocation.reason, if (allocation.allowed) "INFO" else "HIGH")
        if (!allocation.allowed) return AdvancedEntryPlan(false, BigDecimal.ZERO, OrderType.LIMIT, ticker.ask, BigDecimal.ZERO, protection.level, allocation.reason)

        val liquidity = liquiditySizer.size(settings, allocation.finalQuote, ticker, orderBook)
        record("liquidity_sizing", decision.symbol, settings, mode, allocation.finalQuote, liquidity.finalQuote, liquidity.multiplier,
            "", liquidity.reasonCategory, liquidity.requestedSizeBand, "", "normal", false, liquidity.reason, if (liquidity.finalQuote < allocation.finalQuote) "WARN" else "INFO")

        var finalQuote = liquidity.finalQuote
        // Economic minimum from desktop v1.0.50, made fail-safe for Android:
        // never raise a size after governance/liquidity reduced it. If a normal-size live
        // request is pushed below the economic floor, skip instead of undoing the safety reduction.
        val economicFloor = BigDecimal("12.00")
        if (mode.equals("LIVE", true) && requestedQuote >= economicFloor && finalQuote >= BigDecimal("5.00") && finalQuote < economicFloor) {
            val reason = "advanced execution blocked: fee-efficient €12 floor is not safely reachable after production/portfolio/liquidity sizing; calculated=${finalQuote.s2()}"
            record("entry_plan", decision.symbol, settings, mode, requestedQuote, finalQuote, finalQuote.divide(requestedQuote, 6, RoundingMode.HALF_UP), "", "fee_floor_unreachable", sizeBand(requestedQuote), "", "blocked", true, reason, "WARN")
            return AdvancedEntryPlan(false, finalQuote, OrderType.LIMIT, ticker.ask, BigDecimal.ZERO, protection.level, reason)
        }
        if (finalQuote < BigDecimal("5.00")) {
            val reason = "advanced execution blocked: final quote ${finalQuote.s2()} below practical €5 minimum after production/portfolio/liquidity sizing"
            record("entry_plan", decision.symbol, settings, mode, requestedQuote, finalQuote, BigDecimal.ZERO, "", "below_minimum", sizeBand(requestedQuote), "", "blocked", true, reason, "WARN")
            return AdvancedEntryPlan(false, finalQuote, OrderType.LIMIT, ticker.ask, BigDecimal.ZERO, protection.level, reason)
        }
        val order = orderTypeOptimizer.suggest(settings, ticker, orderBook, finalQuote, currentUseMarket)
        if (order.orderType == OrderType.MARKET) finalQuote = finalQuote.min(settings.maxMarketOrderEur)
        val combined = finalQuote.divide(requestedQuote, 6, RoundingMode.HALF_UP).coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
        record("order_type", decision.symbol, settings, mode, requestedQuote, finalQuote, combined,
            order.orderType.name, order.reasonCategory, sizeBand(requestedQuote), "", "normal", false, order.reason, "INFO")
        val reason = "advanced entry plan: requested=${requestedQuote.s2()}, final=${finalQuote.s2()}, combined×${combined.setScale(3, RoundingMode.HALF_UP)}, protection=${protection.level}, order=${order.orderType}. ${allocation.reason} | ${liquidity.reason} | ${order.reason}"
        record("entry_plan", decision.symbol, settings, mode, requestedQuote, finalQuote, combined,
            order.orderType.name, "final_plan", sizeBand(requestedQuote), "", "normal", false, reason, "INFO")
        return AdvancedEntryPlan(true, finalQuote, order.orderType, order.limitPrice, combined, protection.level, reason)
    }

    suspend fun reconcileLive(settings: BotSettings, exchange: CryptoExchangeClient): ReconciliationSummary {
        if (settings.mode != BotMode.LIVE_AUTO && settings.mode != BotMode.LIVE_CONFIRM) return ReconciliationSummary(0, 0, 0, emptyList())
        val balances = runCatching { exchange.getPortfolioBalances() }.getOrElse { emptyList() }
        val openOrders = runCatching { exchange.getOpenOrders() }.getOrElse { emptyList() }
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
