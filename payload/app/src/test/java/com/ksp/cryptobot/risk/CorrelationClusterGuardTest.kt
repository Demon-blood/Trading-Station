package com.ksp.cryptobot.risk

import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.Timeframe
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class CorrelationClusterGuardTest {
    private fun series(symbol:String,mult:Double):List<Candle>=(0..45).map{i->
        val p=BigDecimal.valueOf(100.0 + i*mult + (i%3)*0.1)
        Candle(symbol,Timeframe.H4,i*14_400_000L,p,p,p,p,BigDecimal.ONE)
    }
    @Test fun secondBtcBetaClusterEntryIsBlocked() {
        val btc=series("BTCEUR",1.0); val eth=series("ETHEUR",2.0); val sol=series("SOLEUR",3.0)
        val a=CorrelationClusterGuard.assessCandidate("SOLEUR",sol,btc,mapOf("ETHEUR" to eth))
        assertFalse(a.allowed); assertTrue(a.existingBtcBetaPositions.contains("ETHEUR"))
    }
    @Test fun insufficientCorrelationHistoryFailsClosed() {
        val few=series("SOLEUR",1.0).take(5); val btc=series("BTCEUR",1.0).take(5)
        assertFalse(CorrelationClusterGuard.assessCandidate("SOLEUR",few,btc,emptyMap()).allowed)
    }
}
