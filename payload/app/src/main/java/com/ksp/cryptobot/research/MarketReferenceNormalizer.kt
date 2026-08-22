package com.ksp.cryptobot.research

import kotlin.math.abs

data class NormalizedReference(
    val valid: Boolean,
    val normalizedPrice: Double,
    val conversionRate: Double,
    val sourceQuote: String,
    val targetQuote: String,
    val reason: String
)

object ExternalNumericSanity {
    fun finitePositive(value: Double): Boolean = value.isFinite() && value > 0.0

    fun plausibleStablecoinLiquidityUsd(value: Double): Boolean =
        value.isFinite() && value >= 1_000_000.0 && value <= 10_000_000_000_000.0

    fun plausiblePriceRatio(reference: Double, target: Double, maxRatio: Double = 5.0): Boolean {
        if (!finitePositive(reference) || !finitePositive(target)) return false
        val ratio = maxOf(reference, target) / minOf(reference, target)
        return ratio <= maxRatio
    }
}

object MarketReferenceNormalizer {
    /**
     * conversionTargetPerSource means target-quote units per one source-quote unit.
     * Example: BASE/USDT -> BASE/EUR uses EUR per USDT.
     */
    fun normalize(
        sourcePrice: Double,
        sourceQuote: String,
        targetQuote: String,
        conversionTargetPerSource: Double?
    ): NormalizedReference {
        val src = sourceQuote.uppercase()
        val dst = targetQuote.uppercase()
        if (!ExternalNumericSanity.finitePositive(sourcePrice)) {
            return NormalizedReference(false, 0.0, 0.0, src, dst, "Invalid source reference price.")
        }
        if (src == dst) {
            return NormalizedReference(true, sourcePrice, 1.0, src, dst, "No quote conversion required.")
        }
        val fx = conversionTargetPerSource
        if (fx == null || !ExternalNumericSanity.finitePositive(fx)) {
            return NormalizedReference(false, 0.0, 0.0, src, dst, "Quote conversion unavailable; cross-market deviation must remain neutral.")
        }
        val normalized = sourcePrice * fx
        if (!ExternalNumericSanity.finitePositive(normalized)) {
            return NormalizedReference(false, 0.0, fx, src, dst, "Normalized price invalid.")
        }
        return NormalizedReference(true, normalized, fx, src, dst, "Normalized $src reference into $dst.")
    }

    fun deviationPercent(krakenPrice: Double, reference: NormalizedReference): Double? {
        if (!reference.valid || !ExternalNumericSanity.plausiblePriceRatio(reference.normalizedPrice, krakenPrice)) return null
        return (krakenPrice - reference.normalizedPrice) / reference.normalizedPrice * 100.0
    }
}
