package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.OrderBookLevel
import com.ksp.cryptobot.core.OrderBookSnapshot
import com.ksp.cryptobot.core.OrderSide
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class MarketMicrostructureEngineTest {
    private val engine = MarketMicrostructureEngine()

    private fun level(price: String, qty: String) =
        OrderBookLevel(BigDecimal(price), BigDecimal(qty))

    @Test fun balancedBookHasMidMicropriceAndNearZeroImbalance() {
        val book = OrderBookSnapshot(
            "BTCEUR",
            bids = listOf(level("100.00", "2"), level("99.99", "2")),
            asks = listOf(level("100.02", "2"), level("100.03", "2"))
        )
        val m = engine.evaluate(book, OrderSide.BUY, BigDecimal("20"))
        assertTrue(m.valid)
        assertEquals(0.0, m.bookImbalance, 0.001)
        assertEquals(0, m.microPrice.compareTo(m.midpoint))
    }

    @Test fun bidHeavyBookMovesMicropriceAboveMidpoint() {
        val book = OrderBookSnapshot(
            "BTCEUR",
            bids = listOf(level("100.00", "10")),
            asks = listOf(level("100.02", "1"))
        )
        val m = engine.evaluate(book, OrderSide.BUY, BigDecimal("20"))
        assertTrue(m.microPrice > m.midpoint)
        assertTrue(m.microPricePressureBps > 0.0)
        assertTrue(m.bookImbalance > 0.0)
    }

    @Test fun marketImpactConsumesMultipleAskLevels() {
        val book = OrderBookSnapshot(
            "BTCEUR",
            bids = listOf(level("99.99", "10")),
            asks = listOf(level("100.00", "0.10"), level("101.00", "1.00"))
        )
        val m = engine.evaluate(book, OrderSide.BUY, BigDecimal("20.00"))
        assertTrue(m.marketImpactComplete)
        assertTrue(m.marketImpactBps > 0.0)
    }

    @Test fun insufficientBookDepthNeverPretendsImpactIsComplete() {
        val book = OrderBookSnapshot(
            "BTCEUR",
            bids = listOf(level("99.99", "1")),
            asks = listOf(level("100.00", "0.01"))
        )
        val m = engine.evaluate(book, OrderSide.BUY, BigDecimal("50.00"))
        assertFalse(m.marketImpactComplete)
        assertTrue(m.marketImpactBps >= 99_999.0)
    }

    @Test fun passiveTargetNeverCrossesOppositeTouch() {
        val book = OrderBookSnapshot(
            "BTCEUR",
            bids = listOf(level("100.00", "1")),
            asks = listOf(level("100.05", "5"))
        )
        val m = engine.evaluate(
            book,
            OrderSide.BUY,
            BigDecimal("20"),
            workingPrice = BigDecimal("99.90"),
            tickSize = BigDecimal("0.01"),
            calibrationSamples = 10,
            calibratedMeanFillSeconds = 500.0,
            fillHorizonSeconds = 90
        )
        assertTrue(m.makerTargetPrice < m.bestAsk)
        assertTrue(m.makerTargetPrice >= m.bestBid)
        assertTrue(m.makerFillProbability in 0.02..0.98)
    }

    @Test fun crossedBookIsInvalid() {
        val book = OrderBookSnapshot(
            "BTCEUR",
            bids = listOf(level("100.02", "1")),
            asks = listOf(level("100.01", "1"))
        )
        val m = engine.evaluate(book, OrderSide.BUY, BigDecimal("20"))
        assertFalse(m.valid)
        assertEquals(1.0, m.adverseSelectionRisk, 0.0)
    }
}
