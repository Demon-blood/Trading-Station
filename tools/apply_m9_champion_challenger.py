#!/usr/bin/env python3
from pathlib import Path
import os, sys

NEW_ENGINE="app/src/main/java/com/ksp/cryptobot/research/StrategyChampionChallengerEngine.kt"
NEW_TEST="app/src/test/java/com/ksp/cryptobot/research/StrategyChampionChallengerEngineTest.kt"

def fail(msg): raise SystemExit("ERROR | "+msg)

def replace_once(text, old, new, label):
    n=text.count(old)
    if n!=1: fail(f"{label}: expected one match, got {n}")
    return text.replace(old,new,1)

def main():
    repo=Path(sys.argv[1] if len(sys.argv)>1 else ".").resolve()
    if not (repo/".git").exists(): fail("Not a git checkout")
    dirty=os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty: fail("Refusing to patch dirty app tree:\n"+dirty)

    payload=Path(__file__).resolve().parent/"m9_payload"
    for rel in (NEW_ENGINE,NEW_TEST):
        src=payload/rel; dst=repo/rel
        dst.parent.mkdir(parents=True,exist_ok=True)
        dst.write_text(src.read_text(encoding="utf-8").rstrip()+"\n",encoding="utf-8")
        print("WRITE |",rel)

    p=repo/"app/src/main/java/com/ksp/cryptobot/research/ResearchCoordinator.kt"
    t=p.read_text(encoding="utf-8")
    t=replace_once(t,
'''    private val handoff=ResearchHandoffEngine(context.applicationContext, dao, settingsStore)
    private val moshi=Moshi.Builder().build()
''',
'''    private val handoff=ResearchHandoffEngine(context.applicationContext, dao, settingsStore)
    private val championChallenger=StrategyChampionChallengerEngine(dao)
    private val moshi=Moshi.Builder().build()
''',"M9 engine property")

    t=replace_once(t,
'''        val handoffEntry=handoffResult.selectedEntry
        val handoffCanExecute=handoffEntry?.let { if(settings.mode==BotMode.PAPER) it.allowedForPaperExecution else it.allowedForLiveEntry } ?: false
        val handoffCanStage=handoffEntry?.candidate?.let { it.triggerDetected || it.entryPlan.resting } ?: false
        val handoffProtective=handoffResult.protectiveAction
''',
'''        val handoffEntry=handoffResult.selectedEntry
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
        val handoffModeEligible=handoffEntry?.let { if(settings.mode==BotMode.PAPER) it.allowedForPaperExecution else it.allowedForLiveEntry } ?: false
        val handoffCanExecute=handoffModeEligible && (settings.mode==BotMode.PAPER || strategyGovernance.productionAuthorized)
        val handoffCanStage=handoffEntry?.candidate?.let { it.triggerDetected || it.entryPlan.resting } ?: false
        val handoffProtective=handoffResult.protectiveAction
''',"M9 governance evaluation")

    t=replace_once(t,
'''            if(canPromote && regime.risk!="RISK_OFF"){action=SignalAction.SMALL_BUY;score=maxOf(score,68);promoted=true}
''',
'''            if(canPromote && regime.risk!="RISK_OFF" && (settings.mode==BotMode.PAPER || strategyGovernance.productionAuthorized)){action=SignalAction.SMALL_BUY;score=maxOf(score,68);promoted=true}
''',"M9 generic research LIVE gate")

    old_handoff='''        val handoffLine="handoffSelected=${handoffEntry?.definition?.id ?: "none"}/${handoffEntry?.status ?: "none"}; handoffEligible=$handoffCanExecute; handoffCanStage=$handoffCanStage; handoffProtective=${handoffProtective?.definition?.id ?: "none"}/${handoffProtective?.candidate?.sideIntent ?: "none"}; handoffAdj=${handoffResult.aggregateAdjustment}; handoffSize=${"%.3f".format(handoffResult.sizeMultiplier)}; handoffBlock=${handoffResult.hardEntryBlock}"
'''
    new_handoff='''        val governanceLine="M9 strategy=$governedStrategy action=${strategyGovernance.action} championBefore=${strategyGovernance.championBefore ?: "none"} championAfter=${strategyGovernance.championAfter ?: "none"} liveAuthorized=${strategyGovernance.productionAuthorized} exact=${strategyGovernance.challenger.exactSamples} paper=${strategyGovernance.challenger.paperSamples} oos=${strategyGovernance.challenger.testSamples} regimes=${strategyGovernance.regimeCount} lower95=${strategyGovernance.challenger.lower95Return} diffLower95=${strategyGovernance.difference?.lower95Difference ?: BigDecimal.ZERO}"
        val handoffLine="handoffSelected=${handoffEntry?.definition?.id ?: "none"}/${handoffEntry?.status ?: "none"}; handoffEligible=$handoffCanExecute; handoffCanStage=$handoffCanStage; handoffProtective=${handoffProtective?.definition?.id ?: "none"}/${handoffProtective?.candidate?.sideIntent ?: "none"}; handoffAdj=${handoffResult.aggregateAdjustment}; handoffSize=${"%.3f".format(handoffResult.sizeMultiplier)}; handoffBlock=${handoffResult.hardEntryBlock}"
'''
    t=replace_once(t,old_handoff,new_handoff,"M9 governance explanation prelude")

    old_exp='''        val explanation="Research strategy=$strategy score=${selected?.score?:0}; strategyEnsembleAdj=$voteAdj (parity=$parityVoteAdj, professional=$professionalVoteAdj/raw=$professionalVoteRaw, proMature=$professionalMature, samples=$professionalOutcomeSamples); regime=${regime.regime}; totalAdj=$total; WF=${"%.1f".format(wf.score)}; MC=${"%.1f".format(mc.score)}; desktopWF=${desktopWf.first}; desktopMC=${desktopMc.first}; desktopMTF=${desktopMtf.first}; desktopSmart=${desktopSmartResult.adjustment}/size=${"%.2f".format(desktopSmartResult.appliedSizeMultiplier)}; meta=${metaResult.adjustment}; cross=${crossResult.adjustment}; seq=${sequence.adjustment}; RL=${rl.adjustment}; replay=${replayResult.first}; mutation=${mutationResult.variant}/${mutationResult.adjustment}; hypothesis=${hypothesis.first}; futures=${futures.adjustment}; wallet=${wallet.adjustment}; crossMarket=${crossMarket.adjustment}; professionalExternal=${professionalExternalResult.compositeAdjustment}; proRisk=${"%.2f".format(professionalRiskResult.sizeMultiplier)}x/ATR=${"%.2f".format(professionalRiskResult.atrPercent)}%; $handoffLine; promoted=$promoted; exitPreserved=$isExit. $parameterSuggestion | ${desktopSmartResult.report} | ${professionalRiskResult.reason} | ${professionalExternalResult.reason} | ${handoffResult.reason}"
'''
    new_exp='''        val explanation="Research strategy=$strategy score=${selected?.score?:0}; strategyEnsembleAdj=$voteAdj (parity=$parityVoteAdj, professional=$professionalVoteAdj/raw=$professionalVoteRaw, proMature=$professionalMature, samples=$professionalOutcomeSamples); regime=${regime.regime}; totalAdj=$total; WF=${"%.1f".format(wf.score)}; MC=${"%.1f".format(mc.score)}; desktopWF=${desktopWf.first}; desktopMC=${desktopMc.first}; desktopMTF=${desktopMtf.first}; desktopSmart=${desktopSmartResult.adjustment}/size=${"%.2f".format(desktopSmartResult.appliedSizeMultiplier)}; meta=${metaResult.adjustment}; cross=${crossResult.adjustment}; seq=${sequence.adjustment}; RL=${rl.adjustment}; replay=${replayResult.first}; mutation=${mutationResult.variant}/${mutationResult.adjustment}; hypothesis=${hypothesis.first}; futures=${futures.adjustment}; wallet=${wallet.adjustment}; crossMarket=${crossMarket.adjustment}; professionalExternal=${professionalExternalResult.compositeAdjustment}; proRisk=${"%.2f".format(professionalRiskResult.sizeMultiplier)}x/ATR=${"%.2f".format(professionalRiskResult.atrPercent)}%; $governanceLine; $handoffLine; promoted=$promoted; exitPreserved=$isExit. $parameterSuggestion | ${desktopSmartResult.report} | ${professionalRiskResult.reason} | ${professionalExternalResult.reason} | ${strategyGovernance.reason} | ${handoffResult.reason}"
'''
    t=replace_once(t,old_exp,new_exp,"M9 explanation body")

    t=replace_once(t,
'''    suspend fun profiles():List<ResearchStrategyProfileEntity> = dao.profiles()
    fun researchSettings():ResearchSettingsStore=settingsStore
''',
'''    suspend fun profiles():List<ResearchStrategyProfileEntity> = dao.profiles()
    suspend fun strategyChampion(symbol:String):String? = championChallenger.currentChampion(symbol)
    suspend fun strategyPromotions(limit:Int=100):List<ResearchEventEntity> = championChallenger.recentPromotions(limit)
    fun researchSettings():ResearchSettingsStore=settingsStore
''',"M9 public state")
    p.write_text(t,encoding="utf-8")
    print("PATCH |",p.relative_to(repo))

    p=repo/"app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    t=p.read_text(encoding="utf-8")
    t=replace_once(t,
'''        val primarySymbol = settings.symbols().firstOrNull()?.uppercase()?.replace("/", "")?.replace("-", "") ?: "BTCEUR"
        val publicKraken = KrakenSpotClient(apiKey = "", secretKey = "")
''',
'''        val primarySymbol = settings.symbols().firstOrNull()?.uppercase()?.replace("/", "")?.replace("-", "") ?: "BTCEUR"
        val currentStrategyChampion = runCatching { researchIntelligence.strategyChampion(primarySymbol) }.getOrNull()
        add(
            "PASS",
            "M9 Strategy Champion/Challenger",
            if(currentStrategyChampion.isNullOrBlank())
                "No champion yet for $primarySymbol. PAPER challengers may gather exact evidence; LIVE research promotion remains champion-gated."
            else
                "Champion for $primarySymbol=$currentStrategyChampion. System inspection is read-only; M9 cannot increase size or bypass M4/M5/risk gates."
        )
        val publicKraken = KrakenSpotClient(apiKey = "", secretKey = "")
''',"M9 system verification")
    p.write_text(t,encoding="utf-8")
    print("PATCH |",p.relative_to(repo))

    changed=set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked=set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    all_changed=changed|untracked
    allowed={NEW_ENGINE,NEW_TEST,
        "app/src/main/java/com/ksp/cryptobot/research/ResearchCoordinator.kt",
        "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"}
    if all_changed-allowed: fail("Unexpected M9 app changes: "+",".join(sorted(all_changed-allowed)))
    if allowed-all_changed: fail("Expected M9 changes missing: "+",".join(sorted(allowed-all_changed)))
    print("PASS | M9 controlled app diff.")

if __name__=="__main__": main()
