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
import com.ksp.cryptobot.completion.LiveVerificationEngine
import com.ksp.cryptobot.completion.LiveVerificationResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneId

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
    private val liveVerificationEngine = LiveVerificationEngine()
    private val _status = MutableStateFlow("Stopped")
    val status: StateFlow<String> = _status

    private data class ExecutionAttemptResult(
        val submitted: Boolean,
        val quoteAsset: String = "EUR",
        val reservedAmount: BigDecimal = BigDecimal.ZERO
    )

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
        val paperExecution = execute && (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER)
        val liveAutoExecution = execute && settings.mode == BotMode.LIVE_AUTO && settings.exchangeProvider != ExchangeProvider.PAPER
        if (paperExecution) {
            updateStatus("Paper execution active: simulated orders and paper wallet will be used. No real exchange order can be sent.", "INFO")
        }
        if (liveAutoExecution) {
            manageExistingLiveOrders(settings, exchange)
            lifecycleManager.runPreScanMaintenance(settings, exchange)
        }
        val liveBalances = if (liveAutoExecution || paperExecution) {
            runCatching { exchange.getAvailableBalances() }
                .onSuccess { balances ->
                    val eur = balances["EUR"] ?: balances["ZEUR"] ?: BigDecimal.ZERO
                    updateStatus("Balance check: free/available EUR=${eur.setScale(2, RoundingMode.DOWN)}. ${if (paperExecution) "Paper wallet balance." else "This excludes funds locked in open orders."}", "INFO")
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
        val reservedByQuoteThisScan = mutableMapOf<String, BigDecimal>()
        var submittedOrdersThisScan = 0
        val newsClient = createNewsClient(settings)
        val recentTrades = dao.recentTradesSnapshot(100)
        val configuredSymbols = selectSymbolUniverse(settings, exchange, liveBalances)
        val symbols = configuredSymbols.mapNotNull { rawSymbol ->
            val autonomousAssessment = autonomousPack.assessSymbol(rawSymbol, recentTrades, settings)
            updateStatus("[$rawSymbol] v1.2 assessment: strategy=${autonomousAssessment.selectedStrategy}, allowed=${autonomousAssessment.allowed}, winRate=${autonomousAssessment.winRatePercent}%, pf=${autonomousAssessment.profitFactor}. ${autonomousAssessment.optimizerHint}", if (autonomousAssessment.allowed) "INFO" else "WARN")
            if (!autonomousAssessment.allowed) {
                updateStatus("[$rawSymbol] Auto-disabled before scan: ${autonomousAssessment.disableReason}", "WARN")
                null
            } else if (settings.exchangeProvider == ExchangeProvider.KRAKEN || settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) {
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
                    if (settings.autoTradeMultipleSymbolsPerScan && submittedOrdersThisScan >= settings.maxNewTradesPerScan.coerceAtLeast(1)) {
                        updateStatus("[$symbol] Execution skipped: max new trades per scan reached (${submittedOrdersThisScan}/${settings.maxNewTradesPerScan}). Signal saved only.", "WARN")
                    } else if (!settings.autoTradeMultipleSymbolsPerScan && submittedOrdersThisScan >= 1) {
                        updateStatus("[$symbol] Execution skipped: multi-symbol execution disabled and one order was already submitted this scan.", "WARN")
                    } else {
                        val result = executeDecisionIfAllowed(settings, exchange, ticker, decision, liveBalances, reservedByQuoteThisScan.toMap())
                        if (result.submitted) {
                            submittedOrdersThisScan += 1
                        }
                        if (result.reservedAmount > BigDecimal.ZERO) {
                            val current = reservedByQuoteThisScan[result.quoteAsset] ?: BigDecimal.ZERO
                            reservedByQuoteThisScan[result.quoteAsset] = current.add(result.reservedAmount)
                            updateStatus("[$symbol] Reserved ${result.quoteAsset} this scan now ${reservedByQuoteThisScan[result.quoteAsset]?.setScale(2, RoundingMode.UP)}", "INFO")
                        }
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
        val reservedSummary = reservedByQuoteThisScan.entries.joinToString(", ") { "${it.key}=${it.value.setScale(2, RoundingMode.UP)}" }.ifBlank { "none" }
        if (execute && submittedOrdersThisScan == 0) {
            val tradableSignals = decisions.count { it.allowedToTrade && (it.finalAction == SignalAction.BUY || it.finalAction == SignalAction.SMALL_BUY || it.finalAction == SignalAction.SELL) }
            updateStatus("No orders submitted this scan. TradableSignals=$tradableSignals/${decisions.size}. Check the preceding WARN lines for exact blockers: quote balance/reserve, cooldown, max trades/hour, confidence, net-profit filter, spread, or minimum order size.", "WARN")
        }
        updateStatus("Last scan complete: ${decisions.size} AI decisions. Execute=$execute. OrdersSubmittedThisScan=$submittedOrdersThisScan. ReservedByQuoteThisScan=$reservedSummary")
        return decisions
    }

    private suspend fun selectSymbolUniverse(settings: BotSettings, exchange: CryptoExchangeClient, liveBalances: Map<String, BigDecimal> = emptyMap()): List<String> {
        val fallback = if (settings.tradeOnlyBtcEth) {
            settings.symbols().filter { it.startsWith("BTC") || it.startsWith("ETH") }
        } else settings.symbols()
        if (!settings.autoSymbolDiscoveryEnabled || (settings.exchangeProvider != ExchangeProvider.KRAKEN && settings.exchangeProvider != ExchangeProvider.PAPER && settings.mode != BotMode.PAPER)) {
            updateStatus("Auto symbol discovery disabled or unavailable. Using configured symbols: ${fallback.joinToString(",")}", "INFO")
            return fallback
        }
        if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) {
            updateStatus("Paper mode market data source: Kraken public endpoints for AssetPairs/Ticker/OHLC; fake local wallet for orders.", "INFO")
        }
        val discovered = discoverAutoSymbols(settings, exchange)
        val enabledCandidates = discovered.filter { it.enabledForRotation }
        val balanceAwareCandidates = if (settings.mode == BotMode.PAPER || liveBalances.isEmpty()) {
            enabledCandidates
        } else {
            enabledCandidates.filter { candidate ->
                val quoteFree = freeBalanceForAsset(liveBalances, candidate.quoteAsset)
                val baseFree = freeBalanceForAsset(liveBalances, candidate.baseAsset)
                val quoteSpendable = quoteFree
                    .subtract(quoteReserveAmount(settings, quoteFree))
                    .max(BigDecimal.ZERO)
                val canBuyWithQuote = candidate.quoteAsset.uppercase() in settings.allowedQuoteAssets() && quoteSpendable >= BigDecimal("5.00")
                val canSellHeldBase = candidate.lastPrice > BigDecimal.ZERO && baseFree.multiply(candidate.lastPrice) >= BigDecimal("5.00")
                canBuyWithQuote || canSellHeldBase
            }
        }
        if (settings.mode != BotMode.PAPER && liveBalances.isNotEmpty()) {
            val skippedForBalance = enabledCandidates.size - balanceAwareCandidates.size
            updateStatus(
                "Balance-aware rotation filter: ${balanceAwareCandidates.size}/${enabledCandidates.size} enabled symbols have usable quote cash or sellable base balance. SkippedForBalance=$skippedForBalance.",
                if (balanceAwareCandidates.isEmpty()) "WARN" else "INFO"
            )
            balanceAwareCandidates.take(20).forEach { candidate ->
                val q = freeBalanceForAsset(liveBalances, candidate.quoteAsset)
                val b = freeBalanceForAsset(liveBalances, candidate.baseAsset)
                updateStatus("Rotation candidate ${candidate.symbol}: free ${candidate.quoteAsset}=${q.stripTrailingZeros().toPlainString()}, free ${candidate.baseAsset}=${b.stripTrailingZeros().toPlainString()}, score=${candidate.score}", "INFO")
            }
        }
        val rotationSource = if (balanceAwareCandidates.isNotEmpty()) balanceAwareCandidates else enabledCandidates
        val selected = rotationSource
            .map { it.symbol }
            .distinct()
            .let { list -> if (settings.tradeOnlyBtcEth) list.filter { it.startsWith("BTC") || it.startsWith("ETH") } else list }
            .take(settings.autoSymbolActiveLimit.coerceAtLeast(1))
        if (selected.isEmpty()) {
            updateStatus("Auto symbol discovery produced no tradable/balance-usable candidates. Falling back to configured symbols: ${fallback.joinToString(",")}", "WARN")
            return fallback
        }
        updateStatus("Auto symbol rotation active: ${selected.size} symbols selected from full Kraken universe: ${selected.joinToString(",")}", "LIVE")
        return selected
    }

    suspend fun discoverAutoSymbols(settings: BotSettings = settingsStore.load()): List<SymbolDiscoveryCandidate> {
        return discoverAutoSymbols(settings, createExchange(settings))
    }

    private suspend fun discoverAutoSymbols(settings: BotSettings, exchange: CryptoExchangeClient): List<SymbolDiscoveryCandidate> {
        val quoteUniverse = settings.autoSymbolQuoteAsset.uppercase().ifBlank { "ALL" }
        updateStatus("Auto symbol discovery started. provider=${settings.exchangeProvider}, quoteUniverse=$quoteUniverse, candidates=${settings.autoSymbolCandidateLimit}", "INFO")
        val raw = runCatching { exchange.discoverTradableSymbols(quoteUniverse, settings.autoSymbolCandidateLimit.coerceAtLeast(5)) }
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
                val quoteBoost = when (candidate.quoteAsset.uppercase()) {
                    "EUR" -> 8
                    "USD", "USDT", "USDC" -> 5
                    "BTC", "ETH" -> 2
                    else -> 0
                }
                val quoteAllowed = settings.autoSymbolQuoteAsset.equals("ALL", ignoreCase = true) || candidate.quoteAsset.uppercase() in settings.allowedQuoteAssets()
                val quoteTradabilityPenalty = if (!quoteAllowed) -35 else if (candidate.quoteAsset.uppercase() != "EUR" && !settings.nonEurQuoteBuyEnabled) -4 else 0
                val liquidityBlocked = settings.liquidityBlacklistEnabled && (spreadPercent > settings.autoSymbolMaxSpreadPercent || ticker.volume24h < settings.autoSymbolMinVolume24hEur)
                val score = (50 + volumeScore + spreadScore + momentumScore + majorBoost + quoteBoost + quoteTradabilityPenalty).coerceIn(0, 100)
                val enabled = candidate.tradable &&
                    quoteAllowed &&
                    !liquidityBlocked &&
                    score >= 50
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
                        "Enabled: quote=${candidate.quoteAsset}, spread=${spreadPercent.setScale(3, RoundingMode.HALF_UP)}%, 24h quote-volume≈${ticker.volume24h.setScale(0, RoundingMode.HALF_UP)} ${candidate.quoteAsset}, 24h=${ticker.priceChangePercent24h.setScale(2, RoundingMode.HALF_UP)}%."
                    } else {
                        val quoteMessage = if (!quoteAllowed) " quote not allowed by settings (${settings.allowedQuoteAssetsCsv})." else ""
                        val liquidityMessage = if (liquidityBlocked) " liquidity/spread blacklist active." else ""
                        "Skipped: quote=${candidate.quoteAsset}, spread=${spreadPercent.setScale(3, RoundingMode.HALF_UP)}%, 24h quote-volume≈${ticker.volume24h.setScale(0, RoundingMode.HALF_UP)} ${candidate.quoteAsset}, score=$score. Limits spread<=${settings.autoSymbolMaxSpreadPercent}%, volume>=${settings.autoSymbolMinVolume24hEur}.$quoteMessage$liquidityMessage"
                    }
                )
            }.getOrElse { error ->
                updateStatus("[${candidate.symbol}] Auto-discovery ticker scoring failed: ${error.message}", "WARN")
                candidate.copy(score = 0, enabledForRotation = false, reason = "Ticker/scoring failed: ${error.message}")
            }
        }.sortedWith(compareByDescending<SymbolDiscoveryCandidate> { it.enabledForRotation }.thenByDescending { it.score }.thenBy { it.spreadPercent })
        val selected = enriched.filter { it.enabledForRotation }.take(settings.autoSymbolActiveLimit.coerceAtLeast(1))
        updateStatus("Auto symbol discovery complete. candidates=${enriched.size}, enabled=${selected.size}, selected=${selected.joinToString(",") { it.symbol }}", "INFO")
        enriched.take(24).forEach { c -> updateStatus("Symbol scanner: ${c.symbol} quote=${c.quoteAsset} score=${c.score}, enabled=${c.enabledForRotation}, spread=${c.spreadPercent}%, vol≈${c.volume24hEur} ${c.quoteAsset}. ${c.reason.take(140)}", if (c.enabledForRotation) "INFO" else "WARN") }
        return enriched
    }

    private suspend fun executeDecisionIfAllowed(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        ticker: MarketTicker,
        decision: AiDecision,
        liveBalances: Map<String, BigDecimal> = emptyMap(),
        reservedByQuoteThisScan: Map<String, BigDecimal> = emptyMap()
    ): ExecutionAttemptResult {
        val capability = ExchangeCapabilityChecker.capability(settings.exchangeProvider)
        if (settings.manualExecutionMode || capability.manualOnly || !capability.liveTrading) {
            updateStatus("Manual/read-only mode: signal saved, no automatic order sent. ${capability.warning}", "WARN")
            return ExecutionAttemptResult(false)
        }
        if (settings.mode != BotMode.PAPER && (settingsStore.exchangeApiKey(settings.exchangeProvider).isNullOrBlank() || settingsStore.exchangeSecretKey(settings.exchangeProvider).isNullOrBlank())) {
            updateStatus("Trade blocked: missing ${capability.displayName} API credentials.", "ERROR")
            return ExecutionAttemptResult(false)
        }
        val allowed = guard.canExecute(settings, decision)
        if (!allowed.first) {
            updateStatus("Trade blocked: ${allowed.second}", "WARN")
            return ExecutionAttemptResult(false)
        }
        val proNetCheck = proAutomationSuite.netProfitCheck(ticker, decision, settings)
        updateStatus("[${ticker.symbol}] v1.1 fee/spread net-profit check: ${proNetCheck.reason}", if (proNetCheck.allowed) "INFO" else "WARN")
        if (!proNetCheck.allowed) {
            return ExecutionAttemptResult(false)
        }
        if (settings.mode == BotMode.LIVE_CONFIRM) {
            updateStatus("Live confirm mode: decision saved, no automatic order placed.", "WARN")
            return ExecutionAttemptResult(false)
        }
        val side = if (decision.finalAction == SignalAction.SELL) OrderSide.SELL else OrderSide.BUY
        val pairInfo = runCatching { exchange.validateSymbol(ticker.symbol) }.getOrNull()
        val baseAsset = pairInfo?.baseAsset ?: baseAssetFromSymbol(ticker.symbol)
        val quoteAsset = pairInfo?.quoteAsset ?: quoteAssetFromSymbol(ticker.symbol)
        val availableQuote = liveBalances[quoteAsset] ?: if (quoteAsset == "EUR") liveBalances["ZEUR"] else null
        val availableBase = liveBalances[baseAsset]
        val quoteReserve = quoteReserveAmount(settings, availableQuote ?: BigDecimal.ZERO)
        val quoteReservedThisScan = reservedByQuoteThisScan[quoteAsset] ?: BigDecimal.ZERO
        val allowedQuotes = settings.allowedQuoteAssets()

        if (side == OrderSide.BUY && quoteAsset !in allowedQuotes) {
            updateStatus("Trade blocked: quote asset $quoteAsset is not enabled in Allowed Quote Assets (${settings.allowedQuoteAssetsCsv}). SELL remains available if you hold $baseAsset.", "WARN")
            return ExecutionAttemptResult(false)
        }
        if (side == OrderSide.BUY && settings.mode != BotMode.PAPER) {
            val openPositions = runCatching { dao.openPositionsSnapshot().size }.getOrDefault(0)
            if (openPositions >= settings.maxSimultaneousLivePositions) {
                updateStatus("Trade blocked: max simultaneous live positions reached ($openPositions/${settings.maxSimultaneousLivePositions}).", "WARN")
                return ExecutionAttemptResult(false)
            }
        }

        var useMarketOrder = settings.enableMarketOrders && settings.mode == BotMode.LIVE_AUTO && settings.exchangeProvider == ExchangeProvider.KRAKEN
        val spreadPct = if (ticker.lastPrice > BigDecimal.ZERO) ticker.ask.subtract(ticker.bid).divide(ticker.lastPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal.ZERO
        if (useMarketOrder) {
            updateStatus("[${ticker.symbol}] MARKET order requested. spread≈${spreadPct.setScale(3, RoundingMode.HALF_UP)}%, maxMarketOrder=€${settings.maxMarketOrderEur.setScale(2, RoundingMode.DOWN)}", "WARN")
            val marketBlockedReason = when {
                spreadPct > settings.marketOrderSlippageWarningPercent -> "spread/slippage risk too high: ${spreadPct.setScale(3, RoundingMode.HALF_UP)}% > ${settings.marketOrderSlippageWarningPercent}%"
                settings.marketOrderHighLiquidityOnly && ticker.volume24h < settings.autoSymbolMinVolume24hEur.multiply(BigDecimal("5")) -> "24h liquidity too low for market order: ${ticker.volume24h.setScale(0, RoundingMode.HALF_UP)} < ${settings.autoSymbolMinVolume24hEur.multiply(BigDecimal("5"))}"
                else -> null
            }
            if (marketBlockedReason != null) {
                if (settings.fallbackToLimitWhenMarketBlocked) {
                    updateStatus("Market order fallback: $marketBlockedReason. Using LIMIT instead.", "WARN")
                    useMarketOrder = false
                } else {
                    updateStatus("Trade blocked: $marketBlockedReason", "WARN")
                    return ExecutionAttemptResult(false)
                }
            }
        }
        val price = if (side == OrderSide.BUY) ticker.ask else ticker.bid
        val feeReserveMultiplier = BigDecimal("1.01")
        val minimumOrderNotional = BigDecimal("5.00")

        val perOrderCap = if (useMarketOrder) settings.maxPositionEur.min(settings.maxMarketOrderEur) else settings.maxPositionEur
        val targetNotional = if (side == OrderSide.BUY && settings.mode != BotMode.PAPER) {
            val freeQuote = availableQuote ?: BigDecimal.ZERO
            val spendableAfterReserve = freeQuote
                .subtract(quoteReserve)
                .subtract(quoteReservedThisScan)
                .max(BigDecimal.ZERO)
                .divide(feeReserveMultiplier, 8, RoundingMode.DOWN)
            when {
                quoteAsset in setOf("EUR", "USD", "USDT", "USDC") -> perOrderCap.min(spendableAfterReserve)
                settings.nonEurQuoteBuyEnabled -> {
                    val cryptoQuoteCap = freeQuote.multiply(settings.maxNonEurQuoteSpendPercent).divide(BigDecimal("100"), 8, RoundingMode.DOWN)
                    cryptoQuoteCap.min(spendableAfterReserve)
                }
                else -> BigDecimal.ZERO
            }
        } else {
            perOrderCap
        }

        if (side == OrderSide.BUY) {
            updateStatus("[${ticker.symbol}] Quote budget: base=$baseAsset, quote=$quoteAsset, freeQuote=${availableQuote?.stripTrailingZeros()?.toPlainString() ?: "unknown"}, reservedByBotThisScan=${quoteReservedThisScan.setScale(2, RoundingMode.DOWN)}, reserve=${quoteReserve.setScale(2, RoundingMode.DOWN)}, targetOrder=${targetNotional.stripTrailingZeros().toPlainString()} $quoteAsset", "INFO")
            val heldBaseValue = (availableBase ?: BigDecimal.ZERO).multiply(price)
            if (quoteAsset != "EUR" && quoteAsset !in setOf("USD", "USDT", "USDC") && !settings.nonEurQuoteBuyEnabled) {
                updateStatus("Trade blocked: ${ticker.symbol} uses quote asset $quoteAsset. The scanner analyzes it, but live BUY is disabled for non-fiat/non-stable quotes unless Non-EUR quote buys are enabled. SELL remains available when you hold $baseAsset.", "WARN")
                return ExecutionAttemptResult(false)
            }
            if (targetNotional < minimumOrderNotional) {
                if (heldBaseValue >= minimumOrderNotional) {
                    updateStatus("Trade blocked: BUY signal but free $quoteAsset is too low. You already have ${baseAsset}≈${heldBaseValue.setScale(2, RoundingMode.DOWN)} $quoteAsset available; the bot will wait for a SELL signal or you must add free $quoteAsset.", "WARN")
                } else {
                    updateStatus("Trade blocked: not enough free $quoteAsset to buy. API reports free $quoteAsset=${availableQuote?.stripTrailingZeros()?.toPlainString() ?: "unknown"}.", "WARN")
                }
                return ExecutionAttemptResult(false)
            }
        }

        val quantity = if (side == OrderSide.SELL && settings.mode != BotMode.PAPER) {
            val desiredQuantity = targetNotional.divide(price, 8, RoundingMode.DOWN)
            val freeBase = availableBase ?: BigDecimal.ZERO
            val chosen = desiredQuantity.min(freeBase).setScale(8, RoundingMode.DOWN)
            val chosenValue = chosen.multiply(price)
            updateStatus("[${ticker.symbol}] SELL budget: baseAsset=$baseAsset, quoteAsset=$quoteAsset, freeBase=${freeBase.stripTrailingZeros().toPlainString()}, targetQty=${desiredQuantity.stripTrailingZeros().toPlainString()}, chosenQty=${chosen.stripTrailingZeros().toPlainString()}, estimatedValue=${chosenValue.setScale(2, RoundingMode.DOWN)} $quoteAsset", "INFO")
            if (chosenValue < minimumOrderNotional) {
                updateStatus("Trade blocked: SELL signal but free $baseAsset value is below minimum. Value=${chosenValue.setScale(2, RoundingMode.DOWN)} $quoteAsset, minimum=$minimumOrderNotional $quoteAsset", "WARN")
                return ExecutionAttemptResult(false)
            }
            chosen
        } else {
            targetNotional.divide(price, 8, RoundingMode.DOWN)
        }
        if (quantity <= BigDecimal.ZERO) {
            updateStatus("Trade blocked: calculated quantity is zero.", "ERROR")
            return ExecutionAttemptResult(false)
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
        updateStatus("Submitting ${settings.exchangeProvider} ${request.side} $orderModeLabel order: ${request.symbol}, notional≈${targetNotional.setScale(2, RoundingMode.DOWN)} $quoteAsset, qty=${request.quantity}, price=${request.limitPrice ?: "market"}, id=${request.clientOrderId}", "LIVE")
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
        val reservedAmount = if (side == OrderSide.BUY) targetNotional.multiply(feeReserveMultiplier).setScale(2, RoundingMode.UP) else BigDecimal.ZERO
        return ExecutionAttemptResult(true, quoteAsset, reservedAmount)
    }

    private fun freeBalanceForAsset(balances: Map<String, BigDecimal>, asset: String): BigDecimal {
        val key = asset.uppercase()
        return balances[key] ?: when (key) {
            "EUR" -> balances["ZEUR"] ?: BigDecimal.ZERO
            "BTC", "XBT" -> balances["XXBT"] ?: balances["XBT"] ?: BigDecimal.ZERO
            "ETH" -> balances["XETH"] ?: BigDecimal.ZERO
            else -> BigDecimal.ZERO
        }
    }

    private fun quoteReserveAmount(settings: BotSettings, freeQuote: BigDecimal): BigDecimal {
        if (freeQuote <= BigDecimal.ZERO) return BigDecimal.ZERO
        val reserveByPercent = freeQuote.multiply(settings.minimumQuoteReservePercent).divide(BigDecimal("100"), 8, RoundingMode.DOWN)
        return settings.minimumQuoteReserveAmount.max(reserveByPercent).min(freeQuote)
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

    private fun quoteAssetFromSymbol(symbol: String): String {
        val upper = symbol.uppercase().replace("/", "").replace("-", "")
        val knownQuotes = listOf("USDT", "USDC", "EUR", "USD", "GBP", "CHF", "AUD", "CAD", "JPY", "BTC", "ETH")
        return knownQuotes.firstOrNull { upper.endsWith(it) && upper.length > it.length } ?: "EUR"
    }

    private fun createExchange(settings: BotSettings): CryptoExchangeClient {
        if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) return PaperExchangeClient(appContext)
        val apiKey = settingsStore.exchangeApiKey(settings.exchangeProvider).orEmpty()
        val secret = settingsStore.exchangeSecretKey(settings.exchangeProvider).orEmpty()
        return when (settings.exchangeProvider) {
            ExchangeProvider.PAPER -> PaperExchangeClient(appContext)
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


    suspend fun runLiveVerification(settings: BotSettings = settingsStore.load()): List<LiveVerificationResult> {
        updateStatus("Live verification started. Provider=${settings.exchangeProvider}", "INFO")
        val exchange = createExchange(settings)
        val results = liveVerificationEngine.run(settings, exchange)
        results.forEach { result ->
            updateStatus("Verification ${if (result.passed) "PASS" else "FAIL"}: ${result.name} — ${result.detail}", if (result.passed) "INFO" else "ERROR")
        }
        val passed = results.count { it.passed }
        updateStatus("Live verification complete: $passed/${results.size} checks passed.", if (passed == results.size) "LIVE" else "WARN")
        return results
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
        val warning = if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) {
            "Paper portfolio loaded. These balances are simulated locally and no real exchange order is sent."
        } else if (freeEur < BigDecimal("5.00") && total >= BigDecimal("5.00")) {
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
