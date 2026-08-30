package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class LiquidityAwareSizerM16Test {
    private val ticker = MarketTicker(
        symbol = "BTCEUR",
        lastPrice = BigDecimal("100"),
        bid = BigDecimal("99.99"),
        ask = BigDecimal("100.01"),
        volume24h = BigDecimal("10000000"),
        priceChangePercent24h = BigDecimal.ZERO
    )

    @Test fun guardedLiveWithoutBookFailsClosed() {
        val decision = LiquidityAwareSizer().size(
            BotSettings(
                mode = BotMode.LIVE_AUTO,
                orderBookDepthGuardEnabled = true
            ),
            BigDecimal("20"),
            ticker,
            null
        )
        assertEquals(0, decision.finalQuote.compareTo(BigDecimal.ZERO))
        assertEquals("microstructure_unavailable", decision.reasonCategory)
    }

    @Test fun paperCanContinueWithoutBook() {
        val decision = LiquidityAwareSizer().size(
            BotSettings(mode = BotMode.PAPER),
            BigDecimal("20"),
            ticker,
            null
        )
        assertTrue(decision.finalQuote > BigDecimal.ZERO)
    }
}
