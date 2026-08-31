#!/usr/bin/env python3
from pathlib import Path
import os, sys

PAYLOAD_FILES = [
    "app/src/main/java/com/ksp/cryptobot/strategy/StrategyTruthRegistry.kt",
    "app/src/main/java/com/ksp/cryptobot/strategy/StrategyTruthRules.kt",
    "app/src/main/java/com/ksp/cryptobot/strategy/MultiStrategyEngine.kt",
    "app/src/main/java/com/ksp/cryptobot/backtest/BacktestEngine.kt",
    "app/src/test/java/com/ksp/cryptobot/strategy/StrategyTruthRegistryTest.kt",
    "app/src/test/java/com/ksp/cryptobot/strategy/StrategyTruthRulesTest.kt",
    "app/src/test/java/com/ksp/cryptobot/backtest/BacktestTruthGateM18Test.kt",
    "app/src/test/java/com/ksp/cryptobot/intelligence/AiDecisionTruthGateM18Test.kt",
    "app/src/test/java/com/ksp/cryptobot/strategy/RecommendationTruthGateM18Test.kt",
]

def fail(msg):
    raise SystemExit("ERROR | " + msg)

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

def main():
    print("INFO | M18 applier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app tree:\n" + dirty)

    payload = Path(__file__).resolve().parent / "m18_payload"
    for rel in PAYLOAD_FILES:
        src = payload / rel
        dst = repo / rel
        if not src.exists():
            fail(f"M18 payload missing: {rel}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(src.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
        copied = dst.read_bytes()
        if copied.endswith(b"\\n"):
            fail(f"M18 payload copy produced literal backslash-n EOF: {rel}")
        if not copied.endswith(b"\n"):
            fail(f"M18 payload copy missing real newline EOF: {rel}")
        print("WRITE |", rel)

    # RecommendationEngine must respect the strategy's entry permission. A score boost
    # may rank/confirm an existing entry, but cannot turn WAIT/WATCH/AVOID into BUY.
    p = repo / "app/src/main/java/com/ksp/cryptobot/strategy/RecommendationEngine.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''            val action = when {
                risk > BigDecimal("15") -> SignalAction.WAIT
                selected.action == SignalAction.SELL -> SignalAction.SELL
                finalScore >= settings.minStrategyScoreToBuy + 10 -> SignalAction.BUY
                finalScore >= settings.minStrategyScoreToBuy -> SignalAction.SMALL_BUY
                finalScore >= 58 -> SignalAction.WATCH
                finalScore >= 45 -> SignalAction.WAIT
                else -> SignalAction.AVOID
            }
''',
        '''            val selectedEntryAllowed =
                selected.action == SignalAction.BUY || selected.action == SignalAction.SMALL_BUY
            val action = when {
                risk > BigDecimal("15") -> SignalAction.WAIT
                selected.action == SignalAction.SELL -> SignalAction.SELL
                !selectedEntryAllowed -> selected.action
                selected.action == SignalAction.SMALL_BUY &&
                    finalScore >= settings.minStrategyScoreToBuy -> SignalAction.SMALL_BUY
                selected.action == SignalAction.BUY &&
                    finalScore >= settings.minStrategyScoreToBuy + 10 -> SignalAction.BUY
                selected.action == SignalAction.BUY &&
                    finalScore >= settings.minStrategyScoreToBuy -> SignalAction.SMALL_BUY
                finalScore >= 58 -> SignalAction.WATCH
                finalScore >= 45 -> SignalAction.WAIT
                else -> SignalAction.AVOID
            }
''',
        "M18 RecommendationEngine entry-permission gate"
    )

    t = replace_once(
        t,
        '''        val fallbackAction = when {
            risk > BigDecimal("12") -> SignalAction.WAIT
            fallbackScore >= 75 -> SignalAction.SMALL_BUY
            fallbackScore >= 60 -> SignalAction.WATCH
            fallbackScore >= 45 -> SignalAction.WAIT
            else -> SignalAction.AVOID
        }

        return Recommendation(
            symbol = ticker.symbol,
            action = fallbackAction,
            score = fallbackScore,
            riskPercent = risk,
            taxWarning = taxEstimate.warning,
            reason = "Fallback momentum mode: 24h momentum=${ticker.priceChangePercent24h}%, estimated risk=$risk%, spread=${riskEngine.spreadPercent(ticker)}%."
        )
''',
        '''        val fallbackAction = when {
            risk > BigDecimal("12") -> SignalAction.WAIT
            fallbackScore >= 60 -> SignalAction.WATCH
            fallbackScore >= 45 -> SignalAction.WAIT
            else -> SignalAction.AVOID
        }

        return Recommendation(
            symbol = ticker.symbol,
            action = fallbackAction,
            score = fallbackScore,
            riskPercent = risk,
            taxWarning = taxEstimate.warning,
            reason = "M18 non-trading fallback: no truth-validated strategy candidate/candle set is available. 24h momentum=${ticker.priceChangePercent24h}%, estimated risk=$risk%, spread=${riskEngine.spreadPercent(ticker)}%. Fallback may observe but cannot authorize BUY."
        )
''',
        "M18 RecommendationEngine non-trading fallback"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # AI is supplementary: it can reduce/veto a truth-valid entry but cannot create
    # entry permission or upgrade SMALL_BUY to BUY.
    p = repo / "app/src/main/java/com/ksp/cryptobot/intelligence/AiDecisionEngine.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''        val action = when {
            newsScore <= -25 -> SignalAction.WAIT
            recommendation.riskPercent.toDouble() >= 15.0 -> SignalAction.WAIT
            finalScore >= 78 -> SignalAction.BUY
            finalScore >= 68 -> SignalAction.SMALL_BUY
            finalScore >= 55 -> SignalAction.WATCH
            finalScore >= 45 -> SignalAction.WAIT
            else -> SignalAction.AVOID
        }
''',
        '''        val strategyEntryAllowed =
            recommendation.action == SignalAction.BUY ||
                recommendation.action == SignalAction.SMALL_BUY
        val action = when {
            recommendation.action == SignalAction.SELL -> SignalAction.SELL
            !strategyEntryAllowed -> recommendation.action
            newsScore <= -25 -> SignalAction.WAIT
            recommendation.riskPercent.toDouble() >= 15.0 -> SignalAction.WAIT
            recommendation.action == SignalAction.SMALL_BUY && finalScore >= 68 ->
                SignalAction.SMALL_BUY
            recommendation.action == SignalAction.BUY && finalScore >= 78 ->
                SignalAction.BUY
            recommendation.action == SignalAction.BUY && finalScore >= 68 ->
                SignalAction.SMALL_BUY
            finalScore >= 55 -> SignalAction.WATCH
            finalScore >= 45 -> SignalAction.WAIT
            else -> SignalAction.AVOID
        }
''',
        "M18 AI monotonic entry gate"
    )
    t = replace_once(
        t,
        '''            append("Technical=${technicalScore}, news=${newsScore}, newsArticles=${news.size}, memory=${memoryScore}, collective=${collective.adjustment}, final=${finalScore}. ")
''',
        '''            append("Technical=${technicalScore}, news=${newsScore}, newsArticles=${news.size}, memory=${memoryScore}, collective=${collective.adjustment}, final=${finalScore}, strategyGate=${recommendation.action}. ")
''',
        "M18 AI truth-gate explanation"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # Remove misleading legacy label; mechanics remain the same defined MTF scalper.
    p = repo / "app/src/main/java/com/ksp/cryptobot/strategy/MultiTimeframeScalpingStrategy.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '"Recovered scalping strategy: ${trendAgreement}/3 timeframe trend agreement, score=$score, TP≈$takeProfitPercent%, SL≈$stopLossPercent%. ${notes.joinToString("; ")}"',
        '"Defined MTF EMA/OBV scalper: ${trendAgreement}/3 timeframe trend agreement, score=$score, TP≈$takeProfitPercent%, SL≈$stopLossPercent%. ${notes.joinToString("; ")}"',
        "M18 scalper truth label"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    actual = changed | untracked
    allowed = set(PAYLOAD_FILES) | {
        "app/src/main/java/com/ksp/cryptobot/strategy/RecommendationEngine.kt",
        "app/src/main/java/com/ksp/cryptobot/intelligence/AiDecisionEngine.kt",
        "app/src/main/java/com/ksp/cryptobot/strategy/MultiTimeframeScalpingStrategy.kt",
    }
    if actual - allowed:
        fail("Unexpected M18 app changes: " + ",".join(sorted(actual - allowed)))
    if allowed - actual:
        fail("Expected M18 changes missing: " + ",".join(sorted(allowed - actual)))

    print("PASS | M18 controlled app diff.")

if __name__ == "__main__":
    main()
