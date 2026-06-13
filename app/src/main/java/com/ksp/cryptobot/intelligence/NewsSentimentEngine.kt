package com.ksp.cryptobot.intelligence

import com.ksp.cryptobot.core.NewsArticle

class NewsSentimentEngine {
    private val positive = setOf(
        "approval", "approved", "adoption", "partnership", "upgrade", "surge", "rally", "bullish",
        "institutional", "etf inflow", "record inflow", "breakout", "accumulation", "integrates", "launches",
        "launch", "expands", "growth", "inflow", "listing", "listed", "support", "raises", "record high",
        "all-time high", "partnership", "mainnet", "upgrade", "staking", "buy", "outperform"
    )
    private val negative = setOf(
        "hack", "hacked", "exploit", "lawsuit", "ban", "banned", "crackdown", "outflow", "fraud",
        "bearish", "liquidation", "selloff", "investigation", "sec sues", "downtime", "depeg", "bankruptcy",
        "delist", "delisted", "security breach", "charges", "charged", "probe", "warning", "falls", "drops",
        "plunge", "losses", "sanction", "exploit", "stolen", "risk"
    )

    fun score(articles: List<NewsArticle>): Int {
        if (articles.isEmpty()) return 0
        var score = 0
        articles.take(15).forEach { article ->
            val text = (article.title + " " + article.description).lowercase()
            positive.forEach { if (text.contains(it)) score += 6 }
            negative.forEach { if (text.contains(it)) score -= 9 }
        }
        if (score == 0 && articles.isNotEmpty()) {
            // Non-zero confidence that the app actually checked recent news.
            // Kept intentionally tiny so neutral news does not dominate technical signals.
            score = articles.size.coerceIn(1, 5)
        }
        return score.coerceIn(-40, 40)
    }

    fun explain(score: Int): String = when {
        score >= 20 -> "News sentiment is positive."
        score <= -20 -> "News sentiment is negative; trade size should be reduced or blocked."
        score == 0 -> "No usable news sentiment signal or no articles returned."
        score > 0 -> "News sentiment is mildly positive."
        else -> "News sentiment is mildly negative."
    }
}
