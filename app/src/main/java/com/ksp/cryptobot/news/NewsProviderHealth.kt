package com.ksp.cryptobot.news

/**
 * Process-local health/cooldown truth for external news providers.
 *
 * The cooldown durations are Crypto TradeStation retry policy, not provider
 * quota guarantees. They prevent a broken/auth-limited provider from being hit
 * on every symbol during every scan while keeping the rest of the news ensemble
 * fail-neutral.
 */
data class NewsProviderHealth(
    val provider: String,
    val status: String = "READY",
    val lastAttemptEpochMs: Long = 0L,
    val lastSuccessEpochMs: Long = 0L,
    val lastFailureEpochMs: Long = 0L,
    val consecutiveFailures: Int = 0,
    val cooldownUntilEpochMs: Long = 0L,
    val lastArticleCount: Int = 0,
    val lastError: String = ""
) {
    fun coolingDown(nowEpochMs: Long = System.currentTimeMillis()): Boolean = cooldownUntilEpochMs > nowEpochMs
}

object NewsProviderHealthRegistry {
    private const val FIVE_MINUTES = 5L * 60L * 1000L
    private const val TEN_MINUTES = 10L * 60L * 1000L
    private const val THIRTY_MINUTES = 30L * 60L * 1000L
    private const val SIX_HOURS = 6L * 60L * 60L * 1000L
    private const val MAX_COOLDOWN = 12L * 60L * 60L * 1000L

    private val lock = Any()
    private val states = linkedMapOf<String, NewsProviderHealth>()

    fun shouldAttempt(provider: String, nowEpochMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val current = states[provider] ?: return@synchronized true
        current.cooldownUntilEpochMs <= nowEpochMs
    }

    fun recordAttempt(provider: String, nowEpochMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val current = states[provider] ?: NewsProviderHealth(provider)
        states[provider] = current.copy(
            status = if (current.cooldownUntilEpochMs > nowEpochMs) "COOLDOWN" else "CHECKING",
            lastAttemptEpochMs = nowEpochMs
        )
    }

    fun recordSuccess(provider: String, articleCount: Int, nowEpochMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val current = states[provider] ?: NewsProviderHealth(provider)
        states[provider] = current.copy(
            status = if (articleCount > 0) "HEALTHY" else "EMPTY",
            lastAttemptEpochMs = nowEpochMs,
            lastSuccessEpochMs = nowEpochMs,
            consecutiveFailures = 0,
            cooldownUntilEpochMs = 0L,
            lastArticleCount = articleCount.coerceAtLeast(0),
            lastError = ""
        )
    }

    fun recordFailure(provider: String, error: Throwable, nowEpochMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val current = states[provider] ?: NewsProviderHealth(provider)
        val failures = (current.consecutiveFailures + 1).coerceAtMost(20)
        val base = baseCooldown(error)
        val multiplier = 1L shl (failures - 1).coerceIn(0, 3)
        val cooldown = (base * multiplier).coerceAtMost(MAX_COOLDOWN)
        states[provider] = current.copy(
            status = "COOLDOWN",
            lastAttemptEpochMs = nowEpochMs,
            lastFailureEpochMs = nowEpochMs,
            consecutiveFailures = failures,
            cooldownUntilEpochMs = nowEpochMs + cooldown,
            lastArticleCount = 0,
            lastError = (error.message ?: error.javaClass.simpleName).take(280)
        )
    }

    fun healthFor(provider: String, nowEpochMs: Long = System.currentTimeMillis()): NewsProviderHealth? = synchronized(lock) {
        normalize(states[provider], nowEpochMs)
    }

    fun snapshot(nowEpochMs: Long = System.currentTimeMillis()): List<NewsProviderHealth> = synchronized(lock) {
        states.values.mapNotNull { normalize(it, nowEpochMs) }.sortedBy { it.provider }
    }

    internal fun resetForTests() = synchronized(lock) { states.clear() }

    private fun normalize(value: NewsProviderHealth?, nowEpochMs: Long): NewsProviderHealth? {
        value ?: return null
        return if (value.status == "COOLDOWN" && value.cooldownUntilEpochMs <= nowEpochMs) {
            value.copy(status = "READY")
        } else value
    }

    private fun baseCooldown(error: Throwable): Long {
        val message = (error.message ?: "").lowercase()
        return when {
            Regex("\\b(401|403)\\b").containsMatchIn(message) -> SIX_HOURS
            Regex("\\b429\\b").containsMatchIn(message) -> THIRTY_MINUTES
            Regex("\\b5\\d\\d\\b").containsMatchIn(message) -> TEN_MINUTES
            else -> FIVE_MINUTES
        }
    }
}
