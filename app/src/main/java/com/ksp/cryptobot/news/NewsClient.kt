package com.ksp.cryptobot.news

import com.ksp.cryptobot.core.NewsArticle

interface NewsClient {
    suspend fun latestCryptoNews(symbol: String): List<NewsArticle>
}

class NoopNewsClient : NewsClient {
    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> = emptyList()
}

class CompositeNewsClient(private val providers: List<NewsClient>) : NewsClient {
    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> {
        return providers
            .flatMap { provider ->
                val label = providerLabel(provider)
                if (!NewsProviderHealthRegistry.shouldAttempt(label)) {
                    emptyList()
                } else {
                    NewsProviderHealthRegistry.recordAttempt(label)
                    runCatching { provider.latestCryptoNews(symbol) }
                        .fold(
                            onSuccess = { rows ->
                                NewsProviderHealthRegistry.recordSuccess(label, rows.size)
                                rows
                            },
                            onFailure = { error ->
                                NewsProviderHealthRegistry.recordFailure(label, error)
                                emptyList()
                            }
                        )
                }
            }
            .distinctBy { it.url.ifBlank { it.title.lowercase() } }
            .sortedByDescending { it.publishedAt }
            .take(40)
    }

    private fun providerLabel(provider: NewsClient): String = when (provider.javaClass.simpleName) {
        "GdeltNewsClient" -> "GDELT"
        "RssFeedNewsClient" -> "RSS"
        "CryptoPanicNewsClient" -> "CryptoPanic"
        "MarketauxNewsClient" -> "Marketaux"
        "NewsDataNewsClient" -> "NewsData.io"
        "GNewsNewsClient" -> "GNews"
        "GuardianNewsClient" -> "Guardian"
        "NewsApiClient" -> "NewsAPI"
        else -> provider.javaClass.simpleName.ifBlank { "UnknownNewsProvider" }
    }
}
