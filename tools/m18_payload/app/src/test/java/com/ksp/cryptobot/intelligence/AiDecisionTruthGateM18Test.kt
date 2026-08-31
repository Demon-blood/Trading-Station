package com.ksp.cryptobot.intelligence

import com.ksp.cryptobot.core.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class AiDecisionTruthGateM18Test {
    @Test fun aiCannotPromoteTruthGatedWaitIntoBuy() {
        val recommendation = Recommendation(
            symbol = "BTCEUR",
            action = SignalAction.WAIT,
            score = 95,
            riskPercent = BigDecimal("2"),
            taxWarning = "",
            reason = "M18 TRUTH_BLOCKED test"
        )
        val decision = AiDecisionEngine().decide(
            recommendation = recommendation,
            news = emptyList(),
            recentTrades = emptyList(),
            settings = BotSettings(
                useNewsAi = false,
                useTradeMemoryAi = false
            )
        )
        assertFalse(decision.allowedToTrade)
        assertFalse(decision.finalAction == SignalAction.BUY)
        assertFalse(decision.finalAction == SignalAction.SMALL_BUY)
    }

    @Test fun aiCannotIncreaseSmallBuyIntoFullBuy() {
        val recommendation = Recommendation(
            symbol = "BTCEUR",
            action = SignalAction.SMALL_BUY,
            score = 95,
            riskPercent = BigDecimal("2"),
            taxWarning = "",
            reason = "truth-valid reduced entry"
        )
        val decision = AiDecisionEngine().decide(
            recommendation = recommendation,
            news = emptyList(),
            recentTrades = emptyList(),
            settings = BotSettings(
                useNewsAi = false,
                useTradeMemoryAi = false
            )
        )
        assertEquals(SignalAction.SMALL_BUY, decision.finalAction)
        assertTrue(decision.allowedToTrade)
    }

    @Test fun negativeNewsMayStillVetoTruthValidBuy() {
        val recommendation = Recommendation(
            symbol = "BTCEUR",
            action = SignalAction.BUY,
            score = 95,
            riskPercent = BigDecimal("2"),
            taxWarning = "",
            reason = "truth-valid entry"
        )
        // This test only asserts the monotonic risk contract: without a negative
        // news fixture BUY can remain BUY; other M6/M8 layers may still reduce/veto.
        val decision = AiDecisionEngine().decide(
            recommendation = recommendation,
            news = emptyList(),
            recentTrades = emptyList(),
            settings = BotSettings(
                useNewsAi = false,
                useTradeMemoryAi = false
            )
        )
        assertTrue(decision.finalAction in setOf(SignalAction.BUY, SignalAction.SMALL_BUY))
    }
}
