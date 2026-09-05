package com.ksp.cryptobot.governance

import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.SignalAction
import com.ksp.cryptobot.data.AiValueAttributionEntity
import com.ksp.cryptobot.data.ExecutionQualityEntity
import com.ksp.cryptobot.data.LearningFeatureSnapshotEntity
import com.ksp.cryptobot.data.TradeEntity
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.sqrt

enum class LearningGovernanceStage {
    WARMUP,
    PAPER_VALIDATION,
    SHADOW_VALIDATED,
    TINY_LIVE,
    ROLLBACK
}

data class DriftMetric(
    val known: Boolean,
    val score: Double,
    val recentSamples: Int,
    val baselineSamples: Int,
    val severe: Boolean,
    val reason: String
)

data class ConfidenceCalibrationAssessment(
    val known: Boolean,
    val samples: Int,
    val brierScore: Double,
    val severe: Boolean,
    val reason: String
)

data class PerformanceDecayAssessment(
    val known: Boolean,
    val recentSamples: Int,
    val baselineSamples: Int,
    val recentNetPnl: BigDecimal,
    val recentMeanPnl: BigDecimal,
    val recentLower95MeanPnl: BigDecimal,
    val baselineMeanPnl: BigDecimal,
    val recentWinRate: Double,
    val decayed: Boolean,
    val reason: String
)

data class ParameterStabilityAssessment(
    val known: Boolean,
    val samples: Int,
    val chunkMeans: List<BigDecimal>,
    val stable: Boolean,
    val reason: String
)

data class ModelValueAssessment(
    val known: Boolean,
    val samples: Int,
    val netValueQuote: BigDecimal,
    val meanValueQuote: BigDecimal,
    val lower95MeanValueQuote: BigDecimal,
    val upper95MeanValueQuote: BigDecimal,
    val defensive: Boolean,
    val reason: String
)

data class LearningExecutionBounds(
    val scoreBoostCeiling: Int = 0,
    val liveSizeMultiplierCeiling: BigDecimal = BigDecimal.ONE,
    val paperSizeMultiplierCeiling: BigDecimal = BigDecimal.ONE,
    val fillProbabilityOffset: Double = 0.0,
    val staleTimingMultiplier: Double = 1.0,
    val amendFillProbabilityThreshold: Double = 0.55,
    val slippageSafetyBufferBps: Double = 0.0
) {
    init {
        require(scoreBoostCeiling in 0..3)
        require(liveSizeMultiplierCeiling in BigDecimal("0.25")..BigDecimal.ONE)
        require(paperSizeMultiplierCeiling in BigDecimal("0.25")..BigDecimal("1.10"))
        require(fillProbabilityOffset in -0.08..0.0)
        require(staleTimingMultiplier in 0.75..1.0)
        require(amendFillProbabilityThreshold in 0.45..0.60)
        require(slippageSafetyBufferBps in 0.0..25.0)
    }
}

data class LearningGovernanceAssessment(
    val stage: LearningGovernanceStage,
    val positiveLearningEnabled: Boolean,
    val rollbackRequired: Boolean,
    val featureDrift: DriftMetric,
    val regimeDrift: DriftMetric,
    val executionDrift: DriftMetric,
    val performanceDecay: PerformanceDecayAssessment,
    val confidenceCalibration: ConfidenceCalibrationAssessment,
    val parameterStability: ParameterStabilityAssessment,
    val modelValue: ModelValueAssessment,
    val bounds: LearningExecutionBounds,
    val reason: String
) {
    fun clampScoreAdjustment(raw: Int): Int =
        if (raw > 0) raw.coerceAtMost(bounds.scoreBoostCeiling) else raw

    fun clampPositionMultiplier(raw: BigDecimal, mode: BotMode): BigDecimal {
        val ceiling = if (mode == BotMode.PAPER) {
            bounds.paperSizeMultiplierCeiling
        } else {
            bounds.liveSizeMultiplierCeiling
        }
        return raw.coerceIn(BigDecimal("0.10"), ceiling)
    }

    fun compactState(): String =
        listOf(
            stage.name,
            positiveLearningEnabled,
            rollbackRequired,
            "%.4f".format(featureDrift.score),
            "%.4f".format(regimeDrift.score),
            "%.4f".format(executionDrift.score),
            performanceDecay.recentNetPnl.toPlainString(),
            "%.4f".format(confidenceCalibration.brierScore),
            bounds.scoreBoostCeiling,
            bounds.liveSizeMultiplierCeiling.toPlainString(),
            "%.4f".format(bounds.fillProbabilityOffset),
            "%.4f".format(bounds.staleTimingMultiplier),
            "%.4f".format(bounds.amendFillProbabilityThreshold),
            "%.2f".format(bounds.slippageSafetyBufferBps)
        ).joinToString("|")
}

object LearningGovernanceRuntime {
    private val restrictiveDefault = LearningGovernanceAssessment(
        stage = LearningGovernanceStage.WARMUP,
        positiveLearningEnabled = false,
        rollbackRequired = false,
        featureDrift = unknownDrift("feature drift not evaluated"),
        regimeDrift = unknownDrift("regime drift not evaluated"),
        executionDrift = unknownDrift("execution drift not evaluated"),
        performanceDecay = unknownPerformance("performance not evaluated"),
        confidenceCalibration = unknownCalibration("confidence calibration not evaluated"),
        parameterStability = unknownStability("parameter stability not evaluated"),
        modelValue = unknownModelValue("model attribution not evaluated"),
        bounds = LearningExecutionBounds(),
        reason = "M19 governance warm-up; positive online adaptation is disabled until evidence is evaluated."
    )

    @Volatile
    private var snapshot: LearningGovernanceAssessment = restrictiveDefault

    fun snapshot(): LearningGovernanceAssessment = snapshot
    fun install(value: LearningGovernanceAssessment) { snapshot = value }
    fun reset() { snapshot = restrictiveDefault }

    private fun unknownDrift(reason: String) =
        DriftMetric(false, 0.0, 0, 0, false, reason)

    private fun unknownPerformance(reason: String) =
        PerformanceDecayAssessment(
            false, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, 0.0, false, reason
        )

    private fun unknownCalibration(reason: String) =
        ConfidenceCalibrationAssessment(false, 0, 0.0, false, reason)

    private fun unknownStability(reason: String) =
        ParameterStabilityAssessment(false, 0, emptyList(), false, reason)

    private fun unknownModelValue(reason: String) =
        ModelValueAssessment(
            false, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO, false, reason
        )
}

/**
 * M19 monotonic online-learning authority.
 *
 * Learning may reduce an already-authorized entry. It may never manufacture entry
 * permission, upgrade SMALL_BUY to BUY, or turn WATCH/WAIT/AVOID into an entry.
 */
object LearningMonotonicPolicy {
    fun action(current: SignalAction, score: Int, minBuyScore: Int): SignalAction = when (current) {
        SignalAction.SELL -> SignalAction.SELL

        SignalAction.BUY -> when {
            score >= minBuyScore -> SignalAction.BUY
            score >= minBuyScore - 8 -> SignalAction.SMALL_BUY
            score >= 55 -> SignalAction.WATCH
            score >= 45 -> SignalAction.WAIT
            else -> SignalAction.AVOID
        }

        SignalAction.SMALL_BUY -> when {
            score >= minBuyScore - 8 -> SignalAction.SMALL_BUY
            score >= 55 -> SignalAction.WATCH
            score >= 45 -> SignalAction.WAIT
            else -> SignalAction.AVOID
        }

        SignalAction.WATCH -> when {
            score >= 55 -> SignalAction.WATCH
            score >= 45 -> SignalAction.WAIT
            else -> SignalAction.AVOID
        }

        SignalAction.WAIT -> if (score < 45) SignalAction.AVOID else SignalAction.WAIT
        SignalAction.AVOID -> SignalAction.AVOID
    }
}

/**
 * Statistical drift/model governance for the existing online-learning layer.
 *
 * M19 deliberately does NOT rewrite the deterministic risk engine, exchange authority,
 * DMS, lease, stop logic or position limits. Positive online changes are allowed only
 * after enough chronological evidence, and even then LIVE size can never exceed the
 * deterministic pre-learning size.
 */
class LearningGovernanceEngine {
    companion object {
        const val FEATURE_RECENT = 50
        const val FEATURE_BASELINE = 100
        const val EXECUTION_RECENT = 25
        const val EXECUTION_BASELINE = 50
        const val PERFORMANCE_RECENT = 20
        const val PERFORMANCE_BASELINE = 30
        const val MIN_CALIBRATION_SAMPLES = 15
        const val MIN_MODEL_ATTRIBUTION_SAMPLES = 20
        const val MIN_STABILITY_SAMPLES = 30
    }

    fun assess(
        mode: BotMode,
        snapshots: List<LearningFeatureSnapshotEntity>,
        completedOutcomes: List<TradeEntity>,
        executionRows: List<ExecutionQualityEntity>,
        aiRows: List<AiValueAttributionEntity>
    ): LearningGovernanceAssessment {
        val feature = featureDrift(snapshots)
        val regime = regimeDrift(snapshots)
        val execution = executionDrift(executionRows)
        val performance = performanceDecay(completedOutcomes)
        val calibration = confidenceCalibration(completedOutcomes)
        val stability = parameterStability(completedOutcomes)
        val modelValue = modelAttribution(aiRows)

        val severeDrift =
            feature.severe ||
                regime.severe ||
                execution.severe

        val statisticallyPositive =
            performance.known &&
                performance.recentSamples >= PERFORMANCE_RECENT &&
                performance.recentNetPnl > BigDecimal.ZERO &&
                performance.recentLower95MeanPnl > BigDecimal.ZERO &&
                !performance.decayed &&
                stability.known &&
                stability.stable &&
                calibration.known &&
                !calibration.severe &&
                !execution.severe &&
                !modelValue.defensive

        val rollback =
            severeDrift ||
                (performance.known &&
                    performance.recentSamples >= PERFORMANCE_RECENT &&
                    performance.recentNetPnl < BigDecimal.ZERO &&
                    performance.recentLower95MeanPnl < BigDecimal.ZERO) ||
                (calibration.known && calibration.severe) ||
                modelValue.defensive ||
                (stability.known && !stability.stable)

        val positiveLearning = statisticallyPositive && !rollback

        val stage = when {
            rollback -> LearningGovernanceStage.ROLLBACK
            !performance.known || !calibration.known || !stability.known ->
                LearningGovernanceStage.WARMUP
            mode == BotMode.PAPER && positiveLearning ->
                LearningGovernanceStage.SHADOW_VALIDATED
            mode == BotMode.PAPER ->
                LearningGovernanceStage.PAPER_VALIDATION
            positiveLearning ->
                LearningGovernanceStage.TINY_LIVE
            else ->
                LearningGovernanceStage.PAPER_VALIDATION
        }

        val executionPenalty = when {
            execution.severe -> 1.0
            execution.known && execution.score >= 1.0 -> 0.6
            execution.known && execution.score >= 0.5 -> 0.3
            else -> 0.0
        }

        val fillOffset = (-0.08 * executionPenalty).coerceIn(-0.08, 0.0)
        val staleMultiplier = (1.0 - 0.25 * executionPenalty).coerceIn(0.75, 1.0)
        val amendThreshold = (0.60 - 0.15 * executionPenalty).coerceIn(0.45, 0.60)

        val recentSlippageBps = if (execution.known) {
            recentExecutionMeanPct(executionRows).coerceAtLeast(0.0) * 100.0
        } else 0.0

        val liveCeiling = when {
            rollback -> BigDecimal("0.60")
            !positiveLearning -> BigDecimal("0.85")
            else -> BigDecimal.ONE
        }
        val paperCeiling = when {
            rollback -> BigDecimal("0.75")
            positiveLearning -> BigDecimal("1.10")
            else -> BigDecimal.ONE
        }

        val bounds = LearningExecutionBounds(
            scoreBoostCeiling = if (positiveLearning) 3 else 0,
            liveSizeMultiplierCeiling = liveCeiling,
            paperSizeMultiplierCeiling = paperCeiling,
            fillProbabilityOffset = fillOffset,
            staleTimingMultiplier = staleMultiplier,
            amendFillProbabilityThreshold = amendThreshold,
            slippageSafetyBufferBps = recentSlippageBps.coerceIn(0.0, 25.0)
        )

        val reason = buildString {
            append("M19 learning governance: stage=$stage")
            append(", positiveLearning=$positiveLearning")
            append(", rollback=$rollback")
            append(", feature=${metricLabel(feature)}")
            append(", regime=${metricLabel(regime)}")
            append(", execution=${metricLabel(execution)}")
            append(", performance=${performance.reason}")
            append(", calibration=${calibration.reason}")
            append(", stability=${stability.reason}")
            append(", model=${modelValue.reason}")
            append(", bounds(score+=")
            append(bounds.scoreBoostCeiling)
            append(", liveSize<=")
            append(bounds.liveSizeMultiplierCeiling)
            append(", fillOffset=")
            append("%.3f".format(bounds.fillProbabilityOffset))
            append(", stale×")
            append("%.3f".format(bounds.staleTimingMultiplier))
            append(", amendFill<")
            append("%.3f".format(bounds.amendFillProbabilityThreshold))
            append(", slipBuffer=")
            append("%.2f".format(bounds.slippageSafetyBufferBps))
            append("bps).")
        }

        return LearningGovernanceAssessment(
            stage = stage,
            positiveLearningEnabled = positiveLearning,
            rollbackRequired = rollback,
            featureDrift = feature,
            regimeDrift = regime,
            executionDrift = execution,
            performanceDecay = performance,
            confidenceCalibration = calibration,
            parameterStability = stability,
            modelValue = modelValue,
            bounds = bounds,
            reason = reason
        )
    }

    fun featureDrift(rows: List<LearningFeatureSnapshotEntity>): DriftMetric {
        val sorted = rows.sortedBy { it.timestampEpochMs }
        if (sorted.size < 60) {
            return DriftMetric(
                false, 0.0, minOf(sorted.size, FEATURE_RECENT), 0, false,
                "feature drift unknown: ${sorted.size}/60 snapshots"
            )
        }
        val recent = sorted.takeLast(FEATURE_RECENT)
        val baseline = sorted
            .dropLast(recent.size)
            .takeLast(FEATURE_BASELINE)
        if (recent.size < 20 || baseline.size < 30) {
            return DriftMetric(false, 0.0, recent.size, baseline.size, false, "feature drift unknown: insufficient split")
        }

        val shifts = listOf(
            normalizedShift(
                baseline.map { it.finalScore.toDouble() / 100.0 },
                recent.map { it.finalScore.toDouble() / 100.0 },
                0.05
            ),
            normalizedShift(
                baseline.map { parseFinite(it.spreadPercent) ?: 0.0 },
                recent.map { parseFinite(it.spreadPercent) ?: 0.0 },
                0.02
            ),
            normalizedShift(
                baseline.map { ln1p((parseFinite(it.volume24h) ?: 0.0).coerceAtLeast(0.0)) },
                recent.map { ln1p((parseFinite(it.volume24h) ?: 0.0).coerceAtLeast(0.0)) },
                0.25
            ),
            normalizedShift(
                baseline.map { abs(parseFinite(it.priceChange24hPercent) ?: 0.0) },
                recent.map { abs(parseFinite(it.priceChange24hPercent) ?: 0.0) },
                0.50
            )
        )
        val score = shifts.average().coerceIn(0.0, 5.0)
        return DriftMetric(
            true, score, recent.size, baseline.size,
            severe = score >= 1.50,
            reason = "feature drift normalizedMeanShift=${"%.3f".format(score)}"
        )
    }

    fun regimeDrift(rows: List<LearningFeatureSnapshotEntity>): DriftMetric {
        val tagged = rows.sortedBy { it.timestampEpochMs }
            .mapNotNull { row -> extractRegime(row.reason)?.let { row.timestampEpochMs to it } }
        if (tagged.size < 50) {
            return DriftMetric(
                false, 0.0, minOf(tagged.size, 30), max(0, tagged.size - 30), false,
                "regime drift unknown: ${tagged.size}/50 labeled snapshots"
            )
        }
        val recent = tagged.takeLast(30).map { it.second }
        val baseline = tagged.dropLast(recent.size).takeLast(70).map { it.second }
        if (baseline.size < 20) {
            return DriftMetric(false, 0.0, recent.size, baseline.size, false, "regime drift unknown: insufficient baseline")
        }

        val labels = (recent + baseline).toSet()
        val tv = 0.5 * labels.sumOf { label ->
            abs(
                recent.count { it == label }.toDouble() / recent.size.toDouble() -
                    baseline.count { it == label }.toDouble() / baseline.size.toDouble()
            )
        }
        return DriftMetric(
            true, tv, recent.size, baseline.size,
            severe = tv >= 0.60,
            reason = "regime distribution totalVariation=${"%.3f".format(tv)}"
        )
    }

    fun executionDrift(rows: List<ExecutionQualityEntity>): DriftMetric {
        val valid = rows
            .filter { it.slippagePct.isFinite() }
            .sortedBy { it.timestampEpochMs }
        if (valid.size < 30) {
            return DriftMetric(
                false, 0.0, minOf(valid.size, EXECUTION_RECENT), 0, false,
                "execution drift unknown: ${valid.size}/30 fills"
            )
        }
        val recent = valid.takeLast(EXECUTION_RECENT)
        val baseline = valid.dropLast(recent.size).takeLast(EXECUTION_BASELINE)
        if (recent.size < 10 || baseline.size < 10) {
            return DriftMetric(false, 0.0, recent.size, baseline.size, false, "execution drift unknown: insufficient split")
        }

        val recentMean = recent.map { it.slippagePct }.average()
        val baselineMean = baseline.map { it.slippagePct }.average()
        val recentWorst = recent.maxOf { it.slippagePct }
        val delta = recentMean - baselineMean
        val normalized = (
            max(0.0, delta) / 0.10 +
                max(0.0, recentMean - 0.10) / 0.15 +
                max(0.0, recentWorst - 0.30) / 0.30
            ).coerceIn(0.0, 5.0)

        val severe =
            (recentMean >= 0.20 && delta >= 0.08) ||
                recentWorst >= 0.75

        return DriftMetric(
            true, normalized, recent.size, baseline.size, severe,
            "execution drift recentAvg=${"%.3f".format(recentMean)}%, baselineAvg=${"%.3f".format(baselineMean)}%, worst=${"%.3f".format(recentWorst)}%"
        )
    }

    fun performanceDecay(rows: List<TradeEntity>): PerformanceDecayAssessment {
        val valid = rows.asSequence()
            .filter { it.side.equals("SELL", true) }
            .mapNotNull { trade ->
                trade.realizedPnlEur.toBigDecimalOrNull()?.let { trade.timestampEpochMs to it }
            }
            .filter { it.second.compareTo(BigDecimal.ZERO) != 0 }
            .sortedBy { it.first }
            .toList()

        if (valid.size < 30) {
            return PerformanceDecayAssessment(
                false, minOf(valid.size, PERFORMANCE_RECENT), 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0.0, false,
                "performance decay unknown: ${valid.size}/30 realized exits"
            )
        }

        val recent = valid.takeLast(PERFORMANCE_RECENT).map { it.second }
        val baseline = valid.dropLast(recent.size).takeLast(PERFORMANCE_BASELINE).map { it.second }
        if (recent.size < 10 || baseline.size < 10) {
            return PerformanceDecayAssessment(
                false, recent.size, baseline.size,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0.0, false,
                "performance decay unknown: insufficient split"
            )
        }

        val recentNet = recent.fold(BigDecimal.ZERO, BigDecimal::add)
        val recentMean = mean(recent)
        val baselineMean = mean(baseline)
        val lower95 = lower95Mean(recent)
        val winRate = recent.count { it > BigDecimal.ZERO }.toDouble() / recent.size.toDouble()
        val decayed =
            (recentMean < BigDecimal.ZERO && baselineMean > BigDecimal.ZERO) ||
                recentMean < baselineMean.multiply(BigDecimal("0.50"))

        return PerformanceDecayAssessment(
            true, recent.size, baseline.size,
            recentNet, recentMean, lower95, baselineMean, winRate, decayed,
            "recentN=${recent.size}, net=${recentNet.s4()}, mean=${recentMean.s4()}, lower95=${lower95.s4()}, baselineMean=${baselineMean.s4()}, win=${"%.1f".format(winRate * 100.0)}%, decayed=$decayed"
        )
    }

    fun confidenceCalibration(rows: List<TradeEntity>): ConfidenceCalibrationAssessment {
        val valid = rows.asSequence()
            .filter { it.side.equals("SELL", true) }
            .mapNotNull { trade ->
                val pnl = trade.realizedPnlEur.toBigDecimalOrNull() ?: return@mapNotNull null
                if (pnl.compareTo(BigDecimal.ZERO) == 0) return@mapNotNull null
                val p = (trade.aiScore.coerceIn(0, 100) / 100.0)
                val y = if (pnl > BigDecimal.ZERO) 1.0 else 0.0
                (p - y) * (p - y)
            }
            .toList()
            .takeLastCompat(80)

        if (valid.size < MIN_CALIBRATION_SAMPLES) {
            return ConfidenceCalibrationAssessment(
                false, valid.size, 0.0, false,
                "confidence calibration unknown: ${valid.size}/$MIN_CALIBRATION_SAMPLES exits"
            )
        }

        val brier = valid.average()
        return ConfidenceCalibrationAssessment(
            true, valid.size, brier,
            severe = brier >= 0.32,
            reason = "Brier=${"%.3f".format(brier)} from ${valid.size} realized exits"
        )
    }

    fun parameterStability(rows: List<TradeEntity>): ParameterStabilityAssessment {
        val pnl = rows.asSequence()
            .filter { it.side.equals("SELL", true) }
            .mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }
            .filter { it.compareTo(BigDecimal.ZERO) != 0 }
            .toList()
            .takeLastCompat(90)

        if (pnl.size < MIN_STABILITY_SAMPLES) {
            return ParameterStabilityAssessment(
                false, pnl.size, emptyList(), false,
                "parameter/outcome stability unknown: ${pnl.size}/$MIN_STABILITY_SAMPLES exits"
            )
        }

        val chunkSize = pnl.size / 3
        val chunks = listOf(
            pnl.take(chunkSize),
            pnl.drop(chunkSize).take(chunkSize),
            pnl.drop(chunkSize * 2)
        )
        val means = chunks.map(::mean)
        val signs = means.map {
            when {
                it > BigDecimal.ZERO -> 1
                it < BigDecimal.ZERO -> -1
                else -> 0
            }
        }
        val signFlips = signs.zipWithNext().count { (a, b) -> a != 0 && b != 0 && a != b }
        val stable = signFlips <= 1 && means.count { it > BigDecimal.ZERO } >= 2

        return ParameterStabilityAssessment(
            true, pnl.size, means, stable,
            "3-chunk means=${means.joinToString("/") { it.s4() }}, signFlips=$signFlips, stable=$stable"
        )
    }

    fun modelAttribution(rows: List<AiValueAttributionEntity>): ModelValueAssessment {
        val resolvedRows = rows
            .filter { it.status.equals("RESOLVED", true) }
            .sortedBy { it.resolvedAtEpochMs }
            .takeLastCompat(250)

        val resolved = resolvedRows.mapNotNull { row ->
            val value = row.aiValueAddedQuote.toBigDecimalOrNull() ?: return@mapNotNull null
            val cost = row.totalAiCostQuote.toBigDecimalOrNull() ?: BigDecimal.ZERO
            value.subtract(cost)
        }

        if (resolved.size < MIN_MODEL_ATTRIBUTION_SAMPLES) {
            return ModelValueAssessment(
                false, resolved.size, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, false,
                "AI/model attribution unknown: ${resolved.size}/$MIN_MODEL_ATTRIBUTION_SAMPLES resolved counterfactuals"
            )
        }

        val net = resolved.fold(BigDecimal.ZERO, BigDecimal::add)
        val mean = mean(resolved)
        val lower = lower95Mean(resolved)
        val upper = upper95Mean(resolved)

        val pathAssessments = resolvedRows
            .groupBy { it.modelPath.ifBlank { "UNKNOWN" }.uppercase() }
            .mapNotNull { (path, pathRows) ->
                val values = pathRows.mapNotNull inner@{ row ->
                    val value = row.aiValueAddedQuote.toBigDecimalOrNull() ?: return@inner null
                    val cost = row.totalAiCostQuote.toBigDecimalOrNull() ?: BigDecimal.ZERO
                    value.subtract(cost)
                }
                if (values.size < 10) null
                else Triple(path, lower95Mean(values), upper95Mean(values))
            }

        val badPath = pathAssessments.firstOrNull { (_, _, pathUpper) ->
            pathUpper < BigDecimal.ZERO
        }
        val defensive =
            badPath != null ||
                upper < BigDecimal.ZERO ||
                (net < BigDecimal.ZERO && lower < BigDecimal.ZERO)

        val pathText = if (pathAssessments.isEmpty()) {
            "no model path has >=10 samples"
        } else {
            pathAssessments.joinToString(";") { (path, pathLower, pathUpper) ->
                "$path[${pathLower.s4()},${pathUpper.s4()}]"
            }
        }

        return ModelValueAssessment(
            true, resolved.size, net, mean, lower, upper, defensive,
            "AI/model value N=${resolved.size}, net=${net.s4()}, mean=${mean.s4()}, 95%=[${lower.s4()},${upper.s4()}], paths=$pathText, badPath=${badPath?.first ?: "none"}, defensive=$defensive"
        )
    }

    private fun recentExecutionMeanPct(rows: List<ExecutionQualityEntity>): Double {
        val valid = rows.filter { it.slippagePct.isFinite() }
            .sortedBy { it.timestampEpochMs }
            .takeLast(EXECUTION_RECENT)
        return if (valid.isEmpty()) 0.0 else valid.map { it.slippagePct }.average()
    }

    private fun normalizedShift(
        baseline: List<Double>,
        recent: List<Double>,
        floorSd: Double
    ): Double {
        if (baseline.isEmpty() || recent.isEmpty()) return 0.0
        val bm = baseline.average()
        val rm = recent.average()
        val sd = sampleSd(baseline).coerceAtLeast(floorSd)
        return abs(rm - bm) / sd
    }

    private fun sampleSd(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1).toDouble()
        return sqrt(variance.coerceAtLeast(0.0))
    }

    private fun parseFinite(value: String): Double? =
        value.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun extractRegime(reason: String): String? {
        val match = Regex("""(?i)\bregime\s*[=:]\s*([A-Z_]+)""").find(reason)
            ?: return null
        return match.groupValues.getOrNull(1)?.uppercase()
    }

    private fun mean(values: List<BigDecimal>): BigDecimal =
        if (values.isEmpty()) BigDecimal.ZERO
        else values.fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(values.size), 12, RoundingMode.HALF_UP)

    private fun lower95Mean(values: List<BigDecimal>): BigDecimal {
        if (values.size < 2) return mean(values)
        val m = values.map { it.toDouble() }.average()
        val sd = sampleSd(values.map { it.toDouble() })
        val margin = 2.0 * sd / sqrt(values.size.toDouble())
        return BigDecimal.valueOf(m - margin)
    }

    private fun upper95Mean(values: List<BigDecimal>): BigDecimal {
        if (values.size < 2) return mean(values)
        val m = values.map { it.toDouble() }.average()
        val sd = sampleSd(values.map { it.toDouble() })
        val margin = 2.0 * sd / sqrt(values.size.toDouble())
        return BigDecimal.valueOf(m + margin)
    }

    private fun metricLabel(metric: DriftMetric): String =
        if (metric.known) "${"%.3f".format(metric.score)}${if (metric.severe) "/SEVERE" else ""}"
        else "unknown"

    private fun BigDecimal.coerceIn(min: BigDecimal, max: BigDecimal): BigDecimal = when {
        this < min -> min
        this > max -> max
        else -> this
    }

    private fun BigDecimal.s4(): String =
        setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    private fun <T> List<T>.takeLastCompat(n: Int): List<T> =
        if (size <= n) this else subList(size - n, size)
}
