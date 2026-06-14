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

class CoinGeckoNewsClient(
    private val baseUrl: String = "https://api.coingecko.com"
) : NewsClient {
    private val client = OkHttpClient.Builder().build()
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val adapter = moshi.adapter(CoinGeckoSearchResponse::class.java)

    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> = withContext(Dispatchers.IO) {
        val base = baseAssetFromSymbol(symbol)
        val url = "$baseUrl/api/v3/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", base)
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("CoinGecko HTTP ${response.code}: ${body.take(180)}")
            val parsed = adapter.fromJson(body) ?: return@withContext emptyList()
            parsed.coins.orEmpty().take(5).mapNotNull { coin ->
                val name = coin.name ?: return@mapNotNull null
                val rank = coin.marketCapRank?.toString() ?: "unknown"
                NewsArticle(
                    title = "CoinGecko market context: $name (${coin.symbol.orEmpty().uppercase()}) rank=$rank",
                    description = "Market metadata used for crypto context/trending/rank scoring. id=${coin.id.orEmpty()}",
                    source = "CoinGecko",
                    url = "https://www.coingecko.com/en/coins/${coin.id.orEmpty()}",
                    publishedAt = Instant.now()
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

data class CoinGeckoSearchResponse(val coins: List<CoinGeckoCoin>? = emptyList())
data class CoinGeckoCoin(
    val id: String?,
    val name: String?,
    val symbol: String?,
    @Json(name = "market_cap_rank") val marketCapRank: Int?
)
