package com.ksp.cryptobot.news

import com.ksp.cryptobot.core.NewsArticle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.Instant

class RssFeedNewsClient(
    private val baseUrl: String = "https://news.google.com"
) : NewsClient {
    private val client = OkHttpClient.Builder().build()

    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> = withContext(Dispatchers.IO) {
        val base = baseAssetFromSymbol(symbol)
        val query = when (base) {
            "BTC" -> "Bitcoin OR BTC crypto"
            "ETH" -> "Ethereum OR ETH crypto"
            "SOL" -> "Solana OR SOL crypto"
            "XRP" -> "XRP OR Ripple crypto"
            else -> "$base crypto OR $base cryptocurrency"
        }
        val url = "$baseUrl/rss/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("hl", "en-US")
            .addQueryParameter("gl", "US")
            .addQueryParameter("ceid", "US:en")
            .build()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("RSS HTTP ${response.code}: ${body.take(180)}")
            parseRss(body).take(25)
        }
    }

    private fun parseRss(xml: String): List<NewsArticle> {
        val out = mutableListOf<NewsArticle>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        var event = parser.eventType
        var insideItem = false
        var currentTag = ""
        var title = ""
        var link = ""
        var description = ""
        var source = "RSS"
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name.orEmpty()
                    if (currentTag == "item") {
                        insideItem = true
                        title = ""
                        link = ""
                        description = ""
                        source = "RSS"
                    }
                    if (insideItem && currentTag == "source") {
                        source = parser.getAttributeValue(null, "url") ?: "RSS"
                    }
                }
                XmlPullParser.TEXT -> if (insideItem) {
                    val text = parser.text.orEmpty()
                    when (currentTag) {
                        "title" -> title += text
                        "link" -> link += text
                        "description" -> description += text
                        "source" -> if (text.isNotBlank()) source = "RSS:$text"
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item") {
                        if (title.isNotBlank()) {
                            out += NewsArticle(
                                title = title.trim(),
                                description = description.replace(Regex("<[^>]*>"), "").trim().take(600),
                                source = source.take(120),
                                url = link.trim(),
                                publishedAt = Instant.now()
                            )
                        }
                        insideItem = false
                    }
                    currentTag = ""
                }
            }
            event = parser.next()
        }
        return out
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
