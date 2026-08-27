package com.ksp.cryptobot.intelligence

import android.content.Context
import com.ksp.cryptobot.data.AiValueAttributionEntity
import com.ksp.cryptobot.data.GovernanceDao
import com.ksp.cryptobot.settings.AppSettingsStore
import kotlinx.coroutines.sync.Mutex
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sqrt

enum class AiAdaptiveAction {
    HOLD,
    DISABLE_SOL,
    DISABLE_CLOUD_AI
}

data class AiAdaptiveSample(
    val normalizedValueRate: BigDecimal,
    val absoluteValueQuote: BigDecimal,
    val resolvedAtEpochMs: Long
)

data class AiValueStatistics(
    val samples: Int,
    val totalValueQuote: BigDecimal,
    val meanNormalizedValue: BigDecimal,
    val standardDeviation: BigDecimal,
    val standardError: BigDecimal,
    val lower95: BigDecimal,
    val upper95: BigDecimal,
    val evidenceSpanMs: Long
)

data class AiAdaptiveGovernanceDecision(
    val action: AiAdaptiveAction,
    val overall: AiValueStatistics,
    val sol: AiValueStatistics,
    val excludedLowIntegrityRows: Int,
    val reason: String,
    val evaluatedAtEpochMs: Long = System.currentTimeMillis()
)

data class AiAdaptiveGovernanceState(
    val lastAction: String,
    val lastActionAtEpochMs: Long,
    val lastReason: String
)

/**
 * M8 statistically-gated defensive AI governance.
 *
 * Authority is deliberately one-way:
 * - may disable Sol;
 * - may disable all cloud AI;
 * - never enables/re-enables paid AI;
 * - never raises API budgets;
 * - never increases trading size;
 * - never weakens M4 execution-state, M5 EV, M6 veto/reduce, or risk controls.
 */
class AiAdaptiveGovernanceEngine(
    context: Context,
    private val governanceDao: GovernanceDao,
    private val settingsStore: AppSettingsStore
) {
    companion object {
        const val MIN_OVERALL_SAMPLES = 50
        const val MIN_SOL_SAMPLES = 30
        const val MAX_ROWS = 5000
        const val ACTION_COOLDOWN_MS = 24L * 60L * 60L * 1000L
        const val MIN_EVIDENCE_SPAN_MS = 7L * 24L * 60L * 60L * 1000L

        private val MIN_BASELINE_QUOTE = BigDecimal("5.00")
        private val CLIP_RATE = BigDecimal("0.25")
        private val MIN_HARM_MEAN_RATE = BigDecimal("-0.0005")
        private val MIN_OVERALL_TOTAL_HARM = BigDecimal("-0.25")
        private val MIN_SOL_TOTAL_HARM = BigDecimal("-0.10")

        fun decide(
            overallSamples: List<AiAdaptiveSample>,
            solSamples: List<AiAdaptiveSample>,
            cloudEnabled: Boolean,
            solEnabled: Boolean,
            excludedLowIntegrityRows: Int = 0
        ): AiAdaptiveGovernanceDecision {
            val overall = statistics(overallSamples)
            val sol = statistics(solSamples)

            if (!cloudEnabled) {
                return AiAdaptiveGovernanceDecision(
                    AiAdaptiveAction.HOLD,
                    overall,
                    sol,
                    excludedLowIntegrityRows,
                    "Cloud AI is already disabled. M8 never auto-enables paid AI."
                )
            }

            val overallProvenHarmful =
                overall.samples >= MIN_OVERALL_SAMPLES &&
                    overall.evidenceSpanMs >= MIN_EVIDENCE_SPAN_MS &&
                    overall.totalValueQuote <= MIN_OVERALL_TOTAL_HARM &&
                    overall.meanNormalizedValue <= MIN_HARM_MEAN_RATE &&
                    overall.upper95 < BigDecimal.ZERO

            if (overallProvenHarmful) {
                return AiAdaptiveGovernanceDecision(
                    AiAdaptiveAction.DISABLE_CLOUD_AI,
                    overall,
                    sol,
                    excludedLowIntegrityRows,
                    "Disable cloud AI: n=${overall.samples}, span=${days(overall.evidenceSpanMs)}d, totalValue=${overall.totalValueQuote.s6()}, mean=${overall.meanNormalizedValue.pct()}, 95%CI=[${overall.lower95.pct()},${overall.upper95.pct()}]. Even the optimistic 95% bound is below zero after AI costs."
                )
            }

            val solProvenHarmful =
                solEnabled &&
                    sol.samples >= MIN_SOL_SAMPLES &&
                    sol.evidenceSpanMs >= MIN_EVIDENCE_SPAN_MS &&
                    sol.totalValueQuote <= MIN_SOL_TOTAL_HARM &&
                    sol.meanNormalizedValue <= MIN_HARM_MEAN_RATE &&
                    sol.upper95 < BigDecimal.ZERO

            if (solProvenHarmful) {
                return AiAdaptiveGovernanceDecision(
                    AiAdaptiveAction.DISABLE_SOL,
                    overall,
                    sol,
                    excludedLowIntegrityRows,
                    "Disable Sol only: n=${sol.samples}, span=${days(sol.evidenceSpanMs)}d, incrementalValue=${sol.totalValueQuote.s6()}, mean=${sol.meanNormalizedValue.pct()}, 95%CI=[${sol.lower95.pct()},${sol.upper95.pct()}]. Sol's optimistic 95% incremental-value bound is below zero."
                )
            }

            val positiveOverall =
                overall.samples >= MIN_OVERALL_SAMPLES &&
                    overall.evidenceSpanMs >= MIN_EVIDENCE_SPAN_MS &&
                    overall.totalValueQuote > BigDecimal.ZERO &&
                    overall.lower95 > BigDecimal.ZERO

            val positiveSol =
                sol.samples >= MIN_SOL_SAMPLES &&
                    sol.evidenceSpanMs >= MIN_EVIDENCE_SPAN_MS &&
                    sol.totalValueQuote > BigDecimal.ZERO &&
                    sol.lower95 > BigDecimal.ZERO

            val reason = when {
                positiveOverall && (!solEnabled || positiveSol || sol.samples < MIN_SOL_SAMPLES) ->
                    "Hold: paid AI has statistically positive value evidence. M8 still cannot auto-enable or expand it."
                overall.samples < MIN_OVERALL_SAMPLES ->
                    "Hold: overall evidence ${overall.samples}/$MIN_OVERALL_SAMPLES samples; excludedLowIntegrity=$excludedLowIntegrityRows."
                overall.evidenceSpanMs < MIN_EVIDENCE_SPAN_MS ->
                    "Hold: overall evidence span ${days(overall.evidenceSpanMs)}d/7d; avoiding a single-regime shutdown."
                solEnabled && sol.samples < MIN_SOL_SAMPLES ->
                    "Hold: cloud AI is not proven harmful; Sol evidence ${sol.samples}/$MIN_SOL_SAMPLES samples."
                solEnabled && sol.samples >= MIN_SOL_SAMPLES && sol.evidenceSpanMs < MIN_EVIDENCE_SPAN_MS ->
                    "Hold: Sol evidence span ${days(sol.evidenceSpanMs)}d/7d."
                else ->
                    "Hold: harm thresholds are not satisfied or the 95% confidence interval crosses zero."
            }

            return AiAdaptiveGovernanceDecision(
                AiAdaptiveAction.HOLD,
                overall,
                sol,
                excludedLowIntegrityRows,
                reason
            )
        }

        fun statistics(samples: List<AiAdaptiveSample>): AiValueStatistics {
            if (samples.isEmpty()) {
                return AiValueStatistics(
                    0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L
                )
            }

            val clipped = samples.map {
                it.normalizedValueRate.coerceIn(CLIP_RATE.negate(), CLIP_RATE)
            }
            val n = clipped.size
            val mean = clipped.fold(BigDecimal.ZERO) { acc, value -> acc.add(value) }
                .divide(BigDecimal(n), 16, RoundingMode.HALF_UP)

            val variance = if (n > 1) {
                clipped.fold(0.0) { acc, value ->
                    val d = value.toDouble() - mean.toDouble()
                    acc + d * d
                } / (n - 1).toDouble()
            } else 0.0

            val sd = sqrt(variance.coerceAtLeast(0.0))
            val se = if (n > 0) sd / sqrt(n.toDouble()) else 0.0
            val margin = critical95(n) * se
            val total = samples.fold(BigDecimal.ZERO) { acc, sample ->
                acc.add(sample.absoluteValueQuote)
            }
            val minTs = samples.minOfOrNull { it.resolvedAtEpochMs } ?: 0L
            val maxTs = samples.maxOfOrNull { it.resolvedAtEpochMs } ?: 0L

            return AiValueStatistics(
                samples = n,
                totalValueQuote = total,
                meanNormalizedValue = mean,
                standardDeviation = BigDecimal.valueOf(sd),
                standardError = BigDecimal.valueOf(se),
                lower95 = BigDecimal.valueOf(mean.toDouble() - margin),
                upper95 = BigDecimal.valueOf(mean.toDouble() + margin),
                evidenceSpanMs = (maxTs - minTs).coerceAtLeast(0L)
            )
        }

        private fun critical95(n: Int): Double = when {
            n < 30 -> 2.20
            n < 40 -> 2.06
            n < 60 -> 2.03
            n < 120 -> 2.00
            else -> 1.98
        }

        fun sample(
            absoluteValueQuote: BigDecimal,
            deterministicNotionalQuote: BigDecimal,
            resolvedAtEpochMs: Long
        ): AiAdaptiveSample {
            val baseline = deterministicNotionalQuote.abs().max(MIN_BASELINE_QUOTE)
            val rate = absoluteValueQuote
                .divide(baseline, 16, RoundingMode.HALF_UP)
                .coerceIn(CLIP_RATE.negate(), CLIP_RATE)
            return AiAdaptiveSample(rate, absoluteValueQuote, resolvedAtEpochMs)
        }

        fun isHighIntegrityResolution(row: AiValueAttributionEntity): Boolean =
            row.status == "RESOLVED" &&
                row.resolvedAtEpochMs > 0L &&
                row.resolution != "HORIZON_FALLBACK_CURRENT_TICKER"

        private fun BigDecimal.coerceIn(lo: BigDecimal, hi: BigDecimal): BigDecimal = when {
            this < lo -> lo
            this > hi -> hi
            else -> this
        }

        private fun BigDecimal.s6(): String =
            setScale(6, RoundingMode.HALF_UP).toPlainString()

        private fun BigDecimal.pct(): String =
            multiply(BigDecimal("100")).setScale(3, RoundingMode.HALF_UP).toPlainString() + "%"

        private fun days(ms: Long): String =
            BigDecimal.valueOf(ms).divide(
                BigDecimal.valueOf(24L * 60L * 60L * 1000L),
                1,
                RoundingMode.DOWN
            ).toPlainString()
    }

    private val prefs = context.applicationContext.getSharedPreferences(
        "cts_ai_adaptive_governance",
        Context.MODE_PRIVATE
    )
    private val mutationMutex = Mutex()

    suspend fun inspect(): AiAdaptiveGovernanceDecision {
        val rows = governanceDao.resolvedAiAttributions(MAX_ROWS)
        val eligible = rows.filter { isHighIntegrityResolution(it) }
        val excluded = rows.size - eligible.size
        val config = settingsStore.cloudAiConfig()
        return decide(
            overallSamples = overallSamples(eligible),
            solSamples = solSamples(eligible),
            cloudEnabled = config.enabled,
            solEnabled = config.solEnabled,
            excludedLowIntegrityRows = excluded
        )
    }

    suspend fun evaluateAndApply(
        nowEpochMs: Long = System.currentTimeMillis()
    ): AiAdaptiveGovernanceDecision {
        mutationMutex.lock()
        try {
            val config = settingsStore.cloudAiConfig()
            val rows = governanceDao.resolvedAiAttributions(MAX_ROWS)
            val eligible = rows.filter { isHighIntegrityResolution(it) }
            val excluded = rows.size - eligible.size
            val candidate = decide(
                overallSamples = overallSamples(eligible),
                solSamples = solSamples(eligible),
                cloudEnabled = config.enabled,
                solEnabled = config.solEnabled,
                excludedLowIntegrityRows = excluded
            )

            if (candidate.action == AiAdaptiveAction.HOLD) return candidate

            val previous = state()
            if (previous.lastActionAtEpochMs > 0L &&
                nowEpochMs - previous.lastActionAtEpochMs < ACTION_COOLDOWN_MS
            ) {
                return candidate.copy(
                    action = AiAdaptiveAction.HOLD,
                    reason = "Hold by 24h adaptation cooldown. Candidate=${candidate.action}. ${candidate.reason}"
                )
            }

            when (candidate.action) {
                AiAdaptiveAction.DISABLE_SOL -> {
                    settingsStore.saveCloudAiConfig(
                        enabled = config.enabled,
                        monthlyBudgetUsd = config.monthlyBudgetUsd,
                        solEnabled = false,
                        maxSolCallsPerDay = config.maxSolCallsPerDay
                    )
                }
                AiAdaptiveAction.DISABLE_CLOUD_AI -> {
                    settingsStore.saveCloudAiConfig(
                        enabled = false,
                        monthlyBudgetUsd = config.monthlyBudgetUsd,
                        solEnabled = false,
                        maxSolCallsPerDay = config.maxSolCallsPerDay
                    )
                }
                AiAdaptiveAction.HOLD -> Unit
            }

            prefs.edit()
                .putString("last_action", candidate.action.name)
                .putLong("last_action_at", nowEpochMs)
                .putString("last_reason", candidate.reason.take(1000))
                .apply()

            return candidate
        } finally {
            mutationMutex.unlock()
        }
    }

    fun state(): AiAdaptiveGovernanceState =
        AiAdaptiveGovernanceState(
            lastAction = prefs.getString("last_action", "NONE") ?: "NONE",
            lastActionAtEpochMs = prefs.getLong("last_action_at", 0L),
            lastReason = prefs.getString("last_reason", "") ?: ""
        )

    private fun overallSamples(rows: List<AiValueAttributionEntity>): List<AiAdaptiveSample> =
        rows.mapNotNull { row ->
            val lunaCost = row.lunaCostQuote.toBigDecimalOrNull() ?: BigDecimal.ZERO
            if (lunaCost <= BigDecimal.ZERO) return@mapNotNull null
            val value = row.aiValueAddedQuote.toBigDecimalOrNull() ?: return@mapNotNull null
            val baseline = row.deterministicNotionalQuote.toBigDecimalOrNull() ?: return@mapNotNull null
            sample(value, baseline, row.resolvedAtEpochMs)
        }

    private fun solSamples(rows: List<AiValueAttributionEntity>): List<AiAdaptiveSample> =
        rows.mapNotNull { row ->
            val solCost = row.solCostQuote.toBigDecimalOrNull() ?: BigDecimal.ZERO
            if (solCost <= BigDecimal.ZERO) return@mapNotNull null
            val value = row.solIncrementalValueQuote.toBigDecimalOrNull() ?: return@mapNotNull null
            val baseline = row.deterministicNotionalQuote.toBigDecimalOrNull() ?: return@mapNotNull null
            sample(value, baseline, row.resolvedAtEpochMs)
        }
}
