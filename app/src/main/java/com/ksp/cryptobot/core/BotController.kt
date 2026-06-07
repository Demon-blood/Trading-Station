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
import com.ksp.cryptobot.status.BotStatusStore
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
    private val statusStore = BotStatusStore(appContext)
    private val guard = ExecutionGuard(dao)
    private val advancedRiskManager = AdvancedRiskManager(dao)
    private val advancedAutomationEngine = AdvancedAutomationEngine()
    private val _status = MutableStateFlow("Stopped")
    val status: StateFlow<String> = _status

    @Volatile var running: Boolean = false
        private set

    private fun updateStatus(message: String, level: String = "INFO") {
        _status.value = message
        statusStore.write(message, level)
    }

    suspend fun scanOnce(settings: BotSettings = settingsStore.load(), execute: Boolean = false): List<AiDecision> {
        updateStatus(if (execute) "Scan started with execution enabled. Provider=${settings.exchangeProvider}, mode=${settings.mode}, manual=${settings.manualExecutionMode}" else "Scan started. Provider=${settings.exchangeProvider}, mode=${settings.mode}")
        val exchange = createExchange(settings)
        val liveAutoExecution = execute && settings.mode == BotMode.LIVE_AUTO && settings.exchangeProvider != ExchangeProvider.PAPER
        val liveBalances = if (liveAutoExecution) {
            runCatching { exchange.getAvailableBalances() }
                .onSuccess { balances ->
                    val eur = balances["EUR"] ?: balances["ZEUR"] ?: BigDecimal.ZERO
                    updateStatus("Live balance check: available EUR=${eur.setScale(2, RoundingMode.DOWN)}. Funds will be reserved per submitted order.", "INFO")
                }
                .onFailure { error -> updateStatus("Live balance check failed: ${error.message}. Orders may be blocked before submit.", "ERROR") }
                .getOrDefault(emptyMap())
        } else emptyMap()
        var reservedEurThisScan = BigDecimal.ZERO
        val newsClient = createNewsClient(settings)
        val recentTrades = dao.recentTradesSnapshot(100)
        val symbols = if (settings.tradeOnlyBtcEth) settings.symbols().filter { it.startsWith("BTC") || it.startsWith("ETH") } else settings.symbols()
        val decisions = symbols.mapNotNull { symbol ->
            runCatching {
                updateStatus("[$symbol] Fetching ticker from ${settings.exchangeProvider}...")
                val ticker = exchange.getTicker(symbol)
                updateStatus("[$symbol] Ticker OK. Bid=${ticker.bid}, Ask=${ticker.ask}, Last=${ticker.lastPrice}, 24hVolEUR=${ticker.volume24h.setScale(0, RoundingMode.HALF_UP)}")
                val candlesByTimeframe = if (settings.recoveredScalpingStrategyEnabled) {
                    updateStatus("[$symbol] Fetching multi-timeframe candles...")
                    Timeframe.values().associateWith { timeframe -> exchange.getCandles(symbol, timeframe, 140) }
                } else emptyMap()
                updateStatus("[$symbol] Running recommendation + AI automation engines...")
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
                updateStatus("[$symbol] Decision=${decision.finalAction}, score=${decision.finalScore}, allowed=${decision.allowedToTrade}. ${decision.explanation.take(180)}")
                if (execute) {
                    val reserved = executeDecisionIfAllowed(settings, exchange, ticker, decision, liveBalances, reservedEurThisScan)
                    if (reserved > BigDecimal.ZERO) {
                        reservedEurThisScan = reservedEurThisScan.add(reserved)
                        updateStatus("[$symbol] Reserved EUR this scan now ${reservedEurThisScan.setScale(2, RoundingMode.UP)}", "INFO")
                    }
                }
                decision
            }.getOrElse { error ->
                updateStatus("[$symbol] Scan failed: ${error.message}", "ERROR")
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
        updateStatus("Last scan complete: ${decisions.size} AI decisions. Execute=$execute. ReservedEUR=${reservedEurThisScan.setScale(2, RoundingMode.UP)}")
        return decisions
    }

    private suspend fun executeDecisionIfAllowed(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        ticker: MarketTicker,
        decision: AiDecision,
        liveBalances: Map<String, BigDecimal> = emptyMap(),
        reservedEurThisScan: BigDecimal = BigDecimal.ZERO
    ): BigDecimal {
        val capability = ExchangeCapabilityChecker.capability(settings.exchangeProvider)
        if (settings.manualExecutionMode || capability.manualOnly || !capability.liveTrading) {
            updateStatus("Manual/read-only mode: signal saved, no automatic order sent. ${capability.warning}", "WARN")
            return BigDecimal.ZERO
        }
        if (settings.mode != BotMode.PAPER && (settingsStore.exchangeApiKey(settings.exchangeProvider).isNullOrBlank() || settingsStore.exchangeSecretKey(settings.exchangeProvider).isNullOrBlank())) {
            updateStatus("Trade blocked: missing ${capability.displayName} API credentials.", "ERROR")
            return BigDecimal.ZERO
        }
        val allowed = guard.canExecute(settings, decision)
        if (!allowed.first) {
            updateStatus("Trade blocked: ${allowed.second}", "WARN")
            return BigDecimal.ZERO
        }
        if (settings.mode == BotMode.LIVE_CONFIRM) {
            updateStatus("Live confirm mode: decision saved, no automatic order placed.", "WARN")
            return BigDecimal.ZERO
        }
        val side = if (decision.finalAction == SignalAction.SELL) OrderSide.SELL else OrderSide.BUY
        val price = if (side == OrderSide.BUY) ticker.ask else ticker.bid
        val feeReserveMultiplier = BigDecimal("1.01")
        val minimumOrderEur = BigDecimal("5.00")
        val availableEur = liveBalances["EUR"] ?: liveBalances["ZEUR"]
        val remainingEur = availableEur?.subtract(reservedEurThisScan)?.max(BigDecimal.ZERO)

        val targetNotionalEur = if (side == OrderSide.BUY && remainingEur != null && settings.mode != BotMode.PAPER) {
            val safeRemaining = remainingEur.divide(feeReserveMultiplier, 2, RoundingMode.DOWN)
            settings.maxPositionEur.min(safeRemaining)
        } else {
            settings.maxPositionEur
        }

        if (side == OrderSide.BUY) {
            updateStatus("[${ticker.symbol}] EUR budget: available=${availableEur?.setScale(2, RoundingMode.DOWN) ?: "unknown"}, reservedThisScan=${reservedEurThisScan.setScale(2, RoundingMode.DOWN)}, targetOrder=${targetNotionalEur.setScale(2, RoundingMode.DOWN)}", "INFO")
        }

        if (side == OrderSide.BUY && targetNotionalEur < minimumOrderEur) {
            updateStatus("Trade blocked: not enough free EUR after already reserved orders. Remaining=${remainingEur?.setScale(2, RoundingMode.DOWN) ?: "unknown"}, minimum=$minimumOrderEur", "WARN")
            return BigDecimal.ZERO
        }

        val quantity = targetNotionalEur.divide(price, 8, RoundingMode.DOWN)
        if (quantity <= BigDecimal.ZERO) {
            updateStatus("Trade blocked: calculated quantity is zero.", "ERROR")
            return BigDecimal.ZERO
        }
        val request = OrderRequest(
            symbol = ticker.symbol,
            side = side,
            quantity = quantity,
            limitPrice = price,
            clientOrderId = "ksp-${ticker.symbol.lowercase()}-${System.currentTimeMillis()}"
        )
        updateStatus("Submitting ${settings.exchangeProvider} ${request.side} LIMIT order: ${request.symbol}, notional=${targetNotionalEur.setScale(2, RoundingMode.DOWN)}, qty=${request.quantity}, price=${request.limitPrice}, id=${request.clientOrderId}", "LIVE")
        val result = runCatching { exchange.placeOrder(request) }.getOrElse { error ->
            updateStatus("Order submit failed: ${error.message}", "ERROR")
            throw error
        }
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
        updateStatus("Order placed: ${result.side} ${result.symbol} ${if (result.paper) "PAPER" else "LIVE"}. orderId=${result.exchangeOrderId}", if (result.paper) "INFO" else "LIVE")
        return if (side == OrderSide.BUY) targetNotionalEur.multiply(feeReserveMultiplier).setScale(2, RoundingMode.UP) else BigDecimal.ZERO
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
        updateStatus("Bot controller running.")
    }

    fun stop() {
        running = false
        updateStatus("Bot controller stopped.")
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
