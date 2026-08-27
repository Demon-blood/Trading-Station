#!/usr/bin/env python3
from pathlib import Path
import sys

def read(p): return p.read_text(encoding="utf-8") if p.exists() else ""

def main():
    repo=Path(sys.argv[1] if len(sys.argv)>1 else ".").resolve()
    engine=read(repo/"app/src/main/java/com/ksp/cryptobot/research/StrategyChampionChallengerEngine.kt")
    coord=read(repo/"app/src/main/java/com/ksp/cryptobot/research/ResearchCoordinator.kt")
    controller=read(repo/"app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    tests=read(repo/"app/src/test/java/com/ksp/cryptobot/research/StrategyChampionChallengerEngineTest.kt")
    db=read(repo/"app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt")

    checks={
      "M9 engine present":"class StrategyChampionChallengerEngine" in engine,
      "no Room schema bump":"version = 12" in db,
      "exact strategy tags only":"strategyTagged(it.aiReason" in engine,
      "closing outcomes only":'.filter { it.side.equals("SELL", true) }' in engine,
      "conservative entry fee reserve":"val conservativeNet = realized.subtract(fee)" in engine,
      "minimum 30 exact":"MIN_EXACT_OUTCOMES = 30" in engine,
      "minimum 20 paper":"MIN_PAPER_OUTCOMES = 20" in engine,
      "minimum 10 OOS":"MIN_OOS_OUTCOMES = 10" in engine,
      "seven day evidence":"MIN_EVIDENCE_SPAN_MS = 7L" in engine,
      "two regimes":"MIN_REGIMES = 2" in engine,
      "seven day promotion cooldown":"PROMOTION_COOLDOWN_MS = 7L" in engine,
      "chronological holdout":"val test = if (split > 0) rows.drop(split)" in engine,
      "positive OOS net":"c.oosNetPnlQuote >= MIN_OOS_NET_PNL" in engine,
      "positive lower95":"c.lower95Return > BigDecimal.ZERO" in engine,
      "profit factor gate":"c.oosProfitFactor >= MIN_PROFIT_FACTOR" in engine,
      "drawdown gate":"c.oosMaxDrawdownRate <= hardDd" in engine,
      "walk forward gate":'walkForward.status == "PASS"' in engine and "walkForward.score >= 60.0" in engine,
      "Monte Carlo gate":"monteCarlo.score >= 60.0" in engine and "monteCarlo.probabilityPositive >= 0.65" in engine,
      "statistical superiority":"diff.lower95Difference > BigDecimal.ZERO" in engine,
      "minimum superiority effect":"diff.meanDifference >= MIN_SUPERIORITY_EFFECT" in engine,
      "no PF regression":"c.oosProfitFactor >= p.oosProfitFactor" in engine,
      "no drawdown regression":"c.oosMaxDrawdownRate <= championDdAllowance" in engine,
      "no validation regression":"noValidationRegression" in engine,
      "persistent champion state":'"m9_champion:$s"' in engine,
      "promotion audit event":'eventType = "m9_strategy_promotion"' in engine,
      "current champion remains authorized":"StrategyGovernanceAction.KEEP_CHAMPION" in engine and "productionAuthorized" in engine,
      "governed handoff strategy":"val governedStrategy=handoffEntry?.definition?.id ?: strategy" in coord,
      "M9 evaluates selected strategy":"championChallenger.evaluateAndMaybePromote(" in coord,
      "PAPER trials stay allowed":"settings.mode==BotMode.PAPER || strategyGovernance.productionAuthorized" in coord,
      "handoff LIVE champion gate":"handoffModeEligible && (settings.mode==BotMode.PAPER || strategyGovernance.productionAuthorized)" in coord,
      "generic research LIVE champion gate":'canPromote && regime.risk!="RISK_OFF" && (settings.mode==BotMode.PAPER || strategyGovernance.productionAuthorized)' in coord,
      "protective handoff untouched":"val handoffProtective=handoffResult.protectiveAction" in coord,
      "M9 state in explanation":"val governanceLine=" in coord and "${strategyGovernance.action}" in coord,
      "read only system visibility":"M9 Strategy Champion/Challenger" in controller and "strategyChampion(primarySymbol)" in controller,
      "initial champion regression":"strongExactPaperEvidenceCanCreateInitialChampion" in tests,
      "paper evidence regression":"paperEvidenceIsMandatoryForFirstChampion" in tests,
      "regime regression":"oneRegimeCannotPromote" in tests,
      "champion continuity regression":"currentChampionRemainsAuthorizedWithoutAutomaticDemotion" in tests,
      "superiority regression":"challengerMustProveStatisticalSuperiority" in tests,
      "overlap rejection regression":"higherAverageWithOverlappingConfidenceDoesNotReplaceChampion" in tests,
      "no borrowed profit regression":"exactOutcomeExtractorDoesNotBorrowAnotherStrategysProfit" in tests,
      "churn cooldown regression":"promotionCooldownPreventsChurn" in tests,
    }
    failed=[]
    for name,ok in checks.items():
        print(("PASS" if ok else "FAIL")+" | "+name)
        if not ok: failed.append(name)
    if failed: raise SystemExit("M9 champion/challenger verification failed: "+", ".join(failed))
    print("\nPASS | M9 champion/challenger contracts satisfied.")

if __name__=="__main__": main()
