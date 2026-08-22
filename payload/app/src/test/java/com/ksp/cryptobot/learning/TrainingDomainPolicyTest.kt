package com.ksp.cryptobot.learning

import com.ksp.cryptobot.data.TradeEntity
import org.junit.Assert.*
import org.junit.Test

class TrainingDomainPolicyTest {
    private fun t(paper:Boolean,id:Long)=TradeEntity(id,"BTCEUR","BUY","1","1","0",paper,timestampEpochMs=id)
    @Test fun paperAndLiveAreSeparated() {
        val rows=listOf(t(true,1),t(false,2),t(true,3))
        val p=TrainingDomainPolicy.filterTrades(rows,TrainingDomain.PAPER,true)
        val l=TrainingDomainPolicy.filterTrades(rows,TrainingDomain.LIVE,true)
        assertEquals(2,p.size); assertTrue(p.all{it.paper})
        assertEquals(1,l.size); assertTrue(l.none{it.paper})
        TrainingDomainPolicy.assertNoCrossDomainUse(TrainingDomain.PAPER,p,true)
        TrainingDomainPolicy.assertNoCrossDomainUse(TrainingDomain.LIVE,l,true)
    }
    @Test fun shadowAndBacktestDoNotInferDomainFromGenericJournal() {
        val rows=listOf(t(true,1),t(false,2))
        assertTrue(TrainingDomainPolicy.filterTrades(rows,TrainingDomain.SHADOW,true).isEmpty())
        assertTrue(TrainingDomainPolicy.filterTrades(rows,TrainingDomain.BACKTEST,true).isEmpty())
    }
}
