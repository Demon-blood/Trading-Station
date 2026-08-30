package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class OrderTypeOptimizerM16Test {
    private fun level(price: String, qty: String) =
        OrderBookLevel(BigDecimal(price), BigDecimal(qty))

    private fun ticker() = MarketTicker(
        symbol = "BTCEUR",
        lastPrice = BigDecimal("100.005"),
        bid = BigDecimal("100.00"),
        ask = BigDecimal("100.01"),
        volume24h = BigDecimal("10000000"),
        priceChangePercent24h = BigDecimal.ZERO
    )

    private fun deepBook() = OrderBookSnapshot(
        "BTCEUR",
        bids = List(5) { i -> level("${100 - i}.00", "100") },
        asks = listOf(
            level("100.01", "100"),
            level("100.02", "100"),
            level("100.03", "100"),
            level("100.04", "100"),
            level("100.05", "100")
        )
    )

    @Test fun ordinaryLimitIsPassiveAndPostOnly() {
        val decision = OrderTypeOptimizer().suggest(
            BotSettings(),
            ticker(),
            deepBook(),
            BigDecimal("20"),
            currentUseMarket = false
        )
        assertEquals(OrderType.LIMIT, decision.orderType)
        assertTrue(decision.postOnly)
        assertNotNull(decision.limitPrice)
        assertTrue(decision.limitPrice!! < ticker().ask)
    }

    @Test fun enabledMarketRequiresStrongMicrostructureEvidence() {
        val settings = BotSettings(
            enableMarketOrders = true,
            maxSpreadPercent = BigDecimal("0.35"),
            maxOrderBookSlippagePercent = BigDecimal("0.35"),
            marketOrderSlippageWarningPercent = BigDecimal("0.75")
        )
        val decision = OrderTypeOptimizer().suggest(
            settings,
            ticker(),
            deepBook(),
            BigDecimal("20"),
            currentUseMarket = true
        )
        assertEquals(OrderType.MARKET, decision.orderType)
        assertFalse(decision.postOnly)
    }

    @Test fun missingBookNeverAllowsMarket() {
        val decision = OrderTypeOptimizer().suggest(
            BotSettings(enableMarketOrders = true),
            ticker(),
            null,
            BigDecimal("20"),
            currentUseMarket = true
        )
        assertEquals(OrderType.LIMIT, decision.orderType)
        assertTrue(decision.postOnly)
    }
}
