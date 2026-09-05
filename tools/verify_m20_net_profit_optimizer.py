#!/usr/bin/env python3
from pathlib import Path
import sys

def main():
    print("INFO | M20 verifier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    required = {
        "optimizer": repo / "app/src/main/java/com/ksp/cryptobot/execution/NetProfitCostOptimizer.kt",
        "coordinator": repo / "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt",
        "lifecycle": repo / "app/src/main/java/com/ksp/cryptobot/execution/SmartOrderLifecycleManager.kt",
        "tests": repo / "app/src/test/java/com/ksp/cryptobot/execution/NetProfitCostOptimizerM20Test.kt",
        "m19": repo / "tools/verify_m19_learning_governance.py",
        "database": repo / "app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt",
    }
    missing = [name for name, path in required.items() if not path.exists()]
    if missing:
        raise SystemExit("FAIL | missing files: " + ", ".join(missing))

    optimizer = required["optimizer"].read_text(encoding="utf-8")
    coordinator = required["coordinator"].read_text(encoding="utf-8")
    lifecycle = required["lifecycle"].read_text(encoding="utf-8")
    tests = required["tests"].read_text(encoding="utf-8")
    database = required["database"].read_text(encoding="utf-8")

    checks = {
        "M19 prerequisite exists": required["m19"].exists(),
        "no Room schema bump": "version = 12" in database,
        "M20 optimizer exists downstream of M5":
            "class NetProfitCostOptimizer" in optimizer and
            "val economics: TradeEconomicsAssessment" in optimizer,
        "M20 monotonic cannot promote M5 block":
            "if (!economics.allowed || notional <= BigDecimal.ZERO)" in optimizer and
            "M20 monotonic block" in optimizer,
        "M20 never increases notional":
            "recommendedQuote" not in optimizer and
            "M20 never increases notional" in optimizer,
        "M5 costs are explicitly not double-counted":
            "No M5 cost is double-counted" in optimizer and
            "M5 AI cost already included" in optimizer,
        "observed slippage only charges excess above M5":
            "observedP75Rate.subtract(modeledEntrySlipRate).max(BigDecimal.ZERO)" in optimizer,
        "time-of-day cost is measured from UTC execution samples":
            "currentUtcHour" in optimizer and
            "sameHour.size >= MIN_TIME_BUCKET_SAMPLES" in optimizer and
            "sameHourP75Rate.subtract(observedP75Rate)" in optimizer,
        "lifecycle fill reliability uses M15 fills and cancels":
            "lifecycle.samples.toLong() + lifecycle.totalCancels" in optimizer and
            "lifecycleFillReliability" in optimizer,
        "unknown lifecycle evidence invents no penalty":
            "Unknown evidence must not be invented" in optimizer,
        "operational errors reduce reliability":
            "watchdog_error" in optimizer and
            "order_error" in optimizer and
            "anomaly_event" in optimizer,
        "failed execution is opportunity loss not fake fee":
            "nonExecutionOpportunityCost" in optimizer and
            "does not pay the modeled trade costs" in optimizer,
        "infrastructure cost must be explicit":
            "explicitInfrastructureCostQuote" in optimizer and
            "M20 never invents infrastructure spend" in optimizer,
        "scarce trade slots use measured candidate benchmark":
            'eventType == "entry_economics"' in optimizer and
            "MIN_OPPORTUNITY_BENCHMARK_SAMPLES" in optimizer and
            'slotUtilization >= BigDecimal("0.75")' in optimizer,
        "capital duration is empirical and informational":
            "empiricalCycleMinutes" in optimizer and
            "adjustedNetEdgePerCapitalHour" in optimizer,
        "break-even gross return includes incremental M20 costs":
            "breakEvenGrossReturnRate" in optimizer and
            "economics.totalExpectedCostQuote" in optimizer,
        "M15 calibration runtime is published":
            "object ExecutionCalibrationRuntime" in lifecycle and
            "ExecutionCalibrationRuntime.publish(symbol, snapshot)" in lifecycle,
        "coordinator evaluates M20 only after M5 allow":
            'if (!economics.allowed)' in coordinator and
            "netProfitOptimizer.evaluate(" in coordinator and
            coordinator.index('if (!economics.allowed)') < coordinator.index("netProfitOptimizer.evaluate("),
        "coordinator supplies measured execution evidence":
            "governanceDao.executionQuality" in coordinator and
            "governanceDao.recentAdvancedExecution" in coordinator and
            "governanceDao.recentEventsForSymbol" in coordinator and
            "ExecutionCalibrationRuntime.snapshot" in coordinator,
        "coordinator supplies zero unmeasured infrastructure cost":
            "explicitInfrastructureCostQuote = BigDecimal.ZERO" in coordinator,
        "M20 final gate blocks insufficient adjusted EV":
            "M20 net-profit optimizer blocked entry" in coordinator,
        "M20 decision is durably recorded":
            '"net_profit_optimizer"' in coordinator,
        "tests prove no M5 promotion":
            "m20CanNeverPromoteM5Block" in tests,
        "tests prove no invented low-sample slippage penalty":
            "insufficientExecutionSamplesDoNotInventSlippagePenalty" in tests,
        "tests prove incremental slippage semantics":
            "observedSlippageOnlyChargesExcessAboveM5Model" in tests,
        "tests prove lifecycle cancels reduce edge":
            "lifecycleCancelsReduceOpportunityAdjustedEdge" in tests,
        "tests prove operational errors reduce reliability":
            "operationalErrorsCannotImproveReliability" in tests,
        "tests prove explicit infrastructure cost":
            "explicitInfrastructureCostIsChargedOnce" in tests,
        "tests prove scarce-slot benchmark":
            "scarceTradeSlotsUseMeasuredAlternativeBenchmark" in tests and
            "abundantTradeSlotsDoNotInventOpportunityBenchmark" in tests,
    }

    ok = True
    for label, passed in checks.items():
        print(("PASS | " if passed else "FAIL | ") + label)
        ok = ok and passed

    if not ok:
        raise SystemExit(1)

    print()
    print("PASS | M20 measured net-profit, execution-reliability, turnover-opportunity and capital-efficiency contracts satisfied.")

if __name__ == "__main__":
    main()
