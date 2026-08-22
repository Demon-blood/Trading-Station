package com.ksp.cryptobot.strategy.turtle

import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.Timeframe
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class TurtleSpotSafeStrategyTest {
    private fun h4(t:Long,p:String)=Candle("BTCEUR",Timeframe.H4,t,BigDecimal(p),BigDecimal(p).add(BigDecimal("1")),BigDecimal(p).subtract(BigDecimal("1")),BigDecimal(p),BigDecimal("10"))
    @Test fun aggregationDropsFinalRestRowAndFinalPartialDay() {
        val day=86_400_000L
        val rows=mutableListOf<Candle>()
        for(d in 0L..2L) for(k in 0L..5L) rows += h4(d*day+k*14_400_000L,(100+d).toString())
        rows += h4(3*day,"103") // current/uncommitted REST row
        val daily=TurtleDailyAggregation.completedDailyFromH4(rows)
        // After dropping current REST row, the last committed H4 belongs to day 2, which is conservatively partial/excluded.
        assertEquals(listOf(0L,day),daily.map{it.dayEpochMs})
    }
    @Test fun system1FailsClosedWhenPreviousBreakoutStateUnknown() {
        // Use artificial completed daily conversion through 60 full UTC days + one current row.
        val day=86_400_000L; val rows=mutableListOf<Candle>()
        for(d in 0L..60L) for(k in 0L..5L) rows += h4(d*day+k*14_400_000L,(100+d).toString())
        rows += h4(61*day,"200")
        val ticker=MarketTicker("BTCEUR",BigDecimal("1000"),BigDecimal("999"),BigDecimal("1001"),BigDecimal("1000000"),BigDecimal.ZERO,Instant.now())
        val e=TurtleSpotSafeStrategy().evaluate(ticker,rows,false,true,null,TurtleSystem.SYSTEM_1)
        assertEquals(TurtleSignalType.BLOCKED,e.type)
        assertTrue(e.reason.contains("unknown"))
    }
}
