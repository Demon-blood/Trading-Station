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
import java.time.OffsetDateTime

class NewsDataNewsClient(
    private val apiKey: String,
    private val baseUrl: String = "https://newsdata.io"
) : NewsClient {
    private val client = OkHttpClient.Builder().build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(NewsDataResponse::class.java)

    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> = withContext(Dispatchers.IO) {
        val base = baseAssetFromSymbol(symbol)
        val query = when (base) {
            "BTC" -> "bitcoin OR BTC"
            "ETH" -> "ethereum OR ETH"
            else -> "$base crypto"
        }
        val url = "$baseUrl/api/1/latest".toHttpUrl().newBuilder()
            .addQueryParameter("apikey", apiKey)
            .addQueryParameter("q", query)
            .addQueryParameter("language", "en")
            .addQueryParameter("category", "business,technology")
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("NewsData.io HTTP ${response.code}: ${body.take(180)}")
            val parsed = adapter.fromJson(body) ?: return@withContext emptyList()
            if (!parsed.status.equals("success", ignoreCase = true)) error("NewsData.io status=${parsed.status}: ${parsed.message.orEmpty().take(180)}")
            parsed.results.orEmpty().mapNotNull { item ->
                val title = item.title ?: return@mapNotNull null
                NewsArticle(
                    title = title,
                    description = item.description.orEmpty(),
                    source = "NewsData.io:${item.sourceId.orEmpty()}",
                    url = item.link.orEmpty(),
                    publishedAt = item.pubDate?.let { runCatching { OffsetDateTime.parse(it.replace(" ", "T") + "Z").toInstant() }.getOrNull() }
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

data class NewsDataResponse(val status: String?, val results: List<NewsDataArticle>? = emptyList(), val message: String? = null)
data class NewsDataArticle(
    val title: String?,
    val description: String?,
    val link: String?,
    @Json(name = "pubDate") val pubDate: String?,
    @Json(name = "source_id") val sourceId: String?
)
