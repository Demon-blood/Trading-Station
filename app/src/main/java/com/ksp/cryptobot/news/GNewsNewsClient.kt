package com.ksp.cryptobot.news

import com.ksp.cryptobot.core.NewsArticle
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.OffsetDateTime

class GNewsNewsClient(
    private val apiKey: String,
    private val baseUrl: String = "https://gnews.io"
) : NewsClient {
    private val client = OkHttpClient.Builder().build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(GNewsResponse::class.java)

    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> = withContext(Dispatchers.IO) {
        val base = baseAssetFromSymbol(symbol)
        val query = when (base) {
            "BTC" -> "bitcoin OR BTC crypto"
            "ETH" -> "ethereum OR ETH crypto"
            else -> "$base crypto"
        }
        val url = "$baseUrl/api/v4/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("lang", "en")
            .addQueryParameter("max", "20")
            .addQueryParameter("apikey", apiKey)
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("GNews HTTP ${response.code}: ${body.take(180)}")
            val parsed = adapter.fromJson(body) ?: return@withContext emptyList()
            parsed.articles.orEmpty().mapNotNull { item ->
                val title = item.title ?: return@mapNotNull null
                NewsArticle(
                    title = title,
                    description = item.description.orEmpty(),
                    source = "GNews:${item.source?.name.orEmpty()}",
                    url = item.url.orEmpty(),
                    publishedAt = item.publishedAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
                )
            }
        }
    }

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
}

data class GNewsResponse(val articles: List<GNewsArticle>? = emptyList())
data class GNewsArticle(
    val title: String?,
    val description: String?,
    val url: String?,
    val publishedAt: String?,
    val source: GNewsSource?
)
data class GNewsSource(val name: String?)
