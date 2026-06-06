package com.ksp.cryptobot.core

import android.content.Context
import com.ksp.cryptobot.data.*
import com.ksp.cryptobot.automation.AdvancedAutomationEngine
import com.ksp.cryptobot.exchange.BinanceReadOnlyClient
import com.ksp.cryptobot.exchange.BitvavoClient
import com.ksp.cryptobot.exchange.CoinbaseAdvancedClient
import com.ksp.cryptobot.exchange.CryptoExchangeClient
import com.ksp.cryptobot.exchange.ExchangeCapabilityChecker
import com.ksp.cryptobot.exchange.KrakenSpotClient
import com.ksp.cryptobot.exchange.ManualExecutionClient
import com.ksp.cryptobot.exchange.PaperExchangeClient
import com.ksp.cryptobot.execution.ExecutionGuard
import com.ksp.cryptobot.execution.AdvancedRiskManager
import com.ksp.cryptobot.intelligence.AiDecisionEngine
import com.ksp.cryptobot.news.NewsApiClient
import com.ksp.cryptobot.news.NewsClient
import com.ksp.cryptobot.news.NoopNewsClient
import com.ksp.cryptobot.settings.AppSettingsStore
import com.ksp.cryptobot.strategy.RecommendationEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.math.BigDecimal
import java.math.RoundingMode

class BotController(
    context: Context,
    private val recommendationEngine: RecommendationEngine = RecommendationEngine(),
    private val aiDecisionEngine: AiDecisionEngine = AiDecisionEngine()
) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(appContext).dao()
    private val settingsStore = AppSettingsStore(appContext)
    private val guard = ExecutionGuard(dao)
    private val advancedRiskManager = AdvancedRiskManager(dao)
    private val advancedAutomationEngine = AdvancedAutomationEngine()
    private val _status = MutableStateFlow("Stopped")
    val status: StateFlow<String> = _status

    @Volatile var running: Boolean = false
        private set

    suspend fun scanOnce(settings: BotSettings = settingsStore.load(), execute: Boolean = false): List<AiDecision> {
        _status.value = if (execute) "Scanning + executing" else "Scanning"
        val exchange = createExchange(settings)
        val newsClient = createNewsClient(settings)
        val recentTrades = dao.recentTradesSnapshot(100)
        val symbols = if (settings.tradeOnlyBtcEth) settings.symbols().filter { it.startsWith("BTC") || it.startsWith("ETH") } else settings.symbols()
        val decisions = symbols.mapNotNull { symbol ->
            runCatching {
                val ticker = exchange.getTicker(symbol)
                val candlesByTimeframe = if (settings.recoveredScalpingStrategyEnabled) {
                    Timeframe.values().associateWith { timeframe -> exchange.getCandles(symbol, timeframe, 140) }
                } else emptyMap()
                val rec = recommendationEngine.recommend(ticker, settings, candlesByTimeframe = candlesByTimeframe)
                dao.insertSignal(rec.toEntity())
                val news = newsClient.latestCryptoNews(symbol)
                val baseDecision = aiDecisionEngine.decide(rec, news, recentTrades, settings)
                val riskState = advancedRiskManager.riskState(settings)
                val autoDecision = advancedAutomationEngine.decide(ticker, candlesByTimeframe, news, recentTrades, settings, riskState)
                val decision = baseDecision.copy(
                    finalAction = autoDecision.finalAction,
                    finalScore = autoDecision.finalScore,
                    confidencePercent = autoDecision.finalScore.coerceIn(0, 100),
                    technicalScore = autoDecision.finalScore - baseDecision.newsScore - baseDecision.memoryScore,
                    allowedToTrade = autoDecision.allowed,
                    explanation = autoDecision.explanation
                )
                dao.insertAiDecision(decision.toEntity())
                if (execute) executeDecisionIfAllowed(settings, exchange, ticker, decision)
                decision
            }.getOrElse { error ->
                AiDecision(
                    symbol = symbol,
                    finalAction = SignalAction.WAIT,
                    finalScore = 0,
                    confidencePercent = 0,
                    technicalScore = 0,
                    newsScore = 0,
                    memoryScore = 0,
                    allowedToTrade = false,
                    explanation = "Scan failed: ${error.message}"
                )
            }
        }
        _status.value = "Last scan: ${decisions.size} AI decisions"
        return decisions
    }

    private suspend fun executeDecisionIfAllowed(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        ticker: MarketTicker,
        decision: AiDecision
    ) {
        val capability = ExchangeCapabilityChecker.capability(settings.exchangeProvider)
        if (settings.manualExecutionMode || capability.manualOnly || !capability.liveTrading) {
            _status.value = "Manual/read-only mode: signal saved, no automatic order sent. ${capability.warning}"
            return
        }
        if (settings.mode != BotMode.PAPER && (settingsStore.exchangeApiKey(settings.exchangeProvider).isNullOrBlank() || settingsStore.exchangeSecretKey(settings.exchangeProvider).isNullOrBlank())) {
            _status.value = "Trade blocked: missing ${capability.displayName} API credentials."
            return
        }
        val allowed = guard.canExecute(settings, decision)
        if (!allowed.first) {
            _status.value = "Trade blocked: ${allowed.second}"
            return
        }
        if (settings.mode == BotMode.LIVE_CONFIRM) {
            _status.value = "Live confirm mode: decision saved, no automatic order placed."
            return
        }
        val side = if (decision.finalAction == SignalAction.SELL) OrderSide.SELL else OrderSide.BUY
        val price = if (side == OrderSide.BUY) ticker.ask else ticker.bid
        val quantity = settings.maxPositionEur.divide(price, 6, RoundingMode.DOWN)
        if (quantity <= BigDecimal.ZERO) {
            _status.value = "Trade blocked: calculated quantity is zero."
            return
        }
        val request = OrderRequest(
            symbol = ticker.symbol,
            side = side,
            quantity = quantity,
            limitPrice = price,
            clientOrderId = "ksp-${ticker.symbol.lowercase()}-${System.currentTimeMillis()}"
        )
        val result = exchange.placeOrder(request)
        dao.insertTrade(
            TradeEntity(
                symbol = result.symbol,
                side = result.side.name,
                quantity = result.executedQuantity.takeIf { it > BigDecimal.ZERO }?.toPlainString() ?: quantity.toPlainString(),
                priceEur = result.averagePrice.toPlainString(),
                feeEur = result.fee.toPlainString(),
                paper = result.paper,
                aiScore = decision.finalScore,
                aiReason = decision.explanation,
                clientOrderId = request.clientOrderId,
                exchangeOrderId = result.exchangeOrderId,
                timestampEpochMs = result.timestamp.toEpochMilli()
            )
        )
        _status.value = "Order placed: ${result.side} ${result.symbol} ${if (result.paper) "PAPER" else "LIVE"}"
    }

    private fun createExchange(settings: BotSettings): CryptoExchangeClient {
        if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) return PaperExchangeClient()
        val apiKey = settingsStore.exchangeApiKey(settings.exchangeProvider).orEmpty()
        val secret = settingsStore.exchangeSecretKey(settings.exchangeProvider).orEmpty()
        return when (settings.exchangeProvider) {
            ExchangeProvider.PAPER -> PaperExchangeClient()
            ExchangeProvider.BINANCE_READ_ONLY -> BinanceReadOnlyClient()
            ExchangeProvider.KRAKEN -> KrakenSpotClient(apiKey, secret)
            ExchangeProvider.COINBASE_ADVANCED -> CoinbaseAdvancedClient(apiKey, secret)
            ExchangeProvider.BITVAVO -> BitvavoClient(apiKey, secret)
            ExchangeProvider.MANUAL -> ManualExecutionClient()
        }
    }

    private fun createNewsClient(settings: BotSettings): NewsClient {
        if (!settings.useNewsAi) return NoopNewsClient()
        val key = settingsStore.newsApiKey()
        return if (!key.isNullOrBlank()) NewsApiClient(key) else NoopNewsClient()
    }

    fun start() {
        running = true
        _status.value = "Running"
    }

    fun stop() {
        running = false
        _status.value = "Stopped"
    }

    private fun Recommendation.toEntity(): SignalEntity = SignalEntity(
        symbol = symbol,
        action = action.name,
        score = score,
        riskPercent = riskPercent.toPlainString(),
        reason = reason,
        timestampEpochMs = createdAt.toEpochMilli()
    )

    private fun AiDecision.toEntity(): AiDecisionEntity = AiDecisionEntity(
        symbol = symbol,
        finalAction = finalAction.name,
        finalScore = finalScore,
        confidencePercent = confidencePercent,
        technicalScore = technicalScore,
        newsScore = newsScore,
        memoryScore = memoryScore,
        allowedToTrade = allowedToTrade,
        explanation = explanation,
        timestampEpochMs = createdAt.toEpochMilli()
    )
}
