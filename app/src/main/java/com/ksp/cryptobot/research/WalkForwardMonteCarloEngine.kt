package com.ksp.cryptobot.research

import com.ksp.cryptobot.data.TradeEntity
import kotlin.math.max
import kotlin.random.Random

class WalkForwardMonteCarloEngine {
    fun walkForward(strategy: String, symbol: String, trades: List<TradeEntity>, minSamples: Int = 10): WalkForwardAssessment {
        val outcomes = outcomePnl(strategy, symbol, trades)
        if (outcomes.size < minSamples) return WalkForwardAssessment(false,"WARMUP",0.0,0,0,outcomes.size,"n/a","n/a","Walk-forward warming up: ${outcomes.size}/$minSamples realized outcomes.")
        val windows = minOf(5, max(2, outcomes.size / 5))
        val chunk = max(2, outcomes.size / windows)
        var profitable=0; var total=0; var scoreSum=0.0
        var start=0
        while (start + chunk < outcomes.size) {
            val train = outcomes.subList(0, start + chunk)
            val testEnd = minOf(outcomes.size, start + chunk*2)
            val test = outcomes.subList(start+chunk, testEnd)
            if (test.isEmpty()) break
            val trainPnl=train.sum(); val testPnl=test.sum(); val testWins=test.count{it>0.0}
            if(testPnl>0.0) profitable++
            val winRate=testWins.toDouble()/test.size
            val consistency=if(trainPnl==0.0)0.0 else if(trainPnl>0 && testPnl>0)1.0 else if(trainPnl<0 && testPnl<0)0.35 else 0.0
            scoreSum += (winRate*70.0 + consistency*30.0).coerceIn(0.0,100.0)
            total++; start+=chunk
        }
        val score=if(total>0)scoreSum/total else 0.0
        val status=if(total>0 && profitable>=max(1,total/2) && score>=55.0)"PASS" else "WARN"
        return WalkForwardAssessment(true,status,score,profitable,total,outcomes.size,"growing/${chunk}","rolling/${chunk}","Walk-forward: windows=$total, profitable=$profitable, score=${"%.1f".format(score)}. Use unseen/forward outcomes before increasing live size.")
    }

    fun monteCarlo(strategy: String, symbol: String, trades: List<TradeEntity>, simulations: Int = 500, minSamples: Int = 10): MonteCarloAssessment {
        val outcomes=outcomePnl(strategy,symbol,trades)
        if(outcomes.size<minSamples) return MonteCarloAssessment(false,0.0,0.0,0.0,0.0,0.0,0.0,simulations,outcomes.size,"Monte Carlo warming up: ${outcomes.size}/$minSamples realized outcomes.")
        val rnd=Random(1337 xor symbol.hashCode() xor strategy.hashCode())
        val totals=DoubleArray(simulations); val drawdowns=DoubleArray(simulations)
        for(i in 0 until simulations){
            var equity=0.0;var peak=0.0;var maxDd=0.0
            repeat(outcomes.size){ equity += outcomes[rnd.nextInt(outcomes.size)]; peak=max(peak,equity); maxDd=max(maxDd,peak-equity) }
            totals[i]=equity;drawdowns[i]=maxDd
        }
        totals.sort();drawdowns.sort()
        fun q(a:DoubleArray,p:Double)=a[((a.size-1)*p).toInt().coerceIn(0,a.lastIndex)]
        val positive=totals.count{it>0}.toDouble()/totals.size
        val p05=q(totals,.05);val med=q(totals,.50);val p95=q(totals,.95);val dd95=q(drawdowns,.95)
        val meanAbs=outcomes.map{kotlin.math.abs(it)}.average().coerceAtLeast(0.01)
        val ddPenalty=(dd95/(meanAbs*outcomes.size.coerceAtLeast(1))).coerceIn(0.0,1.0)
        val score=(positive*75.0 + (if(p05>=0)20.0 else 0.0) - ddPenalty*25.0).coerceIn(0.0,100.0)
        return MonteCarloAssessment(true,score,positive,p05,med,p95,dd95,simulations,outcomes.size,"Monte Carlo: p(positive)=${"%.1f".format(positive*100)}%, p05=${"%.2f".format(p05)}, median=${"%.2f".format(med)}, p95DD=${"%.2f".format(dd95)}, score=${"%.1f".format(score)}.")
    }

    private fun outcomePnl(strategy:String,symbol:String,trades:List<TradeEntity>):List<Double>{
        val exact=trades.filter{it.symbol.equals(symbol,true) && it.realizedPnlEur.toDoubleOrNull()!=null && it.realizedPnlEur.toDoubleOrNull()!=0.0 && it.aiReason.contains(strategy,true)}
        val rows=if(exact.size>=5)exact else trades.filter{it.symbol.equals(symbol,true) && (it.realizedPnlEur.toDoubleOrNull()?:0.0)!=0.0}
        return rows.sortedBy{it.timestampEpochMs}.mapNotNull{it.realizedPnlEur.toDoubleOrNull()}.takeLast(200)
    }
}
