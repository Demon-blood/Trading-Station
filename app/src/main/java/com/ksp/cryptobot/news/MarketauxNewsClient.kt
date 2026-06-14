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

class MarketauxNewsClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.marketaux.com"
) : NewsClient {
    private val client = OkHttpClient.Builder().build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(MarketauxResponse::class.java)

    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> = withContext(Dispatchers.IO) {
        val base = baseAssetFromSymbol(symbol)
        val query = when (base) {
            "BTC" -> "bitcoin OR BTC"
            "ETH" -> "ethereum OR ETH"
            else -> "$base crypto"
        }
        val url = "$baseUrl/v1/news/all".toHttpUrl().newBuilder()
            .addQueryParameter("api_token", apiKey)
            .addQueryParameter("search", query)
            .addQueryParameter("language", "en")
            .addQueryParameter("limit", "20")
            .addQueryParameter("filter_entities", "true")
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Marketaux HTTP ${response.code}: ${body.take(180)}")
            val parsed = adapter.fromJson(body) ?: return@withContext emptyList()
            parsed.data.orEmpty().mapNotNull { item ->
                val title = item.title ?: return@mapNotNull null
                NewsArticle(
                    title = title,
                    description = item.description.orEmpty(),
                    source = "Marketaux:${item.source.orEmpty()}",
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

data class MarketauxResponse(val data: List<MarketauxArticle>? = emptyList())
data class MarketauxArticle(
    val title: String?,
    val description: String?,
    val url: String?,
    val source: String?,
    @Json(name = "published_at") val publishedAt: String?
)
