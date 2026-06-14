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

class GdeltNewsClient(
    private val baseUrl: String = "https://api.gdeltproject.org"
) : NewsClient {
    private val client = OkHttpClient.Builder().build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(GdeltResponse::class.java)

    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> = withContext(Dispatchers.IO) {
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
            val parsed = adapter.fromJson(body) ?: return@withContext emptyList()
            parsed.articles.orEmpty().mapNotNull { item ->
                val title = item.title ?: return@mapNotNull null
                NewsArticle(
                    title = title,
                    description = item.seendate.orEmpty(),
                    source = "GDELT:${item.sourceCountry.orEmpty()}",
                    url = item.url.orEmpty(),
                    publishedAt = item.seendate?.let { parseGdeltDate(it) }
                )
            }
        }
    }

    private fun parseGdeltDate(raw: String): Instant? = runCatching {
        LocalDateTime.parse(raw.take(14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).toInstant(ZoneOffset.UTC)
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
}

data class GdeltResponse(val articles: List<GdeltArticle>? = emptyList())
data class GdeltArticle(
    val url: String?,
    val title: String?,
    val seendate: String?,
    @Json(name = "sourcecountry") val sourceCountry: String?
)
