#!/usr/bin/env python3
from pathlib import Path
import sys

def read(path):
    p = Path(path)
    return p.read_text(encoding="utf-8") if p.exists() else ""

def main():
    print("INFO | M18 verifier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    registry = read(repo / "app/src/main/java/com/ksp/cryptobot/strategy/StrategyTruthRegistry.kt")
    rules = read(repo / "app/src/main/java/com/ksp/cryptobot/strategy/StrategyTruthRules.kt")
    multi = read(repo / "app/src/main/java/com/ksp/cryptobot/strategy/MultiStrategyEngine.kt")
    recommendation = read(repo / "app/src/main/java/com/ksp/cryptobot/strategy/RecommendationEngine.kt")
    ai = read(repo / "app/src/main/java/com/ksp/cryptobot/intelligence/AiDecisionEngine.kt")
    scalper = read(repo / "app/src/main/java/com/ksp/cryptobot/strategy/MultiTimeframeScalpingStrategy.kt")
    backtest = read(repo / "app/src/main/java/com/ksp/cryptobot/backtest/BacktestEngine.kt")
    registry_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/strategy/StrategyTruthRegistryTest.kt")
    rule_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/strategy/StrategyTruthRulesTest.kt")
    backtest_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/backtest/BacktestTruthGateM18Test.kt")
    ai_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/intelligence/AiDecisionTruthGateM18Test.kt")
    recommendation_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/strategy/RecommendationTruthGateM18Test.kt")
    db = read(repo / "app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt")

    checks = {
        "no Room schema bump":
            "version = 12" in db,

        "truth availability distinguishes missing data from missing architecture":
            "DATA_REQUIRED" in registry and
            "ARCHITECTURE_REQUIRED" in registry and
            "MULTITIMEFRAME_ONLY" in registry,
        "all proxy-prone strategies are explicitly blocked":
            "StrategyMode.RANGE_GRID" in registry and
            "StrategyMode.MARKET_MAKING_IMBALANCE" in registry and
            "StrategyMode.FUNDING_NEWS_RISK_OFF" in registry and
            "StrategyMode.PAIRS_RELATIVE_STRENGTH" in registry and
            "StrategyMode.DCA_CRASH_PROTECTION" in registry and
            "StrategyMode.VOLUME_ANOMALY_WHALE_MOVE" in registry,
        "market making truth requires two-sided architecture":
            "Simultaneously quote passive bid and offer" in registry and
            "two-sided quote/inventory engine" in registry,
        "pairs truth requires benchmark series":
            "benchmark/peer return series" in registry and
            "absolute momentum, not pairs relative strength" in registry,
        "DCA truth requires durable tranche state":
            "scheduled/tranche state" in registry and
            "dip score cannot be called DCA" in registry,
        "news momentum requires actual event feed":
            "timestamped news/event feed" in registry and
            "24h price change is not a news strategy" in registry,
        "whale attribution cannot come from candle volume alone":
            "Candle volume alone cannot truthfully attribute activity to whales" in registry,
        "auto only selects liveSelectable truth specs":
            "specs.values.filter { it.liveSelectable }" in registry,

        "shared rule engine covers implemented candle strategies":
            "StrategyMode.TREND -> trend(" in rules and
            "StrategyMode.BREAKOUT -> breakout(" in rules and
            "StrategyMode.REVERSAL -> reversal(" in rules and
            "StrategyMode.MEAN_REVERSION_RSI_BOLLINGER -> meanReversion(" in rules and
            "StrategyMode.VWAP_PULLBACK -> vwapPullback(" in rules and
            "StrategyMode.DONCHIAN_BREAKOUT -> donchian(" in rules and
            "StrategyMode.MOMENTUM_SPIKE_CONTINUATION -> momentumContinuation(" in rules,
        "breakout excludes current bar from resistance":
            "c.dropLast(1).takeLast(20)" in rules,
        "breakout requires volume confirmation":
            'avgVol.multiply(BigDecimal("1.25"))' in rules,
        "reversal requires oversold then bullish reclaim":
            'prevRsi <= BigDecimal("30")' in rules and
            "bullishConfirm" in rules,
        "mean reversion requires band re-entry not touch alone":
            "stretched" in rules and
            "reentered" in rules and
            "rsi > prevRsi" in rules,
        "VWAP pullback requires trend and reclaim":
            "e21 > e55" in rules and
            "pulledBack" in rules and
            "reclaim" in rules,
        "Donchian uses prior 20 entry and prior 10 exit":
            "highestHigh(c.dropLast(1), 20)" in rules and
            "lowestLow(candles.dropLast(1), 10)" in rules,
        "momentum continuation separates impulse from follow-through":
            "val impulse = c[c.lastIndex - 1]" in rules and
            "val continuation = c.last()" in rules and
            "genuineImpulse" in rules and
            "followThrough" in rules,

        "AUTO cannot rank truth-blocked strategies":
            "StrategyTruthRegistry.autoSelectable()" in multi and
            "if (!spec.liveSelectable)" in multi,
        "collective data is tie-break only":
            "Collective tie-break hint only; it cannot bypass M18 truth/entry gates." in multi,
        "legacy fake market-making proxy removed":
            "marketMakingImbalanceCandidate" not in multi and
            "Market-making imbalance proxy" not in multi,
        "legacy fake pairs proxy removed":
            "pairsRelativeStrengthCandidate" not in multi and
            "Relative-strength rotation" not in multi,
        "legacy fake DCA proxy removed":
            "dcaCrashProtectionCandidate" not in multi,
        "legacy fake funding-news proxy removed":
            "fundingNewsRiskOffCandidate" not in multi,
        "scalper label is defined not recovered":
            "Defined MTF EMA/OBV scalper" in scalper and
            "Recovered scalping strategy:" not in scalper,

        "recommendation cannot promote non-entry by score":
            "selectedEntryAllowed" in recommendation and
            "!selectedEntryAllowed -> selected.action" in recommendation,
        "fallback can no longer authorize SMALL_BUY":
            "M18 non-trading fallback" in recommendation and
            "fallbackScore >= 75 -> SignalAction.SMALL_BUY" not in recommendation,
        "AI cannot create strategy entry permission":
            "strategyEntryAllowed" in ai and
            "!strategyEntryAllowed -> recommendation.action" in ai,
        "AI cannot upgrade SMALL_BUY to BUY":
            "recommendation.action == SignalAction.SMALL_BUY && finalScore >= 68" in ai and
            "SignalAction.SMALL_BUY" in ai,

        "backtest shares live truth rules":
            "StrategyTruthRules.evaluate(strategy, signalWindow, settings)" in backtest and
            "StrategyTruthRules.shouldExit(strategy, signalWindow, open.entryPrice, settings)" in backtest,
        "unsupported strategy cannot pass proxy backtest":
            "if (!spec.liveSelectable)" in backtest and
            "passedLiveGate = false" in backtest,
        "single-frame API blocks multi-timeframe scalper backtest":
            "if (!spec.singleTimeframeBacktestable)" in backtest,
        "backtest enforces canonical primary timeframe":
            "timeframe != spec.primaryTimeframe" in backtest,
        "signal enters next bar open":
            "val entryPrice = next.open" in backtest,
        "same-bar TP and SL resolves conservatively to stop":
            "same-bar TP+SL ambiguity resolved conservatively to stop" in backtest,
        "backtest charges baseline maker fees both sides":
            'BASELINE_MAKER_FEE_PER_SIDE_PERCENT = BigDecimal("0.40")' in backtest and
            'multiply(BigDecimal("2"))' in backtest,
        "backtest requires positive net return in addition to legacy gates":
            "net > BigDecimal.ZERO" in backtest,
        "backtest explicitly leaves production economics to M5/M20":
            "M5/M20 production economics remain final." in backtest,

        "registry tests cover every named mode":
            "everyNamedStrategyHasExplicitTruthSpec" in registry_tests,
        "registry tests ensure proxies excluded from auto":
            "autoOnlyContainsActuallyLiveSelectableStrategies" in registry_tests,
        "rule tests cover breakout no-current-bar semantics":
            "breakoutExcludesCurrentBarFromResistanceAndNeedsVolume" in rule_tests,
        "rule tests cover separate impulse/follow-through":
            "momentumContinuationRequiresSeparateImpulseAndFollowThroughBars" in rule_tests,
        "backtest tests prove proxy strategies cannot pass":
            "architectureRequiredGridCanNeverPassProxyBacktest" in backtest_tests and
            "pairsRelativeStrengthCannotBeBacktestedWithoutBenchmarkSeries" in backtest_tests,
        "AI tests prove no truth-gate promotion":
            "aiCannotPromoteTruthGatedWaitIntoBuy" in ai_tests and
            "aiCannotIncreaseSmallBuyIntoFullBuy" in ai_tests,
        "Recommendation tests prove score/fallback cannot create entry":
            "liquidityBoostCannotTurnNonEntrySetupIntoBuy" in recommendation_tests and
            "noTruthValidatedCandlesNeverUsesUnnamedFallbackToEnter" in recommendation_tests,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        raise SystemExit(
            "M18 strategy truth / research validation failed: " +
            ", ".join(failed)
        )

    print("\nPASS | M18 strategy truth, monotonic entry authority, and truth-aligned backtest contracts satisfied.")

if __name__ == "__main__":
    main()
