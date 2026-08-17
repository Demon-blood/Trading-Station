package com.ksp.cryptobot.cloudshare

import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

/**
 * Protocol-compatible implementation of the Crypto TradeStation desktop v1.0.50
 * CloudShare hashing/canonicalisation rules.
 *
 * IMPORTANT: Keep PROTOCOL_VERSION and SCHEMA_VERSION aligned with the Worker.
 */
object CloudShareProtocol {
    const val PROTOCOL_VERSION = "2026-07-26"
    const val SCHEMA_VERSION = 1
    const val MAX_EVENTS_PER_BATCH = 250

    private val secretKeyTokens = listOf(
        "api_key", "apikey", "api_secret", "secret", "password", "passwd",
        "token", "webhook", "authorization", "bearer", "private_key",
        "encryption_key", "github_token", "telegram_bot", "discord_webhook"
    )
    private val pathKeyTokens = listOf(
        "local_path", "file_path", "filepath", "data_dir", "app_dir",
        "source_database", "database_file", "zip_file", "device_id",
        "machine_id", "hardware_id"
    )
    private val windowsUserPath = Regex("(?i)[a-z]:\\\\users\\\\[^\\\\\\s]+\\\\")
    private val bearerPattern = Regex("(?i)\\bbearer\\s+[a-z0-9._~+/=-]{12,}")
    private val longTokenPattern = Regex("(?i)\\b(?:sk|ghp|github_pat|xox[baprs])-?[a-z0-9_-]{16,}\\b")

    private val sharedAggregateKeyFields: Map<String, List<String>> = mapOf(
        "shared_account_learning_daily" to listOf("day", "event_type", "symbol"),
        "shared_anomaly_daily" to listOf("day", "symbol", "severity", "reason_category"),
        "shared_counterfactual_daily" to listOf("day", "symbol", "scenario"),
        "shared_crash_recovery_daily" to listOf("day", "status"),
        "shared_execution_quality_daily" to listOf("day", "symbol", "side", "mode"),
        "shared_exit_daily" to listOf("day", "strategy", "symbol", "exit_method", "quality_tier"),
        "shared_guard_daily" to listOf("day", "symbol", "action", "reason_category"),
        "shared_learning_daily" to listOf("day", "event_type", "strategy", "symbol", "regime", "timeframe", "mode", "quality_tier"),
        "shared_liquidity_sizing_daily" to listOf("day", "symbol", "reason_category", "requested_size_band"),
        "shared_news_daily" to listOf("day", "symbol", "source"),
        "shared_onchain_daily" to listOf("day", "symbol", "provider", "status"),
        "shared_order_type_daily" to listOf("day", "symbol", "recommended_type", "reason_category"),
        "shared_paper_execution_daily" to listOf("day", "symbol", "side", "trade_size_band"),
        "shared_private_feed_health_daily" to listOf("day", "event_type"),
        "shared_reconciliation_daily" to listOf("day", "severity"),
        "shared_replay_daily" to listOf("day", "source", "event_type", "symbol"),
        "shared_research_daily" to listOf("day", "strategy", "regime", "variant"),
        "shared_risk_budget_daily" to listOf("day", "mode", "symbol"),
        "shared_safe_mode_daily" to listOf("day", "level", "reason_category"),
        "shared_signal_daily" to listOf("day", "strategy", "symbol", "regime", "action"),
        "shared_source_inventory" to listOf("source_table"),
        "shared_strategy_variant_daily" to listOf("day", "symbol", "variant"),
        "shared_trade_daily" to listOf("day", "strategy", "symbol", "mode", "side", "trade_size_band"),
        "shared_walk_forward_daily" to listOf("day", "strategy", "train_window", "test_window"),
        "shared_watchdog_daily" to listOf("day", "severity", "status")
    )

    fun nowIso(): String = Instant.now().toString()

    fun sanitize(value: Any?, key: String = ""): Any? {
        val normalizedKey = key.lowercase(Locale.US)
            .replace('-', '_')
            .replace(' ', '_')
        if (secretKeyTokens.any { normalizedKey.contains(it) }) return "[REDACTED]"
        if (pathKeyTokens.any { normalizedKey.contains(it) }) return "[LOCAL_ONLY]"
        return when (value) {
            null, is Boolean, is Number -> value
            is String -> sanitizeString(value)
            is ByteArray -> "[BINARY:${value.size} bytes]"
            is Map<*, *> -> value.entries.associate { (k, v) ->
                val childKey = k?.toString().orEmpty().trim()
                childKey to sanitize(v, childKey)
            }
            is Iterable<*> -> value.map { sanitize(it, key) }
            is Array<*> -> value.map { sanitize(it, key) }
            else -> value.toString()
        }
    }

    fun canonicalJson(value: Any?): String = when (value) {
        null -> "null"
        is Boolean -> if (value) "true" else "false"
        is Byte, is Short, is Int, is Long -> value.toString()
        is Float -> canonicalFloating(value.toDouble())
        is Double -> canonicalFloating(value)
        is Number -> value.toString()
        is String -> quoteJson(value)
        is Map<*, *> -> value.entries
            .associate { it.key.toString() to it.value }
            .toSortedMap()
            .entries
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
                "${quoteJson(k)}:${canonicalJson(v)}"
            }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { canonicalJson(it) }
        is Array<*> -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { canonicalJson(it) }
        else -> quoteJson(value.toString())
    }

    fun payloadHash(payload: Map<String, Any?>): String =
        sha256(canonicalJson(sanitize(payload) as Map<*, *>))

    fun eventId(sourceTable: String, eventTimestamp: String, payload: Map<String, Any?>): String {
        val sanitized = sanitize(payload) as Map<*, *>
        val payloadJson = canonicalJson(sanitized)
        return sha256("cts-cloudshare-v$SCHEMA_VERSION\n$sourceTable\n$eventTimestamp\n$payloadJson")
    }

    fun batchId(eventIds: Collection<String>, contributorId: String): String {
        val eventPart = eventIds.sorted().joinToString("\n")
        return sha256("${contributorId.trim()}\n$eventPart")
    }

    fun sharedAggregateKey(sourceTable: String, payload: Map<String, Any?>): String {
        val table = sourceTable.trim()
        val metricNames = setOf(
            "sample_count", "row_count", "wins", "losses", "breakeven",
            "positive_pnl_count", "negative_pnl_count", "zero_pnl_count",
            "first_timestamp", "last_timestamp", "created_at", "updated_at"
        )
        val fields = sharedAggregateKeyFields[table] ?: payload.keys.map { it.toString() }.sorted().filter { key ->
            key !in metricNames &&
                !key.startsWith("avg_") && !key.startsWith("min_") && !key.startsWith("max_") &&
                !key.endsWith("_sum") && !key.startsWith("_cloudshare_")
        }
        val dimensions = linkedMapOf<String, Any?>()
        fields.forEach { field -> dimensions[field] = sanitize(payload[field], field) }
        return sha256(canonicalJson(mapOf("source_table" to table, "dimensions" to dimensions)))
    }


    fun sha256(value: String): String = sha256Bytes(value.toByteArray(Charsets.UTF_8))

    fun sha256Bytes(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private fun sanitizeString(value: String): String {
        var cleaned = windowsUserPath.replace(value) { "C:\\Users\\[LOCAL_USER]\\" }
        cleaned = bearerPattern.replace(cleaned, "Bearer [REDACTED]")
        cleaned = longTokenPattern.replace(cleaned, "[REDACTED_TOKEN]")
        return cleaned
    }

    private fun canonicalFloating(value: Double): String {
        require(value.isFinite()) { "CloudShare JSON cannot encode NaN/Infinity" }
        if (value == 0.0) return "0"
        val text = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
        return if (text == "-0") "0" else text
    }

    private fun quoteJson(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
