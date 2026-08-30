#!/usr/bin/env python3
from pathlib import Path
import os, sys

NEW_FILES = [
    "app/src/main/java/com/ksp/cryptobot/exchange/KrakenOrderTruth.kt",
    "app/src/main/java/com/ksp/cryptobot/execution/KrakenOrderTruthResolver.kt",
    "app/src/main/java/com/ksp/cryptobot/execution/EngineAuthorityLeaseManager.kt",
    "app/src/main/java/com/ksp/cryptobot/lifecycle/PartialFillSynchronizer.kt",
    "app/src/test/java/com/ksp/cryptobot/execution/KrakenOrderTruthResolverTest.kt",
    "app/src/test/java/com/ksp/cryptobot/lifecycle/PartialFillMathTest.kt",
    "app/src/test/java/com/ksp/cryptobot/execution/EngineAuthorityPolicyTest.kt",
]

def fail(msg):
    raise SystemExit("ERROR | " + msg)

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

def main():
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app tree:\\n" + dirty)

    payload = Path(__file__).resolve().parent / "m12_payload"
    for rel in NEW_FILES:
        src = payload / rel
        dst = repo / rel
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(src.read_text(encoding="utf-8").rstrip() + "\\n", encoding="utf-8")
        print("WRITE |", rel)

    # Models
    p = repo / "app/src/main/java/com/ksp/cryptobot/core/Models.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''    val openedAtEpochSeconds: Long,
    val description: String = ""
)


data class ClosedOrderInfo(
''',
        '''    val openedAtEpochSeconds: Long,
    val description: String = "",
    val clientOrderId: String = "",
    val averageFillPrice: BigDecimal = BigDecimal.ZERO,
    val fee: BigDecimal = BigDecimal.ZERO
)


data class ClosedOrderInfo(
''',
        "M12 LiveOrderInfo truth fields"
    )
    t = replace_once(
        t,
        '''    val closedAtEpochSeconds: Long,
    val status: String,
    val description: String = ""
)

data class PositionInfo(
''',
        '''    val closedAtEpochSeconds: Long,
    val status: String,
    val description: String = "",
    val clientOrderId: String = ""
)

data class PositionInfo(
''',
        "M12 ClosedOrderInfo cl_ord_id"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # CloudShare client
    p = repo / "app/src/main/java/com/ksp/cryptobot/cloudshare/CloudShareClient.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''    suspend fun adminPing(): Map<String, Any?> = requestMap("GET", "/v1/admin/ping", admin = true)
''',
        '''    suspend fun acquireEngineLease(
        accountKey: String,
        engineId: String,
        platform: String,
        ttlSeconds: Int
    ): Map<String, Any?> = requestMap(
        "POST",
        "/v1/engine-lease/acquire",
        body = mapOf(
            "account_key" to accountKey,
            "engine_id" to engineId,
            "platform" to platform,
            "ttl_seconds" to ttlSeconds.coerceIn(30, 300)
        )
    )

    suspend fun heartbeatEngineLease(
        accountKey: String,
        engineId: String,
        ttlSeconds: Int
    ): Map<String, Any?> = requestMap(
        "POST",
        "/v1/engine-lease/heartbeat",
        body = mapOf(
            "account_key" to accountKey,
            "engine_id" to engineId,
            "ttl_seconds" to ttlSeconds.coerceIn(30, 300)
        )
    )

    suspend fun releaseEngineLease(
        accountKey: String,
        engineId: String
    ): Map<String, Any?> = requestMap(
        "POST",
        "/v1/engine-lease/release",
        body = mapOf("account_key" to accountKey, "engine_id" to engineId)
    )

    suspend fun adminPing(): Map<String, Any?> = requestMap("GET", "/v1/admin/ping", admin = true)
''',
        "M12 CloudShare engine lease client"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # CloudShare schema
    p = repo / "app/src/main/assets/cloudshare_setup/schema.sql"
    t = p.read_text(encoding="utf-8").rstrip() + "\\n"
    if "CREATE TABLE IF NOT EXISTS engine_leases" not in t:
        t += '''
CREATE TABLE IF NOT EXISTS engine_leases (
    account_key TEXT PRIMARY KEY,
    holder_client_id TEXT NOT NULL,
    holder_engine_id TEXT NOT NULL,
    platform TEXT NOT NULL DEFAULT '',
    expires_at_epoch_ms INTEGER NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_engine_leases_expiry ON engine_leases(expires_at_epoch_ms);
'''
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # CloudShare Worker
    p = repo / "app/src/main/assets/cloudshare_setup/cloudshare-worker.js"
    t = p.read_text(encoding="utf-8")
    lease_handler = r'''
async function handleEngineLease(request, env, client, path) {
  const body = await request.json().catch(() => ({}));
  const accountKey = String(body.account_key || "").trim().toLowerCase();
  const engineId = String(body.engine_id || "").trim().slice(0, 120);
  const platform = String(body.platform || "").trim().slice(0, 40);
  const ttlSeconds = Math.min(300, Math.max(30, Number(body.ttl_seconds || 75)));
  if (!/^[a-f0-9]{64}$/.test(accountKey) || !engineId) {
    return json({ error: "valid account_key and engine_id required" }, 400);
  }

  const now = Date.now();
  const expires = now + ttlSeconds * 1000;
  const updated = nowIso();

  if (path === "/v1/engine-lease/acquire") {
    await env.DB.prepare(
      `INSERT INTO engine_leases
       (account_key, holder_client_id, holder_engine_id, platform, expires_at_epoch_ms, updated_at)
       VALUES (?, ?, ?, ?, ?, ?)
       ON CONFLICT(account_key) DO UPDATE SET
         holder_client_id=excluded.holder_client_id,
         holder_engine_id=excluded.holder_engine_id,
         platform=excluded.platform,
         expires_at_epoch_ms=excluded.expires_at_epoch_ms,
         updated_at=excluded.updated_at
       WHERE engine_leases.expires_at_epoch_ms <= ?
          OR (engine_leases.holder_client_id=? AND engine_leases.holder_engine_id=?)`
    ).bind(
      accountKey, client.client_id, engineId, platform, expires, updated,
      now, client.client_id, engineId
    ).run();

    const row = await env.DB.prepare(
      "SELECT holder_client_id, holder_engine_id, platform, expires_at_epoch_ms FROM engine_leases WHERE account_key=? LIMIT 1"
    ).bind(accountKey).first();

    const acquired = !!row &&
      row.holder_client_id === client.client_id &&
      row.holder_engine_id === engineId;

    return json({
      acquired,
      holder_engine_id: row?.holder_engine_id || "",
      holder_platform: row?.platform || "",
      expires_at_epoch_ms: Number(row?.expires_at_epoch_ms || 0)
    });
  }

  if (path === "/v1/engine-lease/heartbeat") {
    const result = await env.DB.prepare(
      `UPDATE engine_leases
          SET expires_at_epoch_ms=?, updated_at=?, platform=?
        WHERE account_key=? AND holder_client_id=? AND holder_engine_id=? AND expires_at_epoch_ms > ?`
    ).bind(expires, updated, platform, accountKey, client.client_id, engineId, now).run();

    const row = await env.DB.prepare(
      "SELECT holder_client_id, holder_engine_id, platform, expires_at_epoch_ms FROM engine_leases WHERE account_key=? LIMIT 1"
    ).bind(accountKey).first();

    const renewed = Number(result?.meta?.changes || 0) > 0 &&
      row?.holder_client_id === client.client_id &&
      row?.holder_engine_id === engineId;

    return json({
      renewed,
      holder_engine_id: row?.holder_engine_id || "",
      holder_platform: row?.platform || "",
      expires_at_epoch_ms: Number(row?.expires_at_epoch_ms || 0)
    });
  }

  if (path === "/v1/engine-lease/release") {
    const result = await env.DB.prepare(
      "DELETE FROM engine_leases WHERE account_key=? AND holder_client_id=? AND holder_engine_id=?"
    ).bind(accountKey, client.client_id, engineId).run();
    return json({ released: Number(result?.meta?.changes || 0) > 0 });
  }

  return json({ error: "engine lease route not found" }, 404);
}

'''
    t = replace_once(
        t,
        '''async function adminRoutes(request, env, path) {
''',
        lease_handler + '''async function adminRoutes(request, env, path) {
''',
        "M12 worker engine lease handler"
    )
    t = replace_once(
        t,
        '''      if (request.method === "POST" && path === "/v1/events/batch") return await handleBatch(request, env, client);
''',
        '''      if (request.method === "POST" && path.startsWith("/v1/engine-lease/")) return await handleEngineLease(request, env, client, path);
      if (request.method === "POST" && path === "/v1/events/batch") return await handleBatch(request, env, client);
''',
        "M12 worker lease routes"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # Kraken connector
    p = repo / "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''import java.math.RoundingMode
import com.ksp.cryptobot.core.BalanceInfo
''',
        '''import java.math.RoundingMode
import java.security.MessageDigest
import com.ksp.cryptobot.core.BalanceInfo
''',
        "M12 MessageDigest import"
    )

    kraken_methods = r'''
    suspend fun accountAuthorityIdentity(): KrakenAccountAuthorityIdentity = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Kraken credentials are required for account authority identity.")
        val root = privateJson("/0/private/GetApiKeyInfo", emptyMap())
        val result = root.optJSONObject("result") ?: error("Kraken GetApiKeyInfo returned no result.")
        val iban = result.optString("iban", "").trim()
        require(iban.isNotBlank()) {
            "Kraken GetApiKeyInfo returned no account IIBAN. M12 refuses a key-specific fallback because different API keys on the same account must not create independent LIVE leases."
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("KRAKEN-IIBAN:" + iban).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        KrakenAccountAuthorityIdentity(
            accountKey = digest,
            source = "KRAKEN_IIBAN"
        )
    }

    suspend fun resolveClientOrderId(rawClientOrderId: String): KrakenClientOrderResolution = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Kraken credentials are required for client-order resolution.")
        val clientOrderId = KrakenClientOrderId.normalize(rawClientOrderId)

        fun parse(item: org.json.JSONObject, txid: String, isOpen: Boolean): KrakenClientOrderResolution {
            val descr = item.optJSONObject("descr")
            val pair = descr?.optString("pair", "").orEmpty()
            val side = if (descr?.optString("type", "buy").equals("sell", true)) OrderSide.SELL else OrderSide.BUY
            val orderType = when (descr?.optString("ordertype", "limit")?.lowercase()) {
                "market" -> OrderType.MARKET
                "stop-loss", "stop-loss-limit" -> OrderType.STOP_LOSS
                "take-profit", "take-profit-limit" -> OrderType.TAKE_PROFIT
                else -> OrderType.LIMIT
            }
            val qty = item.optString("vol", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val executed = item.optString("vol_exec", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val avg = item.optString("price", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val fee = item.optString("fee", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            return KrakenClientOrderResolution(
                found = true,
                open = isOpen,
                exchangeOrderId = txid,
                clientOrderId = item.optString("cl_ord_id", clientOrderId).ifBlank { clientOrderId },
                symbol = fromKrakenPair(pair),
                side = side,
                orderType = orderType,
                status = item.optString("status", if (isOpen) "open" else "closed"),
                quantity = qty,
                executedQuantity = executed,
                averageFillPrice = avg,
                fee = fee
            )
        }

        val openRoot = privateJson(
            "/0/private/OpenOrders",
            mapOf("trades" to "true", "cl_ord_id" to clientOrderId)
        )
        val open = openRoot.optJSONObject("result")?.optJSONObject("open")
        val openTxid = open?.keys()?.asSequence()?.firstOrNull()
        if (openTxid != null) {
            return@withContext parse(open.getJSONObject(openTxid), openTxid, true)
        }

        val closedRoot = privateJson(
            "/0/private/ClosedOrders",
            mapOf("trades" to "true", "closetime" to "close", "cl_ord_id" to clientOrderId)
        )
        val closed = closedRoot.optJSONObject("result")?.optJSONObject("closed")
        val closedTxid = closed?.keys()?.asSequence()?.firstOrNull()
        if (closedTxid != null) {
            return@withContext parse(closed.getJSONObject(closedTxid), closedTxid, false)
        }

        KrakenClientOrderResolution(found = false, open = false, clientOrderId = clientOrderId)
    }

    suspend fun setDeadMansSwitch(timeoutSeconds: Int): KrakenDeadManSwitchStatus = withContext(Dispatchers.IO) {
        require(timeoutSeconds in 0 until 86400) { "Kraken CancelAllOrdersAfter timeout must be 0..86399 seconds." }
        val root = privateJson(
            "/0/private/CancelAllOrdersAfter",
            mapOf("timeout" to timeoutSeconds.toString())
        )
        val result = root.optJSONObject("result") ?: error("Kraken CancelAllOrdersAfter returned no result.")
        KrakenDeadManSwitchStatus(
            timeoutSeconds = timeoutSeconds,
            currentTime = result.optString("currentTime", ""),
            triggerTime = result.optString("triggerTime", ""),
            enabled = timeoutSeconds > 0
        )
    }

'''
    t = replace_once(
        t,
        '''        root.optJSONObject("result")?.optString("token")?.takeIf { it.isNotBlank() }
            ?: error("Kraken GetWebSocketsToken returned no token.")
    }

    override suspend fun validateSymbol(symbol: String): ExchangeSymbolInfo = withContext(Dispatchers.IO) {
''',
        '''        root.optJSONObject("result")?.optString("token")?.takeIf { it.isNotBlank() }
            ?: error("Kraken GetWebSocketsToken returned no token.")
    }

''' + kraken_methods + '''    override suspend fun validateSymbol(symbol: String): ExchangeSymbolInfo = withContext(Dispatchers.IO) {
''',
        "M12 Kraken-specific truth methods"
    )
    t = replace_once(
        t,
        '''            val price = (descr?.optString("price", "0") ?: "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val vol = item.optString("vol", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val volExec = item.optString("vol_exec", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
''',
        '''            val price = (descr?.optString("price", "0") ?: "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val avgFill = item.optString("price", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val fee = item.optString("fee", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val clientOrderId = item.optString("cl_ord_id", "")
            val vol = item.optString("vol", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
            val volExec = item.optString("vol_exec", "0").toBigDecimalOrNull() ?: BigDecimal.ZERO
''',
        "M12 Kraken open-order fill economics"
    )
    t = replace_once(
        t,
        '''                status = item.optString("status", "open"),
                openedAtEpochSeconds = item.optLong("opentm", 0L),
                description = descr?.optString("order", "") ?: ""
            )
''',
        '''                status = item.optString("status", "open"),
                openedAtEpochSeconds = item.optLong("opentm", 0L),
                description = descr?.optString("order", "") ?: "",
                clientOrderId = clientOrderId,
                averageFillPrice = avgFill,
                fee = fee
            )
''',
        "M12 Kraken LiveOrderInfo fields"
    )
    t = replace_once(
        t,
        '''                closedAtEpochSeconds = item.optLong("closetm", item.optLong("opentm", 0L)),
                status = item.optString("status", "closed"),
                description = descr?.optString("order", "") ?: ""
            )
''',
        '''                closedAtEpochSeconds = item.optLong("closetm", item.optLong("opentm", 0L)),
                status = item.optString("status", "closed"),
                description = descr?.optString("order", "") ?: "",
                clientOrderId = item.optString("cl_ord_id", "")
            )
''',
        "M12 Kraken ClosedOrderInfo cl_ord_id"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # Lifecycle
    p = repo / "app/src/main/java/com/ksp/cryptobot/lifecycle/TradeLifecycleManager.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''    private val protectiveStops = governanceDao?.let { ProtectiveStopManager(dao, it) }
    private fun log(message: String, level: String = "INFO") = statusStore.write(message, level)
''',
        '''    private val protectiveStops = governanceDao?.let { ProtectiveStopManager(dao, it) }
    private val partialFillSynchronizer = PartialFillSynchronizer(dao, statusStore, protectiveStops)
    private fun log(message: String, level: String = "INFO") = statusStore.write(message, level)
''',
        "M12 partial-fill synchronizer initialization"
    )
    t = replace_once(
        t,
        '''        if (settings.syncKrakenHistory && settings.mode != BotMode.PAPER) syncClosedOrders(settings, exchange)
        refreshPositionRows(settings, exchange)
''',
        '''        if (settings.syncKrakenHistory && settings.mode != BotMode.PAPER) syncClosedOrders(settings, exchange)
        partialFillSynchronizer.sync(settings, exchange)
        refreshPositionRows(settings, exchange)
''',
        "M12 partial-fill lifecycle hook"
    )
    t = replace_once(
        t,
        '''            val syncedStrategyId = pendingPlan?.strategyId ?: HandoffPositionPlanCodec.decode(pendingPosition?.source)?.strategyId ?: "KRAKEN_SYNC"
            val syncedEntry = pendingPosition?.entryPriceEur?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val syncedRealized = if (order.side == OrderSide.SELL && syncedEntry > BigDecimal.ZERO && order.price > BigDecimal.ZERO) order.price.subtract(syncedEntry).multiply(order.executedQuantity).subtract(order.fee) else BigDecimal.ZERO
            val exists = dao.recentTradesSnapshot(300).any { it.exchangeOrderId == order.exchangeOrderId }
            if (!exists) {
''',
        '''            val syncedStrategyId = pendingPlan?.strategyId ?: HandoffPositionPlanCodec.decode(pendingPosition?.source)?.strategyId ?: "KRAKEN_SYNC"
            val syncedEntry = pendingPosition?.entryPriceEur?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val alreadyRecordedRows = dao.recentTradesSnapshot(500).filter {
                it.exchangeOrderId == order.exchangeOrderId && it.side.equals(order.side.name, true)
            }
            val alreadyRecordedQty = alreadyRecordedRows.fold(BigDecimal.ZERO) { acc, row ->
                acc + (row.quantity.toBigDecimalOrNull() ?: BigDecimal.ZERO)
            }
            val alreadyRecordedFee = alreadyRecordedRows.fold(BigDecimal.ZERO) { acc, row ->
                acc + (row.feeEur.toBigDecimalOrNull() ?: BigDecimal.ZERO)
            }
            val deltaQty = PartialFillMath.incrementalQuantity(order.executedQuantity, alreadyRecordedQty)
            val deltaFee = PartialFillMath.incrementalFee(order.fee, alreadyRecordedFee)
            val syncedRealized = if (order.side == OrderSide.SELL && syncedEntry > BigDecimal.ZERO && order.price > BigDecimal.ZERO) {
                order.price.subtract(syncedEntry).multiply(deltaQty).subtract(deltaFee)
            } else BigDecimal.ZERO
            if (deltaQty > BigDecimal.ZERO) {
''',
        "M12 closed-order cumulative fill delta"
    )
    t = replace_once(
        t,
        '''                        symbol = order.symbol,
                        side = order.side.name,
                        quantity = order.executedQuantity.toPlainString(),
                        priceEur = order.price.toPlainString(),
                        feeEur = order.fee.toPlainString(),
                        paper = false,
''',
        '''                        symbol = order.symbol,
                        side = order.side.name,
                        quantity = deltaQty.toPlainString(),
                        priceEur = order.price.toPlainString(),
                        feeEur = deltaFee.toPlainString(),
                        paper = false,
''',
        "M12 closed-order journal delta fields"
    )
    t = replace_once(
        t,
        '''                        clientOrderId = "kraken-sync-${order.exchangeOrderId}",
''',
        '''                        clientOrderId = order.clientOrderId.ifBlank { "kraken-sync-${order.exchangeOrderId}" },
''',
        "M12 closed-order client-order id"
    )

    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # Protective-stop incremental coverage
    p = repo / "app/src/main/java/com/ksp/cryptobot/execution/ProtectiveStopManager.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''        val standalone = runCatching {
            exchange.placeOrder(OrderRequest(
                symbol=symbol,
                side=OrderSide.SELL,
                quantity=quantity,
''',
        '''        val existingBeforeStandalone = activeStops(exchange, symbol, stopPrice)
        val coveredBeforeStandalone = existingBeforeStandalone.fold(BigDecimal.ZERO) { acc, o -> acc + o.remainingQuantity }
        val missingCoverage = quantity.subtract(coveredBeforeStandalone).max(BigDecimal.ZERO)
        if (missingCoverage <= quantity.multiply(BigDecimal("0.02"))) {
            clearUnprotectedState(symbol)
            return ProtectionResult(true,false,false,existingBeforeStandalone.map { it.exchangeOrderId },"Protective stop coverage already sufficient after refresh. covered=$coveredBeforeStandalone requested=$quantity.")
        }
        val standalone = runCatching {
            exchange.placeOrder(OrderRequest(
                symbol=symbol,
                side=OrderSide.SELL,
                quantity=missingCoverage,
''',
        "M12 delta protective-stop coverage"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # Controller strict order truth + distributed entry gate
    p = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    t = p.read_text(encoding="utf-8")
    old_reconcile = '''        val reconciliation = advancedExecution.reconcileLive(settings, exchange)
        if (settings.exchangeProvider == ExchangeProvider.KRAKEN) {
            KrakenPrivateExecutionRegistry.markRestReconciled(reconciliation.openOrders)
        }
'''
    new_reconcile = '''        val reconciliation = advancedExecution.reconcileLive(settings, exchange)
        if (exchange is KrakenSpotClient) {
            val orderTruth = com.ksp.cryptobot.execution.KrakenOrderTruthResolver.resolveDurable(exchange)
            orderTruth.messages.take(8).forEach { updateStatus("M12 order truth: $it", if (orderTruth.unresolved > 0) "WARN" else "INFO") }
            require(orderTruth.unresolved == 0) {
                "Kraken durable client-order ambiguity remains unresolved (${orderTruth.unresolved}); LIVE entry authority stays blocked."
            }
        }
        if (settings.exchangeProvider == ExchangeProvider.KRAKEN) {
            KrakenPrivateExecutionRegistry.markRestReconciled(reconciliation.openOrders)
        }
'''

    def patch_reconcile_scope(text, start_marker, end_marker, label):
        start = text.find(start_marker)
        if start < 0:
            fail(f"{label}: start marker missing")
        end = text.find(end_marker, start + len(start_marker))
        if end < 0:
            fail(f"{label}: end marker missing")
        body = text[start:end]
        matches = body.count(old_reconcile)
        if matches != 1:
            fail(f"{label}: expected one reconciliation anchor, got {matches}")
        return text[:start] + body.replace(old_reconcile, new_reconcile, 1) + text[end:]

    t = patch_reconcile_scope(
        t,
        "suspend fun reconcileLiveExecutionState(",
        "suspend fun loadLifecycleSnapshot(",
        "M12 strict startup/recovery reconciliation"
    )
    t = patch_reconcile_scope(
        t,
        "suspend fun scanOnce(",
        "private suspend fun selectSymbolUniverse(",
        "M12 LIVE scan reconciliation"
    )

    gate_anchor = '''        if (
            settings.mode == BotMode.LIVE_AUTO &&
            settings.exchangeProvider == ExchangeProvider.KRAKEN &&
            request.side == OrderSide.BUY
        ) {
'''
    authority_gate = '''        if (settings.mode != BotMode.PAPER && request.side == OrderSide.BUY) {
            val authority = com.ksp.cryptobot.execution.EngineAuthorityRuntime.canSubmitNewEntry(settings.mode)
            if (!authority.first) {
                updateStatus("LIVE entry blocked by distributed engine-authority gate: ${authority.second}", "ERROR")
                return ExecutionAttemptResult(false)
            }
        }

'''
    if gate_anchor not in t:
        fail("M12 engine authority entry-gate anchor missing")
    t = t.replace(gate_anchor, authority_gate + gate_anchor, 1)
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    # Foreground service authority acquisition
    p = repo / "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''import com.ksp.cryptobot.governance.ProductionIntelligenceServiceMonitor
''',
        '''import com.ksp.cryptobot.governance.ProductionIntelligenceServiceMonitor
import com.ksp.cryptobot.execution.EngineAuthorityLeaseManager
''',
        "M12 authority manager import"
    )
    t = replace_once(
        t,
        '''    private lateinit var connectivity: RuntimeConnectivityMonitor
''',
        '''    private lateinit var connectivity: RuntimeConnectivityMonitor
    private lateinit var authorityLease: EngineAuthorityLeaseManager
''',
        "M12 authority manager field"
    )
    t = replace_once(
        t,
        '''        hostStore = RuntimeHostStateStore(applicationContext)
        connectivity = RuntimeConnectivityMonitor(applicationContext) { state ->
''',
        '''        hostStore = RuntimeHostStateStore(applicationContext)
        authorityLease = EngineAuthorityLeaseManager(applicationContext)
        connectivity = RuntimeConnectivityMonitor(applicationContext) { state ->
''',
        "M12 authority manager initialize"
    )
    t = replace_once(
        t,
        '''            configureRealtimeMarketData(startSettings, connectivity.snapshot.usable)
            configurePrivateExecutionState(startSettings, connectivity.snapshot.usable)

            if (startSettings.mode == BotMode.LIVE_AUTO) {
''',
        '''            configureRealtimeMarketData(startSettings, connectivity.snapshot.usable)
            configurePrivateExecutionState(startSettings, connectivity.snapshot.usable)

            if (startSettings.mode != BotMode.PAPER && startSettings.exchangeProvider != ExchangeProvider.PAPER) {
                updateNotification("Acquiring distributed LIVE engine authority…")
                val authority = runCatching { authorityLease.acquire(startSettings) }.getOrElse { error ->
                    com.ksp.cryptobot.execution.EngineAuthoritySnapshot(false, "ERROR", reason = error.message ?: error.javaClass.simpleName)
                }
                if (!authority.authorized) {
                    hostStore.failure("LIVE authority blocked: ${authority.state}: ${authority.reason}")
                    statusStore.write("LIVE start blocked by distributed authority: ${authority.state}: ${authority.reason}", "ERROR")
                    updateNotification("LIVE blocked: engine authority unavailable")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
                statusStore.write("Distributed LIVE authority acquired. engine=${authority.engineId}, state=${authority.state}, expires=${authority.expiresAtEpochMs}.", "LIVE")
            }

            if (startSettings.mode == BotMode.LIVE_AUTO) {
''',
        "M12 acquire authority before LIVE start"
    )
    t = replace_once(
        t,
        '''        KrakenRealtimeMarketDataRegistry.stop()
        KrakenPrivateExecutionRegistry.stop()
        controller.stop()
''',
        '''        KrakenRealtimeMarketDataRegistry.stop()
        KrakenPrivateExecutionRegistry.stop()
        authorityLease.stop()
        controller.stop()
''',
        "M12 release authority on stop"
    )
    t = replace_once(
        t,
        '''    override fun onDestroy() {
        KrakenRealtimeMarketDataRegistry.stop()
        KrakenPrivateExecutionRegistry.stop()
        connectivity.stop()
''',
        '''    override fun onDestroy() {
        KrakenRealtimeMarketDataRegistry.stop()
        KrakenPrivateExecutionRegistry.stop()
        authorityLease.stop()
        connectivity.stop()
''',
        "M12 release authority on service destruction"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    actual = changed | untracked
    allowed = set(NEW_FILES) | {
        "app/src/main/java/com/ksp/cryptobot/core/Models.kt",
        "app/src/main/java/com/ksp/cryptobot/cloudshare/CloudShareClient.kt",
        "app/src/main/assets/cloudshare_setup/schema.sql",
        "app/src/main/assets/cloudshare_setup/cloudshare-worker.js",
        "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt",
        "app/src/main/java/com/ksp/cryptobot/lifecycle/TradeLifecycleManager.kt",
        "app/src/main/java/com/ksp/cryptobot/execution/ProtectiveStopManager.kt",
        "app/src/main/java/com/ksp/cryptobot/core/BotController.kt",
        "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt",
    }
    if actual - allowed:
        fail("Unexpected M12 app changes: " + ",".join(sorted(actual - allowed)))
    if allowed - actual:
        fail("Expected M12 changes missing: " + ",".join(sorted(allowed - actual)))
    print("PASS | M12 controlled app diff.")

if __name__ == "__main__":
    main()
