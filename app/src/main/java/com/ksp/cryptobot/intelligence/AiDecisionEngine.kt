package com.ksp.cryptobot.intelligence

import com.ksp.cryptobot.cloudshare.CloudShareCollectiveCache
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
        val collective = CloudShareCollectiveCache.score(
            symbol = recommendation.symbol,
            strategy = settings.strategyMode.name,
            regime = "",
            timeframe = ""
        )
        val collectiveSnapshot = CloudShareCollectiveCache.snapshot()
        val finalScore = (technicalScore + newsScore + memoryScore + collective.adjustment).coerceIn(0, 100)
        val confidence = when {
            finalScore >= 80 -> 85
            finalScore >= 70 -> 75
            finalScore >= 60 -> 65
            finalScore >= 50 -> 55
            else -> 40
        }
        val strategyEntryAllowed =
            recommendation.action == SignalAction.BUY ||
                recommendation.action == SignalAction.SMALL_BUY
        val action = when {
            recommendation.action == SignalAction.SELL -> SignalAction.SELL
            !strategyEntryAllowed -> recommendation.action
            newsScore <= -25 -> SignalAction.WAIT
            recommendation.riskPercent.toDouble() >= 15.0 -> SignalAction.WAIT
            recommendation.action == SignalAction.SMALL_BUY && finalScore >= 68 ->
                SignalAction.SMALL_BUY
            recommendation.action == SignalAction.BUY && finalScore >= 78 ->
                SignalAction.BUY
            recommendation.action == SignalAction.BUY && finalScore >= 68 ->
                SignalAction.SMALL_BUY
            finalScore >= 55 -> SignalAction.WATCH
            finalScore >= 45 -> SignalAction.WAIT
            else -> SignalAction.AVOID
        }
        val allowed = action == SignalAction.BUY || action == SignalAction.SMALL_BUY
        val explanation = buildString {
            append("Technical=${technicalScore}, news=${newsScore}, newsArticles=${news.size}, memory=${memoryScore}, collective=${collective.adjustment}, final=${finalScore}, strategyGate=${recommendation.action}. ")
            append(newsSentimentEngine.explain(newsScore)).append(' ')
            append(tradeMemoryEngine.explain(memoryScore)).append(' ')
            if (collectiveSnapshot.enabled) {
                append(collective.reason).append(' ')
                append("CloudShare data=${collectiveSnapshot.dataState}, indexed=${collectiveSnapshot.indexedSamples}, observations=${collectiveSnapshot.observationSamples}, resolvedOutcomes=${collectiveSnapshot.outcomeSamples}. ")
            }
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
