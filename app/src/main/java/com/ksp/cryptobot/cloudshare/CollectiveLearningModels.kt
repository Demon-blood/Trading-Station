package com.ksp.cryptobot.cloudshare

import kotlin.math.abs
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

data class CollectiveOutcomeRow(
    val eventId: String,
    val contributorId: String,
    val sourceTable: String,
    val aggregateKey: String,
    val symbol: String,
    val strategy: String,
    val regime: String,
    val timeframe: String,
    val eventType: String,
    val sampleCount: Int,
    val wins: Int,
    val losses: Int,
    val edgeSum: Double,
    val eventTimestamp: String
)

data class CollectiveScoreResult(
    val adjustment: Int,
    val reason: String,
    val ready: Boolean,
    val matchTier: String,
    val samples: Int,
    val wins: Int,
    val losses: Int,
    val averageEdgePct: Double,
    val winRate: Double,
    val matchedRows: Int,
    val requiredSamples: Int
) {
    companion object {
        fun neutral(requiredSamples: Int, samples: Int = 0, tier: String = "none") = CollectiveScoreResult(
            adjustment = 0,
            reason = "CloudShare evidence neutral: $samples/$requiredSamples matching outcome samples; local strategy remains active.",
            ready = false,
            matchTier = tier,
            samples = samples,
            wins = 0,
            losses = 0,
            averageEdgePct = 0.0,
            winRate = 0.5,
            matchedRows = 0,
            requiredSamples = requiredSamples
        )
    }
}

data class CollectiveCacheSnapshot(
    val enabled: Boolean,
    val rowCount: Int,
    val totalSamples: Int,
    val contributors: Int,
    val newestEventTimestamp: String,
    val minSamples: Int,
    val maxAdjustment: Int,
    val weight: Double
)

object CollectiveScoreMath {
    private data class Tier(
        val name: String,
        val specificity: Double,
        val matches: (CollectiveOutcomeRow) -> Boolean,
        val usable: Boolean
    )

    fun score(
        rows: List<CollectiveOutcomeRow>,
        symbol: String,
        strategy: String,
        regime: String,
        timeframe: String,
        minSamples: Int = 25,
        maxAdjustment: Int = 6,
        weight: Double = 1.0
    ): CollectiveScoreResult {
        val symbolU = symbol.trim().uppercase()
        val strategyU = strategy.trim().uppercase()
        val regimeU = regime.trim().uppercase()
        val timeframeL = timeframe.trim().lowercase()
        val required = minSamples.coerceAtLeast(1)

        val tiers = listOf(
            Tier("symbol+strategy+regime+timeframe", 1.00,
                { it.symbol == symbolU && it.strategy == strategyU && it.regime == regimeU && it.timeframe == timeframeL },
                symbolU.isNotBlank() && strategyU.isNotBlank() && regimeU.isNotBlank() && timeframeL.isNotBlank()),
            Tier("symbol+strategy+regime (cross-timeframe)", 0.90,
                { it.symbol == symbolU && it.strategy == strategyU && it.regime == regimeU },
                symbolU.isNotBlank() && strategyU.isNotBlank() && regimeU.isNotBlank()),
            Tier("symbol+strategy", 0.80,
                { it.symbol == symbolU && it.strategy == strategyU },
                symbolU.isNotBlank() && strategyU.isNotBlank()),
            Tier("strategy+regime (cross-symbol)", 0.60,
                { it.strategy == strategyU && it.regime == regimeU },
                strategyU.isNotBlank() && regimeU.isNotBlank()),
            Tier("strategy (cross-symbol/regime/timeframe)", 0.50,
                { it.strategy == strategyU },
                strategyU.isNotBlank()),
            Tier("symbol+regime (cross-strategy)", 0.45,
                { it.symbol == symbolU && it.regime == regimeU },
                symbolU.isNotBlank() && regimeU.isNotBlank()),
            Tier("symbol (cross-strategy/regime/timeframe)", 0.35,
                { it.symbol == symbolU },
                symbolU.isNotBlank())
        )

        var bestPartial: Pair<Tier, List<CollectiveOutcomeRow>>? = null
        var chosen: Pair<Tier, List<CollectiveOutcomeRow>>? = null
        for (tier in tiers) {
            if (!tier.usable) continue
            val matches = rows.filter(tier.matches)
            val samples = matches.sumOf { it.sampleCount.coerceAtLeast(0) }
            val currentBest = bestPartial?.second?.sumOf { it.sampleCount.coerceAtLeast(0) } ?: -1
            if (samples > currentBest) bestPartial = tier to matches
            if (samples >= required) {
                chosen = tier to matches
                break
            }
        }

        if (chosen == null) {
            val partialTier = bestPartial?.first?.name ?: "none"
            val partialRows = bestPartial?.second.orEmpty()
            val partialSamples = partialRows.sumOf { it.sampleCount.coerceAtLeast(0) }
            return CollectiveScoreResult.neutral(required, partialSamples, partialTier).copy(matchedRows = partialRows.size)
        }

        val (tier, matches) = chosen
        val samples = matches.sumOf { it.sampleCount.coerceAtLeast(0) }.coerceAtLeast(1)
        val wins = matches.sumOf { it.wins.coerceAtLeast(0) }
        val losses = matches.sumOf { it.losses.coerceAtLeast(0) }
        val edgeSum = matches.sumOf { it.edgeSum }
        val resolved = wins + losses
        val averageEdge = edgeSum / samples.toDouble()
        val winRate = if (resolved > 0) wins.toDouble() / resolved.toDouble() else 0.5
        val raw = (averageEdge * 2.5) + ((winRate - 0.5) * 12.0)
        val effectiveWeight = weight.coerceAtLeast(0.0) * tier.specificity
        val limit = abs(maxAdjustment).coerceAtLeast(0).toDouble()
        val bounded = (raw * effectiveWeight).coerceIn(-limit, limit)
        val adjustment = BigDecimal.valueOf(bounded).setScale(0, RoundingMode.HALF_EVEN).toInt()
        return CollectiveScoreResult(
            adjustment = adjustment,
            reason = "CloudShare collective evidence (${tier.name}): samples=$samples, resolved=$resolved, win_rate=${String.format(Locale.US, "%.1f", winRate * 100.0)}%, avg_edge=${String.format(Locale.US, "%+.3f", averageEdge)}%, adjustment=${if (adjustment >= 0) "+" else ""}$adjustment.",
            ready = true,
            matchTier = tier.name,
            samples = samples,
            wins = wins,
            losses = losses,
            averageEdgePct = averageEdge,
            winRate = winRate,
            matchedRows = matches.size,
            requiredSamples = required
        )
    }
}

/**
 * Immutable in-memory view refreshed by CloudShareSyncEngine after downloaded
 * aggregate rows have been normalised. Trading code can read it synchronously
 * without blocking on Room/network calls during a market scan.
 */
object CloudShareCollectiveCache {
    @Volatile private var rows: List<CollectiveOutcomeRow> = emptyList()
    @Volatile private var enabled: Boolean = false
    @Volatile private var minSamples: Int = 25
    @Volatile private var maxAdjustment: Int = 6
    @Volatile private var weight: Double = 1.0

    fun install(
        outcomeRows: List<CollectiveOutcomeRow>,
        enabled: Boolean,
        minSamples: Int,
        maxAdjustment: Int,
        weight: Double
    ) {
        this.rows = outcomeRows.toList()
        this.enabled = enabled
        this.minSamples = minSamples.coerceIn(1, 100_000)
        this.maxAdjustment = maxAdjustment.coerceIn(0, 20)
        this.weight = weight.coerceIn(0.0, 2.0)
    }

    fun disable() {
        enabled = false
    }

    fun score(symbol: String, strategy: String = "", regime: String = "", timeframe: String = ""): CollectiveScoreResult {
        if (!enabled) return CollectiveScoreResult.neutral(minSamples)
        return CollectiveScoreMath.score(rows, symbol, strategy, regime, timeframe, minSamples, maxAdjustment, weight)
    }

    fun snapshot(): CollectiveCacheSnapshot {
        val local = rows
        return CollectiveCacheSnapshot(
            enabled = enabled,
            rowCount = local.size,
            totalSamples = local.sumOf { it.sampleCount.coerceAtLeast(0) },
            contributors = local.map { it.contributorId }.filter { it.isNotBlank() }.distinct().size,
            newestEventTimestamp = local.maxOfOrNull { it.eventTimestamp }.orEmpty(),
            minSamples = minSamples,
            maxAdjustment = maxAdjustment,
            weight = weight
        )
    }
}
