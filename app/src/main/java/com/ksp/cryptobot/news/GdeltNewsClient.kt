package com.ksp.cryptobot.news

import com.ksp.cryptobot.core.NewsArticle
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private data class GdeltCacheEntry(
    val fetchedAtEpochMs: Long,
    val rows: List<NewsArticle>
)

class GdeltNewsClient(
    private val baseUrl: String = "https://api.gdeltproject.org"
) : NewsClient {
    private val client = OkHttpClient.Builder().build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(GdeltResponse::class.java)

    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> = withContext(Dispatchers.IO) {
        val normalizedSymbol = symbol.uppercase().replace("/", "").replace("-", "").replace("_", "")
        val now = System.currentTimeMillis()

        // Fresh symbol data is reused. If another symbol arrives before the
        // global six-second request window opens, use stale cache when possible
        // or fail-neutral for this pass. Never sleep/block the trading scan.
        val decision: Pair<Boolean, List<NewsArticle>?> = synchronized(throttleLock) {
            val cached = cache[normalizedSymbol]
            val cacheAge = cached?.let { now - it.fetchedAtEpochMs } ?: Long.MAX_VALUE
            when {
                cached != null && cacheAge in 0L..CACHE_TTL_MS ->
                    false to cached.rows
                now - lastRemoteAttemptEpochMs < MIN_REMOTE_INTERVAL_MS ->
                    false to cached?.takeIf {
                        cacheAge in 0L..STALE_CACHE_MAX_AGE_MS
                    }?.rows
                else -> {
                    lastRemoteAttemptEpochMs = now
                    true to null
                }
            }
        }

        if (!decision.first) {
            return@withContext decision.second.orEmpty()
        }

        val base = baseAssetFromSymbol(symbol)
        val query = when (base) {
            "BTC" -> "(bitcoin OR BTC) crypto"
            "ETH" -> "(ethereum OR ETH) crypto"
            "SOL" -> "(solana OR SOL) crypto"
            "XRP" -> "(ripple OR XRP) crypto"
            else -> "($base crypto OR $base cryptocurrency OR $base blockchain)"
        }

        val url = "$baseUrl/api/v2/doc/doc".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("mode", "ArtList")
            .addQueryParameter("format", "json")
            .addQueryParameter("maxrecords", "25")
            .addQueryParameter("sort", "HybridRel")
            .build()

        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("GDELT HTTP ${response.code}: ${body.take(180)}")

            val parsed = adapter.fromJson(body)
            val rows = parsed?.articles.orEmpty().mapNotNull { item ->
                val title = item.title ?: return@mapNotNull null
                NewsArticle(
                    title = title,
                    description = item.seendate.orEmpty(),
                    source = "GDELT:${item.sourceCountry.orEmpty()}",
                    url = item.url.orEmpty(),
                    publishedAt = item.seendate?.let { parseGdeltDate(it) }
                )
            }

            synchronized(throttleLock) {
                cache[normalizedSymbol] = GdeltCacheEntry(now, rows)
                while (cache.size > MAX_CACHE_SYMBOLS) {
                    val oldestKey = cache.minByOrNull { it.value.fetchedAtEpochMs }?.key ?: break
                    cache.remove(oldestKey)
                }
            }
            rows
        }
    }

    private fun parseGdeltDate(raw: String): Instant? = runCatching {
        LocalDateTime.parse(raw.take(14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            .toInstant(ZoneOffset.UTC)
    }.getOrNull()

    private fun baseAssetFromSymbol(symbol: String): String {
        val clean = symbol.uppercase().replace("/", "").replace("-", "").replace("_", "")
        val quotes = listOf("ZEUR", "ZUSD", "EUR", "USD", "USDT", "USDC", "GBP", "BTC", "ETH")
        val raw = quotes.firstOrNull { clean.endsWith(it) }?.let { clean.removeSuffix(it) } ?: clean
        return when (raw) {
            "XBT", "XXBT" -> "BTC"
            "XETH" -> "ETH"
            "XXRP" -> "XRP"
            else -> raw.removePrefix("X").removePrefix("Z")
        }
    }

    companion object {
        // GDELT's own 429 response asks clients to stay at or below one request
        // every five seconds. CTS uses six seconds for headroom.
        private const val MIN_REMOTE_INTERVAL_MS = 6_000L

        // News does not need a new GDELT request every 30/60-second trading scan.
        private const val CACHE_TTL_MS = 15L * 60L * 1000L

        // Previously fetched context remains usable during transient trouble.
        private const val STALE_CACHE_MAX_AGE_MS = 60L * 60L * 1000L

        private const val MAX_CACHE_SYMBOLS = 500

        private val throttleLock = Any()
        private var lastRemoteAttemptEpochMs: Long = 0L
        private val cache = linkedMapOf<String, GdeltCacheEntry>()
    }
}

data class GdeltResponse(val articles: List<GdeltArticle>? = emptyList())

data class GdeltArticle(
    val url: String?,
    val title: String?,
    val seendate: String?,
    @Json(name = "sourcecountry") val sourceCountry: String?
)
