package com.ksp.cryptobot.research

import android.content.Context
import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.*
import com.ksp.cryptobot.exchange.CryptoExchangeClient
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

class ResearchCoordinator(context: Context, private val dao: ResearchDao) {
    private val settingsStore=ResearchSettingsStore(context.applicationContext)
    private val regimeEngine=AdvancedRegimeEngine()
    private val strategyEngine=AdvancedStrategyVoteEngine(regimeEngine)
    private val validation=WalkForwardMonteCarloEngine()
    private val desktopParity=DesktopParityResearchEngine()
    private val desktopSmart=DesktopParitySmartIntelligenceEngine(context.applicationContext, dao)
    private val meta=MetaModelDecisionEngine()
    private val cross=CrossSymbolIntelligenceEngine()
    private val mutation=StrategyMutationLab()
    private val hypotheses=AutonomousHypothesisEngine()
    private val parameterOptimizer=ParameterOptimizerEngine()
    private val external=FuturesAndWalletContextEngine(settingsStore)
    private val professionalExternal=ProfessionalExternalIntelligenceEngine(settingsStore)
    private val sequenceRl=SequenceAndRlResearchEngine(dao)
    private val replay=OrderBookReplayResearchEngine()
    private val professionalRisk=ProfessionalRiskOverlayEngine()
    private val handoff=ResearchHandoffEngine(context.applicationContext, dao, settingsStore)
    private val championChallenger=StrategyChampionChallengerEngine(dao)
    private val championDegradation=ChampionDegradationEngine(dao, validation)
    private val moshi=Moshi.Builder().build()
    private val mapAdapter=moshi.adapter<Map<String,Any?>>(Types.newParameterizedType(Map::class.java,String::class.java,Any::class.java))
    @Volatile private var broadCache=BroadMarketContext()

    suspend fun loadBroadContext(exchange: CryptoExchangeClient, force:Boolean=false):BroadMarketContext {
        val now=System.currentTimeMillis(); if(!force && broadCache.updatedAtEpochMs>0 && now-broadCache.updatedAtEpochMs<120_000)return broadCache
        suspend fun momentum(symbol:String):Double=runCatching{ val c=exchange.getCandles(symbol,Timeframe.H1,48); if(c.size<12)0.0 else pct(c[c.size-12].close.toDouble(),c.last().close.toDouble()) }.getOrDefault(0.0)
        broadCache=BroadMarketContext(momentum("BTCEUR"),momentum("ETHEUR"),now); return broadCache
    }

    suspend fun evaluateDecision(
        settings:BotSettings,
        decision:AiDecision,
        ticker:MarketTicker,
        candlesByTimeframe:Map<Timeframe,List<Candle>>,
        recentTrades:List<TradeEntity>,
        news:List<NewsArticle>,
        exchange:CryptoExchangeClient,
        broad:BroadMarketContext
    ):Pair<AiDecision,ResearchDecisionSummary>{
        if(!settingsStore.enabled()){
            val p=regimeEngine.detect(candlesByTimeframe[Timeframe.M15].orEmpty())
            val empty=ResearchDecisionSummary("NONE",null,0,1.0,1.0,true,false,p,notReadyWf(),notReadyMc(),MetaModelAssessment(true,0,1.0,1.0,0,"Research disabled."),CrossSymbolAssessment(true,0,1.0,broad.broadMomentumPct,"Research disabled."),ContextAssessment(true,0,1.0,"Kraken Futures","DISABLED","Research disabled."),ContextAssessment(true,0,1.0,"Whale Alert","DISABLED","Research disabled."),ContextAssessment(true,0,1.0,"Binance public","DISABLED","Research disabled."),SequenceModelAssessment(0,.5,0,"Research disabled."),RlSandboxAssessment(0,"disabled","HOLD",0.0,"Research disabled."),MutationCandidate("none",0,"Research disabled."),"Research disabled.","Research disabled.")
            return decision to empty
        }
        sequenceRl.trainFromNewOutcomes(recentTrades)
        val baseCandles=candlesByTimeframe[Timeframe.M15].orEmpty().ifEmpty{candlesByTimeframe[Timeframe.H1].orEmpty()}
        val regime=regimeEngine.detect(baseCandles)
        val votes=if(settingsStore.advancedStrategiesEnabled())strategyEngine.evaluate(settings,ticker,candlesByTimeframe,news,regime,settingsStore.professionalStrategiesEnabled(),decision.newsScore) else emptyList()
        val selected=votes.firstOrNull{strategyEngine.isEntryStrategy(it.name)&&it.adjustment>0} ?: votes.firstOrNull{strategyEngine.isEntryStrategy(it.name)};val strategy=selected?.name ?: "AUTO"
        val wf=if(settingsStore.walkForwardEnabled())validation.walkForward(strategy,ticker.symbol,recentTrades,settingsStore.minimumOutcomeSamples()) else notReadyWf()
        val mc=if(settingsStore.monteCarloEnabled())validation.monteCarlo(strategy,ticker.symbol,recentTrades,settingsStore.monteCarloSimulations(),settingsStore.minimumOutcomeSamples()) else notReadyMc()
        val metaResult=meta.evaluate(ticker.symbol,strategy,recentTrades)
        val crossResult=cross.evaluate(ticker.symbol,strategy,broad)
        val mutationResult=mutation.evaluate(strategy,regime,ticker)
        val hypothesis=hypotheses.evaluate(ticker.symbol,recentTrades,baseCandles)
        val futures=external.futures(ticker.symbol)
        val wallet=external.labeledWallet(ticker.symbol)
        val crossMarket=external.crossMarket(ticker.symbol,ticker.lastPrice.toDouble())
        val professionalExternalResult=professionalExternal.evaluate(ticker.symbol,ticker.lastPrice.toDouble())
        val parameterSuggestion=parameterOptimizer.suggestion(strategy,ticker.symbol,recentTrades)
        val needBook=(selected?.score?:0)>=60 || decision.finalAction in setOf(SignalAction.BUY,SignalAction.SMALL_BUY,SignalAction.SELL)
        val book=if(needBook)runCatching{exchange.getOrderBook(ticker.symbol,40)}.getOrNull() else null
        val sequence=if(settingsStore.sequenceModelEnabled())sequenceRl.sequence(ticker,baseCandles,book) else SequenceModelAssessment(0,.5,0,"Sequence model disabled.")
        val rl=if(settingsStore.rlSandboxEnabled())sequenceRl.rl(decision,ticker,regime.regime,strategy) else RlSandboxAssessment(0,"disabled","HOLD",0.0,"RL sandbox disabled.")
        sequenceRl.rememberRlState(ticker.symbol,rl.state)
        val replayResult=replay.evaluate(decision,ticker,book,settings.maxPositionEur.toDouble())
        val desktopWf=desktopParity.walkForward(ticker.symbol,recentTrades)
        val desktopMc=desktopParity.monteCarlo(settings,recentTrades)
        val desktopMtf=desktopParity.multiHorizonFusion(baseCandles,decision)
        val desktopSmartEnabled=settingsStore.desktopParityIntelligenceEnabled()
        val desktopSmartResult=if(desktopSmartEnabled) desktopSmart.evaluate(settings,decision,ticker,baseCandles,book,regime,strategy,recentTrades,crossMarket) else DesktopSmartAssessment(0,1.0,1.0,false,.5,"disabled",0.0,"Desktop parity smart intelligence disabled.")
        val professionalRiskResult=professionalRisk.evaluate(strategy,ticker,baseCandles,recentTrades,professionalExternalResult.compositeAdjustment)
        val handoffResult=handoff.evaluate(settings,decision,ticker,candlesByTimeframe,recentTrades,exchange,broad)
        val handoffEntry=handoffResult.selectedEntry
        val governedStrategy=handoffEntry?.definition?.id ?: strategy
        val governanceWf=if(governedStrategy.equals(strategy,true)) wf
            else validation.walkForward(governedStrategy,ticker.symbol,recentTrades,maxOf(10,settingsStore.minimumOutcomeSamples()))
        val governanceMc=if(governedStrategy.equals(strategy,true)) mc
            else validation.monteCarlo(governedStrategy,ticker.symbol,recentTrades,settingsStore.monteCarloSimulations(),maxOf(10,settingsStore.minimumOutcomeSamples()))
        val strategyGovernance=championChallenger.evaluateAndMaybePromote(
            settings=settings,
            challengerStrategy=governedStrategy,
            symbol=ticker.symbol,
            trades=recentTrades,
            walkForward=governanceWf,
            monteCarlo=governanceMc
        )
        val championHealth=championDegradation.evaluateAndApply(settings,ticker.symbol,recentTrades)
        val governedLiveAuthorized=strategyGovernance.productionAuthorized && championHealth.liveEntryAuthorized &&
            championHealth.championAfter?.equals(governedStrategy,true)==true
        val handoffModeEligible=handoffEntry?.let { if(settings.mode==BotMode.PAPER) it.allowedForPaperExecution else it.allowedForLiveEntry } ?: false
        val handoffCanExecute=handoffModeEligible && (settings.mode==BotMode.PAPER || governedLiveAuthorized)
        val handoffCanStage=handoffEntry?.candidate?.let { it.triggerDetected || it.entryPlan.resting } ?: false
        val handoffProtective=handoffResult.protectiveAction

        // Exact desktop-parity votes are always evaluated at their native weight. Professional variants
        // start in shadow/evidence mode: positive influence is capped until the symbol has enough realized
        // outcomes, while negative/risk evidence is applied immediately. This avoids promoting a fashionable
        // strategy simply because it is widely used; it has to earn influence in this account's data.
        val parityVotes=votes.filterNot{it.name.startsWith("PRO_") || it.name.startsWith("FILTER_")}
        val professionalVotes=votes.filter{it.name.startsWith("PRO_") || it.name.startsWith("FILTER_")}
        val parityVoteAdj=strategyEngine.ensembleAdjustment(parityVotes)
        val professionalVoteRaw=strategyEngine.ensembleAdjustment(professionalVotes)
        val professionalOutcomeSamples=recentTrades.count{it.symbol.equals(ticker.symbol,true)&&(it.realizedPnlEur.toDoubleOrNull()?:0.0)!=0.0}
        val professionalMature=professionalOutcomeSamples>=maxOf(12,settingsStore.minimumOutcomeSamples()) && desktopWf.first>=-2 && desktopMc.first>=-3
        val professionalVoteAdj=if(professionalMature) professionalVoteRaw else if(professionalVoteRaw>0) minOf(1,professionalVoteRaw) else professionalVoteRaw
        val voteAdj=(parityVoteAdj+professionalVoteAdj).coerceIn(-25,25)
        val wfAdj=if(!wf.ready)0 else if(wf.score>=65)2 else if(wf.score<40)-3 else 0
        val mcAdj=if(!mc.ready)0 else if(mc.score>=65)2 else if(mc.score<35)-3 else 0
        // crossMarket is part of the exact desktop smart ensemble when that engine is enabled; do not count it twice.
        val standaloneCrossMarketAdj=if(desktopSmartEnabled)0 else crossMarket.adjustment
        val raw=voteAdj+wfAdj+mcAdj+desktopWf.first+desktopMc.first+desktopMtf.first+desktopSmartResult.adjustment+metaResult.adjustment+crossResult.adjustment+mutationResult.adjustment+hypothesis.first+futures.adjustment+wallet.adjustment+standaloneCrossMarketAdj+professionalExternalResult.compositeAdjustment+sequence.adjustment+rl.adjustment+replayResult.first+handoffResult.aggregateAdjustment
        val total=raw.coerceIn(-settingsStore.maxNegativeAdjustment(),settingsStore.maxPositiveAdjustment())
        val allowed=metaResult.allowed && crossResult.allowed && !desktopSmartResult.blocked && !handoffResult.hardEntryBlock
        var score=(decision.finalScore+total).coerceIn(0,100)
        var action=decision.finalAction;var promoted=false
        if(!allowed && action in setOf(SignalAction.BUY,SignalAction.SMALL_BUY)){action=SignalAction.WAIT;score=minOf(score,54)}
        else if(action in setOf(SignalAction.BUY,SignalAction.SMALL_BUY) && score<68)action=SignalAction.WAIT
        else if(action==SignalAction.BUY && score<78)action=SignalAction.SMALL_BUY
        else if(action in setOf(SignalAction.WAIT,SignalAction.WATCH) && selected?.action in setOf(SignalAction.BUY,SignalAction.SMALL_BUY) && (selected?.score?:0)>=80 && total>=4){
            val canPromote=if(settings.mode==BotMode.PAPER)settingsStore.researchPromotionInPaper() else settingsStore.researchPromotionInLive()
            if(canPromote && regime.risk!="RISK_OFF" && (settings.mode==BotMode.PAPER || governedLiveAuthorized)){action=SignalAction.SMALL_BUY;score=maxOf(score,68);promoted=true}
        }
        if (handoffProtective != null) {
            action = SignalAction.SELL
            score = maxOf(score, 70)
            promoted = false
        } else if (handoffCanExecute && handoffCanStage && action in setOf(SignalAction.WAIT, SignalAction.WATCH)) {
            action = SignalAction.SMALL_BUY
            score = maxOf(score, 68)
            promoted = true
        }
        val isExit = decision.finalAction == SignalAction.SELL
        if (isExit) {
            action = SignalAction.SELL
            score = decision.finalScore
            promoted = false
        }
        val finalAllowed = when {
            isExit -> decision.allowedToTrade
            handoffProtective != null -> true
            handoffCanExecute && promoted -> allowed
            else -> allowed && action in setOf(SignalAction.BUY,SignalAction.SMALL_BUY)
        }
        val confidenceMult=(metaResult.confidenceMultiplier*crossResult.multiplier*futures.multiplier*wallet.multiplier*crossMarket.multiplier).coerceIn(.50,1.10)
        val externalRiskMultiplier=when{professionalExternalResult.compositeAdjustment<=-8->.80;professionalExternalResult.compositeAdjustment<=-4->.90;else->1.0}
        val championHealthSize=if(settings.mode==BotMode.PAPER)1.0 else if(strategyGovernance.productionAuthorized) championHealth.liveSizeMultiplier.toDouble() else 1.0
        val sizeMult=(metaResult.sizeMultiplier*crossResult.multiplier*futures.multiplier*wallet.multiplier*crossMarket.multiplier*externalRiskMultiplier*desktopSmartResult.appliedSizeMultiplier*professionalRiskResult.sizeMultiplier*handoffResult.sizeMultiplier*championHealthSize).coerceIn(.05,1.00)
        val governanceLine="M9 strategy=$governedStrategy action=${strategyGovernance.action} championBefore=${strategyGovernance.championBefore ?: "none"} championAfter=${strategyGovernance.championAfter ?: "none"} liveAuthorized=${strategyGovernance.productionAuthorized} exact=${strategyGovernance.challenger.exactSamples} paper=${strategyGovernance.challenger.paperSamples} oos=${strategyGovernance.challenger.testSamples} regimes=${strategyGovernance.regimeCount} lower95=${strategyGovernance.challenger.lower95Return} diffLower95=${strategyGovernance.difference?.lower95Difference ?: BigDecimal.ZERO}; M10 health=${championHealth.state} healthChampion=${championHealth.championAfter ?: "none"} liveAuthorized=$governedLiveAuthorized rollingN=${championHealth.rolling.samples} rollingMean=${championHealth.rolling.meanReturn} rollingUpper95=${championHealth.rolling.upper95Return} rollback=${championHealth.rollbackCandidate ?: "none"}"
        val handoffLine="handoffSelected=${handoffEntry?.definition?.id ?: "none"}/${handoffEntry?.status ?: "none"}; handoffEligible=$handoffCanExecute; handoffCanStage=$handoffCanStage; handoffProtective=${handoffProtective?.definition?.id ?: "none"}/${handoffProtective?.candidate?.sideIntent ?: "none"}; handoffAdj=${handoffResult.aggregateAdjustment}; handoffSize=${"%.3f".format(handoffResult.sizeMultiplier)}; handoffBlock=${handoffResult.hardEntryBlock}"
        val explanation="Research strategy=$strategy score=${selected?.score?:0}; strategyEnsembleAdj=$voteAdj (parity=$parityVoteAdj, professional=$professionalVoteAdj/raw=$professionalVoteRaw, proMature=$professionalMature, samples=$professionalOutcomeSamples); regime=${regime.regime}; totalAdj=$total; WF=${"%.1f".format(wf.score)}; MC=${"%.1f".format(mc.score)}; desktopWF=${desktopWf.first}; desktopMC=${desktopMc.first}; desktopMTF=${desktopMtf.first}; desktopSmart=${desktopSmartResult.adjustment}/size=${"%.2f".format(desktopSmartResult.appliedSizeMultiplier)}; meta=${metaResult.adjustment}; cross=${crossResult.adjustment}; seq=${sequence.adjustment}; RL=${rl.adjustment}; replay=${replayResult.first}; mutation=${mutationResult.variant}/${mutationResult.adjustment}; hypothesis=${hypothesis.first}; futures=${futures.adjustment}; wallet=${wallet.adjustment}; crossMarket=${crossMarket.adjustment}; professionalExternal=${professionalExternalResult.compositeAdjustment}; proRisk=${"%.2f".format(professionalRiskResult.sizeMultiplier)}x/ATR=${"%.2f".format(professionalRiskResult.atrPercent)}%; $governanceLine; $handoffLine; promoted=$promoted; exitPreserved=$isExit. $parameterSuggestion | ${desktopSmartResult.report} | ${professionalRiskResult.reason} | ${professionalExternalResult.reason} | ${strategyGovernance.reason} | ${handoffResult.reason}"
        val out=decision.copy(finalAction=action,finalScore=score,confidencePercent=if(isExit) decision.confidencePercent else (decision.confidencePercent*confidenceMult).toInt().coerceIn(0,100),allowedToTrade=finalAllowed,explanation=decision.explanation+" | "+explanation)
        val summary=ResearchDecisionSummary(strategy,selected,total,confidenceMult,sizeMult,allowed,promoted,regime,wf,mc,metaResult,crossResult,futures,wallet,crossMarket,sequence,rl,mutationResult,parameterSuggestion,explanation)
        persist(settings,out,summary,replayResult.second,hypothesis.second)
        updateProfile(strategy,ticker.symbol,recentTrades,wf,mc,mutationResult)
        return out to summary
    }

    suspend fun diagnostics(limit:Int=100):List<ResearchEventEntity> = dao.recentEvents(limit)
    suspend fun profiles():List<ResearchStrategyProfileEntity> = dao.profiles()
    suspend fun strategyChampion(symbol:String):String? = championChallenger.currentChampion(symbol)
    suspend fun strategyPromotions(limit:Int=100):List<ResearchEventEntity> = championChallenger.recentPromotions(limit)
    suspend fun championHealth(settings:BotSettings,symbol:String,trades:List<TradeEntity>):ChampionHealthDecision = championDegradation.inspect(settings,symbol,trades)
    fun researchSettings():ResearchSettingsStore=settingsStore
    fun handoffAssetAudit():Map<String,String> = handoff.assetAudit()

    private suspend fun persist(settings:BotSettings,decision:AiDecision,s:ResearchDecisionSummary,replayReason:String,hypothesis:String){
        fun json(m:Map<String,Any?>)=mapAdapter.toJson(m)
        val now=System.currentTimeMillis(); val mode=settings.mode.name
        dao.insertEvent(ResearchEventEntity(timestampEpochMs=now,eventType="research_evaluation",symbol=decision.symbol,strategy=s.selectedStrategy,regime=s.regime.regime,mode=mode,variant=s.mutation.variant,adjustment=s.scoreAdjustment,confidence=s.confidenceMultiplier,score=s.selectedVote?.score?.toDouble()?:0.0,sampleCount=maxOf(s.walkForward.sampleCount,s.monteCarlo.sampleCount),status=if(s.allowed)"OK" else "BLOCK",reason=s.explanation,payloadJson=json(mapOf("promoted" to s.promotedFromResearch,"size_multiplier" to s.sizeMultiplier,"replay" to replayReason))))
        dao.insertEvent(ResearchEventEntity(timestampEpochMs=now,eventType="strategy_variant",symbol=decision.symbol,strategy=s.selectedStrategy,regime=s.regime.regime,mode=mode,variant=s.mutation.variant,adjustment=s.mutation.adjustment,score=s.selectedVote?.score?.toDouble()?:0.0,reason=s.mutation.reason))
        if(s.walkForward.ready)dao.insertEvent(ResearchEventEntity(timestampEpochMs=now,eventType="walk_forward",symbol=decision.symbol,strategy=s.selectedStrategy,regime=s.regime.regime,mode=mode,score=s.walkForward.score,sampleCount=s.walkForward.sampleCount,trainWindow=s.walkForward.trainWindow,testWindow=s.walkForward.testWindow,status=s.walkForward.status,reason=s.walkForward.reason))
        dao.insertEvent(ResearchEventEntity(timestampEpochMs=now,eventType="onchain_context",symbol=decision.symbol,strategy=s.selectedStrategy,regime=s.regime.regime,mode=mode,provider=s.futuresContext.provider,status=s.futuresContext.status,adjustment=s.futuresContext.adjustment,reason=s.futuresContext.reason))
        dao.insertEvent(ResearchEventEntity(timestampEpochMs=now,eventType="onchain_context",symbol=decision.symbol,strategy=s.selectedStrategy,regime=s.regime.regime,mode=mode,provider=s.labeledWallet.provider,status=s.labeledWallet.status,adjustment=s.labeledWallet.adjustment,reason=s.labeledWallet.reason))
        dao.insertEvent(ResearchEventEntity(timestampEpochMs=now,eventType="cross_market_context",symbol=decision.symbol,strategy=s.selectedStrategy,regime=s.regime.regime,mode=mode,provider=s.crossMarket.provider,status=s.crossMarket.status,adjustment=s.crossMarket.adjustment,reason=s.crossMarket.reason))
        dao.insertEvent(ResearchEventEntity(timestampEpochMs=now,eventType="parameter_suggestion",symbol=decision.symbol,strategy=s.selectedStrategy,regime=s.regime.regime,mode=mode,reason=s.parameterSuggestion))
        dao.insertEvent(ResearchEventEntity(timestampEpochMs=now,eventType="sequence_model",symbol=decision.symbol,strategy=s.selectedStrategy,regime=s.regime.regime,mode=mode,adjustment=s.sequence.adjustment,confidence=s.sequence.probabilityProfit,sampleCount=s.sequence.samples,reason=s.sequence.reason))
        dao.insertEvent(ResearchEventEntity(timestampEpochMs=now,eventType="rl_sandbox",symbol=decision.symbol,strategy=s.selectedStrategy,regime=s.regime.regime,mode=mode,adjustment=s.rlSandbox.adjustment,confidence=s.rlSandbox.confidence,variant=s.rlSandbox.bestAction,reason=s.rlSandbox.reason))
        dao.insertEvent(ResearchEventEntity(timestampEpochMs=now,eventType="hypothesis",symbol=decision.symbol,strategy=s.selectedStrategy,regime=s.regime.regime,mode=mode,reason=hypothesis))
    }

    private suspend fun updateProfile(strategy:String,symbol:String,trades:List<TradeEntity>,wf:WalkForwardAssessment,mc:MonteCarloAssessment,mut:MutationCandidate){
        val rows=trades.filter{it.symbol.equals(symbol,true)&&(it.realizedPnlEur.toDoubleOrNull()?:0.0)!=0.0}.takeLast(200);val wins=rows.count{(it.realizedPnlEur.toDoubleOrNull()?:0.0)>0};val losses=rows.count{(it.realizedPnlEur.toDoubleOrNull()?:0.0)<0};val gp=rows.sumOf{maxOf(0.0,it.realizedPnlEur.toDoubleOrNull()?:0.0)};val gl=abs(rows.sumOf{minOf(0.0,it.realizedPnlEur.toDoubleOrNull()?:0.0)});val wr=if(rows.isNotEmpty())wins*100.0/rows.size else 0.0;val pf=if(gl>0)gp/gl else if(gp>0)10.0 else 0.0;val pnl=rows.sumOf{it.realizedPnlEur.toDoubleOrNull()?:0.0};val life=when{rows.size<5->"OBSERVE";rows.size<25&&pnl>0&&wr>=50->"ACTIVE_TRIAL";rows.size<25->"WATCH";pnl>0&&wr>=55&&pf>=1.2->"PREFERRED";pnl<0&&wr<42->"PROBATION";else->"ACTIVE"};dao.upsertProfile(ResearchStrategyProfileEntity(strategyKey="${strategy}|${symbol.uppercase()}",updatedAtEpochMs=System.currentTimeMillis(),sampleSize=rows.size,wins=wins,losses=losses,totalPnlEur=pnl,winRatePercent=wr,profitFactor=pf,walkForwardScore=wf.score,monteCarloScore=mc.score,mutationScore=mut.adjustment.toDouble(),lifecycleState=life,reason="symbol=$symbol; WF=${"%.1f".format(wf.score)}; MC=${"%.1f".format(mc.score)}; mutation=${mut.variant}"))
    }
    private fun pct(a:Double,b:Double)=if(a==0.0)0.0 else (b-a)/a*100.0
    private fun notReadyWf()=WalkForwardAssessment(false,"DISABLED",0.0,0,0,0,"n/a","n/a","Walk-forward unavailable/disabled.")
    private fun notReadyMc()=MonteCarloAssessment(false,0.0,0.0,0.0,0.0,0.0,0.0,0,0,"Monte Carlo unavailable/disabled.")
}
