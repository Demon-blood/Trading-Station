package com.ksp.cryptobot.cloudshare

import com.ksp.cryptobot.data.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class CloudShareGovernanceAggregateCollector(
    private val cloudDao: CloudShareDao,
    private val governanceDao: GovernanceDao
) {
    private val moshi = Moshi.Builder().build()
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val adapter = moshi.adapter<Map<String, Any?>>(mapType)

    suspend fun collectRecent(days: Int = 7): Int {
        val since = System.currentTimeMillis() - days.coerceAtLeast(1) * 86_400_000L
        val events = governanceDao.recentEvents(10_000).filter { it.timestampEpochMs >= since }
        val quality = governanceDao.recentExecutionQuality(10_000).filter { it.timestampEpochMs >= since }
        val advanced = governanceDao.recentAdvancedExecution(10_000).filter { it.timestampEpochMs >= since }
        var queued = 0

        fun day(ms: Long): String = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(ms))
        fun reasonCategory(reason: String): String {
            val r = reason.lowercase()
            return when {
                "spread" in r -> "spread"
                "loss" in r || "risk budget" in r -> "loss_risk"
                "volume" in r || "liquid" in r -> "liquidity"
                "candle" in r || "ticker" in r || "feed" in r -> "market_data"
                "error" in r || "failed" in r -> "runtime_error"
                "cooldown" in r -> "cooldown"
                "balance" in r || "reserve" in r -> "balance_reserve"
                else -> "other"
            }
        }

        val anomalyGroups = events.filter { it.eventType == "anomaly_event" }.groupBy { listOf(day(it.timestampEpochMs), it.symbol, it.severity, reasonCategory(it.reason)) }
        for ((k, rows) in anomalyGroups) queued += queue("shared_anomaly_daily", rows.maxOf { it.timestampEpochMs }, mapOf(
            "day" to k[0], "symbol" to k[1], "severity" to k[2], "reason_category" to k[3],
            "sample_count" to rows.size, "blocked_count" to rows.count { it.blocked }
        ))

        val guardGroups = events.filter { it.eventType == "why_not_trade" || (it.blocked && it.eventType == "production_ai_evaluation") }
            .groupBy { listOf(day(it.timestampEpochMs), it.symbol, "BLOCK", reasonCategory(it.reason)) }
        for ((k, rows) in guardGroups) queued += queue("shared_guard_daily", rows.maxOf { it.timestampEpochMs }, mapOf(
            "day" to k[0], "symbol" to k[1], "action" to k[2], "reason_category" to k[3],
            "sample_count" to rows.size, "blocked_count" to rows.size
        ))

        val riskGroups = events.filter { it.eventType == "risk_budget_event" }.groupBy { listOf(day(it.timestampEpochMs), it.mode, it.symbol) }
        for ((k, rows) in riskGroups) queued += queue("shared_risk_budget_daily", rows.maxOf { it.timestampEpochMs }, mapOf(
            "day" to k[0], "mode" to k[1], "symbol" to k[2], "sample_count" to rows.size,
            "blocked_count" to rows.count { it.blocked }, "size_multiplier_sum" to rows.sumOf { it.sizeMultiplier }
        ))

        val safeGroups = events.filter { it.eventType == "safe_mode_event" }.groupBy { row ->
            val level = row.reason.substringBefore(':').ifBlank { "NORMAL" }
            listOf(day(row.timestampEpochMs), level, reasonCategory(row.reason))
        }
        for ((k, rows) in safeGroups) queued += queue("shared_safe_mode_daily", rows.maxOf { it.timestampEpochMs }, mapOf(
            "day" to k[0], "level" to k[1], "reason_category" to k[2], "sample_count" to rows.size,
            "blocked_count" to rows.count { it.blocked }, "score_adjustment_sum" to rows.sumOf { it.scoreAdjustment }
        ))

        val watchdogGroups = events.filter { it.eventType == "watchdog_error" }.groupBy { listOf(day(it.timestampEpochMs), it.severity, reasonCategory(it.reason)) }
        for ((k, rows) in watchdogGroups) queued += queue("shared_watchdog_daily", rows.maxOf { it.timestampEpochMs }, mapOf(
            "day" to k[0], "severity" to k[1], "status" to k[2], "sample_count" to rows.size
        ))

        val crashGroups = events.filter { it.eventType == "crash_recovery_event" }.groupBy { listOf(day(it.timestampEpochMs), reasonCategory(it.reason)) }
        for ((k, rows) in crashGroups) queued += queue("shared_crash_recovery_daily", rows.maxOf { it.timestampEpochMs }, mapOf(
            "day" to k[0], "status" to k[1], "sample_count" to rows.size
        ))

        val counterGroups = events.filter { it.eventType == "counterfactual_event" }.groupBy { row ->
            val scenario = row.reason.substringBefore(';').take(120)
            listOf(day(row.timestampEpochMs), row.symbol, scenario)
        }
        for ((k, rows) in counterGroups) queued += queue("shared_counterfactual_daily", rows.maxOf { it.timestampEpochMs }, mapOf(
            "day" to k[0], "symbol" to k[1], "scenario" to k[2], "sample_count" to rows.size,
            "score_adjustment_sum" to rows.sumOf { it.scoreAdjustment }
        ))

        val qualityGroups = quality.groupBy { listOf(day(it.timestampEpochMs), it.symbol, it.side, it.mode) }
        for ((k, rows) in qualityGroups) queued += queue("shared_execution_quality_daily", rows.maxOf { it.timestampEpochMs }, mapOf(
            "day" to k[0], "symbol" to k[1], "side" to k[2], "mode" to k[3], "sample_count" to rows.size,
            "slippage_pct_sum" to rows.sumOf { it.slippagePct },
            "worst_slippage_pct" to (rows.maxOfOrNull { it.slippagePct } ?: 0.0),
            "notional_quote_sum" to rows.sumOf { it.notionalQuote }
        ))

        val orderTypeGroups = advanced.filter { it.eventType == "order_type" }.groupBy { listOf(day(it.timestampEpochMs), it.symbol, it.recommendedOrderType.ifBlank { "LIMIT" }, it.reasonCategory) }
        for ((k, rows) in orderTypeGroups) queued += queue("shared_order_type_daily", rows.maxOf { it.timestampEpochMs }, mapOf(
            "day" to k[0], "symbol" to k[1], "recommended_type" to k[2], "reason_category" to k[3],
            "sample_count" to rows.size, "requested_quote_sum" to rows.sumOf { it.requestedQuote }, "final_quote_sum" to rows.sumOf { it.finalQuote }
        ))

        val liquidityGroups = advanced.filter { it.eventType == "liquidity_sizing" }.groupBy { listOf(day(it.timestampEpochMs), it.symbol, it.reasonCategory, it.requestedSizeBand) }
        for ((k, rows) in liquidityGroups) queued += queue("shared_liquidity_sizing_daily", rows.maxOf { it.timestampEpochMs }, mapOf(
            "day" to k[0], "symbol" to k[1], "reason_category" to k[2], "requested_size_band" to k[3],
            "sample_count" to rows.size, "requested_quote_sum" to rows.sumOf { it.requestedQuote }, "final_quote_sum" to rows.sumOf { it.finalQuote },
            "multiplier_sum" to rows.sumOf { it.multiplier }
        ))

        val exitGroups = advanced.filter { it.eventType == "exit_optimization" }.groupBy { listOf(day(it.timestampEpochMs), it.strategy, it.symbol, it.exitMethod, it.qualityTier) }
        for ((k, rows) in exitGroups) queued += queue("shared_exit_daily", rows.maxOf { it.timestampEpochMs }, mapOf(
            "day" to k[0], "strategy" to k[1], "symbol" to k[2], "exit_method" to k[3], "quality_tier" to k[4],
            "sample_count" to rows.size, "blocked_count" to rows.count { it.blocked }, "sell_fraction_sum" to rows.sumOf { it.multiplier }
        ))


        val paperGroups = advanced.filter { it.eventType == "paper_execution" }.groupBy { listOf(day(it.timestampEpochMs), it.symbol, it.side, it.requestedSizeBand) }
        for ((k, rows) in paperGroups) queued += queue("shared_paper_execution_daily", rows.maxOf { it.timestampEpochMs }, mapOf(
            "day" to k[0], "symbol" to k[1], "side" to k[2], "trade_size_band" to k[3],
            "sample_count" to rows.size, "requested_quote_sum" to rows.sumOf { it.requestedQuote }, "filled_quote_sum" to rows.sumOf { it.finalQuote },
            "fill_ratio_sum" to rows.sumOf { it.multiplier }
        ))

        val reconciliationGroups = advanced.filter { it.eventType == "reconciliation" }.groupBy { listOf(day(it.timestampEpochMs), it.severity) }
        for ((k, rows) in reconciliationGroups) queued += queue("shared_reconciliation_daily", rows.maxOf { it.timestampEpochMs }, mapOf(
            "day" to k[0], "severity" to k[1], "sample_count" to rows.size, "issue_count" to rows.count { it.severity != "INFO" }
        ))
        return queued
    }

    private suspend fun queue(source: String, timestampMs: Long, payload: Map<String, Any?>): Int {
        val event = CloudShareEvent.create(source, Instant.ofEpochMilli(timestampMs).toString(), payload)
        val row = CloudShareOutboxEntity(
            eventId = event.eventId, sourceTable = event.sourceTable, eventTimestamp = event.eventTimestamp,
            schemaVersion = event.schemaVersion, payloadJson = adapter.toJson(event.payload), payloadSha256 = event.payloadSha256
        )
        return if (cloudDao.enqueue(row) == -1L) 0 else 1
    }
}
