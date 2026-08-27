package com.ksp.cryptobot.intelligence

import com.ksp.cryptobot.core.AiDecision
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.SignalAction
import com.ksp.cryptobot.core.Timeframe
import com.ksp.cryptobot.data.AiValueAttributionEntity
import com.ksp.cryptobot.data.GovernanceDao
import com.ksp.cryptobot.exchange.CryptoExchangeClient
import com.ksp.cryptobot.execution.TradeEconomicsAssessment
import java.math.BigDecimal
import java.math.RoundingMode

data class AiPathOutcome(
    val deterministicNetPnlQuote: BigDecimal,
    val lunaNetPnlQuote: BigDecimal,
    val finalNetPnlQuote: BigDecimal,
    val lunaValueAddedQuote: BigDecimal,
    val solIncrementalValueQuote: BigDecimal,
    val aiValueAddedQuote: BigDecimal,
    val avoidedLossQuote: BigDecimal,
    val missedProfitQuote: BigDecimal,
    val aiGeneratedProfitQuote: BigDecimal
)

data class AiValueAttributionSummary(
    val openCounterfactuals: Int,
    val resolvedCounterfactuals: Int,
    val lunaResolved: Int,
    val solResolved: Int,
    val totalAiCostQuote: BigDecimal,
    val deterministicNetPnlQuote: BigDecimal,
    val lunaNetPnlQuote: BigDecimal,
    val lunaSolNetPnlQuote: BigDecimal,
    val lunaValueAddedQuote: BigDecimal,
    val solIncrementalValueQuote: BigDecimal,
    val aiValueAddedQuote: BigDecimal,
    val avoidedLossQuote: BigDecimal,
    val missedProfitQuote: BigDecimal,
    val aiGeneratedProfitQuote: BigDecimal,
    val aiRoi: BigDecimal?,
    val lunaRoi: BigDecimal?,
    val solIncrementalRoi: BigDecimal?,
    val verdict: String
)

class AiValueAttributionEngine(
    private val governanceDao: GovernanceDao
) {
    companion object {
        const val SHADOW_HORIZON_MINUTES = 240
        private val KRAKEN_TAKER_ROUND_TRIP = BigDecimal("0.0160")
        private val DEFAULT_SLIPPAGE_RESERVE = BigDecimal("0.0020")

        fun effectiveMultiplier(verdict: CloudAiVerdict, multiplier: BigDecimal): BigDecimal =
            if (verdict == CloudAiVerdict.REJECT) {
                BigDecimal.ZERO
            } else {
                multiplier.coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
            }

        fun calculatePathOutcome(
            deterministicNotionalQuote: BigDecimal,
            netReturnRate: BigDecimal,
            lunaVerdict: CloudAiVerdict,
            lunaRiskMultiplier: BigDecimal,
            finalVerdict: CloudAiVerdict,
            finalRiskMultiplier: BigDecimal,
            lunaCostQuote: BigDecimal,
            solCostQuote: BigDecimal
        ): AiPathOutcome {
            val baseline = deterministicNotionalQuote.max(BigDecimal.ZERO)
            val deterministic = baseline.multiply(netReturnRate)

            val lunaSize = baseline.multiply(effectiveMultiplier(lunaVerdict, lunaRiskMultiplier))
            val lunaNet = lunaSize.multiply(netReturnRate).subtract(lunaCostQuote.max(BigDecimal.ZERO))

            val finalSize = baseline.multiply(effectiveMultiplier(finalVerdict, finalRiskMultiplier))
            val totalAiCost = lunaCostQuote.max(BigDecimal.ZERO).add(solCostQuote.max(BigDecimal.ZERO))
            val finalNet = finalSize.multiply(netReturnRate).subtract(totalAiCost)

            val lunaValue = lunaNet.subtract(deterministic)
            val solIncremental = finalNet.subtract(lunaNet)
            val totalValue = finalNet.subtract(deterministic)

            val avoidedLoss = if (deterministic < BigDecimal.ZERO && totalValue > BigDecimal.ZERO) {
                totalValue
            } else BigDecimal.ZERO

            val missedProfit = if (deterministic > BigDecimal.ZERO && totalValue < BigDecimal.ZERO) {
                totalValue.abs()
            } else BigDecimal.ZERO

            // M6 is veto/reduce-only. Positive AI alpha therefore normally comes from
            // avoided losses, not from inventing additional profitable exposure.
            val generatedProfit = if (deterministic >= BigDecimal.ZERO && totalValue > BigDecimal.ZERO) {
                totalValue
            } else BigDecimal.ZERO

            return AiPathOutcome(
                deterministicNetPnlQuote = deterministic,
                lunaNetPnlQuote = lunaNet,
                finalNetPnlQuote = finalNet,
                lunaValueAddedQuote = lunaValue,
                solIncrementalValueQuote = solIncremental,
                aiValueAddedQuote = totalValue,
                avoidedLossQuote = avoidedLoss,
                missedProfitQuote = missedProfit,
                aiGeneratedProfitQuote = generatedProfit
            )
        }

        data class ExitResolution(val price: BigDecimal, val reason: String)

        fun resolveExit(
            createdAtEpochMs: Long,
            horizonMinutes: Int,
            targetPrice: BigDecimal,
            stopPrice: BigDecimal,
            candles: List<Candle>,
            fallbackPrice: BigDecimal
        ): ExitResolution {
            val endMs = createdAtEpochMs + horizonMinutes.coerceAtLeast(1) * 60_000L
            val path = candles.asSequence()
                .filter { it.openTimeEpochMs >= createdAtEpochMs && it.openTimeEpochMs <= endMs }
                .sortedBy { it.openTimeEpochMs }
                .toList()

            path.forEach { candle ->
                val hitStop = stopPrice > BigDecimal.ZERO && candle.low <= stopPrice
                val hitTarget = targetPrice > BigDecimal.ZERO && candle.high >= targetPrice

                if (hitStop && hitTarget) {
                    return ExitResolution(stopPrice, "STOP_AND_TARGET_SAME_M1_CANDLE_CONSERVATIVE_STOP_FIRST")
                }
                if (hitStop) return ExitResolution(stopPrice, "STOP_HIT_M1_PATH")
                if (hitTarget) return ExitResolution(targetPrice, "TARGET_HIT_M1_PATH")
            }

            val horizonClose = path.lastOrNull()?.close?.takeIf { it > BigDecimal.ZERO }
            return ExitResolution(
                price = horizonClose ?: fallbackPrice.max(BigDecimal.ZERO),
                reason = if (horizonClose != null) "HORIZON_M1_CLOSE" else "HORIZON_FALLBACK_CURRENT_TICKER"
            )
        }

        private fun BigDecimal.coerceIn(lo: BigDecimal, hi: BigDecimal): BigDecimal = when {
            this < lo -> lo
            this > hi -> hi
            else -> this
        }
    }

    suspend fun beginCloudReview(
        deterministicDecision: AiDecision,
        review: CloudAiReview,
        ticker: MarketTicker,
        settings: BotSettings,
        strategy: String,
        regime: String
    ) {
        if (review.lunaUsage == null) return
        if (!deterministicDecision.allowedToTrade) return
        if (deterministicDecision.finalAction !in setOf(SignalAction.BUY, SignalAction.SMALL_BUY)) return
        if (ticker.ask <= BigDecimal.ZERO) return

        val existing = governanceDao.aiAttributionByFingerprint(review.fingerprint)
        if (existing != null) return

        val baseline = if (deterministicDecision.finalAction == SignalAction.SMALL_BUY) {
            settings.maxPositionEur.multiply(BigDecimal("0.50"))
        } else {
            settings.maxPositionEur
        }.max(BigDecimal.ZERO)

        val entry = ticker.ask
        val target = entry.multiply(
            BigDecimal.ONE.add(
                settings.takeProfitPercent.divide(BigDecimal("100"), 12, RoundingMode.HALF_UP)
            )
        )
        val stop = entry.multiply(
            BigDecimal.ONE.subtract(
                settings.stopLossPercent.divide(BigDecimal("100"), 12, RoundingMode.HALF_UP)
            )
        )
        val spreadRate = if (ticker.lastPrice > BigDecimal.ZERO) {
            ticker.ask.subtract(ticker.bid).abs()
                .divide(ticker.lastPrice, 12, RoundingMode.HALF_UP)
                .coerceIn(BigDecimal.ZERO, BigDecimal("0.05"))
        } else BigDecimal("0.05")

        val fallbackRoundTripCost = KRAKEN_TAKER_ROUND_TRIP
            .add(spreadRate)
            .add(DEFAULT_SLIPPAGE_RESERVE)

        val now = System.currentTimeMillis()
        governanceDao.upsertAiAttribution(
            AiValueAttributionEntity(
                fingerprint = review.fingerprint,
                createdAtEpochMs = now,
                updatedAtEpochMs = now,
                symbol = normalize(deterministicDecision.symbol),
                strategy = strategy.ifBlank { settings.strategyMode.name },
                regime = regime.ifBlank { "UNKNOWN" },
                modelPath = review.modelPath,
                deterministicAction = deterministicDecision.finalAction.name,
                deterministicNotionalQuote = baseline.toPlainString(),
                lunaVerdict = review.lunaVerdict.name,
                lunaRiskMultiplier = review.lunaRiskMultiplier.toPlainString(),
                finalVerdict = review.verdict.name,
                finalRiskMultiplier = review.riskMultiplier.toPlainString(),
                entryPrice = entry.toPlainString(),
                targetPrice = target.toPlainString(),
                stopPrice = stop.toPlainString(),
                horizonMinutes = SHADOW_HORIZON_MINUTES,
                estimatedRoundTripCostRate = fallbackRoundTripCost.toPlainString(),
                lunaCostQuote = (review.lunaUsage?.costQuote ?: BigDecimal.ZERO).toPlainString(),
                solCostQuote = (review.solUsage?.costQuote ?: BigDecimal.ZERO).toPlainString(),
                totalAiCostQuote = review.totalCostQuote.toPlainString()
            )
        )
    }

    suspend fun linkExecutionEconomics(
        fingerprint: String,
        deterministicNotionalQuote: BigDecimal,
        assessment: TradeEconomicsAssessment
    ) {
        val row = governanceDao.aiAttributionByFingerprint(fingerprint) ?: return
        if (row.status != "OPEN") return

        val notional = assessment.notionalQuote
        if (notional <= BigDecimal.ZERO) return

        val modeledTradingCosts = assessment.totalExpectedCostQuote
            .subtract(assessment.externalDecisionCostQuote)
            .subtract(assessment.safetyReserveQuote)
            .max(BigDecimal.ZERO)

        val rate = modeledTradingCosts
            .divide(notional, 12, RoundingMode.HALF_UP)
            .coerceIn(BigDecimal.ZERO, BigDecimal("0.25"))

        val entry = assessment.notionalQuote
            .divide(assessment.quantity, 16, RoundingMode.HALF_UP)
        val target = entry.add(
            assessment.expectedWinQuote.divide(assessment.quantity, 16, RoundingMode.HALF_UP)
        )
        val stop = entry.subtract(
            assessment.expectedLossQuote.divide(assessment.quantity, 16, RoundingMode.HALF_UP)
        ).max(BigDecimal.ZERO)

        governanceDao.upsertAiAttribution(
            row.copy(
                updatedAtEpochMs = System.currentTimeMillis(),
                deterministicNotionalQuote = deterministicNotionalQuote.max(BigDecimal.ZERO).toPlainString(),
                entryPrice = entry.toPlainString(),
                targetPrice = target.toPlainString(),
                stopPrice = stop.toPlainString(),
                estimatedRoundTripCostRate = rate.toPlainString()
            )
        )
    }

    suspend fun settleDueForSymbol(
        exchange: CryptoExchangeClient,
        ticker: MarketTicker,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Int {
        val symbol = normalize(ticker.symbol)
        val open = governanceDao.openAiAttributionForSymbol(symbol)
        val due = open.filter {
            nowEpochMs >= it.createdAtEpochMs + it.horizonMinutes.coerceAtLeast(1) * 60_000L
        }
        if (due.isEmpty()) return 0

        val candles = runCatching {
            exchange.getCandles(symbol, Timeframe.M1, 720)
        }.getOrDefault(emptyList())

        var resolved = 0
        due.forEach { row ->
            val entry = row.entryPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val target = row.targetPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val stop = row.stopPrice.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val friction = row.estimatedRoundTripCostRate.toBigDecimalOrNull()
                ?.coerceIn(BigDecimal.ZERO, BigDecimal("0.25"))
                ?: BigDecimal.ZERO
            if (entry <= BigDecimal.ZERO) return@forEach

            val exit = resolveExit(
                createdAtEpochMs = row.createdAtEpochMs,
                horizonMinutes = row.horizonMinutes,
                targetPrice = target,
                stopPrice = stop,
                candles = candles,
                fallbackPrice = ticker.lastPrice
            )
            if (exit.price <= BigDecimal.ZERO) return@forEach

            val grossReturnRate = exit.price.subtract(entry)
                .divide(entry, 12, RoundingMode.HALF_UP)
            val netReturnRate = grossReturnRate.subtract(friction)

            val lunaVerdict = runCatching { CloudAiVerdict.valueOf(row.lunaVerdict) }
                .getOrDefault(CloudAiVerdict.ABSTAIN)
            val finalVerdict = runCatching { CloudAiVerdict.valueOf(row.finalVerdict) }
                .getOrDefault(CloudAiVerdict.ABSTAIN)

            val outcome = calculatePathOutcome(
                deterministicNotionalQuote = row.deterministicNotionalQuote.toBigDecimalOrNull()
                    ?: BigDecimal.ZERO,
                netReturnRate = netReturnRate,
                lunaVerdict = lunaVerdict,
                lunaRiskMultiplier = row.lunaRiskMultiplier.toBigDecimalOrNull()
                    ?: BigDecimal.ONE,
                finalVerdict = finalVerdict,
                finalRiskMultiplier = row.finalRiskMultiplier.toBigDecimalOrNull()
                    ?: BigDecimal.ONE,
                lunaCostQuote = row.lunaCostQuote.toBigDecimalOrNull()
                    ?: BigDecimal.ZERO,
                solCostQuote = row.solCostQuote.toBigDecimalOrNull()
                    ?: BigDecimal.ZERO
            )

            governanceDao.upsertAiAttribution(
                row.copy(
                    updatedAtEpochMs = nowEpochMs,
                    resolvedAtEpochMs = nowEpochMs,
                    status = "RESOLVED",
                    resolution = exit.reason,
                    exitPrice = exit.price.toPlainString(),
                    deterministicNetPnlQuote = outcome.deterministicNetPnlQuote.toPlainString(),
                    lunaNetPnlQuote = outcome.lunaNetPnlQuote.toPlainString(),
                    finalNetPnlQuote = outcome.finalNetPnlQuote.toPlainString(),
                    lunaValueAddedQuote = outcome.lunaValueAddedQuote.toPlainString(),
                    solIncrementalValueQuote = outcome.solIncrementalValueQuote.toPlainString(),
                    aiValueAddedQuote = outcome.aiValueAddedQuote.toPlainString(),
                    avoidedLossQuote = outcome.avoidedLossQuote.toPlainString(),
                    missedProfitQuote = outcome.missedProfitQuote.toPlainString(),
                    aiGeneratedProfitQuote = outcome.aiGeneratedProfitQuote.toPlainString()
                )
            )
            resolved += 1
        }
        return resolved
    }

    suspend fun summary(limit: Int = 5000): AiValueAttributionSummary {
        val rows = governanceDao.resolvedAiAttributions(limit)
        val open = governanceDao.openAiAttributionCount()

        fun sum(selector: (AiValueAttributionEntity) -> String): BigDecimal =
            rows.fold(BigDecimal.ZERO) { total, row ->
                total.add(selector(row).toBigDecimalOrNull() ?: BigDecimal.ZERO)
            }

        val aiCost = sum { it.totalAiCostQuote }
        val lunaCost = sum { it.lunaCostQuote }
        val solCost = sum { it.solCostQuote }
        val deterministic = sum { it.deterministicNetPnlQuote }
        val lunaNet = sum { it.lunaNetPnlQuote }
        val finalNet = sum { it.finalNetPnlQuote }
        val lunaValue = sum { it.lunaValueAddedQuote }
        val solValue = sum { it.solIncrementalValueQuote }
        val aiValue = sum { it.aiValueAddedQuote }
        val avoided = sum { it.avoidedLossQuote }
        val missed = sum { it.missedProfitQuote }
        val generated = sum { it.aiGeneratedProfitQuote }
        val solRows = rows.count { (it.solCostQuote.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO }
        val lunaRows = rows.count { (it.lunaCostQuote.toBigDecimalOrNull() ?: BigDecimal.ZERO) > BigDecimal.ZERO }

        val roi = ratio(aiValue, aiCost)
        val lunaRoi = ratio(lunaValue, lunaCost)
        val solRoi = ratio(solValue, solCost)

        val verdict = when {
            rows.size < 20 -> "INSUFFICIENT_DATA"
            aiValue <= BigDecimal.ZERO -> "DISABLE_CLOUD_AI_RECOMMENDED"
            solRows >= 10 && solValue <= BigDecimal.ZERO -> "KEEP_LUNA_DISABLE_SOL_RECOMMENDED"
            else -> "KEEP_SELECTIVE_AI"
        }

        return AiValueAttributionSummary(
            openCounterfactuals = open,
            resolvedCounterfactuals = rows.size,
            lunaResolved = lunaRows,
            solResolved = solRows,
            totalAiCostQuote = aiCost,
            deterministicNetPnlQuote = deterministic,
            lunaNetPnlQuote = lunaNet,
            lunaSolNetPnlQuote = finalNet,
            lunaValueAddedQuote = lunaValue,
            solIncrementalValueQuote = solValue,
            aiValueAddedQuote = aiValue,
            avoidedLossQuote = avoided,
            missedProfitQuote = missed,
            aiGeneratedProfitQuote = generated,
            aiRoi = roi,
            lunaRoi = lunaRoi,
            solIncrementalRoi = solRoi,
            verdict = verdict
        )
    }

    suspend fun recent(limit: Int = 100): List<AiValueAttributionEntity> =
        governanceDao.recentAiAttributions(limit.coerceIn(1, 1000))

    private fun ratio(value: BigDecimal, cost: BigDecimal): BigDecimal? =
        if (cost > BigDecimal.ZERO) {
            value.divide(cost, 6, RoundingMode.HALF_UP)
        } else null

    private fun normalize(symbol: String): String =
        symbol.uppercase().replace("/", "").replace("-", "").replace("_", "")

    private fun BigDecimal.coerceIn(lo: BigDecimal, hi: BigDecimal): BigDecimal = when {
        this < lo -> lo
        this > hi -> hi
        else -> this
    }
}
