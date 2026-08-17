package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.*
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.sqrt

/** Native port of the desktop v1.0.50 expanded strategy-vote family.
 * Votes are advisory inputs to the ensemble; Stage 3/4 safety and capital ceilings stay authoritative. */
class AdvancedStrategyVoteEngine(private val regimeEngine: AdvancedRegimeEngine = AdvancedRegimeEngine()) {
    fun evaluate(
        ticker: MarketTicker,
        candlesByTimeframe: Map<Timeframe, List<Candle>>,
        news: List<NewsArticle>,
        profile: AdvancedRegimeProfile
    ): List<ResearchStrategyVote> {
        val base = candlesByTimeframe[Timeframe.M15].orEmpty().ifEmpty { candlesByTimeframe[Timeframe.M5].orEmpty() }.ifEmpty { candlesByTimeframe[Timeframe.H1].orEmpty() }
        if (base.size < 35) return emptyList()
        val c = base.takeLast(120)
        val closes = c.map { it.close.toDouble() }
        val highs = c.map { it.high.toDouble() }
        val lows = c.map { it.low.toDouble() }
        val vols = c.map { it.volume.toDouble() }
        val last = closes.last(); val prev = closes.getOrElse(closes.lastIndex-1) { last }
        val ema9=ema(closes,9); val ema21=ema(closes,21); val ema50=ema(closes,50)
        val rsi14=rsi(closes,14); val atr=atrPct(c,14); val vwap=vwap(c,48)
        val bb=bb(closes,20,2.0); val donHigh=highs.dropLast(1).takeLast(40).maxOrNull() ?: last; val donLow=lows.dropLast(1).takeLast(40).minOrNull() ?: last
        val avgVol=vols.dropLast(1).takeLast(30).average().takeIf { !it.isNaN() && it>0 } ?: 1.0
        val volRatio=vols.last()/avgVol
        val change12=pct(closes.getOrElse(closes.lastIndex-12){closes.first()},last)
        val widthPct=if(last>0)(bb.third-bb.first)/last*100 else 0.0
        val range40=(highs.takeLast(40).maxOrNull()?:last)-(lows.takeLast(40).minOrNull()?:last)
        val nearLow=last <= (lows.takeLast(40).minOrNull()?:last)+range40*0.25
        val nearHigh=last >= (highs.takeLast(40).maxOrNull()?:last)-range40*0.25
        val newsSent = newsScore(news)
        val spreadPct = if (ticker.lastPrice > BigDecimal.ZERO) ticker.ask.subtract(ticker.bid).abs().divide(ticker.lastPrice,8,RoundingMode.HALF_UP).multiply(BigDecimal("100")).toDouble() else 99.0
        val votes=mutableListOf<ResearchStrategyVote>()

        fun add(name:String, raw:Int, action:SignalAction, reason:String) {
            val (w,wr)=regimeEngine.weight(profile,name)
            val score=(raw*w).toInt().coerceIn(0,100)
            val finalAction=when {
                action==SignalAction.SELL -> SignalAction.SELL
                score>=78 -> SignalAction.BUY
                score>=68 -> SignalAction.SMALL_BUY
                score>=55 -> SignalAction.WATCH
                else -> SignalAction.WAIT
            }
            votes += ResearchStrategyVote(name,finalAction,score,(score/100.0).coerceIn(0.0,1.0),(score-60).coerceIn(-12,12),"$reason; $wr")
        }

        add("VOLATILITY_BREAKOUT", 42 + bonus(last>donHigh,28) + bonus(volRatio>1.25,18), SignalAction.BUY, "close>${f(donHigh)}=${last>donHigh}, volumeRatio=${f(volRatio)}, ATR=${f(atr)}%")
        add("PULLBACK_CONTINUATION", 38 + bonus(profile.trend=="BULL_TREND",20,-10) + bonus(last<=ema21*1.006 && last>=ema21*0.985,20) + bonus(ema9>ema21,10), SignalAction.BUY, "trend=${profile.trend}, nearEMA21=${last<=ema21*1.006 && last>=ema21*0.985}, EMA9/21=${f(ema9)}/${f(ema21)}")
        add("MEAN_REVERSION", 40 + bonus(rsi14<32,28) + bonus(last<=bb.first,18) + bonus(profile.regime.contains("RANG"),10), SignalAction.BUY, "RSI=${f(rsi14)}, lowerBB=${f(bb.first)}, close=${f(last)}")
        add("VWAP_RECLAIM", 42 + bonus(prev<vwap && last>=vwap,28) + bonus(ema9>=ema21,14), SignalAction.BUY, "VWAP=${f(vwap)}, reclaimed=${prev<vwap && last>=vwap}")
        val sweep = lows.takeLast(10).dropLast(1).minOrNull()?.let { c.last().low.toDouble()<it && last>it } ?: false
        add("LIQUIDITY_SWEEP_REVERSAL", 42 + bonus(sweep,32) + bonus(rsi14<40,10), SignalAction.BUY, "downsideSweep=$sweep, RSI=${f(rsi14)}")
        add("BOLLINGER_SQUEEZE_BREAKOUT", 38 + bonus(widthPct<2.0,16) + bonus(last>bb.third,30) + bonus(volRatio>1.2,12), SignalAction.BUY, "BBwidth=${f(widthPct)}%, aboveUpper=${last>bb.third}, volRatio=${f(volRatio)}")
        add("EMA_TREND_RIDER", 40 + bonus(ema9>ema21 && ema21>ema50,34) + bonus(last>ema9,12), SignalAction.BUY, "EMA9/21/50=${f(ema9)}/${f(ema21)}/${f(ema50)}")
        val bullishDiv = rsi14<42 && last<=closes.takeLast(20).minOrNull()!!.times(1.01) && change12<0
        add("RSI_DIVERGENCE_REVERSAL", 40 + bonus(bullishDiv,32) + bonus(profile.trend!="BEAR_TREND",8), SignalAction.BUY, "bullishDivergenceProxy=$bullishDiv, RSI=${f(rsi14)}, change12=${f(change12)}%")
        add("SUPERTREND", 42 + bonus(last>ema21 && atr>0.25,27) + bonus(ema9>ema21,12), SignalAction.BUY, "supertrendProxy=${last>ema21}, ATR=${f(atr)}%")
        add("SUPPORT_RESISTANCE_BOUNCE", 40 + bonus(nearLow,28) + bonus(rsi14<45,10), SignalAction.BUY, "nearSupport=$nearLow, nearResistance=$nearHigh")
        add("DONCHIAN_CHANNEL_BREAKOUT", 42 + bonus(last>donHigh,32) + bonus(volRatio>1.2,14), SignalAction.BUY, "DonchianHigh=${f(donHigh)}, breakout=${last>donHigh}")
        val kelMid=ema(closes,20); val kelUpper=kelMid*(1+atr/100*1.5)
        add("KELTNER_CHANNEL_BREAKOUT", 42 + bonus(last>kelUpper,32) + bonus(volRatio>1.15,12), SignalAction.BUY, "KeltnerUpper=${f(kelUpper)}, breakout=${last>kelUpper}")
        val macd=ema(closes,12)-ema(closes,26); val macdPrev=ema(closes.dropLast(1),12)-ema(closes.dropLast(1),26)
        add("MACD_TREND_CROSS", 42 + (if(macd>0 && macdPrev<=0)32 else if(macd>0)14 else 0) + bonus(profile.trend=="BULL_TREND",10), SignalAction.BUY, "MACD=${f(macd)}, prior=${f(macdPrev)}")
        val stoch=stochastic(c,14)
        add("STOCHASTIC_OVERSOLD_REVERSAL", 42 + bonus(stoch<20,30) + bonus(last>prev,10), SignalAction.BUY, "stochastic=${f(stoch)}, rising=${last>prev}")
        val cci=cci(c,20)
        add("CCI_MEAN_REVERSION", 42 + bonus(cci < -100,30) + bonus(profile.regime.contains("RANG"),10), SignalAction.BUY, "CCI=${f(cci)}")
        val adxProxy=abs(pct(ema21,ema9))*8.0
        add("ADX_TREND_PULLBACK", 40 + bonus(adxProxy>20 && ema9>ema21,28) + bonus(last>=ema21*0.99 && last<=ema9*1.01,14), SignalAction.BUY, "ADXproxy=${f(adxProxy)}, pullback=${last>=ema21*0.99 && last<=ema9*1.01}")
        add("PARABOLIC_SAR_FLIP", 42 + bonus(prev<ema21 && last>ema21,30) + bonus(last>ema9,12), SignalAction.BUY, "SARflipProxy=${prev<ema21 && last>ema21}")
        val ichiTenkan=(highs.takeLast(9).maxOrNull()!!+lows.takeLast(9).minOrNull()!!)/2.0; val ichiKijun=(highs.takeLast(26).maxOrNull()!!+lows.takeLast(26).minOrNull()!!)/2.0
        add("ICHIMOKU_CLOUD_BREAKOUT", 40 + bonus(last>ichiTenkan && ichiTenkan>ichiKijun,32) + bonus(profile.trend=="BULL_TREND",10), SignalAction.BUY, "Tenkan/Kijun=${f(ichiTenkan)}/${f(ichiKijun)}")
        val prevRange=(highs.dropLast(1).takeLast(20).maxOrNull()!!-lows.dropLast(1).takeLast(20).minOrNull()!!).coerceAtLeast(1e-12); val curRange=highs.takeLast(20).maxOrNull()!!-lows.takeLast(20).minOrNull()!!
        add("ROLLING_RANGE_EXPANSION", 40 + bonus(curRange>prevRange*1.15 && last>closes.dropLast(1).takeLast(20).maxOrNull()!!,30) + bonus(volRatio>1.2,12), SignalAction.BUY, "rangeExpansion=${f(curRange/prevRange)}, volRatio=${f(volRatio)}")
        val vwapDev=if(vwap>0)(last-vwap)/vwap*100 else 0.0
        add("VWAP_DEVIATION_REVERSION", 42 + bonus(vwapDev < -1.2,30) + bonus(rsi14<38,10), SignalAction.BUY, "VWAPdev=${f(vwapDev)}%, RSI=${f(rsi14)}")
        add("NEWS_MOMENTUM_CONFIRMATION", 40 + (if(newsSent>=20)25 else if(newsSent<=-20)-20 else 0) + bonus(ticker.priceChangePercent24h>BigDecimal.ZERO,12), if(newsSent<=-30) SignalAction.WAIT else SignalAction.BUY, "newsSentiment=$newsSent, 24h=${ticker.priceChangePercent24h}%")
        add("SPREAD_LIQUIDITY_SCALP", 40 + bonus(spreadPct<=0.20,25,-20) + bonus(ticker.volume24h>=BigDecimal("1000000"),18) + bonus(abs(change12)<3.0,8), SignalAction.BUY, "spread=${f(spreadPct)}%, vol24h=${ticker.volume24h}")
        val frames=listOf(Timeframe.M5,Timeframe.M15,Timeframe.H1); val bullish=frames.count { tf -> candlesByTimeframe[tf].orEmpty().let { it.size>=12 && it.last().close>it[it.size-12].close } }
        add("MULTI_TIMEFRAME_CONFIRMATION", 40 + bullish*15, SignalAction.BUY, "bullishFrames=$bullish/${frames.size}")
        return votes.sortedByDescending { it.score }
    }

    private fun bonus(condition:Boolean, yes:Int, no:Int=0)=if(condition)yes else no
    private fun ema(v:List<Double>,p:Int):Double { if(v.isEmpty())return 0.0; val k=2.0/(p+1); var x=v.take(p).average(); for(n in v.drop(p))x=n*k+x*(1-k); return x }
    private fun pct(a:Double,b:Double)=if(a==0.0)0.0 else (b-a)/a*100.0
    private fun rsi(v:List<Double>,p:Int):Double { if(v.size<p+1)return 50.0; val d=v.takeLast(p+1).zipWithNext().map{it.second-it.first}; val g=d.filter{it>0}.sum()/p; val l=-d.filter{it<0}.sum()/p; return if(l<=1e-12)100.0 else 100-100/(1+g/l) }
    private fun atrPct(c:List<Candle>,p:Int):Double { if(c.size<p+1)return 0.0; var prev=c[c.size-p-1].close.toDouble(); val out=mutableListOf<Double>(); for(x in c.takeLast(p)){ val h=x.high.toDouble();val l=x.low.toDouble();val cl=x.close.toDouble();if(cl>0)out+=maxOf(h-l,abs(h-prev),abs(l-prev))/cl*100;prev=cl};return out.average() }
    private fun vwap(c:List<Candle>,n:Int):Double { val w=c.takeLast(n); val vs=w.sumOf{it.volume.toDouble().coerceAtLeast(0.0)}; return if(vs<=0)0.0 else w.sumOf{((it.high.toDouble()+it.low.toDouble()+it.close.toDouble())/3.0)*it.volume.toDouble().coerceAtLeast(0.0)}/vs }
    private fun bb(v:List<Double>,p:Int,m:Double):Triple<Double,Double,Double>{ val w=v.takeLast(p);val mid=w.average();val sd=sqrt(w.sumOf{(it-mid)*(it-mid)}/w.size);return Triple(mid-m*sd,mid,mid+m*sd) }
    private fun stochastic(c:List<Candle>,p:Int):Double{ val w=c.takeLast(p); val h=w.maxOf{it.high.toDouble()};val l=w.minOf{it.low.toDouble()};return if(h==l)50.0 else (w.last().close.toDouble()-l)/(h-l)*100 }
    private fun cci(c:List<Candle>,p:Int):Double{ val w=c.takeLast(p);val tp=w.map{(it.high.toDouble()+it.low.toDouble()+it.close.toDouble())/3};val m=tp.average();val md=tp.map{abs(it-m)}.average();return if(md<=1e-12)0.0 else (tp.last()-m)/(0.015*md) }
    private fun newsScore(news:List<NewsArticle>):Int { val positive=listOf("surge","approval","adoption","upgrade","partnership","record","bullish","launch"); val negative=listOf("hack","exploit","ban","lawsuit","fraud","outage","bearish","liquidation"); var s=0; news.take(30).forEach{ val t=(it.title+" "+it.description).lowercase(); s+=positive.count{p->p in t}*3; s-=negative.count{p->p in t}*4 }; return s.coerceIn(-40,40) }
    private fun f(v:Double)=BigDecimal.valueOf(v).setScale(3,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
