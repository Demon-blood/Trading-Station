package com.ksp.cryptobot.intelligence

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.TradeEntity

class AiDecisionEngine(
    private val newsSentimentEngine: NewsSentimentEngine = NewsSentimentEngine(),
    private val tradeMemoryEngine: TradeMemoryEngine = TradeMemoryEngine()
) {
    fun decide(
        recommendation: Recommendation,
        news: List<NewsArticle>,
        recentTrades: List<TradeEntity>,
        settings: BotSettings
    ): AiDecision {
        val newsScore = if (settings.useNewsAi) newsSentimentEngine.score(news) else 0
        val memoryScore = if (settings.useTradeMemoryAi) tradeMemoryEngine.score(recommendation.symbol, recentTrades) else 0
        val technicalScore = recommendation.score
        val finalScore = (technicalScore + newsScore + memoryScore).coerceIn(0, 100)
        val confidence = when {
            finalScore >= 80 -> 85
            finalScore >= 70 -> 75
            finalScore >= 60 -> 65
            finalScore >= 50 -> 55
            else -> 40
        }
        val action = when {
            newsScore <= -25 -> SignalAction.WAIT
            recommendation.riskPercent.toDouble() >= 15.0 -> SignalAction.WAIT
            finalScore >= 78 -> SignalAction.BUY
            finalScore >= 68 -> SignalAction.SMALL_BUY
            finalScore >= 55 -> SignalAction.WATCH
            finalScore >= 45 -> SignalAction.WAIT
            else -> SignalAction.AVOID
        }
        val allowed = action == SignalAction.BUY || action == SignalAction.SMALL_BUY
        val explanation = buildString {
            append("Technical=${technicalScore}, news=${newsScore}, newsArticles=${news.size}, memory=${memoryScore}, final=${finalScore}. ")
            append(newsSentimentEngine.explain(newsScore)).append(' ')
            append(tradeMemoryEngine.explain(memoryScore)).append(' ')
            append("Base reason: ${recommendation.reason}")
        }
        return AiDecision(
            symbol = recommendation.symbol,
            finalAction = action,
            finalScore = finalScore,
            confidencePercent = confidence,
            technicalScore = technicalScore,
            newsScore = newsScore,
            memoryScore = memoryScore,
            allowedToTrade = allowed,
            explanation = explanation
        )
    }
}
