#!/usr/bin/env python3
from pathlib import Path
import sys

def read(path):
    p = Path(path)
    return p.read_text(encoding="utf-8") if p.exists() else ""

def slice_between(text, start_marker, end_marker):
    start = text.find(start_marker)
    if start < 0:
        return ""
    end = text.find(end_marker, start + len(start_marker))
    return text[start:] if end < 0 else text[start:end]

def main():
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    models = read(repo / "app/src/main/java/com/ksp/cryptobot/core/Models.kt")
    exchange = read(repo / "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt")
    resolver = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/KrakenOrderTruthResolver.kt")
    order_truth = read(repo / "app/src/main/java/com/ksp/cryptobot/exchange/KrakenOrderTruth.kt")
    partial = read(repo / "app/src/main/java/com/ksp/cryptobot/lifecycle/PartialFillSynchronizer.kt")
    lifecycle = read(repo / "app/src/main/java/com/ksp/cryptobot/lifecycle/TradeLifecycleManager.kt")
    protection = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/ProtectiveStopManager.kt")
    lease = read(repo / "app/src/main/java/com/ksp/cryptobot/execution/EngineAuthorityLeaseManager.kt")
    cloud_client = read(repo / "app/src/main/java/com/ksp/cryptobot/cloudshare/CloudShareClient.kt")
    schema = read(repo / "app/src/main/assets/cloudshare_setup/schema.sql")
    worker = read(repo / "app/src/main/assets/cloudshare_setup/cloudshare-worker.js")
    controller = read(repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    service = read(repo / "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt")
    db = read(repo / "app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt")

    resolver_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/execution/KrakenOrderTruthResolverTest.kt")
    partial_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/lifecycle/PartialFillMathTest.kt")
    lease_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/execution/EngineAuthorityPolicyTest.kt")

    kraken = slice_between(exchange, "class KrakenSpotClient(", "class CoinbaseAdvancedClient")
    resolve_fn = slice_between(kraken, "suspend fun resolveClientOrderId(", "suspend fun setDeadMansSwitch(")
    dms_fn = slice_between(kraken, "suspend fun setDeadMansSwitch(", "override suspend fun validateSymbol(")
    pre_scan = slice_between(lifecycle, "suspend fun runPreScanMaintenance(", "suspend fun runPostDecisionManagement(")
    sync_closed = slice_between(lifecycle, "private suspend fun syncClosedOrders(", "private suspend fun")
    protect = slice_between(protection, "suspend fun protectOrFlatten(", "suspend fun cancelProtectiveStops(")
    start_bot = slice_between(service, "private fun startBot(", "private fun configureRealtimeMarketData(")
    on_destroy = slice_between(service, "override fun onDestroy()", "override fun onBind")

    acquire_route = slice_between(worker, 'if (path === "/v1/engine-lease/acquire")', 'if (path === "/v1/engine-lease/heartbeat")')

    checks = {
        "no Room schema bump": "version = 12" in db,

        "LiveOrderInfo exposes cl_ord_id": 'val clientOrderId: String = ""' in models,
        "LiveOrderInfo exposes cumulative fill economics":
            "val averageFillPrice: BigDecimal = BigDecimal.ZERO" in models and
            "val fee: BigDecimal = BigDecimal.ZERO" in models,
        "ClosedOrderInfo exposes cl_ord_id": models.count('val clientOrderId: String = ""') >= 2,

        "Kraken order truth model": "data class KrakenClientOrderResolution" in order_truth,
        "Kraken resolver uses OpenOrders cl_ord_id":
            '"/0/private/OpenOrders"' in resolve_fn and '"cl_ord_id" to clientOrderId' in resolve_fn,
        "Kraken resolver uses ClosedOrders cl_ord_id":
            '"/0/private/ClosedOrders"' in resolve_fn and resolve_fn.count('"cl_ord_id" to clientOrderId') >= 2,
        "Kraken resolver is fail closed":
            "privateJson(" in resolve_fn and "runCatching" not in resolve_fn,
        "durable not-found consistency grace":
            "NOT_FOUND_GRACE_MS = 10L * 60L * 1000L" in resolver and
            "canClearAuthoritativeNotFound" in resolver,
        "durable quarantine clears only on authoritative resolution":
            "KrakenPrivateExecutionRegistry.clearSubmission" in resolver,
        "both live reconciliation paths resolve durable cl_ord_id":
            controller.count("KrakenOrderTruthResolver.resolveDurable(exchange)") == 2,
        "unresolved durable ambiguity blocks reconciliation":
            controller.count("require(orderTruth.unresolved == 0)") == 2,

        "Kraken open orders surface client order id":
            'val clientOrderId = item.optString("cl_ord_id", "")' in kraken and
            "clientOrderId = clientOrderId" in kraken,
        "Kraken open orders surface cumulative fill price and fee":
            "averageFillPrice = avgFill" in kraken and "fee = fee" in kraken,
        "Kraken closed orders surface client order id":
            'clientOrderId = item.optString("cl_ord_id", "")' in kraken,

        "partial fill synchronizer present": "class PartialFillSynchronizer" in partial,
        "partial fill reads are authoritative":
            'ExecutionTruthGate.requireAuthoritative(' in partial and '"partial-fill open orders"' in partial,
        "partial fill requires executed plus remaining":
            "it.executedQuantity > BigDecimal.ZERO" in partial and
            "it.remainingQuantity > BigDecimal.ZERO" in partial,
        "partial fill journals only delta":
            "PartialFillMath.incrementalQuantity" in partial and
            "if (deltaQty > BigDecimal.ZERO" in partial,
        "partial fill cumulative position state":
            'status = "OPEN_PARTIAL"' in partial and
            "quantity = order.executedQuantity.toPlainString()" in partial,
        "partial fill retains exchange/client IDs":
            "exchangeOrderId = order.exchangeOrderId" in partial and
            "clientOrderId = order.clientOrderId" in partial,
        "partial fills run before position refresh":
            pre_scan.find("partialFillSynchronizer.sync(settings, exchange)") >= 0 and
            pre_scan.find("partialFillSynchronizer.sync(settings, exchange)") <
            pre_scan.find("refreshPositionRows(settings, exchange)"),

        "closed fill uses cumulative delta":
            "alreadyRecordedQty" in sync_closed and
            "PartialFillMath.incrementalQuantity(order.executedQuantity, alreadyRecordedQty)" in sync_closed,
        "closed fill does not use any-order-id dedup":
            "val exists = dao.recentTradesSnapshot(300).any" not in sync_closed,
        "closed journal writes delta quantity and fee":
            "quantity = deltaQty.toPlainString()" in sync_closed and
            "feeEur = deltaFee.toPlainString()" in sync_closed,

        "protective stop adds only missing coverage":
            "val missingCoverage = quantity.subtract(coveredBeforeStandalone)" in protect and
            "quantity=missingCoverage" in protect,
        "protective stop 98 percent coverage retained":
            'BigDecimal("0.98")' in protect,

        "Kraken account identity uses GetApiKeyInfo":
            '"/0/private/GetApiKeyInfo"' in kraken,
        "distributed identity requires account IIBAN":
            'require(iban.isNotBlank())' in kraken and
            'source = "KRAKEN_IIBAN"' in kraken,
        "no key-specific distributed identity fallback":
            "KRAKEN_API_KEY_FINGERPRINT" not in kraken,

        "CloudShare engine lease table":
            "CREATE TABLE IF NOT EXISTS engine_leases" in schema and
            "account_key TEXT PRIMARY KEY" in schema,
        "CloudShare acquire route":
            '"/v1/engine-lease/acquire"' in worker,
        "CloudShare heartbeat route":
            '"/v1/engine-lease/heartbeat"' in worker,
        "CloudShare release route":
            '"/v1/engine-lease/release"' in worker,
        "lease acquisition is atomic conditional UPSERT":
            "ON CONFLICT(account_key) DO UPDATE SET" in acquire_route and
            "engine_leases.expires_at_epoch_ms <= ?" in acquire_route and
            "engine_leases.holder_client_id=? AND engine_leases.holder_engine_id=?" in acquire_route,
        "lease routes require registered client auth":
            worker.find("const auth = await requireClient(request, env);") <
            worker.find('path.startsWith("/v1/engine-lease/")') and
            worker.find("const auth = await requireClient(request, env);") >= 0,
        "CloudShare client lease calls":
            "suspend fun acquireEngineLease(" in cloud_client and
            "suspend fun heartbeatEngineLease(" in cloud_client and
            "suspend fun releaseEngineLease(" in cloud_client,

        "LIVE lease TTL 75 seconds":
            "const val LEASE_TTL_SECONDS = 75" in lease,
        "LIVE lease heartbeat 20 seconds":
            "const val HEARTBEAT_SECONDS = 20L" in lease,
        "CloudShare mandatory for distributed LIVE authority":
            'blocked("CLOUDSHARE_REQUIRED"' in lease and
            'blocked("CLOUDSHARE_UNREGISTERED"' in lease,
        "lease heartbeat loss revokes entry authority":
            'EngineAuthoritySnapshot(false, "LOST"' in lease and
            'EngineAuthoritySnapshot(false, "UNKNOWN"' in lease,
        "paper does not need LIVE lease":
            'settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER' in lease and
            'EngineAuthoritySnapshot(true, "PAPER"' in lease and
            'return paper' in lease,
        "service acquires authority before controller start":
            start_bot.find("authorityLease.acquire(startSettings)") >= 0 and
            start_bot.find("authorityLease.acquire(startSettings)") <
            start_bot.find("controller.start()"),
        "service blocks LIVE when authority unavailable":
            "LIVE start blocked by distributed authority" in start_bot and
            "if (!authority.authorized)" in start_bot,
        "service stop releases authority":
            "authorityLease.stop()" in service,
        "service destruction releases authority":
            "authorityLease.stop()" in on_destroy,
        "BotController BUY entry uses distributed authority":
            "EngineAuthorityRuntime.canSubmitNewEntry(settings.mode)" in controller and
            "LIVE entry blocked by distributed engine-authority gate" in controller,
        "distributed authority does not gate protective SELL":
            "settings.mode != BotMode.PAPER && request.side == OrderSide.BUY" in controller,

        "Kraken Spot DMS capability implemented":
            '"/0/private/CancelAllOrdersAfter"' in dms_fn and
            '"timeout" to timeoutSeconds.toString()' in dms_fn,
        "DMS timeout bound below 24h":
            "timeoutSeconds in 0 until 86400" in dms_fn,
        "DMS deliberately not auto armed":
            "setDeadMansSwitch(" not in service and
            "setDeadMansSwitch(" not in controller,

        "not-found grace regression tests":
            "authoritativeNotFoundStaysQuarantinedDuringConsistencyGrace" in resolver_tests and
            "authoritativeNotFoundCanClearAfterTenMinuteGrace" in resolver_tests,
        "partial-fill delta regression tests":
            "cumulativeFillCreatesOnlyIncrementalJournalQuantity" in partial_tests and
            "replayedSameCumulativeFillCreatesNoDuplicateQuantity" in partial_tests and
            "cumulativeFeeCreatesOnlyIncrementalFee" in partial_tests,
        "distributed authority regression tests":
            "paperDoesNotRequireDistributedLease" in lease_tests and
            "liveAutoRequiresDistributedLease" in lease_tests and
            "liveConfirmAlsoRequiresDistributedLease" in lease_tests,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        raise SystemExit("M12 authoritative order truth / engine authority verification failed: " + ", ".join(failed))

    print("\nPASS | M12 authoritative order truth, partial-fill lifecycle, distributed engine authority and DMS policy contracts satisfied.")

if __name__ == "__main__":
    main()
