package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.OrderBookLevel
import com.ksp.cryptobot.core.OrderBookSnapshot
import com.ksp.cryptobot.core.OrderType
import com.ksp.cryptobot.exchange.TradingFeeSchedule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class TradeEconomicsEngineTest {
    private val engine = TradeEconomicsEngine()
    private val ticker = MarketTicker(
        symbol = "BTCEUR",
        lastPrice = BigDecimal("100.00"),
        bid = BigDecimal("99.95"),
        ask = BigDecimal("100.05"),
        volume24h = BigDecimal("10000000"),
        priceChangePercent24h = BigDecimal.ZERO
    )
    private val book = OrderBookSnapshot(
        symbol = "BTCEUR",
        bids = listOf(
            OrderBookLevel(BigDecimal("99.95"), BigDecimal("100")),
            OrderBookLevel(BigDecimal("99.90"), BigDecimal("100"))
        ),
        asks = listOf(
            OrderBookLevel(BigDecimal("100.05"), BigDecimal("100")),
            OrderBookLevel(BigDecimal("100.10"), BigDecimal("100"))
        )
    )

    @Test
    fun tierOneTakerEconomicsBlocksSmallTwoPercentTargetAtNeutralPrior() {
        val result = engine.evaluate(
            TradeEconomicsInput(
                symbol = "BTCEUR",
                strategyId = "GENERIC",
                notionalQuote = BigDecimal("25"),
                entryPrice = BigDecimal("100.05"),
                targetPrice = BigDecimal("102.051"),
                stopPrice = BigDecimal("98.8494"),
                orderType = OrderType.MARKET,
                postOnly = false,
                ticker = ticker,
                orderBook = book,
                recentTrades = emptyList(),
                feeSchedule = TradingFeeSchedule(
                    makerRate = BigDecimal("0.0040"),
                    takerRate = BigDecimal("0.0080"),
                    source = "TEST_TIER1"
                )
            )
        )
        assertFalse(result.allowed)
        assertTrue(result.netExpectedValueQuote < BigDecimal.ZERO)
        assertTrue(result.breakEvenWinProbability > result.probabilityWin)
    }

    @Test
    fun strongRewardRiskWithMakerEntryCanRemainPositiveAfterAllCosts() {
        val result = engine.evaluate(
            TradeEconomicsInput(
                symbol = "BTCEUR",
                strategyId = "GENERIC",
                notionalQuote = BigDecimal("25"),
                entryPrice = BigDecimal("99.95"),
                targetPrice = BigDecimal("105.947"),
                stopPrice = BigDecimal("98.9505"),
                orderType = OrderType.LIMIT,
                postOnly = true,
                ticker = ticker,
                orderBook = book,
                recentTrades = emptyList(),
                feeSchedule = TradingFeeSchedule(
                    makerRate = BigDecimal("0.0040"),
                    takerRate = BigDecimal("0.0080"),
                    source = "TEST_TIER1"
                )
            )
        )
        assertTrue(result.allowed)
        assertTrue(result.netExpectedValueQuote > BigDecimal.ZERO)
        assertTrue(result.makerEntry)
    }

    @Test
    fun externalDecisionCostIsPartOfExpectedValue() {
        val baseline = engine.evaluate(
            TradeEconomicsInput(
                symbol = "BTCEUR",
                strategyId = "GENERIC",
                notionalQuote = BigDecimal("25"),
                entryPrice = BigDecimal("99.95"),
                targetPrice = BigDecimal("105.947"),
                stopPrice = BigDecimal("98.9505"),
                orderType = OrderType.LIMIT,
                postOnly = true,
                ticker = ticker,
                orderBook = book,
                recentTrades = emptyList(),
                feeSchedule = TradingFeeSchedule(
                    makerRate = BigDecimal("0.0040"),
                    takerRate = BigDecimal("0.0080"),
                    source = "TEST_TIER1"
                )
            )
        )
        val expensiveDecision = engine.evaluate(
            TradeEconomicsInput(
                symbol = "BTCEUR",
                strategyId = "GENERIC",
                notionalQuote = BigDecimal("25"),
                entryPrice = BigDecimal("99.95"),
                targetPrice = BigDecimal("105.947"),
                stopPrice = BigDecimal("98.9505"),
                orderType = OrderType.LIMIT,
                postOnly = true,
                ticker = ticker,
                orderBook = book,
                recentTrades = emptyList(),
                feeSchedule = TradingFeeSchedule(
                    makerRate = BigDecimal("0.0040"),
                    takerRate = BigDecimal("0.0080"),
                    source = "TEST_TIER1"
                ),
                externalDecisionCostQuote = BigDecimal("1.00")
            )
        )
        assertTrue(baseline.netExpectedValueQuote > expensiveDecision.netExpectedValueQuote)
        assertFalse(expensiveDecision.allowed)
    }

    @Test
    fun makerFeeIsUsedOnlyForExplicitPostOnlyLimit() {
        val schedule = TradingFeeSchedule(
            makerRate = BigDecimal("0.0040"),
            takerRate = BigDecimal("0.0080"),
            source = "TEST_TIER1"
        )
        val postOnly = engine.evaluate(
            TradeEconomicsInput(
                "BTCEUR", "GENERIC", BigDecimal("25"), BigDecimal("99.95"),
                BigDecimal("106"), BigDecimal("98.95"), OrderType.LIMIT, true,
                ticker, book, emptyList(), schedule
            )
        )
        val ordinaryLimit = engine.evaluate(
            TradeEconomicsInput(
                "BTCEUR", "GENERIC", BigDecimal("25"), BigDecimal("100.05"),
                BigDecimal("106"), BigDecimal("99"), OrderType.LIMIT, false,
                ticker, book, emptyList(), schedule
            )
        )
        assertTrue(postOnly.entryFeeRate < ordinaryLimit.entryFeeRate)
        assertTrue(postOnly.makerEntry)
        assertFalse(ordinaryLimit.makerEntry)
    }
}
