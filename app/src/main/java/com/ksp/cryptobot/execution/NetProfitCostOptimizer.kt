package com.ksp.cryptobot.execution

import com.ksp.cryptobot.data.AdvancedExecutionEventEntity
import com.ksp.cryptobot.data.ExecutionQualityEntity
import com.ksp.cryptobot.data.GovernanceEventEntity
import com.ksp.cryptobot.data.TradeEntity
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

/**
 * M20 net-profit / cost optimizer.
 *
 * This is deliberately downstream of M5. M5 remains the authoritative probability-
 * weighted trade economics calculation. M20 only asks whether a trade that survived
 * M5 is still worthwhile after measured execution reliability, measured slippage
 * under-estimation, time-of-day degradation, explicit infrastructure cost and scarce
 * trade-slot opportunity cost.
 *
 * Important monotonic invariant:
 *   M20 can HOLD/BLOCK a candidate that M5 allowed.
 *   M20 can never turn an M5 block into an allowed trade.
 *   M20 never increases notional.
 *
 * Costs already charged by M5 (fees, spread, modeled slippage, cloud-AI cost and
 * safety reserve) are never charged twice here.
 */
data class NetProfitCostInput(
    val economics: TradeEconomicsAssessment,
    val recentTrades: List<TradeEntity>,
    val executionQuality: List<ExecutionQualityEntity>,
    val advancedExecution: List<AdvancedExecutionEventEntity>,
    val governanceEvents: List<GovernanceEventEntity>,
    val calibration: ExecutionCalibrationSnapshot? = null,
    val maxTradesPerDay: Int,
    val currentMode: String,
    val nowEpochMs: Long = System.currentTimeMillis(),
    /**
     * M20 never invents infrastructure spend. The caller may supply a measured,
     * attributable per-decision/per-trade quote cost. Zero means no measured cost.
     */
    val explicitInfrastructureCostQuote: BigDecimal = BigDecimal.ZERO
)

data class NetProfitCostAssessment(
    val allowed: Boolean,
    val symbol: String,
    val strategyId: String,
    val notionalQuote: BigDecimal,
    val m5NetExpectedValueQuote: BigDecimal,
    val m5NetExpectedValueRate: BigDecimal,
    val modeledEntrySlippageRate: BigDecimal,
    val observedP75EntrySlippageRate: BigDecimal,
    val incrementalObservedSlippageQuote: BigDecimal,
    val timeBucketIncrementalSlippageQuote: BigDecimal,
    val lifecycleFillReliability: BigDecimal,
    val operationalReliability: BigDecimal,
    val combinedExecutionReliability: BigDecimal,
    val nonExecutionOpportunityCostQuote: BigDecimal,
    val explicitInfrastructureCostQuote: BigDecimal,
    val adjustedNetExpectedValueQuote: BigDecimal,
    val adjustedNetExpectedValueRate: BigDecimal,
    val breakEvenGrossReturnRate: BigDecimal,
    val dailyFilledBuys: Int,
    val tradeSlotUtilization: BigDecimal,
    val opportunityBenchmarkSamples: Int,
    val opportunityBenchmarkRate: BigDecimal,
    val opportunityGapQuote: BigDecimal,
    val empiricalCycleSamples: Int,
    val empiricalMedianCycleMinutes: BigDecimal,
    val adjustedNetEdgePerCapitalHour: BigDecimal,
    val feeSource: String,
    val reason: String,
    val evaluatedAtEpochMs: Long = System.currentTimeMillis()
)

object NetProfitCostRuntime {
    private val latest = ConcurrentHashMap<String, NetProfitCostAssessment>()

    private fun key(symbol: String): String =
        symbol.uppercase().replace("/", "").replace("-", "").replace("_", "")

    fun publish(assessment: NetProfitCostAssessment) {
        latest[key(assessment.symbol)] = assessment
    }

    fun snapshot(symbol: String): NetProfitCostAssessment? = latest[key(symbol)]
    fun all(): List<NetProfitCostAssessment> = latest.values.sortedByDescending { it.evaluatedAtEpochMs }
    fun clearAll() = latest.clear()
}

class NetProfitCostOptimizer {
    companion object {
        private const val MIN_EXECUTION_SAMPLES = 5
        private const val MIN_TIME_BUCKET_SAMPLES = 5
        private const val MIN_LIFECYCLE_ATTEMPTS = 5
        private const val MIN_OPERATIONAL_ATTEMPTS = 5
        private const val MIN_OPPORTUNITY_BENCHMARK_SAMPLES = 5
        private const val MIN_CYCLE_SAMPLES = 3
        private const val LOOKBACK_24H_MS = 24L * 60L * 60L * 1000L
        private const val LOOKBACK_7D_MS = 7L * LOOKBACK_24H_MS
        private val MAX_REASONABLE_RATE = BigDecimal("0.25")
    }

    fun evaluate(input: NetProfitCostInput): NetProfitCostAssessment {
        val economics = input.economics
        val notional = economics.notionalQuote.max(BigDecimal.ZERO)

        if (!economics.allowed || notional <= BigDecimal.ZERO) {
            return publish(
                baseBlocked(
                    input,
                    "M20 monotonic block: M5 did not authorize a positive-net-EV entry."
                )
            )
        }

        val m5NetEv = economics.netExpectedValueQuote
        val m5NetRate = economics.netExpectedValueRate

        val modeledEntrySlipRate = if (notional > BigDecimal.ZERO) {
            economics.entrySlippageQuote
                .max(BigDecimal.ZERO)
                .divide(notional, 12, RoundingMode.HALF_UP)
                .coerceIn(BigDecimal.ZERO, MAX_REASONABLE_RATE)
        } else BigDecimal.ZERO

        val eligibleExecution = input.executionQuality
            .asSequence()
            .filter { normalize(it.symbol) == normalize(economics.symbol) }
            .filter { it.side.equals("BUY", ignoreCase = true) }
            .filter { input.currentMode.isBlank() || it.mode.equals(input.currentMode, ignoreCase = true) }
            .filter { it.expectedPrice > 0.0 && it.actualPrice > 0.0 }
            .sortedByDescending { it.timestampEpochMs }
            .take(100)
            .toList()

        val observedP75Rate = if (eligibleExecution.size >= MIN_EXECUTION_SAMPLES) {
            percentile(
                eligibleExecution
                    .map { BigDecimal.valueOf(it.slippagePct.coerceAtLeast(0.0)).movePointLeft(2) },
                0.75
            ).coerceIn(BigDecimal.ZERO, MAX_REASONABLE_RATE)
        } else BigDecimal.ZERO

        // M5 already charged modeled entry slippage. Only charge the measured excess.
        val excessObservedSlipRate =
            observedP75Rate.subtract(modeledEntrySlipRate).max(BigDecimal.ZERO)
        val incrementalObservedSlippage =
            notional.multiply(excessObservedSlipRate)

        val currentUtcHour = Instant.ofEpochMilli(input.nowEpochMs).atZone(ZoneOffset.UTC).hour
        val sameHour = eligibleExecution.filter {
            Instant.ofEpochMilli(it.timestampEpochMs).atZone(ZoneOffset.UTC).hour == currentUtcHour
        }
        val sameHourP75Rate = if (sameHour.size >= MIN_TIME_BUCKET_SAMPLES) {
            percentile(
                sameHour
                    .map { BigDecimal.valueOf(it.slippagePct.coerceAtLeast(0.0)).movePointLeft(2) },
                0.75
            ).coerceIn(BigDecimal.ZERO, MAX_REASONABLE_RATE)
        } else observedP75Rate

        // Charge only the hour-specific degradation above the already charged overall P75.
        val hourIncrementalRate =
            sameHourP75Rate.subtract(observedP75Rate).max(BigDecimal.ZERO)
        val timeBucketIncrementalSlippage =
            notional.multiply(hourIncrementalRate)

        val lifecycle = input.calibration
        val lifecycleAttempts = if (lifecycle == null) 0 else {
            (lifecycle.samples.toLong() + lifecycle.totalCancels).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val lifecycleReliability = if (
            lifecycle != null &&
            lifecycleAttempts >= MIN_LIFECYCLE_ATTEMPTS &&
            lifecycleAttempts > 0
        ) {
            BigDecimal(lifecycle.samples)
                .divide(BigDecimal(lifecycleAttempts), 12, RoundingMode.HALF_UP)
                .coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
        } else {
            // Unknown evidence must not be invented. M5's safety reserve remains the fallback.
            BigDecimal.ONE
        }

        val since24h = input.nowEpochMs - LOOKBACK_24H_MS
        val symbolEvents = input.governanceEvents
            .filter { normalize(it.symbol) == normalize(economics.symbol) }
            .filter { it.timestampEpochMs >= since24h }

        val operationalErrors = symbolEvents.count {
            it.eventType in setOf("watchdog_error", "order_error", "anomaly_event")
        }
        val submittedPlans = input.advancedExecution.count {
            normalize(it.symbol) == normalize(economics.symbol) &&
                it.timestampEpochMs >= since24h &&
                it.eventType == "entry_plan" &&
                !it.blocked
        }
        val operationalAttempts = operationalErrors + submittedPlans
        val operationalReliability = if (operationalAttempts >= MIN_OPERATIONAL_ATTEMPTS) {
            BigDecimal(submittedPlans)
                .divide(BigDecimal(operationalAttempts), 12, RoundingMode.HALF_UP)
                .coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
        } else {
            BigDecimal.ONE
        }

        val combinedReliability = lifecycleReliability
            .multiply(operationalReliability)
            .coerceIn(BigDecimal.ZERO, BigDecimal.ONE)

        // A non-fill/failure does not pay the modeled trade costs, but it consumes a
        // valid opportunity. Scale only the positive M5 opportunity value by measured
        // execution reliability; do not invent a fee for failed orders.
        val nonExecutionOpportunityCost = m5NetEv.max(BigDecimal.ZERO)
            .multiply(BigDecimal.ONE.subtract(combinedReliability))

        val infrastructureCost = input.explicitInfrastructureCostQuote.max(BigDecimal.ZERO)

        val preOpportunityAdjusted = m5NetEv
            .subtract(incrementalObservedSlippage)
            .subtract(timeBucketIncrementalSlippage)
            .subtract(nonExecutionOpportunityCost)
            .subtract(infrastructureCost)

        val filledBuys24h = input.recentTrades.count {
            normalize(it.symbol).isNotBlank() &&
                it.side.equals("BUY", ignoreCase = true) &&
                it.timestampEpochMs >= since24h
        }
        val dailyCap = input.maxTradesPerDay.coerceAtLeast(1)
        val slotUtilization = BigDecimal(filledBuys24h)
            .divide(BigDecimal(dailyCap), 12, RoundingMode.HALF_UP)
            .coerceIn(BigDecimal.ZERO, BigDecimal("10"))

        val positiveRecentCandidates = input.advancedExecution
            .asSequence()
            .filter { it.eventType == "entry_economics" }
            .filter { !it.blocked }
            .filter { it.timestampEpochMs >= since24h }
            .map { BigDecimal.valueOf(it.multiplier) }
            .filter { it > BigDecimal.ZERO && it < BigDecimal("0.25") }
            .take(100)
            .toList()

        val opportunityBenchmarkRate = if (
            slotUtilization >= BigDecimal("0.75") &&
            positiveRecentCandidates.size >= MIN_OPPORTUNITY_BENCHMARK_SAMPLES
        ) {
            median(positiveRecentCandidates)
        } else BigDecimal.ZERO

        val preOpportunityRate = if (notional > BigDecimal.ZERO) {
            preOpportunityAdjusted.divide(notional, 12, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        // Only when daily trade slots are scarce and enough real M5 candidate evidence
        // exists do we charge the gap to the measured median alternative candidate.
        val opportunityGapRate =
            opportunityBenchmarkRate.subtract(preOpportunityRate).max(BigDecimal.ZERO)
        val opportunityGapQuote =
            if (opportunityBenchmarkRate > BigDecimal.ZERO) {
                notional.multiply(opportunityGapRate)
            } else BigDecimal.ZERO

        val adjustedNetEv = preOpportunityAdjusted.subtract(opportunityGapQuote)
        val adjustedRate = if (notional > BigDecimal.ZERO) {
            adjustedNetEv.divide(notional, 12, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        val incrementalM20Costs = incrementalObservedSlippage
            .add(timeBucketIncrementalSlippage)
            .add(nonExecutionOpportunityCost)
            .add(infrastructureCost)
            .add(opportunityGapQuote)
        val breakEvenGrossReturnRate = economics.totalExpectedCostQuote
            .add(incrementalM20Costs)
            .divide(notional, 12, RoundingMode.HALF_UP)
            .coerceIn(BigDecimal.ZERO, MAX_REASONABLE_RATE)

        val cycleDurations = empiricalCycleMinutes(
            rows = input.recentTrades,
            symbol = economics.symbol,
            sinceEpochMs = input.nowEpochMs - LOOKBACK_7D_MS
        )
        val cycleMedian = if (cycleDurations.size >= MIN_CYCLE_SAMPLES) {
            median(cycleDurations)
        } else BigDecimal.ZERO
        val edgePerCapitalHour = if (cycleMedian > BigDecimal.ZERO) {
            adjustedRate.divide(
                cycleMedian.divide(BigDecimal("60"), 12, RoundingMode.HALF_UP),
                12,
                RoundingMode.HALF_UP
            )
        } else BigDecimal.ZERO

        val allowed = adjustedNetEv > BigDecimal.ZERO &&
            adjustedRate > BigDecimal.ZERO &&
            (opportunityBenchmarkRate <= BigDecimal.ZERO || preOpportunityRate >= opportunityBenchmarkRate)

        val assessment = NetProfitCostAssessment(
            allowed = allowed,
            symbol = normalize(economics.symbol),
            strategyId = economics.strategyId,
            notionalQuote = notional,
            m5NetExpectedValueQuote = m5NetEv,
            m5NetExpectedValueRate = m5NetRate,
            modeledEntrySlippageRate = modeledEntrySlipRate,
            observedP75EntrySlippageRate = observedP75Rate,
            incrementalObservedSlippageQuote = incrementalObservedSlippage,
            timeBucketIncrementalSlippageQuote = timeBucketIncrementalSlippage,
            lifecycleFillReliability = lifecycleReliability,
            operationalReliability = operationalReliability,
            combinedExecutionReliability = combinedReliability,
            nonExecutionOpportunityCostQuote = nonExecutionOpportunityCost,
            explicitInfrastructureCostQuote = infrastructureCost,
            adjustedNetExpectedValueQuote = adjustedNetEv,
            adjustedNetExpectedValueRate = adjustedRate,
            breakEvenGrossReturnRate = breakEvenGrossReturnRate,
            dailyFilledBuys = filledBuys24h,
            tradeSlotUtilization = slotUtilization,
            opportunityBenchmarkSamples = positiveRecentCandidates.size,
            opportunityBenchmarkRate = opportunityBenchmarkRate,
            opportunityGapQuote = opportunityGapQuote,
            empiricalCycleSamples = cycleDurations.size,
            empiricalMedianCycleMinutes = cycleMedian,
            adjustedNetEdgePerCapitalHour = edgePerCapitalHour,
            feeSource = economics.feeSource,
            reason = buildReason(
                allowed = allowed,
                economics = economics,
                executionSamples = eligibleExecution.size,
                observedP75Rate = observedP75Rate,
                excessObservedSlipRate = excessObservedSlipRate,
                sameHourSamples = sameHour.size,
                sameHourP75Rate = sameHourP75Rate,
                lifecycleAttempts = lifecycleAttempts,
                lifecycleReliability = lifecycleReliability,
                operationalAttempts = operationalAttempts,
                operationalReliability = operationalReliability,
                combinedReliability = combinedReliability,
                incrementalObservedSlippage = incrementalObservedSlippage,
                timeBucketIncrementalSlippage = timeBucketIncrementalSlippage,
                nonExecutionOpportunityCost = nonExecutionOpportunityCost,
                infrastructureCost = infrastructureCost,
                adjustedNetEv = adjustedNetEv,
                adjustedRate = adjustedRate,
                breakEvenGrossReturnRate = breakEvenGrossReturnRate,
                filledBuys24h = filledBuys24h,
                dailyCap = dailyCap,
                slotUtilization = slotUtilization,
                benchmarkSamples = positiveRecentCandidates.size,
                benchmarkRate = opportunityBenchmarkRate,
                opportunityGapQuote = opportunityGapQuote,
                cycleSamples = cycleDurations.size,
                cycleMedian = cycleMedian,
                edgePerCapitalHour = edgePerCapitalHour
            )
        )
        return publish(assessment)
    }

    private fun baseBlocked(input: NetProfitCostInput, why: String): NetProfitCostAssessment {
        val e = input.economics
        return NetProfitCostAssessment(
            allowed = false,
            symbol = normalize(e.symbol),
            strategyId = e.strategyId,
            notionalQuote = e.notionalQuote.max(BigDecimal.ZERO),
            m5NetExpectedValueQuote = e.netExpectedValueQuote,
            m5NetExpectedValueRate = e.netExpectedValueRate,
            modeledEntrySlippageRate = BigDecimal.ZERO,
            observedP75EntrySlippageRate = BigDecimal.ZERO,
            incrementalObservedSlippageQuote = BigDecimal.ZERO,
            timeBucketIncrementalSlippageQuote = BigDecimal.ZERO,
            lifecycleFillReliability = BigDecimal.ONE,
            operationalReliability = BigDecimal.ONE,
            combinedExecutionReliability = BigDecimal.ONE,
            nonExecutionOpportunityCostQuote = BigDecimal.ZERO,
            explicitInfrastructureCostQuote = BigDecimal.ZERO,
            adjustedNetExpectedValueQuote = e.netExpectedValueQuote.min(BigDecimal.ZERO),
            adjustedNetExpectedValueRate = e.netExpectedValueRate.min(BigDecimal.ZERO),
            breakEvenGrossReturnRate = BigDecimal.ZERO,
            dailyFilledBuys = 0,
            tradeSlotUtilization = BigDecimal.ZERO,
            opportunityBenchmarkSamples = 0,
            opportunityBenchmarkRate = BigDecimal.ZERO,
            opportunityGapQuote = BigDecimal.ZERO,
            empiricalCycleSamples = 0,
            empiricalMedianCycleMinutes = BigDecimal.ZERO,
            adjustedNetEdgePerCapitalHour = BigDecimal.ZERO,
            feeSource = e.feeSource,
            reason = why
        )
    }

    private fun empiricalCycleMinutes(
        rows: List<TradeEntity>,
        symbol: String,
        sinceEpochMs: Long
    ): List<BigDecimal> {
        val ordered = rows
            .filter { normalize(it.symbol) == normalize(symbol) && it.timestampEpochMs >= sinceEpochMs }
            .sortedBy { it.timestampEpochMs }

        var oldestOpenBuyMs: Long? = null
        val durations = mutableListOf<BigDecimal>()
        for (row in ordered) {
            when {
                row.side.equals("BUY", ignoreCase = true) && oldestOpenBuyMs == null -> {
                    oldestOpenBuyMs = row.timestampEpochMs
                }
                row.side.equals("SELL", ignoreCase = true) &&
                    oldestOpenBuyMs != null &&
                    (row.realizedPnlEur.toBigDecimalOrNull() ?: BigDecimal.ZERO) != BigDecimal.ZERO -> {
                    val durationMs = (row.timestampEpochMs - oldestOpenBuyMs).coerceAtLeast(0L)
                    durations += BigDecimal(durationMs)
                        .divide(BigDecimal("60000"), 8, RoundingMode.HALF_UP)
                    oldestOpenBuyMs = null
                }
            }
        }
        return durations
    }

    private fun percentile(values: List<BigDecimal>, quantile: Double): BigDecimal {
        if (values.isEmpty()) return BigDecimal.ZERO
        val sorted = values.sorted()
        val q = quantile.coerceIn(0.0, 1.0)
        val index = (ceil(q * sorted.size.toDouble()).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun median(values: List<BigDecimal>): BigDecimal {
        if (values.isEmpty()) return BigDecimal.ZERO
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            sorted[middle - 1].add(sorted[middle])
                .divide(BigDecimal("2"), 12, RoundingMode.HALF_UP)
        }
    }

    private fun buildReason(
        allowed: Boolean,
        economics: TradeEconomicsAssessment,
        executionSamples: Int,
        observedP75Rate: BigDecimal,
        excessObservedSlipRate: BigDecimal,
        sameHourSamples: Int,
        sameHourP75Rate: BigDecimal,
        lifecycleAttempts: Int,
        lifecycleReliability: BigDecimal,
        operationalAttempts: Int,
        operationalReliability: BigDecimal,
        combinedReliability: BigDecimal,
        incrementalObservedSlippage: BigDecimal,
        timeBucketIncrementalSlippage: BigDecimal,
        nonExecutionOpportunityCost: BigDecimal,
        infrastructureCost: BigDecimal,
        adjustedNetEv: BigDecimal,
        adjustedRate: BigDecimal,
        breakEvenGrossReturnRate: BigDecimal,
        filledBuys24h: Int,
        dailyCap: Int,
        slotUtilization: BigDecimal,
        benchmarkSamples: Int,
        benchmarkRate: BigDecimal,
        opportunityGapQuote: BigDecimal,
        cycleSamples: Int,
        cycleMedian: BigDecimal,
        edgePerCapitalHour: BigDecimal
    ): String = buildString {
        append(if (allowed) "M20 ALLOW" else "M20 BLOCK")
        append(": M5 netEV=")
        append(economics.netExpectedValueQuote.s6())
        append(" (")
        append(economics.netExpectedValueRate.pct4())
        append("), feeSource=")
        append(economics.feeSource)
        append(", M5 AI cost already included=")
        append(economics.externalDecisionCostQuote.s6())
        append("; observed BUY slippage p75=")
        append(observedP75Rate.pct4())
        append(" samples=")
        append(executionSamples)
        append(", excess-vs-M5=")
        append(excessObservedSlipRate.pct4())
        append(" cost=")
        append(incrementalObservedSlippage.s6())
        append("; UTC-hour p75=")
        append(sameHourP75Rate.pct4())
        append(" hourSamples=")
        append(sameHourSamples)
        append(" incrementalHourCost=")
        append(timeBucketIncrementalSlippage.s6())
        append("; lifecycleReliability=")
        append(lifecycleReliability.s4())
        append(" attempts=")
        append(lifecycleAttempts)
        append(", operationalReliability=")
        append(operationalReliability.s4())
        append(" attempts=")
        append(operationalAttempts)
        append(", combined=")
        append(combinedReliability.s4())
        append(", nonExecutionOpportunityCost=")
        append(nonExecutionOpportunityCost.s6())
        append("; explicitInfraCost=")
        append(infrastructureCost.s6())
        append(" (zero means unmeasured/not invented)")
        append("; tradeSlots=")
        append(filledBuys24h)
        append("/")
        append(dailyCap)
        append(" util=")
        append(slotUtilization.s4())
        append(", benchmark=")
        append(benchmarkRate.pct4())
        append(" samples=")
        append(benchmarkSamples)
        append(", opportunityGap=")
        append(opportunityGapQuote.s6())
        append("; empiricalCycle=")
        append(cycleMedian.s4())
        append("min samples=")
        append(cycleSamples)
        append(", adjustedEdgePerCapitalHour=")
        append(edgePerCapitalHour.pct4())
        append("; adjustedNetEV=")
        append(adjustedNetEv.s6())
        append(" (")
        append(adjustedRate.pct4())
        append("), breakEvenGrossReturn=")
        append(breakEvenGrossReturnRate.pct4())
        append(". No M5 cost is double-counted and M20 never increases size.")
    }

    private fun publish(value: NetProfitCostAssessment): NetProfitCostAssessment {
        NetProfitCostRuntime.publish(value)
        return value
    }

    private fun normalize(value: String): String =
        value.uppercase().replace("/", "").replace("-", "").replace("_", "")

    private fun BigDecimal.s4(): String = setScale(4, RoundingMode.HALF_UP).toPlainString()
    private fun BigDecimal.s6(): String = setScale(6, RoundingMode.HALF_UP).toPlainString()
    private fun BigDecimal.pct4(): String =
        multiply(BigDecimal("100")).setScale(4, RoundingMode.HALF_UP).toPlainString() + "%"

    private fun BigDecimal.coerceIn(min: BigDecimal, max: BigDecimal): BigDecimal = when {
        this < min -> min
        this > max -> max
        else -> this
    }
}
