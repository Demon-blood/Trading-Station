#!/usr/bin/env python3
from pathlib import Path
import sys

def read(path):
    p = Path(path)
    return p.read_text(encoding="utf-8") if p.exists() else ""

def main():
    print("INFO | M17 verifier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    corr = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/PortfolioCorrelationEngine.kt")
    alloc = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/PortfolioAllocationEngine.kt")
    coordinator = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt")
    controller = read(repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    math_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/execution/PortfolioCorrelationMathTest.kt")
    alloc_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/execution/PortfolioAllocationM17Test.kt")
    db = read(repo / "app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt")

    checks = {
        "no Room schema bump":
            "version = 12" in db,

        "correlation uses chronological H1 candles":
            "Timeframe.H1" in corr and
            "returnsByTimestamp" in corr and
            "openTimeEpochMs" in corr,
        "correlation requires at least 30 paired returns":
            "MIN_PAIRED_RETURNS = 30" in corr and
            "common.size < minSamples" in corr,
        "unknown correlation remains unknown":
            "Pair<Double?, Int>" in corr and
            "return null to common.size" in corr,
        "negative correlation is not concentration penalty":
            '.filter { it > 0.0 }' in corr,
        "correlation penalties are reduction only":
            'BigDecimal("0.40")' in corr and
            'BigDecimal("0.60")' in corr and
            'BigDecimal("0.80")' in corr and
            "BigDecimal.ONE" in corr,

        "portfolio equity uses exchange EUR values":
            "balance.eurValue > BigDecimal.ZERO" in corr,
        "EUR free cash is read separately":
            'normalizeAsset(balance.asset) == "EUR"' in corr and
            "balance.free.max(BigDecimal.ZERO)" in corr,
        "cash reserve honors existing absolute and percentage settings":
            "settings.minimumQuoteReserveAmount" in corr and
            "settings.minimumQuoteReservePercent" in corr and
            "settings.minimumEurReservePercent" in corr,
        "live asset EUR value is preferred over entry notional":
            "liveAssetValue" in corr and
            "entryPriceEur" in corr,
        "broad common factor cap is explicit":
            'COMMON_FACTOR_MAX_SHARE: BigDecimal = BigDecimal("0.70")' in corr,
        "factor grouping is explicitly separate from empirical correlation":
            '"BTC_CORE"' in corr and '"ETH_CORE"' in corr and '"ALT_RISK"' in corr,
        "same-factor missing data reduces rather than invents correlation":
            "sameFactorWithoutEvidence" in corr and
            'empiricalMultiplier.min(BigDecimal("0.80"))' in corr,

        "allocator can only start from requested capital":
            "var amount = requestedQuote" in alloc and
            ".min(requestedQuote)" in alloc,
        "allocator applies fresh cash cap":
            "amount = amount.min(context.availableNewSpendQuote)" in alloc,
        "allocator applies single asset remaining cap":
            "context.singleAssetRemainingQuote?.let { amount = amount.min(it) }" in alloc,
        "allocator applies common factor remaining cap":
            "context.factorRemainingQuote?.let { amount = amount.min(it) }" in alloc,
        "duplicate same symbol can be hard blocked":
            "M17 blocked duplicate open position" in alloc,
        "cash reserve exhaustion hard blocks":
            "M17 cash reserve blocks new spend" in alloc,
        "single asset exhaustion hard blocks":
            "single-asset allocation ceiling is already exhausted" in alloc,
        "factor exhaustion hard blocks":
            "common-factor allocation ceiling is already exhausted" in alloc,

        "coordinator receives exchange for account-aware portfolio context":
            "exchange: CryptoExchangeClient? = null" in coordinator and
            "portfolioCorrelation.assess(" in coordinator,
        "LIVE portfolio context failure is fail closed":
            "LIVE entry fails closed because account-level reserve/exposure state is not authoritative." in coordinator and
            "if (settings.mode != BotMode.PAPER)" in coordinator,
        "PAPER can continue when portfolio context read fails":
            'if (settings.mode == BotMode.PAPER) "WARN" else "HIGH"' in coordinator,
        "correlation decision is recorded":
            '"portfolio_correlation"' in coordinator and
            '"correlation_context"' in coordinator,
        "allocation consumes M17 context":
            "correlation = portfolioCorrelationContext" in coordinator,
        "controller passes selected exchange into execution coordinator":
            "feeSchedule = advancedFeeSchedule," in controller and
            "exchange = exchange" in controller,

        "tests cover high positive correlation":
            "stronglyAlignedReturnsProduceHighPositiveCorrelation" in math_tests,
        "tests cover unknown insufficient samples":
            "tooFewPairedReturnsStayUnknown" in math_tests,
        "tests cover reserve floor":
            "reserveUsesStricterAbsoluteOrPercentageFloor" in math_tests,
        "tests cover correlation reduction invariant":
            "correlationPenaltyNeverIncreasesRequestedSpend" in alloc_tests,
        "tests cover cash reserve hard block":
            "cashReserveCanHardBlockNewEntry" in alloc_tests,
        "tests cover factor cap":
            "factorRemainingCapsButNeverRaisesSpend" in alloc_tests,
        "tests cover duplicate open symbol":
            "duplicateOpenSymbolIsBlockedWhenProtectionEnabled" in alloc_tests,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        raise SystemExit(
            "M17 portfolio correlation / capital allocation verification failed: " +
            ", ".join(failed)
        )

    print("\nPASS | M17 account-aware capital allocation and correlation-risk contracts satisfied.")

if __name__ == "__main__":
    main()
