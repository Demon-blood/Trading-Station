package com.ksp.cryptobot.automation

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.TradeEntity
import com.ksp.cryptobot.intelligence.AdvancedTradeMemoryEngine
import com.ksp.cryptobot.intelligence.NewsIntelligenceEngine
import com.ksp.cryptobot.strategy.MarketRegimeDetector
import com.ksp.cryptobot.strategy.MultiStrategyEngine
import com.ksp.cryptobot.strategy.PositionSizer
import java.math.BigDecimal

class AdvancedAutomationEngine(
    private val regimeDetector: MarketRegimeDetector = MarketRegimeDetector(),
    private val strategyEngine: MultiStrategyEngine = MultiStrategyEngine(),
    private val newsEngine: NewsIntelligenceEngine = NewsIntelligenceEngine(),
    private val memoryEngine: AdvancedTradeMemoryEngine = AdvancedTradeMemoryEngine(),
    private val sizer: PositionSizer = PositionSizer()
) {
    fun decide(
        ticker: MarketTicker,
        candlesByTimeframe: Map<Timeframe, List<Candle>>,
        articles: List<NewsArticle>,
        recentTrades: List<TradeEntity>,
        settings: BotSettings,
        riskState: AdvancedRiskState
    ): AutomationDecision {
        val regime = regimeDetector.detect(ticker.symbol, candlesByTimeframe)
        val news = newsEngine.score(ticker.symbol, articles)
        val selected = strategyEngine.chooseBest(ticker, candlesByTimeframe, settings, regime)
        val memory = memoryEngine.similarTradeAdjustment(ticker.symbol, selected.mode, regime.regime, recentTrades)
        val score = (selected.score + news.sentimentScore + memory.first).coerceIn(0, 100)
        val positionSize = sizer.size(settings, score, regime.regime, memory.first, news)
        val bearishExit = selected.score <= 42 ||
            regime.regime == MarketRegime.TRENDING_DOWN ||
            regime.regime == MarketRegime.RISK_OFF ||
            ticker.priceChangePercent24h < BigDecimal("-2.0") ||
            news.sentimentScore <= -15
        val action = when {
            news.blocksTrading && bearishExit -> SignalAction.SELL
            news.blocksTrading -> SignalAction.AVOID
            bearishExit -> SignalAction.SELL
            score >= settings.minStrategyScoreToBuy + 12 -> SignalAction.BUY
            score >= settings.minStrategyScoreToBuy -> SignalAction.SMALL_BUY
            score >= 58 -> SignalAction.WATCH
            score >= 45 -> SignalAction.WAIT
            else -> SignalAction.AVOID
        }
        val allowedBySignal = action == SignalAction.BUY || action == SignalAction.SMALL_BUY || action == SignalAction.SELL
        val allowed = riskState.allowed && allowedBySignal && positionSize > BigDecimal.ZERO
        val lockReason = when {
            !riskState.allowed -> riskState.lockReason
            news.blocksTrading -> AutomationLockReason.MANUAL_LOCK
            !allowedBySignal -> AutomationLockReason.MANUAL_LOCK
            positionSize <= BigDecimal.ZERO -> AutomationLockReason.DRAWDOWN
            else -> AutomationLockReason.NONE
        }
        val trailingStop = selected.stopLossPercent.multiply(settings.trailingStopAtrMultiplier).max(BigDecimal("0.10"))
        return AutomationDecision(
            symbol = ticker.symbol,
            selectedStrategy = selected.mode,
            marketRegime = regime.regime,
            finalAction = action,
            finalScore = score,
            positionSizeEur = positionSize,
            takeProfitPercent = selected.takeProfitPercent,
            stopLossPercent = selected.stopLossPercent,
            trailingStopPercent = trailingStop,
            allowed = allowed,
            lockReason = lockReason,
            explanation = "AUTO selected ${selected.mode} in ${regime.regime}. ${selected.reason} ${news.reason} ${memory.second} Risk=${riskState.reason} Position=€$positionSize."
        )
    }
}
