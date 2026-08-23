#!/usr/bin/env python3
from pathlib import Path
import sys

def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""

def main():
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    economics = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/TradeEconomicsEngine.kt")
    advanced = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt")
    research = read(repo / "app/src/main/java/com/ksp/cryptobot/research/ResearchHandoffCostRiskEngine.kt")
    controller = read(repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    exchange = read(repo / "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt")
    tests = read(repo / "app/src/test/java/com/ksp/cryptobot/execution/TradeEconomicsEngineTest.kt")

    checks = {
        "authoritative TradeEconomicsEngine": "class TradeEconomicsEngine" in economics,
        "EV formula includes win probability": "pWin.multiply(expectedWin).subtract(pLoss.multiply(expectedLoss))" in economics,
        "realized probability with neutral prior": "PRIOR_WINS" in economics and "PRIOR_SAMPLES" in economics and "NEUTRAL_PRIOR" in economics,
        "AI confidence is not win probability": "confidencePercent" not in economics and "finalScore" not in economics,
        "maker only for post-only LIMIT": "input.postOnly && input.orderType == OrderType.LIMIT" in economics,
        "live fee schedule preferred": "input.feeSchedule?.let" in economics,
        "Kraken tier1 fallback maker 40bps": 'BigDecimal("0.0040")' in economics,
        "Kraken tier1 fallback taker 80bps": 'BigDecimal("0.0080")' in economics,
        "entry fee modeled": "entryFeeQuote" in economics and "notional.multiply(entryFeeRate)" in economics,
        "expected exit fee modeled": "expectedExitFeeQuote" in economics and "expectedExitNotional.multiply(exitFeeRate)" in economics,
        "spread modeled": "spreadCostQuote" in economics and "spreadRate(input.ticker)" in economics,
        "entry slippage modeled": "entrySlippageQuote" in economics and "depthSlippageRate(" in economics,
        "expected exit slippage modeled": "expectedExitSlippageQuote" in economics,
        "future AI cost modeled": "externalDecisionCostQuote" in economics,
        "model-risk safety reserve": 'BigDecimal("0.0025")' in economics and "safetyReserveQuote" in economics,
        "net EV after all costs": "val netEv = grossEv.subtract(totalCosts)" in economics,
        "break-even probability modeled": "breakEvenWinProbability" in economics,
        "risk reward modeled": "riskRewardRatio" in economics,
        "runtime economics snapshot": "object TradeEconomicsRuntime" in economics,
        "advanced execution uses central economics": "private val tradeEconomics = TradeEconomicsEngine()" in advanced and "TradeEconomicsInput(" in advanced,
        "old advanced roundTripCostGate removed": "roundTripCostGate(" not in advanced,
        "research cost gate uses same engine": "private val tradeEconomics = TradeEconomicsEngine()" in research and "TradeEconomicsInput(" in research,
        "final execution retrieves account fee schedule": "advancedFeeSchedule = runCatching { exchange.getTradingFeeSchedule(ticker.symbol) }" in controller,
        "final execution receives fee schedule": "feeSchedule = advancedFeeSchedule" in controller,
        "M5 economics governance event": '"entry_economics"' in advanced and '"positive_net_ev"' in advanced,
        "non-positive EV blocks entry": "M5 trade economics blocked entry" in advanced,
        "M4 research BUY bypass closed in controller": 'request.side == OrderSide.BUY\n        ) {' in controller and 'request.purpose.equals("ENTRY"' not in controller,
        "M4 research BUY bypass closed in Kraken": 'if (request.side == OrderSide.BUY) {' in exchange and 'request.side == OrderSide.BUY && request.purpose.equals("ENTRY"' not in exchange,
        "low-edge Tier1 regression": "tierOneTakerEconomicsBlocksSmallTwoPercentTargetAtNeutralPrior" in tests,
        "strong reward-risk positive EV regression": "strongRewardRiskWithMakerEntryCanRemainPositiveAfterAllCosts" in tests,
        "decision-cost regression": "externalDecisionCostIsPartOfExpectedValue" in tests,
        "maker-vs-taker regression": "makerFeeIsUsedOnlyForExplicitPostOnlyLimit" in tests,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        raise SystemExit("M5 trade-economics verification failed: " + ", ".join(failed))

    print("\nPASS | M5 authoritative net expected-value contracts satisfied.")

if __name__ == "__main__":
    main()
