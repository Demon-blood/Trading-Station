package com.ksp.cryptobot.governance

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

internal fun entryOnlyGovernorsBlock(
    action: SignalAction,
    killAllowed: Boolean,
    riskBlocked: Boolean,
    liveSafeBlocked: Boolean
): Boolean {
    val isEntry = action in setOf(SignalAction.BUY, SignalAction.SMALL_BUY)
    return isEntry && (!killAllowed || riskBlocked || liveSafeBlocked)
}

class ProductionIntelligenceEngine(private val dao: GovernanceDao) {
    private val anomalyFirewall = AnomalyFirewall()
    private val counterfactual = CounterfactualLearningEngine()
    private val killSwitch = KillSwitchEngine()
    private val riskBudget = RiskBudgetManager()
    private val safeMode = SafeModeController()
    private val executionQuality = ExecutionQualityLearner()

    suspend fun evaluateDecision(
        decision: AiDecision,
        ticker: MarketTicker,
        candles: List<Candle>,
        recentTrades: List<TradeEntity>,
        settings: BotSettings
    ): Pair<AiDecision, ProductionDecisionAssessment> {
        val now = System.currentTimeMillis()
        val mode = if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) "PAPER" else "LIVE"
        val side = if (decision.finalAction == SignalAction.SELL) "SELL" else "BUY"
        val anomaly = anomalyFirewall.evaluate(settings, ticker, candles)
        if (!anomaly.allowed) {
            dao.insertEvent(GovernanceEventEntity(
                eventType = "anomaly_event", symbol = decision.symbol, strategy = settings.strategyMode.name,
                mode = mode, severity = anomaly.severity, blocked = true, reason = anomaly.reason
            ))
        }

        val start = LocalDate.now(ZoneId.systemDefault()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val realizedToday = recentTrades.asSequence()
            .filter { it.timestampEpochMs >= start && it.side.equals("SELL", ignoreCase = true) }
            .sumOf { it.realizedPnlEur.toDoubleOrNull() ?: 0.0 }
        val recentEvents = dao.recentEvents(100)
        val operationalErrors = dao.recentOperationalErrorCount(now - 60L * 60L * 1000L)
        val safe = safeMode.evaluate(settings, recentEvents, realizedToday, anomaly)
        val kill = killSwitch.evaluate(settings, decision, recentTrades, realizedToday, operationalErrors)
        val risk = riskBudget.evaluate(settings, recentTrades)
        val qualityRows = dao.executionQuality(decision.symbol, side, mode, 100)
        val quality = executionQuality.assess(qualityRows)
        val (counterAdj, counterReason) = counterfactual.evaluate(decision, candles)

        val adjustment = (safe.scoreAdjustment + quality.scoreAdjustment + counterAdj).coerceIn(-12, 6)
        val liveMode = settings.mode == BotMode.LIVE_AUTO || settings.mode == BotMode.LIVE_CONFIRM
        val entryGovernanceBlocked = entryOnlyGovernorsBlock(
            decision.finalAction,
            killAllowed = kill.allowed,
            riskBlocked = risk.blocked,
            liveSafeBlocked = liveMode && safe.blockLiveEntries
        )
        val blocked = !anomaly.allowed || entryGovernanceBlocked
        val sizeMultiplier = (safe.sizeMultiplier * risk.multiplier).coerceIn(0.0, 1.0)
        val finalScore = (decision.finalScore + adjustment).coerceIn(0, 100)
        val adjustedAction = if (blocked && decision.finalAction in setOf(SignalAction.BUY, SignalAction.SMALL_BUY)) SignalAction.WAIT else decision.finalAction
        val adjusted = decision.copy(
            finalAction = adjustedAction,
            finalScore = finalScore,
            confidencePercent = minOf(decision.confidencePercent, finalScore),
            allowedToTrade = decision.allowedToTrade && !blocked,
            explanation = decision.explanation + " | Production: adj=$adjustment, safe=${safe.level}, anomaly=${anomaly.severity}, kill=${kill.severity}, risk×${"%.2f".format(risk.multiplier)}, execution=${quality.scoreAdjustment}, counterfactual=$counterAdj."
        )
        val severity = when {
            blocked -> listOf(anomaly.severity, kill.severity).firstOrNull { it == "CRITICAL" } ?: "HIGH"
            adjustment <= -5 -> "WARN"
            else -> "INFO"
        }
        val reason = listOf(
            anomaly.reason, safe.reason, kill.reason, risk.reason, quality.reason, counterReason
        ).joinToString(" | ")
        val assessment = ProductionDecisionAssessment(
            scoreAdjustment = adjustment,
            blocked = blocked,
            sizeMultiplier = sizeMultiplier,
            severity = severity,
            reason = reason,
            anomaly = anomaly,
            safeMode = safe,
            killSwitch = kill,
            riskBudget = risk,
            executionQuality = quality,
            counterfactualAdjustment = counterAdj,
            counterfactualReason = counterReason
        )
        ProductionIntelligenceRuntime.install(ProductionRuntimeSnapshot(
            safeModeLevel = safe.level,
            blockLiveEntries = liveMode && safe.blockLiveEntries,
            sizeMultiplier = sizeMultiplier,
            lastReason = reason,
            updatedAtEpochMs = now
        ))
        dao.putState(ProductionIntelligenceStateEntity("safe_mode_level", safe.level, now))
        dao.putState(ProductionIntelligenceStateEntity("last_evaluation_reason", reason.take(3000), now))
        dao.insertEvent(GovernanceEventEntity(
            eventType = "production_ai_evaluation",
            symbol = decision.symbol,
            strategy = settings.strategyMode.name,
            mode = mode,
            severity = severity,
            scoreAdjustment = adjustment,
            blocked = blocked,
            sizeMultiplier = sizeMultiplier,
            reason = reason,
            payloadJson = "{\"final_score\":${adjusted.finalScore},\"counterfactual_adjustment\":$counterAdj,\"execution_samples\":${quality.samples},\"risk_remaining_eur\":${risk.remainingEur}}"
        ))
        dao.insertEvent(GovernanceEventEntity(
            eventType = "risk_budget_event", symbol = decision.symbol, strategy = settings.strategyMode.name,
            mode = mode, severity = if (risk.blocked) "HIGH" else "INFO", blocked = risk.blocked,
            sizeMultiplier = risk.multiplier, reason = risk.reason
        ))
        dao.insertEvent(GovernanceEventEntity(
            eventType = "safe_mode_event", symbol = decision.symbol, strategy = settings.strategyMode.name,
            mode = mode, severity = if (safe.blockLiveEntries) "HIGH" else "INFO", blocked = safe.blockLiveEntries,
            scoreAdjustment = safe.scoreAdjustment, sizeMultiplier = safe.sizeMultiplier,
            reason = "${safe.level}: ${safe.reason}"
        ))
        dao.insertEvent(GovernanceEventEntity(
            eventType = "counterfactual_event", symbol = decision.symbol, strategy = settings.strategyMode.name,
            mode = mode, severity = "INFO", scoreAdjustment = counterAdj, reason = counterReason
        ))
        return adjusted to assessment
    }

    suspend fun recordWhyNotTrade(decision: AiDecision, settings: BotSettings, reason: String) {
        dao.insertEvent(GovernanceEventEntity(
            eventType = "why_not_trade",
            symbol = decision.symbol,
            strategy = settings.strategyMode.name,
            mode = if (settings.mode == BotMode.PAPER) "PAPER" else "LIVE",
            severity = "WARN",
            blocked = true,
            reason = reason.take(3000)
        ))
    }

    suspend fun recordOrderError(symbol: String, settings: BotSettings, message: String) {
        dao.insertEvent(GovernanceEventEntity(
            eventType = "order_error", symbol = symbol, strategy = settings.strategyMode.name,
            mode = if (settings.mode == BotMode.PAPER) "PAPER" else "LIVE",
            severity = "HIGH", blocked = true, reason = message.take(3000)
        ))
    }

    suspend fun observeExecution(
        symbol: String,
        side: OrderSide,
        mode: String,
        orderType: OrderType,
        expectedPrice: BigDecimal,
        actualPrice: BigDecimal,
        quantity: BigDecimal,
        clientOrderId: String,
        exchangeOrderId: String
    ) {
        if (expectedPrice <= BigDecimal.ZERO || actualPrice <= BigDecimal.ZERO) return
        val raw = actualPrice.subtract(expectedPrice).divide(expectedPrice, 12, java.math.RoundingMode.HALF_UP).multiply(BigDecimal("100")).toDouble()
        val adverseSlippage = if (side == OrderSide.SELL) -raw else raw
        val row = ExecutionQualityEntity(
            symbol = symbol,
            side = side.name,
            mode = mode,
            orderType = orderType.name,
            expectedPrice = expectedPrice.toDouble(),
            actualPrice = actualPrice.toDouble(),
            slippagePct = adverseSlippage,
            notionalQuote = actualPrice.multiply(quantity).toDouble(),
            clientOrderId = clientOrderId,
            exchangeOrderId = exchangeOrderId
        )
        dao.insertExecutionQuality(row)
        dao.insertEvent(GovernanceEventEntity(
            eventType = "execution_quality_observed", symbol = symbol, mode = mode,
            severity = if (adverseSlippage > 0.25) "WARN" else "INFO",
            reason = "expected=$expectedPrice actual=$actualPrice adverse_slippage=${"%.4f".format(adverseSlippage)}% orderType=${orderType.name}",
            payloadJson = "{\"slippage_pct\":$adverseSlippage,\"notional_quote\":${row.notionalQuote}}"
        ))
    }
}
