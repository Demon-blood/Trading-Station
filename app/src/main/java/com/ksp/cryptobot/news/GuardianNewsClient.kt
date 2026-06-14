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

class GuardianNewsClient(
    private val apiKey: String,
    private val baseUrl: String = "https://content.guardianapis.com"
) : NewsClient {
    private val client = OkHttpClient.Builder().build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(GuardianEnvelope::class.java)

    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> = withContext(Dispatchers.IO) {
        val base = baseAssetFromSymbol(symbol)
        val query = when (base) {
            "BTC" -> "bitcoin OR BTC"
            "ETH" -> "ethereum OR ETH"
            else -> "$base cryptocurrency"
        }
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("api-key", apiKey)
            .addQueryParameter("q", query)
            .addQueryParameter("show-fields", "trailText")
            .addQueryParameter("page-size", "20")
            .addQueryParameter("order-by", "newest")
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Guardian HTTP ${response.code}: ${body.take(180)}")
            val parsed = adapter.fromJson(body) ?: return@withContext emptyList()
            parsed.response?.results.orEmpty().mapNotNull { item ->
                val title = item.webTitle ?: return@mapNotNull null
                NewsArticle(
                    title = title,
                    description = item.fields?.trailText.orEmpty().replace(Regex("<[^>]*>"), ""),
                    source = "Guardian:${item.sectionName.orEmpty()}",
                    url = item.webUrl.orEmpty(),
                    publishedAt = item.webPublicationDate?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
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

data class GuardianEnvelope(val response: GuardianResponse?)
data class GuardianResponse(val results: List<GuardianResult>? = emptyList())
data class GuardianResult(
    val webTitle: String?,
    val webUrl: String?,
    val sectionName: String?,
    val webPublicationDate: String?,
    val fields: GuardianFields?
)
data class GuardianFields(@Json(name = "trailText") val trailText: String?)
