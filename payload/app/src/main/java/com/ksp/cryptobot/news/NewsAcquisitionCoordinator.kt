package com.ksp.cryptobot.news

import com.ksp.cryptobot.core.NewsArticle
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

enum class NewsProviderState { HEALTHY, EMPTY, RATE_LIMITED, QUOTA_EXHAUSTED, AUTH_ERROR, TEMPORARY_FAILURE, DISABLED }

data class NewsBudgetPolicy(
    val minIntervalMs: Long,
    val maxRequestsPerDay: Int
)

data class NewsProviderBudgetSnapshot(
    val provider: String,
    val state: NewsProviderState,
    val requestsToday: Int,
    val lastRequestEpochMs: Long,
    val nextAllowedEpochMs: Long,
    val cachedArticles: Int,
    val detail: String
)

class NewsAcquisitionCoordinator {
    private data class State(
        var dayKey: Long = -1L,
        var requestsToday: Int = 0,
        var lastRequestEpochMs: Long = 0L,
        var nextAllowedEpochMs: Long = 0L,
        var status: NewsProviderState = NewsProviderState.EMPTY,
        var detail: String = "",
        var cache: List<NewsArticle> = emptyList()
    )

    private val mutex = Mutex()
    private val states = ConcurrentHashMap<String, State>()

    suspend fun fetch(
        symbol: String,
        providers: List<Pair<String, NewsClient>>,
        onStatus: (String, NewsProviderState, String) -> Unit = { _,_,_ -> }
    ): List<NewsArticle> = mutex.withLock {
        val now = System.currentTimeMillis()
        val day = now / 86_400_000L
        val all = mutableListOf<NewsArticle>()
        providers.forEach { (name, client) ->
            val state = states.getOrPut(name) { State() }
            if (state.dayKey != day) {
                state.dayKey = day
                state.requestsToday = 0
            }
            val policy = policy(name)
            if (state.requestsToday >= policy.maxRequestsPerDay) {
                state.status = NewsProviderState.QUOTA_EXHAUSTED
                state.detail = "CTS provider budget exhausted for today (${state.requestsToday}/${policy.maxRequestsPerDay}); using cache."
                onStatus(name, state.status, state.detail)
                all += relevantFromCache(symbol, state.cache)
                return@forEach
            }
            if (now < state.nextAllowedEpochMs) {
                state.status = NewsProviderState.RATE_LIMITED
                state.detail = "CTS provider pacing active until ${state.nextAllowedEpochMs}; using cache."
                onStatus(name, state.status, state.detail)
                all += relevantFromCache(symbol, state.cache)
                return@forEach
            }

            val articles = runCatching { client.latestCryptoNews(symbol) }
                .onFailure { error ->
                    state.status = classify(error.message.orEmpty())
                    state.detail = error.message.orEmpty().take(500)
                    val backoff = if (state.status in setOf(NewsProviderState.RATE_LIMITED, NewsProviderState.QUOTA_EXHAUSTED)) {
                        maxOf(policy.minIntervalMs, 15L * 60L * 1000L)
                    } else policy.minIntervalMs
                    state.nextAllowedEpochMs = now + backoff
                }
                .getOrElse { emptyList() }

            state.requestsToday += 1
            state.lastRequestEpochMs = now
            state.nextAllowedEpochMs = maxOf(state.nextAllowedEpochMs, now + policy.minIntervalMs)
            if (articles.isNotEmpty()) {
                state.cache = (articles + state.cache)
                    .distinctBy { it.title.lowercase().take(160) }
                    .sortedByDescending { it.publishedAt }
                    .take(160)
                state.status = NewsProviderState.HEALTHY
                state.detail = "articles=${articles.size}; cached=${state.cache.size}"
            } else if (state.status !in setOf(NewsProviderState.RATE_LIMITED, NewsProviderState.QUOTA_EXHAUSTED, NewsProviderState.AUTH_ERROR, NewsProviderState.TEMPORARY_FAILURE)) {
                state.status = NewsProviderState.EMPTY
                state.detail = "Provider returned no articles."
            }
            onStatus(name, state.status, state.detail)
            all += if (articles.isNotEmpty()) articles else relevantFromCache(symbol, state.cache)
        }
        all.distinctBy { it.title.lowercase().take(160) }
            .sortedByDescending { it.publishedAt }
            .take(80)
    }

    fun snapshots(): List<NewsProviderBudgetSnapshot> {
        val now = System.currentTimeMillis()
        return states.entries.sortedBy { it.key }.map { (name, s) ->
            NewsProviderBudgetSnapshot(name, s.status, s.requestsToday, s.lastRequestEpochMs, s.nextAllowedEpochMs, s.cache.size, s.detail)
        }
    }

    private fun policy(name: String): NewsBudgetPolicy {
        val n = name.lowercase()
        // CTS-local conservative budgets, not claims about provider contract quotas.
        return when {
            "gdelt" in n -> NewsBudgetPolicy(6_000L, 4_000)
            "newsapi" in n -> NewsBudgetPolicy(15L * 60L * 1000L, 90)
            "marketaux" in n -> NewsBudgetPolicy(5L * 60L * 1000L, 90)
            "newsdata" in n -> NewsBudgetPolicy(5L * 60L * 1000L, 90)
            "gnews" in n -> NewsBudgetPolicy(5L * 60L * 1000L, 90)
            "guardian" in n -> NewsBudgetPolicy(60_000L, 1_000)
            "rss" in n -> NewsBudgetPolicy(5L * 60L * 1000L, 288)
            else -> NewsBudgetPolicy(60_000L, 500)
        }
    }

    private fun classify(message: String): NewsProviderState {
        val m = message.lowercase()
        return when {
            "401" in m || "403" in m || "api key" in m && ("invalid" in m || "missing" in m) -> NewsProviderState.AUTH_ERROR
            "quota" in m || "usage_limit" in m || "usage limit" in m || "credits" in m || "402" in m -> NewsProviderState.QUOTA_EXHAUSTED
            "429" in m || "rate limit" in m || "too many requests" in m -> NewsProviderState.RATE_LIMITED
            else -> NewsProviderState.TEMPORARY_FAILURE
        }
    }

    private fun relevantFromCache(symbol: String, cache: List<NewsArticle>): List<NewsArticle> {
        if (cache.isEmpty()) return emptyList()
        val clean = symbol.uppercase().replace("/", "").replace("-", "")
        val base = listOf("USDT","USDC","EUR","USD","GBP","BTC","ETH")
            .firstOrNull { clean.endsWith(it) && clean.length > it.length }
            ?.let { clean.removeSuffix(it) } ?: clean.take(5)
        val hits = cache.filter {
            val text = (it.title + " " + it.description).uppercase()
            base in text || (base == "XBT" && "BTC" in text)
        }
        return (if (hits.isNotEmpty()) hits else cache.take(10)).take(25)
    }
}
