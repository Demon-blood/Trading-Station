#!/usr/bin/env python3
from pathlib import Path
import sys

def read(p): return p.read_text(encoding='utf-8') if p.exists() else ''
def main():
    repo=Path(sys.argv[1] if len(sys.argv)>1 else '.').resolve()
    engine=read(repo/'app/src/main/java/com/ksp/cryptobot/research/ChampionDegradationEngine.kt')
    coord=read(repo/'app/src/main/java/com/ksp/cryptobot/research/ResearchCoordinator.kt')
    ctl=read(repo/'app/src/main/java/com/ksp/cryptobot/core/BotController.kt')
    tests=read(repo/'app/src/test/java/com/ksp/cryptobot/research/ChampionDegradationEngineTest.kt')
    db=read(repo/'app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt')
    checks={
      'M10 engine present':'class ChampionDegradationEngine' in engine,
      'no Room schema bump':'version = 12' in db,
      'post promotion outcomes':'m9_champion_promoted_at' in engine and 'timestampEpochMs>=promotedAt' in engine,
      'rolling 30':'ROLLING_WINDOW = 30' in engine,
      'watch at 8':'MIN_WATCH_SAMPLES = 8' in engine,
      'probation at 12':'MIN_PROBATION_SAMPLES = 12' in engine,
      'disable at 20':'MIN_DISABLE_SAMPLES = 20' in engine,
      'three day disable span':'MIN_DISABLE_SPAN_MS = 3L' in engine,
      'statistical harm requires upper95 below zero':'stats.upper95Return<BigDecimal.ZERO' in engine,
      'harm mean floor':'MIN_HARM_MEAN = BigDecimal("-0.0005")' in engine,
      'drawdown defense':'hardDdBreach' in engine,
      'watch size 75':'WATCH_SIZE = BigDecimal("0.75")' in engine,
      'probation size 50':'PROBATION_SIZE = BigDecimal("0.50")' in engine,
      'live disabled cannot self reenable':'previous==ChampionHealthState.LIVE_DISABLED' in engine,
      'rollback only prior promotion':'recentEventsByType("m9_strategy_promotion"' in engine,
      'rollback exact evidence':'StrategyChampionChallengerEngine.exactOutcomes(candidate' in engine,
      'rollback M9 sample gates':'StrategyChampionChallengerEngine.MIN_EXACT_OUTCOMES' in engine and 'StrategyChampionChallengerEngine.MIN_PAPER_OUTCOMES' in engine,
      'rollback OOS positive':'stats.lower95Return>BigDecimal.ZERO' in engine,
      'rollback walk forward':'wf.status=="PASS"' in engine and 'wf.score>=60.0' in engine,
      'rollback Monte Carlo':'mc.score>=60.0' in engine and 'mc.probabilityPositive>=0.65' in engine,
      'rollback scan authorizes no entry':'liveEntryAuthorized=false' in engine and 'No entry is authorized in the rollback scan' in engine,
      'health state persistent':'m10_health:' in engine,
      'rollback audited':'m10_champion_rollback' in engine,
      'transition audited':'m10_champion_health_transition' in engine,
      'coordinator applies M10':'championDegradation.evaluateAndApply' in coord,
      'live authorization includes health':'strategyGovernance.productionAuthorized && championHealth.liveEntryAuthorized' in coord,
      'generic promotion uses M10':'settings.mode==BotMode.PAPER || governedLiveAuthorized' in coord,
      'handoff promotion uses M10':'handoffModeEligible && (settings.mode==BotMode.PAPER || governedLiveAuthorized)' in coord,
      'protective handoff remains before positive promotion':'val handoffProtective=handoffResult.protectiveAction' in coord,
      'probation cap wired':'championHealthSize' in coord and 'championHealth.liveSizeMultiplier.toDouble()' in coord,
      'system test read only':'M10 Champion Degradation' in ctl and 'researchIntelligence.championHealth' in ctl,
      'watch regression':'earlyNegativeEvidenceEntersWatch' in tests,
      'probation regression':'sustainedNegativeEvidenceEntersProbation' in tests,
      'disable regression':'statisticallyHarmfulChampionDisablesLive' in tests,
      'short span regression':'oneBadDayCannotStatisticallyDisable' in tests,
      'recovery regression':'stronglyRecoveredProbationReturnsHealthy' in tests,
      'no self reenable regression':'liveDisabledDoesNotSelfReenable' in tests,
    }
    failed=[]
    for n,ok in checks.items():
        print(('PASS' if ok else 'FAIL')+' | '+n)
        if not ok: failed.append(n)
    if failed: raise SystemExit('M10 champion degradation verification failed: '+', '.join(failed))
    print('\nPASS | M10 champion degradation / rollback contracts satisfied.')
if __name__=='__main__': main()
