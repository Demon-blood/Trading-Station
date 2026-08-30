#!/usr/bin/env python3
from pathlib import Path
import sys

def read(path):
    p = Path(path)
    return p.read_text(encoding="utf-8") if p.exists() else ""

def main():
    print("INFO | M13 verifier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    private = read(repo / "app/src/main/java/com/ksp/cryptobot/exchange/KrakenPrivateExecutionState.kt")
    controller = read(repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    lifecycle = read(repo / "app/src/main/java/com/ksp/cryptobot/lifecycle/TradeLifecycleManager.kt")
    protection = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/ProtectiveStopManager.kt")
    partial = read(repo / "app/src/main/java/com/ksp/cryptobot/lifecycle/PartialFillSynchronizer.kt")
    id_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/exchange/KrakenClientOrderIdM13Test.kt")
    math_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/lifecycle/PartialFillMathM13Test.kt")

    checks = {
        "Kraken UUID cl_ord_id factory": "fun newId(): String = UUID.randomUUID().toString()" in private,
        "primary order uses UUID cl_ord_id": "clientOrderId = KrakenClientOrderId.newId()" in controller,
        "lifecycle exit uses UUID cl_ord_id": "clientOrderId = KrakenClientOrderId.newId()" in lifecycle,
        "protective orders use UUID cl_ord_id": protection.count("clientOrderId=KrakenClientOrderId.newId()") >= 2,
        "legacy millisecond primary cl_ord_id removed": 'clientOrderId = "ksp-${ticker.symbol.lowercase()}-${System.currentTimeMillis()}"' not in controller,
        "legacy lifecycle exit cl_ord_id removed": 'clientOrderId = "ksp-exit-${symbol.lowercase()}-${System.currentTimeMillis()}"' not in lifecycle,
        "private execution report exposes exec_id": "val executionId: String" in private and 'executionId = item.optString("exec_id")' in private,
        "permission hint names required Kraken setting": "WebSocket interface - On" in private and "KrakenPrivatePermissionHints.describe(rawError)" in private,
        "subscribe errors also receive permission hint": 'KrakenPrivatePermissionHints.describe(root.optString("error", "unknown"))' in private,
        "private execution fills are ledgered by exec_id": 'it.execType == "trade"' in partial and 'val execMarker = "kraken_exec_id=${report.executionId}"' in partial and 'if (!seenExecMarkers.add(execMarker)) continue' in partial,
        "private fill uses exact last quantity": "quantity = report.lastQuantity.toPlainString()" in partial,
        "private fill uses exact last price": "priceEur = report.lastPrice.toPlainString()" in partial,
        "private fill records Kraken fee": "feeEur = report.feeQuantity.max(BigDecimal.ZERO).toPlainString()" in partial,
        "private fill supports SELL realized pnl": "report.side == OrderSide.SELL" in partial and "report.lastPrice.subtract(trackedEntry)" in partial,
        "REST fallback covers BUY and SELL partials": "it.side == OrderSide.BUY &&" not in partial and "side = order.side.name" in partial,
        "REST fallback uses cumulative cost delta": "fun incrementalAveragePrice(" in partial and "val recordedCost = previousRows.fold(BigDecimal.ZERO)" in partial and "exchangeCumulative = order.executedQuantity" in partial,
        "REST SELL partial computes realized delta": "order.side == OrderSide.SELL" in partial and "realizedPnlEur = realizedDelta.toPlainString()" in partial,
        "SELL partial leaves position quantity to authoritative balance refresh": "Position quantity will be refreshed from authoritative balances." in partial,
        "UUID regression tests": "generatedIdsUseKrakenNativeLongUuidFormat" in id_tests and "generatedIdsAreUniqueAcrossBurst" in id_tests,
        "permission diagnostic regression test": "permissionHintExplainsKrakenWebsocketPermission" in id_tests,
        "incremental fill-price regression tests": "cumulativeAverageProducesExactNewFillPrice" in math_tests and "noQuantityDeltaFallsBackWithoutDivision" in math_tests,
    }
    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)
    if failed:
        raise SystemExit("M13 private execution fill ledger verification failed: " + ", ".join(failed))
    print("\nPASS | M13 private execution ledger and order identity contracts satisfied.")

if __name__ == "__main__":
    main()
