package com.ksp.cryptobot.cloudshare

import com.ksp.cryptobot.data.CloudShareCollectiveIndexEntity

object CollectiveIntelligenceIndexer {
    fun toIndex(event: CloudShareDownloadedEvent): CloudShareCollectiveIndexEntity {
        val payload = event.payload
        val source = event.sourceTable.trim()
        val eventType = payload.string("event_type").lowercase()
        var samples = 0
        var wins = 0
        var losses = 0
        var edgeSum = 0.0
        var isOutcome = false

        if (source == "shared_learning_daily") {
            // Decisions/signals are observational evidence even when they do not yet have
            // realized PnL. Preserve sample_count so data readiness can progress without
            // pretending these rows are resolved trade outcomes.
            samples = payload.int("sample_count").coerceAtLeast(0)
            val positive = payload.int("positive_pnl_count").coerceAtLeast(0)
            val negative = payload.int("negative_pnl_count").coerceAtLeast(0)
            val zero = payload.int("zero_pnl_count").coerceAtLeast(0)
            val outcomeLike = eventType.contains("outcome") || eventType == "trade" || eventType.endsWith("_trade")
            if (outcomeLike) {
                val resolvedSamples = positive + negative + zero
                if (resolvedSamples > 0) samples = resolvedSamples
                if (samples > 0) {
                    isOutcome = true
                    wins = positive
                    losses = negative
                    edgeSum = payload.double("pnl_sum")
                }
            }
        } else if (source == "shared_signal_daily") {
            // Signal aggregates prove that fresh collective data is flowing. They are not
            // outcomes and therefore must never influence win rate / edge adjustments.
            samples = payload.int("sample_count").coerceAtLeast(0)
        } else if (source == "shared_trade_daily") {
            val side = payload.string("side").uppercase()
            samples = payload.int("sample_count").coerceAtLeast(0)
            if (side in setOf("SELL", "EXIT", "CLOSE") && samples > 0) {
                isOutcome = true
                wins = payload.int("wins").coerceAtLeast(0)
                losses = payload.int("losses").coerceAtLeast(0)
                edgeSum = when {
                    payload.containsKey("edge_sum") -> payload.double("edge_sum")
                    else -> payload.double("avg_net_return_pct") * samples.toDouble()
                }
            }
        }

        return CloudShareCollectiveIndexEntity(
            eventId = event.eventId,
            contributorId = event.contributorId,
            sourceTable = source,
            aggregateKey = event.aggregateKey.ifBlank { CloudShareProtocol.sharedAggregateKey(source, payload) },
            symbol = payload.string("symbol").uppercase(),
            strategy = payload.string("strategy").uppercase(),
            regime = payload.string("regime").uppercase(),
            timeframe = payload.string("timeframe").lowercase(),
            eventType = eventType,
            isOutcome = isOutcome,
            sampleCount = samples,
            wins = wins,
            losses = losses,
            edgeSum = edgeSum,
            eventTimestamp = event.eventTimestamp
        )
    }

    private fun Map<String, Any?>.string(key: String): String = this[key]?.toString().orEmpty()
    private fun Map<String, Any?>.double(key: String): Double = when (val value = this[key]) {
        is Number -> value.toDouble()
        else -> value?.toString()?.toDoubleOrNull() ?: 0.0
    }
    private fun Map<String, Any?>.int(key: String): Int = double(key).toInt()
}
