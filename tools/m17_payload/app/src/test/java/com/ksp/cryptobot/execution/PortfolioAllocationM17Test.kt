package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.PositionEntity
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class PortfolioAllocationM17Test {
    private val decision = AiDecision(
        symbol = "SOLEUR",
        finalAction = SignalAction.BUY,
        finalScore = 100,
        confidencePercent = 80,
        technicalScore = 80,
        newsScore = 50,
        memoryScore = 50,
        allowedToTrade = true,
        explanation = "test"
    )

    private fun context(
        correlationMultiplier: String = "1.00",
        spendable: String = "100.00",
        singleRemaining: String? = "100.00",
        factorRemaining: String? = "100.00",
        freeCash: String = "100.00"
    ) = PortfolioCorrelationContext(
        candidateSymbol = "SOLEUR",
        accountEquityQuote = BigDecimal("100"),
        freeCashQuote = BigDecimal(freeCash),
        requiredCashReserveQuote = BigDecimal("20"),
        availableNewSpendQuote = BigDecimal(spendable),
        grossOpenExposureQuote = BigDecimal.ZERO,
        candidateFactorGroup = "ALT_RISK",
        factorExposureQuote = BigDecimal.ZERO,
        singleAssetExposureQuote = BigDecimal.ZERO,
        maxPositiveCorrelation = 0.90,
        correlationSampleCount = 60,
        correlationMultiplier = BigDecimal(correlationMultiplier),
        singleAssetRemainingQuote = singleRemaining?.let { BigDecimal(it) },
        factorRemainingQuote = factorRemaining?.let { BigDecimal(it) },
        observations = emptyList(),
        reason = "test context"
    )

    @Test fun correlationPenaltyNeverIncreasesRequestedSpend() {
        val engine = PortfolioAllocationEngine()
        val noPenalty = engine.allocate(
            BotSettings(),
            decision,
            BigDecimal("20"),
            emptyList(),
            emptyList(),
            context(correlationMultiplier = "1.00")
        )
        val highCorrelation = engine.allocate(
            BotSettings(),
            decision,
            BigDecimal("20"),
            emptyList(),
            emptyList(),
            context(correlationMultiplier = "0.40")
        )
        assertTrue(highCorrelation.finalQuote <= noPenalty.finalQuote)
        assertTrue(highCorrelation.finalQuote <= BigDecimal("20"))
    }

    @Test fun cashReserveCanHardBlockNewEntry() {
        val result = PortfolioAllocationEngine().allocate(
            BotSettings(),
            decision,
            BigDecimal("20"),
            emptyList(),
            emptyList(),
            context(spendable = "0.00", freeCash = "20.00")
        )
        assertFalse(result.allowed)
        assertEquals(0, result.finalQuote.compareTo(BigDecimal.ZERO))
        assertTrue(result.reason.contains("cash reserve"))
    }

    @Test fun factorRemainingCapsButNeverRaisesSpend() {
        val result = PortfolioAllocationEngine().allocate(
            BotSettings(),
            decision,
            BigDecimal("20"),
            emptyList(),
            emptyList(),
            context(factorRemaining = "6.50")
        )
        assertTrue(result.allowed)
        assertTrue(result.finalQuote <= BigDecimal("6.50"))
    }

    @Test fun duplicateOpenSymbolIsBlockedWhenProtectionEnabled() {
        val position = PositionEntity(
            symbol = "SOLEUR",
            baseAsset = "SOL",
            quantity = "1",
            entryPriceEur = "100",
            highestPriceEur = "100",
            stopPriceEur = "90",
            takeProfitPriceEur = "110",
            trailingStopPriceEur = "0",
            openedAtEpochMs = 1L,
            updatedAtEpochMs = 1L
        )
        val result = PortfolioAllocationEngine().allocate(
            BotSettings(duplicatePositionProtectionEnabled = true),
            decision,
            BigDecimal("20"),
            emptyList(),
            listOf(position),
            context()
        )
        assertFalse(result.allowed)
        assertTrue(result.reason.contains("duplicate open position"))
    }
}
