package com.ksp.cryptobot.research

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ChampionDegradationEngineTest {
    private val day=24L*60L*60L*1000L
    private fun rows(n:Int,rate:String,pnl:String,spanDays:Int=5)=List(n){i->
        val d=(n-1).coerceAtLeast(1)
        StrategyOutcome(1_700_000_000_000L+i.toLong()*spanDays*day/d,BigDecimal(pnl),BigDecimal(rate),i%2==0)
    }

    @Test fun earlyNegativeEvidenceEntersWatch(){
        val d=ChampionDegradationEngine.classify(ChampionHealthState.HEALTHY,ChampionDegradationEngine.rollingStats(rows(8,"-0.001","-0.02")),BigDecimal("10"))
        assertEquals(ChampionHealthState.WATCH,d.state);assertEquals(BigDecimal("0.75"),d.liveSizeMultiplier)
    }
    @Test fun sustainedNegativeEvidenceEntersProbation(){
        val d=ChampionDegradationEngine.classify(ChampionHealthState.WATCH,ChampionDegradationEngine.rollingStats(rows(12,"-0.002","-0.02")),BigDecimal("10"))
        assertEquals(ChampionHealthState.PROBATION,d.state);assertEquals(BigDecimal("0.50"),d.liveSizeMultiplier)
    }
    @Test fun statisticallyHarmfulChampionDisablesLive(){
        val d=ChampionDegradationEngine.classify(ChampionHealthState.PROBATION,ChampionDegradationEngine.rollingStats(rows(20,"-0.010","-0.05",4)),BigDecimal("10"))
        assertEquals(ChampionHealthState.LIVE_DISABLED,d.state);assertFalse(d.liveEntryAuthorized);assertTrue(d.rolling.upper95Return<BigDecimal.ZERO)
    }
    @Test fun oneBadDayCannotStatisticallyDisable(){
        val d=ChampionDegradationEngine.classify(ChampionHealthState.WATCH,ChampionDegradationEngine.rollingStats(rows(20,"-0.010","-0.05",1)),BigDecimal("10"))
        assertEquals(ChampionHealthState.PROBATION,d.state);assertTrue(d.liveEntryAuthorized)
    }
    @Test fun stronglyRecoveredProbationReturnsHealthy(){
        val d=ChampionDegradationEngine.classify(ChampionHealthState.PROBATION,ChampionDegradationEngine.rollingStats(rows(20,"0.010","0.05",10)),BigDecimal("10"))
        assertEquals(ChampionHealthState.HEALTHY,d.state);assertEquals(BigDecimal.ONE,d.liveSizeMultiplier)
    }
    @Test fun liveDisabledDoesNotSelfReenable(){
        val d=ChampionDegradationEngine.classify(ChampionHealthState.LIVE_DISABLED,ChampionDegradationEngine.rollingStats(rows(30,"0.020","0.10",15)),BigDecimal("10"))
        assertEquals(ChampionHealthState.LIVE_DISABLED,d.state);assertFalse(d.liveEntryAuthorized)
    }
}
