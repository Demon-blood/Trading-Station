#!/usr/bin/env python3
from pathlib import Path
import sys

def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.exists() else ""

def main():
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    registry = read(repo / "app/src/main/java/com/ksp/cryptobot/exchange/KrakenPrivateExecutionState.kt")
    exchange = read(repo / "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt")
    controller = read(repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    service = read(repo / "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt")
    tests = read(repo / "app/src/test/java/com/ksp/cryptobot/exchange/KrakenExecutionIdentityTest.kt")

    checks = {
        "monotonic Kraken nonce": "object KrakenNonceSequencer" in registry and "previous + 1L" in registry,
        "Kraken nonce used by connector": "KrakenNonceSequencer.next()" in exchange,
        "cl_ord_id used": '"cl_ord_id" to krakenClientOrderId' in exchange,
        "legacy userref removed from Kraken AddOrder": '"userref" to userRefFromClientOrderId(request.clientOrderId)' not in exchange,
        "client id supports UUID/free text": "longUuid" in registry and "shortUuid" in registry and 'return "cts-" + digest.take(14)' in registry,
        "authenticated WS v2 endpoint": 'wss://ws-auth.kraken.com/v2' in registry,
        "WebSocket token endpoint wired": 'GetWebSocketsToken' in exchange,
        "executions channel": '.put("channel", "executions")' in registry,
        "open order snapshot requested": '.put("snap_orders", true)' in registry,
        "trade snapshot requested": '.put("snap_trades", true)' in registry,
        "all status transitions requested": '.put("order_status", true)' in registry,
        "cl_ord_id parsed": 'item.optString("cl_ord_id")' in registry,
        "partial fills modeled": '"partially_filled"' in registry and 'decimal(item, "cum_qty")' in registry and 'decimal(item, "last_qty")' in registry,
        "fees modeled": 'optJSONArray("fees")' in registry,
        "sequence gap fail closed": 'state = "SEQUENCE_GAP"' in registry and "snapshotComplete = false" in registry,
        "private cache reset after loss": "reportsByOrder.clear()" in registry,
        "REST truth fallback TTL": "REST_TRUTH_TTL_MS = 60_000L" in registry,
        "ambiguous submission quarantine": "ambiguous[id]" in registry and "markFailureIfPending" in registry,
        "pending boundary before AddOrder": "markSubmissionPending" in exchange,
        "successful AddOrder acknowledgement": "markSubmissionAcknowledged" in exchange,
        "open BUY duplicate defense": "Kraken duplicate entry blocked" in exchange,
        "LIVE_AUTO entry truth gate": "LIVE_AUTO entry blocked by Kraken execution-state gate" in controller,
        "protective SELL path not entry-gated": 'if (side != OrderSide.BUY)' in registry,
        "scan REST reconciliation refreshes truth": "markRestReconciled(reconciliation.openOrders)" in controller,
        "restart REST reconciliation refreshes truth": "markRestReconciled(openOrders.size)" in service,
        "foreground host starts private feed": "configurePrivateExecutionState(startSettings" in service,
        "network propagated to private feed": "KrakenPrivateExecutionRegistry.onNetworkAvailable(network.usable)" in service,
        "notification exposes exec state": "exec=${execHealth.state}" in service,
        "host stop closes private feed": "KrakenPrivateExecutionRegistry.stop()" in service,
        "nonce regression test": "nonceIsStrictlyIncreasingEvenWithinSameMillisecond" in tests,
        "client order id regression tests": "validLongUuidIsPreserved" in tests and "longArbitraryClientIdBecomesStableKrakenFreeText" in tests,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        raise SystemExit("M4 execution hardening verification failed: " + ", ".join(failed))

    print("\nPASS | M4 Kraken execution-state hardening contracts satisfied.")

if __name__ == "__main__":
    main()
