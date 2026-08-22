package com.ksp.cryptobot.strategy.structure

import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.Timeframe
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant

class KoroushCtsReferenceStrategyTest {
    @Test fun referenceReasonAlwaysSaysThresholdsAreCtsDefined() {
        val rows=(0..120).map{i->
            val p=BigDecimal.valueOf(100.0+i)
            Candle("BTCEUR",Timeframe.H1,i*3_600_000L,p,p.add(BigDecimal.ONE),p.subtract(BigDecimal.ONE),p,BigDecimal.TEN)
        }
        val t=MarketTicker("BTCEUR",BigDecimal("225"),BigDecimal("224"),BigDecimal("226"),BigDecimal("1000000"),BigDecimal.ZERO,Instant.now())
        val e=KoroushCtsReferenceStrategy().evaluate(t,rows)
        assertTrue(e.reason.contains("CTS-defined")); assertFalse(e.reason.contains("Koroush exact"))
    }
}
