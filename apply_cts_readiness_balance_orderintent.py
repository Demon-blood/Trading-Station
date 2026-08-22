#!/usr/bin/env python3
# Crypto TradeStation v4.0.7: CTS-READINESS-001, CTS-BALANCE-002, CTS-ORDERINTENT-003.
# Authority: Crypto_TradeStation_Trading_Research_Handoff_v1-1.md (2026-08-22).
# This patch intentionally adds no trading strategies.

from __future__ import annotations
import re, sys
from pathlib import Path

MARKER = "CTS_READINESS_BALANCE_ORDERINTENT_20260822"
READINESS_SOURCE = 'package com.ksp.cryptobot.warmup\n\nimport android.content.Context\nimport com.ksp.cryptobot.core.BotMode\nimport kotlinx.coroutines.TimeoutCancellationException\nimport kotlinx.coroutines.flow.MutableStateFlow\nimport kotlinx.coroutines.flow.StateFlow\nimport kotlinx.coroutines.withTimeout\n\nenum class StartupStage {\n    STARTING,\n    LOADING_CONFIG,\n    OPENING_LOCAL_DB,\n    LOADING_LOCAL_MARKET_CACHE,\n    CONNECTING_CLOUDSHARE,\n    FETCHING_KRAKEN_ASSET_PAIRS,\n    FETCHING_RECENT_MARKET_DATA,\n    RECONCILING_CANDLES,\n    BUILDING_INDICATORS,\n    RESTORING_PORTFOLIO,\n    RESTORING_STRATEGY_STATE,\n    INITIALIZING_LEARNER,\n    READINESS_CHECK\n}\n\nenum class StartupStageStatus { PENDING, RUNNING, READY, DEGRADED, FAILED, TIMED_OUT, SKIPPED }\nenum class TradingReadiness { STARTING, READY, DEGRADED_READY, FAILED }\n\ndata class StartupStagePolicy(\n    val requiredForPaper: Boolean,\n    val requiredForLive: Boolean,\n    val timeoutMs: Long\n) {\n    fun required(mode: BotMode): Boolean = if (mode == BotMode.PAPER) requiredForPaper else requiredForLive\n}\n\ndata class ReadinessProbeResult(\n    val detail: String = "OK",\n    val itemsDone: Int = 1,\n    val itemsTotal: Int = 1\n)\n\ndata class StartupStageSnapshot(\n    val stage: StartupStage,\n    val status: StartupStageStatus,\n    val requiredForMode: Boolean,\n    val startTimeEpochMs: Long = 0L,\n    val lastProgressTimeEpochMs: Long = 0L,\n    val completedTimeEpochMs: Long = 0L,\n    val itemsDone: Int = 0,\n    val itemsTotal: Int = 0,\n    val retryCount: Int = 0,\n    val lastError: String = "",\n    val detail: String = ""\n) {\n    val elapsedMs: Long\n        get() = when {\n            startTimeEpochMs <= 0L -> 0L\n            completedTimeEpochMs > 0L -> completedTimeEpochMs - startTimeEpochMs\n            else -> System.currentTimeMillis() - startTimeEpochMs\n        }\n}\n\ndata class ReadinessSnapshot(\n    val mode: BotMode,\n    val overall: TradingReadiness,\n    val startedAtEpochMs: Long,\n    val completedAtEpochMs: Long = 0L,\n    val stages: List<StartupStageSnapshot> = emptyList(),\n    val reason: String = ""\n) {\n    val currentStage: StartupStageSnapshot?\n        get() = stages.lastOrNull { it.status == StartupStageStatus.RUNNING }\n            ?: stages.lastOrNull { it.status != StartupStageStatus.PENDING }\n}\n\ndata class ReadinessProbes(\n    val starting: suspend () -> ReadinessProbeResult = { ReadinessProbeResult("Controller started.") },\n    val loadConfig: suspend () -> ReadinessProbeResult = { ReadinessProbeResult() },\n    val openLocalDb: suspend () -> ReadinessProbeResult = { ReadinessProbeResult() },\n    val loadLocalMarketCache: suspend () -> ReadinessProbeResult = { ReadinessProbeResult() },\n    val connectCloudShare: suspend () -> ReadinessProbeResult = { ReadinessProbeResult("Optional dependency not configured.") },\n    val fetchKrakenAssetPairs: suspend () -> ReadinessProbeResult = { ReadinessProbeResult() },\n    val fetchRecentMarketData: suspend () -> ReadinessProbeResult = { ReadinessProbeResult() },\n    val reconcileCandles: suspend () -> ReadinessProbeResult = { ReadinessProbeResult() },\n    val buildIndicators: suspend () -> ReadinessProbeResult = { ReadinessProbeResult() },\n    val restorePortfolio: suspend () -> ReadinessProbeResult = { ReadinessProbeResult() },\n    val restoreStrategyState: suspend () -> ReadinessProbeResult = { ReadinessProbeResult() },\n    val initializeLearner: suspend () -> ReadinessProbeResult = { ReadinessProbeResult() },\n    val readinessCheck: suspend () -> ReadinessProbeResult = { ReadinessProbeResult() }\n)\n\n/**\n * Operational readiness is deliberately separate from per-symbol/model sample maturity.\n * A new symbol can be 0/N learning samples while the bot is READY.\n */\nclass ReadinessCoordinator(\n    context: Context? = null,\n    private val timeoutOverrideMs: Long? = null\n) {\n    private val prefs = context?.applicationContext\n        ?.getSharedPreferences("cts_startup_readiness_v1", Context.MODE_PRIVATE)\n    private val _state = MutableStateFlow(\n        ReadinessSnapshot(BotMode.PAPER, TradingReadiness.STARTING, System.currentTimeMillis())\n    )\n    val state: StateFlow<ReadinessSnapshot> = _state\n\n    private val policies: Map<StartupStage, StartupStagePolicy> = mapOf(\n        StartupStage.STARTING to StartupStagePolicy(true, true, 5_000L),\n        StartupStage.LOADING_CONFIG to StartupStagePolicy(true, true, 5_000L),\n        StartupStage.OPENING_LOCAL_DB to StartupStagePolicy(true, true, 10_000L),\n        StartupStage.LOADING_LOCAL_MARKET_CACHE to StartupStagePolicy(false, false, 8_000L),\n        StartupStage.CONNECTING_CLOUDSHARE to StartupStagePolicy(false, false, 5_000L),\n        StartupStage.FETCHING_KRAKEN_ASSET_PAIRS to StartupStagePolicy(true, true, 15_000L),\n        StartupStage.FETCHING_RECENT_MARKET_DATA to StartupStagePolicy(true, true, 20_000L),\n        StartupStage.RECONCILING_CANDLES to StartupStagePolicy(true, true, 8_000L),\n        StartupStage.BUILDING_INDICATORS to StartupStagePolicy(true, true, 8_000L),\n        StartupStage.RESTORING_PORTFOLIO to StartupStagePolicy(true, true, 15_000L),\n        StartupStage.RESTORING_STRATEGY_STATE to StartupStagePolicy(true, true, 8_000L),\n        StartupStage.INITIALIZING_LEARNER to StartupStagePolicy(false, false, 10_000L),\n        StartupStage.READINESS_CHECK to StartupStagePolicy(true, true, 5_000L)\n    )\n\n    fun reset(mode: BotMode = _state.value.mode) {\n        _state.value = ReadinessSnapshot(mode, TradingReadiness.STARTING, System.currentTimeMillis())\n        persist(_state.value)\n    }\n\n    fun current(): ReadinessSnapshot = _state.value\n\n    suspend fun ensureReady(\n        mode: BotMode,\n        probes: ReadinessProbes,\n        force: Boolean = false,\n        onProgress: (StartupStageSnapshot) -> Unit = {}\n    ): ReadinessSnapshot {\n        val existing = _state.value\n        if (!force && existing.mode == mode &&\n            existing.overall in setOf(TradingReadiness.READY, TradingReadiness.DEGRADED_READY)\n        ) return existing\n\n        val started = System.currentTimeMillis()\n        val rows = StartupStage.values().associateWith { stage ->\n            StartupStageSnapshot(\n                stage = stage,\n                status = StartupStageStatus.PENDING,\n                requiredForMode = policies.getValue(stage).required(mode)\n            )\n        }.toMutableMap()\n\n        fun publish(reason: String = "") {\n            val terminal = rows.values.any {\n                it.requiredForMode && it.status in setOf(StartupStageStatus.FAILED, StartupStageStatus.TIMED_OUT)\n            }\n            val overall = if (terminal) TradingReadiness.FAILED else TradingReadiness.STARTING\n            _state.value = ReadinessSnapshot(mode, overall, started, stages = rows.values.toList(), reason = reason)\n            persist(_state.value)\n        }\n\n        _state.value = ReadinessSnapshot(mode, TradingReadiness.STARTING, started, stages = rows.values.toList())\n        persist(_state.value)\n\n        val probeByStage: Map<StartupStage, suspend () -> ReadinessProbeResult> = mapOf(\n            StartupStage.STARTING to probes.starting,\n            StartupStage.LOADING_CONFIG to probes.loadConfig,\n            StartupStage.OPENING_LOCAL_DB to probes.openLocalDb,\n            StartupStage.LOADING_LOCAL_MARKET_CACHE to probes.loadLocalMarketCache,\n            StartupStage.CONNECTING_CLOUDSHARE to probes.connectCloudShare,\n            StartupStage.FETCHING_KRAKEN_ASSET_PAIRS to probes.fetchKrakenAssetPairs,\n            StartupStage.FETCHING_RECENT_MARKET_DATA to probes.fetchRecentMarketData,\n            StartupStage.RECONCILING_CANDLES to probes.reconcileCandles,\n            StartupStage.BUILDING_INDICATORS to probes.buildIndicators,\n            StartupStage.RESTORING_PORTFOLIO to probes.restorePortfolio,\n            StartupStage.RESTORING_STRATEGY_STATE to probes.restoreStrategyState,\n            StartupStage.INITIALIZING_LEARNER to probes.initializeLearner,\n            StartupStage.READINESS_CHECK to probes.readinessCheck\n        )\n\n        for (stage in StartupStage.values()) {\n            val policy = policies.getValue(stage)\n            val required = policy.required(mode)\n            val start = System.currentTimeMillis()\n            var row = rows.getValue(stage).copy(\n                status = StartupStageStatus.RUNNING,\n                startTimeEpochMs = start,\n                lastProgressTimeEpochMs = start,\n                retryCount = rows.getValue(stage).retryCount + 1\n            )\n            rows[stage] = row\n            publish("Running $stage")\n            onProgress(row)\n\n            val timeout = timeoutOverrideMs ?: policy.timeoutMs\n            try {\n                val result = withTimeout(timeout) { probeByStage.getValue(stage).invoke() }\n                val done = System.currentTimeMillis()\n                row = row.copy(\n                    status = StartupStageStatus.READY,\n                    lastProgressTimeEpochMs = done,\n                    completedTimeEpochMs = done,\n                    itemsDone = result.itemsDone.coerceAtLeast(0),\n                    itemsTotal = result.itemsTotal.coerceAtLeast(result.itemsDone.coerceAtLeast(0)),\n                    detail = result.detail,\n                    lastError = ""\n                )\n            } catch (error: TimeoutCancellationException) {\n                val done = System.currentTimeMillis()\n                row = row.copy(\n                    status = if (required) StartupStageStatus.TIMED_OUT else StartupStageStatus.DEGRADED,\n                    lastProgressTimeEpochMs = done,\n                    completedTimeEpochMs = done,\n                    lastError = "Timed out after ${timeout}ms",\n                    detail = if (required) "Required dependency timed out." else "Optional dependency timed out; trading readiness continues."\n                )\n            } catch (error: Throwable) {\n                val done = System.currentTimeMillis()\n                row = row.copy(\n                    status = if (required) StartupStageStatus.FAILED else StartupStageStatus.DEGRADED,\n                    lastProgressTimeEpochMs = done,\n                    completedTimeEpochMs = done,\n                    lastError = error.message ?: error.javaClass.simpleName,\n                    detail = if (required) "Required dependency failed." else "Optional dependency failed; trading readiness continues."\n                )\n            }\n            rows[stage] = row\n            publish(row.lastError)\n            onProgress(row)\n            if (required && row.status in setOf(StartupStageStatus.FAILED, StartupStageStatus.TIMED_OUT)) break\n        }\n\n        val requiredFailure = rows.values.firstOrNull {\n            it.requiredForMode && it.status in setOf(StartupStageStatus.FAILED, StartupStageStatus.TIMED_OUT)\n        }\n        val degraded = rows.values.any { it.status == StartupStageStatus.DEGRADED }\n        val completed = System.currentTimeMillis()\n        val final = when {\n            requiredFailure != null -> ReadinessSnapshot(\n                mode, TradingReadiness.FAILED, started, completed, rows.values.toList(),\n                "Required stage ${requiredFailure.stage} failed: ${requiredFailure.lastError}"\n            )\n            degraded -> ReadinessSnapshot(\n                mode, TradingReadiness.DEGRADED_READY, started, completed, rows.values.toList(),\n                "Mandatory trading dependencies are ready; optional dependency degraded."\n            )\n            else -> ReadinessSnapshot(\n                mode, TradingReadiness.READY, started, completed, rows.values.toList(),\n                "Mandatory trading dependencies are ready."\n            )\n        }\n        _state.value = final\n        persist(final)\n        return final\n    }\n\n    fun diagnosticsLines(): List<String> {\n        val snapshot = _state.value\n        val out = mutableListOf<String>()\n        out += "overall=${snapshot.overall}|mode=${snapshot.mode}|started=${snapshot.startedAtEpochMs}|completed=${snapshot.completedAtEpochMs}|reason=${snapshot.reason}"\n        snapshot.stages.forEach { row ->\n            out += "stage=${row.stage}|status=${row.status}|required=${row.requiredForMode}|start=${row.startTimeEpochMs}|lastProgress=${row.lastProgressTimeEpochMs}|completed=${row.completedTimeEpochMs}|elapsedMs=${row.elapsedMs}|items=${row.itemsDone}/${row.itemsTotal}|retry=${row.retryCount}|error=${row.lastError}|detail=${row.detail}"\n        }\n        return out\n    }\n\n    private fun persist(snapshot: ReadinessSnapshot) {\n        val edit = prefs?.edit() ?: return\n        edit.putString("overall", snapshot.overall.name)\n        edit.putString("mode", snapshot.mode.name)\n        edit.putLong("started_at", snapshot.startedAtEpochMs)\n        edit.putLong("completed_at", snapshot.completedAtEpochMs)\n        edit.putString("reason", snapshot.reason.take(1000))\n        snapshot.stages.forEach { row ->\n            edit.putString(\n                "stage_${row.stage.name.lowercase()}",\n                listOf(\n                    row.status.name, row.requiredForMode.toString(), row.startTimeEpochMs.toString(),\n                    row.lastProgressTimeEpochMs.toString(), row.completedTimeEpochMs.toString(),\n                    row.itemsDone.toString(), row.itemsTotal.toString(), row.retryCount.toString(),\n                    row.lastError.take(300), row.detail.take(500)\n                ).joinToString("|")\n            )\n        }\n        edit.apply()\n    }\n}\n'
BALANCE_SOURCE = 'package com.ksp.cryptobot.portfolio\n\nimport java.math.BigDecimal\nimport java.math.RoundingMode\n\ndata class SpendableBalanceInput(\n    val asset: String,\n    val exchangeBalance: BigDecimal,\n    val exchangeReserved: BigDecimal = BigDecimal.ZERO,\n    val ctsReserved: BigDecimal = BigDecimal.ZERO,\n    val pendingOrderReserve: BigDecimal = BigDecimal.ZERO,\n    val configuredSafetyReserve: BigDecimal = BigDecimal.ZERO\n)\n\ndata class SpendableBalanceSnapshot(\n    val asset: String,\n    val exchangeBalance: BigDecimal,\n    val exchangeReserved: BigDecimal,\n    val ctsReserved: BigDecimal,\n    val pendingOrderReserve: BigDecimal,\n    val configuredSafetyReserve: BigDecimal,\n    val spendable: BigDecimal\n) {\n    fun maxNotionalAfterFeeReserve(multiplier: BigDecimal): BigDecimal =\n        if (multiplier > BigDecimal.ZERO) spendable.divide(multiplier, 12, RoundingMode.DOWN) else BigDecimal.ZERO\n\n    fun diagnosticLine(requiredForOrder: BigDecimal? = null, code: String = "BALANCE_SNAPSHOT"): String = buildString {\n        append(code)\n        append(" | asset=$asset")\n        append(" | balance=${exchangeBalance.plain()}")\n        append(" | exchangeReserved=${exchangeReserved.plain()}")\n        append(" | ctsReserved=${ctsReserved.plain()}")\n        append(" | pendingOrderReserve=${pendingOrderReserve.plain()}")\n        append(" | safetyReserve=${configuredSafetyReserve.plain()}")\n        append(" | spendable=${spendable.plain()}")\n        requiredForOrder?.let { append(" | requiredForOrder=${it.plain()}") }\n    }\n\n    private fun BigDecimal.plain(): String =\n        setScale(8, RoundingMode.DOWN).stripTrailingZeros().toPlainString()\n}\n\nobject SpendableBalanceCalculator {\n    fun calculate(input: SpendableBalanceInput): SpendableBalanceSnapshot {\n        val balance = input.exchangeBalance.max(BigDecimal.ZERO)\n        val exchangeReserved = input.exchangeReserved.max(BigDecimal.ZERO).min(balance)\n        val afterExchange = balance.subtract(exchangeReserved).max(BigDecimal.ZERO)\n        val ctsReserved = input.ctsReserved.max(BigDecimal.ZERO).min(afterExchange)\n        val afterCts = afterExchange.subtract(ctsReserved).max(BigDecimal.ZERO)\n        val pending = input.pendingOrderReserve.max(BigDecimal.ZERO).min(afterCts)\n        val afterPending = afterCts.subtract(pending).max(BigDecimal.ZERO)\n        val safety = input.configuredSafetyReserve.max(BigDecimal.ZERO).min(afterPending)\n        val spendable = afterPending.subtract(safety).max(BigDecimal.ZERO)\n        return SpendableBalanceSnapshot(\n            asset = input.asset.uppercase(),\n            exchangeBalance = balance,\n            exchangeReserved = exchangeReserved,\n            ctsReserved = ctsReserved,\n            pendingOrderReserve = pending,\n            configuredSafetyReserve = safety,\n            spendable = spendable\n        )\n    }\n}\n'
ORDER_INTENT_SOURCE = 'package com.ksp.cryptobot.execution\n\nimport com.ksp.cryptobot.core.OrderRequest\nimport com.ksp.cryptobot.core.OrderResult\nimport com.ksp.cryptobot.core.OrderSide\nimport com.ksp.cryptobot.core.OrderType\nimport com.ksp.cryptobot.exchange.CryptoExchangeClient\nimport com.ksp.cryptobot.exchange.KrakenSpotClient\nimport java.math.BigDecimal\nimport java.util.concurrent.atomic.AtomicLong\n\nenum class IntentBrokerMode { PAPER, KRAKEN_VALIDATE, KRAKEN_LIVE }\n\ndata class OrderIntent(\n    val pair: String,\n    val side: OrderSide,\n    val orderType: OrderType,\n    val requestedQuantity: BigDecimal,\n    val limitOrTriggerPrice: BigDecimal?,\n    val timeInForce: String = "GTC",\n    val reduceOnly: Boolean = false,\n    val postOnly: Boolean = false,\n    val protectiveStopPrice: BigDecimal? = null,\n    val purpose: String = "ENTRY",\n    val clientOrderId: String,\n    val strategyId: String,\n    val riskBudgetQuote: BigDecimal = BigDecimal.ZERO\n) {\n    fun toOrderRequest(): OrderRequest = OrderRequest(\n        symbol = pair,\n        side = side,\n        quantity = requestedQuantity,\n        limitPrice = limitOrTriggerPrice,\n        orderType = orderType,\n        clientOrderId = clientOrderId,\n        reduceOnly = reduceOnly,\n        purpose = purpose,\n        postOnly = postOnly,\n        protectiveStopPrice = protectiveStopPrice,\n        timeInForce = timeInForce\n    )\n\n    fun semanticFields(): Map<String, String> = linkedMapOf(\n        "pair" to pair.uppercase().replace("/", "").replace("-", ""),\n        "side" to side.name,\n        "orderType" to orderType.name,\n        "requestedQuantity" to requestedQuantity.stripTrailingZeros().toPlainString(),\n        "limitOrTriggerPrice" to (limitOrTriggerPrice?.stripTrailingZeros()?.toPlainString() ?: "MARKET"),\n        "timeInForce" to timeInForce.uppercase(),\n        "reduceOnly" to reduceOnly.toString(),\n        "postOnly" to postOnly.toString(),\n        "protectiveStopPrice" to (protectiveStopPrice?.stripTrailingZeros()?.toPlainString() ?: "NONE"),\n        "purpose" to purpose,\n        "clientOrderId" to clientOrderId,\n        "strategyId" to strategyId,\n        "riskBudgetQuote" to riskBudgetQuote.stripTrailingZeros().toPlainString()\n    )\n}\n\nobject OrderIntentIds {\n    private val sequence = AtomicLong(System.currentTimeMillis())\n\n    fun next(strategyShort: String = "ai"): String {\n        val strategy = strategyShort.lowercase().filter { it.isLetterOrDigit() }.take(4).ifBlank { "ai" }\n        val suffix = java.lang.Long.toUnsignedString(sequence.incrementAndGet(), 36).takeLast(10)\n        return "cts-$strategy-$suffix".take(18)\n    }\n\n    fun sanitizeForKraken(id: String): String {\n        val clean = id.filter { it.code in 33..126 }.take(18)\n        return clean.ifBlank { next("ai") }\n    }\n}\n\ndata class KrakenOrderValidationResult(\n    val valid: Boolean,\n    val clientOrderId: String,\n    val symbol: String,\n    val description: String,\n    val semanticFields: Map<String, String>\n)\n\ndata class IntentBrokerSubmission(\n    val mode: IntentBrokerMode,\n    val semanticFields: Map<String, String>,\n    val orderResult: OrderResult? = null,\n    val validation: KrakenOrderValidationResult? = null\n)\n\ninterface OrderIntentBroker {\n    val mode: IntentBrokerMode\n    suspend fun submit(intent: OrderIntent): IntentBrokerSubmission\n}\n\nclass PaperIntentBroker(private val exchange: CryptoExchangeClient) : OrderIntentBroker {\n    override val mode = IntentBrokerMode.PAPER\n    override suspend fun submit(intent: OrderIntent): IntentBrokerSubmission =\n        IntentBrokerSubmission(mode, intent.semanticFields(), orderResult = exchange.placeOrder(intent.toOrderRequest()))\n}\n\nclass KrakenValidateBroker(private val client: KrakenSpotClient) : OrderIntentBroker {\n    override val mode = IntentBrokerMode.KRAKEN_VALIDATE\n    override suspend fun submit(intent: OrderIntent): IntentBrokerSubmission =\n        IntentBrokerSubmission(mode, intent.semanticFields(), validation = client.validateOrder(intent.toOrderRequest()))\n}\n\nclass KrakenLiveIntentBroker(private val client: KrakenSpotClient) : OrderIntentBroker {\n    override val mode = IntentBrokerMode.KRAKEN_LIVE\n    override suspend fun submit(intent: OrderIntent): IntentBrokerSubmission =\n        IntentBrokerSubmission(mode, intent.semanticFields(), orderResult = client.placeOrder(intent.toOrderRequest()))\n}\n\nobject OrderIntentParity {\n    fun semanticFields(intent: OrderIntent, mode: IntentBrokerMode): Map<String, String> {\n        mode.name\n        return intent.semanticFields()\n    }\n}\n'
READINESS_TEST = 'package com.ksp.cryptobot.warmup\n\nimport com.ksp.cryptobot.core.BotMode\nimport kotlinx.coroutines.delay\nimport kotlinx.coroutines.runBlocking\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\n\nclass ReadinessCoordinatorTest {\n    @Test\n    fun cloudShareTimeoutCannotLeavePaperInWarmup() = runBlocking {\n        val result = ReadinessCoordinator(null, 20L).ensureReady(\n            BotMode.PAPER,\n            ReadinessProbes(connectCloudShare = {\n                delay(100L)\n                ReadinessProbeResult("unreachable")\n            }),\n            force = true\n        )\n        assertEquals(TradingReadiness.DEGRADED_READY, result.overall)\n        assertEquals(StartupStageStatus.DEGRADED, result.stages.first { it.stage == StartupStage.CONNECTING_CLOUDSHARE }.status)\n    }\n\n    @Test\n    fun krakenPublicFailureFailsExplicitly() = runBlocking {\n        val result = ReadinessCoordinator(null, 100L).ensureReady(\n            BotMode.PAPER,\n            ReadinessProbes(fetchKrakenAssetPairs = { error("Kraken public unavailable") }),\n            force = true\n        )\n        assertEquals(TradingReadiness.FAILED, result.overall)\n        assertTrue(result.reason.contains("FETCHING_KRAKEN_ASSET_PAIRS"))\n    }\n\n    @Test\n    fun corruptLocalCacheDegradesAndContinues() = runBlocking {\n        val result = ReadinessCoordinator(null, 100L).ensureReady(\n            BotMode.PAPER,\n            ReadinessProbes(loadLocalMarketCache = { error("corrupt cached row") }),\n            force = true\n        )\n        assertEquals(TradingReadiness.DEGRADED_READY, result.overall)\n    }\n\n    @Test\n    fun staleCandlesFailAtNamedStage() = runBlocking {\n        val result = ReadinessCoordinator(null, 100L).ensureReady(\n            BotMode.PAPER,\n            ReadinessProbes(reconcileCandles = { error("stale committed candle set") }),\n            force = true\n        )\n        assertEquals(TradingReadiness.FAILED, result.overall)\n        assertTrue(result.reason.contains("RECONCILING_CANDLES"))\n    }\n\n    @Test\n    fun missingPrivateKeyCanFailLiveWithoutBlockingPaper() = runBlocking {\n        val live = ReadinessCoordinator(null, 100L).ensureReady(\n            BotMode.LIVE_AUTO,\n            ReadinessProbes(readinessCheck = { error("Kraken private credentials missing") }),\n            force = true\n        )\n        assertEquals(TradingReadiness.FAILED, live.overall)\n\n        val paper = ReadinessCoordinator(null, 100L).ensureReady(\n            BotMode.PAPER,\n            ReadinessProbes(readinessCheck = { ReadinessProbeResult("Paper needs no private key.") }),\n            force = true\n        )\n        assertEquals(TradingReadiness.READY, paper.overall)\n    }\n}\n'
BALANCE_TEST = 'package com.ksp.cryptobot.portfolio\n\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\nimport java.math.BigDecimal\n\nclass SpendableBalanceTest {\n    @Test\n    fun structuredExampleMatchesHandoff() {\n        val result = SpendableBalanceCalculator.calculate(\n            SpendableBalanceInput(\n                "EUR", BigDecimal("46.18"), BigDecimal.ZERO, BigDecimal.ZERO,\n                BigDecimal.ZERO, BigDecimal("2.00")\n            )\n        )\n        assertEquals(0, result.spendable.compareTo(BigDecimal("44.18")))\n        val line = result.diagnosticLine(BigDecimal("48.00"), "REJECTED_INSUFFICIENT_SPENDABLE")\n        assertTrue(line.contains("balance=46.18"))\n        assertTrue(line.contains("safetyReserve=2"))\n        assertTrue(line.contains("spendable=44.18"))\n        assertTrue(line.contains("requiredForOrder=48"))\n    }\n\n    @Test\n    fun reservationsNeverMakeSpendableNegative() {\n        val result = SpendableBalanceCalculator.calculate(\n            SpendableBalanceInput("EUR", BigDecimal("10"), BigDecimal("8"), BigDecimal("4"), BigDecimal("3"), BigDecimal("2"))\n        )\n        assertEquals(0, result.spendable.compareTo(BigDecimal.ZERO))\n    }\n}\n'
ORDER_INTENT_TEST = 'package com.ksp.cryptobot.execution\n\nimport com.ksp.cryptobot.core.OrderSide\nimport com.ksp.cryptobot.core.OrderType\nimport org.junit.Assert.assertEquals\nimport org.junit.Assert.assertTrue\nimport org.junit.Test\nimport java.math.BigDecimal\n\nclass OrderIntentParityTest {\n    private val intent = OrderIntent(\n        pair = "BTCEUR",\n        side = OrderSide.BUY,\n        orderType = OrderType.LIMIT,\n        requestedQuantity = BigDecimal("0.00123400"),\n        limitOrTriggerPrice = BigDecimal("61000.50"),\n        timeInForce = "GTC",\n        clientOrderId = "cts-ai-abc123",\n        strategyId = "AI_PIPELINE_V1",\n        riskBudgetQuote = BigDecimal("75.25")\n    )\n\n    @Test\n    fun paperValidateLiveSemanticFieldsAreIdentical() {\n        val paper = OrderIntentParity.semanticFields(intent, IntentBrokerMode.PAPER)\n        val validate = OrderIntentParity.semanticFields(intent, IntentBrokerMode.KRAKEN_VALIDATE)\n        val live = OrderIntentParity.semanticFields(intent, IntentBrokerMode.KRAKEN_LIVE)\n        assertEquals(paper, validate)\n        assertEquals(validate, live)\n    }\n\n    @Test\n    fun requestConversionPreservesFields() {\n        val request = intent.toOrderRequest()\n        assertEquals(intent.pair, request.symbol)\n        assertEquals(intent.side, request.side)\n        assertEquals(intent.orderType, request.orderType)\n        assertEquals(0, intent.requestedQuantity.compareTo(request.quantity))\n        assertEquals(0, intent.limitOrTriggerPrice!!.compareTo(request.limitPrice!!))\n        assertEquals(intent.clientOrderId, request.clientOrderId)\n    }\n\n    @Test\n    fun krakenFreeTextClientIdFitsLimit() {\n        repeat(100) {\n            val id = OrderIntentIds.next("turtle")\n            assertTrue(id.length <= 18)\n            assertTrue(id.startsWith("cts-"))\n        }\n    }\n}\n'

def fail(msg): raise SystemExit("[CTS readiness/balance/orderintent] " + msg)
def require(p):
    if not p.exists(): fail("Required file missing: " + str(p))
def read(p):
    require(p); return p.read_text(encoding="utf-8")
def write(p, text):
    p.parent.mkdir(parents=True, exist_ok=True); p.write_text(text.rstrip()+"\n", encoding="utf-8")
def replace_once(text, old, new, label):
    if new in text: return text
    n=text.count(old)
    if n!=1: fail(f"{label}: expected one match, found {n}")
    return text.replace(old,new,1)

def install_sources(repo):
    files = {
        "app/src/main/java/com/ksp/cryptobot/warmup/ReadinessCoordinator.kt": READINESS_SOURCE,
        "app/src/main/java/com/ksp/cryptobot/portfolio/SpendableBalance.kt": BALANCE_SOURCE,
        "app/src/main/java/com/ksp/cryptobot/execution/OrderIntent.kt": ORDER_INTENT_SOURCE,
        "app/src/test/java/com/ksp/cryptobot/warmup/ReadinessCoordinatorTest.kt": READINESS_TEST,
        "app/src/test/java/com/ksp/cryptobot/portfolio/SpendableBalanceTest.kt": BALANCE_TEST,
        "app/src/test/java/com/ksp/cryptobot/execution/OrderIntentParityTest.kt": ORDER_INTENT_TEST,
    }
    for rel, src in files.items(): write(repo/rel, src)


def patch_models(path):
    text=read(path)
    if 'val timeInForce: String = "GTC"' in text:
        return
    if 'val protectiveStopPrice: BigDecimal? = null' in text:
        text=text.replace(
            '    val protectiveStopPrice: BigDecimal? = null\n)',
            '    val protectiveStopPrice: BigDecimal? = null,\n    val timeInForce: String = "GTC"\n)',
            1
        )
    elif 'val postOnly: Boolean = false' in text:
        text=text.replace(
            '    val postOnly: Boolean = false\n)',
            '    val postOnly: Boolean = false,\n    val protectiveStopPrice: BigDecimal? = null,\n    val timeInForce: String = "GTC"\n)',
            1
        )
    else:
        old='    val reduceOnly: Boolean = false,\n    val purpose: String = "ENTRY"\n)'
        new='    val reduceOnly: Boolean = false,\n    val purpose: String = "ENTRY",\n    val postOnly: Boolean = false,\n    val protectiveStopPrice: BigDecimal? = null,\n    val timeInForce: String = "GTC"\n)'
        if old not in text:
            fail("OrderRequest model shape not recognized")
        text=text.replace(old,new,1)
    write(path,text)

def patch_controller(path):
    text=read(path)
    if MARKER in text:
        print("controller already patched:", path); return

    anchor="import com.ksp.cryptobot.execution.AdvancedRiskManager\n"
    if "import com.ksp.cryptobot.warmup.ReadinessCoordinator" not in text:
        add=(anchor+
             "import com.ksp.cryptobot.execution.OrderIntent\n"
             "import com.ksp.cryptobot.execution.OrderIntentIds\n"
             "import com.ksp.cryptobot.portfolio.SpendableBalanceCalculator\n"
             "import com.ksp.cryptobot.portfolio.SpendableBalanceInput\n"
             "import com.ksp.cryptobot.warmup.ReadinessCoordinator\n"
             "import com.ksp.cryptobot.warmup.ReadinessProbeResult\n"
             "import com.ksp.cryptobot.warmup.ReadinessProbes\n"
             "import com.ksp.cryptobot.warmup.TradingReadiness\n")
        text=replace_once(text,anchor,add,"controller imports")

    f="    private val remoteCommandClient = RemoteCommandClient()\n"
    fp=(f+"    // "+MARKER+"\n"
          "    private val readinessCoordinator = ReadinessCoordinator(appContext)\n"
          "    val readiness = readinessCoordinator.state\n")
    text=replace_once(text,f,fp,"readiness field")

    scan="    suspend fun scanOnce(settings: BotSettings = settingsStore.load(), execute: Boolean = false): List<AiDecision> {\n"
    helper=r'''    private suspend fun ensureTradingReadiness(
        settings: BotSettings,
        exchange: CryptoExchangeClient
    ): com.ksp.cryptobot.warmup.ReadinessSnapshot {
        val primarySymbol = settings.symbols().firstOrNull()?.uppercase()?.replace("/", "")?.replace("-", "") ?: "BTCEUR"
        val publicKraken = KrakenSpotClient(apiKey = "", secretKey = "")
        var recentRawCandles: List<Candle> = emptyList()
        val probes = ReadinessProbes(
            starting = { ReadinessProbeResult("Controller running=$running; mode=${settings.mode}; provider=${settings.exchangeProvider}.") },
            loadConfig = { settingsStore.load(); ReadinessProbeResult("Settings loaded.") },
            openLocalDb = { dao.recentTradesSnapshot(1); ReadinessProbeResult("Room database reachable.") },
            loadLocalMarketCache = {
                ReadinessProbeResult("Local state reachable; dedicated persistent candle-store migration is a later ticket.")
            },
            connectCloudShare = {
                ReadinessProbeResult("CloudShare classified OPTIONAL; readiness does not await remote sync.")
            },
            fetchKrakenAssetPairs = {
                val info = publicKraken.validateSymbol(primarySymbol)
                require(info.tradable) { "Primary symbol $primarySymbol is not tradable: ${info.reason}" }
                ReadinessProbeResult("AssetPairs OK: ${info.exchangePair}, ordermin=${info.minOrderSize}, costmin=${info.minOrderCost}.")
            },
            fetchRecentMarketData = {
                recentRawCandles = publicKraken.getCandles(primarySymbol, Timeframe.M15, 120)
                require(recentRawCandles.size >= 21) { "Only ${recentRawCandles.size} M15 rows returned; need >=21." }
                ReadinessProbeResult("Kraken OHLC rows=${recentRawCandles.size}.", recentRawCandles.size, 120)
            },
            reconcileCandles = {
                val committed = (recentRawCandles.size - 1).coerceAtLeast(0)
                require(committed >= 20) { "Committed candle set is stale/insufficient: $committed." }
                ReadinessProbeResult("committed=$committed; final Kraken REST OHLC row excluded as uncommitted.", committed, committed)
            },
            buildIndicators = {
                val committed = (recentRawCandles.size - 1).coerceAtLeast(0)
                require(committed >= 20) { "Insufficient committed bars for baseline indicators." }
                ReadinessProbeResult("Indicator prerequisites satisfied from committed bars only.", committed, committed)
            },
            restorePortfolio = {
                val rows = exchange.getPortfolioBalances()
                if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) {
                    require(rows.isNotEmpty()) { "PAPER wallet returned no portfolio state." }
                }
                ReadinessProbeResult("Portfolio restored; assets=${rows.size}.", rows.size, rows.size.coerceAtLeast(1))
            },
            restoreStrategyState = { dao.recentTradesSnapshot(1); ReadinessProbeResult("Strategy/local trade state reachable.") },
            initializeLearner = {
                ReadinessProbeResult(if (settings.trueSelfLearningEnabled)
                    "Learner enabled; sample maturity is per-symbol and does not block READY."
                    else "Learner disabled; trading readiness unaffected.")
            },
            readinessCheck = {
                if (settings.mode != BotMode.PAPER && settings.exchangeProvider == ExchangeProvider.KRAKEN) {
                    require(!settingsStore.exchangeApiKey(ExchangeProvider.KRAKEN).isNullOrBlank()) { "Kraken API key missing." }
                    require(!settingsStore.exchangeSecretKey(ExchangeProvider.KRAKEN).isNullOrBlank()) { "Kraken private key missing." }
                }
                if (settings.mode == BotMode.LIVE_AUTO) liveSafetyBlockReason(settings)?.let { error(it) }
                ReadinessProbeResult("Mandatory prerequisites satisfied for ${settings.mode}.")
            }
        )
        return readinessCoordinator.ensureReady(settings.mode, probes, onProgress = { stage ->
            val level = when (stage.status) {
                com.ksp.cryptobot.warmup.StartupStageStatus.FAILED,
                com.ksp.cryptobot.warmup.StartupStageStatus.TIMED_OUT -> "ERROR"
                com.ksp.cryptobot.warmup.StartupStageStatus.DEGRADED -> "WARN"
                else -> "INFO"
            }
            updateStatus("Readiness ${stage.stage}: ${stage.status} progress=${stage.itemsDone}/${stage.itemsTotal} retry=${stage.retryCount} ${stage.lastError.ifBlank { stage.detail }}", level)
        })
    }

    fun readinessDiagnostics(): List<String> = readinessCoordinator.diagnosticsLines()

'''
    text=replace_once(text,scan,helper+scan,"readiness helper")

    idx=text.index(scan)
    tail=text[idx:]
    ex="        val exchange = createExchange(settings)\n"
    if "val startupReadiness = ensureTradingReadiness" not in tail:
        p=tail.find(ex)
        if p<0: fail("scan exchange anchor missing")
        gate=ex+r'''        val startupReadiness = ensureTradingReadiness(settings, exchange)
        when (startupReadiness.overall) {
            TradingReadiness.FAILED -> {
                updateStatus("Trading readiness FAILED: ${startupReadiness.reason}. Scan/execution stopped.", "ERROR")
                return emptyList()
            }
            TradingReadiness.DEGRADED_READY ->
                updateStatus("Trading readiness DEGRADED_READY: mandatory dependencies ready; optional dependency degraded.", "WARN")
            TradingReadiness.READY ->
                updateStatus("Trading readiness READY. Learning maturity is tracked separately per symbol.", "INFO")
            TradingReadiness.STARTING -> {
                updateStatus("Trading readiness STARTING; execution is not allowed yet.", "WARN")
                return emptyList()
            }
        }
'''
        tail=tail[:p]+gate+tail[p+len(ex):]
        text=text[:idx]+tail

    reserve=("        val quoteReserve = quoteReserveAmount(settings, availableQuote)\n"
             "        val quoteReservedThisScan = reservedByQuoteThisScan[quoteAsset] ?: BigDecimal.ZERO\n")
    bblock=reserve+r'''        val quoteBalanceRow = runCatching { exchange.getPortfolioBalances() }.getOrDefault(emptyList())
            .firstOrNull { row ->
                val a = row.asset.uppercase()
                a == quoteAsset.uppercase() ||
                    (quoteAsset.equals("EUR", true) && a == "ZEUR") ||
                    (quoteAsset.equals("BTC", true) && a in setOf("XBT", "XXBT"))
            }
        val balanceBeforeExchangeReserve = quoteBalanceRow?.let { it.free.add(it.holdTrade) } ?: availableQuote
        val exchangeReservedQuote = quoteBalanceRow?.holdTrade
            ?: balanceBeforeExchangeReserve.subtract(availableQuote).max(BigDecimal.ZERO)
        val spendableQuote = SpendableBalanceCalculator.calculate(
            SpendableBalanceInput(
                asset = quoteAsset,
                exchangeBalance = balanceBeforeExchangeReserve,
                exchangeReserved = exchangeReservedQuote,
                ctsReserved = BigDecimal.ZERO,
                pendingOrderReserve = quoteReservedThisScan,
                configuredSafetyReserve = quoteReserve
            )
        )
        if (side == OrderSide.BUY) updateStatus("[${ticker.symbol}] ${spendableQuote.diagnosticLine()}", "INFO")
'''
    text=replace_once(text,reserve,bblock,"spendable balance")

    # Replace inline target-notional cash arithmetic by the single calculator.
    if "spendableQuote.maxNotionalAfterFeeReserve" not in text:
        pat=re.compile(r'''        (?P<decl>val|var) targetNotional = if \(side == OrderSide\.BUY.*?\n        \} else \{\n            perOrderCap\n        \}''',re.S)
        m=pat.search(text)
        if not m: fail("targetNotional block not found")
        decl=m.group("decl")
        repl=f'''        {decl} targetNotional = if (side == OrderSide.BUY) {{
            val spendableAfterReserve = spendableQuote.maxNotionalAfterFeeReserve(feeReserveMultiplier)
            when {{
                quoteAsset in setOf("EUR", "USD", "USDT", "USDC") -> perOrderCap.min(spendableAfterReserve)
                settings.nonEurQuoteBuyEnabled -> {{
                    val cryptoQuoteCap = balanceBeforeExchangeReserve.multiply(settings.maxNonEurQuoteSpendPercent)
                        .divide(BigDecimal("100"), 8, RoundingMode.DOWN)
                    perOrderCap.min(cryptoQuoteCap).min(spendableAfterReserve)
                }}
                else -> BigDecimal.ZERO
            }}
        }} else {{
            perOrderCap
        }}'''
        text=text[:m.start()]+repl+text[m.end():]

    # Existing exchange-minimum patch also computes max spendable; route that through same snapshot.
    a="        val maxSpendableForExchangeMinimum = if (side == OrderSide.BUY) {\n"
    b="        } else BigDecimal.ZERO\n\n        if (side == OrderSide.BUY && pairInfo != null) {\n"
    if a in text and b in text and "maxSpendableForExchangeMinimum = if (side == OrderSide.BUY) spendableQuote" not in text:
        s=text.index(a); e=text.index(b,s)+len("        } else BigDecimal.ZERO\n")
        repl=("        val maxSpendableForExchangeMinimum = if (side == OrderSide.BUY) "
              "spendableQuote.maxNotionalAfterFeeReserve(feeReserveMultiplier).min(perOrderCap) else BigDecimal.ZERO\n")
        text=text[:s]+repl+text[e:]

    old='                    updateStatus("Trade blocked: not enough free $quoteAsset to buy. API reports free $quoteAsset=${availableQuote.stripTrailingZeros().toPlainString()}.", "WARN")\n'
    new=('                    updateStatus(spendableQuote.diagnosticLine(requiredForOrder = minimumOrderNotional.multiply(feeReserveMultiplier), '
         'code = "REJECTED_INSUFFICIENT_SPENDABLE"), "WARN")\n')
    if old in text: text=text.replace(old,new,1)

    # Replace the final ad-hoc OrderRequest block, preserving advanced planner variables when present.
    if "val orderIntent = OrderIntent(" not in text:
        q=re.compile(r'''        val request = OrderRequest\(\n.*?\n        \)\n        val orderModeLabel''',re.S)
        m=q.search(text)
        if not m: fail("OrderRequest block not found")
        block=m.group(0)
        def field_expr(name, default):
            fm=re.search(r'(?m)^\\s*'+re.escape(name)+r'\\s*=\\s*(.*?)(?:,\\s*)?$', block)
            return fm.group(1).rstrip(',').strip() if fm else default
        price_expr=field_expr("limitPrice", "if (useMarketOrder) null else price")
        type_expr=field_expr("orderType", "if (useMarketOrder) OrderType.MARKET else OrderType.LIMIT")
        purpose_expr=field_expr("purpose", '"ENTRY"')
        post_expr=field_expr("postOnly", "false")
        stop_expr=field_expr("protectiveStopPrice", "null")
        reduce_expr=field_expr("reduceOnly", "false")
        repl=f'''        val orderIntent = OrderIntent(
            pair = ticker.symbol,
            side = side,
            orderType = {type_expr},
            requestedQuantity = quantity,
            limitOrTriggerPrice = {price_expr},
            timeInForce = "GTC",
            reduceOnly = {reduce_expr},
            postOnly = {post_expr},
            protectiveStopPrice = {stop_expr},
            purpose = {purpose_expr},
            clientOrderId = OrderIntentIds.next("ai"),
            strategyId = "AI_PIPELINE_V1",
            riskBudgetQuote = targetNotional
        )
        val request = orderIntent.toOrderRequest()
        updateStatus("[${{ticker.symbol}}] OrderIntent semantic=${{orderIntent.semanticFields()}}", "INFO")
        val orderModeLabel'''
        text=text[:m.start()]+repl+text[m.end():]

    oldstart='    fun start() {\n        running = true\n        updateStatus("Bot controller running.")\n    }\n'
    newstart='    fun start() {\n        running = true\n        readinessCoordinator.reset(settingsStore.load().mode)\n        updateStatus("STARTING — readiness stages run before the next scan. Learning warmup is separate.")\n    }\n'
    if oldstart in text: text=text.replace(oldstart,newstart,1)

    oldstop='    fun stop() {\n        running = false\n        updateStatus("Bot controller stopped.")\n    }\n'
    newstop='    fun stop() {\n        running = false\n        readinessCoordinator.reset(settingsStore.load().mode)\n        updateStatus("Bot controller stopped.")\n    }\n'
    if oldstop in text: text=text.replace(oldstop,newstop,1)

    v='        add("PASS", "Secure Exchange Key Store", "Encrypted key store is reachable. Keys are not exposed in diagnostics.")\n'
    if "Startup Readiness State" not in text and v in text:
        vp=v+r'''        val readinessSnapshot = readinessCoordinator.current()
        add(
            if (readinessSnapshot.overall == TradingReadiness.FAILED) "FAIL"
            else if (readinessSnapshot.overall == TradingReadiness.DEGRADED_READY) "WARN"
            else "PASS",
            "Startup Readiness State",
            "overall=${readinessSnapshot.overall}, mode=${readinessSnapshot.mode}, current=${readinessSnapshot.currentStage?.stage ?: "none"}, reason=${readinessSnapshot.reason}"
        )
'''
        text=text.replace(v,vp,1)

    if "[STARTUP_READINESS]" not in text:
        dm=re.search(r'(?m)^(\s*)([A-Za-z_][A-Za-z0-9_]*)\.appendLine\("\[RECENT_STATUS_LOG\]"\)',text)
        if dm:
            ind,var=dm.group(1),dm.group(2)
            ins=(f'{ind}{var}.appendLine("[STARTUP_READINESS]")\n'
                 f'{ind}readinessCoordinator.diagnosticsLines().forEach {{ {var}.appendLine(it.replace("\\n", " ")) }}\n'
                 f'{ind}{var}.appendLine()\n')
            text=text[:dm.start()]+ins+text[dm.start():]
        else:
            print("WARN: diagnostics anchor not found; readiness remains available through controller/System Test.")
    write(path,text)

def patch_kraken(path):
    text=read(path)
    if "suspend fun validateOrder(request: OrderRequest): KrakenOrderValidationResult" in text:
        print("Kraken validate already patched"); return

    imp="import com.ksp.cryptobot.core.*\n"
    if "import com.ksp.cryptobot.execution.KrakenOrderValidationResult" not in text:
        text=replace_once(text,imp,imp+"import com.ksp.cryptobot.execution.KrakenOrderValidationResult\nimport com.ksp.cryptobot.execution.OrderIntentIds\n","kraken imports")

    start=('        val path = "/0/private/AddOrder"\n'
           '        val nonce = System.currentTimeMillis().toString()\n'
           '        val orderType = when (request.orderType) {\n')
    end='        val encoded = encodeForm(form)\n'
    if start not in text: fail("Kraken AddOrder serializer start not found")
    s=text.index(start); e=text.index(end,s)
    repl=('        val path = "/0/private/AddOrder"\n'
          '        val nonce = System.currentTimeMillis().toString()\n'
          '        val form = buildKrakenAddOrderForm(request, rule, nonce, validateOnly = false)\n')
    text=text[:s]+repl+text[e:]

    anchor="    private fun queryOrderFill(txid: String, rule: KrakenPairRule, request: OrderRequest): OrderResult {\n"
    methods=r'''    suspend fun validateOrder(request: OrderRequest): KrakenOrderValidationResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Kraken credentials are required for AddOrder validate=true.")
        val rule = resolvePairRule(request.symbol)
        if (!rule.tradable) error("Kraken pair not tradable: ${request.symbol}. ${rule.status}")
        val path = "/0/private/AddOrder"
        val nonce = System.currentTimeMillis().toString()
        val form = buildKrakenAddOrderForm(request, rule, nonce, validateOnly = true)
        val encoded = encodeForm(form)
        val signature = krakenSignature(path, nonce, encoded, secretKey)
        val body = encoded.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())
        val req = Request.Builder().url("https://api.kraken.com$path")
            .addHeader("API-Key", apiKey).addHeader("API-Sign", signature).post(body).build()
        http.newCall(req).execute().use { res ->
            val responseBody = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("Kraken AddOrder validate HTTP ${res.code}: $responseBody")
            val root = org.json.JSONObject(responseBody)
            val errors = root.optJSONArray("error")
            if (errors != null && errors.length() > 0) error("Kraken AddOrder validate error: $errors")
            val description = root.optJSONObject("result")?.optJSONObject("descr")?.optString("order", "validated") ?: "validated"
            return@withContext KrakenOrderValidationResult(
                valid = true,
                clientOrderId = OrderIntentIds.sanitizeForKraken(request.clientOrderId),
                symbol = rule.canonicalSymbol,
                description = description,
                semanticFields = linkedMapOf(
                    "pair" to request.symbol.uppercase().replace("/", "").replace("-", ""),
                    "side" to request.side.name,
                    "orderType" to request.orderType.name,
                    "requestedQuantity" to request.quantity.stripTrailingZeros().toPlainString(),
                    "limitOrTriggerPrice" to (request.limitPrice?.stripTrailingZeros()?.toPlainString() ?: "MARKET"),
                    "timeInForce" to request.timeInForce.uppercase(),
                    "clientOrderId" to OrderIntentIds.sanitizeForKraken(request.clientOrderId),
                    "reduceOnly" to request.reduceOnly.toString(),
                    "postOnly" to request.postOnly.toString(),
                    "protectiveStopPrice" to (request.protectiveStopPrice?.stripTrailingZeros()?.toPlainString() ?: "NONE"),
                    "purpose" to request.purpose
                )
            )
        }
    }

    private suspend fun buildKrakenAddOrderForm(
        request: OrderRequest,
        rule: KrakenPairRule,
        nonce: String,
        validateOnly: Boolean
    ): LinkedHashMap<String, String> {
        val orderType = when (request.orderType) {
            OrderType.MARKET -> "market"
            OrderType.STOP_LOSS -> "stop-loss"
            OrderType.TAKE_PROFIT -> "take-profit"
            OrderType.LIMIT -> "limit"
        }
        val cleanQuantity = request.quantity.setScale(rule.quantityDecimals, RoundingMode.DOWN)
        if (cleanQuantity < rule.minOrderSize) {
            error("Kraken order size too small for ${rule.canonicalSymbol}. quantity=$cleanQuantity min=${rule.minOrderSize}")
        }

        val orderPriceForMinimum = if (request.orderType == OrderType.MARKET) {
            val liveTicker = getTicker(rule.canonicalSymbol)
            if (request.side == OrderSide.BUY) liveTicker.ask else liveTicker.bid
        } else request.limitPrice ?: error("Price/trigger is required for ${request.orderType}.")

        val estimatedOrderCost = cleanQuantity.multiply(orderPriceForMinimum)
        if (rule.minOrderCost > BigDecimal.ZERO && estimatedOrderCost < rule.minOrderCost) {
            error("Kraken order cost too small for ${rule.canonicalSymbol}. cost=$estimatedOrderCost minCost=${rule.minOrderCost}")
        }

        val tif = request.timeInForce.uppercase()
        require(tif in setOf("GTC", "IOC", "FOK")) {
            "Unsupported CTS Kraken timeInForce=$tif. GTD requires an explicit expiry and is not emitted by this OrderIntent version."
        }

        val form = linkedMapOf(
            "nonce" to nonce,
            "pair" to rule.exchangePair,
            "type" to if (request.side == OrderSide.BUY) "buy" else "sell",
            "ordertype" to orderType,
            "volume" to cleanQuantity.stripTrailingZeros().toPlainString(),
            "cl_ord_id" to OrderIntentIds.sanitizeForKraken(request.clientOrderId),
            "timeinforce" to tif,
            "validate" to validateOnly.toString()
        )
        if (request.reduceOnly) form["reduce_only"] = "true"

        if (request.orderType != OrderType.MARKET) {
            val rawPrice = request.limitPrice ?: error("Price/trigger is required for ${request.orderType}.")
            val rounded = roundKrakenPriceToTick(rawPrice, rule.tickSize, rule.priceDecimals, request.side, request.orderType)
            form["price"] = rounded.stripTrailingZeros().toPlainString()
        }

        if (request.postOnly) {
            if (request.orderType != OrderType.LIMIT) error("Kraken post-only is valid only for ordinary LIMIT orders.")
            form["oflags"] = "post"
        }

        request.protectiveStopPrice?.takeIf { request.side == OrderSide.BUY && it > BigDecimal.ZERO }?.let { rawStop ->
            val stop = roundKrakenPriceToTick(rawStop, rule.tickSize, rule.priceDecimals, OrderSide.SELL, OrderType.STOP_LOSS)
            if (stop >= orderPriceForMinimum) error("Protective stop must be below BUY reference. stop=$stop entryRef=$orderPriceForMinimum")
            form["close[ordertype]"] = "stop-loss"
            form["close[price]"] = stop.stripTrailingZeros().toPlainString()
        }
        return form
    }

'''
    text=replace_once(text,anchor,methods+anchor,"Kraken shared validate serializer")
    write(path,text)

def patch_workflow(path):
    text=read(path)
    text=text.replace('version_name = "4.0.6"','version_name = "4.0.7"')
    text=text.replace('version_code = 111','version_code = 112')
    text=text.replace('"v4.0.0 CTS", "v4.0.6 CTS"','"v4.0.0 CTS", "v4.0.7 CTS"')
    text=text.replace("'versionName 4.0.6': 'versionName = \"4.0.6\"' in gradle","'versionName 4.0.7': 'versionName = \"4.0.7\"' in gradle")
    text=text.replace("'versionCode 111': 'versionCode = 111' in gradle","'versionCode 112': 'versionCode = 112' in gradle")
    text=text.replace("'V4ReleaseInfo 4.0.6': 'VERSION_NAME = \"4.0.6\"' in release and 'VERSION_CODE = 111' in release",
                      "'V4ReleaseInfo 4.0.7': 'VERSION_NAME = \"4.0.7\"' in release and 'VERSION_CODE = 112' in release")
    text=text.replace("versionCode='111'","versionCode='112'")
    text=text.replace("versionName='4.0.6'","versionName='4.0.7'")
    text=text.replace("CryptoTradeStation-v4.0.6-","CryptoTradeStation-v4.0.7-")

    name="Apply CTS readiness, balance and OrderIntent tickets"
    if name not in text:
        anchor="      - name: Set update build identity\n"
        if anchor not in text: fail("workflow identity anchor missing")
        steps=r'''      - name: Apply v4.0.7 execution stabilization
        shell: bash
        run: |
          set -euo pipefail
          python3 -m py_compile .cts-v4-migration/apply_v4_0_7_stabilization.py
          python3 .cts-v4-migration/apply_v4_0_7_stabilization.py "$GITHUB_WORKSPACE" | tee stabilization-v407.log

      - name: Preserve current 2026-08-22 research-truth migration
        shell: bash
        run: |
          set -euo pipefail
          python3 -m py_compile .cts-v4-migration/apply_research_handoff_2026_08_22.py
          python3 .cts-v4-migration/apply_research_handoff_2026_08_22.py "$GITHUB_WORKSPACE" | tee research-handoff-20260822.log

      - name: Apply CTS readiness, balance and OrderIntent tickets
        shell: bash
        run: |
          set -euo pipefail
          python3 -m py_compile .cts-v4-migration/apply_cts_readiness_balance_orderintent.py
          python3 .cts-v4-migration/apply_cts_readiness_balance_orderintent.py "$GITHUB_WORKSPACE" | tee readiness-balance-orderintent.log

      - name: Validate CTS readiness/balance/OrderIntent contracts
        shell: bash
        run: |
          set -euo pipefail
          python3 - <<'PYCTS'
          from pathlib import Path
          root=Path('app/src/main/java/com/ksp/cryptobot')
          controller=(root/'core/BotController.kt').read_text(encoding='utf-8')
          kraken=(root/'exchange/ExchangeClientsV08.kt').read_text(encoding='utf-8')
          readiness=(root/'warmup/ReadinessCoordinator.kt').read_text(encoding='utf-8')
          balance=(root/'portfolio/SpendableBalance.kt').read_text(encoding='utf-8')
          intent=(root/'execution/OrderIntent.kt').read_text(encoding='utf-8')
          checks={
            'readiness state machine': all(x in readiness for x in ['CONNECTING_CLOUDSHARE','FETCHING_KRAKEN_ASSET_PAIRS','RECONCILING_CANDLES','DEGRADED_READY','FAILED']),
            'CloudShare optional': 'CONNECTING_CLOUDSHARE to StartupStagePolicy(false, false' in readiness,
            'controller readiness gate': 'ensureTradingReadiness' in controller and 'Trading readiness FAILED' in controller,
            'learning maturity separated': 'Learning maturity is tracked separately per symbol' in controller,
            'single spendable calculation': 'SpendableBalanceCalculator.calculate' in controller,
            'structured spendable reject': 'REJECTED_INSUFFICIENT_SPENDABLE' in controller,
            'first-class OrderIntent': 'val orderIntent = OrderIntent(' in controller,
            'broker parity modes': all(x in intent for x in ['PAPER','KRAKEN_VALIDATE','KRAKEN_LIVE']),
            'validate broker': 'class KrakenValidateBroker' in intent and 'validateOrder(request' in kraken,
            'Kraken validate=true': 'validateOnly = true' in kraken,
            'shared Kraken serializer': kraken.count('buildKrakenAddOrderForm(') >= 3,
            'Kraken cl_ord_id': '"cl_ord_id"' in kraken,
            'tests': all(Path(p).exists() for p in [
                'app/src/test/java/com/ksp/cryptobot/warmup/ReadinessCoordinatorTest.kt',
                'app/src/test/java/com/ksp/cryptobot/portfolio/SpendableBalanceTest.kt',
                'app/src/test/java/com/ksp/cryptobot/execution/OrderIntentParityTest.kt'])
          }
          for name,ok in checks.items(): print(('PASS' if ok else 'FAIL')+' | '+name)
          bad=[n for n,o in checks.items() if not o]
          if bad: raise SystemExit('CTS architecture contract failure: '+', '.join(bad))
          PYCTS

'''
        text=text.replace(anchor,steps+anchor,1)
    text=text.replace("cp migration.log diagnostics-fix.log integration-cleanup.log exchange-minimum-order-fix.log ",
                      "cp migration.log diagnostics-fix.log integration-cleanup.log exchange-minimum-order-fix.log stabilization-v407.log research-handoff-20260822.log readiness-balance-orderintent.log ")
    write(path,text)

def validate(repo):
    controller=read(repo/"app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    kraken=read(repo/"app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt")
    checks={
        "readiness":"ensureTradingReadiness" in controller,
        "balance":"SpendableBalanceCalculator.calculate" in controller,
        "structured reject":"REJECTED_INSUFFICIENT_SPENDABLE" in controller,
        "intent":"val orderIntent = OrderIntent(" in controller,
        "validate":"suspend fun validateOrder(request: OrderRequest): KrakenOrderValidationResult" in kraken,
        "shared serializer":kraken.count("buildKrakenAddOrderForm(")>=3,
        "cl_ord_id":'"cl_ord_id"' in kraken,
    }
    for n,o in checks.items(): print(("PASS" if o else "FAIL")+" | "+n)
    bad=[n for n,o in checks.items() if not o]
    if bad: fail("effective validation failed: "+", ".join(bad))

def main():
    if len(sys.argv) not in (2,3): fail("usage: apply_cts_readiness_balance_orderintent.py [--workflow-only] <repo-root>")
    workflow_only = len(sys.argv)==3 and sys.argv[1]=="--workflow-only"
    repo=Path(sys.argv[-1]).resolve()
    if workflow_only:
        wf=repo/".github/workflows/android-v4-build.yml"
        require(wf)
        patch_workflow(wf)
        print("[CTS readiness/balance/orderintent] Canonical workflow installed. Effective sources will be patched after v4 migration in CI.")
        return
    install_sources(repo)
    patch_models(repo/"app/src/main/java/com/ksp/cryptobot/core/Models.kt")
    patch_controller(repo/"app/src/main/java/com/ksp/cryptobot/core/BotController.kt")
    patch_kraken(repo/"app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt")
    wf=repo/".github/workflows/android-v4-build.yml"
    if wf.exists(): patch_workflow(wf)
    validate(repo)
    print("[CTS readiness/balance/orderintent] PASS — no strategies added.")

if __name__=="__main__": main()
