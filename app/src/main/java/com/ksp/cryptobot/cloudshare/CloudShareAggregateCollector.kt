package com.ksp.cryptobot.cloudshare

import com.ksp.cryptobot.data.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.time.Instant

/** Produces compact shared_* snapshots compatible with desktop v1.0.50 compaction exports. */
class CloudShareAggregateCollector(private val dao: CloudShareDao) {
    private val moshi = Moshi.Builder().build()
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)

    suspend fun collectRecent(days: Int = 90): Int {
        val sinceMs = System.currentTimeMillis() - days.coerceIn(1, 3650) * 86_400_000L
        var queued = 0
        for (row in dao.tradeDailyAggregates(sinceMs)) {
            val averageReturn = if (row.sampleCount > 0) row.returnPctSum / row.sampleCount.toDouble() else 0.0
            queued += queue("shared_trade_daily", row.lastTimestampEpochMs, linkedMapOf(
                "day" to row.day,
                "strategy" to "AUTO",
                "symbol" to row.symbol,
                "mode" to row.mode,
                "side" to row.side,
                "trade_size_band" to "MIXED",
                "sample_count" to row.sampleCount,
                "wins" to row.wins,
                "losses" to row.losses,
                "breakeven" to row.zeroPnl,
                "pnl_sum" to row.pnlSum,
                "avg_net_return_pct" to averageReturn,
                "first_timestamp" to Instant.ofEpochMilli(row.firstTimestampEpochMs).toString(),
                "last_timestamp" to Instant.ofEpochMilli(row.lastTimestampEpochMs).toString()
            ))
        }
        for (row in dao.signalDailyAggregates(sinceMs)) {
            queued += queue("shared_signal_daily", row.lastTimestampEpochMs, linkedMapOf(
                "day" to row.day,
                "strategy" to "AUTO",
                "symbol" to row.symbol,
                "regime" to "",
                "action" to row.action,
                "sample_count" to row.sampleCount,
                "avg_score" to if (row.sampleCount > 0) row.scoreSum / row.sampleCount.toDouble() else 0.0,
                "first_timestamp" to Instant.ofEpochMilli(row.firstTimestampEpochMs).toString(),
                "last_timestamp" to Instant.ofEpochMilli(row.lastTimestampEpochMs).toString()
            ))
        }
        for (row in dao.learningDailyAggregates(sinceMs)) {
            queued += queue("shared_learning_daily", row.lastTimestampEpochMs, linkedMapOf(
                "day" to row.day,
                "event_type" to "decision",
                "strategy" to row.strategy,
                "symbol" to row.symbol,
                "regime" to "",
                "timeframe" to "",
                "mode" to row.mode,
                "quality_tier" to "STRATEGY_EVIDENCE",
                "sample_count" to row.sampleCount,
                "score_sum" to row.scoreSum,
                "pnl_sum" to 0.0,
                "positive_pnl_count" to 0,
                "negative_pnl_count" to 0,
                "zero_pnl_count" to 0,
                "traded_count" to row.tradedCount,
                "action" to row.action,
                "first_timestamp" to Instant.ofEpochMilli(row.firstTimestampEpochMs).toString(),
                "last_timestamp" to Instant.ofEpochMilli(row.lastTimestampEpochMs).toString()
            ))
        }
        for (row in dao.sourceInventory()) {
            queued += queue("shared_source_inventory", row.lastTimestampEpochMs.takeIf { it > 0 } ?: System.currentTimeMillis(), linkedMapOf(
                "source_table" to row.sourceTable,
                "row_count" to row.rowCount,
                "first_timestamp" to row.firstTimestampEpochMs.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() }.orEmpty(),
                "last_timestamp" to row.lastTimestampEpochMs.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() }.orEmpty(),
                "export_status" to "INCLUDED_AS_SHARED_EVIDENCE"
            ))
        }
        return queued
    }

    private suspend fun queue(source: String, timestampMs: Long, payload: Map<String, Any?>): Int {
        val timestamp = Instant.ofEpochMilli(timestampMs.coerceAtLeast(1L)).toString()
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
