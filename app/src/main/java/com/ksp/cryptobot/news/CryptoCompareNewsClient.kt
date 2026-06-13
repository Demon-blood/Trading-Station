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

class CryptoCompareNewsClient(
    private val baseUrl: String = "https://min-api.cryptocompare.com"
) : NewsClient {
    private val client = OkHttpClient.Builder().build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(CryptoCompareNewsResponse::class.java)

    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> = withContext(Dispatchers.IO) {
        val baseAsset = baseAssetFromSymbol(symbol)
        val categories = when (baseAsset) {
            "BTC" -> "BTC"
            "ETH" -> "ETH"
            "SOL" -> "SOL"
            "XRP" -> "XRP"
            "ADA" -> "ADA"
            "DOGE" -> "DOGE"
            else -> "Blockchain,Trading"
        }
        val url = "$baseUrl/data/v2/news/".toHttpUrl().newBuilder()
            .addQueryParameter("lang", "EN")
            .addQueryParameter("categories", categories)
            .addQueryParameter("excludeCategories", "Sponsored")
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            val parsed = adapter.fromJson(body) ?: return@withContext emptyList()
            parsed.data.orEmpty().mapNotNull { item ->
                val title = item.title ?: return@mapNotNull null
                val text = "$title ${item.body.orEmpty()}".lowercase()
                val relevant = baseAsset in setOf("BTC", "ETH", "SOL", "XRP", "ADA", "DOGE") ||
                    text.contains(baseAsset.lowercase()) ||
                    text.contains("crypto") ||
                    text.contains("bitcoin") ||
                    text.contains("ethereum")
                if (!relevant) return@mapNotNull null
                NewsArticle(
                    title = title,
                    description = item.body.orEmpty().take(500),
                    source = "CryptoCompare:${item.sourceInfo?.name.orEmpty()}",
                    url = item.url.orEmpty(),
                    publishedAt = item.publishedOn?.let { Instant.ofEpochSecond(it) }
                )
            }.take(20)
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

data class CryptoCompareNewsResponse(
    @Json(name = "Data") val data: List<CryptoCompareNewsItem>? = emptyList()
)

data class CryptoCompareNewsItem(
    val title: String?,
    val body: String?,
    val url: String?,
    @Json(name = "published_on") val publishedOn: Long?,
    @Json(name = "source_info") val sourceInfo: CryptoCompareSourceInfo?
)

data class CryptoCompareSourceInfo(val name: String?)
