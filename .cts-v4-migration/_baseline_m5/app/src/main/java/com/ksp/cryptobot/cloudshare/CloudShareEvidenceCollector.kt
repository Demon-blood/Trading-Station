package com.ksp.cryptobot.cloudshare

import com.ksp.cryptobot.data.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.time.Instant

/**
 * Converts Android evidence into desktop-compatible CloudShare raw events.
 * Deterministic IDs + INSERT IGNORE make repeated recent scans and historical
 * backfill safe to run together.
 */
class CloudShareEvidenceCollector(
    private val dao: CloudShareDao,
    private val perTypeLimit: Int = 250
) {
    private val moshi = Moshi.Builder().build()
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)

    suspend fun collectRecent(): Int {
        var queued = 0
        for (row in dao.recentTradesForCloudShare(perTypeLimit)) queued += queueTrade(row)
        for (row in dao.recentSignalsForCloudShare(perTypeLimit)) queued += queueSignal(row)
        for (row in dao.recentAiDecisionsForCloudShare(perTypeLimit)) queued += queueAiDecision(row)
        for (row in dao.recentNewsForCloudShare(perTypeLimit)) {
            queued += queue("news", row.fetchedAtEpochMs, mapOf(
                "id" to row.id, "symbol" to row.symbol, "title" to row.title,
                "description" to row.description, "source" to row.source,
                "url" to row.url, "provider" to row.provider,
                "published_at_epoch_ms" to row.publishedAtEpochMs,
                "fetched_at_epoch_ms" to row.fetchedAtEpochMs
            ))
        }
        for (row in dao.recentLearningSnapshotsForCloudShare(perTypeLimit)) queued += queueLearningSnapshot(row)
        for (row in dao.recentLearningAuditForCloudShare(perTypeLimit)) {
            queued += queue("learning_events", row.timestampEpochMs, mapOf(
                "event_type" to row.eventType, "id" to row.id, "symbol" to row.symbol,
                "message" to row.message, "timestamp_epoch_ms" to row.timestampEpochMs
            ))
        }
        for (row in dao.learnedSymbolsForCloudShare(perTypeLimit)) {
            queued += queue("learning_events", row.updatedAtEpochMs, mapOf(
                "event_type" to "learned_symbol_profile", "symbol" to row.symbol,
                "sample_size" to row.sampleSize, "wins" to row.wins, "losses" to row.losses,
                "win_rate_percent" to row.winRatePercent, "profit_factor" to row.profitFactor,
                "average_pnl_eur" to row.averagePnlEur, "net_pnl_eur" to row.netPnlEur,
                "score_adjustment" to row.scoreAdjustment, "min_score_adjustment" to row.minScoreAdjustment,
                "position_multiplier" to row.positionMultiplier, "cooldown_multiplier" to row.cooldownMultiplier,
                "preferred_strategy" to row.preferredStrategy, "disabled_until_epoch_ms" to row.disabledUntilEpochMs,
                "confidence" to row.confidence, "explanation" to row.explanation,
                "updated_at_epoch_ms" to row.updatedAtEpochMs
            ))
        }
        for (row in dao.learnedStrategiesForCloudShare(perTypeLimit)) {
            queued += queue("learning_events", row.updatedAtEpochMs, mapOf(
                "event_type" to "learned_strategy_profile", "strategy" to row.strategyKey,
                "sample_size" to row.sampleSize, "wins" to row.wins, "losses" to row.losses,
                "win_rate_percent" to row.winRatePercent, "profit_factor" to row.profitFactor,
                "score_adjustment" to row.scoreAdjustment, "position_multiplier" to row.positionMultiplier,
                "explanation" to row.explanation, "updated_at_epoch_ms" to row.updatedAtEpochMs
            ))
        }
        for (row in dao.learnedHoldsForCloudShare(perTypeLimit)) {
            queued += queue("learning_events", row.updatedAtEpochMs, mapOf(
                "event_type" to "learned_hold_profile", "symbol" to row.symbol,
                "sample_size" to row.sampleSize, "profitable_exits" to row.profitableExits,
                "losing_exits" to row.losingExits, "continuation_win_rate_percent" to row.continuationWinRatePercent,
                "average_hold_minutes" to row.averageHoldMinutes, "average_pnl_eur" to row.averagePnlEur,
                "net_pnl_eur" to row.netPnlEur, "hold_confidence_percent" to row.holdConfidencePercent,
                "hold_multiplier" to row.holdMultiplier, "defer_take_profit" to row.shouldDeferTakeProfit,
                "defer_trailing_exit" to row.shouldDeferTrailingExit, "explanation" to row.explanation,
                "updated_at_epoch_ms" to row.updatedAtEpochMs
            ))
        }
        for (row in dao.recentGovernanceForCloudShare(perTypeLimit)) {
            queued += queue("governance_events", row.timestampEpochMs, mapOf(
                "event_type" to row.eventType, "symbol" to row.symbol, "strategy" to row.strategy,
                "mode" to row.mode, "severity" to row.severity, "score_adjustment" to row.scoreAdjustment,
                "blocked" to row.blocked, "size_multiplier" to row.sizeMultiplier, "reason" to row.reason,
                "timestamp_epoch_ms" to row.timestampEpochMs
            ))
        }
        for (row in dao.recentExecutionQualityForCloudShare(perTypeLimit)) {
            queued += queue("execution_quality", row.timestampEpochMs, mapOf(
                "symbol" to row.symbol, "side" to row.side, "mode" to row.mode, "order_type" to row.orderType,
                "expected_price" to row.expectedPrice, "actual_price" to row.actualPrice,
                "slippage_pct" to row.slippagePct, "notional_quote" to row.notionalQuote,
                "timestamp_epoch_ms" to row.timestampEpochMs
            ))
        }
        return queued
    }

    suspend fun queueTrade(row: TradeEntity): Int = queue("trades", row.timestampEpochMs, mapOf(
        "id" to row.id, "symbol" to row.symbol, "side" to row.side,
        "quantity" to row.quantity, "price_eur" to row.priceEur,
        "fee_eur" to row.feeEur, "paper" to row.paper,
        "realized_pnl_eur" to row.realizedPnlEur, "ai_score" to row.aiScore,
        "ai_reason" to row.aiReason, "client_order_id" to row.clientOrderId,
        "exchange_order_id" to row.exchangeOrderId,
        "timestamp_epoch_ms" to row.timestampEpochMs,
        "mode" to if (row.paper) "PAPER" else "LIVE"
    ))

    suspend fun queueSignal(row: SignalEntity): Int = queue("signals", row.timestampEpochMs, mapOf(
        "id" to row.id, "symbol" to row.symbol, "action" to row.action,
        "score" to row.score, "risk_percent" to row.riskPercent,
        "reason" to row.reason, "timestamp_epoch_ms" to row.timestampEpochMs
    ))

    suspend fun queueAiDecision(row: AiDecisionEntity): Int = queue("learning_events", row.timestampEpochMs, mapOf(
        "event_type" to "ai_decision", "id" to row.id, "symbol" to row.symbol,
        "action" to row.finalAction, "score" to row.finalScore,
        "confidence_percent" to row.confidencePercent,
        "technical_score" to row.technicalScore, "news_score" to row.newsScore,
        "memory_score" to row.memoryScore, "allowed_to_trade" to row.allowedToTrade,
        "explanation" to row.explanation, "timestamp_epoch_ms" to row.timestampEpochMs
    ))

    suspend fun queueLearningSnapshot(row: LearningFeatureSnapshotEntity): Int = queue("learning_events", row.timestampEpochMs, mapOf(
        "event_type" to "feature_snapshot", "id" to row.id,
        "symbol" to row.symbol, "strategy" to row.strategyMode,
        "mode" to row.mode, "action" to row.action,
        "score" to row.finalScore, "technical_score" to row.technicalScore,
        "news_score" to row.newsScore, "memory_score" to row.memoryScore,
        "spread_percent" to row.spreadPercent, "volume_24h" to row.volume24h,
        "price_change_24h_percent" to row.priceChange24hPercent,
        "allowed_to_trade" to row.allowedToTrade, "traded" to row.traded,
        "order_side" to row.orderSide, "order_type" to row.orderType,
        "notional_quote" to row.notionalQuote, "reason" to row.reason,
        "timestamp_epoch_ms" to row.timestampEpochMs
    ))

    private suspend fun queue(source: String, timestampMs: Long, payload: Map<String, Any?>): Int {
        val timestamp = Instant.ofEpochMilli(timestampMs).toString()
        val event = CloudShareEvent.create(source, timestamp, payload)
        val entity = CloudShareOutboxEntity(
            eventId = event.eventId,
            sourceTable = event.sourceTable,
            eventTimestamp = event.eventTimestamp,
            schemaVersion = event.schemaVersion,
            payloadJson = mapAdapter.toJson(event.payload),
            payloadSha256 = event.payloadSha256
        )
        return if (dao.enqueue(entity) == -1L) 0 else 1
    }
}
