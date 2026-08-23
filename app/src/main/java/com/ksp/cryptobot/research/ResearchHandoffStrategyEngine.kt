package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.PositionEntity
import com.ksp.cryptobot.data.TradeEntity
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class HandoffDetectorContext(
    val ticker: MarketTicker,
    val structure: HandoffMarketStructure,
    val broad: BroadMarketContext,
    val recentTrades: List<TradeEntity>,
    val openPosition: PositionEntity?,
    val btcDominancePct: Double? = null,
    val btcDominanceChangePct: Double? = null,
    val nowEpochMs: Long = System.currentTimeMillis()
)

data class HandoffDetection(
    val candidate: HandoffTradeCandidate?,
    val adjustment: Int,
    val sizeMultiplier: Double = 1.0,
    val status: String,
    val reason: String
)

/**
 * Source-preserving strategy detectors for the 2026-08-17 research handoff.
 * B/C machine thresholds are explicitly formalizations; they are never represented as hidden creator rules.
 */
class ResearchHandoffStrategyEngine(private val structureEngine: ResearchHandoffStructureEngine) {
    fun detect(def: HandoffStrategyDefinition, c: HandoffDetectorContext): HandoffDetection = when (def.id) {
        "brandt_classical_atr_breakout_2026" -> brandtClassical(def,c)
        "brandt_3dtsr" -> brandt3dtsr(def,c)
        "brandt_sos_sow_hinge" -> brandtSosHinge(def,c)
        "brandt_adx_ma_anticipatory" -> brandtAdxMa(def,c)
        "brandt_breakout_retest" -> brandtBreakoutRetest(def,c)
        "brandt_secondary_breakout" -> brandtSecondary(def,c)
        "cryptocred_htf_structure_ltf_trigger" -> credTopDown(def,c)
        "cryptocred_sr_flip_first_retest" -> credFirstRetest(def,c,false)
        "cryptocred_rounded_retest" -> credFirstRetest(def,c,true)
        "cryptocred_pdh_pdl_bias" -> credPdhPdl(def,c)
        "cryptocred_fta_management" -> credFta(def,c)
        "cryptocred_pattern_failure" -> credPatternFailure(def,c)
        "cryptocred_risk_sizing" -> context(def,c,"Risk sizing is applied mechanically after every eligible entry candidate.",0,1.0)
        "tcg_backburner_bullish" -> tcgBackBurner(def,c)
        "tcg_equilibrium_break" -> tcgEquilibrium(def,c)
        "tcg_inside_bar" -> tcgInsideBar(def,c)
        "tcg_ema12_rider" -> tcgEma12(def,c)
        "tcg_stair_step_formalization" -> tcgStairStep(def,c)
        "tcg_relative_strength_filter" -> tcgRelativeStrength(def,c)
        "krown_public_trend_vol_momentum_framework" -> krownFramework(def,c)
        "krown_quant_validation_process" -> krownValidation(def,c)
        "krown_vmp_exact" -> proprietary(def,c)
        "cowen_dynamic_dca_concept" -> cowenDca(def,c)
        "cowen_btc_dominance_regime" -> cowenDominance(def,c)
        "cowen_price_risk_exact" -> proprietary(def,c)
        "loukas_four_year_cycle_regime" -> loukasFourYear(def,c)
        "loukas_60day_cycle_context" -> loukas60Day(def,c)
        "pizzino_swing_trade_plan" -> pizzinoSwing(def,c)
        "pizzino_cycle_structure_filter" -> pizzinoStructure(def,c)
        "rastani_opening_gap_original" -> rastaniGap(def,c)
        "rastani_elliott_wave_context" -> rastaniElliott(def,c)
        else -> HandoffDetection(null,0,1.0,"UNRECOGNIZED","No detector registered for ${def.id}; refusing to invent one.")
    }

    private fun brandtClassical(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val daily=c.structure.daily
        val range=structureEngine.recentHorizontalRange(daily,40,98) ?: return warm(d,c,"Needs at least 40 closed UTC daily bars; source example is roughly 8–14 weeks.")
        val low=range.first; val high=range.second; val width=range.third
        val atr=atrBars(daily,30); val adx=adxBars(daily,14); val closes=daily.map{it.close}; val ma18=sma(closes,18); val ma18Prev=sma(closes.dropLast(1),18)
        if(atr<=0||ma18<=0)return warm(d,c,"ATR30/MA18 unavailable.")
        val compactLimit=max(6.0,min(18.0,(atr/((high+low)/2.0))*100.0*8.0))
        val mature=daily.size>=56
        val compact=width<=compactLimit
        val maUp=ma18>ma18Prev
        val compression=adx<12.0
        val trigger=high+.5*atr
        val last=daily.lastOrNull()?.close?:return warm(d,c,"No daily close.")
        val crossed=daily.size>=2 && daily[daily.lastIndex-1].close<trigger && last>=trigger
        val setup=mature&&compact&&(compression||maUp)
        if(!setup)return noSetup(d,c,"Brandt formalization not qualified: mature=$mature compact=$compact width=${f(width)}% limit=${f(compactLimit)}% ADX14=${f(adx)} MA18up=$maUp. Pattern recognition remains discretionary in source.")
        val stop=high-.5*atr
        val target=trigger+(high-low)
        val cand=entry(d,c,HandoffEntryKind.RESTING_STOP,trigger,trigger,OrderType.STOP_LOSS,stop,listOf(target),"D1","D1",crossed,
            "Machine formalization of the 2026-described mature horizontal/continuation setup. Resting trigger = boundary + 0.5*ATR30 is source-described; pattern qualification and protective-stop selection remain discretionary. Stop here is a disclosed structural formalization, not attributed as Brandt's universal stop.")
        return HandoffDetection(cand,if(crossed)5 else 2,.85,if(crossed)"TRIGGERED" else "RESTING_SETUP","range=${f(low)}..${f(high)} width=${f(width)}% ATR30=${f(atr)} ADX14=${f(adx)} MA18=${f(ma18)} trigger=${f(trigger)}.")
    }

    private fun brandt3dtsr(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val bars=c.structure.daily
        if(bars.size<6)return warm(d,c,"3DTSR needs closed daily history.")
        val recent=bars.takeLast(6)
        val extremeIndex=recent.dropLast(2).indices.maxByOrNull{recent[it].high}?:return noSetup(d,c,"No extreme day.")
        val extreme=recent[extremeIndex]
        val setupIndex=(extremeIndex+1 until recent.lastIndex).firstOrNull{recent[it].close<extreme.low}
        val setup=setupIndex?.let{recent[it]}
        val triggered=setup!=null && recent.last().low<setup.low
        if(!triggered)return noSetup(d,c,"3DTSR long-position exit formalization not triggered. Extreme=${f(extreme.high)}/${f(extreme.low)} setup=${setup?.let{f(it.low)}?:"none"}.")
        val cand=protective(d,c,HandoffSideIntent.EXIT,setup!!.low,"D1","D1","3DTSR tactical exit formalization triggered by penetration below the setup-day low after a close below the extreme-day opposite side. Activation timing/target-progress remains discretionary in source.")
        return HandoffDetection(cand,-10,.0,"PROTECTIVE_EXIT","3DTSR protective exit trigger=${f(setup.low)}.")
    }

    private fun brandtSosHinge(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val bars=c.structure.daily; if(bars.size<35)return warm(d,c,"SOS/SOW hinge formalization needs daily history.")
        val atr=atrBars(bars,14); if(atr<=0)return warm(d,c,"ATR unavailable.")
        val hinge=bars.dropLast(2).takeLast(5); val range=hinge.maxOf{it.high}-hinge.minOf{it.low}
        val baseVol=bars.dropLast(2).takeLast(30).map{it.volume}.average(); val hingeVol=hinge.map{it.volume}.average()
        val a=bars[bars.lastIndex-1];val b=bars.last()
        val wideBull=a.close>a.open&&b.close>b.open&&(a.close-a.open)>.55*atr&&(b.close-b.open)>.55*atr
        val setup=range<1.25*atr && baseVol>0 && hingeVol/baseVol<.8 && wideBull
        if(!setup)return noSetup(d,c,"Hinge/SOS formalization absent: hingeRange/ATR=${f(range/max(atr,1e-9))}, volumeRatio=${f(if(baseVol>0)hingeVol/baseVol else 0.0)}, wideBull=$wideBull.")
        val boundary=hinge.maxOf{it.high}; val stop=hinge.minOf{it.low}; val target=b.close+(boundary-stop)
        val cand=entry(d,c,HandoffEntryKind.MARKET_CONFIRMATION,b.close,null,OrderType.MARKET,stop,listOf(target),"D1","D1",true,"Machine definition for discretionary 'narrow hinge', 'very low volume' and consecutive wide bullish bodies. Source explicitly describes this as higher-risk anticipatory behavior, so size is reduced.")
        return HandoffDetection(cand,4,.50,"FORMALIZED_TRIGGER","SOS hinge anticipatory formalization detected; reduced 0.50x research size.")
    }

    private fun brandtAdxMa(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val b=c.structure.daily;if(b.size<35)return warm(d,c,"ADX/18DMA anticipatory rule needs daily bars.")
        val adx=adxBars(b.dropLast(1),14);val closes=b.map{it.close};val ma=sma(closes,18);val prevMa=sma(closes.dropLast(1),18);val prev=b[b.lastIndex-1].close;val last=b.last().close
        val cross=prev<=prevMa&&last>ma; val up=ma>prevMa
        if(!(adx<12&&cross&&up))return noSetup(d,c,"Formalized conditions: ADX14=${f(adx)} (<12), 18DMA cross=$cross, MA rising=$up.")
        val stop=b.takeLast(10).minOf{it.low};val risk=last-stop;val target=last+max(risk*2,b.takeLast(25).maxOf{it.high}-last)
        return HandoffDetection(entry(d,c,HandoffEntryKind.MARKET_CONFIRMATION,last,null,OrderType.MARKET,stop,listOf(target),"D1","D1",true,"Historical public-core ADX<12 + 18DMA directional-cross rule. Mature-pattern judgement and exact stop remain discretionary; stop is the disclosed machine structural formalization."),4,.65,"FORMALIZED_TRIGGER","ADX compression + rising 18DMA cross detected.")
    }

    private fun brandtBreakoutRetest(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val b=c.structure.daily;if(b.size<35)return warm(d,c,"Breakout retest needs daily history.")
        val hit=genericRetest(b,30,.50)?:return noSetup(d,c,"No qualified formalized daily breakout→retest on current closed bar.")
        val level=hit.first;val atr=atrBars(b,30);val entry=level+.5*atr;val stop=level-.5*atr;val target=entry+max(2*(entry-stop),b.takeLast(30).maxOf{it.high}-b.takeLast(30).minOf{it.low})
        return HandoffDetection(entry(d,c,HandoffEntryKind.LIMIT_RETEST,entry,null,OrderType.LIMIT,stop,listOf(target),"D1","D1",true,"Historical source describes boundary retest orders adjusted by ~0.5 ATR in context. Breakout qualification and offset direction remain discretionary; this is an explicit machine version."),4,.75,"FORMALIZED_TRIGGER","Daily breakout retest at ${f(level)}, ATR30=${f(atr)}.")
    }

    private fun brandtSecondary(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val b=c.structure.daily;if(b.size<35)return warm(d,c,"Secondary breakout needs daily history.")
        val prior=b.dropLast(3).takeLast(30);if(prior.isEmpty())return noSetup(d,c,"No prior range.")
        val level=prior.maxOf{it.high};val first=b[b.lastIndex-3];val fail=b[b.lastIndex-2];val now=b.last()
        val firstBreak=first.high>level&&first.close>level;val failed=fail.close<level;val rebreak=now.close>max(level,first.high)
        if(!(firstBreak&&failed&&rebreak))return noSetup(d,c,"Secondary breakout sequence absent: first=$firstBreak failed=$failed rebreak=$rebreak.")
        val stop=min(fail.low,level);val target=now.close+2*(now.close-stop)
        return HandoffDetection(entry(d,c,HandoffEntryKind.MARKET_CONFIRMATION,now.close,null,OrderType.MARKET,stop,listOf(target),"D1","D1",true,"Public examples permit a second attempt after a first failure and discuss willingness to try twice. 'Strong re-break' is machine-defined here as a close beyond the first breakout high."),4,.65,"SECOND_ATTEMPT","Formalized second breakout attempt detected; campaign risk remains separately capped.")
    }

    private fun credTopDown(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val s=c.structure;if(s.h1.size<55||s.m15.size<30)return warm(d,c,"Top-down detector needs H4/H1/M15 closed data.")
        val htfBull=s.trendH4 in setOf("HH_HL","BULLISH");val support=s.nearestSupportH1?:return noSetup(d,c,"No mapped H1 support below price.")
        val last=s.m15.last();val atr=max(s.atrH1,1e-9);val atLocation=abs(last.low.toDouble()-support)<=.5*atr||last.low.toDouble()<support&&last.close.toDouble()>support
        val confirm=s.m15.size>=2&&last.close>s.m15[s.m15.lastIndex-1].high
        if(!(htfBull&&atLocation&&confirm))return noSetup(d,c,"HTF bullish=$htfBull at planned support=$atLocation LTF bullish trigger=$confirm; support=${f(support)}.")
        val stop=min(last.low.toDouble(),support-.25*atr);val fta=structureEngine.firstTroubleArea(s,last.close.toDouble())?:last.close.toDouble()+2*(last.close.toDouble()-stop)
        return HandoffDetection(entry(d,c,HandoffEntryKind.MARKET_CONFIRMATION,last.close.toDouble(),null,OrderType.MARKET,stop,listOf(fta),"H4/H1","M15",true,"Machine formalization preserves CryptoCred's layer separation: HTF structure/location first, then LTF trigger, with structural invalidation and FTA. Exact level placement/trigger quality remain discretionary."),5,.85,"TRIGGERED","HTF→LTF long alignment at support=${f(support)}; FTA=${f(fta)}.")
    }

    private fun credFirstRetest(d:HandoffStrategyDefinition,c:HandoffDetectorContext,rounded:Boolean):HandoffDetection {
        val s=c.structure;val hit=if(rounded)structureEngine.roundedRetest(s.h1) else structureEngine.breakoutRetest(s.h1)
        if(hit==null)return noSetup(d,c,"No ${if(rounded)"rounded " else "first "}H1 S/R flip retest on current closed bar.")
        val level=hit.first;val last=s.h1.last();val atr=max(s.atrH1,1e-9);val stop=min(last.low.toDouble(),level-.35*atr);val fta=structureEngine.firstTroubleArea(s,last.close.toDouble())?:last.close.toDouble()+2*(last.close.toDouble()-stop)
        val desc=if(rounded)"Rounded retest requires >=6 bars and >=1 ATR separation before return; these numerical thresholds are our explicit formalization because public minimum time/distance is not fixed." else "First-retake classifier uses the first meaningful touch after decisive machine-defined breakout; level quality remains discretionary."
        return HandoffDetection(entry(d,c,HandoffEntryKind.LIMIT_RETEST,last.close.toDouble(),level,OrderType.LIMIT,stop,listOf(fta),"H1","H1/M15",true,"$desc Structural invalidation and FTA are preserved."),5,.85,"TRIGGERED","${if(rounded)"Rounded" else "First"} S/R retest level=${f(level)} FTA=${f(fta)}.")
    }

    private fun credPdhPdl(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val x=structureEngine.pdhPdlReclaim(c.structure)?:return noSetup(d,c,"No PDH/PDL sweep/reclaim on latest closed M15 bar.")
        if(x.first=="PDH_REJECTION"){
            val cand=if(c.openPosition!=null) protective(d,c,HandoffSideIntent.REDUCE,x.second,"D1","M15","Bearish rejection above PDH maps to REDUCE/AVOID in Belgium long-only spot; it is never converted into a short derivative order.") else contextCandidate(d,c,HandoffSideIntent.AVOID,"PDH rejection => avoid new long.")
            return HandoffDetection(cand,-5,.65,if(c.openPosition!=null)"PROTECTIVE_REDUCE" else "AVOID","PDH rejection ${f(x.second)}.")
        }
        val htfOkay=c.structure.trendH4!="LH_LL";if(!htfOkay)return noSetup(d,c,"PDL reclaimed but HTF structure is bearish; source context gate blocks long.")
        val last=c.structure.m15.last();val stop=last.low.toDouble();val target=c.structure.previousDayHigh?:structureEngine.firstTroubleArea(c.structure,last.close.toDouble())?:last.close.toDouble()+2*(last.close.toDouble()-stop)
        return HandoffDetection(entry(d,c,HandoffEntryKind.MARKET_CONFIRMATION,last.close.toDouble(),x.second,OrderType.MARKET,stop,listOf(target),"D1/H4","M15",true,"Long formalization requires sweep below PDL then close back above it, plus non-bearish HTF context. 'Strength/impulse' is machine-defined by reclaim close; public source leaves quality discretionary."),4,.80,"TRIGGERED","PDL reclaim=${f(x.second)} target=${f(target)}.")
    }

    private fun credFta(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val entry=c.ticker.lastPrice.toDouble();val fta=structureEngine.firstTroubleArea(c.structure,entry)
        val text=if(fta==null)"No mapped opposing H1 pivot above current price; FTA unavailable." else "Mapped nearest opposing H1 pivot as machine FTA=${f(fta)}. Parent strategy must still decide partial/full management."
        return context(d,c,text,if(fta==null)0 else -1,if(fta!=null&&((fta-entry)/max(entry,1e-9)*100)<1.2).75 else 1.0)
    }

    private fun credPatternFailure(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val fail=structureEngine.patternFailure(c.structure.h1)?:return noSetup(d,c,"No failed H1 breakout on latest closed bars.")
        if(fail.first=="BULL_BREAK_FAILED"){
            val cand=if(c.openPosition!=null)protective(d,c,HandoffSideIntent.EXIT,fail.second,"H1","H1","Bullish breakout failed and closed back through structure. In long-only spot this is protective EXIT/AVOID, not a short entry.") else contextCandidate(d,c,HandoffSideIntent.AVOID,"Failed bullish breakout; avoid new long.")
            return HandoffDetection(cand,-10,0.0,if(c.openPosition!=null)"PROTECTIVE_EXIT" else "AVOID","Bull breakout failure at ${f(fail.second)}.")
        }
        val last=c.structure.h1.last();val stop=last.low.toDouble();val target=structureEngine.firstTroubleArea(c.structure,last.close.toDouble())?:last.close.toDouble()+2*(last.close.toDouble()-stop)
        return HandoffDetection(entry(d,c,HandoffEntryKind.MARKET_CONFIRMATION,last.close.toDouble(),fail.second,OrderType.MARKET,stop,listOf(target),"H1/H4","H1",true,"Failed bearish breakdown reclaimed key structure; Belgium spot maps the reversal information to a possible long. Pattern/failure acceptance remains discretionary and is machine-formalized."),4,.75,"TRIGGERED","Bear breakdown failure/reclaim at ${f(fail.second)}.")
    }

    private fun tcgBackBurner(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val s=c.structure;if(s.h1.size<60||s.m15.size<35)return warm(d,c,"BackBurner formalization needs H1/M15 data.")
        val h1Close=StrategyMath.closes(s.h1);val momentum=StrategyMath.momentumPct(h1Close,12);val atrPct=StrategyMath.atrPct(s.h1,14);val strongTrend=s.trendH4 in setOf("HH_HL","BULLISH")&&momentum>max(1.5,2*atrPct)
        val m15Close=StrategyMath.closes(s.m15);val rsi=StrategyMath.rsi(m15Close,14);val previousOversold=s.m15.dropLast(2).takeLast(20).let{StrategyMath.rsi(StrategyMath.closes(it),14)<30}
        val last=s.m15.last();val recovery=s.m15.size>=2&&last.close>s.m15[s.m15.lastIndex-1].close
        val setup=strongTrend&&rsi<35&&!previousOversold&&recovery
        if(!setup)return noSetup(d,c,"BackBurner public-core formalization: larger trend=$strongTrend H1momentum=${f(momentum)}% ATRP=${f(atrPct)}% M15RSI=${f(rsi)} priorOversold=$previousOversold recovery=$recovery.")
        val stop=s.h1.takeLast(12).minOf{it.low.toDouble()};val target=structureEngine.firstTroubleArea(s,last.close.toDouble())?:last.close.toDouble()+2*(last.close.toDouble()-stop)
        return HandoffDetection(entry(d,c,HandoffEntryKind.MARKET_CONFIRMATION,last.close.toDouble(),null,OrderType.MARKET,stop,listOf(target),"H1/H4","M15",true,"Paid indicator trigger is proprietary and is NOT reconstructed. This independent public-core formalization requires a significant larger-TF bullish move, first lower-TF oversold reaction and recovery; numeric thresholds are ours."),4,.65,"FORMALIZED_TRIGGER","BackBurner-inspired public-core formalization detected; source exact paid trigger remains unknown.")
    }

    private fun tcgEquilibrium(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val h=c.structure.h1;if(h.size<10)return warm(d,c,"Equilibrium needs H1 swings.")
        if(!structureEngine.equilibrium(h,4))return noSetup(d,c,"No current machine-defined lower-high/higher-low equilibrium.")
        val w=h.takeLast(5);val upper=w.maxOf{it.high.toDouble()};val lower=w.minOf{it.low.toDouble()};val trigger=upper+max(c.structure.atrH1*.05,upper*.0002);val target=trigger+(upper-lower)
        val crossed=h.last().close.toDouble()>trigger
        return HandoffDetection(entry(d,c,HandoffEntryKind.RESTING_STOP,trigger,trigger,OrderType.STOP_LOSS,lower,listOf(target),"H1","H1",crossed,"Equilibrium geometry (lower highs + higher lows) is public. Pivot/confirmation policy is versioned machine formalization. Long-only breakout; bearish break becomes avoid/reduce."),if(crossed)4 else 2,.80,if(crossed)"TRIGGERED" else "RESTING_SETUP","Equilibrium ${f(lower)}..${f(upper)} trigger=${f(trigger)}.")
    }

    private fun tcgInsideBar(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val h=c.structure.h1;if(h.size<3)return warm(d,c,"Inside bar needs mother+child+closed data.")
        if(!structureEngine.insideBar(h,true))return noSetup(d,c,"Latest H1 bar is not inside its mother bar under equality-allowed policy.")
        val mother=h[h.lastIndex-1];val trigger=mother.high.toDouble();val stop=mother.low.toDouble();val target=trigger+2*(trigger-stop);val crossed=h.last().close.toDouble()>trigger
        return HandoffDetection(entry(d,c,HandoffEntryKind.RESTING_STOP,trigger,trigger,OrderType.STOP_LOSS,stop,listOf(target),"H1","H1",crossed,"Inside-bar containment is source-faithful mechanical detection. The source explicitly says detection is not itself an automatic trade; this app requires the parent research/context and cost/risk gates before execution. Mother-bar stop/2R target are app policy, not attributed to TCG."),if(crossed)4 else 1,.75,if(crossed)"TRIGGERED" else "SETUP_ONLY","Inside bar mother range ${f(stop)}..${f(trigger)}.")
    }

    private fun tcgEma12(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val h=c.structure.h1;if(h.size<40)return warm(d,c,"EMA12 formalization needs H1 data.")
        val closes=StrategyMath.closes(h);val e12=StrategyMath.ema(closes,12);val e26=StrategyMath.ema(closes,26);val last=h.last();val touch=last.low.toDouble()<=e12*1.003&&last.close.toDouble()>e12;val trend=e12>e26&&closes.last()>e26
        if(!(trend&&touch))return noSetup(d,c,"EMA12 public-core formalization absent: trend=$trend touch/reclaim=$touch EMA12=${f(e12)} EMA26=${f(e26)}.")
        val stop=h.takeLast(8).minOf{it.low.toDouble()};val target=last.close.toDouble()+2*(last.close.toDouble()-stop)
        return HandoffDetection(entry(d,c,HandoffEntryKind.LIMIT_RETEST,last.close.toDouble(),e12,OrderType.LIMIT,stop,listOf(target),"H1/H4","H1",true,"Full current EMA Rider guide is gated. This is explicitly FORMALIZED_FROM_PUBLIC_CORE: established trend + EMA12 interaction/reclaim; stop/target are independent app formalization."),3,.70,"FORMALIZED_RESEARCH","EMA12 public-core pullback detected. Not source-exact live strategy.")
    }

    private fun tcgStairStep(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val h=c.structure.h1;if(h.size<12)return warm(d,c,"Stair Step formalization needs swing history.")
        val pre=h.dropLast(1).takeLast(5);val descending=pre.zipWithNext().all{(a,b)->b.high<a.high&&b.low<a.low};val last=h.last();val breakUp=descending&&last.close>pre.last().high
        if(!breakUp)return noSetup(d,c,"Independent Stair Step formalization absent: descending=$descending reversalBreak=$breakUp.")
        val stop=pre.minOf{it.low.toDouble()};val target=last.close.toDouble()+2*(last.close.toDouble()-stop)
        return HandoffDetection(entry(d,c,HandoffEntryKind.MARKET_CONFIRMATION,last.close.toDouble(),null,OrderType.MARKET,stop,listOf(target),"H1","H1",true,"Exact gated Stair Step definition is unavailable. This independent bullish reversal formalization uses a sequence of descending steps followed by close above the prior step high. It is NOT claimed as the commercial rule."),3,.60,"FORMALIZED_RESEARCH","Independent Stair Step public-core formalization triggered.")
    }

    private fun tcgRelativeStrength(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val cl=StrategyMath.closes(c.structure.h1);if(cl.size<25)return warm(d,c,"Relative-strength filter needs 24+ H1 closes.")
        val own=StrategyMath.momentumPct(cl,24);val bench=if(c.ticker.symbol.uppercase().contains("BTC"))c.broad.ethMomentumPct else c.broad.btcMomentumPct;val diff=own-bench
        return context(d,c,"Candidate 24h momentum=${f(own)}%, benchmark=${f(bench)}%, relative=${f(diff)}%. Correlation window/metric is an explicit app formalization.",when{diff>1.0->2;diff< -1.0->-3;else->0},when{diff< -2.0->.65;diff<-.75->.85;else->1.0})
    }

    private fun krownFramework(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val h=c.structure.h1;if(h.size<60)return warm(d,c,"Trend/volatility/momentum framework needs H1 history.")
        val cl=StrategyMath.closes(h);val e20=StrategyMath.ema(cl,20);val e50=StrategyMath.ema(cl,50);val rsi=StrategyMath.rsi(cl,14);val atr=StrategyMath.atrPct(h,14);val trend=cl.last()>e20&&e20>e50;val mom=rsi in 50.0..72.0;val vol=atr in .15..5.0
        return context(d,c,"Independent public-framework state: trend=$trend (EMA20/50), momentum=$mom (RSI=${f(rsi)}), volatilityUsable=$vol (ATRP=${f(atr)}%). Exact proprietary indicator/template weights are not used.",when{trend&&mom&&vol->2;!trend&&rsi<45->-2;else->0},if(atr>4).65 else 1.0)
    }

    private fun krownValidation(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val rows=c.recentTrades.filter{it.symbol.equals(c.ticker.symbol,true)&&abs(it.realizedPnlEur.toDoubleOrNull()?:0.0)>1e-9}.takeLast(40)
        if(rows.size<12)return context(d,c,"Quant validation process warm-up ${rows.size}/12 realized outcomes. Threshold is app research policy, not claimed Krown curriculum threshold.",-1,.75)
        val pnl=rows.sumOf{it.realizedPnlEur.toDoubleOrNull()?:0.0};val wins=rows.count{(it.realizedPnlEur.toDoubleOrNull()?:0.0)>0};val wr=wins.toDouble()/rows.size
        return context(d,c,"App walk-forward/shelf-life proxy: n=${rows.size} net=${f(pnl)} winRate=${f(wr*100)}%. Exact proprietary curriculum thresholds are not public.",if(pnl<0||wr<.4)-4 else 1,if(pnl<0).60 else 1.0)
    }

    private fun cowenDca(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val daily=c.structure.daily;if(daily.size<60)return context(d,c,"INDEPENDENT_RISK_BAND warm-up; Dynamic DCA remains portfolio allocation context, never an intraday trigger.",0,.75)
        val closes=daily.map{it.close};val current=closes.last();val lo=closes.takeLast(100).minOrNull()?:current;val hi=closes.takeLast(100).maxOrNull()?:current;val pct=if(hi>lo)(current-lo)/(hi-lo) else .5
        val mult=when{pct>=.85->.25;pct>=.70->.45;pct>=.50->.70;else->1.0}
        return context(d,c,"INDEPENDENT_RISK_BAND_v1 percentile=${f(pct*100)}% over available closed daily history => allocation cap ${f(mult)}x. This is NOT Cowen's proprietary Price Risk or exact DCA ladder.",if(pct>.85)-2 else 0,mult)
    }

    private fun cowenDominance(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val dom=c.btcDominancePct ?: return context(d,c,"BTC dominance source unavailable; missing macro/context fails neutral, never silently bullish.",0,1.0)
        val change=c.btcDominanceChangePct
        val isAlt=!c.ticker.symbol.uppercase().startsWith("BTC")
        val adj=if(isAlt&&change!=null&&change>.5)-3 else if(!isAlt&&change!=null&&change>.5)1 else 0
        val mult=if(isAlt&&change!=null&&change>.5).70 else 1.0
        return context(d,c,"BTC dominance=${f(dom)}% changeSample=${change?.let{f(it)}?:"n/a"}%; filter formalization only. Trend window/threshold are app policy, not proprietary Price Risk.",adj,mult)
    }

    private fun loukasFourYear(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val days=c.structure.daily.size
        if(days<730)return context(d,c,"LOUKAS_CYCLE_FORMALIZATION_v1 has only $days closed daily bars; insufficient multi-year history. No cycle phase is invented.",0,1.0)
        val closes=c.structure.daily.map{it.close};val ma200=sma(closes,200);val current=closes.last();val state=if(current>ma200)"ABOVE_200D" else "BELOW_200D"
        return context(d,c,"LOUKAS_CYCLE_FORMALIZATION_v1 slow regime=$state using long-horizon price state only. This is not claimed as Loukas's exact cycle-counting algorithm.",if(current>ma200)1 else -1,if(current<ma200).75 else 1.0)
    }

    private fun loukas60Day(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection = context(d,c,structureEngine.sixtyDayCycleContext(c.structure.daily)+". Cycle segmentation/translation remains a formalization and is context-only.",0,1.0)

    private fun pizzinoSwing(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val s=c.structure;if(s.h1.size<40)return warm(d,c,"Swing-plan formalization needs structure history.")
        val htf=s.trendH4 in setOf("HH_HL","BULLISH");val last=s.h1.last();val support=s.nearestSupportH1;val atSupport=support!=null&&abs(last.low.toDouble()-support)<max(s.atrH1,.001);val confirm=s.h1.size>=2&&last.close>s.h1[s.h1.lastIndex-1].high
        if(!(htf&&atSupport&&confirm))return noSetup(d,c,"Pizzino independent swing-plan formalization: HTF bullish=$htf atSupport=$atSupport confirmation=$confirm.")
        val stop=min(last.low.toDouble(),support!!-.25*max(s.atrH1,.001));val target=structureEngine.firstTroubleArea(s,last.close.toDouble())?:last.close.toDouble()+2*(last.close.toDouble()-stop)
        return HandoffDetection(entry(d,c,HandoffEntryKind.MARKET_CONFIRMATION,last.close.toDouble(),support,OrderType.MARKET,stop,listOf(target),"H4","H1",true,"Public material establishes planned technical entry/invalidation/target process but not a single exact indicator algorithm. This machine swing trigger is explicitly our formalization."),3,.70,"FORMALIZED_RESEARCH","Pizzino swing-plan formalization triggered.")
    }

    private fun pizzinoStructure(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val bull=c.structure.trendH4 in setOf("HH_HL","BULLISH")
        return context(d,c,"Cycle+structure filter uses machine HH/HL structure; broader cycle timing definitions remain discretionary/public-core only. H4=${c.structure.trendH4}.",if(bull)1 else -2,if(bull)1.0 else .65)
    }

    private fun rastaniGap(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        return context(d,c,"Original opening-gap strategy is a session-market method. Kraken spot is 24/7, so exact live execution is NOT ported. Any UTC synthetic-session gap study is a separately named inferred adaptation and remains research-only.",0,1.0,HandoffSideIntent.RESEARCH)
    }

    private fun rastaniElliott(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val piv=c.structure.h4Pivots.takeLast(8);val desc="Observed ${piv.size} recent H4 machine pivots. Elliott wave count/degree is non-unique, so the app stores scenario context only and never turns a guessed count into an autonomous live trigger."
        return context(d,c,desc,0,1.0,HandoffSideIntent.CONTEXT)
    }

    private fun proprietary(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val cand=contextCandidate(d,c,HandoffSideIntent.BLOCKED_SOURCE_UNKNOWN,"${d.name}: exact formula/settings are proprietary or materially under-specified. The app deliberately does not reverse-engineer or substitute a generic proxy.")
        return HandoffDetection(cand,0,1.0,"BLOCKED_SOURCE_UNKNOWN","PROPRIETARY_NOT_IMPLEMENTED: ${d.mustNotClaim}")
    }

    private fun entry(d:HandoffStrategyDefinition,c:HandoffDetectorContext,kind:HandoffEntryKind,entry:Double,trigger:Double?,orderType:OrderType?,stop:Double,targets:List<Double>,thesisTf:String,execTf:String,triggered:Boolean,note:String):HandoffTradeCandidate {
        val implementation=when(d.fidelity.uppercase()){"A"->if(d.usageContextSourceVerified&&d.noTradeConditionsSourceVerified)HandoffImplementationClass.SOURCE_FAITHFUL else HandoffImplementationClass.SOURCE_FAITHFUL_WITH_DISCRETION;"B"->HandoffImplementationClass.SOURCE_FAITHFUL_WITH_DISCRETION;"C"->HandoffImplementationClass.FORMALIZED_FROM_PUBLIC_CORE;else->HandoffImplementationClass.PROPRIETARY_NOT_IMPLEMENTED}
        val eligibility=when(d.fidelity.uppercase()){ "A","B"->HandoffExecutionEligibility.PAPER_AND_TRUTH_GATED_LIVE;"C"->HandoffExecutionEligibility.PAPER_ONLY;else->HandoffExecutionEligibility.BLOCKED }
        return HandoffTradeCandidate(d.id,"${d.researchFreeze}:${d.id}",d.trader,d.name,c.ticker.symbol,HandoffSideIntent.LONG_ENTRY,c.nowEpochMs,thesisTf,execTf,
            HandoffEntryPlan(kind,entry.bd(),trigger?.bd(),orderType,kind==HandoffEntryKind.RESTING_STOP,kind in setOf(HandoffEntryKind.LIMIT,HandoffEntryKind.LIMIT_RETEST),implementation!=HandoffImplementationClass.SOURCE_FAITHFUL,note),
            HandoffInvalidationPlan(stop.bd(),false,"STRUCTURAL_MACHINE_STOP",false,"Technical/structural stop formalization. Source-specific discretion is preserved in metadata."),
            targets.filter{it>entry}.mapIndexed{i,p->HandoffTargetPlan("T${i+1}",p.bd(),if(i==0)BigDecimal.ONE else BigDecimal.ZERO,false,"Source structural/measured objective translated into explicit machine target; exact target choice may remain discretionary.")},
            mapOf("data_integrity" to c.structure.integrityReason,"trend_h1" to c.structure.trendH1,"trend_h4" to c.structure.trendH4,"trigger_detected" to triggered.toString()),d.provenance+d.sourceRefs,d.fidelity,implementation,eligibility,d.liveTruthGate,true,triggered,d.usageContextSourceVerified&&d.noTradeConditionsSourceVerified,note)
    }

    private fun protective(d:HandoffStrategyDefinition,c:HandoffDetectorContext,intent:HandoffSideIntent,trigger:Double,thesisTf:String,execTf:String,note:String):HandoffTradeCandidate = HandoffTradeCandidate(
        d.id,"${d.researchFreeze}:${d.id}",d.trader,d.name,c.ticker.symbol,intent,c.nowEpochMs,thesisTf,execTf,
        HandoffEntryPlan(HandoffEntryKind.NONE,null,trigger.bd(),null,false,false,true,"Protective action; not a new entry."),
        HandoffInvalidationPlan(trigger.bd(),true,"PROTECTIVE_TRIGGER",false,note),emptyList(),mapOf("data_integrity" to c.structure.integrityReason),d.provenance+d.sourceRefs,d.fidelity,d.implementationClass,HandoffExecutionEligibility.PROTECTIVE_LIVE_ALLOWED,d.liveTruthGate,true,true,false,note)

    private fun context(d:HandoffStrategyDefinition,c:HandoffDetectorContext,reason:String,adj:Int,mult:Double,intent:HandoffSideIntent=HandoffSideIntent.FILTER)=HandoffDetection(contextCandidate(d,c,intent,reason),adj,mult.coerceIn(.1,1.0),"CONTEXT",reason)
    private fun contextCandidate(d:HandoffStrategyDefinition,c:HandoffDetectorContext,intent:HandoffSideIntent,reason:String)=HandoffTradeCandidate(d.id,"${d.researchFreeze}:${d.id}",d.trader,d.name,c.ticker.symbol,intent,c.nowEpochMs,d.timeframes.firstOrNull()?:("context"),d.timeframes.lastOrNull()?:("context"),HandoffEntryPlan(HandoffEntryKind.NONE,null,null,null,false,false,true,reason),HandoffInvalidationPlan(null,false,"NONE",false,"Context/filter only."),emptyList(),mapOf("data_integrity" to c.structure.integrityReason),d.provenance+d.sourceRefs,d.fidelity,d.implementationClass,if(d.fidelity.equals("X",true))HandoffExecutionEligibility.BLOCKED else HandoffExecutionEligibility.RESEARCH_ONLY,d.liveTruthGate,true,true,d.usageContextSourceVerified&&d.noTradeConditionsSourceVerified,reason)
    private fun noSetup(d:HandoffStrategyDefinition,c:HandoffDetectorContext,reason:String)=HandoffDetection(contextCandidate(d,c,HandoffSideIntent.RESEARCH,reason),0,1.0,"NO_SETUP",reason)
    private fun warm(d:HandoffStrategyDefinition,c:HandoffDetectorContext,reason:String)=HandoffDetection(contextCandidate(d,c,HandoffSideIntent.RESEARCH,reason),0,.85,"WARMUP",reason)

    private fun genericRetest(b:List<HandoffBar>,lookback:Int,tolAtr:Double):Pair<Double,Int>?{
        if(b.size<lookback+3)return null;val atr=atrBars(b,14);if(atr<=0)return null
        for(i in b.size-3 downTo max(lookback,b.size-12)){
            val prior=b.subList(i-lookback,i);val level=prior.maxOf{it.high};if(b[i].close>level){val after=b.subList(i+1,b.size);val touch=after.indexOfFirst{it.low<=level+atr*tolAtr&&it.close>=level-atr*.1};if(touch>=0&&i+1+touch==b.lastIndex)return level to i}
        };return null
    }
    private fun atrBars(b:List<HandoffBar>,p:Int):Double{if(b.size<p+1)return 0.0;return (b.size-p until b.size).map{i->val prev=b[i-1].close;max(b[i].high-b[i].low,max(abs(b[i].high-prev),abs(b[i].low-prev)))}.average()}
    private fun adxBars(b:List<HandoffBar>,p:Int):Double{if(b.size<p*2+2)return 0.0;val tr=mutableListOf<Double>();val pdm=mutableListOf<Double>();val mdm=mutableListOf<Double>();for(i in 1 until b.size){val up=b[i].high-b[i-1].high;val dn=b[i-1].low-b[i].low;pdm+=if(up>dn&&up>0)up else 0.0;mdm+=if(dn>up&&dn>0)dn else 0.0;tr+=max(b[i].high-b[i].low,max(abs(b[i].high-b[i-1].close),abs(b[i].low-b[i-1].close)))};var ts=tr.take(p).sum();var ps=pdm.take(p).sum();var ms=mdm.take(p).sum();val dx=mutableListOf<Double>();fun add(){val a=100*ps/max(ts,1e-12);val z=100*ms/max(ts,1e-12);dx+=100*abs(a-z)/max(a+z,1e-12)};add();for(i in p until tr.size){ts=ts-ts/p+tr[i];ps=ps-ps/p+pdm[i];ms=ms-ms/p+mdm[i];add()};if(dx.isEmpty())return 0.0;var adx=dx.take(p).average();for(v in dx.drop(p))adx=(adx*(p-1)+v)/p;return adx}
    private fun sma(v:List<Double>,p:Int)=if(v.size<p)0.0 else v.takeLast(p).average()
    private fun f(v:Double)="%.4f".format(v)
    private fun Double.bd():BigDecimal=BigDecimal.valueOf(this).setScale(12,RoundingMode.HALF_UP).stripTrailingZeros()
}
