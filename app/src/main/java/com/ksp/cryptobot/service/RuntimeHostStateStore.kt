package com.ksp.cryptobot.service

import android.content.Context

/**
 * Durable host intent/state.
 *
 * This is deliberately separate from trading settings. Exchange state remains
 * authoritative at Kraken; this store only records whether the user asked the
 * Android host to keep running and enough health metadata to recover safely.
 */
class RuntimeHostStateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Snapshot(
        val desiredRunning: Boolean,
        val resumeAfterBoot: Boolean,
        val lastStartReason: String,
        val lastStartEpochMs: Long,
        val lastStopEpochMs: Long,
        val lastHeartbeatEpochMs: Long,
        val lastSuccessfulCycleEpochMs: Long,
        val lastReconciliationEpochMs: Long,
        val consecutiveFailures: Int,
        val networkState: String,
        val recoveryState: String,
        val lastError: String
    )

    fun snapshot(): Snapshot = Snapshot(
        desiredRunning = prefs.getBoolean(KEY_DESIRED_RUNNING, false),
        resumeAfterBoot = prefs.getBoolean(KEY_RESUME_AFTER_BOOT, false),
        lastStartReason = prefs.getString(KEY_LAST_START_REASON, "NONE").orEmpty(),
        lastStartEpochMs = prefs.getLong(KEY_LAST_START_MS, 0L),
        lastStopEpochMs = prefs.getLong(KEY_LAST_STOP_MS, 0L),
        lastHeartbeatEpochMs = prefs.getLong(KEY_LAST_HEARTBEAT_MS, 0L),
        lastSuccessfulCycleEpochMs = prefs.getLong(KEY_LAST_SUCCESS_MS, 0L),
        lastReconciliationEpochMs = prefs.getLong(KEY_LAST_RECONCILE_MS, 0L),
        consecutiveFailures = prefs.getInt(KEY_FAILURES, 0),
        networkState = prefs.getString(KEY_NETWORK, "UNKNOWN").orEmpty(),
        recoveryState = prefs.getString(KEY_RECOVERY, "IDLE").orEmpty(),
        lastError = prefs.getString(KEY_LAST_ERROR, "").orEmpty()
    )

    fun requestContinuousRun(reason: String, resumeAfterBoot: Boolean) {
        prefs.edit()
            .putBoolean(KEY_DESIRED_RUNNING, true)
            .putBoolean(KEY_RESUME_AFTER_BOOT, resumeAfterBoot)
            .putString(KEY_LAST_START_REASON, reason)
            .putLong(KEY_LAST_START_MS, System.currentTimeMillis())
            .putString(KEY_RECOVERY, "START_REQUESTED")
            .apply()
    }

    fun requestStop(reason: String) {
        prefs.edit()
            .putBoolean(KEY_DESIRED_RUNNING, false)
            .putBoolean(KEY_RESUME_AFTER_BOOT, false)
            .putLong(KEY_LAST_STOP_MS, System.currentTimeMillis())
            .putString(KEY_RECOVERY, "STOPPED")
            .putString(KEY_LAST_ERROR, reason)
            .apply()
    }

    fun heartbeat() {
        prefs.edit().putLong(KEY_LAST_HEARTBEAT_MS, System.currentTimeMillis()).apply()
    }

    fun successfulCycle() {
        prefs.edit()
            .putLong(KEY_LAST_SUCCESS_MS, System.currentTimeMillis())
            .putInt(KEY_FAILURES, 0)
            .putString(KEY_LAST_ERROR, "")
            .putString(KEY_RECOVERY, "RUNNING")
            .apply()
    }

    fun reconciliationSucceeded(detail: String) {
        prefs.edit()
            .putLong(KEY_LAST_RECONCILE_MS, System.currentTimeMillis())
            .putInt(KEY_FAILURES, 0)
            .putString(KEY_LAST_ERROR, "")
            .putString(KEY_RECOVERY, "RECONCILED:$detail")
            .apply()
    }

    fun failure(error: String): Int {
        val next = (prefs.getInt(KEY_FAILURES, 0) + 1).coerceAtMost(1000)
        prefs.edit()
            .putInt(KEY_FAILURES, next)
            .putString(KEY_LAST_ERROR, error.take(500))
            .putString(KEY_RECOVERY, "DEGRADED")
            .apply()
        return next
    }

    fun network(state: String) {
        prefs.edit().putString(KEY_NETWORK, state.take(120)).apply()
    }

    fun recovery(state: String) {
        prefs.edit().putString(KEY_RECOVERY, state.take(200)).apply()
    }

    companion object {
        private const val PREFS = "cts_runtime_host"
        private const val KEY_DESIRED_RUNNING = "desired_running"
        private const val KEY_RESUME_AFTER_BOOT = "resume_after_boot"
        private const val KEY_LAST_START_REASON = "last_start_reason"
        private const val KEY_LAST_START_MS = "last_start_ms"
        private const val KEY_LAST_STOP_MS = "last_stop_ms"
        private const val KEY_LAST_HEARTBEAT_MS = "last_heartbeat_ms"
        private const val KEY_LAST_SUCCESS_MS = "last_success_ms"
        private const val KEY_LAST_RECONCILE_MS = "last_reconcile_ms"
        private const val KEY_FAILURES = "consecutive_failures"
        private const val KEY_NETWORK = "network_state"
        private const val KEY_RECOVERY = "recovery_state"
        private const val KEY_LAST_ERROR = "last_error"
    }
}
