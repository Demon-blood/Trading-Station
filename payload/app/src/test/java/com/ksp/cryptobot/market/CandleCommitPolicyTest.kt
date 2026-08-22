package com.ksp.cryptobot.market

import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class CandleCommitPolicyTest {
    private fun c(t:Long)=Candle("BTCEUR",Timeframe.H4,t,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE,BigDecimal.ONE)
    @Test fun finalRestRowIsNeverCommitted() {
        val rows=listOf(c(1),c(2),c(3))
        assertEquals(listOf(1L,2L),CandleCommitPolicy.committedRows(rows).map{it.openTimeEpochMs})
    }
}
