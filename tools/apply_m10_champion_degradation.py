#!/usr/bin/env python3
from pathlib import Path
import os, sys

NEW_ENGINE='app/src/main/java/com/ksp/cryptobot/research/ChampionDegradationEngine.kt'
NEW_TEST='app/src/test/java/com/ksp/cryptobot/research/ChampionDegradationEngineTest.kt'

def fail(m): raise SystemExit('ERROR | '+m)
def repl(t,o,n,label):
    c=t.count(o)
    if c!=1: fail(f'{label}: expected one match, got {c}')
    return t.replace(o,n,1)

def main():
    repo=Path(sys.argv[1] if len(sys.argv)>1 else '.').resolve()
    if not (repo/'.git').exists(): fail('Not a git checkout')
    dirty=os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty: fail('Refusing to patch dirty app tree:\n'+dirty)
    payload=Path(__file__).resolve().parent/'m10_payload'
    for rel in (NEW_ENGINE,NEW_TEST):
        dst=repo/rel; dst.parent.mkdir(parents=True,exist_ok=True)
        dst.write_text((payload/rel).read_text(encoding='utf-8').rstrip()+'\n',encoding='utf-8')
        print('WRITE |',rel)

    p=repo/'app/src/main/java/com/ksp/cryptobot/research/ResearchCoordinator.kt'
    t=p.read_text(encoding='utf-8')
    t=repl(t,
'''    private val championChallenger=StrategyChampionChallengerEngine(dao)
    private val moshi=Moshi.Builder().build()
''',
'''    private val championChallenger=StrategyChampionChallengerEngine(dao)
    private val championDegradation=ChampionDegradationEngine(dao, validation)
    private val moshi=Moshi.Builder().build()
''','M10 engine property')

    t=repl(t,
'''        val strategyGovernance=championChallenger.evaluateAndMaybePromote(
            settings=settings,
            challengerStrategy=governedStrategy,
            symbol=ticker.symbol,
            trades=recentTrades,
            walkForward=governanceWf,
            monteCarlo=governanceMc
        )
        val handoffModeEligible=handoffEntry?.let { if(settings.mode==BotMode.PAPER) it.allowedForPaperExecution else it.allowedForLiveEntry } ?: false
        val handoffCanExecute=handoffModeEligible && (settings.mode==BotMode.PAPER || strategyGovernance.productionAuthorized)
''',
'''        val strategyGovernance=championChallenger.evaluateAndMaybePromote(
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
''','M10 health integration')

    t=repl(t,
'''            if(canPromote && regime.risk!="RISK_OFF" && (settings.mode==BotMode.PAPER || strategyGovernance.productionAuthorized)){action=SignalAction.SMALL_BUY;score=maxOf(score,68);promoted=true}
''',
'''            if(canPromote && regime.risk!="RISK_OFF" && (settings.mode==BotMode.PAPER || governedLiveAuthorized)){action=SignalAction.SMALL_BUY;score=maxOf(score,68);promoted=true}
''','M10 generic promotion gate')

    t=repl(t,
'''        val governanceLine="M9 strategy=$governedStrategy action=${strategyGovernance.action} championBefore=${strategyGovernance.championBefore ?: "none"} championAfter=${strategyGovernance.championAfter ?: "none"} liveAuthorized=${strategyGovernance.productionAuthorized} exact=${strategyGovernance.challenger.exactSamples} paper=${strategyGovernance.challenger.paperSamples} oos=${strategyGovernance.challenger.testSamples} regimes=${strategyGovernance.regimeCount} lower95=${strategyGovernance.challenger.lower95Return} diffLower95=${strategyGovernance.difference?.lower95Difference ?: BigDecimal.ZERO}"
''',
'''        val governanceLine="M9 strategy=$governedStrategy action=${strategyGovernance.action} championBefore=${strategyGovernance.championBefore ?: "none"} championAfter=${strategyGovernance.championAfter ?: "none"} liveAuthorized=${strategyGovernance.productionAuthorized} exact=${strategyGovernance.challenger.exactSamples} paper=${strategyGovernance.challenger.paperSamples} oos=${strategyGovernance.challenger.testSamples} regimes=${strategyGovernance.regimeCount} lower95=${strategyGovernance.challenger.lower95Return} diffLower95=${strategyGovernance.difference?.lower95Difference ?: BigDecimal.ZERO}; M10 health=${championHealth.state} healthChampion=${championHealth.championAfter ?: "none"} liveAuthorized=$governedLiveAuthorized rollingN=${championHealth.rolling.samples} rollingMean=${championHealth.rolling.meanReturn} rollingUpper95=${championHealth.rolling.upper95Return} rollback=${championHealth.rollbackCandidate ?: "none"}"
''','M10 explanation')

    t=repl(t,
'''        val sizeMult=(metaResult.sizeMultiplier*crossResult.multiplier*futures.multiplier*wallet.multiplier*crossMarket.multiplier*externalRiskMultiplier*desktopSmartResult.appliedSizeMultiplier*professionalRiskResult.sizeMultiplier*handoffResult.sizeMultiplier).coerceIn(.05,1.00)
''',
'''        val championHealthSize=if(settings.mode==BotMode.PAPER)1.0 else if(strategyGovernance.productionAuthorized) championHealth.liveSizeMultiplier.toDouble() else 1.0
        val sizeMult=(metaResult.sizeMultiplier*crossResult.multiplier*futures.multiplier*wallet.multiplier*crossMarket.multiplier*externalRiskMultiplier*desktopSmartResult.appliedSizeMultiplier*professionalRiskResult.sizeMultiplier*handoffResult.sizeMultiplier*championHealthSize).coerceIn(.05,1.00)
''','M10 probation size cap')

    t=repl(t,
'''    suspend fun strategyPromotions(limit:Int=100):List<ResearchEventEntity> = championChallenger.recentPromotions(limit)
    fun researchSettings():ResearchSettingsStore=settingsStore
''',
'''    suspend fun strategyPromotions(limit:Int=100):List<ResearchEventEntity> = championChallenger.recentPromotions(limit)
    suspend fun championHealth(settings:BotSettings,symbol:String,trades:List<TradeEntity>):ChampionHealthDecision = championDegradation.inspect(settings,symbol,trades)
    fun researchSettings():ResearchSettingsStore=settingsStore
''','M10 public health')
    p.write_text(t,encoding='utf-8'); print('PATCH |',p.relative_to(repo))

    p=repo/'app/src/main/java/com/ksp/cryptobot/core/BotController.kt'
    t=p.read_text(encoding='utf-8')
    old='''        val currentStrategyChampion = runCatching { researchIntelligence.strategyChampion(primarySymbol) }.getOrNull()
        add(
            "PASS",
            "M9 Strategy Champion/Challenger",
            if(currentStrategyChampion.isNullOrBlank())
                "No champion yet for $primarySymbol. PAPER challengers may gather exact evidence; LIVE research promotion remains champion-gated."
            else
                "Champion for $primarySymbol=$currentStrategyChampion. System inspection is read-only; M9 cannot increase size or bypass M4/M5/risk gates."
        )
'''
    new=old+'''        val m10Trades = runCatching { dao.recentTradesSnapshot(500) }.getOrDefault(emptyList())
        val currentChampionHealth = runCatching { researchIntelligence.championHealth(settings, primarySymbol, m10Trades) }.getOrNull()
        if(currentChampionHealth==null) add("WARN","M10 Champion Degradation","Unable to inspect champion health.")
        else add("PASS","M10 Champion Degradation","state=${currentChampionHealth.state}, champion=${currentChampionHealth.championAfter ?: "none"}, rollingN=${currentChampionHealth.rolling.samples}, mean=${currentChampionHealth.rolling.meanReturn}, upper95=${currentChampionHealth.rolling.upper95Return}, liveAuthorized=${currentChampionHealth.liveEntryAuthorized}, sizeCap=${currentChampionHealth.liveSizeMultiplier}, rollback=${currentChampionHealth.rollbackCandidate ?: "none"}. Inspection is read-only and never affects protective exits.")
'''
    t=repl(t,old,new,'M10 system verification')
    p.write_text(t,encoding='utf-8'); print('PATCH |',p.relative_to(repo))

    changed=set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked=set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    allowed={NEW_ENGINE,NEW_TEST,'app/src/main/java/com/ksp/cryptobot/research/ResearchCoordinator.kt','app/src/main/java/com/ksp/cryptobot/core/BotController.kt'}
    actual=changed|untracked
    if actual-allowed: fail('Unexpected M10 app changes: '+','.join(sorted(actual-allowed)))
    if allowed-actual: fail('Expected M10 changes missing: '+','.join(sorted(allowed-actual)))
    print('PASS | M10 controlled app diff.')

if __name__=='__main__': main()
