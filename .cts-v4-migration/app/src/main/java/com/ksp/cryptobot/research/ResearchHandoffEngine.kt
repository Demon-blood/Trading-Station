package com.ksp.cryptobot.research

import android.content.Context
import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.*
import com.ksp.cryptobot.exchange.CryptoExchangeClient
import com.ksp.cryptobot.exchange.TradingFeeSchedule
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Executes the entire research handoff as a truth-preserving research/decision layer.
 * All 31 catalog entries are evaluated every scan. Positive live entries remain source-truth gated;
 * protective exits/reductions and negative risk evidence can act automatically because they reduce risk.
 */
class ResearchHandoffEngine(
    context: Context,
    private val dao: ResearchDao,
    private val settingsStore: ResearchSettingsStore
) {
    private val appContext = context.applicationContext
    private val appDao = AppDatabase.get(appContext).dao()
    private val catalog = ResearchHandoffCatalog(appContext)
    private val structureEngine = ResearchHandoffStructureEngine()
    private val detector = ResearchHandoffStrategyEngine(structureEngine)
    private val costRisk = ResearchHandoffCostRiskEngine()
    private val validation = WalkForwardMonteCarloEngine()
    private val external = ResearchHandoffExternalContextEngine(dao)
    private val historyCache = ConcurrentHashMap<String, HistoryCache>()
    @Volatile private var assetAuditDone = false

    private data class HistoryCache(val h1: List<Candle>, val h4: List<Candle>, val loadedAt: Long)
    private data class EmpiricalPromotion(
        val passed: Boolean,
        val exactOutcomes: Int,
        val profitFactor: Double,
        val netPnl: Double,
        val stressedNetPnl: Double,
        val walkForward: WalkForwardAssessment?,
        val monteCarlo: MonteCarloAssessment?,
        val reason: String
    )

    suspend fun evaluate(
        settings: BotSettings,
        decision: AiDecision,
        ticker: MarketTicker,
        candlesByTimeframe: Map<Timeframe, List<Candle>>,
        recentTrades: List<TradeEntity>,
        exchange: CryptoExchangeClient,
        broad: BroadMarketContext
    ): HandoffResearchEvaluation {
        if (!settingsStore.handoffEngineEnabled()) {
            ResearchExecutionRuntime.clear(ticker.symbol)
            return HandoffResearchEvaluation(emptyList(),null,null,0,1.0,false,"Research handoff truth engine disabled.")
        }
        catalog.verifyIntegrityOrThrow()
        auditAssetsOnce()
        val augmented = augmentHistory(ticker.symbol, candlesByTimeframe, exchange)
        val structure = structureEngine.build(ticker, augmented)
        val openPosition = appDao.positionForSymbol(ticker.symbol)?.takeIf { it.status.equals("OPEN",true) }
        val allPositions = appDao.openPositionsSnapshot()
        val dominance = external.btcDominance()
        val ctx = HandoffDetectorContext(ticker,structure,broad,recentTrades,openPosition,dominance.valuePct,dominance.changePct)
        val symbolInfo = runCatching { exchange.validateSymbol(ticker.symbol) }.getOrNull()
        val quoteAsset = symbolInfo?.quoteAsset?.uppercase()?.ifBlank { "EUR" } ?: "EUR"
        val balances = runCatching { exchange.getAvailableBalances() }.getOrDefault(emptyMap())
        val available = balanceForQuote(balances,quoteAsset)
        val equityEstimate = conservativeEquityEstimate(available,allPositions,ticker)
        val book = runCatching { exchange.getOrderBook(ticker.symbol, 60) }.getOrNull()
        val feeSchedule = runCatching { exchange.getTradingFeeSchedule(ticker.symbol) }.getOrNull()
        val stale = researchFreezeStale()
        val definitions = catalog.strategies()
        val rows = definitions.map { def ->
            val detection = runCatching { detector.detect(def,ctx) }.getOrElse { error ->
                HandoffDetection(null,0,.75,"DETECTOR_ERROR","${def.id} failed safely: ${error.message}")
            }
            evaluateDetection(def,detection,settings,ticker,book,recentTrades,feeSchedule,symbolInfo,available,equityEstimate,allPositions,stale)
        }
        check(rows.size == 31) { "Truth engine must evaluate all 31 handoff strategies; evaluated ${rows.size}." }

        val protective = rows.filter { it.candidate?.sideIntent in setOf(HandoffSideIntent.EXIT,HandoffSideIntent.REDUCE) }
            .filter { settings.mode == BotMode.PAPER || it.allowedForProtectiveLiveAction }
            .maxByOrNull { if (it.candidate?.sideIntent == HandoffSideIntent.EXIT) 100 else 50 + abs(it.adjustment) }

        val entryRows = rows.filter { it.candidate?.sideIntent == HandoffSideIntent.LONG_ENTRY && it.candidate.setupDetected }
        val selected = entryRows.maxByOrNull { rankEntry(it, settings.mode) }

        val hardAvoid = rows.any { it.candidate?.sideIntent == HandoffSideIntent.AVOID && it.adjustment <= -4 }
        val integrityBlock = !structure.dataIntegrityOk
        val hardBlock = hardAvoid || integrityBlock
        val adj = rows.filter { it.candidate?.sideIntent in setOf(HandoffSideIntent.FILTER,HandoffSideIntent.CONTEXT,HandoffSideIntent.AVOID) }
            .sumOf { it.adjustment }.coerceIn(-10,4)
        var size = rows.filter { it.candidate?.sideIntent in setOf(HandoffSideIntent.FILTER,HandoffSideIntent.CONTEXT,HandoffSideIntent.AVOID) }
            .fold(1.0) { acc,row -> acc * row.sizeMultiplier.coerceIn(.1,1.0) }.coerceIn(.15,1.0)
        if (hardBlock) size = minOf(size,.25)
        selected?.risk?.takeIf { it.allowed }?.let { risk ->
            val existingCap = settings.effectiveMaxPositionFor(ticker.symbol).toDouble().coerceAtLeast(.01)
            size = minOf(size,(risk.maxNotionalQuote.toDouble()/existingCap).coerceIn(.05,1.0))
        }
        val reason = buildString {
            append("handoff evaluated=31; integrity=${structure.dataIntegrityOk}; freezeStale=$stale; dominance=${dominance.status}; ")
            append("selected=${selected?.definition?.id ?: "none"}/${selected?.status ?: "none"}; protective=${protective?.definition?.id ?: "none"}; ")
            append("contextAdj=$adj size=${"%.3f".format(size)} hardBlock=$hardBlock. ")
            if (!structure.dataIntegrityOk) append(structure.integrityReason)
        }
        val result = HandoffResearchEvaluation(rows,selected,protective,adj,size,hardBlock,reason)
        publishRuntime(settings,result,ticker,feeSchedule)
        persistAudit(settings,result,structure,dominance)
        return result
    }

    fun assetAudit(): Map<String,String> = catalog.assetAudit()

    private fun evaluateDetection(
        def:HandoffStrategyDefinition,
        detection:HandoffDetection,
        settings:BotSettings,
        ticker:MarketTicker,
        book:OrderBookSnapshot?,
        recentTrades:List<TradeEntity>,
        feeSchedule:TradingFeeSchedule?,
        symbolInfo:ExchangeSymbolInfo?,
        available:BigDecimal,
        equity:BigDecimal,
        positions:List<PositionEntity>,
        researchStale:Boolean
    ):HandoffCandidateEvaluation {
        val candidate=detection.candidate
        if(candidate==null)return HandoffCandidateEvaluation(def,null,detection.status,detection.adjustment,detection.sizeMultiplier,null,null,false,false,false,detection.reason)
        if(candidate.sideIntent==HandoffSideIntent.BLOCKED_SOURCE_UNKNOWN)return HandoffCandidateEvaluation(def,candidate,"BLOCKED_SOURCE_UNKNOWN",0,1.0,null,null,false,false,false,"${def.name}: proprietary/under-specified; exact implementation prohibited by handoff.")
        if(candidate.sideIntent in setOf(HandoffSideIntent.EXIT,HandoffSideIntent.REDUCE,HandoffSideIntent.AVOID,HandoffSideIntent.FILTER,HandoffSideIntent.CONTEXT,HandoffSideIntent.RESEARCH)){
            val protective = candidate.sideIntent in setOf(HandoffSideIntent.EXIT,HandoffSideIntent.REDUCE) && settingsStore.handoffProtectiveLiveActionsEnabled()
            return HandoffCandidateEvaluation(def,candidate,detection.status,detection.adjustment,detection.sizeMultiplier,null,null,false,false,protective,detection.reason)
        }
        val probe=settings.effectiveMaxPositionFor(ticker.symbol).min(available.max(BigDecimal("5.00")))
        val cost=costRisk.costGate(candidate,ticker,book,recentTrades,feeSchedule,symbolInfo,probe,settingsStore.handoffCostSafetyMarginPct())
        var risk=costRisk.riskGate(candidate,ticker,symbolInfo,recentTrades,available,equity,settingsStore.handoffRiskPerTradeFraction(),book,feeSchedule)
        if(risk.allowed) risk=applyClusterRisk(candidate,risk,positions,equity,settingsStore.handoffCorrelatedRiskCapFraction())
        // An order is executable only when its source trigger exists, unless the source method
        // explicitly calls for a genuinely resting order that must be staged before price reaches it.
        // This is what preserves closed-candle/no-lookahead semantics without breaking real stop/limit entries.
        val canStageBeforeTrigger = candidate.entryPlan.resting && candidate.entryPlan.preferredOrderType in setOf(OrderType.LIMIT, OrderType.STOP_LOSS, OrderType.TAKE_PROFIT)
        val sourceActionable = candidate.triggerDetected || canStageBeforeTrigger
        val paperClassAllowed = when(def.fidelity.uppercase()){
            "A","B" -> true
            "C" -> settingsStore.handoffFormalizedPaperExecutionEnabled()
            else -> false
        }
        val paper = settingsStore.handoffAutoPaperExecutionEnabled() && paperClassAllowed && sourceActionable && cost.allowed && risk.allowed
        val empirical = empiricalPromotion(def,candidate,recentTrades)
        val live = settingsStore.handoffSourceTruthLiveEntriesEnabled() && !researchStale && sourceActionable && def.positiveLiveTruthSatisfied && empirical.passed && cost.allowed && risk.allowed && candidate.executionEligibility==HandoffExecutionEligibility.PAPER_AND_TRUTH_GATED_LIVE
        val gateStatus=when{
            !cost.allowed->"BLOCK_COST"
            !risk.allowed->"BLOCK_RISK"
            researchStale&&settings.mode!=BotMode.PAPER->"BLOCK_STALE_SOURCE_REVALIDATION"
            settings.mode!=BotMode.PAPER&&!def.positiveLiveTruthSatisfied->"BLOCK_SOURCE_TRUTH"
            settings.mode!=BotMode.PAPER&&def.positiveLiveTruthSatisfied&&!empirical.passed->"BLOCK_EMPIRICAL_PROMOTION"
            !sourceActionable->"WAIT_SOURCE_TRIGGER"
            paper||live->"EXECUTION_ELIGIBLE"
            else->detection.status
        }
        val reason="${detection.reason} | cost=${cost.reason} | risk=${risk.reason} | empirical=${empirical.reason} | fidelity=${def.fidelity}/${def.implementationClass} liveTruth=${def.liveTruthGate} usageVerified=${def.usageContextSourceVerified} noTradeVerified=${def.noTradeConditionsSourceVerified}."
        return HandoffCandidateEvaluation(def,candidate,gateStatus,detection.adjustment,detection.sizeMultiplier,cost,risk,paper,live,false,reason)
    }

    private fun empiricalPromotion(def:HandoffStrategyDefinition,candidate:HandoffTradeCandidate,recentTrades:List<TradeEntity>):EmpiricalPromotion{
        val exact = recentTrades
            .filter { it.symbol.equals(candidate.symbol,true) }
            .filter { (it.realizedPnlEur.toDoubleOrNull() ?: 0.0) != 0.0 }
            .filter { it.aiReason.contains(def.id,true) }
            .sortedBy { it.timestampEpochMs }
            .takeLast(200)
        val minSamples = settingsStore.minimumOutcomeSamples()
        if (exact.size < minSamples) {
            return EmpiricalPromotion(false,exact.size,0.0,exact.sumOf { it.realizedPnlEur.toDoubleOrNull() ?: 0.0 },0.0,null,null,
                "EMPIRICAL_WARMUP exactOutcomes=${exact.size}/$minSamples for ${def.id}; positive LIVE promotion prohibited until strategy-specific realized evidence exists.")
        }
        val wf = validation.walkForward(def.id,candidate.symbol,exact,minSamples)
        val mc = validation.monteCarlo(def.id,candidate.symbol,exact,settingsStore.monteCarloSimulations(),minSamples)
        val pnl = exact.map { it.realizedPnlEur.toDoubleOrNull() ?: 0.0 }
        val grossWins = pnl.filter { it > 0.0 }.sum()
        val grossLosses = -pnl.filter { it < 0.0 }.sum()
        val pf = when { grossLosses > 0.0 -> grossWins/grossLosses; grossWins > 0.0 -> 99.0; else -> 0.0 }
        val net = pnl.sum()
        // Actual PAPER/LIVE outcomes already include observed fees/slippage. Add a conservative
        // product-policy stress: 25% of recorded exit fees plus 10 bps of realized notional per outcome.
        val extraStress = exact.sumOf { trade ->
            val fee = kotlin.math.abs(trade.feeEur.toDoubleOrNull() ?: 0.0) * 0.25
            val qty = kotlin.math.abs(trade.quantity.toDoubleOrNull() ?: 0.0)
            val px = kotlin.math.abs(trade.priceEur.toDoubleOrNull() ?: 0.0)
            fee + qty*px*0.0010
        }
        val stressed = net-extraStress
        val passed = wf.ready && wf.status=="PASS" && wf.score>=55.0 &&
            mc.ready && mc.score>=55.0 && mc.probabilityPositive>=0.55 &&
            pf>=1.10 && net>0.0 && stressed>0.0
        val reason = "EMPIRICAL_${if(passed)"PASS" else "BLOCK"} exact=${exact.size}, pf=${"%.2f".format(pf)}, net=${"%.2f".format(net)}, stressedNet=${"%.2f".format(stressed)}, wf=${wf.status}/${"%.1f".format(wf.score)}, mcScore=${"%.1f".format(mc.score)}, pPositive=${"%.1f".format(mc.probabilityPositive*100)}%."
        return EmpiricalPromotion(passed,exact.size,pf,net,stressed,wf,mc,reason)
    }

    private fun applyClusterRisk(c:HandoffTradeCandidate,r:HandoffRiskAssessment,positions:List<PositionEntity>,equity:BigDecimal,capFraction:BigDecimal):HandoffRiskAssessment{
        val cluster=cluster(c.symbol);var existing=BigDecimal.ZERO
        for(p in positions.filter{it.status.equals("OPEN",true)&&cluster(it.symbol)==cluster}){
            val q=p.quantity.toBigDecimalOrNull()?.abs()?:continue;val entry=p.entryPriceEur.toBigDecimalOrNull()?.abs()?:continue;val stop=p.stopPriceEur.toBigDecimalOrNull()?.abs()?:BigDecimal.ZERO
            val per=if(stop>BigDecimal.ZERO&&stop<entry)entry-stop else entry.multiply(BigDecimal("0.05"))
            existing+=q.multiply(per)
        }
        val cap=equity.multiply(capFraction);val total=existing+r.actualModeledLossQuote
        return if(cap>BigDecimal.ZERO&&total>cap)r.copy(allowed=false,reason=r.reason+" | BLOCK_CORRELATED_CAMPAIGN_RISK cluster=$cluster existing=${existing.s4()} candidate=${r.actualModeledLossQuote.s4()} cap=${cap.s4()} (${capFraction.multiply(BigDecimal("100")).s2()}% product-policy preset).") else r.copy(reason=r.reason+" | clusterRisk=$cluster total=${total.s4()}/${cap.s4()}.")
    }

    private fun publishRuntime(settings:BotSettings,e:HandoffResearchEvaluation,ticker:MarketTicker,feeSchedule:TradingFeeSchedule?){
        val selected=e.selectedEntry;val modeEligible=selected?.let{if(settings.mode==BotMode.PAPER)it.allowedForPaperExecution else it.allowedForLiveEntry}?:false
        val c=selected?.candidate
        // Preserve the source order type in PAPER and LIVE. The paper exchange now holds real
        // pending LIMIT/STOP/TAKE_PROFIT orders instead of converting conditionals to MARKET.
        val preferred=if(modeEligible) c?.entryPlan?.preferredOrderType else null
        val maxNotional=if(modeEligible)selected?.risk?.maxNotionalQuote else null
        ResearchExecutionRuntime.publish(ResearchExecutionDirective(
            symbol=ticker.symbol,
            allowedEntry=!e.hardEntryBlock,
            sideIntent=e.protectiveAction?.candidate?.sideIntent?:if(modeEligible) c?.sideIntent?:HandoffSideIntent.FILTER else HandoffSideIntent.FILTER,
            strategyId=c?.strategyId?:"HANDOFF_FILTERS",
            strategyName=c?.strategyName?:"Research handoff filters",
            fidelity=c?.fidelity?:"N/A",
            implementationClass=c?.implementationClass?.name?:"FILTERS",
            liveTruthGate=c?.liveTruthGate?:"N/A",
            sizeMultiplier=e.sizeMultiplier.coerceIn(.15,1.0),
            maxNotionalQuote=maxNotional,
            preferredOrderType=preferred,
            preferredLimitOrTriggerPrice=if(modeEligible && preferred!=OrderType.MARKET) c?.entryPlan?.triggerPrice?:c?.entryPlan?.intendedPrice else null,
            postOnlyPreferred=modeEligible && c?.entryPlan?.postOnlyPreferred==true && preferred==OrderType.LIMIT,
            makerFeeRate=feeSchedule?.makerRate,
            takerFeeRate=feeSchedule?.takerRate,
            feeSource=feeSchedule?.source ?: "fallback/observed",
            stopPrice=if(modeEligible)c?.invalidation?.stopPrice else null,
            targets=if(modeEligible)c?.targets?.map{it.price}.orEmpty() else emptyList(),
            costGatePassed=if(modeEligible) selected?.cost?.allowed?:true else true,
            riskGatePassed=if(modeEligible) selected?.risk?.allowed?:true else true,
            reason=e.reason+" | sourceEntryModeEligible=$modeEligible; M4 may only shrink to this directive, never enlarge."
        ))
    }

    private suspend fun persistAudit(settings:BotSettings,e:HandoffResearchEvaluation,s:HandoffMarketStructure,dom:ResearchHandoffExternalContextEngine.DominanceContext){
        val arr=JSONArray()
        e.evaluations.forEach{row->
            val c=row.candidate
            arr.put(JSONObject().apply{
                put("strategy_id",row.definition.id);put("creator",row.definition.trader);put("name",row.definition.name);put("fidelity",row.definition.fidelity);put("implementation_class",row.definition.implementationClass.name)
                put("status",row.status);put("adjustment",row.adjustment);put("size_multiplier",row.sizeMultiplier);put("side_intent",c?.sideIntent?.name?:"NONE")
                put("setup_detected",c?.setupDetected?:false);put("trigger_detected",c?.triggerDetected?:false);put("entry",c?.entryPlan?.intendedPrice?.toPlainString());put("trigger",c?.entryPlan?.triggerPrice?.toPlainString());put("stop",c?.invalidation?.stopPrice?.toPlainString())
                put("targets",JSONArray(c?.targets?.map{it.price.toPlainString()}.orEmpty()));put("paper_eligible",row.allowedForPaperExecution);put("live_entry_eligible",row.allowedForLiveEntry);put("protective_live",row.allowedForProtectiveLiveAction)
                put("live_truth_gate",row.definition.liveTruthGate);put("usage_context_verified",row.definition.usageContextSourceVerified);put("no_trade_verified",row.definition.noTradeConditionsSourceVerified);put("source_refs",JSONArray(row.definition.sourceRefs));put("provenance",JSONArray(row.definition.provenance));put("reason",row.reason.take(1800))
            })
        }
        val selected=e.selectedEntry
        dao.insertEvent(ResearchEventEntity(eventType="handoff_catalog_evaluation",symbol=selected?.candidate?.symbol?:"",strategy=selected?.definition?.id?:"NONE",regime="${s.trendH4}/${s.trendH1}",mode=settings.mode.name,adjustment=e.aggregateAdjustment,status=if(e.hardEntryBlock)"BLOCK" else "OK",reason=e.reason.take(3000),payloadJson=JSONObject().apply{put("research_freeze",ResearchHandoffCatalog.RESEARCH_FREEZE);put("evaluated_count",e.evaluations.size);put("size_multiplier",e.sizeMultiplier);put("btc_dominance_status",dom.status);put("evaluations",arr)}.toString()))
        selected?.let{row->dao.insertEvent(ResearchEventEntity(eventType="handoff_selected_candidate",symbol=row.candidate?.symbol?:"",strategy=row.definition.id,mode=settings.mode.name,adjustment=row.adjustment,status=row.status,reason=row.reason.take(3000),payloadJson=JSONObject().apply{put("cost_gate",row.cost?.allowed);put("risk_gate",row.risk?.allowed);put("max_notional",row.risk?.maxNotionalQuote?.toPlainString());put("source_refs",JSONArray(row.definition.sourceRefs));put("truth_gate",row.definition.liveTruthGate)}.toString()))}
        e.protectiveAction?.let{row->dao.insertEvent(ResearchEventEntity(eventType="handoff_protective_action",symbol=row.candidate?.symbol?:"",strategy=row.definition.id,mode=settings.mode.name,adjustment=row.adjustment,status=row.status,reason=row.reason.take(3000)))}
        val stale = researchFreezeStale()
        dao.putState(ResearchStateEntity(
            key="handoff_source_revalidation_due",
            value=JSONObject().apply{
                put("due",stale);put("research_freeze",ResearchHandoffCatalog.RESEARCH_FREEZE);put("freshness_days",settingsStore.handoffFreshnessWarnDays())
                put("policy_asset","research_handoff/WEEKLY_RESEARCH_RUNBOOK.md")
                put("effect",if(stale)"Positive handoff LIVE entries blocked pending source revalidation; PAPER evidence and protective risk reductions remain automatic." else "Source research within configured freshness window.")
                put("updated_at",System.currentTimeMillis())
            }.toString()
        ))
    }

    private suspend fun auditAssetsOnce(){if(assetAuditDone)return;val audit=runCatching{catalog.assetAudit()}.getOrElse{mapOf("error" to (it.message?:"asset audit failure"))};dao.putState(ResearchStateEntity("handoff_asset_audit",JSONObject(audit).toString()));assetAuditDone=true}

    private suspend fun augmentHistory(symbol:String,input:Map<Timeframe,List<Candle>>,exchange:CryptoExchangeClient):Map<Timeframe,List<Candle>>{
        val key=symbol.uppercase();val now=System.currentTimeMillis();val cached=historyCache[key]
        val need=cached==null||now-cached.loadedAt>15*60_000L
        val history=if(need){
            val h1=runCatching{exchange.getCandles(symbol,Timeframe.H1,720)}.getOrDefault(input[Timeframe.H1].orEmpty())
            val h4=runCatching{exchange.getCandles(symbol,Timeframe.H4,720)}.getOrDefault(input[Timeframe.H4].orEmpty())
            HistoryCache(h1,h4,now).also{historyCache[key]=it}
        }else cached!!
        return input.toMutableMap().apply{put(Timeframe.H1,(input[Timeframe.H1].orEmpty()+history.h1).distinctBy{it.openTimeEpochMs}.sortedBy{it.openTimeEpochMs});put(Timeframe.H4,(input[Timeframe.H4].orEmpty()+history.h4).distinctBy{it.openTimeEpochMs}.sortedBy{it.openTimeEpochMs})}
    }

    private fun rankEntry(row:HandoffCandidateEvaluation,mode:BotMode):Int{val eligibility=if(mode==BotMode.PAPER)row.allowedForPaperExecution else row.allowedForLiveEntry;val fidelity=when(row.definition.fidelity.uppercase()){ "A"->4;"B"->3;"C"->1;else->-10 };val triggered=if(row.candidate?.triggerDetected==true)5 else 0;return (if(eligibility)50 else 0)+fidelity*5+triggered+row.adjustment}
    private fun balanceForQuote(b:Map<String,BigDecimal>,quote:String):BigDecimal{val direct=b[quote]?:b[if(quote=="EUR")"ZEUR" else quote];return direct?:BigDecimal.ZERO}
    private fun conservativeEquityEstimate(cash:BigDecimal,positions:List<PositionEntity>,ticker:MarketTicker):BigDecimal{var total=cash;positions.forEach{p->if(p.symbol.equals(ticker.symbol,true)){val q=p.quantity.toBigDecimalOrNull()?:BigDecimal.ZERO;total+=q.multiply(ticker.bid)}};return total.max(cash)}
    private fun researchFreezeStale():Boolean{val freeze=LocalDate.parse(ResearchHandoffCatalog.RESEARCH_FREEZE);val now=LocalDate.now(ZoneOffset.UTC);return ChronoUnit.DAYS.between(freeze,now)>settingsStore.handoffFreshnessWarnDays()}
    private fun cluster(symbol:String):String{val base=symbol.uppercase().replace("/","").replace("-","").removeSuffix("EUR").removeSuffix("USD").removeSuffix("USDT").removeSuffix("USDC");return when(base){"BTC","XBT"->"BTC";"ETH","LINK","UNI","AAVE","LDO","ARB","OP","MKR"->"ETH_ECOSYSTEM";"SOL","JUP","RAY","BONK","WIF"->"SOL_ECOSYSTEM";else->"HIGH_BETA_ALT"}}
    private fun BigDecimal.s2()=setScale(2,RoundingMode.HALF_UP).toPlainString();private fun BigDecimal.s4()=setScale(4,RoundingMode.HALF_UP).toPlainString()
}
