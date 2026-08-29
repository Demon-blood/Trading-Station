package com.ksp.cryptobot.research

import com.ksp.cryptobot.data.TradeEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class StrategyChampionChallengerEngineTest {
    private val day = 24L * 60L * 60L * 1000L

    private fun outcomes(
        n: Int,
        rate: String,
        pnl: String,
        paperCount: Int = n,
        spanDays: Int = 10
    ): List<StrategyOutcome> =
        List(n) { index ->
            val denominator = (n - 1).coerceAtLeast(1)
            val offset = index.toLong() * spanDays.toLong() * day / denominator.toLong()
            StrategyOutcome(
                timestampEpochMs = 1_700_000_000_000L + offset,
                conservativeNetPnlQuote = BigDecimal(pnl),
                normalizedNetReturn = BigDecimal(rate),
                paper = index < paperCount
            )
        }

    private fun wf(score: Double = 70.0) =
        WalkForwardAssessment(true, "PASS", score, 4, 5, 40, "train", "test", "ok")

    private fun mc(score: Double = 70.0) =
        MonteCarloAssessment(true, score, 0.80, 1.0, 3.0, 6.0, 2.0, 500, 40, "ok")

    @Test
    fun strongExactPaperEvidenceCanCreateInitialChampion() {
        val result = StrategyChampionChallengerEngine.decide(
            null, "VOL_BREAKOUT",
            outcomes(40, "0.010", "0.25"),
            emptyList(), 3, wf(), mc(), null,
            BigDecimal("10"), 1_800_000_000_000L, 0L
        )
        assertEquals(StrategyGovernanceAction.PROMOTE_INITIAL_CHAMPION, result.action)
        assertTrue(result.productionAuthorized)
    }

    @Test
    fun paperEvidenceIsMandatoryForFirstChampion() {
        val result = StrategyChampionChallengerEngine.decide(
            null, "VOL_BREAKOUT",
            outcomes(40, "0.010", "0.25", paperCount = 5),
            emptyList(), 3, wf(), mc(), null,
            BigDecimal("10"), 1_800_000_000_000L, 0L
        )
        assertEquals(StrategyGovernanceAction.HOLD_CHALLENGER, result.action)
        assertFalse(result.productionAuthorized)
    }

    @Test
    fun oneRegimeCannotPromote() {
        val result = StrategyChampionChallengerEngine.decide(
            null, "VOL_BREAKOUT",
            outcomes(40, "0.010", "0.25"),
            emptyList(), 1, wf(), mc(), null,
            BigDecimal("10"), 1_800_000_000_000L, 0L
        )
        assertEquals(StrategyGovernanceAction.HOLD_CHALLENGER, result.action)
    }

    @Test
    fun currentChampionRemainsAuthorizedWithoutAutomaticDemotion() {
        val bad = outcomes(40, "-0.010", "-0.25")
        val result = StrategyChampionChallengerEngine.decide(
            "TREND", "TREND", bad, bad, 2, wf(40.0), mc(40.0), null,
            BigDecimal("10"), 1_800_000_000_000L, 0L
        )
        assertEquals(StrategyGovernanceAction.KEEP_CHAMPION, result.action)
        assertTrue(result.productionAuthorized)
    }

    @Test
    fun challengerMustProveStatisticalSuperiority() {
        val result = StrategyChampionChallengerEngine.decide(
            "TREND", "VOL_BREAKOUT",
            outcomes(45, "0.012", "0.30"),
            outcomes(45, "0.003", "0.08"),
            3, wf(75.0), mc(75.0), null,
            BigDecimal("10"), 1_800_000_000_000L, 0L
        )
        assertEquals(StrategyGovernanceAction.PROMOTE_CHALLENGER, result.action)
        assertTrue(result.difference!!.lower95Difference > BigDecimal.ZERO)
    }

    @Test
    fun higherAverageWithOverlappingConfidenceDoesNotReplaceChampion() {
        fun noisy(baseWin: String): List<StrategyOutcome> = List(45) { index ->
            StrategyOutcome(
                1_700_000_000_000L + index * day / 4,
                BigDecimal(if (index % 2 == 0) baseWin else "-0.15"),
                BigDecimal(if (index % 2 == 0) "0.020" else "-0.010"),
                true
            )
        }
        val challenger = noisy("0.34")
        val champion = noisy("0.30").mapIndexed { index, row ->
            row.copy(normalizedNetReturn = BigDecimal(if (index % 2 == 0) "0.019" else "-0.010"))
        }
        val result = StrategyChampionChallengerEngine.decide(
            "TREND", "VOL_BREAKOUT", challenger, champion, 3, wf(), mc(), null,
            BigDecimal("10"), 1_800_000_000_000L, 0L
        )
        assertEquals(StrategyGovernanceAction.HOLD_CHALLENGER, result.action)
    }

    @Test
    fun exactOutcomeExtractorDoesNotBorrowAnotherStrategysProfit() {
        val trades = listOf(
            TradeEntity(
                symbol="BTCEUR", side="SELL", quantity="0.1", priceEur="100",
                feeEur="0.10", paper=true, realizedPnlEur="1.10",
                aiReason="Lifecycle exit [VOL_BREAKOUT]: target", timestampEpochMs=1000L
            ),
            TradeEntity(
                symbol="BTCEUR", side="SELL", quantity="0.1", priceEur="100",
                feeEur="0.10", paper=true, realizedPnlEur="9.00",
                aiReason="Lifecycle exit [MEAN_REVERSION]: target", timestampEpochMs=2000L
            )
        )
        val rows = StrategyChampionChallengerEngine.exactOutcomes("VOL_BREAKOUT", "BTCEUR", trades)
        assertEquals(1, rows.size)
        assertEquals(BigDecimal("1.00"), rows.single().conservativeNetPnlQuote)
    }

    @Test
    fun promotionCooldownPreventsChurn() {
        val now = 1_800_000_000_000L
        val result = StrategyChampionChallengerEngine.decide(
            null, "VOL_BREAKOUT", outcomes(40, "0.010", "0.25"),
            emptyList(), 3, wf(), mc(), null,
            BigDecimal("10"), now, now - 2L * day
        )
        assertEquals(StrategyGovernanceAction.HOLD_CHALLENGER, result.action)
    }
}
