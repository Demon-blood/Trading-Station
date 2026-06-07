package com.ksp.cryptobot.news

import com.ksp.cryptobot.core.NewsArticle

interface NewsClient {
    suspend fun latestCryptoNews(symbol: String): List<NewsArticle>
}

class NoopNewsClient : NewsClient {
    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> = emptyList()
}
