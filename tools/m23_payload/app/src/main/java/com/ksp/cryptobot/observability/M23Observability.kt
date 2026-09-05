package com.ksp.cryptobot.observability

import android.content.Context
import android.os.SystemClock
import com.ksp.cryptobot.exchange.KrakenApiKeySecurityRuntime
import com.ksp.cryptobot.exchange.KrakenPrivateExecutionRegistry
import com.ksp.cryptobot.exchange.KrakenRealtimeMarketDataRegistry
import com.ksp.cryptobot.execution.EngineAuthorityRuntime
import com.ksp.cryptobot.execution.ExecutionCalibrationRuntime
import com.ksp.cryptobot.execution.KrakenDmsSafetyRuntime
import com.ksp.cryptobot.execution.MarketMicrostructureRuntime
import com.ksp.cryptobot.service.RuntimeHostStateStore
import com.ksp.cryptobot.settings.AppSettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

object M23Redaction {
    private val assignment = Regex(
        "(?i)(api[_ -]?key|secret|token|pin|webhook|authorization)\\s*[:=]\\s*([^,;\\s]+)"
    )

    fun sanitizeText(value: String): String = assignment
        .replace(value.replace('\n', ' ').replace('\r', ' ')) { match ->
            "${match.groupValues[1]}=<redacted>"
        }
        .take(3000)

    fun fingerprint(value: String): String {
        if (value.isBlank()) return "UNKNOWN"
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }
}

data class M23ServiceRuntimeSnapshot(
    val state: String,
    val startedAtEpochMs: Long,
    val detail: String
)

object M23ServiceRuntime {
    @Volatile private var snapshot = M23ServiceRuntimeSnapshot("STOPPED", 0L, "Service has not started in this process.")

    fun starting(detail: String) {
        snapshot = M23ServiceRuntimeSnapshot("STARTING", System.currentTimeMillis(), M23Redaction.sanitizeText(detail))
    }

    fun running(detail: String) {
        val current = snapshot
        snapshot = M23ServiceRuntimeSnapshot(
            "RUNNING",
            current.startedAtEpochMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
            M23Redaction.sanitizeText(detail)
        )
    }

    fun stopped(detail: String) {
        snapshot = M23ServiceRuntimeSnapshot("STOPPED", 0L, M23Redaction.sanitizeText(detail))
    }

    fun snapshot(): M23ServiceRuntimeSnapshot = snapshot
}

data class UnifiedHealthSnapshot(
    val generatedAtEpochMs: Long,
    val runtime: Map<String, Any?>,
    val connectivity: Map<String, Any?>,
    val recovery: Map<String, Any?>,
    val authority: Map<String, Any?>,
    val apiSecurity: Map<String, Any?>,
    val marketExecution: Map<String, Any?>,
    val economics: Map<String, Any?>,
    val portfolioRisk: Map<String, Any?>,
    val strategyLearning: Map<String, Any?>,
    val ai: Map<String, Any?>,
    val remoteOperations: Map<String, Any?>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("generatedAtEpochMs", generatedAtEpochMs)
        put("runtime", mapToJson(runtime))
        put("connectivity", mapToJson(connectivity))
        put("recovery", mapToJson(recovery))
        put("authority", mapToJson(authority))
        put("apiSecurity", mapToJson(apiSecurity))
        put("marketExecution", mapToJson(marketExecution))
        put("economics", mapToJson(economics))
        put("portfolioRisk", mapToJson(portfolioRisk))
        put("strategyLearning", mapToJson(strategyLearning))
        put("ai", mapToJson(ai))
        put("remoteOperations", mapToJson(remoteOperations))
    }

    fun toRemoteText(): String = buildString {
        appendLine("CTS M23 HEALTH")
        appendLine("mode=${runtime["mode"]} service=${runtime["foreground_service_state"]} desiredRunning=${runtime["desired_running"]}")
        appendLine("network=${connectivity["validated_network"]} publicWs=${connectivity["public_ws_state"]} privateWs=${connectivity["private_ws_state"]}")
        appendLine("recoveryFence=${recovery["recovery_fence"]} authority=${authority["state"]} dms=${authority["dms_state"]}")
        appendLine("apiSecurity=${apiSecurity["assessment"]} ageMs=${apiSecurity["assessment_age_ms"]}")
        appendLine("remotePause=${remoteOperations["pause_new_entries"]} killSwitch=${remoteOperations["kill_switch"]}")
        appendLine("m5NetEv=${economics["latest_m5_expected_net_ev"]} m20NetEv=${economics["latest_m20_adjusted_net_ev"]}")
        append("unknownStatesAreSafe=false")
    }.trim()

    private fun mapToJson(map: Map<String, Any?>): JSONObject = JSONObject().apply {
        map.forEach { (key, value) -> put(key, jsonValue(value)) }
    }

    private fun jsonValue(value: Any?): Any = when (value) {
        null -> JSONObject.NULL
        is Map<*, *> -> JSONObject().apply {
            value.forEach { (key, item) -> if (key != null) put(key.toString(), jsonValue(item)) }
        }
        is Iterable<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
        is Array<*> -> JSONArray().apply { value.forEach { put(jsonValue(it)) } }
        else -> value
    }
}

object M23HealthSnapshotBuilder {
    fun build(context: Context): UnifiedHealthSnapshot {
        val now = System.currentTimeMillis()
        val settings = AppSettingsStore(context.applicationContext).load()
        val host = RuntimeHostStateStore(context.applicationContext).snapshot()
        val service = M23ServiceRuntime.snapshot()
        val publicWs = KrakenRealtimeMarketDataRegistry.health()
        val privateWs = KrakenPrivateExecutionRegistry.health()
        val recoveryFence = KrakenPrivateExecutionRegistry.recoveryFence()
        val authority = EngineAuthorityRuntime.snapshot()
        val dms = KrakenDmsSafetyRuntime.snapshot()
        val apiSecurity = KrakenApiKeySecurityRuntime.snapshot()
        val remote = M23RemoteOperationsRuntime.snapshot()
        val micro = MarketMicrostructureRuntime.all()
        val calibration = ExecutionCalibrationRuntime.all()
        val economicsRows = M23DecisionLineageRuntime.economics()
        val latestLineage = M23DecisionLineageRuntime.recent(1).firstOrNull()

        val apiAge = apiSecurity?.let { (now - it.checkedAtEpochMs).coerceAtLeast(0L) }
        val apiAssessment = when {
            apiSecurity == null -> "UNKNOWN"
            !apiSecurity.safeForLive -> "BLOCKED"
            apiAge != null && apiAge > KrakenApiKeySecurityRuntime.MAX_ASSESSMENT_AGE_MS -> "UNKNOWN"
            else -> "SAFE"
        }

        val uptime = if (service.state == "RUNNING" && service.startedAtEpochMs > 0L) {
            (now - service.startedAtEpochMs).coerceAtLeast(0L)
        } else 0L

        val runtime = linkedMapOf<String, Any?>(
            "mode" to settings.mode.name,
            "exchange_provider" to settings.exchangeProvider.name,
            "bot_state" to if (host.desiredRunning) "RUN_REQUESTED" else "STOPPED",
            "foreground_service_state" to service.state,
            "uptime_ms" to uptime,
            "last_successful_cycle_epoch_ms" to host.lastSuccessfulCycleEpochMs,
            "last_failure" to M23Redaction.sanitizeText(host.lastError),
            "recovery_state" to M23Redaction.sanitizeText(host.recoveryState),
            "desired_running" to host.desiredRunning,
            "consecutive_failures" to host.consecutiveFailures
        )

        val connectivity = linkedMapOf<String, Any?>(
            "validated_network" to M23Redaction.sanitizeText(host.networkState),
            "public_ws_state" to publicWs.state,
            "public_ws_system_status" to publicWs.systemStatus,
            "public_ws_healthy" to publicWs.healthy,
            "public_ws_age_ms" to publicWs.lastMessageAgeMs,
            "active_symbols" to publicWs.activeSymbols,
            "private_ws_state" to privateWs.state,
            "private_ws_ready" to privateWs.privateReady,
            "private_sequence" to privateWs.lastSequence,
            "private_sequence_continuity" to if (privateWs.state == "SEQUENCE_GAP") "BROKEN" else if (privateWs.snapshotComplete) "CURRENT" else "UNKNOWN",
            "private_ws_age_ms" to privateWs.lastMessageAgeMs,
            "rest_reconciliation_age_ms" to privateWs.recentRestTruthAgeMs,
            "last_exchange_error" to M23Redaction.sanitizeText(privateWs.lastError.ifBlank { publicWs.lastError })
        )

        val recovery = linkedMapOf<String, Any?>(
            "recovery_fence" to if (recoveryFence.first) "OPEN" else "CLOSED",
            "reason" to M23Redaction.sanitizeText(recoveryFence.second),
            "last_authoritative_reconciliation_epoch_ms" to host.lastReconciliationEpochMs,
            "ambiguous_add_order_count" to privateWs.ambiguousSubmissions,
            "ambiguous_mutation_count" to privateWs.ambiguousSubmissions,
            "database_health" to "UNKNOWN",
            "clock_sanity" to if (SystemClock.elapsedRealtime() >= 0L && now > 0L) "BASIC_OK" else "UNKNOWN"
        )

        val authorityMap = linkedMapOf<String, Any?>(
            "authorized" to authority.authorized,
            "state" to authority.state,
            "engine_id_fingerprint" to M23Redaction.fingerprint(authority.engineId),
            "holder_id_fingerprint" to M23Redaction.fingerprint(authority.holderEngineId),
            "fencing_token" to authority.fencingToken,
            "lease_schema_version" to authority.leaseSchemaVersion,
            "lease_remaining_ms" to authority.leaseRemainingMs,
            "reason" to M23Redaction.sanitizeText(authority.reason),
            "dms_state" to dms.state,
            "dms_safe_for_new_entries" to dms.safeForNewEntries,
            "dms_timeout_seconds" to dms.timeoutSeconds
        )

        val apiMap = linkedMapOf<String, Any?>(
            "assessment" to apiAssessment,
            "key_fingerprint" to (apiSecurity?.keyFingerprint ?: "UNKNOWN"),
            "assessment_age_ms" to (apiAge ?: -1L),
            "missing_required_permissions" to (apiSecurity?.missingRequiredPermissions?.sorted() ?: listOf("UNKNOWN")),
            "dangerous_permission_categories" to (apiSecurity?.dangerousPermissions?.sorted() ?: listOf("UNKNOWN")),
            "key_expired" to (apiSecurity?.expired ?: "UNKNOWN"),
            "ip_allowlist_configured" to (apiSecurity?.ipRestricted ?: "UNKNOWN")
        )

        val marketMap = linkedMapOf<String, Any?>(
            "microstructure" to micro.sortedBy { it.symbol }.map {
                linkedMapOf<String, Any?>(
                    "symbol" to it.symbol,
                    "valid" to it.valid,
                    "spread_bps" to it.spreadBps,
                    "microprice" to it.microPrice.stripTrailingZeros().toPlainString(),
                    "imbalance" to it.bookImbalance,
                    "market_impact_bps" to it.marketImpactBps,
                    "maker_fill_probability_heuristic" to it.makerFillProbability,
                    "adverse_selection_estimate" to it.adverseSelectionRisk
                )
            },
            "execution_calibration" to calibration.toSortedMap().map { (symbol, value) ->
                linkedMapOf<String, Any?>(
                    "symbol" to symbol,
                    "samples" to value.samples,
                    "mean_fill_seconds" to value.meanFillSeconds,
                    "mean_slippage_bps" to value.meanSlippageBps,
                    "amendments_per_fill" to value.amendmentsPerCompletedFill,
                    "total_cancels" to value.totalCancels
                )
            },
            "open_order_truth" to if (privateWs.knownForEntries) "KNOWN" else "UNKNOWN",
            "open_orders" to privateWs.openOrders,
            "partial_orders" to privateWs.partialOrders
        )

        val latestEconomics = economicsRows.maxByOrNull { it.updatedAtEpochMs }
        val economicsMap = linkedMapOf<String, Any?>(
            "latest_symbol" to (latestEconomics?.symbol ?: "UNKNOWN"),
            "latest_m5_expected_net_ev" to (latestEconomics?.m5ExpectedNetEvRate ?: "UNKNOWN"),
            "latest_m20_adjusted_net_ev" to (latestEconomics?.m20AdjustedExpectedNetEvRate ?: "UNKNOWN"),
            "fee_source" to "UNKNOWN",
            "fee_estimate" to "UNKNOWN",
            "spread" to (micro.maxByOrNull { it.symbol }?.spreadBps ?: "UNKNOWN"),
            "slippage" to (calibration.values.maxByOrNull { it.samples }?.meanSlippageBps ?: "UNKNOWN"),
            "ai_cost" to "UNKNOWN",
            "execution_reliability_penalty" to "UNKNOWN",
            "opportunity_cost" to "UNKNOWN",
            "break_even_return" to "UNKNOWN"
        )

        val portfolio = linkedMapOf<String, Any?>(
            "total_equity" to "UNKNOWN",
            "available_quote" to "UNKNOWN",
            "reserved_quote" to "UNKNOWN",
            "current_exposure" to "UNKNOWN",
            "portfolio_heat" to "UNKNOWN",
            "correlation_exposure" to "UNKNOWN",
            "open_positions" to "UNKNOWN",
            "protected_vs_unprotected" to "UNKNOWN",
            "daily_pnl" to "UNKNOWN",
            "weekly_pnl" to "UNKNOWN",
            "drawdown" to "UNKNOWN",
            "daily_loss_budget" to settings.maxDailyLossEur.stripTrailingZeros().toPlainString(),
            "kill_switch_state" to if (remote.killSwitch) "ACTIVE" else "INACTIVE"
        )

        val strategyLearning = linkedMapOf<String, Any?>(
            "strategy" to (latestLineage?.strategy?.ifBlank { settings.strategyMode.name } ?: settings.strategyMode.name),
            "market_regime" to "UNKNOWN",
            "action" to (latestLineage?.action?.ifBlank { "UNKNOWN" } ?: "UNKNOWN"),
            "confidence_percent" to (latestLineage?.confidencePercent ?: "UNKNOWN"),
            "champion_challenger_status" to "UNKNOWN",
            "m19_drift_state" to "UNKNOWN",
            "calibration_state" to if (calibration.isEmpty()) "UNKNOWN" else "OBSERVED",
            "rollback_state" to "UNKNOWN",
            "relevant_sample_counts" to calibration.values.sumOf { it.samples }
        )

        val ai = linkedMapOf<String, Any?>(
            "model_path" to "UNKNOWN",
            "decision" to (latestLineage?.action?.ifBlank { "UNKNOWN" } ?: "UNKNOWN"),
            "cost" to "UNKNOWN",
            "attributable_value" to "UNKNOWN",
            "escalation_count" to "UNKNOWN",
            "failures" to "UNKNOWN",
            "abstentions" to "UNKNOWN"
        )

        val remoteMap = linkedMapOf<String, Any?>(
            "initialized" to remote.initialized,
            "pause_new_entries" to remote.pauseNewEntries,
            "kill_switch" to remote.killSwitch,
            "pending_stop" to remote.pendingStop,
            "pending_reconciliation" to remote.pendingReconciliation,
            "pending_market_refresh" to remote.pendingMarketRefresh,
            "audit_events" to remote.auditEvents
        )

        return UnifiedHealthSnapshot(
            generatedAtEpochMs = now,
            runtime = runtime,
            connectivity = connectivity,
            recovery = recovery,
            authority = authorityMap,
            apiSecurity = apiMap,
            marketExecution = marketMap,
            economics = economicsMap,
            portfolioRisk = portfolio,
            strategyLearning = strategyLearning,
            ai = ai,
            remoteOperations = remoteMap
        )
    }
}
