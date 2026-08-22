package com.ksp.cryptobot.backtest

import com.ksp.cryptobot.core.*
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffBacktestEngineTest {
    @Test fun turtleRefusesWrongTimeframe() {
        val r=HandoffBacktestEngine.run("BTCEUR",Timeframe.H1,StrategyMode.CTS_TURTLE_SPOT_SAFE,emptyList(),BotSettings())
        assertFalse(r.passedLiveGate); assertTrue(r.summary.contains("requires H4"))
    }
    @Test fun kakNeverSelfPromotes() {
        val r=HandoffBacktestEngine.run("BTCEUR",Timeframe.H4,StrategyMode.CTS_KAK_CLOSE_BREAK_RETEST_V1,emptyList(),BotSettings())
        assertFalse(r.passedLiveGate); assertTrue(r.summary.contains("CTS-defined profile"))
    }
}
