#!/usr/bin/env python3
from pathlib import Path
import sys

def read(path):
    p=Path(path)
    return p.read_text(encoding="utf-8") if p.exists() else ""

def slice_between(text: str, start_marker: str, end_marker: str) -> str:
    start = text.find(start_marker)
    if start < 0:
        return ""
    end = text.find(end_marker, start + len(start_marker))
    if end < 0:
        end = len(text)
    return text[start:end]

def main():
    repo=Path(sys.argv[1] if len(sys.argv)>1 else ".").resolve()
    service=read(repo/"app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt")
    controller=read(repo/"app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    advanced=read(repo/"app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt")
    truth=read(repo/"app/src/main/java/com/ksp/cryptobot/execution/ExecutionTruthGate.kt")
    registry=read(repo/"app/src/main/java/com/ksp/cryptobot/exchange/KrakenPrivateExecutionState.kt")
    durable=read(repo/"app/src/main/java/com/ksp/cryptobot/exchange/KrakenDurableExecutionQuarantine.kt")
    exchange=read(repo/"app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt")
    truth_tests=read(repo/"app/src/test/java/com/ksp/cryptobot/execution/ExecutionTruthGateTest.kt")
    durable_tests=read(repo/"app/src/test/java/com/ksp/cryptobot/exchange/KrakenDurableSubmissionCodecTest.kt")
    db=read(repo/"app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt")

    reconcile_live = slice_between(
        advanced,
        "suspend fun reconcileLive(",
        "suspend fun diagnostics("
    )

    # ExchangeClientsV08.kt contains several placeOrder() overrides.
    # First isolate KrakenSpotClient, then isolate Kraken's own placeOrder().
    kraken_class = slice_between(
        exchange,
        "class KrakenSpotClient(",
        "class CoinbaseAdvancedClient"
    )
    if not kraken_class:
        # Forward-compatible fallback: use the Kraken class until EOF if the next
        # client class name changes.
        start = exchange.find("class KrakenSpotClient(")
        kraken_class = exchange[start:] if start >= 0 else ""

    kraken_place_order = slice_between(
        kraken_class,
        "override suspend fun placeOrder(request: OrderRequest)",
        "private fun roundKrakenPriceToTick("
    )

    balance_truth_idx = reconcile_live.find('"portfolio balances"')
    order_truth_idx = reconcile_live.find('"open orders"')
    local_positions_idx = reconcile_live.find("val positions = appDao.openPositionsSnapshot()")

    pending_boundary_idx = kraken_place_order.find(
        "KrakenPrivateExecutionRegistry.markSubmissionPending("
    )
    add_order_transport_idx = kraken_place_order.find(
        "http.newCall(req).execute().use"
    )

    checks={
      "no Room schema bump":"version = 12" in db,

      "startup retry loop":"var startupReconciled = reconcileAfterRecovery" in service and
          "while (isActive && hostStore.snapshot().desiredRunning && !startupReconciled)" in service,
      "startup controller start gated":"Trading controller was not started because authoritative startup reconciliation never completed." in service,
      "startup reconciliation retry":"startup-retry:$recoveryReason" in service,

      "strict controller reconcile method":"suspend fun reconcileLiveExecutionState(" in controller,
      "strict controller runs lifecycle maintenance":"lifecycleManager.runPreScanMaintenance(settings, exchange)" in controller,
      "strict controller runs advanced reconcile":"advancedExecution.reconcileLive(settings, exchange)" in controller,
      "strict controller refreshes Kraken truth":"KrakenPrivateExecutionRegistry.markRestReconciled(reconciliation.openOrders)" in controller,

      "advanced balance failures are not empty":"ExecutionTruthGate.requireAuthoritative(" in reconcile_live and
          '"portfolio balances"' in reconcile_live,
      "advanced open-order failures are not empty":'"open orders"' in reconcile_live and
          "runCatching { exchange.getOpenOrders() }" in reconcile_live,
      "old fail-open balance fallback removed":"exchange.getPortfolioBalances() }.getOrElse { emptyList() }" not in reconcile_live,
      "old fail-open order fallback removed":"exchange.getOpenOrders() }.getOrElse { emptyList() }" not in reconcile_live,
      "authoritative reads happen before local mutation":
          balance_truth_idx >= 0 and
          order_truth_idx >= 0 and
          local_positions_idx >= 0 and
          balance_truth_idx < local_positions_idx and
          order_truth_idx < local_positions_idx,

      "recovery establishes strict truth first":"val executionTruth = controller.reconcileLiveExecutionState(settings)" in service,
      "diagnostic mismatch fails reconciliation":"Open-order diagnostics disagree with strict execution truth" in service,

      "truth gate exception":"class ExchangeTruthUnavailableException" in truth,
      "truth gate throws on failure":"throw ExchangeTruthUnavailableException" in truth,
      "legitimate empty test":"legitimateEmptySnapshotRemainsAuthoritative" in truth_tests,
      "failure cannot become empty test":"apiFailureCannotBecomeEmptyAuthoritativeSnapshot" in truth_tests,

      "durable quarantine object":"object KrakenDurableExecutionQuarantine" in durable,
      "durable persistence uses commit":"commit()" in durable,
      "registry initialize hook":"fun initialize(context: Context)" in registry and
          "KrakenDurableExecutionQuarantine.initialize(context)" in registry,
      "controller initializes registry":"KrakenPrivateExecutionRegistry.initialize(appContext)" in controller,
      "registry restores unresolved as ambiguous":"restoreDurableQuarantineLocked" in registry and
          "ambiguous[id] = PendingSubmission(" in registry,
      "pending persisted":"KrakenDurableExecutionQuarantine.markPending(" in registry,
      "ambiguous persisted":"KrakenDurableExecutionQuarantine.markAmbiguous(id, reason)" in registry,
      "ack clears durable":"KrakenDurableExecutionQuarantine.clear(id)" in registry,
      "execution report clears durable":"KrakenDurableExecutionQuarantine.clear(report.clientOrderId)" in registry,
      "stop does not erase durable quarantine":
          "KrakenDurableExecutionQuarantine.clear" not in
          registry[registry.find("fun stop()"):registry.find("fun onNetworkAvailable")],

      "Kraken placeOrder isolated":
          "KrakenPrivateExecutionRegistry.markSubmissionPending(" in kraken_place_order and
          '"https://api.kraken.com$path"' in kraken_place_order,

      "AddOrder pending boundary remains before transport":
          pending_boundary_idx >= 0 and
          add_order_transport_idx >= 0 and
          pending_boundary_idx < add_order_transport_idx,

      "durable codec roundtrip test":"unresolvedSubmissionRoundTripsAcrossProcessBoundary" in durable_tests,
    }

    failed=[]
    for name,ok in checks.items():
        print(("PASS" if ok else "FAIL")+" | "+name)
        if not ok:
            failed.append(name)

    if failed:
        print(
            "DEBUG | reconcile indexes "
            f"balance={balance_truth_idx}, orders={order_truth_idx}, localPositions={local_positions_idx}"
        )
        print(
            "DEBUG | Kraken AddOrder indexes "
            f"pending={pending_boundary_idx}, transport={add_order_transport_idx}, "
            f"krakenClassLen={len(kraken_class)}, placeOrderLen={len(kraken_place_order)}"
        )
        raise SystemExit("M11 execution fail-closed verification failed: "+", ".join(failed))

    print("\nPASS | M11 execution fail-closed / durable unknown-state contracts satisfied.")

if __name__=="__main__":
    main()
