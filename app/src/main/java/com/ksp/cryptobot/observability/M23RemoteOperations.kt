package com.ksp.cryptobot.observability

import android.content.Context
import java.security.MessageDigest

/**
 * M23 remote operations are intentionally one-way toward safety.
 * Remote callers may inspect state, pause entries, activate the kill switch,
 * stop the engine, request authoritative reconciliation, or refresh market data.
 * They never receive entry/exit authority and cannot clear a safety stop.
 */
data class M23RemoteAuthorization(
    val accepted: Boolean,
    val sourceId: String,
    val reason: String
)

data class M23RemoteAuditEvent(
    val timestampEpochMs: Long,
    val command: String,
    val sourceId: String,
    val accepted: Boolean,
    val reason: String,
    val result: String
)

data class M23RemoteOperationsSnapshot(
    val initialized: Boolean,
    val pauseNewEntries: Boolean,
    val killSwitch: Boolean,
    val pendingStop: Boolean,
    val pendingReconciliation: Boolean,
    val pendingMarketRefresh: Boolean,
    val auditEvents: Int
)

object M23RemoteOperationsPolicy {
    val SAFE_COMMANDS: Set<String> = setOf(
        "status",
        "health",
        "settings",
        "portfolio",
        "positions",
        "orders",
        "diagnostics",
        "pause",
        "pause_entries",
        "kill",
        "kill_switch",
        "stop",
        "reconcile",
        "refresh_market"
    )

    val FORBIDDEN_AUTHORITY_COMMANDS: Set<String> = setOf(
        "execute",
        "start",
        "resume",
        "mode",
        "set",
        "buy",
        "sell",
        "force_buy",
        "force_sell",
        "ignore_risk",
        "ignore_security",
        "ignore_authority"
    )

    fun constantTimeEquals(expected: String, supplied: String): Boolean =
        MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            supplied.toByteArray(Charsets.UTF_8)
        )

    fun entryAllowed(initialized: Boolean, pauseNewEntries: Boolean, killSwitch: Boolean): Pair<Boolean, String> {
        if (!initialized) return false to "M23 remote-operations state is not initialized."
        if (killSwitch) return false to "M23 kill switch is ACTIVE."
        if (pauseNewEntries) return false to "M23 new-entry pause is ACTIVE."
        return true to "M23 remote-operations entry gate is open."
    }

    fun commandAllowed(command: String): Boolean = command.lowercase() in SAFE_COMMANDS
}

object M23RemoteOperationsRuntime {
    private const val PREFS = "cts_m23_remote_operations"
    private const val KEY_PAUSE = "pause_new_entries"
    private const val KEY_KILL = "kill_switch"
    private const val KEY_REPLAY = "replay_fingerprints"
    private const val KEY_AUDIT = "audit_events"
    private const val MAX_REPLAY = 256
    private const val MAX_AUDIT = 200

    private val lock = Any()

    @Volatile private var initialized = false
    @Volatile private var pauseNewEntries = false
    @Volatile private var killSwitch = false
    @Volatile private var pendingStop = false
    @Volatile private var pendingReconciliation = false
    @Volatile private var pendingMarketRefresh = false

    private var appContext: Context? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            val ctx = context.applicationContext
            appContext = ctx
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            pauseNewEntries = prefs.getBoolean(KEY_PAUSE, false)
            killSwitch = prefs.getBoolean(KEY_KILL, false)
            pendingStop = false
            pendingReconciliation = false
            pendingMarketRefresh = false
            initialized = true
        }
    }

    fun snapshot(): M23RemoteOperationsSnapshot = synchronized(lock) {
        M23RemoteOperationsSnapshot(
            initialized = initialized,
            pauseNewEntries = pauseNewEntries,
            killSwitch = killSwitch,
            pendingStop = pendingStop,
            pendingReconciliation = pendingReconciliation,
            pendingMarketRefresh = pendingMarketRefresh,
            auditEvents = readAuditLocked().size
        )
    }

    fun canSubmitNewEntry(): Pair<Boolean, String> =
        M23RemoteOperationsPolicy.entryAllowed(initialized, pauseNewEntries, killSwitch)

    fun authenticateAndReserveMessage(
        source: String,
        messageId: String,
        sourceIdentity: String,
        suppliedPin: String,
        configuredPin: String,
        requirePin: Boolean
    ): M23RemoteAuthorization = synchronized(lock) {
        val sourceId = fingerprint("${source.lowercase()}|$sourceIdentity")
        if (!initialized) {
            return@synchronized M23RemoteAuthorization(false, sourceId, "Remote operations are not initialized.")
        }
        if (requirePin) {
            if (configuredPin.isBlank()) {
                auditLocked("AUTH", sourceId, false, "PIN not configured", "REJECTED")
                return@synchronized M23RemoteAuthorization(false, sourceId, "Remote command center is locked: no PIN configured.")
            }
            if (!M23RemoteOperationsPolicy.constantTimeEquals(configuredPin, suppliedPin)) {
                auditLocked("AUTH", sourceId, false, "Invalid PIN", "REJECTED")
                return@synchronized M23RemoteAuthorization(false, sourceId, "Rejected: bad or missing PIN.")
            }
        }

        val replayFingerprint = fingerprint("${source.lowercase()}|$messageId|$sourceIdentity")
        val existing = readReplayLocked()
        if (replayFingerprint in existing) {
            auditLocked("AUTH", sourceId, false, "Replay detected", "REJECTED")
            return@synchronized M23RemoteAuthorization(false, sourceId, "Rejected: replayed remote command.")
        }
        val next = (listOf(replayFingerprint) + existing).distinct().take(MAX_REPLAY)
        if (!writeReplayLocked(next)) {
            // Replay protection is an authorization primitive. Failure to durably
            // reserve the message is fail-closed rather than best-effort.
            auditLocked("AUTH", sourceId, false, "Replay reservation persistence failed", "REJECTED")
            return@synchronized M23RemoteAuthorization(false, sourceId, "Rejected: replay protection persistence failed.")
        }

        auditLocked("AUTH", sourceId, true, "Authenticated and replay-reserved", "ACCEPTED")
        M23RemoteAuthorization(true, sourceId, "Authenticated.")
    }

    fun auditCommand(
        sourceId: String,
        command: String,
        accepted: Boolean,
        reason: String,
        result: String
    ) {
        synchronized(lock) {
            auditLocked(command, sourceId, accepted, reason, result)
        }
    }

    fun pauseEntries(sourceId: String): String = synchronized(lock) {
        val persisted = persistSafetyLocked(pause = true, kill = killSwitch)
        pauseNewEntries = true
        val result = if (persisted) "PAUSED" else "PAUSED_IN_MEMORY_PERSISTENCE_FAILED"
        auditLocked("PAUSE_NEW_ENTRIES", sourceId, true, "Authenticated safety command", result)
        "New entries paused. Existing positions and protective/exit SELL handling remain available."
    }

    fun activateKillSwitch(sourceId: String): String = synchronized(lock) {
        val persisted = persistSafetyLocked(pause = true, kill = true)
        pauseNewEntries = true
        killSwitch = true
        val result = if (persisted) "KILL_SWITCH_ACTIVE" else "KILL_SWITCH_ACTIVE_IN_MEMORY_PERSISTENCE_FAILED"
        auditLocked("ACTIVATE_KILL_SWITCH", sourceId, true, "Authenticated safety command", result)
        "Kill switch activated. New BUY entries are blocked; risk-reducing/protective SELL handling is not disabled."
    }

    fun requestStop(sourceId: String): String = synchronized(lock) {
        // STOP also closes the entry gate immediately, before the service consumes
        // the stop request on its next cycle.
        persistSafetyLocked(pause = true, kill = killSwitch)
        pauseNewEntries = true
        pendingStop = true
        auditLocked("STOP_ENGINE", sourceId, true, "Authenticated safety command", "QUEUED")
        "Engine stop requested. New entries are already paused while the foreground service shuts down safely."
    }

    fun requestReconciliation(sourceId: String): String = synchronized(lock) {
        pendingReconciliation = true
        auditLocked("FORCE_AUTHORITATIVE_RECONCILIATION", sourceId, true, "Authenticated recovery command", "QUEUED")
        "Authoritative reconciliation requested. It cannot reopen BUY authority unless existing recovery/security/authority gates pass."
    }

    fun requestMarketRefresh(sourceId: String): String = synchronized(lock) {
        pendingMarketRefresh = true
        auditLocked("REFRESH_MARKET_CONNECTION", sourceId, true, "Authenticated recovery command", "QUEUED")
        "Market/private connection refresh requested. BUY truth will be marked unknown until authoritative reconciliation succeeds."
    }

    fun consumeStopRequest(): Boolean = synchronized(lock) {
        val value = pendingStop
        pendingStop = false
        value
    }

    fun consumeReconciliationRequest(): Boolean = synchronized(lock) {
        val value = pendingReconciliation
        pendingReconciliation = false
        value
    }

    fun consumeMarketRefreshRequest(): Boolean = synchronized(lock) {
        val value = pendingMarketRefresh
        pendingMarketRefresh = false
        value
    }

    fun recentAudit(limit: Int = 100): List<M23RemoteAuditEvent> = synchronized(lock) {
        readAuditLocked().take(limit.coerceIn(1, MAX_AUDIT))
    }

    private fun persistSafetyLocked(pause: Boolean, kill: Boolean): Boolean {
        val ctx = appContext ?: return false
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PAUSE, pause)
            .putBoolean(KEY_KILL, kill)
            .commit()
    }

    private fun writeReplayLocked(values: List<String>): Boolean {
        val ctx = appContext ?: return false
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_REPLAY, values.joinToString("\n"))
            .commit()
    }

    private fun readReplayLocked(): List<String> {
        val ctx = appContext ?: return emptyList()
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_REPLAY, "")
            .orEmpty()
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(MAX_REPLAY)
            .toList()
    }

    private fun auditLocked(
        command: String,
        sourceId: String,
        accepted: Boolean,
        reason: String,
        result: String
    ) {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        val line = listOf(
            now.toString(),
            clean(command),
            clean(sourceId),
            accepted.toString(),
            clean(reason),
            clean(result)
        ).joinToString("|")
        val existing = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AUDIT, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .take(MAX_AUDIT - 1)
            .toList()
        // Audit persistence is synchronous. A failed audit write never grants any
        // additional trading authority because all safety mutations are monotonic.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AUDIT, (listOf(line) + existing).joinToString("\n"))
            .commit()
    }

    private fun readAuditLocked(): List<M23RemoteAuditEvent> {
        val ctx = appContext ?: return emptyList()
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AUDIT, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val p = line.split('|', limit = 6)
                if (p.size != 6) null else M23RemoteAuditEvent(
                    timestampEpochMs = p[0].toLongOrNull() ?: 0L,
                    command = p[1],
                    sourceId = p[2],
                    accepted = p[3].toBooleanStrictOrNull() ?: false,
                    reason = p[4],
                    result = p[5]
                )
            }
            .take(MAX_AUDIT)
            .toList()
    }

    private fun fingerprint(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)

    private fun clean(value: String): String = value
        .replace('|', '/')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .take(300)
}
