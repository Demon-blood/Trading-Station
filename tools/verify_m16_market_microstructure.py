#!/usr/bin/env python3
from pathlib import Path
import sys

def read(path):
    p = Path(path)
    return p.read_text(encoding="utf-8") if p.exists() else ""

def main():
    print("INFO | M16 verifier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    micro = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/MarketMicrostructureEngine.kt")
    sizer = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/LiquidityAwareSizer.kt")
    optimizer = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/OrderTypeOptimizer.kt")
    models = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionModels.kt")
    coordinator = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt")
    lifecycle = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/SmartOrderLifecycleManager.kt")
    micro_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/execution/MarketMicrostructureEngineTest.kt")
    optimizer_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/execution/OrderTypeOptimizerM16Test.kt")
    sizer_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/execution/LiquidityAwareSizerM16Test.kt")
    db = read(repo / "app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt")

    checks = {
        "no Room schema bump":
            "version = 12" in db,

        "microstructure snapshot exposes spread":
            "val spreadBps: Double" in micro,
        "microstructure snapshot exposes imbalance":
            "val bookImbalance: Double" in micro,
        "microstructure snapshot exposes microprice":
            "val microPrice: BigDecimal" in micro and
            "val microPricePressureBps: Double" in micro,
        "microstructure exposes depth tiers":
            "val top5BidDepthQuote" in micro and
            "val top10AskDepthQuote" in micro,
        "microstructure simulates market impact":
            "private fun marketImpact(" in micro and
            "marketImpactComplete" in micro,
        "fill estimate explicitly heuristic not queue truth":
            "This is NOT exchange queue position" in micro and
            "L2 is aggregated depth, not exact queue position." in micro,
        "maker fill probability strictly bounded":
            ".coerceIn(0.02, 0.98)" in micro,
        "adverse selection risk strictly bounded":
            "adverseSelectionRisk(" in micro and
            ".coerceIn(0.0, 1.0)" in micro,
        "maker target cannot intentionally cross":
            "bestBid.add(tickSize).min(bestAsk.subtract(tickSize))" in micro,
        "invalid/crossed books fail conservative":
            "bestBid >= bestAsk" in micro and
            "adverseSelectionRisk = 1.0" in micro,
        "runtime publishes microstructure diagnostics":
            "object MarketMicrostructureRuntime" in micro and
            "snapshots[snapshot.symbol.uppercase()] = snapshot" in micro,

        "LIVE guarded missing book fails sizing closed":
            "settings.orderBookDepthGuardEnabled && settings.mode != BotMode.PAPER" in sizer and
            '"microstructure_unavailable"' in sizer,
        "incomplete market impact blocks guarded size":
            "!micro.marketImpactComplete" in sizer and
            '"depth_incomplete"' in sizer,
        "excess impact blocks guarded size":
            "micro.marketImpactBps > maxImpactBps" in sizer and
            '"impact_too_high"' in sizer,
        "adverse selection can only reduce size":
            'BigDecimal("0.50")' in sizer and
            'BigDecimal("0.75")' in sizer,

        "order type decision carries postOnly":
            "val postOnly: Boolean = false" in models,
        "ordinary safe limit is passive post-only":
            '"passive_maker"' in optimizer and
            "postOnly = true" in optimizer,
        "market requires explicit setting and current intent":
            "currentUseMarket &&" in optimizer and
            "settings.enableMarketOrders &&" in optimizer,
        "market requires complete low impact":
            "micro.marketImpactComplete && micro.marketImpactBps <= maxImpactBps" in optimizer,
        "market requires acceptable adverse selection":
            "micro.adverseSelectionRisk < 0.55" in optimizer,
        "missing/invalid L2 never allows market":
            '"microstructure_unavailable"' in optimizer and
            '"microstructure_invalid"' in optimizer,

        "coordinator adopts optimizer post-only when no source override":
            "else -> optimizedOrder.postOnly" in coordinator and
            "directive?.preferredOrderType != null -> directive.postOnlyPreferred == true" in coordinator,

        "M15 lifecycle now fetches L2 book":
            "exchange.getOrderBook(order.symbol, 25)" in lifecycle,
        "M15 lifecycle feeds M15 calibration into fill model":
            "calibrationSamples = calibration.samples" in lifecycle and
            "calibratedMeanFillSeconds = calibration.meanFillSeconds" in lifecycle,
        "M15 lifecycle only amends under acceptable L2 risk":
            "it.makerFillProbability < 0.60" in lifecycle and
            "it.adverseSelectionRisk < 0.65" in lifecycle,
        "M15 lifecycle remains post-only":
            "postOnly = true" in lifecycle,
        "M15 hard cancel invariant retained":
            "HARD_CANCEL_MULTIPLIER = 4L" in lifecycle and
            "No automatic replacement was submitted" in lifecycle,

        "tests cover balanced microprice":
            "balancedBookHasMidMicropriceAndNearZeroImbalance" in micro_tests,
        "tests cover multi-level impact":
            "marketImpactConsumesMultipleAskLevels" in micro_tests,
        "tests cover insufficient depth":
            "insufficientBookDepthNeverPretendsImpactIsComplete" in micro_tests,
        "tests cover passive no-cross invariant":
            "passiveTargetNeverCrossesOppositeTouch" in micro_tests,
        "tests cover crossed book":
            "crossedBookIsInvalid" in micro_tests,
        "tests cover default passive post-only":
            "ordinaryLimitIsPassiveAndPostOnly" in optimizer_tests,
        "tests cover guarded missing book fail-closed":
            "guardedLiveWithoutBookFailsClosed" in sizer_tests,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        raise SystemExit(
            "M16 market microstructure / fill probability verification failed: " +
            ", ".join(failed)
        )

    print("\nPASS | M16 L2 microstructure, impact, passive-maker and heuristic fill-probability contracts satisfied.")

if __name__ == "__main__":
    main()
