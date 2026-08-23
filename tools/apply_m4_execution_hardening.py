#!/usr/bin/env python3
from __future__ import annotations
import os
import sys
from pathlib import Path

NEW_REGISTRY = "app/src/main/java/com/ksp/cryptobot/exchange/KrakenPrivateExecutionState.kt"
NEW_TEST = "app/src/test/java/com/ksp/cryptobot/exchange/KrakenExecutionIdentityTest.kt"

def fail(message: str):
    raise SystemExit("ERROR | " + message)

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)

def main():
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app/ tree:\n" + dirty)

    payload_root = Path(__file__).resolve().parent / "m4_payload"
    for rel in (NEW_REGISTRY, NEW_TEST):
        source = payload_root / rel
        target = repo / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
        print("WRITE |", rel)

    exchange_path = repo / "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt"
    exchange = exchange_path.read_text(encoding="utf-8")

    kraken_start = exchange.find("class KrakenSpotClient(")
    if kraken_start < 0:
        fail("KrakenSpotClient marker missing")
    next_client = exchange.find("class CoinbaseAdvancedClient", kraken_start)
    if next_client < 0:
        next_client = exchange.find("class BitvavoClient", kraken_start)
    if next_client < 0:
        fail("Could not find end of KrakenSpotClient section")

    prefix = exchange[:kraken_start]
    kraken = exchange[kraken_start:next_client]
    suffix = exchange[next_client:]

    nonce_old = 'val nonce = System.currentTimeMillis().toString()'
    nonce_count = kraken.count(nonce_old)
    if nonce_count < 2:
        fail(f"Kraken nonce hardening expected >=2 nonce sites, got {nonce_count}")
    kraken = kraken.replace(nonce_old, 'val nonce = KrakenNonceSequencer.next()')
    print("PATCH | Kraken nonce sites =", nonce_count)

    token_anchor = '''    @Volatile private var pairCache: Map<String, KrakenPairRule> = emptyMap()
    @Volatile private var pairCacheLoadedAtMs: Long = 0L
'''
    token_new = '''    @Volatile private var pairCache: Map<String, KrakenPairRule> = emptyMap()
    @Volatile private var pairCacheLoadedAtMs: Long = 0L

    suspend fun getWebSocketsToken(): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            error("Kraken API key and private key are required for authenticated WebSocket execution state.")
        }
        val root = privateJson("/0/private/GetWebSocketsToken", emptyMap())
        root.optJSONObject("result")?.optString("token")?.takeIf { it.isNotBlank() }
            ?: error("Kraken GetWebSocketsToken returned no token.")
    }
'''
    kraken = replace_once(kraken, token_anchor, token_new, "Kraken WebSocket token method")

    form_old = '''            "volume" to cleanQuantity.stripTrailingZeros().toPlainString(),
            "userref" to userRefFromClientOrderId(request.clientOrderId).toString(),
            "validate" to "false"
'''
    form_new = '''            "volume" to cleanQuantity.stripTrailingZeros().toPlainString(),
            "cl_ord_id" to krakenClientOrderId,
            "validate" to "false"
'''
    kraken = replace_once(kraken, form_old, form_new, "Kraken cl_ord_id replacement")

    duplicate_anchor = '''        val path = "/0/private/AddOrder"
        val nonce = KrakenNonceSequencer.next()
        val orderType = when (request.orderType) {
'''
    duplicate_new = '''        val path = "/0/private/AddOrder"
        val nonce = KrakenNonceSequencer.next()
        val krakenClientOrderId = KrakenClientOrderId.normalize(request.clientOrderId)

        if (request.side == OrderSide.BUY && request.purpose.equals("ENTRY", ignoreCase = true)) {
            val existingBuy = getOpenOrders().firstOrNull {
                it.symbol.equals(rule.canonicalSymbol, ignoreCase = true) && it.side == OrderSide.BUY
            }
            if (existingBuy != null) {
                error("Kraken duplicate entry blocked: open BUY already exists for ${rule.canonicalSymbol}; txid=${existingBuy.exchangeOrderId}, status=${existingBuy.status}, remaining=${existingBuy.remainingQuantity}.")
            }
        }

        val orderType = when (request.orderType) {
'''
    kraken = replace_once(kraken, duplicate_anchor, duplicate_new, "Kraken pre-submit duplicate check")

    request_anchor = '''        val req = Request.Builder().url("https://api.kraken.com$path").addHeader("API-Key", apiKey).addHeader("API-Sign", signature).post(body).build()
        http.newCall(req).execute().use { res ->
            val responseBody = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("Kraken AddOrder HTTP ${res.code}: $responseBody")
'''
    request_new = '''        val req = Request.Builder().url("https://api.kraken.com$path").addHeader("API-Key", apiKey).addHeader("API-Sign", signature).post(body).build()
        KrakenPrivateExecutionRegistry.markSubmissionPending(
            clientOrderId = krakenClientOrderId,
            symbol = rule.canonicalSymbol,
            side = request.side
        )
        http.newCall(req).execute().use { res ->
            val responseBody = res.body?.string().orEmpty()
            if (!res.isSuccessful) {
                if (res.code >= 500) {
                    KrakenPrivateExecutionRegistry.markFailureIfPending(
                        krakenClientOrderId,
                        "Kraken AddOrder HTTP ${res.code}"
                    )
                } else {
                    KrakenPrivateExecutionRegistry.clearSubmission(krakenClientOrderId)
                }
                error("Kraken AddOrder HTTP ${res.code}: $responseBody")
            }
'''
    kraken = replace_once(kraken, request_anchor, request_new, "Kraken pending submission boundary")

    errors_anchor = '''            val errors = root.optJSONArray("error")
            if (errors != null && errors.length() > 0) error("Kraken AddOrder error: $errors")
            val result = root.getJSONObject("result")
'''
    errors_new = '''            val errors = root.optJSONArray("error")
            if (errors != null && errors.length() > 0) {
                KrakenPrivateExecutionRegistry.clearSubmission(krakenClientOrderId)
                error("Kraken AddOrder error: $errors")
            }
            val result = root.getJSONObject("result")
'''
    kraken = replace_once(kraken, errors_anchor, errors_new, "Kraken deterministic API rejection cleanup")

    txid_anchor = '''            val txid = if (txidArray != null && txidArray.length() > 0) txidArray.getString(0) else request.clientOrderId

            // Kraken AddOrder usually only returns txid/description, not the actual fill.
'''
    txid_new = '''            val txid = if (txidArray != null && txidArray.length() > 0) txidArray.getString(0) else request.clientOrderId
            KrakenPrivateExecutionRegistry.markSubmissionAcknowledged(krakenClientOrderId, txid)

            // Kraken AddOrder usually only returns txid/description, not the actual fill.
'''
    kraken = replace_once(kraken, txid_anchor, txid_new, "Kraken AddOrder acknowledgement")

    exchange = prefix + kraken + suffix
    exchange_path.write_text(exchange, encoding="utf-8")
    print("PATCH |", exchange_path.relative_to(repo))

    controller_path = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    controller = controller_path.read_text(encoding="utf-8")

    import_anchor = "import com.ksp.cryptobot.exchange.KrakenRealtimeMarketDataRegistry\n"
    controller = replace_once(
        controller,
        import_anchor,
        import_anchor + "import com.ksp.cryptobot.exchange.KrakenPrivateExecutionRegistry\n",
        "BotController private execution import"
    )

    reconcile_anchor = '''            val reconciliation = advancedExecution.reconcileLive(settings, exchange)
            reconciliation.messages.take(8).forEach { updateStatus("Advanced reconciliation: $it", if (reconciliation.removed > 0) "WARN" else "INFO") }
'''
    reconcile_new = '''            val reconciliation = advancedExecution.reconcileLive(settings, exchange)
            if (settings.exchangeProvider == ExchangeProvider.KRAKEN) {
                KrakenPrivateExecutionRegistry.markRestReconciled(reconciliation.openOrders)
            }
            reconciliation.messages.take(8).forEach { updateStatus("Advanced reconciliation: $it", if (reconciliation.removed > 0) "WARN" else "INFO") }
'''
    controller = replace_once(controller, reconcile_anchor, reconcile_new, "REST reconciliation truth handoff")

    submit_anchor = '''        val orderModeLabel = request.orderType.name
        val submittedNotionalEstimate = request.quantity.multiply(price).setScale(8, RoundingMode.HALF_UP)
        updateStatus("Submitting ${settings.exchangeProvider} ${request.side} $orderModeLabel order: ${request.symbol}, notional≈${submittedNotionalEstimate.setScale(2, RoundingMode.DOWN)} $quoteAsset, qty=${request.quantity}, price=${request.limitPrice ?: "market"}, id=${request.clientOrderId}", "LIVE")
        val result = runCatching { exchange.placeOrder(request) }.getOrElse { error ->
            updateStatus("Order submit failed: ${error.message}", "ERROR")
'''
    submit_new = '''        val orderModeLabel = request.orderType.name
        val submittedNotionalEstimate = request.quantity.multiply(price).setScale(8, RoundingMode.HALF_UP)

        if (settings.mode == BotMode.LIVE_AUTO &&
            settings.exchangeProvider == ExchangeProvider.KRAKEN &&
            request.side == OrderSide.BUY &&
            request.purpose.equals("ENTRY", ignoreCase = true)
        ) {
            val executionTruth = KrakenPrivateExecutionRegistry.canSubmitNewEntry(request.symbol, request.side)
            if (!executionTruth.first) {
                updateStatus("LIVE_AUTO entry blocked by Kraken execution-state gate: ${executionTruth.second}", "ERROR")
                return ExecutionAttemptResult(false)
            }
        }

        updateStatus("Submitting ${settings.exchangeProvider} ${request.side} $orderModeLabel order: ${request.symbol}, notional≈${submittedNotionalEstimate.setScale(2, RoundingMode.DOWN)} $quoteAsset, qty=${request.quantity}, price=${request.limitPrice ?: "market"}, id=${request.clientOrderId}", "LIVE")
        val result = runCatching { exchange.placeOrder(request) }.getOrElse { error ->
            KrakenPrivateExecutionRegistry.markFailureIfPending(
                request.clientOrderId,
                error.message ?: error.javaClass.simpleName
            )
            updateStatus("Order submit failed: ${error.message}", "ERROR")
'''
    controller = replace_once(controller, submit_anchor, submit_new, "LIVE_AUTO execution-state entry gate")
    controller_path.write_text(controller, encoding="utf-8")
    print("PATCH |", controller_path.relative_to(repo))

    service_path = repo / "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt"
    service = service_path.read_text(encoding="utf-8")

    import_anchor = "import com.ksp.cryptobot.exchange.KrakenRealtimeMarketDataRegistry\n"
    service = replace_once(
        service,
        import_anchor,
        import_anchor + "import com.ksp.cryptobot.exchange.KrakenPrivateExecutionRegistry\n",
        "Service private execution import"
    )

    startup_anchor = '''            configureRealtimeMarketData(startSettings, connectivity.snapshot.usable)

            if (startSettings.mode == BotMode.LIVE_AUTO) {
'''
    startup_new = '''            configureRealtimeMarketData(startSettings, connectivity.snapshot.usable)
            configurePrivateExecutionState(startSettings, connectivity.snapshot.usable)

            if (startSettings.mode == BotMode.LIVE_AUTO) {
'''
    service = replace_once(service, startup_anchor, startup_new, "Private execution startup")

    network_anchor = '''                val network = connectivity.refresh()
                KrakenRealtimeMarketDataRegistry.onNetworkAvailable(network.usable)
'''
    network_new = '''                val network = connectivity.refresh()
                KrakenRealtimeMarketDataRegistry.onNetworkAvailable(network.usable)
                KrakenPrivateExecutionRegistry.onNetworkAvailable(network.usable)
'''
    service = replace_once(service, network_anchor, network_new, "Private execution network lifecycle")

    cycle_anchor = '''                val current = settingsStore.load()
                configureRealtimeMarketData(current, network.usable)
                val cycleStart = System.currentTimeMillis()
'''
    cycle_new = '''                val current = settingsStore.load()
                configureRealtimeMarketData(current, network.usable)
                configurePrivateExecutionState(current, network.usable)
                val cycleStart = System.currentTimeMillis()
'''
    service = replace_once(service, cycle_anchor, cycle_new, "Private execution cycle refresh")

    notification_anchor = '''                    val wsHealth = KrakenRealtimeMarketDataRegistry.health()
                    updateNotification(
                        "RUNNING $modeText • net=${network.transports} • ws=${wsHealth.state}/${wsHealth.systemStatus} • next=${selectedDelay}s • signals=${decisions.size}"
                    )
'''
    notification_new = '''                    val wsHealth = KrakenRealtimeMarketDataRegistry.health()
                    val execHealth = KrakenPrivateExecutionRegistry.health()
                    updateNotification(
                        "RUNNING $modeText • net=${network.transports} • ws=${wsHealth.state}/${wsHealth.systemStatus} • exec=${execHealth.state}${if (execHealth.knownForEntries) "/known" else "/unknown"} • next=${selectedDelay}s • signals=${decisions.size}"
                    )
'''
    service = replace_once(service, notification_anchor, notification_new, "Private execution notification state")

    reconcile_anchor = '''            val openOrders = controller.loadOpenOrdersSnapshot(settings)
            val lifecycle = controller.loadLifecycleSnapshot(settings)
            val portfolio = controller.loadPortfolioSnapshot(settings)

            hostStore.reconciliationSucceeded(
'''
    reconcile_new = '''            val openOrders = controller.loadOpenOrdersSnapshot(settings)
            val lifecycle = controller.loadLifecycleSnapshot(settings)
            val portfolio = controller.loadPortfolioSnapshot(settings)
            if (settings.exchangeProvider == ExchangeProvider.KRAKEN) {
                KrakenPrivateExecutionRegistry.markRestReconciled(openOrders.size)
            }

            hostStore.reconciliationSucceeded(
'''
    service = replace_once(service, reconcile_anchor, reconcile_new, "Recovery REST truth handoff")

    stop_anchor = '''        KrakenRealtimeMarketDataRegistry.stop()
        controller.stop()
'''
    stop_new = '''        KrakenRealtimeMarketDataRegistry.stop()
        KrakenPrivateExecutionRegistry.stop()
        controller.stop()
'''
    service = replace_once(service, stop_anchor, stop_new, "Private execution stop")

    destroy_anchor = '''    override fun onDestroy() {
        KrakenRealtimeMarketDataRegistry.stop()
        connectivity.stop()
'''
    destroy_new = '''    override fun onDestroy() {
        KrakenRealtimeMarketDataRegistry.stop()
        KrakenPrivateExecutionRegistry.stop()
        connectivity.stop()
'''
    service = replace_once(service, destroy_anchor, destroy_new, "Private execution destroy")

    helper_anchor = '''    private suspend fun awaitUsableNetwork(reason: String): Boolean {
'''
    helper_new = '''    private fun configurePrivateExecutionState(
        settings: com.ksp.cryptobot.core.BotSettings,
        networkUsable: Boolean
    ) {
        val shouldRun = settings.exchangeProvider == ExchangeProvider.KRAKEN &&
            settings.mode != BotMode.PAPER
        if (!shouldRun) {
            KrakenPrivateExecutionRegistry.stop()
            return
        }

        val key = settingsStore.exchangeApiKey(ExchangeProvider.KRAKEN).orEmpty()
        val secret = settingsStore.exchangeSecretKey(ExchangeProvider.KRAKEN).orEmpty()
        if (key.isBlank() || secret.isBlank()) {
            KrakenPrivateExecutionRegistry.stop()
            return
        }

        KrakenPrivateExecutionRegistry.start(key, secret)
        KrakenPrivateExecutionRegistry.onNetworkAvailable(networkUsable)
        val health = KrakenPrivateExecutionRegistry.health()
        statusStore.write(
            "Kraken private execution-state host: ${health.summary()}",
            if (health.lastError.isBlank()) "INFO" else "WARN"
        )
    }

    private suspend fun awaitUsableNetwork(reason: String): Boolean {
'''
    service = replace_once(service, helper_anchor, helper_new, "Private execution configuration helper")
    service_path.write_text(service, encoding="utf-8")
    print("PATCH |", service_path.relative_to(repo))

    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    all_changed = changed | untracked

    allowed = {
        NEW_REGISTRY,
        NEW_TEST,
        "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt",
        "app/src/main/java/com/ksp/cryptobot/core/BotController.kt",
        "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt",
    }
    unexpected = sorted(all_changed - allowed)
    missing = sorted(allowed - all_changed)
    if unexpected:
        fail("Unexpected M4 app changes: " + ", ".join(unexpected))
    if missing:
        fail("Expected M4 app changes missing: " + ", ".join(missing))

    print("PASS | M4 patch changed only approved execution-state files.")

if __name__ == "__main__":
    main()
