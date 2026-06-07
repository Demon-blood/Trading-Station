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

class NewsApiClient(
    private val apiKey: String,
    private val baseUrl: String = "https://newsapi.org"
) : NewsClient {
    private val client = OkHttpClient.Builder().build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(NewsApiResponse::class.java)

    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> = withContext(Dispatchers.IO) {
        val baseAsset = symbol.replace("EUR", "").replace("USDT", "")
        val query = when (baseAsset) {
            "BTC" -> "Bitcoin OR BTC"
            "ETH" -> "Ethereum OR ETH"
            "SOL" -> "Solana OR SOL"
            "XRP" -> "XRP OR Ripple"
            else -> "$baseAsset crypto"
        }
        val url = "$baseUrl/v2/everything".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("language", "en")
            .addQueryParameter("sortBy", "publishedAt")
            .addQueryParameter("pageSize", "10")
            .addQueryParameter("apiKey", apiKey)
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val parsed = adapter.fromJson(body) ?: return@withContext emptyList()
            parsed.articles.mapNotNull { article ->
                val title = article.title ?: return@mapNotNull null
                NewsArticle(
                    title = title,
                    description = article.description.orEmpty(),
                    source = article.source?.name.orEmpty(),
                    url = article.url.orEmpty(),
                    publishedAt = article.publishedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
                )
            }
        }
    }
}

data class NewsApiResponse(val status: String?, val totalResults: Int?, val articles: List<NewsApiArticle>)
data class NewsApiArticle(
    val source: NewsApiSource?,
    val title: String?,
    val description: String?,
    val url: String?,
    @Json(name = "publishedAt") val publishedAt: String?
)
data class NewsApiSource(val id: String?, val name: String?)
