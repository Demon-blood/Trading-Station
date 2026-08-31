package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.Timeframe
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class PortfolioCorrelationMathTest {
    private fun candles(multiplier: Double, invert: Boolean = false): List<Candle> {
        var price = 100.0
        return (0..60).map { i ->
            if (i > 0) {
                val move = multiplier * (1.0 + (i % 7) * 0.1) / 100.0
                price *= if (invert) (1.0 - move) else (1.0 + move)
            }
            val p = BigDecimal.valueOf(price)
            Candle(
                symbol = "TESTEUR",
                timeframe = Timeframe.H1,
                openTimeEpochMs = i * 3_600_000L,
                open = p,
                high = p,
                low = p,
                close = p,
                volume = BigDecimal.ONE
            )
        }
    }

    @Test fun stronglyAlignedReturnsProduceHighPositiveCorrelation() {
        val left = PortfolioCorrelationMath.returnsByTimestamp(candles(0.5))
        val right = PortfolioCorrelationMath.returnsByTimestamp(candles(1.0))
        val (corr, n) = PortfolioCorrelationMath.pearson(left, right)
        assertTrue(n >= PortfolioCorrelationMath.MIN_PAIRED_RETURNS)
        assertNotNull(corr)
        assertTrue(corr!! > 0.95)
    }

    @Test fun tooFewPairedReturnsStayUnknown() {
        val left = PortfolioCorrelationMath.returnsByTimestamp(candles(0.5).take(10))
        val right = PortfolioCorrelationMath.returnsByTimestamp(candles(1.0).take(10))
        val (corr, n) = PortfolioCorrelationMath.pearson(left, right)
        assertNull(corr)
        assertTrue(n < PortfolioCorrelationMath.MIN_PAIRED_RETURNS)
    }

    @Test fun highCorrelationCanOnlyReduceCapital() {
        assertEquals(0, PortfolioCorrelationMath.correlationMultiplier(0.95).compareTo(BigDecimal("0.40")))
        assertEquals(0, PortfolioCorrelationMath.correlationMultiplier(0.85).compareTo(BigDecimal("0.60")))
        assertEquals(0, PortfolioCorrelationMath.correlationMultiplier(0.75).compareTo(BigDecimal("0.80")))
        assertEquals(0, PortfolioCorrelationMath.correlationMultiplier(0.20).compareTo(BigDecimal.ONE))
    }

    @Test fun reserveUsesStricterAbsoluteOrPercentageFloor() {
        val required = PortfolioCorrelationMath.reserveRequired(
            accountEquityQuote = BigDecimal("50"),
            minimumAbsoluteQuote = BigDecimal("10"),
            minimumQuoteReservePercent = BigDecimal("20"),
            minimumEurReservePercent = BigDecimal("15")
        )
        assertEquals(0, required.compareTo(BigDecimal("10")))
    }

    @Test fun factorGroupsAreExplicitNotCorrelationClaims() {
        assertEquals("BTC_CORE", PortfolioCorrelationMath.factorGroup("BTCEUR"))
        assertEquals("ETH_CORE", PortfolioCorrelationMath.factorGroup("ETHEUR"))
        assertEquals("ALT_RISK", PortfolioCorrelationMath.factorGroup("SOLEUR"))
    }
}
