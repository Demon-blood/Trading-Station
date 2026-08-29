package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.data.ResearchDao
import com.ksp.cryptobot.data.ResearchEventEntity
import com.ksp.cryptobot.data.ResearchStateEntity
import com.ksp.cryptobot.data.TradeEntity
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sqrt

enum class ChampionHealthState { HEALTHY, WATCH, PROBATION, LIVE_DISABLED, ROLLED_BACK }

data class ChampionRollingStats(
    val samples: Int,
    val evidenceSpanMs: Long,
    val netPnlQuote: BigDecimal,
    val meanReturn: BigDecimal,
    val profitFactor: BigDecimal,
    val maxDrawdownRate: BigDecimal,
    val lower95Return: BigDecimal,
    val upper95Return: BigDecimal
)

data class ChampionHealthDecision(
    val state: ChampionHealthState,
    val championBefore: String?,
    val championAfter: String?,
    val liveEntryAuthorized: Boolean,
    val liveSizeMultiplier: BigDecimal,
    val rolling: ChampionRollingStats,
    val rollbackCandidate: String?,
    val reason: String
)

class ChampionDegradationEngine(
    private val dao: ResearchDao,
    private val validation: WalkForwardMonteCarloEngine
) {
    companion object {
        const val ROLLING_WINDOW = 30
        const val MIN_WATCH_SAMPLES = 8
        const val MIN_PROBATION_SAMPLES = 12
        const val MIN_DISABLE_SAMPLES = 20
        const val MIN_DISABLE_SPAN_MS = 3L * 24L * 60L * 60L * 1000L
        private val WATCH_SIZE = BigDecimal("0.75")
        private val PROBATION_SIZE = BigDecimal("0.50")
        private val MIN_PROBATION_NET = BigDecimal("-0.10")
        private val MIN_DISABLE_NET = BigDecimal("-0.25")
        private val MIN_HARM_MEAN = BigDecimal("-0.0005")
        private val CLIP = BigDecimal("0.25")

        fun rollingStats(outcomes: List<StrategyOutcome>): ChampionRollingStats {
            val rows = outcomes.sortedBy { it.timestampEpochMs }.takeLast(ROLLING_WINDOW)
            if (rows.isEmpty()) return ChampionRollingStats(0,0L,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO)
            val returns = rows.map { it.normalizedNetReturn.coerceIn(CLIP.negate(), CLIP).toDouble() }
            val mean = returns.average()
            val variance = if (returns.size > 1) returns.sumOf { (it-mean)*(it-mean) } / (returns.size-1).toDouble() else 0.0
            val se = if (returns.isNotEmpty()) sqrt(variance.coerceAtLeast(0.0))/sqrt(returns.size.toDouble()) else 0.0
            val margin = critical95(returns.size) * se
            val wins = rows.filter { it.conservativeNetPnlQuote > BigDecimal.ZERO }.fold(BigDecimal.ZERO){a,b->a+b.conservativeNetPnlQuote}
            val losses = rows.filter { it.conservativeNetPnlQuote < BigDecimal.ZERO }.fold(BigDecimal.ZERO){a,b->a+b.conservativeNetPnlQuote.abs()}
            val pf = when { losses>BigDecimal.ZERO -> wins.divide(losses,8,RoundingMode.HALF_UP); wins>BigDecimal.ZERO -> BigDecimal("99"); else -> BigDecimal.ZERO }
            var equity=BigDecimal.ZERO; var peak=BigDecimal.ZERO; var maxDd=BigDecimal.ZERO
            rows.forEach { equity += it.normalizedNetReturn; if(equity>peak) peak=equity; val dd=peak-equity; if(dd>maxDd) maxDd=dd }
            val span = if(rows.size>1) (rows.last().timestampEpochMs-rows.first().timestampEpochMs).coerceAtLeast(0L) else 0L
            return ChampionRollingStats(
                rows.size, span, rows.fold(BigDecimal.ZERO){a,b->a+b.conservativeNetPnlQuote},
                BigDecimal.valueOf(mean), pf, maxDd,
                BigDecimal.valueOf(mean-margin), BigDecimal.valueOf(mean+margin)
            )
        }

        fun classify(previous: ChampionHealthState, stats: ChampionRollingStats, maxDrawdownPercent: BigDecimal): ChampionHealthDecision {
            val hardDd=maxDrawdownPercent.divide(BigDecimal("100"),8,RoundingMode.HALF_UP).coerceIn(BigDecimal("0.005"),BigDecimal("0.50"))
            val hardDdBreach=stats.samples>=MIN_PROBATION_SAMPLES && stats.maxDrawdownRate>hardDd
            val harmful=stats.samples>=MIN_DISABLE_SAMPLES && stats.evidenceSpanMs>=MIN_DISABLE_SPAN_MS && stats.netPnlQuote<=MIN_DISABLE_NET && stats.meanReturn<=MIN_HARM_MEAN && stats.upper95Return<BigDecimal.ZERO
            val probation=stats.samples>=MIN_PROBATION_SAMPLES && (hardDdBreach || (stats.netPnlQuote<=MIN_PROBATION_NET && stats.meanReturn<=MIN_HARM_MEAN && stats.profitFactor<BigDecimal("0.90")))
            val watch=stats.samples>=MIN_WATCH_SAMPLES && (stats.meanReturn<BigDecimal.ZERO || stats.profitFactor<BigDecimal.ONE || stats.maxDrawdownRate>=hardDd*BigDecimal("0.50"))
            val recovered=stats.samples>=MIN_PROBATION_SAMPLES && stats.netPnlQuote>BigDecimal.ZERO && stats.meanReturn>BigDecimal.ZERO && stats.lower95Return>=BigDecimal.ZERO && stats.profitFactor>=BigDecimal("1.10")
            val state=when {
                previous==ChampionHealthState.LIVE_DISABLED -> ChampionHealthState.LIVE_DISABLED
                harmful || hardDdBreach -> ChampionHealthState.LIVE_DISABLED
                previous==ChampionHealthState.PROBATION && !recovered -> ChampionHealthState.PROBATION
                probation -> ChampionHealthState.PROBATION
                recovered -> ChampionHealthState.HEALTHY
                watch -> ChampionHealthState.WATCH
                else -> ChampionHealthState.HEALTHY
            }
            val mult=when(state){ChampionHealthState.HEALTHY->BigDecimal.ONE;ChampionHealthState.WATCH->WATCH_SIZE;ChampionHealthState.PROBATION->PROBATION_SIZE;else->BigDecimal.ZERO}
            return ChampionHealthDecision(state,null,null,state!=ChampionHealthState.LIVE_DISABLED && state!=ChampionHealthState.ROLLED_BACK,mult,stats,null,
                when(state){ChampionHealthState.HEALTHY->"Champion HEALTHY.";ChampionHealthState.WATCH->"Champion WATCH: LIVE research size capped at 75%.";ChampionHealthState.PROBATION->"Champion PROBATION: LIVE research size capped at 50%.";ChampionHealthState.LIVE_DISABLED->"Champion LIVE_DISABLED: statistically credible degradation or strategy drawdown breach.";ChampionHealthState.ROLLED_BACK->"Champion rolled back."})
        }

        private fun critical95(n:Int)=when { n<=8->2.365; n<=10->2.262; n<=15->2.145; n<=20->2.093; n<=30->2.045; else->2.0 }
        private fun BigDecimal.coerceIn(lo:BigDecimal,hi:BigDecimal)=when{this<lo->lo;this>hi->hi;else->this}
    }

    suspend fun evaluateAndApply(settings: BotSettings, symbol: String, trades: List<TradeEntity>, nowEpochMs: Long=System.currentTimeMillis()): ChampionHealthDecision {
        val s=normalize(symbol)
        val champion=dao.state("m9_champion:$s")?.value?.takeIf{it.isNotBlank()} ?: return ChampionHealthDecision(ChampionHealthState.HEALTHY,null,null,false,BigDecimal.ONE,rollingStats(emptyList()),null,"No M9 champion exists for $s.")
        val promotedAt=dao.state("m9_champion_promoted_at:$s")?.value?.toLongOrNull()?:0L
        val post=StrategyChampionChallengerEngine.exactOutcomes(champion,s,trades).filter{promotedAt<=0L||it.timestampEpochMs>=promotedAt}
        val previous=dao.state("m10_health:$s")?.value?.let{runCatching{ChampionHealthState.valueOf(it)}.getOrNull()}?:ChampionHealthState.HEALTHY
        var decision=classify(previous,rollingStats(post),settings.maxDrawdownPercent).copy(championBefore=champion,championAfter=champion)
        if(decision.state==ChampionHealthState.LIVE_DISABLED){
            val rollback=findSafeRollbackCandidate(settings,s,champion,trades)
            if(rollback!=null){
                dao.putState(ResearchStateEntity("m9_champion:$s",rollback,nowEpochMs))
                dao.putState(ResearchStateEntity("m9_champion_promoted_at:$s",nowEpochMs.toString(),nowEpochMs))
                dao.putState(ResearchStateEntity("m9_champion_reason:$s","M10 rollback from $champion to $rollback",nowEpochMs))
                decision=decision.copy(state=ChampionHealthState.ROLLED_BACK,championAfter=rollback,liveEntryAuthorized=false,liveSizeMultiplier=BigDecimal.ZERO,rollbackCandidate=rollback,reason="M10 ROLLBACK: $champion degraded; previous champion $rollback still passes M9 standalone gates. No entry is authorized in the rollback scan.")
                dao.insertEvent(ResearchEventEntity(timestampEpochMs=nowEpochMs,eventType="m10_champion_rollback",symbol=s,strategy=rollback,variant="FROM=$champion",mode=settings.mode.name,status="ROLLED_BACK",sampleCount=decision.rolling.samples,reason=decision.reason))
            }
        }
        dao.putState(ResearchStateEntity("m10_health:$s",decision.state.name,nowEpochMs))
        dao.putState(ResearchStateEntity("m10_health_reason:$s",decision.reason.take(2000),nowEpochMs))
        dao.putState(ResearchStateEntity("m10_live_size_multiplier:$s",decision.liveSizeMultiplier.toPlainString(),nowEpochMs))
        if(previous!=decision.state || decision.state==ChampionHealthState.ROLLED_BACK){
            dao.insertEvent(ResearchEventEntity(timestampEpochMs=nowEpochMs,eventType="m10_champion_health_transition",symbol=s,strategy=champion,variant="${previous.name}->${decision.state.name}",mode=settings.mode.name,status=decision.state.name,sampleCount=decision.rolling.samples,reason=decision.reason))
        }
        return decision
    }

    suspend fun inspect(settings: BotSettings, symbol: String, trades: List<TradeEntity>): ChampionHealthDecision {
        val s=normalize(symbol)
        val champion=dao.state("m9_champion:$s")?.value?.takeIf{it.isNotBlank()} ?: return ChampionHealthDecision(ChampionHealthState.HEALTHY,null,null,false,BigDecimal.ONE,rollingStats(emptyList()),null,"No M9 champion exists for $s.")
        val promotedAt=dao.state("m9_champion_promoted_at:$s")?.value?.toLongOrNull()?:0L
        val rows=StrategyChampionChallengerEngine.exactOutcomes(champion,s,trades).filter{promotedAt<=0L||it.timestampEpochMs>=promotedAt}
        val previous=dao.state("m10_health:$s")?.value?.let{runCatching{ChampionHealthState.valueOf(it)}.getOrNull()}?:ChampionHealthState.HEALTHY
        return classify(previous,rollingStats(rows),settings.maxDrawdownPercent).copy(championBefore=champion,championAfter=champion)
    }

    private suspend fun findSafeRollbackCandidate(settings:BotSettings,symbol:String,currentChampion:String,trades:List<TradeEntity>):String?{
        val prior=dao.recentEventsByType("m9_strategy_promotion",100).asSequence().filter{it.symbol.equals(symbol,true)}.map{it.strategy.trim()}.filter{it.isNotBlank()&&!it.equals(currentChampion,true)}.distinct().toList()
        for(candidate in prior){
            val outcomes=StrategyChampionChallengerEngine.exactOutcomes(candidate,symbol,trades)
            val stats=StrategyChampionChallengerEngine.statistics(outcomes)
            if(stats.exactSamples<StrategyChampionChallengerEngine.MIN_EXACT_OUTCOMES) continue
            val wf=validation.walkForward(candidate,symbol,trades,StrategyChampionChallengerEngine.MIN_EXACT_OUTCOMES)
            val mc=validation.monteCarlo(candidate,symbol,trades,500,StrategyChampionChallengerEngine.MIN_EXACT_OUTCOMES)
            val regimes=dao.recentEventsForSymbol(symbol,1500).asSequence().filter{it.strategy.equals(candidate,true)}.map{it.regime.trim()}.filter{it.isNotBlank()&&!it.equals("UNKNOWN",true)}.toSet().size
            if(rollbackQualityPassed(stats,regimes,wf,mc,settings.maxDrawdownPercent)) return candidate
        }
        return null
    }

    private fun rollbackQualityPassed(stats:StrategyOosStats,regimes:Int,wf:WalkForwardAssessment,mc:MonteCarloAssessment,maxDrawdownPercent:BigDecimal):Boolean {
        val hardDd=maxDrawdownPercent.divide(BigDecimal("100"),8,RoundingMode.HALF_UP).coerceInLocal(BigDecimal("0.005"),BigDecimal("0.50"))
        return stats.exactSamples>=StrategyChampionChallengerEngine.MIN_EXACT_OUTCOMES &&
            stats.paperSamples>=StrategyChampionChallengerEngine.MIN_PAPER_OUTCOMES &&
            stats.testSamples>=StrategyChampionChallengerEngine.MIN_OOS_OUTCOMES &&
            stats.evidenceSpanMs>=StrategyChampionChallengerEngine.MIN_EVIDENCE_SPAN_MS &&
            regimes>=StrategyChampionChallengerEngine.MIN_REGIMES &&
            stats.oosNetPnlQuote>=BigDecimal("0.25") && stats.oosMeanReturn>=BigDecimal("0.0005") &&
            stats.lower95Return>BigDecimal.ZERO && stats.oosProfitFactor>=BigDecimal("1.20") && stats.oosMaxDrawdownRate<=hardDd &&
            wf.ready && wf.status=="PASS" && wf.score>=60.0 && wf.windows>0 && wf.profitableWindows*2>=wf.windows &&
            mc.ready && mc.score>=60.0 && mc.probabilityPositive>=0.65
    }

    private fun BigDecimal.coerceInLocal(lo:BigDecimal,hi:BigDecimal)=when{this<lo->lo;this>hi->hi;else->this}
    private fun normalize(symbol:String)=symbol.uppercase().replace("/","").replace("-","").replace("_","")
}
