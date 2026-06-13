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
import java.time.LocalDate

class NewsApiClient(
    private val apiKey: String,
    private val baseUrl: String = "https://newsapi.org",
    private val providerName: String = "NewsAPI"
) : NewsClient {
    private val client = OkHttpClient.Builder().build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(NewsApiResponse::class.java)

    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> = withContext(Dispatchers.IO) {
        val baseAsset = baseAssetFromSymbol(symbol)
        val primaryQuery = symbolQuery(baseAsset)
        val primary = fetchNews(primaryQuery, 25)
        if (primary.isNotEmpty()) {
            return@withContext primary
                .filterForSymbol(baseAsset)
                .ifEmpty { primary }
                .distinctBy { it.title.lowercase().take(120) }
                .take(20)
        }

        // Fallback: still use the symbol name, but loosen the query so smaller coins can return data.
        val fallbackQuery = "\"$baseAsset\" AND (crypto OR cryptocurrency OR blockchain OR token OR exchange)"
        fetchNews(fallbackQuery, 15)
            .distinctBy { it.title.lowercase().take(120) }
            .take(15)
    }

    private fun fetchNews(query: String, pageSize: Int): List<NewsArticle> {
        val url = "$baseUrl/v2/everything".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("searchIn", "title,description")
            .addQueryParameter("language", "en")
            .addQueryParameter("sortBy", "publishedAt")
            .addQueryParameter("from", LocalDate.now().minusDays(7).toString())
            .addQueryParameter("pageSize", pageSize.coerceIn(5, 50).toString())
            .addQueryParameter("apiKey", apiKey)
            .build()
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val parsed = adapter.fromJson(body) ?: return emptyList()
            parsed.articles.mapNotNull { article ->
                val title = article.title ?: return@mapNotNull null
                if (title.equals("[Removed]", ignoreCase = true)) return@mapNotNull null
                NewsArticle(
                    title = title,
                    description = article.description.orEmpty(),
                    source = "$providerName:${article.source?.name.orEmpty()}",
                    url = article.url.orEmpty(),
                    publishedAt = article.publishedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                )
            }
        }
    }

    private fun baseAssetFromSymbol(symbol: String): String {
        val clean = symbol.uppercase().replace("/", "").replace("-", "").replace("_", "")
        val knownQuotes = listOf("ZEUR", "ZUSD", "EUR", "USD", "USDT", "USDC", "GBP", "BTC", "ETH")
        val raw = knownQuotes.firstOrNull { clean.endsWith(it) }?.let { clean.removeSuffix(it) } ?: clean
        return when (raw) {
            "XBT", "XXBT" -> "BTC"
            "XETH" -> "ETH"
            "XXRP" -> "XRP"
            else -> raw.removePrefix("X").removePrefix("Z")
        }
    }

    private fun symbolQuery(baseAsset: String): String {
        return when (baseAsset.uppercase()) {
            "BTC" -> "(Bitcoin OR BTC) AND (crypto OR cryptocurrency OR ETF OR market)"
            "ETH" -> "(Ethereum OR ETH) AND (crypto OR cryptocurrency OR ETF OR staking)"
            "SOL" -> "(Solana OR SOL) AND (crypto OR cryptocurrency OR blockchain)"
            "XRP" -> "(XRP OR Ripple) AND (crypto OR cryptocurrency OR lawsuit OR ETF)"
            "ADA" -> "(Cardano OR ADA) AND (crypto OR cryptocurrency OR blockchain)"
            "DOGE" -> "(Dogecoin OR DOGE) AND (crypto OR cryptocurrency)"
            "DOT" -> "(Polkadot OR DOT) AND (crypto OR cryptocurrency)"
            "LINK" -> "(Chainlink OR LINK) AND (crypto OR cryptocurrency)"
            "LTC" -> "(Litecoin OR LTC) AND (crypto OR cryptocurrency)"
            "MATIC", "POL" -> "(Polygon OR MATIC OR POL) AND (crypto OR cryptocurrency)"
            "AVAX" -> "(Avalanche OR AVAX) AND (crypto OR cryptocurrency)"
            "ATOM" -> "(Cosmos OR ATOM) AND (crypto OR cryptocurrency)"
            "ALGO" -> "(Algorand OR ALGO) AND (crypto OR cryptocurrency)"
            "XLM" -> "(Stellar OR XLM) AND (crypto OR cryptocurrency)"
            "TRX" -> "(TRON OR TRX) AND (crypto OR cryptocurrency)"
            else -> "($baseAsset OR \"$baseAsset token\") AND (crypto OR cryptocurrency OR blockchain OR exchange)"
        }
    }

    private fun List<NewsArticle>.filterForSymbol(baseAsset: String): List<NewsArticle> {
        val aliases = symbolQuery(baseAsset)
            .replace("(", " ")
            .replace(")", " ")
            .replace("\"", " ")
            .split(" OR ", " AND ", " ")
            .map { it.trim().lowercase() }
            .filter { it.length >= 3 && it !in setOf("crypto", "cryptocurrency", "blockchain", "market", "token", "exchange") }
            .toSet()
        if (aliases.isEmpty()) return this
        return filter { article ->
            val text = "${article.title} ${article.description}".lowercase()
            aliases.any { text.contains(it) }
        }
    }
}

data class NewsApiResponse(val status: String?, val totalResults: Int?, val articles: List<NewsApiArticle> = emptyList())
data class NewsApiArticle(
    val source: NewsApiSource?,
    val title: String?,
    val description: String?,
    val url: String?,
    @Json(name = "publishedAt") val publishedAt: String?
)
data class NewsApiSource(val id: String?, val name: String?)
