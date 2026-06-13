package com.ksp.cryptobot.news

import com.ksp.cryptobot.core.NewsArticle

interface NewsClient {
    suspend fun latestCryptoNews(symbol: String): List<NewsArticle>
}

class NoopNewsClient : NewsClient {
    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> = emptyList()
}

class CompositeNewsClient(
    private val providers: List<NewsClient>
) : NewsClient {
    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> {
        return providers
            .flatMap { provider -> runCatching { provider.latestCryptoNews(symbol) }.getOrDefault(emptyList()) }
            .distinctBy { it.title.lowercase().take(120) }
            .sortedByDescending { it.publishedAt }
            .take(40)
    }
}
