package com.ksp.cryptobot.intelligence

import com.ksp.cryptobot.core.*

class NewsIntelligenceEngine {
    private val bullish = listOf("etf inflow", "approval", "partnership", "adoption", "reserve", "upgrade", "institutional", "accumulate", "breakthrough")
    private val bearish = listOf("hack", "exploit", "lawsuit", "ban", "outflow", "liquidation", "delist", "investigation", "security breach", "fraud")
    private val severe = listOf("hack", "exploit", "security breach", "ban", "delist", "insolvency", "lawsuit", "fraud")

    fun score(symbol: String, articles: List<NewsArticle>): NewsEventScore {
        if (articles.isEmpty()) return NewsEventScore(symbol, 0, 0, 0, 0, false, "No recent news available.")
        val normalized = articles.distinctBy { it.title.lowercase().take(80) }
        var sentiment = 0
        var severity = 0
        normalized.forEach { article ->
            val text = "${article.title} ${article.description}".lowercase()
            bullish.forEach { if (text.contains(it)) sentiment += 4 }
            bearish.forEach { if (text.contains(it)) sentiment -= 6 }
            severe.forEach { if (text.contains(it)) severity += 18 }
        }
        val confidence = (35 + normalized.size * 8).coerceIn(35, 90)
        val blocks = severity >= 45 && sentiment < 0
        val reason = "News sentiment=$sentiment, severity=$severity, uniqueArticles=${normalized.size}, sourceConfidence=$confidence."
        return NewsEventScore(symbol, sentiment.coerceIn(-40, 40), severity.coerceIn(0, 100), confidence, normalized.size, blocks, reason)
    }
}
