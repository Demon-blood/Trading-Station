package com.ksp.cryptobot.intelligence

import com.ksp.cryptobot.data.AiValueAttributionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class AiAdaptiveGovernanceEngineTest {
    private val day = 24L * 60L * 60L * 1000L

    private fun samples(
        n: Int,
        rate: String,
        value: String,
        spanDays: Int = 8
    ): List<AiAdaptiveSample> =
        List(n) { index ->
            val denominator = (n - 1).coerceAtLeast(1)
            val offset = (index.toLong() * spanDays.toLong() * day) / denominator.toLong()
            AiAdaptiveSample(
                normalizedValueRate = BigDecimal(rate),
                absoluteValueQuote = BigDecimal(value),
                resolvedAtEpochMs = 1_700_000_000_000L + offset
            )
        }

    @Test
    fun fiftyConsistentlyHarmfulAiSamplesDisableCloud() {
        val decision = AiAdaptiveGovernanceEngine.decide(
            overallSamples = samples(50, "-0.020", "-0.50"),
            solSamples = emptyList(),
            cloudEnabled = true,
            solEnabled = true
        )
        assertEquals(AiAdaptiveAction.DISABLE_CLOUD_AI, decision.action)
        assertTrue(decision.overall.upper95 < BigDecimal.ZERO)
    }

    @Test
    fun fortyNineHarmfulSamplesAreStillInsufficient() {
        val decision = AiAdaptiveGovernanceEngine.decide(
            overallSamples = samples(49, "-0.020", "-0.50"),
            solSamples = emptyList(),
            cloudEnabled = true,
            solEnabled = true
        )
        assertEquals(AiAdaptiveAction.HOLD, decision.action)
    }

    @Test
    fun concentratedOneDayEvidenceCannotDisableAi() {
        val decision = AiAdaptiveGovernanceEngine.decide(
            overallSamples = samples(60, "-0.020", "-0.50", spanDays = 1),
            solSamples = emptyList(),
            cloudEnabled = true,
            solEnabled = true
        )
        assertEquals(AiAdaptiveAction.HOLD, decision.action)
        assertTrue(decision.reason.contains("span", ignoreCase = true))
    }

    @Test
    fun harmfulSolCanBeDisabledWithoutDisablingLuna() {
        val overall = List(60) { index ->
            AiAdaptiveSample(
                normalizedValueRate = if (index % 2 == 0) BigDecimal("0.010") else BigDecimal("-0.009"),
                absoluteValueQuote = if (index % 2 == 0) BigDecimal("0.20") else BigDecimal("-0.18"),
                resolvedAtEpochMs = 1_700_000_000_000L + index * (8L * day / 59L)
            )
        }
        val decision = AiAdaptiveGovernanceEngine.decide(
            overallSamples = overall,
            solSamples = samples(30, "-0.025", "-0.60"),
            cloudEnabled = true,
            solEnabled = true
        )
        assertEquals(AiAdaptiveAction.DISABLE_SOL, decision.action)
        assertTrue(decision.sol.upper95 < BigDecimal.ZERO)
    }

    @Test
    fun positiveEvidenceNeverAutoExpandsAi() {
        val decision = AiAdaptiveGovernanceEngine.decide(
            overallSamples = samples(60, "0.020", "0.50"),
            solSamples = samples(35, "0.015", "0.35"),
            cloudEnabled = true,
            solEnabled = true
        )
        assertEquals(AiAdaptiveAction.HOLD, decision.action)
        assertTrue(decision.overall.lower95 > BigDecimal.ZERO)
    }

    @Test
    fun disabledCloudIsNeverAutomaticallyReenabled() {
        val decision = AiAdaptiveGovernanceEngine.decide(
            overallSamples = samples(100, "0.030", "0.75"),
            solSamples = samples(50, "0.030", "0.75"),
            cloudEnabled = false,
            solEnabled = false
        )
        assertEquals(AiAdaptiveAction.HOLD, decision.action)
        assertTrue(decision.reason.contains("never auto-enables", ignoreCase = true))
    }

    @Test
    fun extremeOutlierRateIsClippedBeforeConfidenceCalculation() {
        val normal = samples(49, "-0.020", "-0.50")
        val outlier = AiAdaptiveSample(
            normalizedValueRate = BigDecimal("10.0"),
            absoluteValueQuote = BigDecimal("0.01"),
            resolvedAtEpochMs = 1_700_000_000_000L + 8L * day
        )
        val stats = AiAdaptiveGovernanceEngine.statistics(normal + outlier)
        assertTrue(stats.meanNormalizedValue < BigDecimal("0.01"))
    }

    @Test
    fun fallbackCurrentTickerResolutionIsNotHighIntegrityEvidence() {
        val row = AiValueAttributionEntity(
            fingerprint = "x",
            createdAtEpochMs = 1L,
            updatedAtEpochMs = 2L,
            resolvedAtEpochMs = 3L,
            symbol = "BTCEUR",
            strategy = "X",
            regime = "X",
            modelPath = "LUNA",
            deterministicAction = "BUY",
            deterministicNotionalQuote = "25",
            lunaVerdict = "REJECT",
            lunaRiskMultiplier = "0",
            finalVerdict = "REJECT",
            finalRiskMultiplier = "0",
            entryPrice = "100",
            targetPrice = "102",
            stopPrice = "98",
            horizonMinutes = 240,
            estimatedRoundTripCostRate = "0.01",
            lunaCostQuote = "0.01",
            solCostQuote = "0",
            totalAiCostQuote = "0.01",
            status = "RESOLVED",
            resolution = "HORIZON_FALLBACK_CURRENT_TICKER"
        )
        assertFalse(AiAdaptiveGovernanceEngine.isHighIntegrityResolution(row))
    }
}
