package com.ksp.cryptobot.strategy

import com.ksp.cryptobot.core.StrategyMode
import org.junit.Assert.*
import org.junit.Test

class StrategyTruthRegistryTest {
    @Test fun everyNamedStrategyHasExplicitTruthSpec() {
        val missing = StrategyMode.values()
            .filter { it != StrategyMode.AUTO }
            .filter { StrategyTruthRegistry.spec(it) == null }
        assertTrue("Missing truth specs: $missing", missing.isEmpty())
    }

    @Test fun autoOnlyContainsActuallyLiveSelectableStrategies() {
        val auto = StrategyTruthRegistry.autoSelectable().toSet()
        assertTrue(StrategyMode.TREND in auto)
        assertTrue(StrategyMode.MEAN_REVERSION_RSI_BOLLINGER in auto)
        assertFalse(StrategyMode.RANGE_GRID in auto)
        assertFalse(StrategyMode.MARKET_MAKING_IMBALANCE in auto)
        assertFalse(StrategyMode.FUNDING_NEWS_RISK_OFF in auto)
        assertFalse(StrategyMode.PAIRS_RELATIVE_STRENGTH in auto)
        assertFalse(StrategyMode.DCA_CRASH_PROTECTION in auto)
        assertFalse(StrategyMode.VOLUME_ANOMALY_WHALE_MOVE in auto)
    }

    @Test fun proxyStrategiesExplainWhyTheyAreBlocked() {
        val mm = StrategyTruthRegistry.truthBlockedReason(StrategyMode.MARKET_MAKING_IMBALANCE)
        val pairs = StrategyTruthRegistry.truthBlockedReason(StrategyMode.PAIRS_RELATIVE_STRENGTH)
        val whale = StrategyTruthRegistry.truthBlockedReason(StrategyMode.VOLUME_ANOMALY_WHALE_MOVE)
        assertTrue(mm.contains("two-sided", ignoreCase = true))
        assertTrue(pairs.contains("relative", ignoreCase = true))
        assertTrue(whale.contains("whale", ignoreCase = true))
    }
}
