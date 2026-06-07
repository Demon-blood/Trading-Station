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
import com.ksp.cryptobot.lifecycle.TradeLifecycleManager
import com.ksp.cryptobot.strategy.RecommendationEngine
import com.ksp.cryptobot.pro.ProAutomationSuite
import com.ksp.cryptobot.autonomous.AutonomousIntelligencePack
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
    private val lifecycleManager = TradeLifecycleManager(dao, statusStore)
    private val proAutomationSuite = ProAutomationSuite(appContext)
    private val autonomousPack = AutonomousIntelligencePack(appContext)
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
        val proReadiness = proAutomationSuite.readiness(settings)
        proReadiness.lines.take(12).forEach { updateStatus("Pro readiness: $it", if (it.startsWith("BLOCK")) "WARN" else "INFO") }
        autonomousPack.watchdogLines(settings).take(6).forEach { updateStatus("Autonomous watchdog: $it", if (it.startsWith("BLOCK")) "WARN" else "INFO") }
        if (execute && !proReadiness.allowed && settings.mode != BotMode.PAPER) {
            updateStatus("Execution blocked by v1.1 pro readiness gate: ${proReadiness.level}", "WARN")
            return emptyList()
        }
        val exchange = createExchange(settings)
        val liveAutoExecution = execute && settings.mode == BotMode.LIVE_AUTO && settings.exchangeProvider != ExchangeProvider.PAPER
        if (liveAutoExecution) {
            manageExistingLiveOrders(settings, exchange)
            lifecycleManager.runPreScanMaintenance(settings, exchange)
        }
        val liveBalances = if (liveAutoExecution) {
            runCatching { exchange.getAvailableBalances() }
                .onSuccess { balances ->
                    val eur = balances["EUR"] ?: balances["ZEUR"] ?: BigDecimal.ZERO
                    updateStatus("Live balance check: free/available EUR=${eur.setScale(2, RoundingMode.DOWN)}. This excludes funds locked in open orders.", "INFO")
                    val positiveBalances = balances.entries
                        .filter { it.value > BigDecimal.ZERO && it.key.length <= 6 }
                        .distinctBy { it.key }
                        .take(10)
                        .joinToString(", ") { "${it.key}=${it.value.stripTrailingZeros().toPlainString()}" }
                    updateStatus("Tradeable balance snapshot: ${positiveBalances.ifBlank { "no positive free balances returned by API" }}", "INFO")
                    runCatching { exchange.getBalanceDiagnostics() }
                        .onSuccess { lines -> lines.take(30).forEach { updateStatus(it, "INFO") } }
                        .onFailure { error -> updateStatus("Balance diagnostics failed: ${error.message}", "WARN") }
                }
                .onFailure { error -> updateStatus("Live balance check failed: ${error.message}. Orders may be blocked before submit.", "ERROR") }
                .getOrDefault(emptyMap())
        } else emptyMap()
        var reservedEurThisScan = BigDecimal.ZERO
        val newsClient = createNewsClient(settings)
        val recentTrades = dao.recentTradesSnapshot(100)
        val configuredSymbols = selectSymbolUniverse(settings, exchange)
        val symbols = configuredSymbols.mapNotNull { rawSymbol ->
            val autonomousAssessment = autonomousPack.assessSymbol(rawSymbol, recentTrades, settings)
            updateStatus("[$rawSymbol] v1.2 assessment: strategy=${autonomousAssessment.selectedStrategy}, allowed=${autonomousAssessment.allowed}, winRate=${autonomousAssessment.winRatePercent}%, pf=${autonomousAssessment.profitFactor}. ${autonomousAssessment.optimizerHint}", if (autonomousAssessment.allowed) "INFO" else "WARN")
            if (!autonomousAssessment.allowed) {
                updateStatus("[$rawSymbol] Auto-disabled before scan: ${autonomousAssessment.disableReason}", "WARN")
                null
            } else if (settings.exchangeProvider == ExchangeProvider.KRAKEN && settings.mode != BotMode.PAPER) {
                runCatching { exchange.validateSymbol(rawSymbol) }
                    .onSuccess { info ->
                        if (info.tradable) {
                            updateStatus("[$rawSymbol] Symbol valid: ${info.normalizedSymbol} → Kraken pair ${info.exchangePair}, min=${info.minOrderSize}, priceDecimals=${info.priceDecimals}, qtyDecimals=${info.quantityDecimals}", "INFO")
                        } else {
                            updateStatus("[$rawSymbol] Symbol skipped: not tradable. ${info.reason}", "WARN")
                        }
                    }
                    .getOrElse { error ->
                        updateStatus("[$rawSymbol] Symbol validation failed: ${error.message}", "ERROR")
                        null
                    }
                    ?.takeIf { it.tradable }
                    ?.normalizedSymbol
            } else rawSymbol
        }.distinct()
        val decisions = symbols.mapNotNull { symbol ->
            runCatching {
                updateStatus("[$symbol] Fetching ticker from ${settings.exchangeProvider}...")
                val ticker = exchange.getTicker(symbol)
                updateStatus("[$symbol] Ticker OK. Bid=${ticker.bid}, Ask=${ticker.ask}, Last=${ticker.lastPrice}, 24hVolEUR=${ticker.volume24h.setScale(0, RoundingMode.HALF_UP)}")
                val symbolRank = proAutomationSuite.rankSymbol(ticker, recentTrades)
                updateStatus("[$symbol] Smart rotation score=${symbolRank.score}. ${symbolRank.reason}", if (symbolRank.score < 45) "WARN" else "INFO")
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
                val rawDecision = baseDecision.copy(
                    finalAction = autoDecision.finalAction,
                    finalScore = autoDecision.finalScore,
                    confidencePercent = autoDecision.finalScore.coerceIn(0, 100),
                    technicalScore = autoDecision.finalScore - baseDecision.newsScore - baseDecision.memoryScore,
                    allowedToTrade = autoDecision.allowed,
                    explanation = autoDecision.explanation
                )
                val autonomousAssessment = autonomousPack.assessSymbol(symbol, recentTrades, settings)
                val decision = autonomousPack.enrichDecision(rawDecision, ticker, settings, autonomousAssessment)
                val replay = autonomousPack.buildTradeReplay(decision, ticker, settings)
                val netCheck = proAutomationSuite.netProfitCheck(ticker, decision, settings)
                val whyLine = proAutomationSuite.explainTrade(ticker, decision, symbolRank, netCheck)
                dao.insertAiDecision(decision.copy(explanation = decision.explanation + " | " + whyLine + " | replay=" + replay.mirrorExitComparison).toEntity())
                updateStatus("[$symbol] Decision=${decision.finalAction}, score=${decision.finalScore}, allowed=${decision.allowedToTrade}. ${decision.explanation.take(180)}")
                updateStatus("[$symbol] Why/edge: ${whyLine.take(240)}", if (netCheck.allowed) "INFO" else "WARN")
                if (settings.tradeReplayEnabled) updateStatus("[$symbol] Trade replay snapshot: ${replay.mirrorExitComparison}", "INFO")
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
        if (liveAutoExecution && settings.liveLifecycleManagerEnabled) {
            val lifecycle = lifecycleManager.runPostDecisionManagement(settings, exchange, decisions)
            lifecycle.messages.take(10).forEach { updateStatus(it, if (it.contains("submitted", ignoreCase = true)) "LIVE" else "INFO") }
            updateStatus("Lifecycle manager complete: positions=${lifecycle.positions.size}, openOrders=${lifecycle.openOrders.size}, trades=${lifecycle.performance.totalTrades}", "INFO")
        }
        updateStatus("Last scan complete: ${decisions.size} AI decisions. Execute=$execute. ReservedEURThisScan=${reservedEurThisScan.setScale(2, RoundingMode.UP)}")
        return decisions
    }

    private suspend fun selectSymbolUniverse(settings: BotSettings, exchange: CryptoExchangeClient): List<String> {
        val fallback = if (settings.tradeOnlyBtcEth) {
            settings.symbols().filter { it.startsWith("BTC") || it.startsWith("ETH") }
        } else settings.symbols()
        if (!settings.autoSymbolDiscoveryEnabled || settings.exchangeProvider != ExchangeProvider.KRAKEN || settings.mode == BotMode.PAPER) {
            updateStatus("Auto symbol discovery disabled or unavailable. Using configured symbols: ${fallback.joinToString(",")}", "INFO")
            return fallback
        }
        val discovered = discoverAutoSymbols(settings, exchange)
        val selected = discovered
            .filter { it.enabledForRotation }
            .map { it.symbol }
            .let { list -> if (settings.tradeOnlyBtcEth) list.filter { it.startsWith("BTC") || it.startsWith("ETH") } else list }
            .take(settings.autoSymbolActiveLimit.coerceAtLeast(1))
        if (selected.isEmpty()) {
            updateStatus("Auto symbol discovery produced no enabled candidates. Falling back to configured symbols: ${fallback.joinToString(",")}", "WARN")
            return fallback
        }
        updateStatus("Auto symbol rotation active: ${selected.joinToString(",")}", "LIVE")
        return selected
    }

    suspend fun discoverAutoSymbols(settings: BotSettings = settingsStore.load()): List<SymbolDiscoveryCandidate> {
        return discoverAutoSymbols(settings, createExchange(settings))
    }

    private suspend fun discoverAutoSymbols(settings: BotSettings, exchange: CryptoExchangeClient): List<SymbolDiscoveryCandidate> {
        updateStatus("Auto symbol discovery started. provider=${settings.exchangeProvider}, quote=EUR, candidates=${settings.autoSymbolCandidateLimit}", "INFO")
        val raw = runCatching { exchange.discoverTradableSymbols("EUR", settings.autoSymbolCandidateLimit.coerceAtLeast(5)) }
            .onFailure { updateStatus("Auto symbol discovery failed: ${it.message}", "ERROR") }
            .getOrElse { emptyList() }
        if (raw.isEmpty()) {
            updateStatus("Auto symbol discovery returned no exchange candidates.", "WARN")
            return emptyList()
        }
        val enriched = raw.mapNotNull { candidate ->
            runCatching {
                val ticker = exchange.getTicker(candidate.symbol)
                val spreadPercent = if (ticker.lastPrice > BigDecimal.ZERO) {
                    ticker.ask.subtract(ticker.bid).divide(ticker.lastPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
                } else BigDecimal("999")
                val volumeScore = when {
                    ticker.volume24h >= settings.autoSymbolMinVolume24hEur.multiply(BigDecimal("10")) -> 30
                    ticker.volume24h >= settings.autoSymbolMinVolume24hEur -> 22
                    ticker.volume24h >= settings.autoSymbolMinVolume24hEur.divide(BigDecimal("2"), 2, RoundingMode.HALF_UP) -> 10
                    else -> -18
                }
                val spreadScore = when {
                    spreadPercent <= settings.autoSymbolMaxSpreadPercent.divide(BigDecimal("4"), 4, RoundingMode.HALF_UP) -> 30
                    spreadPercent <= settings.autoSymbolMaxSpreadPercent -> 18
                    spreadPercent <= settings.autoSymbolMaxSpreadPercent.multiply(BigDecimal("2")) -> -8
                    else -> -35
                }
                val momentum = ticker.priceChangePercent24h.abs()
                val momentumScore = when {
                    momentum < BigDecimal("0.15") -> -6
                    momentum <= BigDecimal("12.0") -> 12
                    momentum <= BigDecimal("24.0") -> 2
                    else -> -12
                }
                val majorBoost = if (candidate.symbol.startsWith("BTC") || candidate.symbol.startsWith("ETH")) 8 else 0
                val score = (50 + volumeScore + spreadScore + momentumScore + majorBoost).coerceIn(0, 100)
                val enabled = candidate.tradable &&
                    spreadPercent <= settings.autoSymbolMaxSpreadPercent &&
                    ticker.volume24h >= settings.autoSymbolMinVolume24hEur &&
                    score >= 55
                candidate.copy(
                    lastPrice = ticker.lastPrice,
                    bid = ticker.bid,
                    ask = ticker.ask,
                    spreadPercent = spreadPercent.setScale(4, RoundingMode.HALF_UP),
                    volume24hEur = ticker.volume24h.setScale(0, RoundingMode.HALF_UP),
                    change24hPercent = ticker.priceChangePercent24h.setScale(2, RoundingMode.HALF_UP),
                    score = score,
                    enabledForRotation = enabled,
                    reason = if (enabled) {
                        "Enabled: spread=${spreadPercent.setScale(3, RoundingMode.HALF_UP)}%, volume≈€${ticker.volume24h.setScale(0, RoundingMode.HALF_UP)}, 24h=${ticker.priceChangePercent24h.setScale(2, RoundingMode.HALF_UP)}%."
                    } else {
                        "Skipped: spread=${spreadPercent.setScale(3, RoundingMode.HALF_UP)}%, volume≈€${ticker.volume24h.setScale(0, RoundingMode.HALF_UP)}, score=$score. Limits spread<=${settings.autoSymbolMaxSpreadPercent}%, volume>=€${settings.autoSymbolMinVolume24hEur}."
                    }
                )
            }.getOrElse { error ->
                updateStatus("[${candidate.symbol}] Auto-discovery ticker scoring failed: ${error.message}", "WARN")
                candidate.copy(score = 0, enabledForRotation = false, reason = "Ticker/scoring failed: ${error.message}")
            }
        }.sortedWith(compareByDescending<SymbolDiscoveryCandidate> { it.enabledForRotation }.thenByDescending { it.score }.thenBy { it.spreadPercent })
        val selected = enriched.filter { it.enabledForRotation }.take(settings.autoSymbolActiveLimit.coerceAtLeast(1))
        updateStatus("Auto symbol discovery complete. candidates=${enriched.size}, enabled=${selected.size}, selected=${selected.joinToString(",") { it.symbol }}", "INFO")
        enriched.take(12).forEach { c -> updateStatus("Symbol scanner: ${c.symbol} score=${c.score}, enabled=${c.enabledForRotation}, spread=${c.spreadPercent}%, vol≈€${c.volume24hEur}. ${c.reason.take(140)}", if (c.enabledForRotation) "INFO" else "WARN") }
        return enriched
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
        val proNetCheck = proAutomationSuite.netProfitCheck(ticker, decision, settings)
        updateStatus("[${ticker.symbol}] v1.1 fee/spread net-profit check: ${proNetCheck.reason}", if (proNetCheck.allowed) "INFO" else "WARN")
        if (!proNetCheck.allowed) {
            return BigDecimal.ZERO
        }
        if (settings.mode == BotMode.LIVE_CONFIRM) {
            updateStatus("Live confirm mode: decision saved, no automatic order placed.", "WARN")
            return BigDecimal.ZERO
        }
        val side = if (decision.finalAction == SignalAction.SELL) OrderSide.SELL else OrderSide.BUY
        val price = if (side == OrderSide.BUY) ticker.ask else ticker.bid
        val useMarketOrder = settings.enableMarketOrders && settings.mode == BotMode.LIVE_AUTO && settings.exchangeProvider == ExchangeProvider.KRAKEN
        if (useMarketOrder) {
            val spreadPct = if (ticker.lastPrice > BigDecimal.ZERO) ticker.ask.subtract(ticker.bid).divide(ticker.lastPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal.ZERO
            updateStatus("[${ticker.symbol}] MARKET order mode enabled. spread≈${spreadPct.setScale(3, RoundingMode.HALF_UP)}%, maxMarketOrder=€${settings.maxMarketOrderEur.setScale(2, RoundingMode.DOWN)}", "WARN")
            if (spreadPct > settings.marketOrderSlippageWarningPercent) {
                updateStatus("Trade blocked: market-order spread/slippage risk too high. spread=${spreadPct.setScale(3, RoundingMode.HALF_UP)}%, max=${settings.marketOrderSlippageWarningPercent}%", "WARN")
                return BigDecimal.ZERO
            }
        }
        val feeReserveMultiplier = BigDecimal("1.01")
        val minimumOrderEur = BigDecimal("5.00")
        val availableEur = liveBalances["EUR"] ?: liveBalances["ZEUR"]
        val remainingEur = availableEur?.subtract(reservedEurThisScan)?.max(BigDecimal.ZERO)
        val baseAsset = baseAssetFromSymbol(ticker.symbol)
        val availableBase = liveBalances[baseAsset]

        val perOrderCap = if (useMarketOrder) settings.maxPositionEur.min(settings.maxMarketOrderEur) else settings.maxPositionEur
        val targetNotionalEur = if (side == OrderSide.BUY && remainingEur != null && settings.mode != BotMode.PAPER) {
            val safeRemaining = remainingEur.divide(feeReserveMultiplier, 2, RoundingMode.DOWN)
            perOrderCap.min(safeRemaining)
        } else {
            perOrderCap
        }

        if (side == OrderSide.BUY) {
            updateStatus("[${ticker.symbol}] EUR budget: freeAvailable=${availableEur?.setScale(2, RoundingMode.DOWN) ?: "unknown"}, reservedByBotThisScan=${reservedEurThisScan.setScale(2, RoundingMode.DOWN)}, targetOrder=${targetNotionalEur.setScale(2, RoundingMode.DOWN)}", "INFO")
            val heldBaseValue = (availableBase ?: BigDecimal.ZERO).multiply(price)
            if (targetNotionalEur < minimumOrderEur) {
                if (heldBaseValue >= minimumOrderEur) {
                    updateStatus("Trade blocked: BUY signal but free EUR is too low. You already have ${baseAsset}≈€${heldBaseValue.setScale(2, RoundingMode.DOWN)} available; the bot will wait for a SELL signal or you must add free EUR.", "WARN")
                } else {
                    updateStatus("Trade blocked: not enough free EUR to buy. Kraken API reports free EUR=${availableEur?.setScale(2, RoundingMode.DOWN) ?: "unknown"}. If your app shows €46, it is likely crypto/portfolio value, not free EUR cash.", "WARN")
                }
                return BigDecimal.ZERO
            }
        }

        val quantity = if (side == OrderSide.SELL && settings.mode != BotMode.PAPER) {
            val desiredQuantity = targetNotionalEur.divide(price, 8, RoundingMode.DOWN)
            val freeBase = availableBase ?: BigDecimal.ZERO
            val chosen = desiredQuantity.min(freeBase).setScale(8, RoundingMode.DOWN)
            val chosenValue = chosen.multiply(price)
            updateStatus("[${ticker.symbol}] SELL budget: baseAsset=$baseAsset, freeBase=${freeBase.stripTrailingZeros().toPlainString()}, targetQty=${desiredQuantity.stripTrailingZeros().toPlainString()}, chosenQty=${chosen.stripTrailingZeros().toPlainString()}, estimatedValue=${chosenValue.setScale(2, RoundingMode.DOWN)}", "INFO")
            if (chosenValue < minimumOrderEur) {
                updateStatus("Trade blocked: SELL signal but free $baseAsset value is below Kraken minimum. Value=${chosenValue.setScale(2, RoundingMode.DOWN)}, minimum=$minimumOrderEur", "WARN")
                return BigDecimal.ZERO
            }
            chosen
        } else {
            targetNotionalEur.divide(price, 8, RoundingMode.DOWN)
        }
        if (quantity <= BigDecimal.ZERO) {
            updateStatus("Trade blocked: calculated quantity is zero.", "ERROR")
            return BigDecimal.ZERO
        }
        val request = OrderRequest(
            symbol = ticker.symbol,
            side = side,
            quantity = quantity,
            limitPrice = if (useMarketOrder) null else price,
            orderType = if (useMarketOrder) OrderType.MARKET else OrderType.LIMIT,
            clientOrderId = "ksp-${ticker.symbol.lowercase()}-${System.currentTimeMillis()}"
        )
        val orderModeLabel = if (useMarketOrder) "MARKET" else "LIMIT"
        updateStatus("Submitting ${settings.exchangeProvider} ${request.side} $orderModeLabel order: ${request.symbol}, notional≈${targetNotionalEur.setScale(2, RoundingMode.DOWN)}, qty=${request.quantity}, price=${request.limitPrice ?: "market"}, id=${request.clientOrderId}", "LIVE")
        val result = runCatching { exchange.placeOrder(request) }.getOrElse { error ->
            updateStatus("Order submit failed: ${error.message}", "ERROR")
            throw error
        }
        dao.insertTrade(
            TradeEntity(
                symbol = result.symbol,
                side = result.side.name,
                quantity = result.executedQuantity.takeIf { it > BigDecimal.ZERO }?.toPlainString() ?: quantity.toPlainString(),
                priceEur = (if (result.averagePrice > BigDecimal.ZERO) result.averagePrice else price).toPlainString(),
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

    private fun baseAssetFromSymbol(symbol: String): String {
        val upper = symbol.uppercase()
        return when {
            upper.endsWith("EUR") -> upper.removeSuffix("EUR")
            upper.endsWith("USDT") -> upper.removeSuffix("USDT")
            upper.endsWith("USD") -> upper.removeSuffix("USD")
            upper.endsWith("BTC") && upper != "BTC" -> upper.removeSuffix("BTC")
            else -> upper.take(3)
        }
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


    suspend fun exportBelgianTaxCsv(settings: BotSettings = settingsStore.load()): TaxExportSummary {
        updateStatus("Belgian tax export requested for year ${settings.taxExportYear}.", "INFO")
        val rows = dao.taxReportRowsSnapshot()
        val trades = dao.allTradesSnapshot()
        val summary = autonomousPack.exportBelgianTaxCsv(settings.taxExportYear, rows, trades)
        updateStatus("Belgian tax export ready: rows=${summary.rowCount}, realized≈€${summary.realizedGainEur.setScale(2, RoundingMode.HALF_UP)}", "INFO")
        return summary
    }

    fun parseRemoteCommand(command: String, settings: BotSettings = settingsStore.load()): RemoteCommandResult {
        val result = autonomousPack.parseRemoteCommand(command, settings)
        updateStatus("Remote command parsed: ${result.command} → ${result.message}", if (result.accepted) "INFO" else "WARN")
        return result
    }

    private suspend fun manageExistingLiveOrders(settings: BotSettings, exchange: CryptoExchangeClient) {
        if (!settings.smartLimitRequote && settings.orderManagementMode == OrderManagementMode.SIMPLE_LIMIT) return
        val orders = runCatching { exchange.getOpenOrders() }
            .onFailure { updateStatus("Open-order sync failed: ${it.message}", "WARN") }
            .getOrElse { emptyList() }
        if (orders.isEmpty()) {
            updateStatus("Open-order sync: no live open orders found.", "INFO")
            return
        }
        updateStatus("Open-order sync: ${orders.size} live order(s) currently open.", "INFO")
        val nowSec = System.currentTimeMillis() / 1000L
        orders.take(8).forEach { order ->
            val age = (nowSec - order.openedAtEpochSeconds).coerceAtLeast(0L)
            updateStatus("Open order: ${order.side} ${order.symbol} ${order.orderType} remaining=${order.remainingQuantity.stripTrailingZeros().toPlainString()} price=${order.price.stripTrailingZeros().toPlainString()} age=${age}s status=${order.status}", "INFO")
            if (settings.smartLimitRequote && order.orderType == OrderType.LIMIT && age >= settings.staleOrderTimeoutSeconds) {
                val cancelled = runCatching { exchange.cancelOrder(order.exchangeOrderId) }.getOrDefault(false)
                if (cancelled) {
                    updateStatus("Stale order cancelled for requote: ${order.exchangeOrderId} ${order.symbol} age=${age}s", "LIVE")
                } else {
                    updateStatus("Stale order cancel failed or not supported: ${order.exchangeOrderId}", "WARN")
                }
            }
        }
    }

    suspend fun loadOpenOrdersSnapshot(settings: BotSettings = settingsStore.load()): List<LiveOrderInfo> {
        updateStatus("Live open-order refresh started. Provider=${settings.exchangeProvider}")
        val exchange = createExchange(settings)
        return runCatching { exchange.getOpenOrders() }
            .onSuccess { updateStatus("Live open-order refresh complete. count=${it.size}", "INFO") }
            .onFailure { updateStatus("Live open-order refresh failed: ${it.message}", "ERROR") }
            .getOrElse { emptyList() }
    }

    suspend fun cancelLiveOrder(orderId: String, settings: BotSettings = settingsStore.load()): Boolean {
        updateStatus("Cancel requested for live order $orderId", "WARN")
        val exchange = createExchange(settings)
        return runCatching { exchange.cancelOrder(orderId) }
            .onSuccess { updateStatus("Cancel result for $orderId: $it", if (it) "LIVE" else "WARN") }
            .onFailure { updateStatus("Cancel failed for $orderId: ${it.message}", "ERROR") }
            .getOrDefault(false)
    }

    suspend fun validateConfiguredSymbols(settings: BotSettings = settingsStore.load()): List<ExchangeSymbolInfo> {
        updateStatus("Symbol validation started for ${settings.symbols().size} configured symbol(s).")
        val exchange = createExchange(settings)
        return settings.symbols().map { symbol ->
            runCatching { exchange.validateSymbol(symbol) }
                .onSuccess { info -> updateStatus("Symbol ${info.requestedSymbol}: ${if (info.tradable) "VALID" else "BLOCKED"} → ${info.exchangePair}. ${info.reason}", if (info.tradable) "INFO" else "WARN") }
                .getOrElse { error ->
                    updateStatus("Symbol $symbol validation failed: ${error.message}", "ERROR")
                    ExchangeSymbolInfo(symbol, symbol.uppercase(), symbol.uppercase(), symbol.uppercase(), symbol.removeSuffix("EUR"), "EUR", BigDecimal.ZERO, 8, 8, false, error.message ?: "Validation failed")
                }
        }
    }


    suspend fun loadPortfolioSnapshot(settings: BotSettings = settingsStore.load()): PortfolioSnapshot {
        updateStatus("Portfolio refresh started. Provider=${settings.exchangeProvider}")
        val exchange = createExchange(settings)
        val rawAssets = runCatching { exchange.getPortfolioBalances() }
            .onFailure { updateStatus("Portfolio refresh failed: ${it.message}", "ERROR") }
            .getOrElse { emptyList() }

        if (rawAssets.isEmpty()) {
            return PortfolioSnapshot(
                provider = settings.exchangeProvider,
                totalValueEur = BigDecimal.ZERO,
                freeEur = BigDecimal.ZERO,
                assets = emptyList(),
                warning = "No portfolio balances returned. Check API permissions or selected exchange."
            )
        }

        val priced = rawAssets.map { asset ->
            val eurValue = when (asset.asset.uppercase()) {
                "EUR", "ZEUR" -> asset.total
                else -> runCatching {
                    val ticker = exchange.getTicker("${asset.asset.uppercase()}EUR")
                    asset.total.multiply(ticker.bid).setScale(2, RoundingMode.DOWN)
                }.getOrDefault(BigDecimal.ZERO)
            }
            asset.copy(eurValue = eurValue)
        }.filter { it.total > BigDecimal.ZERO || it.free > BigDecimal.ZERO || it.holdTrade > BigDecimal.ZERO }
            .sortedByDescending { it.eurValue }

        val total = priced.fold(BigDecimal.ZERO) { acc, row -> acc.add(row.eurValue) }.setScale(2, RoundingMode.DOWN)
        val freeEur = priced.firstOrNull { it.asset == "EUR" || it.asset == "ZEUR" }?.free ?: BigDecimal.ZERO
        val warning = if (freeEur < BigDecimal("5.00") && total >= BigDecimal("5.00")) {
            "Portfolio has value, but free EUR is below Kraken's practical minimum for BUY orders. The bot can SELL held crypto on SELL signals, but cannot BUY until free EUR is available."
        } else "Live portfolio loaded from ${settings.exchangeProvider}."
        updateStatus("Portfolio refresh complete. Total≈€${total.toPlainString()}, freeEUR=${freeEur.setScale(2, RoundingMode.DOWN)}", "INFO")
        val snapshot = PortfolioSnapshot(settings.exchangeProvider, total, freeEur, priced, warning = warning)
        proAutomationSuite.portfolioGuard(snapshot, settings).take(10).forEach { line -> updateStatus("Portfolio balancer: $line", if (line.contains("blocks", ignoreCase = true) || line.contains("exceeds", ignoreCase = true)) "WARN" else "INFO") }
        return snapshot
    }


    suspend fun loadLifecycleSnapshot(settings: BotSettings = settingsStore.load()): LifecycleSnapshot {
        updateStatus("Lifecycle snapshot refresh started. Provider=${settings.exchangeProvider}")
        val exchange = createExchange(settings)
        return runCatching { lifecycleManager.snapshot(settings, exchange) }
            .onSuccess { updateStatus("Lifecycle snapshot loaded. positions=${it.positions.size}, openOrders=${it.openOrders.size}", "INFO") }
            .onFailure { updateStatus("Lifecycle snapshot failed: ${it.message}", "ERROR") }
            .getOrElse { LifecycleSnapshot(emptyList(), emptyList(), PerformanceSummary(0,0,0,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,"n/a","n/a"), listOf(it.message ?: "Lifecycle snapshot failed")) }
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
