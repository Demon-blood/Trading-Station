package com.ksp.cryptobot.strategy

import com.ksp.cryptobot.core.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class RecommendationTruthGateM18Test {
    private fun candles(): List<Candle> =
        (0 until 50).map { i ->
            Candle(
                symbol = "BTCEUR",
                timeframe = Timeframe.M15,
                openTimeEpochMs = i * 900_000L,
                open = BigDecimal("100.00"),
                high = BigDecimal("101.00"),
                low = BigDecimal("99.00"),
                close = BigDecimal("100.00"),
                volume = BigDecimal("100")
            )
        }

    @Test fun liquidityBoostCannotTurnNonEntrySetupIntoBuy() {
        val ticker = MarketTicker(
            symbol = "BTCEUR",
            lastPrice = BigDecimal("100.00"),
            bid = BigDecimal("99.99"),
            ask = BigDecimal("100.01"),
            volume24h = BigDecimal("20000000"),
            priceChangePercent24h = BigDecimal.ZERO,
            timestamp = Instant.now()
        )
        val settings = BotSettings(
            strategyMode = StrategyMode.BREAKOUT,
            minStrategyScoreToBuy = 40,
            recoveredScalpingStrategyEnabled = true
        )
        val recommendation = RecommendationEngine().recommend(
            ticker = ticker,
            settings = settings,
            candlesByTimeframe = mapOf(Timeframe.M15 to candles())
        )
        assertFalse(recommendation.action == SignalAction.BUY)
        assertFalse(recommendation.action == SignalAction.SMALL_BUY)
    }

    @Test fun noTruthValidatedCandlesNeverUsesUnnamedFallbackToEnter() {
        val ticker = MarketTicker(
            symbol = "BTCEUR",
            lastPrice = BigDecimal("100.00"),
            bid = BigDecimal("99.99"),
            ask = BigDecimal("100.01"),
            volume24h = BigDecimal("20000000"),
            priceChangePercent24h = BigDecimal("5.0"),
            timestamp = Instant.now()
        )
        val recommendation = RecommendationEngine().recommend(
            ticker = ticker,
            settings = BotSettings(),
            candlesByTimeframe = emptyMap()
        )
        assertFalse(recommendation.action == SignalAction.BUY)
        assertFalse(recommendation.action == SignalAction.SMALL_BUY)
        assertTrue(recommendation.reason.contains("non-trading fallback", ignoreCase = true))
    }
}
