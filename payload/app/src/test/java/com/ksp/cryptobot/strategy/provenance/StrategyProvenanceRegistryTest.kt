package com.ksp.cryptobot.strategy.provenance

import org.junit.Assert.*
import org.junit.Test

class StrategyProvenanceRegistryTest {
    @Test fun turtleOriginalAndAdaptationCannotBeConfused() {
        StrategyProvenanceRegistry.assertTruthContract()
        assertEquals(ProvenanceType.SOURCE_EXACT, StrategyProvenanceRegistry.turtleOriginal.provenanceType)
        assertEquals(ProvenanceType.CTS_REFERENCE, StrategyProvenanceRegistry.turtleSpotSafe.provenanceType)
        assertFalse(StrategyProvenanceRegistry.turtleSpotSafe.enabledForLive)
        assertTrue(StrategyProvenanceRegistry.turtleSpotSafe.differencesFromSource.contains("pyramiding disabled"))
    }
    @Test fun kakFrameworkIsNotExecutableExactStrategy() {
        val k = StrategyProvenanceRegistry.kakFramework
        assertEquals(ProvenanceType.SOURCE_FRAMEWORK, k.provenanceType)
        assertFalse(k.enabledForPaper); assertFalse(k.enabledForLive)
    }
}
