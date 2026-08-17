package com.ksp.cryptobot.cloudshare

import android.content.Context
import com.ksp.cryptobot.security.SecureSettingsStore
import java.util.UUID

class CloudShareSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("cloudshare_settings", Context.MODE_PRIVATE)
    private val secure = SecureSettingsStore(context)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    var apiUrl: String
        get() = prefs.getString(KEY_API_URL, "").orEmpty().trim().trimEnd('/')
        set(value) { prefs.edit().putString(KEY_API_URL, value.trim().trimEnd('/')).apply() }

    var syncIntervalMinutes: Int
        get() = prefs.getInt(KEY_SYNC_MINUTES, 5).coerceIn(1, 1440)
        set(value) { prefs.edit().putInt(KEY_SYNC_MINUTES, value.coerceIn(1, 1440)).apply() }


    var collectiveLearningEnabled: Boolean
        get() = prefs.getBoolean(KEY_COLLECTIVE_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_COLLECTIVE_ENABLED, value).apply() }

    var collectiveMinSamples: Int
        get() = prefs.getInt(KEY_COLLECTIVE_MIN_SAMPLES, 25).coerceIn(1, 100000)
        set(value) { prefs.edit().putInt(KEY_COLLECTIVE_MIN_SAMPLES, value.coerceIn(1, 100000)).apply() }

    var collectiveMaxAdjustment: Int
        get() = prefs.getInt(KEY_COLLECTIVE_MAX_ADJUSTMENT, 6).coerceIn(0, 20)
        set(value) { prefs.edit().putInt(KEY_COLLECTIVE_MAX_ADJUSTMENT, value.coerceIn(0, 20)).apply() }

    var collectiveWeight: Double
        get() = prefs.getString(KEY_COLLECTIVE_WEIGHT, "1.0")?.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: 1.0
        set(value) { prefs.edit().putString(KEY_COLLECTIVE_WEIGHT, value.coerceIn(0.0, 2.0).toString()).apply() }

    var emitSharedAggregates: Boolean
        get() = prefs.getBoolean(KEY_EMIT_AGGREGATES, true)
        set(value) { prefs.edit().putBoolean(KEY_EMIT_AGGREGATES, value).apply() }

    var backfillEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKFILL_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_BACKFILL_ENABLED, value).apply() }

    var backfillRowsPerSync: Int
        get() = prefs.getInt(KEY_BACKFILL_ROWS_PER_SYNC, 500).coerceIn(1, 5000)
        set(value) { prefs.edit().putInt(KEY_BACKFILL_ROWS_PER_SYNC, value.coerceIn(1, 5000)).apply() }

    fun contributorId(): String {
        val existing = prefs.getString(KEY_CONTRIBUTOR_ID, "").orEmpty()
        if (existing.matches(Regex("^[a-f0-9-]{20,80}$", RegexOption.IGNORE_CASE))) return existing
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_CONTRIBUTOR_ID, generated).apply()
        return generated
    }

    fun credentials(): CloudShareCredentials? {
        val clientId = secure.readEncryptedString(SECRET_CLIENT_ID).orEmpty()
        val token = secure.readEncryptedString(SECRET_CLIENT_TOKEN).orEmpty()
        if (clientId.isBlank() || token.isBlank()) return null
        return CloudShareCredentials(clientId, token, contributorId())
    }

    fun saveCredentials(clientId: String, clientToken: String) {
        secure.saveEncryptedString(SECRET_CLIENT_ID, clientId)
        secure.saveEncryptedString(SECRET_CLIENT_TOKEN, clientToken)
    }

    fun clearCredentials() {
        secure.clearSecret(SECRET_CLIENT_ID)
        secure.clearSecret(SECRET_CLIENT_TOKEN)
    }

    fun adminToken(): String = secure.readEncryptedString(SECRET_ADMIN_TOKEN).orEmpty()
    fun saveAdminToken(value: String) = secure.saveEncryptedString(SECRET_ADMIN_TOKEN, value.trim())
    fun clearAdminToken() = secure.clearSecret(SECRET_ADMIN_TOKEN)

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_API_URL = "api_url"
        private const val KEY_SYNC_MINUTES = "sync_minutes"
        private const val KEY_CONTRIBUTOR_ID = "contributor_id"
        private const val KEY_COLLECTIVE_ENABLED = "collective_enabled"
        private const val KEY_COLLECTIVE_MIN_SAMPLES = "collective_min_samples"
        private const val KEY_COLLECTIVE_MAX_ADJUSTMENT = "collective_max_adjustment"
        private const val KEY_COLLECTIVE_WEIGHT = "collective_weight"
        private const val KEY_EMIT_AGGREGATES = "emit_shared_aggregates"
        private const val KEY_BACKFILL_ENABLED = "backfill_enabled"
        private const val KEY_BACKFILL_ROWS_PER_SYNC = "backfill_rows_per_sync"
        private const val SECRET_CLIENT_ID = "cloudshare_client_id"
        private const val SECRET_CLIENT_TOKEN = "cloudshare_client_token"
        private const val SECRET_ADMIN_TOKEN = "cloudshare_admin_token"
    }
}
