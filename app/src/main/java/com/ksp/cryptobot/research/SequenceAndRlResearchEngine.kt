package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.ResearchDao
import com.ksp.cryptobot.data.ResearchStateEntity
import com.ksp.cryptobot.data.TradeEntity
import java.math.BigDecimal
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.math.tanh

class SequenceAndRlResearchEngine(private val dao: ResearchDao) {
    private val inputSize=9; private val hidden=8

    suspend fun sequence(ticker:MarketTicker,candles:List<Candle>,orderBook:OrderBookSnapshot?):SequenceModelAssessment{
        val state=loadSequence(); val x=features(ticker,candles,orderBook); val (_,p)=forward(state,x)
        rememberFeatures(ticker.symbol,x)
        val adj=when { state.samples<25 -> 0; p>=.62 -> (((p-.55)*20).toInt()).coerceIn(0,6); p<=.42 -> -(((.50-p)*22).toInt()).coerceIn(0,8); else -> 0 }
        val reason=when { state.samples<25 -> "Sequence model warming up; samples=${state.samples}, pProfit=${"%.2f".format(p)}."; adj>0 -> "Sequence model positive; pProfit=${"%.2f".format(p)}, samples=${state.samples}."; adj<0 -> "Sequence model cautious; pProfit=${"%.2f".format(p)}, samples=${state.samples}."; else -> "Sequence model neutral; pProfit=${"%.2f".format(p)}." }
        return SequenceModelAssessment(adj,p,state.samples,reason)
    }

    suspend fun rl(decision:AiDecision,ticker:MarketTicker,regime:String,strategy:String):RlSandboxAssessment{
        val spread=if(ticker.lastPrice>BigDecimal.ZERO)ticker.ask.subtract(ticker.bid).abs().divide(ticker.lastPrice,8,java.math.RoundingMode.HALF_UP).multiply(BigDecimal("100")).toDouble() else 99.0
        val st="$regime|$strategy|${if(spread>.35)"wide" else "tight"}|${if(decision.finalScore>=75)"high" else if(decision.finalScore>=62)"mid" else "low"}"
        val q=loadQ(st); val sorted=q.entries.sortedByDescending{it.value}; val best=sorted.firstOrNull()?.key?:"HOLD"; val confidence=(sorted.getOrNull(0)?.value?:0.0)-(sorted.getOrNull(1)?.value?:0.0)
        val adj=if(confidence>.35) when { best=="BUY" && decision.finalAction in setOf(SignalAction.BUY,SignalAction.SMALL_BUY)->3; best=="HOLD" && decision.finalAction in setOf(SignalAction.BUY,SignalAction.SMALL_BUY)->-4; best=="SELL" && decision.finalAction==SignalAction.SELL->2; else->0 } else 0
        return RlSandboxAssessment(adj,st,best,confidence,"RL sandbox state=$st; best=$best; confidence=${"%.2f".format(confidence)}; adj=$adj.")
    }

    suspend fun trainFromNewOutcomes(trades:List<TradeEntity>):Int{
        val marker=(dao.state("research_last_trained_trade_id")?.value?.toLongOrNull()?:0L)
        // Desktop observes every new trade for RL; sequence supervision is strongest on realized SELL outcomes
        // (profitable outcomes are positive labels; non-positive SELL outcomes are negative labels).
        val rows=trades.filter{it.id>marker}.sortedBy{it.id}
        var trained=0; var maxId=marker
        for(t in rows){ trainSequence(t); trainRl(t); trained++; if(t.id>maxId)maxId=t.id }
        if(maxId>marker)dao.putState(ResearchStateEntity("research_last_trained_trade_id",maxId.toString()))
        return trained
    }

    private data class Seq(var samples:Int,var w1:Array<DoubleArray>,var b1:DoubleArray,var w2:DoubleArray,var b2:Double)
    private suspend fun loadSequence():Seq{
        val raw=dao.state("sequence_model_v2_desktop_parity")?.value
        if(!raw.isNullOrBlank()) {
            runCatching { decode(raw) }.getOrNull()?.let { decoded ->
                if (decoded.w1.size == hidden && decoded.b1.size == hidden && decoded.w2.size == hidden && decoded.w1.all { it.size == inputSize }) return decoded
            }
        }
        // Exact desktop TinySequenceNeuralModel initialization from Python random.Random(1337).
        // Hard-coded so Android and desktop begin from identical weights instead of Kotlin's different RNG sequence.
        return desktopInitialSequence()
    }
    private suspend fun saveSequence(s:Seq)=dao.putState(ResearchStateEntity("sequence_model_v2_desktop_parity",encode(s)))

    private fun desktopInitialSequence(): Seq = Seq(
        samples = 0,
        w1 = arrayOf(
            doubleArrayOf(0.0353258570854412,0.009979672081500246,-0.040245492225187335,0.025736206170681453,-0.10029381489336575,0.09731212407228942,-0.0348885574157374,0.0868838474747062,0.12651795296887788),
            doubleArrayOf(-0.05770998360614926,0.14767412177574615,-0.08851619937672547,0.04688328995666666,0.12369160286681283,-0.11721348071361858,0.09613128893494044,-0.030378283783510665,-0.13039706087397107),
            doubleArrayOf(0.05976249948469284,-0.04556941713020701,-0.028923740826466135,0.09048376397239657,0.09001953969340776,0.049124664853123556,0.09975195978545315,0.028337042298623938,-0.011719896093650373),
            doubleArrayOf(0.13726901599291877,0.08871607896353215,-0.05588163060114694,0.05716241133609706,0.12452999935580525,-0.012852724037883012,-0.07054314359130488,-0.08970555168146446,-0.129809918317906),
            doubleArrayOf(0.00763302811120245,0.035601966015498476,0.023189323634293574,-0.13683587892598412,-0.027758999252679134,0.04587682910685781,0.03592366364709751,0.05267966244475658,0.040499365024610795),
            doubleArrayOf(-0.09089338458048489,-0.0645222495572833,-0.014301539028187121,0.12495924532336303,0.08947164766940091,-0.05571512726143264,0.08862222866014105,0.03323797285614602,-0.02034938654577234),
            doubleArrayOf(0.0871282813596844,0.014882773263378196,0.08279904792902396,0.016623056813794668,0.04372700595996995,0.10452894395159915,-0.08590485018923161,0.1423383016455045,0.008722741589055477),
            doubleArrayOf(0.008534429635824431,0.08259639068537389,-0.13486973327465987,-0.0016021310456707516,0.07094965782111895,-0.013444349229195796,0.11963682850725313,0.09149671090163139,-0.0037237989701467133)
        ),
        b1 = DoubleArray(8),
        w2 = doubleArrayOf(0.07565429729417514,0.06866123810332589,-0.044217318144365386,0.01751395269869427,0.020692674021419777,-0.07281370402474842,-0.12996353721151593,-0.11085284885502414),
        b2 = 0.0
    )
    private fun forward(s:Seq,x:DoubleArray):Pair<DoubleArray,Double>{ val h=DoubleArray(hidden){i->tanh(s.w1[i].indices.sumOf{j->s.w1[i][j]*x[j]}+s.b1[i])}; val y=sigmoid(h.indices.sumOf{i->s.w2[i]*h[i]}+s.b2);return h to y }
    private suspend fun trainSequence(t:TradeEntity){ val pnl=t.realizedPnlEur.toDoubleOrNull()?:0.0; val label=when{pnl>0.0->1.0;t.side.equals("SELL",true)->0.0;else->return};val raw=dao.state("sequence_features_${t.symbol.uppercase()}")?.value ?: return; val x=raw.split(',').mapNotNull{it.toDoubleOrNull()}.toDoubleArray();if(x.size!=inputSize)return;val s=loadSequence();val(h,y)=forward(s,x);val lr=.025;val err=y-label;for(i in 0 until hidden)s.w2[i]-=lr*err*h[i];s.b2-=lr*err;for(i in 0 until hidden){/* Desktop intentionally uses the already-updated output weight here. */val dh=(1-h[i]*h[i])*err*s.w2[i];for(j in 0 until inputSize)s.w1[i][j]-=lr*dh*x[j];s.b1[i]-=lr*dh};s.samples++;saveSequence(s) }
    private suspend fun rememberFeatures(symbol:String,x:DoubleArray)=dao.putState(ResearchStateEntity("sequence_features_${symbol.uppercase()}",x.joinToString(",")))

    private suspend fun loadQ(state:String):MutableMap<String,Double>{ val raw=dao.state("rl_${state.hashCode()}")?.value;val m=mutableMapOf("BUY" to 0.0,"HOLD" to 0.0,"SELL" to 0.0);raw?.split(';')?.forEach{p->val a=p.split('=');if(a.size==2)m[a[0]]=a[1].toDoubleOrNull()?:0.0};return m }
    private suspend fun saveQ(state:String,q:Map<String,Double>)=dao.putState(ResearchStateEntity("rl_${state.hashCode()}",q.entries.joinToString(";"){"${it.key}=${it.value}"}))
    private suspend fun loadQWithPrefix(prefix:String,state:String):MutableMap<String,Double>{ val raw=dao.state("${prefix}_${state.hashCode()}")?.value;val m=mutableMapOf("BUY" to 0.0,"HOLD" to 0.0,"SELL" to 0.0);raw?.split(';')?.forEach{p->val a=p.split('=');if(a.size==2)m[a[0]]=a[1].toDoubleOrNull()?:0.0};return m }
    private suspend fun saveQWithPrefix(prefix:String,state:String,q:Map<String,Double>)=dao.putState(ResearchStateEntity("${prefix}_${state.hashCode()}",q.entries.joinToString(";"){"${it.key}=${it.value}"}))
    private suspend fun trainRl(t:TradeEntity){
        val observed=dao.state("research_last_rl_state_${t.symbol.uppercase()}")?.value ?: return
        val parts=observed.split('|')
        val scoreBucket=parts.getOrNull(3) ?: "low"
        val regime=parts.getOrNull(0) ?: "UNKNOWN"
        val strategy=parts.getOrNull(1) ?: "AUTO"
        val parityState="$regime|$strategy|unknown|$scoreBucket"
        val action=if(t.side.equals("SELL",true))"SELL" else "BUY"
        val pnl=t.realizedPnlEur.toDoubleOrNull()?:0.0
        val value=(t.priceEur.toDoubleOrNull()?:0.0)*(t.quantity.toDoubleOrNull()?:0.0)
        // Exact desktop RL reward: BUY observation gets weak +0.02; SELL uses realized return.
        val parityReward=if(t.side.equals("SELL",true))(if(value>0)pnl/maxOf(1.0,value) else pnl).coerceIn(-1.0,1.0) else .02
        val pq=loadQWithPrefix("rlp",parityState); val pold=pq[action]?:0.0; pq[action]=pold+.15*(parityReward-pold); saveQWithPrefix("rlp",parityState,pq)
        // Professional corrected table learns against the actual observed spread state, making the advice usable
        // without deleting the desktop-parity table/behavior above.
        val proReward=(if(value>0)pnl/value else pnl).coerceIn(-1.0,1.0)
        val q=loadQ(observed);val old=q[action]?:0.0;q[action]=old+.15*(proReward-old);saveQ(observed,q)
    }
    suspend fun rememberRlState(symbol:String,state:String)=dao.putState(ResearchStateEntity("research_last_rl_state_${symbol.uppercase()}",state))

    private fun features(t:MarketTicker,c:List<Candle>,book:OrderBookSnapshot?):DoubleArray{ val closes=c.filter{it.close>BigDecimal.ZERO}.map{it.close.toDouble()};val rets=closes.zipWithNext().mapNotNull{(a,b)->if(a==0.0)null else (b-a)/a};val mom3=if(rets.size>=3)rets.takeLast(3).sum() else 0.0;val mom12=if(rets.size>=12)rets.takeLast(12).sum() else 0.0;val vol=if(rets.size>=3){val w=rets.takeLast(30);val m=w.average();sqrt(w.sumOf{(it-m)*(it-m)}/w.size)}else 0.0;val lastC=c.lastOrNull();val range=if(lastC!=null&&lastC.close>BigDecimal.ZERO)lastC.high.subtract(lastC.low).divide(lastC.close,12,java.math.RoundingMode.HALF_UP).toDouble() else 0.0;val spread=if(t.lastPrice>BigDecimal.ZERO)t.ask.subtract(t.bid).abs().divide(t.lastPrice,12,java.math.RoundingMode.HALF_UP).multiply(BigDecimal("100")).toDouble() else 2.0;val bids=book?.bids.orEmpty();val asks=book?.asks.orEmpty();val bq=bids.take(10).sumOf{it.price.multiply(it.quantity).toDouble()};val aq=asks.take(10).sumOf{it.price.multiply(it.quantity).toDouble()};val depth=bq+aq;val imbalance=(bq-aq)/maxOf(1.0,depth);return doubleArrayOf(clamp(mom3*100,-5.0,5.0)/5.0,clamp(mom12*100,-10.0,10.0)/10.0,clamp(vol*100,0.0,6.0)/6.0,clamp(range*100,0.0,6.0)/6.0,clamp(spread,0.0,2.0)/2.0,clamp(t.priceChangePercent24h.toDouble(),-12.0,12.0)/12.0,clamp(ln(t.volume24h.toDouble().coerceAtLeast(1.0))/ln(10.0)/8.0,0.0,1.0),clamp(imbalance,-1.0,1.0),clamp(ln(depth.coerceAtLeast(1.0))/ln(10.0)/8.0,0.0,1.0)) }
    private fun encode(s:Seq)=buildString{append(s.samples).append('|').append(s.b2).append('|').append(s.b1.joinToString(",")).append('|').append(s.w2.joinToString(",")).append('|').append(s.w1.joinToString(";"){it.joinToString(",")})}
    private fun decode(raw:String):Seq{val p=raw.split('|');val samples=p[0].toInt();val b2=p[1].toDouble();val b1=p[2].split(',').map{it.toDouble()}.toDoubleArray();val w2=p[3].split(',').map{it.toDouble()}.toDoubleArray();val w1=p[4].split(';').map{r->r.split(',').map{it.toDouble()}.toDoubleArray()}.toTypedArray();return Seq(samples,w1,b1,w2,b2)}
    private fun sigmoid(x:Double)=when{ x < -35 -> 0.0; x > 35 -> 1.0; else -> 1.0/(1.0+exp(-x)) }
    private fun clamp(v:Double,lo:Double,hi:Double)=v.coerceIn(lo,hi)
}

class OrderBookReplayResearchEngine {
    fun evaluate(decision:AiDecision,ticker:MarketTicker,book:OrderBookSnapshot?,requestedQuote:Double):Pair<Int,String>{
        if(book==null)return 0 to "Order-book replay unavailable; no depth snapshot."
        val levels=if(decision.finalAction in setOf(SignalAction.BUY,SignalAction.SMALL_BUY))book.asks else book.bids;var remaining=requestedQuote.coerceAtLeast(0.0);var filledQty=0.0;var spent=0.0;var used=0
        for(l in levels.take(25)){val p=l.price.toDouble();val q=l.quantity.toDouble();val value=p*q;val take=minOf(remaining,value);if(take<=0)break;filledQty+=take/p;spent+=take;remaining-=take;used++;if(remaining<=1e-9)break}
        val avg=if(filledQty>0)spent/filledQty else 0.0;val ref=if(decision.finalAction in setOf(SignalAction.BUY,SignalAction.SMALL_BUY))ticker.ask.toDouble() else ticker.bid.toDouble();val slip=if(ref>0&&avg>0)(avg-ref)/ref*100 else 0.0;val fill=spent/requestedQuote.coerceAtLeast(1.0);val adj=when{fill<.75||kotlin.math.abs(slip)>.45->-6;kotlin.math.abs(slip)>.20->-2;else->1};return adj to "Order-book replay fill=${"%.2f".format(fill)}, slippage=${"%.3f".format(slip)}%, levels=$used."
    }
}
