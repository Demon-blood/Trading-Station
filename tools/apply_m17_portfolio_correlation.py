#!/usr/bin/env python3
from pathlib import Path
import os, sys

PAYLOAD_FILES = [
    "app/src/main/java/com/ksp/cryptobot/execution/PortfolioCorrelationEngine.kt",
    "app/src/main/java/com/ksp/cryptobot/execution/PortfolioAllocationEngine.kt",
    "app/src/test/java/com/ksp/cryptobot/execution/PortfolioCorrelationMathTest.kt",
    "app/src/test/java/com/ksp/cryptobot/execution/PortfolioAllocationM17Test.kt",
]

def fail(msg):
    raise SystemExit("ERROR | " + msg)

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

def main():
    print("INFO | M17 applier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app tree:\n" + dirty)

    payload = Path(__file__).resolve().parent / "m17_payload"
    for rel in PAYLOAD_FILES:
        src = payload / rel
        dst = repo / rel
        if not src.exists():
            fail(f"M17 payload missing: {rel}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(src.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
        copied = dst.read_bytes()
        if copied.endswith(b"\\n"):
            fail(f"M17 payload copy produced literal backslash-n EOF: {rel}")
        if not copied.endswith(b"\n"):
            fail(f"M17 payload copy missing real newline EOF: {rel}")
        print("WRITE |", rel)

    p = repo / "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''    private val portfolioAllocation = PortfolioAllocationEngine()
''',
        '''    private val portfolioAllocation = PortfolioAllocationEngine()
    private val portfolioCorrelation = PortfolioCorrelationEngine()
''',
        "M17 coordinator correlation engine field"
    )

    t = replace_once(
        t,
        '''        currentUseMarket: Boolean,
        feeSchedule: TradingFeeSchedule? = null
''',
        '''        currentUseMarket: Boolean,
        feeSchedule: TradingFeeSchedule? = null,
        exchange: CryptoExchangeClient? = null
''',
        "M17 prepareEntry exchange parameter"
    )

    t = replace_once(
        t,
        '''        val trades = appDao.recentTradesSnapshot(500)
        val positions = appDao.openPositionsSnapshot()
        val protection = capitalProtection.evaluate(settings, trades, mode)
''',
        '''        val trades = appDao.recentTradesSnapshot(500)
        val positions = appDao.openPositionsSnapshot()

        var portfolioCorrelationContext: PortfolioCorrelationContext? = null
        if (settings.portfolioBalancerEnabled && exchange != null) {
            val portfolioContextResult = runCatching {
                portfolioCorrelation.assess(
                    settings = settings,
                    exchange = exchange,
                    candidateSymbol = decision.symbol,
                    requestedQuote = researchCappedQuote,
                    positions = positions
                )
            }
            if (portfolioContextResult.isFailure) {
                val error = portfolioContextResult.exceptionOrNull()
                val reason = "M17 portfolio context unavailable: ${error?.message ?: error?.javaClass?.simpleName ?: "unknown"}. LIVE entry fails closed because account-level reserve/exposure state is not authoritative."
                record("portfolio_correlation", decision.symbol, settings, mode, requestedQuote, BigDecimal.ZERO, BigDecimal.ZERO,
                    "", "context_unavailable", sizeBand(requestedQuote), "", "blocked", settings.mode != BotMode.PAPER, reason,
                    if (settings.mode == BotMode.PAPER) "WARN" else "HIGH")
                if (settings.mode != BotMode.PAPER) {
                    return AdvancedEntryPlan(false, BigDecimal.ZERO, OrderType.LIMIT, ticker.ask, BigDecimal.ZERO, 0, reason)
                }
            } else {
                portfolioCorrelationContext = portfolioContextResult.getOrNull()
                portfolioCorrelationContext?.let { context ->
                    record("portfolio_correlation", decision.symbol, settings, mode, requestedQuote, requestedQuote,
                        context.correlationMultiplier, "", "correlation_context", sizeBand(requestedQuote), "",
                        "normal", false, context.reason, "INFO")
                }
            }
        }

        val protection = capitalProtection.evaluate(settings, trades, mode)
''',
        "M17 coordinator correlation context"
    )

    t = replace_once(
        t,
        '''        val allocation = portfolioAllocation.allocate(settings, decision, afterProduction, trades, positions)
''',
        '''        val allocation = portfolioAllocation.allocate(
            settings = settings,
            decision = decision,
            requestedQuote = afterProduction,
            recentTrades = trades,
            positions = positions,
            correlation = portfolioCorrelationContext
        )
''',
        "M17 coordinator correlation allocation"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''                currentUseMarket = useMarketOrder,
                feeSchedule = advancedFeeSchedule
''',
        '''                currentUseMarket = useMarketOrder,
                feeSchedule = advancedFeeSchedule,
                exchange = exchange
''',
        "M17 BotController exchange handoff"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    actual = changed | untracked
    allowed = set(PAYLOAD_FILES) | {
        "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt",
        "app/src/main/java/com/ksp/cryptobot/core/BotController.kt",
    }
    if actual - allowed:
        fail("Unexpected M17 app changes: " + ",".join(sorted(actual - allowed)))
    if allowed - actual:
        fail("Expected M17 changes missing: " + ",".join(sorted(allowed - actual)))

    print("PASS | M17 controlled app diff.")

if __name__ == "__main__":
    main()
