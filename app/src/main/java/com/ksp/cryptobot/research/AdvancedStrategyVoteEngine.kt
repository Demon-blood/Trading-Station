package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.*
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Crypto TradeStation strategy engine: desktop parity + practitioner expansion.
 *
 * Layer 1 ports desktop v1.0.50 strategy_expansion.py behavior and thresholds.
 * Layer 2 adds evidence-informed professional variants (MTF trend, Wilder ADX/DMI,
 * true PSAR/Supertrend, volume-confirmed breakouts, ATR risk discipline, anchored
 * VWAP and longer-horizon trend following).  Layer 2 never bypasses M3/M4 safety.
 */
class AdvancedStrategyVoteEngine(private val regimeEngine: AdvancedRegimeEngine = AdvancedRegimeEngine()) {
    private data class Vote(
        val name: String,
        val action: SignalAction,
        val delta: Int,
        val confidence: Double,
        val reason: String,
        val entry: Boolean = true,
        val professional: Boolean = false
    )

    fun evaluate(
        settings: BotSettings,
        ticker: MarketTicker,
        candlesByTimeframe: Map<Timeframe, List<Candle>>,
        news: List<NewsArticle>,
        profile: AdvancedRegimeProfile,
        professionalEnabled: Boolean = true,
        upstreamNewsScore: Int? = null
    ): List<ResearchStrategyVote> {
        val baseTimeframe = when {
            candlesByTimeframe[Timeframe.M15].orEmpty().isNotEmpty() -> Timeframe.M15
            candlesByTimeframe[Timeframe.M5].orEmpty().isNotEmpty() -> Timeframe.M5
            else -> Timeframe.H1
        }
        val base = candlesByTimeframe[baseTimeframe].orEmpty()
        if (base.size < 35) return emptyList()
        val candles = base.takeLast(240)
        // Desktop StrategyExpansionEngine receives the upstream news score.  Use that exact value
        // when available; list-based scoring remains only a compatibility fallback.
        val newsScore = upstreamNewsScore ?: newsScore(news)
        val parityExtraTimeframes = candlesByTimeframe.filterKeys { it != baseTimeframe }
        val votes = mutableListOf<Vote>()

        // ----- Desktop v1.0.50 parity layer -----
        votes += volatilityBreakout(ticker, candles)
        votes += pullbackContinuation(ticker, candles, profile)
        votes += meanReversion(ticker, candles, profile)
        votes += vwapReclaim(ticker, candles)
        votes += liquiditySweep(ticker, candles)
        votes += bollingerSqueezeBreakout(ticker, candles)
        votes += emaTrendRider(ticker, candles)
        votes += rsiDivergenceReversal(ticker, candles, profile)
        votes += desktopSupertrend(ticker, candles)
        votes += supportResistanceBounce(ticker, candles, profile)
        votes += donchianBreakout(ticker, candles)
        votes += keltnerBreakout(ticker, candles)
        votes += macdTrendCross(ticker, candles)
        votes += stochasticOversoldReversal(ticker, candles)
        votes += cciMeanReversion(ticker, candles)
        votes += desktopAdxTrendPullback(ticker, candles)
        votes += desktopParabolicSarFlip(ticker, candles)
        votes += ichimokuCloudBreakout(ticker, candles)
        votes += rollingRangeExpansion(ticker, candles)
        votes += vwapDeviationReversion(ticker, candles)
        votes += newsMomentum(ticker, candles, newsScore)
        votes += spreadLiquidityScalp(settings, ticker, candles)
        votes += multiTimeframeConfirmation(ticker, candles, parityExtraTimeframes)
        votes += exitOptimizerHint(ticker, candles, profile)

        // ----- Practitioner / investor-use layer -----
        if (professionalEnabled) {
            votes += proTrendMtfBreakout(ticker, candles, candlesByTimeframe, profile)
            votes += proTrendPullbackAtr(ticker, candles, candlesByTimeframe, profile)
            votes += proRangeReversion(ticker, candles, profile)
            votes += proVolumeConfirmedBreakout(ticker, candles, profile)
            votes += proSupertrendConfirmed(ticker, candles, candlesByTimeframe, profile)
            votes += proPsarTrendFlip(ticker, candles, candlesByTimeframe, profile)
            votes += proIchimokuMtf(ticker, candles, candlesByTimeframe, profile)
            votes += proAnchoredVwapReclaim(ticker, candles, profile)
            votes += proCompressionExpansion(ticker, candles, profile)
            votes += positionTrendFollowing(ticker, candlesByTimeframe, profile)
            votes += proDmiAdxTrendPullback(ticker, candles, candlesByTimeframe)
            votes += proLongHorizon50200(ticker, candlesByTimeframe)
            votes += proTimeSeriesMomentum(ticker, candlesByTimeframe)
            votes += proDualDonchianBreakout(ticker, candles, candlesByTimeframe)
            votes += proVwapTrendRetest(ticker, candles, candlesByTimeframe)
            votes += proMacdMtfConfirmation(ticker, candles, candlesByTimeframe)
            votes += proBreakoutRetest(ticker, candles, candlesByTimeframe)
            votes += proObvAccumulation(ticker, candles, candlesByTimeframe)
            votes += proPriceStructureTrend(ticker, candles, candlesByTimeframe)
            votes += proZscoreRangeReversion(ticker, candles)
            votes += professionalExecutionFilter(settings, ticker, candles)
            votes += professionalMtfFilter(candlesByTimeframe)
            votes += professionalVolumeFilter(candles)
        }

        return votes.map { vote -> toResearchVote(vote, profile) }
            .sortedWith(compareByDescending<ResearchStrategyVote> { it.adjustment > 0 }
                .thenByDescending { it.score }
                .thenByDescending { abs(it.adjustment) })
    }

    /** Exact desktop combiner: sum(score_delta * confidence), clamp, then round once.
     * ResearchStrategyVote.adjustment stores the raw (or regime-weighted professional) delta. */
    fun ensembleAdjustment(votes: List<ResearchStrategyVote>): Int {
        val weighted = votes.sumOf { it.adjustment.toDouble() * it.confidence.coerceIn(0.0, 1.0) }
        return weighted.coerceIn(-25.0, 25.0).roundToInt()
    }

    fun isEntryStrategy(name: String): Boolean = !name.startsWith("FILTER_") && name != "EXIT_OPTIMIZER"

    private fun toResearchVote(v: Vote, profile: AdvancedRegimeProfile): ResearchStrategyVote {
        val (weight, weightReason) = regimeEngine.weight(profile, v.name)
        val confidence = v.confidence.coerceIn(0.0, 1.0)
        // Desktop parity adjustment is exactly score_delta * confidence. Professional variants
        // additionally honor the regime compatibility multiplier.
        val adjustment = if (v.professional)
            (v.delta * weight).roundToInt().coerceIn(-12, 12)
        else
            v.delta.coerceIn(-12, 12)
        val rankBase = 50.0 + v.delta * 4.0 + confidence * 20.0
        val rank = (rankBase * if (v.professional) weight else 1.0).roundToInt().coerceIn(0, 100)
        // Preserve the strategy's native desktop action. Desktop HOLD maps to Android WAIT.
        val action = v.action
        val layer = if (v.professional) "professional" else "desktop-parity"
        return ResearchStrategyVote(v.name, action, rank, confidence, adjustment, "[$layer] ${v.reason}; $weightReason")
    }

    // =============================================================================================
    // Desktop v1.0.50 strategy_expansion.py parity implementations
    // =============================================================================================

    private fun volatilityBreakout(t: MarketTicker, c: List<Candle>): Vote {
        val closes = StrategyMath.closes(c)
        val atrNow = StrategyMath.atrPct(c, 14)
        val atrPrev = if (c.size > 28) StrategyMath.atrPct(c.dropLast(8), 14) else 0.0
        val recentHigh = c.takeLast(21).dropLast(1).maxOf { it.high.toDouble() }
        val volRecent = c.takeLast(6).map { max(0.0, it.volume.toDouble()) }.average()
        val volBase = if (c.size >= 42) c.dropLast(6).takeLast(30).map { max(0.0, it.volume.toDouble()) }.average() else volRecent
        val compression = StrategyMath.volatilityPct(closes.dropLast(4), 20)
        val last = t.lastPrice.toDouble()
        val expanding = atrNow > max(atrPrev * 1.15, 0.05)
        val distance = if (recentHigh > 0 && last <= recentHigh) (recentHigh - last) / recentHigh * 100.0 else 0.0
        val near = last <= recentHigh && distance <= 0.25
        return when {
            (last > recentHigh || near) && expanding && volRecent >= max(volBase * 1.50, 0.0) -> {
                val broken = last > recentHigh
                Vote("VOLATILITY_BREAKOUT", SignalAction.BUY, if (broken) 10 else 7, if (broken) .78 else .70,
                    "${if (broken) "20-bar high broken" else "near breakout within ${f(distance)}% of 20-bar high"}, ATR ${f(atrNow)}% expanding, volume >=1.5x average")
            }
            compression < .20 && atrNow > 0 -> Vote("VOLATILITY_BREAKOUT", SignalAction.WAIT, 3, .45, "compression watch, ATR ${f(atrNow)}%")
            else -> Vote("VOLATILITY_BREAKOUT", SignalAction.WAIT, 0, .25, "no breakout confirmation")
        }
    }

    private fun pullbackContinuation(t: MarketTicker, c: List<Candle>, p: AdvancedRegimeProfile): Vote {
        val closes = StrategyMath.closes(c); val e20 = StrategyMath.ema(closes, 20)
        val e50 = if (closes.size >= 50) StrategyMath.ema(closes, 50) else StrategyMath.ema(closes, 21)
        val rsi = StrategyMath.rsi(closes, 14); val last = t.lastPrice.toDouble()
        val near = if (e20 != 0.0) abs(last - e20) / e20 * 100.0 else 999.0
        val mom = StrategyMath.momentumPct(closes, 5)
        return when {
            e20 > e50 && near <= .9 && rsi in 42.0..68.0 && mom > 0 -> Vote("PULLBACK_CONTINUATION", SignalAction.BUY, 8, .72, "trend intact, pullback near EMA20 (${f(near)}%), RSI ${f(rsi)}")
            e20 < e50 && (p.trend == "BEAR_TREND" || p.risk == "RISK_OFF") -> Vote("PULLBACK_CONTINUATION", SignalAction.WAIT, -4, .50, "pullback continuation blocked by bearish EMA structure")
            else -> Vote("PULLBACK_CONTINUATION", SignalAction.WAIT, 0, .25, "no clean pullback continuation")
        }
    }

    private fun meanReversion(t: MarketTicker, c: List<Candle>, p: AdvancedRegimeProfile): Vote {
        val closes = StrategyMath.closes(c); val b = StrategyMath.bollinger(closes, 20, 2.0); val rsi = StrategyMath.rsi(closes, 14); val last = t.lastPrice.toDouble()
        val rangeCompatible = p.regime in setOf("RANGING", "LOW_VOL_RANGE", "UNKNOWN")
        return when {
            b.lower != 0.0 && last < b.lower && rsi <= 36 && rangeCompatible -> Vote("MEAN_REVERSION", SignalAction.BUY, 7, .65, "below lower band, RSI ${f(rsi)}, range-compatible regime")
            b.upper != 0.0 && last > b.upper && rsi > 72 -> Vote("MEAN_REVERSION", SignalAction.SELL, -6, .60, "above upper band and overbought RSI ${f(rsi)}")
            else -> Vote("MEAN_REVERSION", SignalAction.WAIT, 0, .25, "no band/RSI reversion setup")
        }
    }

    private fun vwapReclaim(t: MarketTicker, c: List<Candle>): Vote {
        if (c.size < 20) return Vote("VWAP_RECLAIM", SignalAction.WAIT, 0, .20, "not enough candles")
        val vw = StrategyMath.vwap(c, 48); if (vw == 0.0) return Vote("VWAP_RECLAIM", SignalAction.WAIT, 0, .20, "no volume for VWAP")
        val prev = c[c.lastIndex - 1].close.toDouble(); val last = t.lastPrice.toDouble()
        val vr = c.takeLast(4).map { max(0.0, it.volume.toDouble()) }.average()
        val vb = if (c.size >= 32) c.dropLast(4).takeLast(24).map { max(0.0, it.volume.toDouble()) }.average() else vr
        return when {
            prev < vw && last > vw && c.last().low.toDouble() <= vw * 1.002 && vr >= vb * 1.10 -> Vote("VWAP_RECLAIM", SignalAction.BUY, 7, .68, "reclaimed and retested/held VWAP ${f(vw)} with volume confirmation")
            last < vw && StrategyMath.slopePct(StrategyMath.closes(c), 8) < -.2 -> Vote("VWAP_RECLAIM", SignalAction.WAIT, -3, .45, "below VWAP with weak slope")
            else -> Vote("VWAP_RECLAIM", SignalAction.WAIT, 0, .25, "no VWAP reclaim")
        }
    }

    private fun liquiditySweep(t: MarketTicker, c: List<Candle>): Vote {
        if (c.size < 25) return Vote("LIQUIDITY_SWEEP_REVERSAL", SignalAction.WAIT, 0, .20, "not enough candles")
        val prior = c.dropLast(2).takeLast(20); val low = prior.minOf { it.low.toDouble() }; val last = c.last()
        val swept = last.low.toDouble() < low && last.close.toDouble() > low && last.close > last.open
        val vr = c.takeLast(3).map { max(0.0, it.volume.toDouble()) }.average(); val vb = c.dropLast(3).takeLast(22).map { max(0.0, it.volume.toDouble()) }.average()
        return if (swept && vr >= vb * 1.10) Vote("LIQUIDITY_SWEEP_REVERSAL", SignalAction.BUY, 9, .70, "swept low ${f(low)} and reclaimed with volume") else Vote("LIQUIDITY_SWEEP_REVERSAL", SignalAction.WAIT, 0, .25, "no sweep/reclaim")
    }

    private fun bollingerSqueezeBreakout(t: MarketTicker, c: List<Candle>): Vote {
        val closes = StrategyMath.closes(c); if (closes.size < 45) return Vote("BOLLINGER_SQUEEZE_BREAKOUT", SignalAction.WAIT, 0, .20, "not enough candles")
        val b = StrategyMath.bollinger(closes,20,2.0); val prevB = StrategyMath.bollinger(closes.dropLast(1),20,2.0)
        val bandwidth = if (b.mid != 0.0) (b.upper - b.lower) / b.mid * 100.0 else 999.0
        val priorWidths = mutableListOf<Double>()
        for (i in 21 until min(closes.size,60)) {
            val start = closes.size - i; val end = min(closes.size, start + 20)
            if (start >= 0 && end - start >= 20) {
                val x = StrategyMath.bollinger(closes.subList(start,end),20,2.0); if (x.mid != 0.0) priorWidths += (x.upper-x.lower)/x.mid*100.0
            }
        }
        val avgWidth = priorWidths.average().takeIf { priorWidths.isNotEmpty() && !it.isNaN() } ?: bandwidth
        val squeeze = bandwidth <= max(avgWidth * .70, .10)
        val vr = c.takeLast(4).map { max(0.0,it.volume.toDouble()) }.average(); val vb = if(c.size>=38)c.dropLast(4).takeLast(30).map{max(0.0,it.volume.toDouble())}.average() else vr
        val broke = t.lastPrice.toDouble() > b.upper && closes[closes.lastIndex-1] <= prevB.upper
        return when { squeeze && broke && vr >= vb*1.30 -> Vote("BOLLINGER_SQUEEZE_BREAKOUT",SignalAction.BUY,9,.74,"squeeze width ${f(bandwidth)}% broke upper band with volume >=1.3x"); squeeze -> Vote("BOLLINGER_SQUEEZE_BREAKOUT",SignalAction.WAIT,3,.45,"squeeze armed width ${f(bandwidth)}%, awaiting band break"); else -> Vote("BOLLINGER_SQUEEZE_BREAKOUT",SignalAction.WAIT,0,.25,"no squeeze breakout") }
    }

    private fun emaTrendRider(t: MarketTicker, c: List<Candle>): Vote {
        val closes=StrategyMath.closes(c); if(closes.size<55)return Vote("EMA_TREND_RIDER",SignalAction.WAIT,0,.20,"not enough candles")
        val e9=StrategyMath.ema(closes,9);val e20s=StrategyMath.emaSeries(closes,20);val e20=e20s.last();val e50=StrategyMath.ema(closes,50);val slope=StrategyMath.slopePct(e20s,8);val last=t.lastPrice.toDouble();val pull=min(abs(last-e9)/e9*100.0,abs(last-e20)/e20*100.0);val mom=StrategyMath.momentumPct(closes,5);val r=StrategyMath.rsi(closes,14)
        return when { e9>e20&&e20>e50&&slope>.10&&pull<=.75&&mom>0&&r in 48.0..72.0 -> Vote("EMA_TREND_RIDER",SignalAction.BUY,8,.72,"EMA stack bullish, pullback ${f(pull)}%, RSI ${f(r)}, momentum resumed"); e9<e20&&e20<e50&&slope<-.10 -> Vote("EMA_TREND_RIDER",SignalAction.WAIT,-5,.60,"bearish EMA stack blocks long trend riding"); else -> Vote("EMA_TREND_RIDER",SignalAction.WAIT,0,.25,"no clean EMA trend rider setup") }
    }

    private fun rsiDivergenceReversal(t: MarketTicker,c:List<Candle>,p:AdvancedRegimeProfile):Vote{
        val closes=StrategyMath.closes(c);if(closes.size<50)return Vote("RSI_DIVERGENCE_REVERSAL",SignalAction.WAIT,0,.20,"not enough candles");val r=StrategyMath.rsi(closes,14);val last=c.last();val reclaim=last.close>last.open&&last.close>c[c.lastIndex-1].high;val div=StrategyMath.hasBullishDivergence(c,32)
        return if(div&&r<=48&&reclaim&&p.trend!="BEAR_TREND")Vote("RSI_DIVERGENCE_REVERSAL",SignalAction.BUY,7,.66,"bullish RSI divergence with reclaim candle, RSI ${f(r)}") else Vote("RSI_DIVERGENCE_REVERSAL",SignalAction.WAIT,0,.25,"no confirmed bullish RSI divergence")
    }

    private fun desktopSupertrend(t:MarketTicker,c:List<Candle>):Vote{
        val closes=StrategyMath.closes(c);if(closes.size<40)return Vote("SUPERTREND",SignalAction.WAIT,0,.20,"not enough candles");val cur=StrategyMath.desktopSupertrendState(c,10,3.0);val prev=StrategyMath.desktopSupertrendState(c.dropLast(1),10,3.0);val mom=StrategyMath.momentumPct(closes,5);val above=t.lastPrice.toDouble()>cur.band
        return when{cur.bullish&&!prev.bullish&&above&&mom>.10->Vote("SUPERTREND",SignalAction.BUY,8,.70,"supertrend flipped bullish above band ${f(cur.band)}, momentum ${f(mom)}%");cur.bullish&&prev.bullish&&above&&mom>=-.05->Vote("SUPERTREND",SignalAction.BUY,6,.64,"supertrend bullish continuation above band ${f(cur.band)}, momentum ${f(mom)}%");!cur.bullish->Vote("SUPERTREND",SignalAction.WAIT,-5,.58,"supertrend bearish/under band ${f(cur.band)}");else->Vote("SUPERTREND",SignalAction.WAIT,0,.30,"supertrend waiting: up=${cur.bullish}, previous_up=${prev.bullish}, above_band=$above, momentum=${f(mom)}%")}
    }

    private fun supportResistanceBounce(t:MarketTicker,c:List<Candle>,p:AdvancedRegimeProfile):Vote{
        if(c.size<45)return Vote("SUPPORT_RESISTANCE_BOUNCE",SignalAction.WAIT,0,.20,"not enough candles");val prior=c.dropLast(2).takeLast(33);val support=prior.minOf{it.low.toDouble()};val resistance=prior.maxOf{it.high.toDouble()};val range=max(resistance-support,1e-12);val last=c.last();val near=(last.low.toDouble()-support)/range<=.18;val rejected=near&&last.close>last.open&&last.close.toDouble()>support+range*.10;val r=StrategyMath.rsi(StrategyMath.closes(c),14);val compatible=p.regime in setOf("RANGING","LOW_VOL_RANGE","UNKNOWN")
        return if(rejected&&r in 34.0..58.0&&compatible)Vote("SUPPORT_RESISTANCE_BOUNCE",SignalAction.BUY,7,.64,"support bounce from ${f(support)}, range resistance ${f(resistance)}, RSI ${f(r)}") else Vote("SUPPORT_RESISTANCE_BOUNCE",SignalAction.WAIT,0,.25,"no support bounce confirmation")
    }

    private fun donchianBreakout(t:MarketTicker,c:List<Candle>):Vote{if(c.size<58)return Vote("DONCHIAN_CHANNEL_BREAKOUT",SignalAction.WAIT,0,.20,"not enough candles");val high=c.dropLast(1).takeLast(55).maxOf{it.high.toDouble()};val vr=c.takeLast(6).map{max(0.0,it.volume.toDouble())}.average();val vb=c.dropLast(6).takeLast(30).map{max(0.0,it.volume.toDouble())}.average();return if(t.lastPrice.toDouble()>high&&vr>=vb*1.20)Vote("DONCHIAN_CHANNEL_BREAKOUT",SignalAction.BUY,8,.72,"Donchian 55-bar breakout over ${f(high)} with volume")else Vote("DONCHIAN_CHANNEL_BREAKOUT",SignalAction.WAIT,0,.25,"no Donchian breakout")}

    private fun keltnerBreakout(t:MarketTicker,c:List<Candle>):Vote{val closes=StrategyMath.closes(c);if(closes.size<55)return Vote("KELTNER_CHANNEL_BREAKOUT",SignalAction.WAIT,0,.20,"not enough candles");val e20s=StrategyMath.emaSeries(closes,20);val e20=e20s.last();val atr=StrategyMath.atrPct(c,14);val upper=e20*(1+(atr*1.5)/100.0);val slope=StrategyMath.slopePct(e20s,8);return if(t.lastPrice.toDouble()>upper&&slope>.05)Vote("KELTNER_CHANNEL_BREAKOUT",SignalAction.BUY,7,.69,"Keltner upper break ${f(upper)}, EMA slope ${f(slope)}%")else Vote("KELTNER_CHANNEL_BREAKOUT",SignalAction.WAIT,0,.25,"no Keltner breakout")}

    private fun macdTrendCross(t:MarketTicker,c:List<Candle>):Vote{val closes=StrategyMath.closes(c);val m=StrategyMath.macdCross(c);val trend=closes.size>=50&&StrategyMath.ema(closes,20)>=StrategyMath.ema(closes,50);return when{m.bullishCross&&trend->Vote("MACD_TREND_CROSS",SignalAction.BUY,7,.67,"MACD bullish cross ${f(m.macd)}>${f(m.signal)} with trend");m.bearishCross->Vote("MACD_TREND_CROSS",SignalAction.WAIT,-4,.50,"MACD bearish cross");else->Vote("MACD_TREND_CROSS",SignalAction.WAIT,0,.25,"no MACD trend cross")}}

    private fun stochasticOversoldReversal(t:MarketTicker,c:List<Candle>):Vote{val k=StrategyMath.stochasticK(c,14);val kp=StrategyMath.stochasticK(c.dropLast(1),14);val r=StrategyMath.rsi(StrategyMath.closes(c),14);return if(kp<20&&k>=20&&r<=45&&c.last().close>c.last().open)Vote("STOCHASTIC_OVERSOLD_REVERSAL",SignalAction.BUY,6,.62,"Stochastic reclaimed oversold K=${f(k)}, RSI=${f(r)}")else Vote("STOCHASTIC_OVERSOLD_REVERSAL",SignalAction.WAIT,0,.22,"no stochastic oversold reversal")}

    private fun cciMeanReversion(t:MarketTicker,c:List<Candle>):Vote{val x=StrategyMath.cci(c,20);val xp=StrategyMath.cci(c.dropLast(1),20);val b=StrategyMath.bollinger(StrategyMath.closes(c),20,2.0);return if(xp< -100&&x>= -100&&b.lower!=0.0&&t.lastPrice.toDouble()<=b.lower*1.01)Vote("CCI_MEAN_REVERSION",SignalAction.BUY,6,.62,"CCI reclaimed -100 (${f(x)}) near lower band")else Vote("CCI_MEAN_REVERSION",SignalAction.WAIT,0,.22,"no CCI reversion")}

    private fun desktopAdxTrendPullback(t:MarketTicker,c:List<Candle>):Vote{val closes=StrategyMath.closes(c);if(closes.size<55)return Vote("ADX_TREND_PULLBACK",SignalAction.WAIT,0,.20,"not enough candles");val e20=StrategyMath.ema(closes,20);val e50=StrategyMath.ema(closes,50);val adx=StrategyMath.desktopAdx(c,14);val pull=if(e20!=0.0)abs(t.lastPrice.toDouble()-e20)/e20*100 else 999.0;return if(e20>e50&&adx>=18&&pull<=.90&&c.last().close>c.last().open)Vote("ADX_TREND_PULLBACK",SignalAction.BUY,7,.68,"ADX trend pullback ADX=${f(adx)}, pullback=${f(pull)}%")else Vote("ADX_TREND_PULLBACK",SignalAction.WAIT,0,.25,"no ADX trend pullback")}

    private fun desktopParabolicSarFlip(t:MarketTicker,c:List<Candle>):Vote{if(c.size<25)return Vote("PARABOLIC_SAR_FLIP",SignalAction.WAIT,0,.20,"not enough candles");val atrAbs=StrategyMath.atrPct(c,14)/100.0*t.lastPrice.toDouble();val trail=c.dropLast(1).takeLast(10).minOf{it.low.toDouble()}+atrAbs*.20;val flip=c.last().close.toDouble()>trail&&c[c.lastIndex-1].close.toDouble()<=trail;return if(flip&&StrategyMath.momentumPct(StrategyMath.closes(c),5)>0)Vote("PARABOLIC_SAR_FLIP",SignalAction.BUY,6,.62,"PSAR-style bullish flip above ${f(trail)}")else Vote("PARABOLIC_SAR_FLIP",SignalAction.WAIT,0,.22,"no SAR flip")}

    private fun ichimokuCloudBreakout(t:MarketTicker,c:List<Candle>):Vote{if(c.size<80)return Vote("ICHIMOKU_CLOUD_BREAKOUT",SignalAction.WAIT,0,.20,"not enough candles");val conv=(StrategyMath.highestHigh(c,9)+StrategyMath.lowestLow(c,9))/2;val base=(StrategyMath.highestHigh(c,26)+StrategyMath.lowestLow(c,26))/2;val top=max((conv+base)/2,(StrategyMath.highestHigh(c,52)+StrategyMath.lowestLow(c,52))/2);return if(t.lastPrice.toDouble()>top&&c[c.lastIndex-1].close.toDouble()<=top&&conv>base)Vote("ICHIMOKU_CLOUD_BREAKOUT",SignalAction.BUY,7,.68,"Ichimoku cloud breakout above ${f(top)}")else Vote("ICHIMOKU_CLOUD_BREAKOUT",SignalAction.WAIT,0,.25,"no Ichimoku breakout")}

    private fun rollingRangeExpansion(t:MarketTicker,c:List<Candle>):Vote{if(c.size<35)return Vote("ROLLING_RANGE_EXPANSION",SignalAction.WAIT,0,.20,"not enough candles");val prior=c.dropLast(1).takeLast(30);val high=prior.maxOf{it.high.toDouble()};val low=prior.minOf{it.low.toDouble()};val rng=(high-low)/max(t.lastPrice.toDouble(),1e-12)*100;val atr=StrategyMath.atrPct(c,14);return if(t.lastPrice.toDouble()>high&&atr>max(rng*.35,.05))Vote("ROLLING_RANGE_EXPANSION",SignalAction.BUY,7,.67,"Rolling range expansion over ${f(high)}, ATR ${f(atr)}%")else Vote("ROLLING_RANGE_EXPANSION",SignalAction.WAIT,0,.25,"no range expansion")}

    private fun vwapDeviationReversion(t:MarketTicker,c:List<Candle>):Vote{val vw=StrategyMath.vwap(c,48);val r=StrategyMath.rsi(StrategyMath.closes(c),14);val deviation=if(vw>0)(vw-t.lastPrice.toDouble())/vw*100 else 0.0;val reclaim=c.last().close>c.last().open&&t.lastPrice>c[c.lastIndex-1].close;return if(vw>0&&deviation>=.45&&r<=42&&reclaim)Vote("VWAP_DEVIATION_REVERSION",SignalAction.BUY,6,.62,"VWAP deviation ${f(deviation)}% reverted with RSI ${f(r)}")else Vote("VWAP_DEVIATION_REVERSION",SignalAction.WAIT,0,.22,"no VWAP deviation reversion")}

    private fun newsMomentum(t:MarketTicker,c:List<Candle>,newsScore:Int):Vote{val mom=StrategyMath.momentumPct(StrategyMath.closes(c),6);return when{newsScore>=10&&mom>=.15&&StrategyMath.atrPct(c,14)>0->Vote("NEWS_MOMENTUM_CONFIRMATION",SignalAction.BUY,5,.60,"positive news $newsScore confirmed by momentum ${f(mom)}%",entry=false);newsScore<=-10->Vote("NEWS_MOMENTUM_CONFIRMATION",SignalAction.WAIT,-6,.65,"negative news risk $newsScore",entry=false);else->Vote("NEWS_MOMENTUM_CONFIRMATION",SignalAction.WAIT,0,.20,"news not decisive or not confirmed",entry=false)}}

    private fun spreadLiquidityScalp(s:BotSettings,t:MarketTicker,c:List<Candle>):Vote{val spread=spreadPct(t);val maxSpread=s.maxSpreadPercent.toDouble();val minVol=s.minVolume24hEur.toDouble();val atr=StrategyMath.atrPct(c,14);return when{spread<=maxSpread*.45&&t.volume24h.toDouble()>=minVol&&atr>spread*4->Vote("SPREAD_LIQUIDITY_SCALP",SignalAction.BUY,4,.55,"spread ${f(spread)}% liquid enough, ATR covers costs",entry=false);spread>maxSpread||t.volume24h.toDouble()<minVol->Vote("SPREAD_LIQUIDITY_SCALP",SignalAction.WAIT,-8,.75,"execution quality weak: spread ${f(spread)}% volume ${f(t.volume24h.toDouble())}",entry=false);else->Vote("SPREAD_LIQUIDITY_SCALP",SignalAction.WAIT,0,.25,"execution quality neutral",entry=false)}}

    private fun multiTimeframeConfirmation(t:MarketTicker,c:List<Candle>,extra:Map<Timeframe,List<Candle>>):Vote{val slopes=linkedMapOf("base" to StrategyMath.slopePct(StrategyMath.closes(c),12));for((tf,series) in extra)if(series.size>=20)slopes[tf.name]=StrategyMath.slopePct(StrategyMath.closes(series),12);if(slopes.size<2)return Vote("MULTI_TIMEFRAME_CONFIRMATION",SignalAction.WAIT,0,.20,"not enough extra timeframe data",entry=false);val pos=slopes.values.count{it>.10};val neg=slopes.values.count{it<-.10};return when{pos>=max(2,slopes.size-1)->Vote("MULTI_TIMEFRAME_CONFIRMATION",SignalAction.BUY,6,.62,"timeframes agree bullish $slopes",entry=false);neg>=2->Vote("MULTI_TIMEFRAME_CONFIRMATION",SignalAction.WAIT,-7,.68,"higher timeframe disagreement/bearish $slopes",entry=false);else->Vote("MULTI_TIMEFRAME_CONFIRMATION",SignalAction.WAIT,0,.30,"mixed timeframes $slopes",entry=false)}}

    private fun exitOptimizerHint(t:MarketTicker,c:List<Candle>,p:AdvancedRegimeProfile):Vote{val mom=StrategyMath.momentumPct(StrategyMath.closes(c),5);val atr=StrategyMath.atrPct(c,14);return when{mom<-.35&&atr>.20->Vote("EXIT_OPTIMIZER",SignalAction.SELL,-5,.60,"momentum decay ${f(mom)}% with ATR ${f(atr)}%",entry=false);p.trend=="BULL_TREND"&&mom>.25->Vote("EXIT_OPTIMIZER",SignalAction.WAIT,3,.45,"runner-friendly bullish momentum; avoid early exit",entry=false);else->Vote("EXIT_OPTIMIZER",SignalAction.WAIT,0,.25,"exit optimizer neutral",entry=false)}}

    // =============================================================================================
    // Practitioner / investor style variants
    // =============================================================================================

    private fun proTrendMtfBreakout(t:MarketTicker,c:List<Candle>,frames:Map<Timeframe,List<Candle>>,p:AdvancedRegimeProfile):Vote{
        if(c.size<60)return Vote("PRO_TREND_MTF_BREAKOUT",SignalAction.WAIT,0,.20,"warm-up",professional=true)
        val dmi=StrategyMath.wilderDmi(c,14);val high=c.dropLast(1).takeLast(20).maxOf{it.high.toDouble()};val vol=StrategyMath.volumeRatio(c,4,30);val higher=mtfBullishCount(frames);val breakout=t.lastPrice.toDouble()>high
        return if(breakout&&dmi.adx>=25&&dmi.plusDi>dmi.minusDi&&vol>=1.20&&higher>=2)Vote("PRO_TREND_MTF_BREAKOUT",SignalAction.BUY,10,.82,"20-bar breakout + Wilder ADX ${f(dmi.adx)} (+DI>${f(dmi.minusDi)}), volume ${f(vol)}x, bullish higher frames=$higher",professional=true)else Vote("PRO_TREND_MTF_BREAKOUT",SignalAction.WAIT,0,.28,"requires breakout, ADX>=25, +DI>-DI, volume and MTF agreement",professional=true)
    }

    private fun proTrendPullbackAtr(t:MarketTicker,c:List<Candle>,frames:Map<Timeframe,List<Candle>>,p:AdvancedRegimeProfile):Vote{
        if(c.size<60)return Vote("PRO_TREND_PULLBACK_ATR",SignalAction.WAIT,0,.20,"warm-up",professional=true);val closes=StrategyMath.closes(c);val e20=StrategyMath.ema(closes,20);val e50=StrategyMath.ema(closes,50);val atr=StrategyMath.atrAbs(c,14);val dmi=StrategyMath.wilderDmi(c,14);val distance=abs(t.lastPrice.toDouble()-e20);val r=StrategyMath.rsi(closes,14);val bullishCandle=c.last().close>c.last().open;val higher=mtfBullishCount(frames)
        return if(e20>e50&&dmi.adx>=25&&dmi.plusDi>dmi.minusDi&&distance<=atr*.75&&r in 45.0..68.0&&bullishCandle&&higher>=2)Vote("PRO_TREND_PULLBACK_ATR",SignalAction.BUY,9,.78,"trend pullback within 0.75 ATR of EMA20, ADX=${f(dmi.adx)}, RSI=${f(r)}, MTF=$higher",professional=true)else Vote("PRO_TREND_PULLBACK_ATR",SignalAction.WAIT,0,.28,"waiting for volatility-normalized pullback with strong trend",professional=true)
    }

    private fun proRangeReversion(t:MarketTicker,c:List<Candle>,p:AdvancedRegimeProfile):Vote{if(c.size<50)return Vote("PRO_RANGE_BOLLINGER_VWAP",SignalAction.WAIT,0,.20,"warm-up",professional=true);val dmi=StrategyMath.wilderDmi(c,14);val b=StrategyMath.bollinger(StrategyMath.closes(c),20,2.0);val vw=StrategyMath.vwap(c,48);val r=StrategyMath.rsi(StrategyMath.closes(c),14);val last=t.lastPrice.toDouble();val dev=if(vw>0)(vw-last)/vw*100 else 0.0;val reversal=c.last().close>c.last().open&&c.last().close>c[c.lastIndex-1].close;return if(dmi.adx<20&&r<=35&&reversal&&(last<b.lower||dev>=.50))Vote("PRO_RANGE_BOLLINGER_VWAP",SignalAction.BUY,8,.72,"range reversion ADX=${f(dmi.adx)}<20, RSI=${f(r)}, VWAP deviation=${f(dev)}%",professional=true)else Vote("PRO_RANGE_BOLLINGER_VWAP",SignalAction.WAIT,0,.26,"range-only reversion waits for ADX<20 plus stretched price and reversal",professional=true)}

    private fun proVolumeConfirmedBreakout(t:MarketTicker,c:List<Candle>,p:AdvancedRegimeProfile):Vote{if(c.size<55)return Vote("PRO_VOLUME_CONFIRMED_BREAKOUT",SignalAction.WAIT,0,.20,"warm-up",professional=true);val resistance=c.dropLast(1).takeLast(50).maxOf{it.high.toDouble()};val vr=StrategyMath.volumeRatio(c,3,40);val dmi=StrategyMath.wilderDmi(c,14);val last=t.lastPrice.toDouble();return if(last>resistance&&vr>=1.50&&dmi.adx>=20)Vote("PRO_VOLUME_CONFIRMED_BREAKOUT",SignalAction.BUY,10,.80,"breakout above high-volume resistance ${f(resistance)}, volume ${f(vr)}x, ADX=${f(dmi.adx)}",professional=true)else Vote("PRO_VOLUME_CONFIRMED_BREAKOUT",SignalAction.WAIT,0,.25,"breakout requires >=1.5x volume and non-weak trend",professional=true)}

    private fun proSupertrendConfirmed(t:MarketTicker,c:List<Candle>,frames:Map<Timeframe,List<Candle>>,p:AdvancedRegimeProfile):Vote{if(c.size<50)return Vote("PRO_SUPERTREND_CONFIRMED",SignalAction.WAIT,0,.20,"warm-up",professional=true);val cur=StrategyMath.fullSupertrend(c,10,3.0);val prev=StrategyMath.fullSupertrend(c.dropLast(1),10,3.0);val dmi=StrategyMath.wilderDmi(c,14);val mtf=mtfBullishCount(frames);return if(cur.bullish&&!prev.bullish&&t.lastPrice.toDouble()>cur.band&&dmi.adx>=20&&mtf>=2)Vote("PRO_SUPERTREND_CONFIRMED",SignalAction.BUY,9,.76,"full Supertrend bullish flip, ADX=${f(dmi.adx)}, MTF=$mtf",professional=true)else if(cur.bullish&&dmi.adx>=25&&mtf>=2)Vote("PRO_SUPERTREND_CONFIRMED",SignalAction.BUY,6,.65,"full Supertrend continuation with trend strength and MTF agreement",professional=true)else Vote("PRO_SUPERTREND_CONFIRMED",SignalAction.WAIT,0,.25,"no professionally confirmed Supertrend setup",professional=true)}

    private fun proPsarTrendFlip(t:MarketTicker,c:List<Candle>,frames:Map<Timeframe,List<Candle>>,p:AdvancedRegimeProfile):Vote{if(c.size<45)return Vote("PRO_PSAR_TREND_FLIP",SignalAction.WAIT,0,.20,"warm-up",professional=true);val psar=StrategyMath.parabolicSar(c);val dmi=StrategyMath.wilderDmi(c,14);val mtf=mtfBullishCount(frames);return if(psar.flippedBullish&&psar.bullish&&dmi.plusDi>dmi.minusDi&&dmi.adx>=20&&mtf>=2)Vote("PRO_PSAR_TREND_FLIP",SignalAction.BUY,8,.72,"true PSAR bullish flip at ${f(psar.value)}, DMI confirms, MTF=$mtf",professional=true)else Vote("PRO_PSAR_TREND_FLIP",SignalAction.WAIT,0,.24,"no confirmed true-PSAR bullish flip",professional=true)}

    private fun proIchimokuMtf(t:MarketTicker,c:List<Candle>,frames:Map<Timeframe,List<Candle>>,p:AdvancedRegimeProfile):Vote{if(c.size<80)return Vote("PRO_ICHIMOKU_MTF",SignalAction.WAIT,0,.20,"warm-up",professional=true);val tenkan=(StrategyMath.highestHigh(c,9)+StrategyMath.lowestLow(c,9))/2;val kijun=(StrategyMath.highestHigh(c,26)+StrategyMath.lowestLow(c,26))/2;val spanA=(tenkan+kijun)/2;val spanB=(StrategyMath.highestHigh(c,52)+StrategyMath.lowestLow(c,52))/2;val top=max(spanA,spanB);val mtf=mtfBullishCount(frames);return if(t.lastPrice.toDouble()>top&&tenkan>kijun&&mtf>=2)Vote("PRO_ICHIMOKU_MTF",SignalAction.BUY,8,.72,"price above cloud, Tenkan>Kijun, MTF=$mtf",professional=true)else Vote("PRO_ICHIMOKU_MTF",SignalAction.WAIT,0,.25,"Ichimoku needs cloud + conversion/base + MTF agreement",professional=true)}

    private fun proAnchoredVwapReclaim(t:MarketTicker,c:List<Candle>,p:AdvancedRegimeProfile):Vote{if(c.size<55)return Vote("PRO_ANCHORED_VWAP_RECLAIM",SignalAction.WAIT,0,.20,"warm-up",professional=true);val w=c.takeLast(55);val localAnchor=w.indices.minByOrNull{w[it].low.toDouble()}?:0;val anchor=c.size-w.size+localAnchor;val av=StrategyMath.anchoredVwap(c,anchor);val prev=c[c.lastIndex-1].close.toDouble();val last=t.lastPrice.toDouble();val vol=StrategyMath.volumeRatio(c,3,30);return if(prev<av&&last>av&&c.last().low.toDouble()<=av*1.003&&vol>=1.10)Vote("PRO_ANCHORED_VWAP_RECLAIM",SignalAction.BUY,8,.72,"reclaimed swing-low anchored VWAP ${f(av)} with retest and volume ${f(vol)}x",professional=true)else Vote("PRO_ANCHORED_VWAP_RECLAIM",SignalAction.WAIT,0,.25,"waiting for anchored VWAP reclaim/retest",professional=true)}

    private fun proCompressionExpansion(t:MarketTicker,c:List<Candle>,p:AdvancedRegimeProfile):Vote{if(c.size<70)return Vote("PRO_COMPRESSION_EXPANSION",SignalAction.WAIT,0,.20,"warm-up",professional=true);val atr=StrategyMath.atrPct(c,14);val oldAtr=StrategyMath.atrPct(c.dropLast(8),14);val b=StrategyMath.bollinger(StrategyMath.closes(c),20,2.0);val width=if(b.mid>0)(b.upper-b.lower)/b.mid*100 else 999.0;val high=c.dropLast(1).takeLast(20).maxOf{it.high.toDouble()};val vol=StrategyMath.volumeRatio(c,4,30);val expanding=oldAtr>0&&atr>=oldAtr*1.20;return if(width<=2.0&&expanding&&t.lastPrice.toDouble()>high&&vol>=1.25)Vote("PRO_COMPRESSION_EXPANSION",SignalAction.BUY,9,.76,"compressed bands then ATR expansion ${f(oldAtr)}%->${f(atr)}%, breakout and volume ${f(vol)}x",professional=true)else Vote("PRO_COMPRESSION_EXPANSION",SignalAction.WAIT,0,.25,"waiting for compression -> volatility expansion -> breakout sequence",professional=true)}

    private fun positionTrendFollowing(t:MarketTicker,frames:Map<Timeframe,List<Candle>>,p:AdvancedRegimeProfile):Vote{val h1=frames[Timeframe.H1].orEmpty();val h4=frames[Timeframe.H4].orEmpty();if(h1.size<60||h4.size<60)return Vote("PRO_POSITION_TREND_FOLLOWING",SignalAction.WAIT,0,.20,"needs H1 and H4 history",professional=true);fun trend(x:List<Candle>):Triple<Boolean,Double,Double>{val cl=StrategyMath.closes(x);val e20=StrategyMath.ema(cl,20);val e50=StrategyMath.ema(cl,50);val mom=StrategyMath.momentumPct(cl,20);return Triple(e20>e50&&mom>0,e20,e50)};val a=trend(h1);val b=trend(h4);val don=h1.dropLast(1).takeLast(55).maxOf{it.high.toDouble()};return if(a.first&&b.first&&t.lastPrice.toDouble()>don)Vote("PRO_POSITION_TREND_FOLLOWING",SignalAction.BUY,9,.78,"H1/H4 trend alignment plus 55-bar H1 breakout; designed for slower position participation",professional=true)else Vote("PRO_POSITION_TREND_FOLLOWING",SignalAction.WAIT,0,.25,"longer-horizon trend requires H1/H4 alignment and breakout",professional=true)}

    private fun proDmiAdxTrendPullback(t:MarketTicker,c:List<Candle>,frames:Map<Timeframe,List<Candle>>):Vote {
        if(c.size<70)return Vote("PRO_DMI_ADX_TREND_PULLBACK",SignalAction.WAIT,0,.20,"warm-up",professional=true)
        val dmi=StrategyMath.wilderDmi(c,14);val cl=StrategyMath.closes(c);val e20=StrategyMath.ema(cl,20);val e50=StrategyMath.ema(cl,50)
        val near=if(e20>0)abs(t.lastPrice.toDouble()-e20)/e20*100 else 999.0;val r=StrategyMath.rsi(cl,14);val mtf=mtfBullishCount(frames)
        return if(dmi.adx>=25&&dmi.plusDi>dmi.minusDi&&e20>e50&&near<=1.0&&r in 45.0..68.0&&mtf>=2)
            Vote("PRO_DMI_ADX_TREND_PULLBACK",SignalAction.BUY,9,.78,"Wilder DMI +DI>${f(dmi.plusDi)} / -DI=${f(dmi.minusDi)}, ADX=${f(dmi.adx)}, EMA20 pullback ${f(near)}%, MTF=$mtf",professional=true)
        else Vote("PRO_DMI_ADX_TREND_PULLBACK",SignalAction.WAIT,0,.25,"requires ADX>=25, +DI dominance, trend pullback and MTF alignment",professional=true)
    }

    private fun proLongHorizon50200(t:MarketTicker,frames:Map<Timeframe,List<Candle>>):Vote {
        val h4=frames[Timeframe.H4].orEmpty();if(h4.size<210)return Vote("PRO_LONG_HORIZON_50_200",SignalAction.WAIT,0,.18,"needs >=210 H4 candles",professional=true)
        val cl=StrategyMath.closes(h4);val e50=StrategyMath.ema(cl,50);val e200=StrategyMath.ema(cl,200);val e50Prev=StrategyMath.ema(cl.dropLast(12),50);val last=t.lastPrice.toDouble()
        return if(last>e50&&e50>e200&&e50>e50Prev)
            Vote("PRO_LONG_HORIZON_50_200",SignalAction.BUY,7,.76,"H4 price>EMA50>EMA200 and EMA50 rising; long-horizon trend filter",professional=true)
        else if(last<e200) Vote("PRO_LONG_HORIZON_50_200",SignalAction.WAIT,-6,.70,"price below H4 EMA200; long-horizon trend risk",professional=true)
        else Vote("PRO_LONG_HORIZON_50_200",SignalAction.WAIT,0,.30,"long-horizon 50/200 structure neutral",professional=true)
    }

    private fun proTimeSeriesMomentum(t:MarketTicker,frames:Map<Timeframe,List<Candle>>):Vote {
        val h4=frames[Timeframe.H4].orEmpty();if(h4.size<190)return Vote("PRO_TIME_SERIES_MOMENTUM",SignalAction.WAIT,0,.18,"needs >=190 H4 candles",professional=true)
        val cl=StrategyMath.closes(h4);val m30=StrategyMath.momentumPct(cl,30);val m90=StrategyMath.momentumPct(cl,90);val m180=StrategyMath.momentumPct(cl,180)
        val positive=listOf(m30,m90,m180).count{it>.0};val e50=StrategyMath.ema(cl,50)
        return if(positive==3&&t.lastPrice.toDouble()>e50)Vote("PRO_TIME_SERIES_MOMENTUM",SignalAction.BUY,8,.78,"H4 time-series momentum positive across 30/90/180 bars (${f(m30)}/${f(m90)}/${f(m180)}%)",professional=true)
        else if(positive<=1)Vote("PRO_TIME_SERIES_MOMENTUM",SignalAction.WAIT,-5,.65,"long/medium momentum not aligned (${f(m30)}/${f(m90)}/${f(m180)}%)",professional=true)
        else Vote("PRO_TIME_SERIES_MOMENTUM",SignalAction.WAIT,1,.40,"mixed but improving time-series momentum",professional=true)
    }

    private fun proDualDonchianBreakout(t:MarketTicker,c:List<Candle>,frames:Map<Timeframe,List<Candle>>):Vote {
        if(c.size<65)return Vote("PRO_DUAL_DONCHIAN_BREAKOUT",SignalAction.WAIT,0,.20,"warm-up",professional=true)
        val hi20=c.dropLast(1).takeLast(20).maxOf{it.high.toDouble()};val hi55=c.dropLast(1).takeLast(55).maxOf{it.high.toDouble()};val last=t.lastPrice.toDouble();val vr=StrategyMath.volumeRatio(c,3,30);val mtf=mtfBullishCount(frames)
        return when{last>hi55&&vr>=1.25&&mtf>=2->Vote("PRO_DUAL_DONCHIAN_BREAKOUT",SignalAction.BUY,10,.82,"55-bar Donchian breakout + volume ${f(vr)}x + MTF=$mtf",professional=true)
            last>hi20&&vr>=1.15&&mtf>=2->Vote("PRO_DUAL_DONCHIAN_BREAKOUT",SignalAction.SMALL_BUY,6,.66,"20-bar Donchian breakout confirmed by volume/MTF; shorter-horizon entry",professional=true)
            else->Vote("PRO_DUAL_DONCHIAN_BREAKOUT",SignalAction.WAIT,0,.25,"no confirmed 20/55 Donchian breakout",professional=true)}
    }

    private fun proVwapTrendRetest(t:MarketTicker,c:List<Candle>,frames:Map<Timeframe,List<Candle>>):Vote {
        if(c.size<50)return Vote("PRO_VWAP_TREND_RETEST",SignalAction.WAIT,0,.20,"warm-up",professional=true)
        val vw=StrategyMath.vwap(c,48);val h1=frames[Timeframe.H1].orEmpty();val h1Bull=h1.size>=50&&StrategyMath.ema(StrategyMath.closes(h1),20)>StrategyMath.ema(StrategyMath.closes(h1),50)
        val last=t.lastPrice.toDouble();val dist=if(vw>0)abs(last-vw)/vw*100 else 999.0;val c0=c.last();val reclaim=c0.low.toDouble()<=vw*1.002&&c0.close.toDouble()>vw&&c0.close>c0.open;val vr=StrategyMath.volumeRatio(c,3,30)
        return if(h1Bull&&reclaim&&dist<=.75&&vr>=1.0)Vote("PRO_VWAP_TREND_RETEST",SignalAction.BUY,7,.70,"H1 trend + intraday VWAP retest/reclaim ${f(vw)} + volume ${f(vr)}x",professional=true)
        else Vote("PRO_VWAP_TREND_RETEST",SignalAction.WAIT,0,.25,"requires higher-timeframe trend and VWAP retest/reclaim",professional=true)
    }

    private fun proMacdMtfConfirmation(t:MarketTicker,c:List<Candle>,frames:Map<Timeframe,List<Candle>>):Vote {
        if(c.size<45)return Vote("PRO_MACD_MTF_CONFIRMATION",SignalAction.WAIT,0,.20,"warm-up",professional=true)
        val m=StrategyMath.macdCross(c);val mtf=mtfBullishCount(frames);val dmi=StrategyMath.wilderDmi(c,14)
        return if(m.bullishCross&&mtf>=2&&dmi.adx>=20)Vote("PRO_MACD_MTF_CONFIRMATION",SignalAction.BUY,8,.74,"MACD signal-line bullish cross with MTF=$mtf and ADX=${f(dmi.adx)}",professional=true)
        else if(m.bearishCross)Vote("PRO_MACD_MTF_CONFIRMATION",SignalAction.WAIT,-5,.60,"MACD bearish signal-line cross",professional=true)
        else Vote("PRO_MACD_MTF_CONFIRMATION",SignalAction.WAIT,0,.25,"no MTF-confirmed MACD cross",professional=true)
    }


    private fun proBreakoutRetest(t:MarketTicker,c:List<Candle>,frames:Map<Timeframe,List<Candle>>):Vote {
        if(c.size<60)return Vote("PRO_BREAKOUT_RETEST",SignalAction.WAIT,0,.20,"warm-up",professional=true)
        val priorHigh=c.dropLast(3).takeLast(50).maxOf{it.high.toDouble()};val last=c.last();val broke=c[c.lastIndex-2].high.toDouble()>priorHigh||c[c.lastIndex-2].close.toDouble()>priorHigh
        val retested=last.low.toDouble()<=priorHigh*1.004&&last.close.toDouble()>priorHigh&&last.close>last.open
        val vr=StrategyMath.volumeRatio(c,3,35);val mtf=mtfBullishCount(frames);val dmi=StrategyMath.wilderDmi(c,14)
        return if(broke&&retested&&vr>=1.05&&mtf>=2&&dmi.adx>=18) Vote("PRO_BREAKOUT_RETEST",SignalAction.BUY,8,.76,"breakout held on retest of ${f(priorHigh)}; volume=${f(vr)}x, MTF=$mtf, ADX=${f(dmi.adx)}",professional=true)
        else Vote("PRO_BREAKOUT_RETEST",SignalAction.WAIT,0,.26,"waiting for breakout -> retest -> hold sequence",professional=true)
    }

    private fun proObvAccumulation(t:MarketTicker,c:List<Candle>,frames:Map<Timeframe,List<Candle>>):Vote {
        if(c.size<65)return Vote("PRO_OBV_ACCUMULATION",SignalAction.WAIT,0,.20,"warm-up",professional=true)
        val obv=StrategyMath.obvSeries(c);val obvSlope=StrategyMath.slopePct(obv,20);val cl=StrategyMath.closes(c);val priceSlope=StrategyMath.slopePct(cl,20);val e20=StrategyMath.ema(cl,20);val e50=StrategyMath.ema(cl,50);val mtf=mtfBullishCount(frames)
        return when {
            obvSlope>.8&&priceSlope>0&&e20>e50&&mtf>=2 -> Vote("PRO_OBV_ACCUMULATION",SignalAction.BUY,7,.68,"OBV accumulation slope=${f(obvSlope)}%, price trend=${f(priceSlope)}%, MTF=$mtf",professional=true)
            obvSlope<-.8&&priceSlope>=0 -> Vote("PRO_OBV_ACCUMULATION",SignalAction.WAIT,-4,.58,"price/OBV participation divergence; OBV slope=${f(obvSlope)}%",professional=true)
            else -> Vote("PRO_OBV_ACCUMULATION",SignalAction.WAIT,0,.28,"OBV participation neutral",professional=true)
        }
    }

    private fun proPriceStructureTrend(t:MarketTicker,c:List<Candle>,frames:Map<Timeframe,List<Candle>>):Vote {
        if(c.size<55)return Vote("PRO_PRICE_STRUCTURE_TREND",SignalAction.WAIT,0,.20,"warm-up",professional=true)
        val structure=StrategyMath.higherHighHigherLow(c,10);val cl=StrategyMath.closes(c);val e20=StrategyMath.ema(cl,20);val e50=StrategyMath.ema(cl,50);val dmi=StrategyMath.wilderDmi(c,14);val mtf=mtfBullishCount(frames);val pull=if(e20>0)abs(t.lastPrice.toDouble()-e20)/e20*100 else 999.0
        return if(structure&&e20>e50&&dmi.plusDi>dmi.minusDi&&dmi.adx>=20&&pull<=1.25&&mtf>=2) Vote("PRO_PRICE_STRUCTURE_TREND",SignalAction.BUY,8,.74,"higher-high/higher-low structure + EMA/DMI trend; pullback=${f(pull)}%, MTF=$mtf",professional=true)
        else Vote("PRO_PRICE_STRUCTURE_TREND",SignalAction.WAIT,0,.26,"price structure lacks confirmed trend/pullback alignment",professional=true)
    }

    private fun proZscoreRangeReversion(t:MarketTicker,c:List<Candle>):Vote {
        if(c.size<55)return Vote("PRO_ZSCORE_RANGE_REVERSION",SignalAction.WAIT,0,.20,"warm-up",professional=true)
        val cl=StrategyMath.closes(c);val z=StrategyMath.zScore(cl,30);val dmi=StrategyMath.wilderDmi(c,14);val r=StrategyMath.rsi(cl,14);val vw=StrategyMath.vwap(c,48);val last=t.lastPrice.toDouble();val reversal=c.last().close>c.last().open&&c.last().close>c[c.lastIndex-1].close
        return when {
            z<=-1.75&&dmi.adx<20&&r<=38&&last<vw&&reversal -> Vote("PRO_ZSCORE_RANGE_REVERSION",SignalAction.BUY,7,.70,"range z-score=${f(z)}, ADX=${f(dmi.adx)}, RSI=${f(r)} with bullish reversal",professional=true)
            z>=2.0&&dmi.adx<20&&r>=68 -> Vote("PRO_ZSCORE_RANGE_REVERSION",SignalAction.SELL,-6,.66,"range z-score overextension=${f(z)}, RSI=${f(r)}",professional=true)
            else -> Vote("PRO_ZSCORE_RANGE_REVERSION",SignalAction.WAIT,0,.26,"z-score reversion requires non-trending regime plus price reversal",professional=true)
        }
    }

    private fun professionalExecutionFilter(s:BotSettings,t:MarketTicker,c:List<Candle>):Vote{val spread=spreadPct(t);val atr=StrategyMath.atrPct(c,14);val volume=t.volume24h.toDouble();return when{spread>s.maxSpreadPercent.toDouble()||volume<s.minVolume24hEur.toDouble()->Vote("FILTER_EXECUTION_QUALITY",SignalAction.WAIT,-10,.90,"spread/24h liquidity fails configured execution limits",entry=false,professional=true);atr>0&&spread>atr*.25->Vote("FILTER_EXECUTION_QUALITY",SignalAction.WAIT,-6,.78,"spread consumes >25% of ATR; expected edge is execution-fragile",entry=false,professional=true);spread<=s.maxSpreadPercent.toDouble()*.45&&atr>spread*4->Vote("FILTER_EXECUTION_QUALITY",SignalAction.WAIT,2,.55,"tight spread relative to ATR",entry=false,professional=true);else->Vote("FILTER_EXECUTION_QUALITY",SignalAction.WAIT,0,.35,"execution quality neutral",entry=false,professional=true)}}

    private fun professionalMtfFilter(frames:Map<Timeframe,List<Candle>>):Vote{val slopes=listOf(Timeframe.M5,Timeframe.M15,Timeframe.H1,Timeframe.H4).mapNotNull{tf->frames[tf]?.takeIf{it.size>=20}?.let{tf.name to StrategyMath.slopePct(StrategyMath.closes(it),12)}};if(slopes.size<2)return Vote("FILTER_MTF_ALIGNMENT",SignalAction.WAIT,0,.20,"insufficient MTF data",entry=false,professional=true);val bull=slopes.count{it.second>.10};val bear=slopes.count{it.second<-.10};return when{bull>=3->Vote("FILTER_MTF_ALIGNMENT",SignalAction.WAIT,4,.65,"multi-timeframe bullish alignment $slopes",entry=false,professional=true);bear>=2->Vote("FILTER_MTF_ALIGNMENT",SignalAction.WAIT,-7,.75,"two or more timeframes bearish $slopes",entry=false,professional=true);else->Vote("FILTER_MTF_ALIGNMENT",SignalAction.WAIT,0,.35,"mixed MTF structure $slopes",entry=false,professional=true)}}

    private fun professionalVolumeFilter(c:List<Candle>):Vote{val ratio=StrategyMath.volumeRatio(c,3,30);return when{ratio>=1.50->Vote("FILTER_VOLUME_CONFIRMATION",SignalAction.WAIT,3,.60,"above-average volume confirms price participation (${f(ratio)}x)",entry=false,professional=true);ratio<.55->Vote("FILTER_VOLUME_CONFIRMATION",SignalAction.WAIT,-3,.55,"weak participation (${f(ratio)}x average)",entry=false,professional=true);else->Vote("FILTER_VOLUME_CONFIRMATION",SignalAction.WAIT,0,.30,"volume participation neutral",entry=false,professional=true)}}

    private fun mtfBullishCount(frames:Map<Timeframe,List<Candle>>):Int = listOf(Timeframe.M15,Timeframe.H1,Timeframe.H4).count { tf -> frames[tf].orEmpty().let { x -> x.size>=30 && StrategyMath.ema(StrategyMath.closes(x),20)>StrategyMath.ema(StrategyMath.closes(x),50) && StrategyMath.momentumPct(StrategyMath.closes(x),12)>.0 } }

    private fun newsScore(news:List<NewsArticle>):Int{val pos=listOf("surge","approval","adoption","upgrade","partnership","record","bullish","launch");val neg=listOf("hack","exploit","ban","lawsuit","fraud","outage","bearish","liquidation");var score=0;for(n in news.take(30)){val text=(n.title+" "+n.description).lowercase();score+=pos.count{it in text}*3;score-=neg.count{it in text}*4};return score.coerceIn(-40,40)}
    private fun spreadPct(t:MarketTicker):Double=StrategyMath.spreadPct(t.lastPrice.toDouble(),t.bid.toDouble(),t.ask.toDouble())
    private fun f(v:Double)=BigDecimal.valueOf(v).setScale(3,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
