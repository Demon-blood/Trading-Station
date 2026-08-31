package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.Timeframe
import com.ksp.cryptobot.data.PositionEntity
import com.ksp.cryptobot.exchange.CryptoExchangeClient
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

data class PortfolioCorrelationObservation(
    val symbol: String,
    val pairedSamples: Int,
    val correlation: Double?,
    val factorGroup: String,
    val positionNotionalQuote: BigDecimal
)

data class PortfolioCorrelationContext(
    val candidateSymbol: String,
    val accountEquityQuote: BigDecimal,
    val freeCashQuote: BigDecimal,
    val requiredCashReserveQuote: BigDecimal,
    val availableNewSpendQuote: BigDecimal,
    val grossOpenExposureQuote: BigDecimal,
    val candidateFactorGroup: String,
    val factorExposureQuote: BigDecimal,
    val singleAssetExposureQuote: BigDecimal,
    val maxPositiveCorrelation: Double?,
    val correlationSampleCount: Int,
    val correlationMultiplier: BigDecimal,
    val singleAssetRemainingQuote: BigDecimal?,
    val factorRemainingQuote: BigDecimal?,
    val observations: List<PortfolioCorrelationObservation>,
    val reason: String
)

object PortfolioCorrelationMath {
    const val MIN_PAIRED_RETURNS = 30
    val COMMON_FACTOR_MAX_SHARE: BigDecimal = BigDecimal("0.70")

    fun returnsByTimestamp(candles: List<Candle>): Map<Long, Double> {
        val sorted = candles
            .filter { it.close > BigDecimal.ZERO }
            .sortedBy { it.openTimeEpochMs }
        if (sorted.size < 2) return emptyMap()

        val out = LinkedHashMap<Long, Double>()
        for (i in 1 until sorted.size) {
            val previous = sorted[i - 1].close
            val current = sorted[i].close
            if (previous <= BigDecimal.ZERO || current <= BigDecimal.ZERO) continue
            val r = current.subtract(previous)
                .divide(previous, 16, RoundingMode.HALF_UP)
                .toDouble()
            if (r.isFinite()) out[sorted[i].openTimeEpochMs] = r
        }
        return out
    }

    fun pearson(
        left: Map<Long, Double>,
        right: Map<Long, Double>,
        minSamples: Int = MIN_PAIRED_RETURNS
    ): Pair<Double?, Int> {
        val common = left.keys.intersect(right.keys).sorted()
        if (common.size < minSamples) return null to common.size

        val xs = common.map { left.getValue(it) }
        val ys = common.map { right.getValue(it) }
        val xMean = xs.average()
        val yMean = ys.average()

        var covariance = 0.0
        var xVariance = 0.0
        var yVariance = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - xMean
            val dy = ys[i] - yMean
            covariance += dx * dy
            xVariance += dx * dx
            yVariance += dy * dy
        }
        val denominator = sqrt(xVariance * yVariance)
        if (!denominator.isFinite() || denominator <= 1e-18) return null to common.size
        return (covariance / denominator).coerceIn(-1.0, 1.0) to common.size
    }

    fun correlationMultiplier(maxPositiveCorrelation: Double?): BigDecimal = when {
        maxPositiveCorrelation == null -> BigDecimal.ONE
        maxPositiveCorrelation >= 0.90 -> BigDecimal("0.40")
        maxPositiveCorrelation >= 0.80 -> BigDecimal("0.60")
        maxPositiveCorrelation >= 0.70 -> BigDecimal("0.80")
        else -> BigDecimal.ONE
    }

    fun reserveRequired(
        accountEquityQuote: BigDecimal,
        minimumAbsoluteQuote: BigDecimal,
        minimumQuoteReservePercent: BigDecimal,
        minimumEurReservePercent: BigDecimal
    ): BigDecimal {
        if (accountEquityQuote <= BigDecimal.ZERO) return minimumAbsoluteQuote.max(BigDecimal.ZERO)
        val pct = minimumQuoteReservePercent.max(minimumEurReservePercent)
            .coerceIn(BigDecimal.ZERO, BigDecimal("100"))
        val pctAmount = accountEquityQuote
            .multiply(pct)
            .divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)
        return minimumAbsoluteQuote.max(pctAmount).max(BigDecimal.ZERO)
    }

    fun remainingCap(
        accountEquityQuote: BigDecimal,
        currentExposureQuote: BigDecimal,
        maxShare: BigDecimal
    ): BigDecimal? {
        if (accountEquityQuote <= BigDecimal.ZERO) return null
        val share = maxShare.coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
        return accountEquityQuote.multiply(share)
            .subtract(currentExposureQuote)
            .max(BigDecimal.ZERO)
    }

    fun factorGroup(symbol: String): String {
        val base = baseAsset(symbol)
        return when {
            base == "BTC" || base == "XBT" -> "BTC_CORE"
            base == "ETH" -> "ETH_CORE"
            base in setOf(
                "EUR", "USD", "GBP", "CHF",
                "USDT", "USDC", "DAI", "EURC", "EURT", "PYUSD"
            ) -> "CASH_STABLE"
            else -> "ALT_RISK"
        }
    }

    fun baseAsset(symbol: String): String {
        val upper = symbol.uppercase().replace("/", "").replace("-", "")
        val quotes = listOf("USDT", "USDC", "EUR", "USD", "GBP", "CHF", "BTC", "XBT", "ETH")
        return quotes.firstOrNull { upper.endsWith(it) && upper.length > it.length }
            ?.let { upper.removeSuffix(it) }
            ?: upper.take(6)
    }

    private fun BigDecimal.coerceIn(min: BigDecimal, max: BigDecimal): BigDecimal = when {
        this < min -> min
        this > max -> max
        else -> this
    }
}

/**
 * M17 account-aware portfolio context.
 *
 * - Balance/equity is read fresh on every assessment.
 * - H1 return series are cached briefly because correlation does not need per-tick refresh.
 * - Pearson correlation requires >=30 timestamp-aligned returns.
 * - Missing correlation evidence never becomes a fake numeric coefficient.
 */
class PortfolioCorrelationEngine {
    companion object {
        private const val RETURN_CACHE_MS = 5 * 60 * 1000L
        private const val CANDLE_LIMIT = 121
    }

    private data class CachedReturns(
        val loadedAtMs: Long,
        val returns: Map<Long, Double>
    )

    private val returnCache = ConcurrentHashMap<String, CachedReturns>()

    suspend fun assess(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        candidateSymbol: String,
        requestedQuote: BigDecimal,
        positions: List<PositionEntity>
    ): PortfolioCorrelationContext {
        val symbol = candidateSymbol.uppercase().replace("/", "").replace("-", "")
        val open = positions.filter { it.status == "OPEN" }
        val balances = exchange.getPortfolioBalances()

        val accountEquity = balances.fold(BigDecimal.ZERO) { total, balance ->
            val normalized = normalizeAsset(balance.asset)
            val value = when {
                balance.eurValue > BigDecimal.ZERO -> balance.eurValue
                normalized == "EUR" -> balance.total
                else -> BigDecimal.ZERO
            }
            total.add(value.max(BigDecimal.ZERO))
        }.setScale(8, RoundingMode.HALF_UP)

        val freeCash = balances.fold(BigDecimal.ZERO) { total, balance ->
            if (normalizeAsset(balance.asset) == "EUR") total.add(balance.free.max(BigDecimal.ZERO))
            else total
        }.setScale(8, RoundingMode.HALF_UP)

        val assetEurValues = balances
            .groupBy { normalizeAsset(it.asset) }
            .mapValues { (_, rows) ->
                rows.fold(BigDecimal.ZERO) { total, balance ->
                    val value = when {
                        balance.eurValue > BigDecimal.ZERO -> balance.eurValue
                        normalizeAsset(balance.asset) == "EUR" -> balance.total
                        else -> BigDecimal.ZERO
                    }
                    total.add(value.max(BigDecimal.ZERO))
                }
            }

        val reserve = PortfolioCorrelationMath.reserveRequired(
            accountEquityQuote = accountEquity,
            minimumAbsoluteQuote = settings.minimumQuoteReserveAmount,
            minimumQuoteReservePercent = settings.minimumQuoteReservePercent,
            minimumEurReservePercent = settings.minimumEurReservePercent
        )
        val availableSpend = if (freeCash > BigDecimal.ZERO) {
            freeCash.subtract(reserve).max(BigDecimal.ZERO)
        } else {
            // No trustworthy EUR free-cash field: requestedQuote has already passed the
            // controller's balance/reserve gate, so M17 does not manufacture a cash value.
            requestedQuote
        }

        val grossOpen = open.fold(BigDecimal.ZERO) { total, p ->
            total.add(positionNotional(p, assetEurValues))
        }
        val candidateBase = PortfolioCorrelationMath.baseAsset(symbol)
        val candidateFactor = PortfolioCorrelationMath.factorGroup(symbol)

        val singleAssetExposure = open
            .filter { PortfolioCorrelationMath.baseAsset(it.symbol) == candidateBase }
            .fold(BigDecimal.ZERO) { total, p -> total.add(positionNotional(p, assetEurValues)) }

        val factorExposure = open
            .filter { PortfolioCorrelationMath.factorGroup(it.symbol) == candidateFactor }
            .fold(BigDecimal.ZERO) { total, p -> total.add(positionNotional(p, assetEurValues)) }

        val candidateReturns = runCatching { returnsFor(exchange, symbol) }
            .getOrDefault(emptyMap())
        val observations = mutableListOf<PortfolioCorrelationObservation>()
        for (position in open.take(8)) {
            val positionSymbol = position.symbol.uppercase().replace("/", "").replace("-", "")
            if (positionSymbol == symbol) continue
            val otherReturns = runCatching { returnsFor(exchange, positionSymbol) }
                .getOrDefault(emptyMap())
            val (corr, n) = PortfolioCorrelationMath.pearson(candidateReturns, otherReturns)
            observations += PortfolioCorrelationObservation(
                symbol = positionSymbol,
                pairedSamples = n,
                correlation = corr,
                factorGroup = PortfolioCorrelationMath.factorGroup(positionSymbol),
                positionNotionalQuote = positionNotional(position, assetEurValues)
            )
        }

        val usable = observations.filter { it.correlation != null }
        val maxPositive = usable.mapNotNull { it.correlation }
            .filter { it > 0.0 }
            .maxOrNull()
        val empiricalMultiplier = PortfolioCorrelationMath.correlationMultiplier(maxPositive)

        // If empirical data is unavailable but we already carry the same broad risk
        // factor, reduce rather than pretend diversification exists.
        val sameFactorWithoutEvidence =
            factorExposure > BigDecimal.ZERO && usable.isEmpty() && candidateFactor != "CASH_STABLE"
        val effectiveCorrelationMultiplier = if (sameFactorWithoutEvidence) {
            empiricalMultiplier.min(BigDecimal("0.80"))
        } else empiricalMultiplier

        val singleShare = settings.maxSingleAssetAllocationPercent
            .min(settings.maxCoinExposurePercent)
            .coerceIn(BigDecimal.ZERO, BigDecimal("100"))
            .divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)
        val singleRemaining = PortfolioCorrelationMath.remainingCap(
            accountEquity,
            singleAssetExposure,
            singleShare
        )
        val factorRemaining = PortfolioCorrelationMath.remainingCap(
            accountEquity,
            factorExposure,
            PortfolioCorrelationMath.COMMON_FACTOR_MAX_SHARE
        )

        val maxSamples = observations.maxOfOrNull { it.pairedSamples } ?: 0
        val reason = buildString {
            append("M17 portfolio context: equity=")
            append(accountEquity.s2())
            append(", freeEUR=")
            append(freeCash.s2())
            append(", reserve=")
            append(reserve.s2())
            append(", spendable=")
            append(availableSpend.s2())
            append(", openExposure=")
            append(grossOpen.s2())
            append(", factor=")
            append(candidateFactor)
            append(", factorExposure=")
            append(factorExposure.s2())
            append(", singleExposure=")
            append(singleAssetExposure.s2())
            append(", maxCorr=")
            append(maxPositive?.let { "%.3f".format(it) } ?: "unknown")
            append(", corrN=")
            append(maxSamples)
            append(", corr×")
            append(effectiveCorrelationMultiplier.stripTrailingZeros().toPlainString())
            append(". Pearson requires >=${PortfolioCorrelationMath.MIN_PAIRED_RETURNS} aligned H1 returns; unknown correlation stays unknown.")
        }

        return PortfolioCorrelationContext(
            candidateSymbol = symbol,
            accountEquityQuote = accountEquity,
            freeCashQuote = freeCash,
            requiredCashReserveQuote = reserve,
            availableNewSpendQuote = availableSpend,
            grossOpenExposureQuote = grossOpen,
            candidateFactorGroup = candidateFactor,
            factorExposureQuote = factorExposure,
            singleAssetExposureQuote = singleAssetExposure,
            maxPositiveCorrelation = maxPositive,
            correlationSampleCount = maxSamples,
            correlationMultiplier = effectiveCorrelationMultiplier,
            singleAssetRemainingQuote = singleRemaining,
            factorRemainingQuote = factorRemaining,
            observations = observations,
            reason = reason
        )
    }

    private suspend fun returnsFor(
        exchange: CryptoExchangeClient,
        symbol: String
    ): Map<Long, Double> {
        val key = symbol.uppercase()
        val now = System.currentTimeMillis()
        returnCache[key]?.takeIf { now - it.loadedAtMs <= RETURN_CACHE_MS }?.let {
            return it.returns
        }

        val candles = exchange.getCandles(key, Timeframe.H1, CANDLE_LIMIT)
        val returns = PortfolioCorrelationMath.returnsByTimestamp(candles)
        returnCache[key] = CachedReturns(now, returns)
        return returns
    }

    private fun positionNotional(
        position: PositionEntity,
        assetEurValues: Map<String, BigDecimal>
    ): BigDecimal {
        val liveAssetValue = assetEurValues[normalizeAsset(position.baseAsset)]
            ?.takeIf { it > BigDecimal.ZERO }
        if (liveAssetValue != null) return liveAssetValue

        val quantity = position.quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val entry = position.entryPriceEur.toBigDecimalOrNull() ?: BigDecimal.ZERO
        return quantity.max(BigDecimal.ZERO).multiply(entry.max(BigDecimal.ZERO))
    }

    private fun normalizeAsset(asset: String): String =
        asset.uppercase()
            .substringBefore(".")
            .removePrefix("X")
            .removePrefix("Z")
            .let { if (it == "XBT") "BTC" else it }

    private fun BigDecimal.s2(): String =
        setScale(2, RoundingMode.HALF_UP).toPlainString()

    private fun BigDecimal.coerceIn(min: BigDecimal, max: BigDecimal): BigDecimal = when {
        this < min -> min
        this > max -> max
        else -> this
    }
}
