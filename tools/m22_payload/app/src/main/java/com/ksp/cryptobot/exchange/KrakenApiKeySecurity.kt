package com.ksp.cryptobot.exchange

import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference

data class KrakenApiKeySecurityInfo(
    val keyFingerprint: String,
    val keyName: String,
    val permissions: Set<String>,
    val validUntilEpochSeconds: Long,
    val ipAllowlist: List<String>
)

data class KrakenApiKeySecurityAssessment(
    val safeForLive: Boolean,
    val keyFingerprint: String,
    val keyName: String,
    val permissions: Set<String>,
    val dangerousPermissions: Set<String>,
    val missingRequiredPermissions: Set<String>,
    val extraPermissions: Set<String>,
    val expired: Boolean,
    val ipRestricted: Boolean,
    val checkedAtEpochMs: Long,
    val reason: String
)

object KrakenApiKeySecurityPolicy {
    val DANGEROUS_PERMISSIONS: Set<String> = setOf(
        "withdraw-funds",
        "add-withdraw-address",
        "update-withdraw-address"
    )

    // Exact permissions used by the current LIVE Kraken spot runtime.
    val REQUIRED_LIVE_PERMISSIONS: Set<String> = setOf(
        "query-funds",
        "query-open-trades",
        "query-closed-trades",
        "modify-trades",
        "close-trades",
        "create-ws-token"
    )

    fun assess(
        info: KrakenApiKeySecurityInfo,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        checkedAtEpochMs: Long = System.currentTimeMillis()
    ): KrakenApiKeySecurityAssessment {
        val normalized = info.permissions
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        val dangerous = normalized.intersect(DANGEROUS_PERMISSIONS)
        val missing = REQUIRED_LIVE_PERMISSIONS.subtract(normalized)
        val expired = info.validUntilEpochSeconds > 0L &&
            nowEpochSeconds >= info.validUntilEpochSeconds
        val extra = normalized
            .subtract(REQUIRED_LIVE_PERMISSIONS)
            .subtract(DANGEROUS_PERMISSIONS)

        val safe = dangerous.isEmpty() && missing.isEmpty() && !expired
        val reason = buildString {
            append(if (safe) "SAFE" else "BLOCKED")
            append(" Kraken API key")
            if (info.keyName.isNotBlank()) append(" '${info.keyName}'")
            append(" fingerprint=${info.keyFingerprint}. ")
            if (dangerous.isNotEmpty()) {
                append("Dangerous permission(s) present: ${dangerous.sorted().joinToString(",")}. ")
            }
            if (missing.isNotEmpty()) {
                append("Missing LIVE permission(s): ${missing.sorted().joinToString(",")}. ")
            }
            if (expired) append("Key is expired. ")
            if (extra.isNotEmpty()) {
                append("Non-required extra permission(s): ${extra.sorted().joinToString(",")}. ")
            }
            if (info.ipAllowlist.isEmpty()) {
                append("No IP allowlist configured; this is a warning, not a mobile-runtime blocker. ")
            } else {
                append("IP allowlist configured (${info.ipAllowlist.size} entr${if (info.ipAllowlist.size == 1) "y" else "ies"}). ")
            }
            if (safe) {
                append("Withdrawal and withdrawal-address permissions are absent and all current LIVE trading permissions are present.")
            }
        }

        return KrakenApiKeySecurityAssessment(
            safeForLive = safe,
            keyFingerprint = info.keyFingerprint,
            keyName = info.keyName,
            permissions = normalized,
            dangerousPermissions = dangerous,
            missingRequiredPermissions = missing,
            extraPermissions = extra,
            expired = expired,
            ipRestricted = info.ipAllowlist.isNotEmpty(),
            checkedAtEpochMs = checkedAtEpochMs,
            reason = reason.trim()
        )
    }
}

object KrakenApiKeySecurityRuntime {
    const val MAX_ASSESSMENT_AGE_MS = 15L * 60L * 1000L

    private data class State(
        val assessment: KrakenApiKeySecurityAssessment? = null,
        val unknownReason: String = "Kraken API-key permissions have not been inspected."
    )

    private val state = AtomicReference(State())

    fun fingerprint(apiKey: String): String {
        if (apiKey.isBlank()) return "missing"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(apiKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return digest.take(16)
    }

    fun publish(assessment: KrakenApiKeySecurityAssessment) {
        state.set(State(assessment = assessment, unknownReason = ""))
    }

    fun markUnknown(reason: String) {
        state.set(State(assessment = null, unknownReason = reason.take(300)))
    }

    fun snapshot(): KrakenApiKeySecurityAssessment? = state.get().assessment

    fun gateForNewBuy(
        apiKey: String,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Pair<Boolean, String> {
        val current = state.get()
        val assessment = current.assessment
            ?: return false to "Kraken API-key security state is unknown: ${current.unknownReason}"

        val currentFingerprint = fingerprint(apiKey)
        if (currentFingerprint != assessment.keyFingerprint) {
            return false to "Kraken API key changed since permission inspection; re-inspection is required."
        }

        val age = (nowEpochMs - assessment.checkedAtEpochMs).coerceAtLeast(0L)
        if (age > MAX_ASSESSMENT_AGE_MS) {
            return false to "Kraken API-key permission assessment is stale (${age}ms)."
        }

        if (!assessment.safeForLive) {
            return false to assessment.reason
        }

        return true to assessment.reason
    }

    fun clearForTests() = markUnknown("test reset")
}
