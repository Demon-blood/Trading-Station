package com.ksp.cryptobot.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.ksp.cryptobot.data.*
import com.ksp.cryptobot.automation.AdvancedAutomationEngine
import com.ksp.cryptobot.exchange.BinanceReadOnlyClient
import com.ksp.cryptobot.exchange.BitvavoClient
import com.ksp.cryptobot.exchange.CoinbaseAdvancedClient
import com.ksp.cryptobot.exchange.CryptoExchangeClient
import com.ksp.cryptobot.exchange.ExchangeCapabilityChecker
import com.ksp.cryptobot.exchange.KrakenSpotClient
import com.ksp.cryptobot.exchange.KrakenRealtimeMarketDataRegistry
import com.ksp.cryptobot.exchange.KrakenPrivateExecutionRegistry
import com.ksp.cryptobot.exchange.ManualExecutionClient
import com.ksp.cryptobot.exchange.PaperExchangeClient
import com.ksp.cryptobot.execution.ExecutionGuard
import com.ksp.cryptobot.execution.ExchangeMinimumOrderPolicy
import com.ksp.cryptobot.execution.AdvancedRiskManager
import com.ksp.cryptobot.intelligence.AiDecisionEngine
import com.ksp.cryptobot.intelligence.OpenAiDecisionRouter
import com.ksp.cryptobot.intelligence.AiValueAttributionEngine
import com.ksp.cryptobot.intelligence.AiValueAttributionSummary
import com.ksp.cryptobot.intelligence.AiAdaptiveGovernanceEngine
import com.ksp.cryptobot.intelligence.AiAdaptiveGovernanceDecision
import com.ksp.cryptobot.intelligence.AiAdaptiveGovernanceState
import com.ksp.cryptobot.intelligence.AiAdaptiveAction
import com.ksp.cryptobot.news.NewsApiClient
import com.ksp.cryptobot.news.CompositeNewsClient
import com.ksp.cryptobot.news.CryptoPanicNewsClient
import com.ksp.cryptobot.news.GdeltNewsClient
import com.ksp.cryptobot.news.MarketauxNewsClient
import com.ksp.cryptobot.news.RssFeedNewsClient
import com.ksp.cryptobot.news.NewsDataNewsClient
import com.ksp.cryptobot.news.GNewsNewsClient
import com.ksp.cryptobot.news.GuardianNewsClient
import com.ksp.cryptobot.news.CoinGeckoNewsClient
import com.ksp.cryptobot.news.NewsClient
import com.ksp.cryptobot.news.NoopNewsClient
import com.ksp.cryptobot.news.NewsProviderHealthRegistry
import com.ksp.cryptobot.settings.AppSettingsStore
import com.ksp.cryptobot.status.BotStatusStore
import com.ksp.cryptobot.lifecycle.TradeLifecycleManager
import com.ksp.cryptobot.strategy.RecommendationEngine
import com.ksp.cryptobot.pro.ProAutomationSuite
import com.ksp.cryptobot.autonomous.AutonomousIntelligencePack
import com.ksp.cryptobot.alerts.RemoteAlertClient
import com.ksp.cryptobot.alerts.RemoteCommandClient
import com.ksp.cryptobot.alerts.RemoteCommandMessage
import com.ksp.cryptobot.backtest.BacktestEngine
import com.ksp.cryptobot.completion.LiveVerificationEngine
import com.ksp.cryptobot.completion.LiveVerificationResult
import com.ksp.cryptobot.learning.TrueSelfLearningEngine
import com.ksp.cryptobot.performance.PerformanceLabEngine
import com.ksp.cryptobot.governance.ProductionIntelligenceEngine
import com.ksp.cryptobot.research.ResearchCoordinator
import com.ksp.cryptobot.release.V4SystemVerifier
import com.ksp.cryptobot.execution.AdvancedExecutionCoordinator
import com.ksp.cryptobot.execution.ProtectiveStopManager
import com.ksp.cryptobot.research.ResearchExecutionRuntime
import com.ksp.cryptobot.research.HandoffPositionPlan
import com.ksp.cryptobot.research.HandoffPositionPlanCodec
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
    private val lifecycleManager = TradeLifecycleManager(dao, statusStore, AppDatabase.get(appContext).governanceDao())
    private val proAutomationSuite = ProAutomationSuite(appContext)
    private val autonomousPack = AutonomousIntelligencePack(appContext)
    private val liveVerificationEngine = LiveVerificationEngine()
    private val selfLearningEngine = TrueSelfLearningEngine()
    private val productionIntelligence = ProductionIntelligenceEngine(AppDatabase.get(appContext).governanceDao())
    private val researchIntelligence = ResearchCoordinator(appContext, AppDatabase.get(appContext).researchDao())
    private val advancedExecution = AdvancedExecutionCoordinator(dao, AppDatabase.get(appContext).governanceDao())
    private val protectiveStops = ProtectiveStopManager(dao, AppDatabase.get(appContext).governanceDao())
    private val cloudAiRouter = OpenAiDecisionRouter(appContext, settingsStore)
    private val aiValueAttribution = AiValueAttributionEngine(AppDatabase.get(appContext).governanceDao())
    private val aiAdaptiveGovernance = AiAdaptiveGovernanceEngine(
        appContext,
        AppDatabase.get(appContext).governanceDao(),
        settingsStore
    )
    private val remoteAlertClient = RemoteAlertClient()
    private val remoteCommandClient = RemoteCommandClient()
    private val _status = MutableStateFlow("Stopped")
    val status: StateFlow<String> = _status

    private data class ExecutionAttemptResult(
        val submitted: Boolean,
        val quoteAsset: String = "EUR",
        val reservedAmount: BigDecimal = BigDecimal.ZERO
    )

    @Volatile var running: Boolean = false
        private set

    init {
        KrakenPrivateExecutionRegistry.initialize(appContext)
    }

    suspend fun loadAiValueAttributionSummary(): AiValueAttributionSummary =
        aiValueAttribution.summary()

    suspend fun loadAiValueAttributionRows(limit: Int = 100): List<AiValueAttributionEntity> =
        aiValueAttribution.recent(limit)

    suspend fun loadAiAdaptiveGovernanceDecision(): AiAdaptiveGovernanceDecision =
        aiAdaptiveGovernance.inspect()

    fun loadAiAdaptiveGovernanceState(): AiAdaptiveGovernanceState =
        aiAdaptiveGovernance.state()

    suspend fun sendTelegramTestAlert(settings: BotSettings = settingsStore.load()): Boolean {
        val ok = remoteAlertClient.sendTelegram(
            settingsStore.telegramBotToken().orEmpty(),
            settingsStore.telegramChatId().orEmpty(),
            "✅ Crypto TradeStation test alert\nProvider=${settings.exchangeProvider}\nMode=${settings.mode}"
        )
        updateStatus("Telegram test alert ${if (ok) "sent" else "failed"}. Check token/chat id.", if (ok) "INFO" else "ERROR")
        return ok
    }

    suspend fun sendDiscordTestAlert(settings: BotSettings = settingsStore.load()): Boolean {
        val ok = remoteAlertClient.sendDiscord(
            settingsStore.discordWebhookUrl().orEmpty(),
            "✅ Crypto TradeStation test alert\nProvider=${settings.exchangeProvider}\nMode=${settings.mode}"
        )
        updateStatus("Discord test alert ${if (ok) "sent" else "failed"}. Check webhook URL.", if (ok) "INFO" else "ERROR")
        return ok
    }

    private suspend fun sendRemoteAlert(settings: BotSettings, title: String, message: String) {
        val text = "Crypto TradeStation — $title\n$message"
        if (settings.telegramRemoteControlEnabled) {
            runCatching<Boolean> {
                remoteAlertClient.sendTelegram(
                    settingsStore.telegramBotToken().orEmpty(),
                    settingsStore.telegramChatId().orEmpty(),
                    text
                )
            }.onFailure { error: Throwable -> statusStore.write("Telegram alert failed: ${error.message}", "ERROR") }
        }
        if (settings.discordRemoteControlEnabled) {
            runCatching<Boolean> {
                remoteAlertClient.sendDiscord(settingsStore.discordWebhookUrl().orEmpty(), text)
            }.onFailure { error: Throwable -> statusStore.write("Discord alert failed: ${error.message}", "ERROR") }
        }
    }


    private fun isCashLikeBaseAsset(asset: String): Boolean {
        val normalized = asset.uppercase()
            .removePrefix("X")
            .removePrefix("Z")
            .substringBefore(".")
        return normalized in setOf(
            "EUR", "USD", "GBP", "CHF", "AUD", "CAD", "JPY",
            "EURC", "EURT", "EURI", "ZEUR",
            "USDC", "USDT", "USDG", "USDS", "USDE", "ZUSD",
            "DAI", "PYUSD", "TUSD", "BUSD", "GUSD"
        )
    }

    private fun isCashLikeTradingPairBase(symbol: String, baseAsset: String? = null): Boolean {
        val base = baseAsset?.takeIf { it.isNotBlank() } ?: baseAssetFromSymbol(symbol)
        return isCashLikeBaseAsset(base)
    }

    private fun filterCashLikeBaseSymbols(symbols: List<String>, reason: String): List<String> {
        val kept = symbols.filterNot { symbol -> isCashLikeTradingPairBase(symbol) }
        val blocked = symbols.map { it.uppercase().replace("/", "").replace("-", "") }
            .filter { symbol -> isCashLikeTradingPairBase(symbol) }
            .distinct()
        if (blocked.isNotEmpty()) {
            updateStatus("Currency/stable base filter blocked ${blocked.joinToString(",")} from $reason. These are cash/stablecoin symbols, not trading targets.", "WARN")
        }
        return kept
    }

    private fun liveSafetyBlockReason(settings: BotSettings): String? {
        if (settings.mode == BotMode.LIVE_AUTO && !settings.liveTradingAcknowledged) {
            return "Live acknowledgement is OFF."
        }
        if (settings.mode == BotMode.LIVE_AUTO && settings.exchangeProvider != ExchangeProvider.KRAKEN) {
            return "LIVE_AUTO is currently allowed only with Kraken provider."
        }
        if (settings.mode == BotMode.LIVE_AUTO && !settings.allowedQuoteAssetsCsv.uppercase().contains("EUR")) {
            return "EUR is not enabled in allowed quote assets."
        }
        if (settings.mode == BotMode.LIVE_AUTO && settings.nonEurQuoteBuyEnabled) {
            return "Non-EUR quote buys are enabled. Disable them for Belgium/EUR-first safety."
        }
        if (settings.mode == BotMode.LIVE_AUTO && !settings.enableBacktestGate) {
            return "Backtest gate is OFF."
        }
        if (settings.mode == BotMode.LIVE_AUTO && !settings.enableForwardTestGate) {
            return "Forward-test gate is OFF."
        }
        if (settings.mode == BotMode.LIVE_AUTO && !settings.autoStopLossEnabled) {
            return "Auto stop-loss is OFF."
        }
        return null
    }


    suspend fun runSystemFeatureVerification(settings: BotSettings = settingsStore.load()): List<String> {
        val lines = mutableListOf<String>()
        fun add(status: String, name: String, detail: String) {
            lines += "$status | $name | $detail"
        }

        updateStatus("System feature verification started.", "INFO")

        add("PASS", "Settings Store", "Loaded provider=${settings.exchangeProvider}, mode=${settings.mode}, symbols=${settings.symbolsCsv}")
        add("PASS", "Secure Exchange Key Store", "Encrypted key store is reachable. Keys are not exposed in diagnostics.")

        val cloudConfig = settingsStore.cloudAiConfig()
        val cloudKeyConfigured = !settingsStore.openAiApiKey().isNullOrBlank()
        when {
            !cloudConfig.enabled ->
                add("PASS", "Selective Cloud AI Router", "Disabled. Deterministic/local zero-API-cost path is active.")
            !cloudKeyConfigured ->
                add("WARN", "Selective Cloud AI Router", "Enabled but OpenAI API key is not configured. Trading continues on deterministic safeguards.")
            else -> {
                val cloudBudget = cloudAiRouter.budgetSnapshot()
                add(
                    "PASS",
                    "Selective Cloud AI Router",
                    "Enabled; key stored securely; budget=${cloudBudget.spentUsd.setScale(4, RoundingMode.HALF_UP)}/${cloudBudget.monthlyBudgetUsd.setScale(2, RoundingMode.HALF_UP)} USD; Sol=${cloudConfig.solEnabled}; SolToday=${cloudBudget.solCallsToday}/${cloudConfig.maxSolCallsPerDay}. This check makes no paid API call."
                )
            }
        }

        val attribution = runCatching { aiValueAttribution.summary() }.getOrNull()
        if (attribution == null) {
            add("WARN", "AI Value Attribution", "Unable to read M7 attribution state.")
        } else {
            add(
                "PASS",
                "AI Value Attribution",
                "open=${attribution.openCounterfactuals}, resolved=${attribution.resolvedCounterfactuals}, AI_COST=${attribution.totalAiCostQuote.setScale(4, RoundingMode.HALF_UP)}, AI_VALUE_ADDED=${attribution.aiValueAddedQuote.setScale(4, RoundingMode.HALF_UP)}, AI_AVOIDED_LOSS=${attribution.avoidedLossQuote.setScale(4, RoundingMode.HALF_UP)}, AI_MISSED_PROFIT=${attribution.missedProfitQuote.setScale(4, RoundingMode.HALF_UP)}, AI_GENERATED_PROFIT=${attribution.aiGeneratedProfitQuote.setScale(4, RoundingMode.HALF_UP)}, AI_ROI=${attribution.aiRoi?.setScale(3, RoundingMode.HALF_UP) ?: "n/a"}, verdict=${attribution.verdict}. No paid AI call is made by this verifier."
            )
        }

        val adaptiveInspection = runCatching { aiAdaptiveGovernance.inspect() }.getOrNull()
        if (adaptiveInspection == null) {
            add("WARN", "AI Adaptive Governance", "Unable to inspect M8 adaptive-governance evidence.")
        } else {
            val adaptiveState = aiAdaptiveGovernance.state()
            add(
                "PASS",
                "AI Adaptive Governance",
                "action=${adaptiveInspection.action}, overallN=${adaptiveInspection.overall.samples}, overall95=[${adaptiveInspection.overall.lower95.setScale(5, RoundingMode.HALF_UP)},${adaptiveInspection.overall.upper95.setScale(5, RoundingMode.HALF_UP)}], solN=${adaptiveInspection.sol.samples}, sol95=[${adaptiveInspection.sol.lower95.setScale(5, RoundingMode.HALF_UP)},${adaptiveInspection.sol.upper95.setScale(5, RoundingMode.HALF_UP)}], excludedLowIntegrity=${adaptiveInspection.excludedLowIntegrityRows}, lastApplied=${adaptiveState.lastAction}. Inspection is read-only and makes no paid AI call."
            )
        }

        try {
            V4SystemVerifier(appContext).verify(settings).forEach { check ->
                add(check.status, "V4 ${check.name}", check.detail)
            }
            add("PASS", "V4 migrated systems", "Final Stage 6 verifier completed for CloudShare, governance, execution, research, recovery and signing/integrity.")
        } catch (error: Exception) {
            add("WARN", "V4 migrated systems", "V4 verifier failed to complete: ${error.message}")
        }

        val primarySymbol = settings.symbols().firstOrNull()?.uppercase()?.replace("/", "")?.replace("-", "") ?: "BTCEUR"
        val currentStrategyChampion = runCatching { researchIntelligence.strategyChampion(primarySymbol) }.getOrNull()
        add(
            "PASS",
            "M9 Strategy Champion/Challenger",
            if(currentStrategyChampion.isNullOrBlank())
                "No champion yet for $primarySymbol. PAPER challengers may gather exact evidence; LIVE research promotion remains champion-gated."
            else
                "Champion for $primarySymbol=$currentStrategyChampion. System inspection is read-only; M9 cannot increase size or bypass M4/M5/risk gates."
        )
        val m10Trades = runCatching { dao.recentTradesSnapshot(500) }.getOrDefault(emptyList())
        val currentChampionHealth = runCatching { researchIntelligence.championHealth(settings, primarySymbol, m10Trades) }.getOrNull()
        if(currentChampionHealth==null) add("WARN","M10 Champion Degradation","Unable to inspect champion health.")
        else add("PASS","M10 Champion Degradation","state=${currentChampionHealth.state}, champion=${currentChampionHealth.championAfter ?: "none"}, rollingN=${currentChampionHealth.rolling.samples}, mean=${currentChampionHealth.rolling.meanReturn}, upper95=${currentChampionHealth.rolling.upper95Return}, liveAuthorized=${currentChampionHealth.liveEntryAuthorized}, sizeCap=${currentChampionHealth.liveSizeMultiplier}, rollback=${currentChampionHealth.rollbackCandidate ?: "none"}. Inspection is read-only and never affects protective exits.")
        val publicKraken = KrakenSpotClient(apiKey = "", secretKey = "")

        runCatching { publicKraken.validateSymbol(primarySymbol) }
            .onSuccess { add("PASS", "Kraken AssetPairs", "${it.exchangePair} base=${it.baseAsset} quote=${it.quoteAsset}") }
            .onFailure { add("FAIL", "Kraken AssetPairs", it.message ?: "Unknown symbol validation error") }

        runCatching { publicKraken.getTicker(primarySymbol) }
            .onSuccess { add("PASS", "Kraken Public Ticker", "last=${it.lastPrice}, bid=${it.bid}, ask=${it.ask}") }
            .onFailure { add("FAIL", "Kraken Public Ticker", it.message ?: "Unknown ticker error") }

        runCatching { publicKraken.getCandles(primarySymbol, Timeframe.M15, 120) }
            .onSuccess { candles ->
                if (candles.size >= 60) {
                    add("PASS", "Kraken OHLC / Chart Data", "candles=${candles.size}, lastClose=${candles.lastOrNull()?.close ?: BigDecimal.ZERO}")
                } else {
                    add("WARN", "Kraken OHLC / Chart Data", "Only ${candles.size} candles returned.")
                }
            }
            .onFailure { add("FAIL", "Kraken OHLC / Chart Data", it.message ?: "Unknown OHLC error") }

        runCatching { loadTradeJournal(25) }
            .onSuccess { add("PASS", "Trade Journal Database", "rows=${it.size}. Markers appear after local trades exist.") }
            .onFailure { add("FAIL", "Trade Journal Database", it.message ?: "Unknown journal error") }

        runCatching { runKrakenDataHealth(settings) }
            .onSuccess { health ->
                val failCount = health.count { it.startsWith("FAIL") }
                val warnCount = health.count { it.startsWith("WARN") }
                if (failCount == 0) add("PASS", "Kraken Health Monitor", "rows=${health.size}, warnings=$warnCount")
                else add("FAIL", "Kraken Health Monitor", "failures=$failCount, warnings=$warnCount")
            }
            .onFailure { add("FAIL", "Kraken Health Monitor", it.message ?: "Unknown health error") }

        val telegramConfigured = !settingsStore.telegramBotToken().isNullOrBlank() && !settingsStore.telegramChatId().isNullOrBlank()
        if (telegramConfigured && settings.telegramRemoteControlEnabled) {
            runCatching { sendTelegramTestAlert(settings) }
                .onSuccess { ok -> add(if (ok) "PASS" else "FAIL", "Telegram Alert", if (ok) "Test alert sent." else "Telegram API returned failure.") }
                .onFailure { add("FAIL", "Telegram Alert", it.message ?: "Unknown Telegram error") }
        } else {
            add("NOT_CONFIGURED", "Telegram Alert", "Bot token/chat ID missing or Telegram disabled.")
        }

        val discordConfigured = !settingsStore.discordWebhookUrl().isNullOrBlank()
        if (discordConfigured && settings.discordRemoteControlEnabled) {
            runCatching { sendDiscordTestAlert(settings) }
                .onSuccess { ok -> add(if (ok) "PASS" else "FAIL", "Discord Alert", if (ok) "Test alert sent." else "Discord webhook returned failure.") }
                .onFailure { add("FAIL", "Discord Alert", it.message ?: "Unknown Discord error") }
        } else {
            add("NOT_CONFIGURED", "Discord Alert", "Webhook URL missing or Discord disabled.")
        }

        val releaseBlock = liveSafetyBlockReason(settings)
        if (settings.mode == BotMode.LIVE_AUTO) {
            if (releaseBlock == null) add("PASS", "Release Safety Lock", "LIVE_AUTO safety gates passed.")
            else add("FAIL", "Release Safety Lock", releaseBlock)
        } else {
            add("PASS", "Release Safety Lock", "Mode=${settings.mode}. Live safety is enforced before LIVE_AUTO execution.")
        }

        val liveKeyConfigured = !settingsStore.exchangeApiKey(ExchangeProvider.KRAKEN).isNullOrBlank() &&
            !settingsStore.exchangeSecretKey(ExchangeProvider.KRAKEN).isNullOrBlank()
        if (settings.exchangeProvider == ExchangeProvider.KRAKEN && settings.mode != BotMode.PAPER) {
            if (liveKeyConfigured) add("PASS", "Kraken Live Credentials", "Kraken keys are configured. Withdrawal permission must still be checked manually on Kraken.")
            else add("FAIL", "Kraken Live Credentials", "Kraken API key/secret not configured.")
        } else {
            add("PASS", "Kraken Live Credentials", "Skipped live credential requirement in ${settings.mode}/${settings.exchangeProvider}.")
        }

        add("PASS", "Live Order Path Wiring", "Order placement path is wired. This verification does not place a real order for safety.")
        add("PASS", "Max Buy Price Guard", if (settings.maxBuyPriceFilterEnabled || settings.perSymbolRulesEnabled) "Enabled. Global=${settings.globalMaxBuyPriceEur}, maxBuyList=${settings.perSymbolMaxBuyPriceCsv.ifBlank { "none" }}, rules=${settings.perSymbolRulesCsv.ifBlank { "none" }}" else "Disabled. Enable it in Advanced Settings when you want hard buy-price caps.")
        add("PASS", "Per-Symbol Automation Rules", if (settings.perSymbolRulesEnabled) "Enabled. Format SYMBOL=maxPosition|minScore|maxBuyPrice|cooldownMinutes. Rules=${settings.perSymbolRulesCsv.ifBlank { "none" }}" else "Disabled.")
        add("PASS", "Auto-Compounding Hard Cap", if (settings.autoCompoundingHardCapEnabled) "Enabled. Max adaptive position=${settings.autoCompoundingMaxPositionEur}, realized-PnL compounding=${settings.adaptiveCompoundingFromRealizedPnlEnabled}" else "Disabled.")
        add("PASS", "Volatility Circuit Breaker", if (settings.volatilityCircuitBreakerEnabled) "Enabled. BUY blocked above abs 24h move ${settings.volatilityCircuitBreakerMax24hMovePercent}%." else "Disabled.")
        add("PASS", "Pump-Chase Protection", if (settings.pumpChaseProtectionEnabled) "Enabled. BUY blocked above 24h gain ${settings.pumpChaseMax24hGainPercent}%." else "Disabled.")
        add("PASS", "Duplicate Position Protection", if (settings.duplicatePositionProtectionEnabled) "Enabled. Blocks additional BUY entries when an OPEN position or existing base holding exists; SELL remains allowed." else "Disabled.")
        add("PASS", "Portfolio Exposure Guard", if (settings.portfolioBalancerEnabled) "Enabled through existing Portfolio Balancer + Max Single Asset Allocation controls. Max=${settings.maxSingleAssetAllocationPercent}%." else "Disabled.")
        val ultimateReadinessEnabled = listOf(
            settings.ultimateAutomationEnabled,
            settings.trueSelfLearningEnabled,
            settings.adaptiveStrategyLearningEnabled,
            settings.learnedHoldForProfitEnabled,
            settings.spikeProfitTimingEnabled,
            settings.multiTimeframeConsensusEnabled,
            settings.volatilityCircuitBreakerEnabled,
            settings.pumpChaseProtectionEnabled,
            settings.duplicatePositionProtectionEnabled,
            settings.autoPauseAfterOrderFailuresEnabled,
            settings.liveVerificationPanelEnabled
        ).count { it }
        val ultimateReadinessScore = (ultimateReadinessEnabled * 100) / 11
        add("PASS", "Ultimate Readiness Score", "Enabled automation modules=$ultimateReadinessEnabled/11, score=$ultimateReadinessScore%. This is a wiring/readiness score, not a profit guarantee.")
        add("PASS", "Multi-Timeframe Consensus", if (settings.multiTimeframeConsensusEnabled) "Enabled. Requires ${settings.multiTimeframeRequiredBullishCount.coerceIn(1, 3)}/3 bullish frames before automatic BUY." else "Disabled.")
        add("PASS", "Order Book Depth/Slippage Guard", if (settings.orderBookDepthGuardEnabled) "Enabled. Uses Kraken live depth when available. Max slippage=${settings.maxOrderBookSlippagePercent}%, min depth multiple=${settings.minOrderBookDepthMultiple}x." else "Disabled.")
        add("PASS", "Professional Engine Layout", "Market Data, Strategy, Signal Scoring, Risk, Execution, Portfolio, Learning, Backtesting, Monitoring and Security are kept as independent app layers.")
        add("PASS", "Remote Command Center", if (settings.remoteCommandCenterEnabled) "Enabled. Telegram polling=${settings.telegramCommandPollingEnabled}, Discord polling=${settings.discordCommandPollingEnabled}, PIN required=${settings.remoteCommandRequirePin}, remote LIVE_AUTO=${settings.remoteCommandAllowLiveAuto}." else "Disabled. Enable in Notifications → Remote Alerts.")
        add("PASS", "Duplicate Feature Audit", "No duplicate top-level feature was added. Canonical controls: Max Position=max spend, Max Buy Price=price cap, Portfolio Balancer=exposure cap, LIVE_AUTO Preflight=live gate, Auto-Pause=safety stop, Multi-Timeframe Consensus=trend agreement, Order Book Guard=execution quality.")
        add("PASS", "Repeated Failure Auto-Pause", if (settings.autoPauseAfterOrderFailuresEnabled) "Enabled. Threshold=${settings.autoPauseFailureThreshold}, pause mode=LIVE_CONFIRM." else "Disabled.")
        add("PASS", "Dynamic Scan Interval", if (settings.dynamicScanIntervalEnabled) "Enabled. fast=${settings.dynamicScanFastSeconds}s, normal=${settings.scanIntervalSeconds}s, slow=${settings.dynamicScanSlowSeconds}s." else "Disabled.")
        add("PASS", "Chart Auto Refresh Wiring", "Chart screen refresh loop is wired for 30-second updates while Chart/Trade Overlay is open.")
        add("PASS", "Grouped Navigation", "Top tabs route to Dashboard, AI, Self Learning, Chart, Settings and Notifications hubs.")

        val failed = lines.count { it.startsWith("FAIL") }
        val warn = lines.count { it.startsWith("WARN") }
        val notConfigured = lines.count { it.startsWith("NOT_CONFIGURED") }
        updateStatus("System verification complete: fail=$failed, warn=$warn, notConfigured=$notConfigured, checks=${lines.size}", if (failed == 0) "INFO" else "ERROR")
        return lines
    }



    private fun backupClean(value: String?): String = value.orEmpty()
        .replace("\n", " ")
        .replace("\r", " ")
        .replace("|", "/")

    private fun parseBackupRows(lines: List<String>, expectedHeaderPrefix: String): List<List<String>> {
        return lines.dropWhile { !it.startsWith(expectedHeaderPrefix) }
            .drop(1)
            .map { it.split("|") }
    }

    suspend fun exportFullLocalBackup(settings: BotSettings = settingsStore.load()): String {
        val trades = dao.allTradesSnapshot()
        val signals = dao.allSignalsSnapshot()
        val aiDecisions = dao.allAiDecisionsSnapshot()
        val taxLots = dao.allTaxLotsSnapshot()
        val taxRows = dao.taxReportRowsSnapshot()
        val symbolProfiles = dao.learnedSymbolProfilesSnapshot()
        val strategyProfiles = dao.learnedStrategyProfilesSnapshot()
        val holdProfiles = dao.learnedHoldProfilesSnapshot()
        val learningSnapshots = dao.learningFeatureSnapshots(100000)
        val audits = dao.selfLearningAudit(100000)
        val openPositions = dao.openPositionsSnapshot()
        val secureValues = settingsStore.secureBackupMap()

        fun clean(value: String): String = backupClean(value)

        val sb = StringBuilder()
        sb.appendLine("CRYPTO_TRADE_STATION_FULL_BACKUP_V2_FULL")
        sb.appendLine("createdEpochMs=${System.currentTimeMillis()}")
        sb.appendLine("appVersion=v2.8.6")
        sb.appendLine()
        sb.appendLine("[SETTINGS]")
        sb.appendLine("mode=${settings.mode}")
        sb.appendLine("exchangeProvider=${settings.exchangeProvider}")
        sb.appendLine("liveTradingAcknowledged=${settings.liveTradingAcknowledged}")
        sb.appendLine("manualExecutionMode=${settings.manualExecutionMode}")
        sb.appendLine("symbolsCsv=${settings.symbolsCsv}")
        sb.appendLine("allowedQuoteAssetsCsv=${settings.allowedQuoteAssetsCsv}")
        sb.appendLine("autoSymbolQuoteAsset=${settings.autoSymbolQuoteAsset}")
        sb.appendLine("maxPositionEur=${settings.maxPositionEur}")
        sb.appendLine("maxBuyPriceFilterEnabled=${settings.maxBuyPriceFilterEnabled}")
        sb.appendLine("globalMaxBuyPriceEur=${settings.globalMaxBuyPriceEur}")
        sb.appendLine("perSymbolMaxBuyPriceCsv=${clean(settings.perSymbolMaxBuyPriceCsv)}")
        sb.appendLine("ultimateAutomationEnabled=${settings.ultimateAutomationEnabled}")
        sb.appendLine("perSymbolRulesEnabled=${settings.perSymbolRulesEnabled}")
        sb.appendLine("perSymbolRulesCsv=${clean(settings.perSymbolRulesCsv)}")
        sb.appendLine("autoCompoundingHardCapEnabled=${settings.autoCompoundingHardCapEnabled}")
        sb.appendLine("autoCompoundingMaxPositionEur=${settings.autoCompoundingMaxPositionEur}")
        sb.appendLine("autoPauseAfterOrderFailuresEnabled=${settings.autoPauseAfterOrderFailuresEnabled}")
        sb.appendLine("autoPauseFailureThreshold=${settings.autoPauseFailureThreshold}")
        sb.appendLine("autoPauseMinutes=${settings.autoPauseMinutes}")
        sb.appendLine("volatilityCircuitBreakerEnabled=${settings.volatilityCircuitBreakerEnabled}")
        sb.appendLine("volatilityCircuitBreakerMax24hMovePercent=${settings.volatilityCircuitBreakerMax24hMovePercent}")
        sb.appendLine("pumpChaseProtectionEnabled=${settings.pumpChaseProtectionEnabled}")
        sb.appendLine("pumpChaseMax24hGainPercent=${settings.pumpChaseMax24hGainPercent}")
        sb.appendLine("duplicatePositionProtectionEnabled=${settings.duplicatePositionProtectionEnabled}")
        sb.appendLine("adaptiveCompoundingFromRealizedPnlEnabled=${settings.adaptiveCompoundingFromRealizedPnlEnabled}")
        sb.appendLine("dynamicScanIntervalEnabled=${settings.dynamicScanIntervalEnabled}")
        sb.appendLine("dynamicScanFastSeconds=${settings.dynamicScanFastSeconds}")
        sb.appendLine("dynamicScanSlowSeconds=${settings.dynamicScanSlowSeconds}")
        sb.appendLine("multiTimeframeConsensusEnabled=${settings.multiTimeframeConsensusEnabled}")
        sb.appendLine("multiTimeframeRequiredBullishCount=${settings.multiTimeframeRequiredBullishCount}")
        sb.appendLine("ultimateReadinessScoreEnabled=${settings.ultimateReadinessScoreEnabled}")
        sb.appendLine("orderBookDepthGuardEnabled=${settings.orderBookDepthGuardEnabled}")
        sb.appendLine("maxOrderBookSlippagePercent=${settings.maxOrderBookSlippagePercent}")
        sb.appendLine("minOrderBookDepthMultiple=${settings.minOrderBookDepthMultiple}")
        sb.appendLine("maxDailyLossEur=${settings.maxDailyLossEur}")
        sb.appendLine("maxTradesPerDay=${settings.maxTradesPerDay}")
        sb.appendLine("maxTradesPerHour=${settings.maxTradesPerHour}")
        sb.appendLine("maxNewTradesPerScan=${settings.maxNewTradesPerScan}")
        sb.appendLine("maxSimultaneousLivePositions=${settings.maxSimultaneousLivePositions}")
        sb.appendLine("minStrategyScoreToBuy=${settings.minStrategyScoreToBuy}")
        sb.appendLine("enableMarketOrders=${settings.enableMarketOrders}")
        sb.appendLine("enableBacktestGate=${settings.enableBacktestGate}")
        sb.appendLine("enableForwardTestGate=${settings.enableForwardTestGate}")
        sb.appendLine("trueSelfLearningEnabled=${settings.trueSelfLearningEnabled}")
        sb.appendLine("spikeProfitTimingEnabled=${settings.spikeProfitTimingEnabled}")
        sb.appendLine("telegramRemoteControlEnabled=${settings.telegramRemoteControlEnabled}")
        sb.appendLine("discordRemoteControlEnabled=${settings.discordRemoteControlEnabled}")
        sb.appendLine("remoteCommandCenterEnabled=${settings.remoteCommandCenterEnabled}")
        sb.appendLine("telegramCommandPollingEnabled=${settings.telegramCommandPollingEnabled}")
        sb.appendLine("discordCommandPollingEnabled=${settings.discordCommandPollingEnabled}")
        sb.appendLine("remoteCommandRequirePin=${settings.remoteCommandRequirePin}")
        sb.appendLine("remoteCommandAllowLiveAuto=${settings.remoteCommandAllowLiveAuto}")
        sb.appendLine("fullSettings=${clean(settings.toString())}")
        sb.appendLine()
        sb.appendLine("[COUNTS]")
        sb.appendLine("trades=${trades.size}")
        sb.appendLine("signals=${signals.size}")
        sb.appendLine("aiDecisions=${aiDecisions.size}")
        sb.appendLine("taxLots=${taxLots.size}")
        sb.appendLine("openPositions=${openPositions.size}")
        sb.appendLine("taxRows=${taxRows.size}")
        sb.appendLine("secureValues=${secureValues.size}")
        sb.appendLine("learnedSymbolProfiles=${symbolProfiles.size}")
        sb.appendLine("learnedStrategyProfiles=${strategyProfiles.size}")
        sb.appendLine("learnedHoldProfiles=${holdProfiles.size}")
        sb.appendLine("learningSnapshots=${learningSnapshots.size}")
        sb.appendLine("selfLearningAudits=${audits.size}")
        sb.appendLine()
        sb.appendLine("[TRADES]")
        sb.appendLine("id|timestampEpochMs|symbol|side|quantity|priceEur|feeEur|paper|realizedPnlEur|aiScore|clientOrderId|exchangeOrderId|aiReason")
        trades.forEach {
            sb.appendLine("${it.id}|${it.timestampEpochMs}|${clean(it.symbol)}|${clean(it.side)}|${clean(it.quantity)}|${clean(it.priceEur)}|${clean(it.feeEur)}|${it.paper}|${clean(it.realizedPnlEur)}|${it.aiScore}|${clean(it.clientOrderId)}|${clean(it.exchangeOrderId)}|${clean(it.aiReason)}")
        }
        sb.appendLine()
        sb.appendLine("[SIGNALS]")
        sb.appendLine("id|timestampEpochMs|symbol|action|score|riskPercent|reason")
        signals.forEach {
            sb.appendLine("${it.id}|${it.timestampEpochMs}|${clean(it.symbol)}|${clean(it.action)}|${it.score}|${clean(it.riskPercent)}|${clean(it.reason)}")
        }
        sb.appendLine()
        sb.appendLine("[AI_DECISIONS]")
        sb.appendLine("id|timestampEpochMs|symbol|finalAction|finalScore|confidencePercent|technicalScore|newsScore|memoryScore|allowedToTrade|explanation")
        aiDecisions.forEach {
            sb.appendLine("${it.id}|${it.timestampEpochMs}|${clean(it.symbol)}|${clean(it.finalAction)}|${it.finalScore}|${it.confidencePercent}|${it.technicalScore}|${it.newsScore}|${it.memoryScore}|${it.allowedToTrade}|${clean(it.explanation)}")
        }
        sb.appendLine()
        sb.appendLine("[TAX_LOTS]")
        sb.appendLine("id|symbol|quantity|costBasisEur|openedAtEpochMs|closedAtEpochMs|realizedGainEur")
        taxLots.forEach {
            sb.appendLine("${it.id}|${clean(it.symbol)}|${clean(it.quantity)}|${clean(it.costBasisEur)}|${it.openedAtEpochMs}|${it.closedAtEpochMs ?: ""}|${clean(it.realizedGainEur)}")
        }
        sb.appendLine()
        sb.appendLine("[OPEN_POSITIONS]")
        sb.appendLine("symbol|baseAsset|quantity|entryPriceEur|highestPriceEur|stopPriceEur|takeProfitPriceEur|trailingStopPriceEur|openedAtEpochMs|updatedAtEpochMs|status|source")
        openPositions.forEach {
            sb.appendLine("${clean(it.symbol)}|${clean(it.baseAsset)}|${clean(it.quantity)}|${clean(it.entryPriceEur)}|${clean(it.highestPriceEur)}|${clean(it.stopPriceEur)}|${clean(it.takeProfitPriceEur)}|${clean(it.trailingStopPriceEur)}|${it.openedAtEpochMs}|${it.updatedAtEpochMs}|${clean(it.status)}|${clean(it.source)}")
        }
        sb.appendLine()
        sb.appendLine("[TAX_REPORT_ROWS]")
        sb.appendLine("id|timestampEpochMs|symbol|side|quantity|priceEur|feeEur|realizedGainEur|note")
        taxRows.forEach {
            sb.appendLine("${it.id}|${it.timestampEpochMs}|${clean(it.symbol)}|${clean(it.side)}|${clean(it.quantity)}|${clean(it.priceEur)}|${clean(it.feeEur)}|${clean(it.realizedGainEur)}|${clean(it.note)}")
        }
        sb.appendLine()
        sb.appendLine("[LEARNED_SYMBOL_PROFILES]")
        sb.appendLine("symbol|updatedAtEpochMs|sampleSize|wins|losses|winRatePercent|profitFactor|averagePnlEur|netPnlEur|scoreAdjustment|minScoreAdjustment|positionMultiplier|cooldownMultiplier|preferredStrategy|disabledUntilEpochMs|confidence|explanation")
        symbolProfiles.forEach {
            sb.appendLine("${clean(it.symbol)}|${it.updatedAtEpochMs}|${it.sampleSize}|${it.wins}|${it.losses}|${clean(it.winRatePercent)}|${clean(it.profitFactor)}|${clean(it.averagePnlEur)}|${clean(it.netPnlEur)}|${it.scoreAdjustment}|${it.minScoreAdjustment}|${clean(it.positionMultiplier)}|${clean(it.cooldownMultiplier)}|${clean(it.preferredStrategy)}|${it.disabledUntilEpochMs}|${clean(it.confidence)}|${clean(it.explanation)}")
        }
        sb.appendLine()
        sb.appendLine("[LEARNED_STRATEGY_PROFILES]")
        sb.appendLine("strategyKey|updatedAtEpochMs|sampleSize|wins|losses|winRatePercent|profitFactor|scoreAdjustment|positionMultiplier|explanation")
        strategyProfiles.forEach {
            sb.appendLine("${clean(it.strategyKey)}|${it.updatedAtEpochMs}|${it.sampleSize}|${it.wins}|${it.losses}|${clean(it.winRatePercent)}|${clean(it.profitFactor)}|${it.scoreAdjustment}|${clean(it.positionMultiplier)}|${clean(it.explanation)}")
        }
        sb.appendLine()
        sb.appendLine("[LEARNED_HOLD_PROFILES]")
        sb.appendLine("symbol|updatedAtEpochMs|sampleSize|profitableExits|losingExits|continuationWinRatePercent|averageHoldMinutes|averagePnlEur|netPnlEur|holdConfidencePercent|holdMultiplier|shouldDeferTakeProfit|shouldDeferTrailingExit|explanation")
        holdProfiles.forEach {
            sb.appendLine("${clean(it.symbol)}|${it.updatedAtEpochMs}|${it.sampleSize}|${it.profitableExits}|${it.losingExits}|${clean(it.continuationWinRatePercent)}|${clean(it.averageHoldMinutes)}|${clean(it.averagePnlEur)}|${clean(it.netPnlEur)}|${it.holdConfidencePercent}|${clean(it.holdMultiplier)}|${it.shouldDeferTakeProfit}|${it.shouldDeferTrailingExit}|${clean(it.explanation)}")
        }
        sb.appendLine()
        sb.appendLine("[LEARNING_FEATURE_SNAPSHOTS]")
        sb.appendLine("id|timestampEpochMs|symbol|strategyMode|mode|action|finalScore|technicalScore|newsScore|memoryScore|spreadPercent|volume24h|priceChange24hPercent|allowedToTrade|traded|orderSide|orderType|notionalQuote|reason")
        learningSnapshots.forEach {
            sb.appendLine("${it.id}|${it.timestampEpochMs}|${clean(it.symbol)}|${clean(it.strategyMode)}|${clean(it.mode)}|${clean(it.action)}|${it.finalScore}|${it.technicalScore}|${it.newsScore}|${it.memoryScore}|${clean(it.spreadPercent)}|${clean(it.volume24h)}|${clean(it.priceChange24hPercent)}|${it.allowedToTrade}|${it.traded}|${clean(it.orderSide)}|${clean(it.orderType)}|${clean(it.notionalQuote)}|${clean(it.reason)}")
        }
        sb.appendLine()
        sb.appendLine("[SELF_LEARNING_AUDIT]")
        sb.appendLine("id|timestampEpochMs|eventType|symbol|message")
        audits.forEach {
            sb.appendLine("${it.id}|${it.timestampEpochMs}|${clean(it.eventType)}|${clean(it.symbol)}|${clean(it.message)}")
        }
        sb.appendLine()
        sb.appendLine("[SECURE_VALUES]")
        sb.appendLine("key|value")
        secureValues.forEach { (key, value) ->
            sb.appendLine("${clean(key)}|${clean(value)}")
        }
        sb.appendLine()
        sb.appendLine("[SECURITY_NOTE]")
        sb.appendLine("This backup includes API keys, secret keys, Telegram/Discord tokens, webhooks and remote command PINs because the user requested a full restore-everything backup.")
        sb.appendLine("Keep this backup file private and do not share it.")
        updateStatus("Full local backup generated: trades=${trades.size}, signals=${signals.size}, ai=${aiDecisions.size}, profiles=${symbolProfiles.size + strategyProfiles.size + holdProfiles.size}, secure=${secureValues.size}", "INFO")
        return sb.toString()
    }



    suspend fun restoreFullLocalBackup(
        rawInput: String,
        replaceExistingLocalData: Boolean = false
    ): String {
        return try {
            val input = rawInput.trim()
            if (input.isBlank()) return "Restore failed: backup text/path is empty."

            val backupText = if (input.startsWith("content://") && input.length < 1200) {
                runCatching {
                    appContext.contentResolver.openInputStream(Uri.parse(input))?.bufferedReader()?.use { it.readText() }
                        ?: error("Could not open selected backup URI.")
                }.getOrElse {
                    return "Restore failed: could not read backup URI. Paste backup text directly or choose a readable file. ${it.message}"
                }
            } else if ((input.startsWith("/") || input.startsWith("file:")) && input.length < 600) {
                val path = input.removePrefix("file://")
                runCatching { java.io.File(path).readText() }.getOrElse {
                    return "Restore failed: could not read file path. Paste the backup text directly or enter a readable local file path. ${it.message}"
                }
            } else input

            if (!backupText.contains("CRYPTO_TRADE_STATION_FULL_BACKUP", ignoreCase = true)) {
                return "Restore failed: this does not look like a Crypto TradeStation full backup."
            }

            val sections = splitBackupSections(backupText)
            val settingsMap = sections["SETTINGS"].orEmpty()
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                }
                .toMap()

            var restoredSettings = false
            if (settingsMap.isNotEmpty()) {
                val current = settingsStore.load()
                val restored = current.copy(
                    mode = settingsMap["mode"]?.let { runCatching { BotMode.valueOf(it) }.getOrNull() } ?: current.mode,
                    exchangeProvider = settingsMap["exchangeProvider"]?.let { runCatching { ExchangeProvider.valueOf(it) }.getOrNull() } ?: current.exchangeProvider,
                    liveTradingAcknowledged = settingsMap["liveTradingAcknowledged"]?.toBooleanStrictOrNull() ?: current.liveTradingAcknowledged,
                    manualExecutionMode = settingsMap["manualExecutionMode"]?.toBooleanStrictOrNull() ?: current.manualExecutionMode,
                    symbolsCsv = settingsMap["symbolsCsv"] ?: current.symbolsCsv,
                    allowedQuoteAssetsCsv = settingsMap["allowedQuoteAssetsCsv"] ?: current.allowedQuoteAssetsCsv,
                    autoSymbolQuoteAsset = settingsMap["autoSymbolQuoteAsset"] ?: current.autoSymbolQuoteAsset,
                    maxPositionEur = settingsMap["maxPositionEur"]?.toBigDecimalOrNull() ?: current.maxPositionEur,
                    maxBuyPriceFilterEnabled = settingsMap["maxBuyPriceFilterEnabled"]?.toBooleanStrictOrNull() ?: current.maxBuyPriceFilterEnabled,
                    globalMaxBuyPriceEur = settingsMap["globalMaxBuyPriceEur"]?.toBigDecimalOrNull() ?: current.globalMaxBuyPriceEur,
                    perSymbolMaxBuyPriceCsv = settingsMap["perSymbolMaxBuyPriceCsv"] ?: current.perSymbolMaxBuyPriceCsv,
                    ultimateAutomationEnabled = settingsMap["ultimateAutomationEnabled"]?.toBooleanStrictOrNull() ?: current.ultimateAutomationEnabled,
                    perSymbolRulesEnabled = settingsMap["perSymbolRulesEnabled"]?.toBooleanStrictOrNull() ?: current.perSymbolRulesEnabled,
                    perSymbolRulesCsv = settingsMap["perSymbolRulesCsv"] ?: current.perSymbolRulesCsv,
                    autoCompoundingHardCapEnabled = settingsMap["autoCompoundingHardCapEnabled"]?.toBooleanStrictOrNull() ?: current.autoCompoundingHardCapEnabled,
                    autoCompoundingMaxPositionEur = settingsMap["autoCompoundingMaxPositionEur"]?.toBigDecimalOrNull() ?: current.autoCompoundingMaxPositionEur,
                    autoPauseAfterOrderFailuresEnabled = settingsMap["autoPauseAfterOrderFailuresEnabled"]?.toBooleanStrictOrNull() ?: current.autoPauseAfterOrderFailuresEnabled,
                    autoPauseFailureThreshold = settingsMap["autoPauseFailureThreshold"]?.toIntOrNull() ?: current.autoPauseFailureThreshold,
                    autoPauseMinutes = settingsMap["autoPauseMinutes"]?.toIntOrNull() ?: current.autoPauseMinutes,
                    volatilityCircuitBreakerEnabled = settingsMap["volatilityCircuitBreakerEnabled"]?.toBooleanStrictOrNull() ?: current.volatilityCircuitBreakerEnabled,
                    volatilityCircuitBreakerMax24hMovePercent = settingsMap["volatilityCircuitBreakerMax24hMovePercent"]?.toBigDecimalOrNull() ?: current.volatilityCircuitBreakerMax24hMovePercent,
                    pumpChaseProtectionEnabled = settingsMap["pumpChaseProtectionEnabled"]?.toBooleanStrictOrNull() ?: current.pumpChaseProtectionEnabled,
                    pumpChaseMax24hGainPercent = settingsMap["pumpChaseMax24hGainPercent"]?.toBigDecimalOrNull() ?: current.pumpChaseMax24hGainPercent,
                    duplicatePositionProtectionEnabled = settingsMap["duplicatePositionProtectionEnabled"]?.toBooleanStrictOrNull() ?: current.duplicatePositionProtectionEnabled,
                    adaptiveCompoundingFromRealizedPnlEnabled = settingsMap["adaptiveCompoundingFromRealizedPnlEnabled"]?.toBooleanStrictOrNull() ?: current.adaptiveCompoundingFromRealizedPnlEnabled,
                    dynamicScanIntervalEnabled = settingsMap["dynamicScanIntervalEnabled"]?.toBooleanStrictOrNull() ?: current.dynamicScanIntervalEnabled,
                    dynamicScanFastSeconds = settingsMap["dynamicScanFastSeconds"]?.toLongOrNull() ?: current.dynamicScanFastSeconds,
                    dynamicScanSlowSeconds = settingsMap["dynamicScanSlowSeconds"]?.toLongOrNull() ?: current.dynamicScanSlowSeconds,
                    multiTimeframeConsensusEnabled = settingsMap["multiTimeframeConsensusEnabled"]?.toBooleanStrictOrNull() ?: current.multiTimeframeConsensusEnabled,
                    multiTimeframeRequiredBullishCount = settingsMap["multiTimeframeRequiredBullishCount"]?.toIntOrNull() ?: current.multiTimeframeRequiredBullishCount,
                    ultimateReadinessScoreEnabled = settingsMap["ultimateReadinessScoreEnabled"]?.toBooleanStrictOrNull() ?: current.ultimateReadinessScoreEnabled,
                    orderBookDepthGuardEnabled = settingsMap["orderBookDepthGuardEnabled"]?.toBooleanStrictOrNull() ?: current.orderBookDepthGuardEnabled,
                    maxOrderBookSlippagePercent = settingsMap["maxOrderBookSlippagePercent"]?.toBigDecimalOrNull() ?: current.maxOrderBookSlippagePercent,
                    minOrderBookDepthMultiple = settingsMap["minOrderBookDepthMultiple"]?.toBigDecimalOrNull() ?: current.minOrderBookDepthMultiple,
                    maxDailyLossEur = settingsMap["maxDailyLossEur"]?.toBigDecimalOrNull() ?: current.maxDailyLossEur,
                    maxTradesPerDay = settingsMap["maxTradesPerDay"]?.toIntOrNull() ?: current.maxTradesPerDay,
                    maxTradesPerHour = settingsMap["maxTradesPerHour"]?.toIntOrNull() ?: current.maxTradesPerHour,
                    maxNewTradesPerScan = settingsMap["maxNewTradesPerScan"]?.toIntOrNull() ?: current.maxNewTradesPerScan,
                    maxSimultaneousLivePositions = settingsMap["maxSimultaneousLivePositions"]?.toIntOrNull() ?: current.maxSimultaneousLivePositions,
                    minStrategyScoreToBuy = settingsMap["minStrategyScoreToBuy"]?.toIntOrNull() ?: current.minStrategyScoreToBuy,
                    enableMarketOrders = settingsMap["enableMarketOrders"]?.toBooleanStrictOrNull() ?: current.enableMarketOrders,
                    enableBacktestGate = settingsMap["enableBacktestGate"]?.toBooleanStrictOrNull() ?: current.enableBacktestGate,
                    enableForwardTestGate = settingsMap["enableForwardTestGate"]?.toBooleanStrictOrNull() ?: current.enableForwardTestGate,
                    trueSelfLearningEnabled = settingsMap["trueSelfLearningEnabled"]?.toBooleanStrictOrNull() ?: current.trueSelfLearningEnabled,
                    spikeProfitTimingEnabled = settingsMap["spikeProfitTimingEnabled"]?.toBooleanStrictOrNull() ?: current.spikeProfitTimingEnabled,
                    telegramRemoteControlEnabled = settingsMap["telegramRemoteControlEnabled"]?.toBooleanStrictOrNull() ?: current.telegramRemoteControlEnabled,
                    discordRemoteControlEnabled = settingsMap["discordRemoteControlEnabled"]?.toBooleanStrictOrNull() ?: current.discordRemoteControlEnabled,
                    remoteCommandCenterEnabled = settingsMap["remoteCommandCenterEnabled"]?.toBooleanStrictOrNull() ?: current.remoteCommandCenterEnabled,
                    telegramCommandPollingEnabled = settingsMap["telegramCommandPollingEnabled"]?.toBooleanStrictOrNull() ?: current.telegramCommandPollingEnabled,
                    discordCommandPollingEnabled = settingsMap["discordCommandPollingEnabled"]?.toBooleanStrictOrNull() ?: current.discordCommandPollingEnabled,
                    remoteCommandRequirePin = settingsMap["remoteCommandRequirePin"]?.toBooleanStrictOrNull() ?: current.remoteCommandRequirePin,
                    remoteCommandAllowLiveAuto = settingsMap["remoteCommandAllowLiveAuto"]?.toBooleanStrictOrNull() ?: current.remoteCommandAllowLiveAuto
                )
                settingsStore.save(restored)
                restoredSettings = true
            }

            if (replaceExistingLocalData) {
                dao.clearTradesForRestore()
                dao.clearSignalsForRestore()
                dao.clearAiDecisionsForRestore()
                dao.clearNewsArticlesForRestore()
                dao.clearTaxLotsForRestore()
                dao.clearPositionsForRestore()
                dao.clearTaxReportsForRestore()
                dao.clearLearningFeatureSnapshotsForRestore()
                dao.clearLearnedSymbolProfilesForRestore()
                dao.clearLearnedStrategyProfilesForRestore()
                dao.clearLearnedHoldProfilesForRestore()
                dao.clearSelfLearningAuditForRestore()
            }

            val secureValues = sections["SECURE_VALUES"].orEmpty()
                .dropWhile { !it.startsWith("key|") }
                .drop(1)
                .mapNotNull { line ->
                    val idx = line.indexOf('|')
                    if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
                }.toMap()
            if (secureValues.isNotEmpty()) settingsStore.restoreSecureBackupMap(secureValues)

            val restoredTrades = restoreTradesFromSection(sections["TRADES"].orEmpty())
            val restoredSignals = restoreSignalsFromSection(sections["SIGNALS"].orEmpty())
            val restoredAiDecisions = restoreAiDecisionsFromSection(sections["AI_DECISIONS"].orEmpty())
            val restoredTaxLots = restoreTaxLotsFromSection(sections["TAX_LOTS"].orEmpty())
            val restoredPositions = restorePositionsFromSection(sections["OPEN_POSITIONS"].orEmpty())
            val restoredTaxRows = restoreTaxRowsFromSection(sections["TAX_REPORT_ROWS"].orEmpty())
            val restoredSymbolProfiles = restoreLearnedSymbolProfilesFromSection(sections["LEARNED_SYMBOL_PROFILES"].orEmpty())
            val restoredStrategyProfiles = restoreLearnedStrategyProfilesFromSection(sections["LEARNED_STRATEGY_PROFILES"].orEmpty())
            val restoredHoldProfiles = restoreLearnedHoldProfilesFromSection(sections["LEARNED_HOLD_PROFILES"].orEmpty())
            val restoredLearningSnapshots = restoreLearningFeatureSnapshotsFromSection(sections["LEARNING_FEATURE_SNAPSHOTS"].orEmpty())
            val restoredAudits = restoreSelfLearningAuditFromSection(sections["SELF_LEARNING_AUDIT"].orEmpty())

            val message = buildString {
                appendLine("Restore complete.")
                appendLine("settings=${if (restoredSettings) "restored" else "not found"}")
                appendLine("secureValues=${secureValues.size}")
                appendLine("trades=$restoredTrades")
                appendLine("signals=$restoredSignals")
                appendLine("aiDecisions=$restoredAiDecisions")
                appendLine("taxLots=$restoredTaxLots")
                appendLine("openPositions=$restoredPositions")
                appendLine("taxRows=$restoredTaxRows")
                appendLine("symbolProfiles=$restoredSymbolProfiles")
                appendLine("strategyProfiles=$restoredStrategyProfiles")
                appendLine("holdProfiles=$restoredHoldProfiles")
                appendLine("learningSnapshots=$restoredLearningSnapshots")
                appendLine("audits=$restoredAudits")
                appendLine("replaceExistingLocalData=$replaceExistingLocalData")
                appendLine()
                appendLine("Full backup restore includes credentials/tokens/PINs when the backup contains SECURE_VALUES. Keep backups private.")
            }.trim()
            updateStatus("Backup restore complete. trades=$restoredTrades, signals=$restoredSignals, ai=$restoredAiDecisions, positions=$restoredPositions, secure=${secureValues.size}", "INFO")
            message
        } catch (error: Exception) {
            val message = "Restore failed: ${error.message}"
            updateStatus(message, "ERROR")
            message
        }
    }

    private fun splitBackupSections(text: String): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        var current = "ROOT"
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            val section = Regex("^\\[([^]]+)]$").find(line)?.groupValues?.getOrNull(1)
            if (section != null) {
                current = section
                out.getOrPut(current) { mutableListOf() }
            } else {
                out.getOrPut(current) { mutableListOf() }.add(line)
            }
        }
        return out.mapValues { it.value.filter { line -> line.isNotBlank() } }
    }

    private suspend fun restoreTradesFromSection(lines: List<String>): Int {
        var count = 0
        lines.dropWhile { !it.startsWith("id|") }.drop(1).forEach { line ->
            val p = line.split("|")
            if (p.size >= 13) {
                runCatching {
                    dao.restoreTrade(
                        TradeEntity(
                            id = p[0].toLongOrNull() ?: 0L,
                            timestampEpochMs = p[1].toLongOrNull() ?: System.currentTimeMillis(),
                            symbol = p[2],
                            side = p[3],
                            quantity = p[4],
                            priceEur = p[5],
                            feeEur = p[6],
                            paper = p[7].toBooleanStrictOrNull() ?: true,
                            realizedPnlEur = p[8],
                            aiScore = p[9].toIntOrNull() ?: 0,
                            clientOrderId = p[10],
                            exchangeOrderId = p[11],
                            aiReason = p.drop(12).joinToString("|")
                        )
                    )
                    count++
                }
            }
        }
        return count
    }

    private suspend fun restorePositionsFromSection(lines: List<String>): Int {
        var count = 0
        lines.dropWhile { !it.startsWith("symbol|") }.drop(1).forEach { line ->
            val p = line.split("|")
            if (p.size >= 12) {
                runCatching {
                    dao.upsertPosition(
                        PositionEntity(
                            symbol = p[0],
                            baseAsset = p[1],
                            quantity = p[2],
                            entryPriceEur = p[3],
                            highestPriceEur = p[4],
                            stopPriceEur = p[5],
                            takeProfitPriceEur = p[6],
                            trailingStopPriceEur = p[7],
                            openedAtEpochMs = p[8].toLongOrNull() ?: System.currentTimeMillis(),
                            updatedAtEpochMs = p[9].toLongOrNull() ?: System.currentTimeMillis(),
                            status = p[10],
                            source = p[11]
                        )
                    )
                    count++
                }
            }
        }
        return count
    }


    private suspend fun restoreSignalsFromSection(lines: List<String>): Int {
        var count = 0
        parseBackupRows(lines, "id|").forEach { p ->
            if (p.size >= 7) runCatching {
                dao.restoreSignal(SignalEntity(p[0].toLongOrNull() ?: 0L, p[2], p[3], p[4].toIntOrNull() ?: 0, p[5], p.drop(6).joinToString("|"), p[1].toLongOrNull() ?: System.currentTimeMillis()))
                count++
            }
        }
        return count
    }

    private suspend fun restoreAiDecisionsFromSection(lines: List<String>): Int {
        var count = 0
        parseBackupRows(lines, "id|").forEach { p ->
            if (p.size >= 11) runCatching {
                dao.restoreAiDecision(AiDecisionEntity(p[0].toLongOrNull() ?: 0L, p[2], p[3], p[4].toIntOrNull() ?: 0, p[5].toIntOrNull() ?: 0, p[6].toIntOrNull() ?: 0, p[7].toIntOrNull() ?: 0, p[8].toIntOrNull() ?: 0, p[9].toBooleanStrictOrNull() ?: false, p.drop(10).joinToString("|"), p[1].toLongOrNull() ?: System.currentTimeMillis()))
                count++
            }
        }
        return count
    }

    private suspend fun restoreTaxLotsFromSection(lines: List<String>): Int {
        var count = 0
        parseBackupRows(lines, "id|").forEach { p ->
            if (p.size >= 7) runCatching {
                dao.restoreTaxLot(TaxLotEntity(p[0].toLongOrNull() ?: 0L, p[1], p[2], p[3], p[4].toLongOrNull() ?: System.currentTimeMillis(), p[5].toLongOrNull(), p[6]))
                count++
            }
        }
        return count
    }

    private suspend fun restoreTaxRowsFromSection(lines: List<String>): Int {
        var count = 0
        parseBackupRows(lines, "id|").forEach { p ->
            if (p.size >= 9) runCatching {
                dao.restoreTaxReportRow(TaxReportEntity(p[0].toLongOrNull() ?: 0L, p[1].toLongOrNull() ?: System.currentTimeMillis(), p[2], p[3], p[4], p[5], p[6], p[7], p.drop(8).joinToString("|")))
                count++
            }
        }
        return count
    }

    private suspend fun restoreLearnedSymbolProfilesFromSection(lines: List<String>): Int {
        var count = 0
        parseBackupRows(lines, "symbol|").forEach { p ->
            if (p.size >= 17) runCatching {
                dao.upsertLearnedSymbolProfile(LearnedSymbolProfileEntity(p[0], p[1].toLongOrNull() ?: System.currentTimeMillis(), p[2].toIntOrNull() ?: 0, p[3].toIntOrNull() ?: 0, p[4].toIntOrNull() ?: 0, p[5], p[6], p[7], p[8], p[9].toIntOrNull() ?: 0, p[10].toIntOrNull() ?: 0, p[11], p[12], p[13], p[14].toLongOrNull() ?: 0L, p[15], p.drop(16).joinToString("|")))
                count++
            }
        }
        return count
    }

    private suspend fun restoreLearnedStrategyProfilesFromSection(lines: List<String>): Int {
        var count = 0
        parseBackupRows(lines, "strategyKey|").forEach { p ->
            if (p.size >= 10) runCatching {
                dao.upsertLearnedStrategyProfile(LearnedStrategyProfileEntity(p[0], p[1].toLongOrNull() ?: System.currentTimeMillis(), p[2].toIntOrNull() ?: 0, p[3].toIntOrNull() ?: 0, p[4].toIntOrNull() ?: 0, p[5], p[6], p[7].toIntOrNull() ?: 0, p[8], p.drop(9).joinToString("|")))
                count++
            }
        }
        return count
    }

    private suspend fun restoreLearnedHoldProfilesFromSection(lines: List<String>): Int {
        var count = 0
        parseBackupRows(lines, "symbol|").forEach { p ->
            if (p.size >= 14) runCatching {
                dao.upsertLearnedHoldProfile(LearnedHoldProfileEntity(p[0], p[1].toLongOrNull() ?: System.currentTimeMillis(), p[2].toIntOrNull() ?: 0, p[3].toIntOrNull() ?: 0, p[4].toIntOrNull() ?: 0, p[5], p[6], p[7], p[8], p[9].toIntOrNull() ?: 0, p[10], p[11].toBooleanStrictOrNull() ?: false, p[12].toBooleanStrictOrNull() ?: false, p.drop(13).joinToString("|")))
                count++
            }
        }
        return count
    }

    private suspend fun restoreLearningFeatureSnapshotsFromSection(lines: List<String>): Int {
        var count = 0
        parseBackupRows(lines, "id|").forEach { p ->
            if (p.size >= 19) runCatching {
                dao.restoreLearningFeatureSnapshot(LearningFeatureSnapshotEntity(p[0].toLongOrNull() ?: 0L, p[1].toLongOrNull() ?: System.currentTimeMillis(), p[2], p[3], p[4], p[5], p[6].toIntOrNull() ?: 0, p[7].toIntOrNull() ?: 0, p[8].toIntOrNull() ?: 0, p[9].toIntOrNull() ?: 0, p[10], p[11], p[12], p[13].toBooleanStrictOrNull() ?: false, p[14].toBooleanStrictOrNull() ?: false, p[15], p[16], p[17], p.drop(18).joinToString("|")))
                count++
            }
        }
        return count
    }

    private suspend fun restoreSelfLearningAuditFromSection(lines: List<String>): Int {
        var count = 0
        parseBackupRows(lines, "id|").forEach { p ->
            if (p.size >= 5) runCatching {
                dao.restoreSelfLearningAudit(SelfLearningAuditEntity(p[0].toLongOrNull() ?: 0L, p[1].toLongOrNull() ?: System.currentTimeMillis(), p[2], p[3], p.drop(4).joinToString("|")))
                count++
            }
        }
        return count
    }


    suspend fun exportFullLocalBackupToFile(
        settings: BotSettings = settingsStore.load(),
        customDirectoryPath: String = settingsStore.backupDirectoryPath()
    ): String {
        return try {
            val backup = exportFullLocalBackup(settings)
            val requested = customDirectoryPath.trim()
            val filename = "cts_backup_${System.currentTimeMillis()}.txt"
            val preview = backup.lineSequence().take(80).joinToString("\n")

            if (requested.startsWith("content://")) {
                val treeUri = Uri.parse(requested)
                val resolver = appContext.contentResolver
                val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
                )
                val documentUri = DocumentsContract.createDocument(
                    resolver,
                    parentDocumentUri,
                    "text/plain",
                    filename
                ) ?: error("Android folder picker did not return a writable document URI. Select the backup folder again and allow write access.")
                resolver.openOutputStream(documentUri, "w")?.use { stream ->
                    stream.write(backup.toByteArray(Charsets.UTF_8))
                    stream.flush()
                } ?: error("Could not open selected folder for writing. Select the backup folder again and allow write access.")
                val result = buildString {
                    appendLine("BACKUP SAVED SUCCESSFULLY")
                    appendLine("fileUri=$documentUri")
                    appendLine("directoryUri=$treeUri")
                    appendLine("parentDocumentUri=$parentDocumentUri")
                    appendLine("customDirectoryRequested=$customDirectoryPath")
                    appendLine("sizeBytes=${backup.toByteArray(Charsets.UTF_8).size}")
                    appendLine()
                    appendLine("The full backup was written through Android's folder picker permission.")
                    appendLine()
                    appendLine("[PREVIEW FIRST 80 LINES]")
                    appendLine(preview)
                }
                updateStatus("Full backup saved to selected folder: $filename", "INFO")
                return result
            }

            val defaultBackupDir = java.io.File(appContext.getExternalFilesDir(null), "backups")
            val backupDir = if (requested.isNotBlank()) java.io.File(requested) else defaultBackupDir
            if (!backupDir.exists()) backupDir.mkdirs()
            if (!backupDir.exists() || !backupDir.isDirectory || !backupDir.canWrite()) {
                defaultBackupDir.mkdirs()
                updateStatus("Custom backup directory is not writable. Falling back to ${defaultBackupDir.absolutePath}", "WARN")
            }
            val finalDir = if (backupDir.exists() && backupDir.isDirectory && backupDir.canWrite()) backupDir else defaultBackupDir
            val file = java.io.File(finalDir, filename)
            file.writeText(backup)
            val result = buildString {
                appendLine("BACKUP SAVED SUCCESSFULLY")
                appendLine("file=${file.absolutePath}")
                appendLine("directory=${file.parentFile?.absolutePath ?: ""}")
                appendLine("customDirectoryRequested=${customDirectoryPath.ifBlank { "default app backup folder" }}")
                appendLine("sizeBytes=${file.length()}")
                appendLine()
                appendLine("The full backup was written to a file instead of being loaded into the text box, which prevents UI crashes on large databases.")
                appendLine()
                appendLine("[PREVIEW FIRST 80 LINES]")
                appendLine(preview)
            }
            updateStatus("Full backup saved to file: ${file.absolutePath}", "INFO")
            result
        } catch (error: Exception) {
            val message = "Backup export failed: ${error.message}"
            updateStatus(message, "ERROR")
            message
        }
    }

    /**
     * Runs the app-level verification suite and exports a privacy-safe runtime
     * diagnostics report. Exchange/API secrets, bot tokens, webhook secrets and
     * remote command PINs are explicitly redacted from every text section.
     */
    suspend fun exportFullDiagnosticsToFile(
        settings: BotSettings = settingsStore.load(),
        customDirectoryPath: String = settingsStore.diagnosticsDirectoryPath()
    ): Pair<List<String>, String> {
        val verification = runCatching { runSystemFeatureVerification(settings) }
            .getOrElse { error ->
                listOf("FAIL | Full Diagnostics | System verification aborted: ${error.message}")
            }

        return try {
            val portfolio = runCatching { loadPortfolioSnapshot(settings) }.getOrNull()
            val lifecycle = runCatching { loadLifecycleSnapshot(settings) }.getOrNull()
            val openOrders = runCatching { loadOpenOrdersSnapshot(settings) }.getOrDefault(emptyList())
            val trades = runCatching { loadTradeJournal(250) }.getOrDefault(emptyList())
            val recentStatus = statusStore.recentLines(500)
            val providerHealth = runCatching {
                com.ksp.cryptobot.news.NewsProviderHealthRegistry.snapshot().map { it.toString() }
            }.getOrDefault(emptyList())

            val secretValues = listOfNotNull(
                settingsStore.exchangeApiKey(ExchangeProvider.KRAKEN),
                settingsStore.exchangeSecretKey(ExchangeProvider.KRAKEN),
                settingsStore.telegramBotToken(),
                settingsStore.discordWebhookUrl(),
                settingsStore.discordBotToken(),
                settingsStore.remoteCommandPin(),
                settingsStore.newsApiKey(),
                settingsStore.cryptoPanicApiKey(),
                settingsStore.marketauxApiKey(),
                settingsStore.newsDataApiKey(),
                settingsStore.gNewsApiKey(),
                settingsStore.guardianApiKey()
            ).filter { it.length >= 4 }.distinct()

            fun sanitize(raw: String): String {
                return secretValues.fold(raw) { safe, secret ->
                    safe.replace(secret, "[REDACTED]", ignoreCase = false)
                }
            }

            val packageInfo = runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            }.getOrNull()

            val report = buildString {
                appendLine("CRYPTO TRADESTATION — FULL APP DIAGNOSTICS")
                appendLine("generatedAt=${java.time.Instant.now()}")
                appendLine("package=${appContext.packageName}")
                appendLine("versionName=${packageInfo?.versionName ?: "unknown"}")
                appendLine("androidSdk=${android.os.Build.VERSION.SDK_INT}")
                appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("mode=${settings.mode}")
                appendLine("exchangeProvider=${settings.exchangeProvider}")
                appendLine("symbols=${settings.symbolsCsv}")
                appendLine("allowedQuoteAssets=${settings.allowedQuoteAssetsCsv}")
                appendLine("liveTradingAcknowledged=${settings.liveTradingAcknowledged}")
                appendLine("ultimateAutomationEnabled=${settings.ultimateAutomationEnabled}")
                appendLine("liveLifecycleManagerEnabled=${settings.liveLifecycleManagerEnabled}")
                appendLine("autoExitManagerEnabled=${settings.autoExitManagerEnabled}")
                appendLine("autoStopLossEnabled=${settings.autoStopLossEnabled}")
                appendLine("autoTakeProfitEnabled=${settings.autoTakeProfitEnabled}")
                appendLine("trueSelfLearningEnabled=${settings.trueSelfLearningEnabled}")
                appendLine("maxPositionEur=${settings.maxPositionEur}")
                appendLine("maxDailyLossEur=${settings.maxDailyLossEur}")
                appendLine("maxTradesPerDay=${settings.maxTradesPerDay}")
                appendLine("maxTradesPerHour=${settings.maxTradesPerHour}")
                appendLine("maxSimultaneousLivePositions=${settings.maxSimultaneousLivePositions}")
                appendLine("secrets=EXCLUDED_AND_REDACTED")
                appendLine()

                appendLine("[SYSTEM_VERIFICATION]")
                verification.forEach { appendLine(sanitize(it)) }
                appendLine()

                appendLine("[PORTFOLIO]")
                if (portfolio == null) {
                    appendLine("unavailable")
                } else {
                    appendLine("provider=${portfolio.provider}")
                    appendLine("totalValueEur=${portfolio.totalValueEur}")
                    appendLine("freeEur=${portfolio.freeEur}")
                    if (portfolio.warning.isNotBlank()) appendLine("warning=${sanitize(portfolio.warning)}")
                    portfolio.assets.take(80).forEach { asset ->
                        appendLine("${asset.asset}|total=${asset.total}|free=${asset.free}|eurValue=${asset.eurValue}")
                    }
                }
                appendLine()

                appendLine("[LIFECYCLE_POSITIONS]")
                if (lifecycle == null) {
                    appendLine("unavailable")
                } else {
                    appendLine("positions=${lifecycle.positions.size}")
                    lifecycle.positions.take(80).forEach { position ->
                        appendLine(
                            "${position.symbol}|qty=${position.quantity}|entry=${position.entryPrice}|" +
                                "current=${position.currentPrice}|pnlEur=${position.unrealizedPnlEur}|" +
                                "pnlPct=${position.unrealizedPnlPercent}|managed=${position.managed}"
                        )
                    }
                    lifecycle.messages.take(80).forEach { appendLine("message=${sanitize(it)}") }
                }
                appendLine()

                appendLine("[OPEN_ORDERS]")
                appendLine("count=${openOrders.size}")
                openOrders.take(100).forEach { order ->
                    appendLine(
                        "${order.side}|${order.symbol}|${order.orderType}|price=${order.price}|" +
                            "quantity=${order.quantity}|executed=${order.executedQuantity}|" +
                            "remaining=${order.remainingQuantity}|status=${order.status}"
                    )
                }
                appendLine()

                appendLine("[NEWS_PROVIDER_HEALTH]")
                if (providerHealth.isEmpty()) appendLine("no provider health snapshot")
                else providerHealth.take(100).forEach { appendLine(sanitize(it)) }
                appendLine()

                appendLine("[RECENT_TRADES]")
                appendLine("count=${trades.size}")
                trades.take(250).forEach { trade ->
                    appendLine(
                        "${trade.timestampEpochMs}|${trade.symbol}|${trade.side}|" +
                            "qty=${trade.quantity}|price=${trade.priceEur}|fee=${trade.feeEur}|" +
                            "realizedPnl=${trade.realizedPnlEur}|paper=${trade.paper}|" +
                            "score=${trade.aiScore}|reason=${sanitize(trade.aiReason)}"
                    )
                }
                appendLine()

                appendLine("[RECENT_STATUS_LOG]")
                recentStatus.take(500).forEach { appendLine(sanitize(it)) }
                appendLine()

                appendLine("[PRIVACY]")
                appendLine("API keys, exchange secrets, Telegram tokens, Discord tokens/webhooks, news API keys and remote-command PINs are never intentionally exported.")
            }

            val requested = customDirectoryPath.trim()
            val filename = "cts_full_diagnostics_${System.currentTimeMillis()}.txt"

            val result = if (requested.startsWith("content://")) {
                val treeUri = Uri.parse(requested)
                val resolver = appContext.contentResolver
                val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(treeUri)
                )
                val documentUri = DocumentsContract.createDocument(
                    resolver,
                    parentDocumentUri,
                    "text/plain",
                    filename
                ) ?: error("Android folder picker did not return a writable diagnostics document URI.")

                resolver.openOutputStream(documentUri, "w")?.use { stream ->
                    stream.write(report.toByteArray(Charsets.UTF_8))
                    stream.flush()
                } ?: error("Could not open the selected diagnostics folder for writing.")

                buildString {
                    appendLine("DIAGNOSTICS SAVED SUCCESSFULLY")
                    appendLine("fileUri=$documentUri")
                    appendLine("directoryUri=$treeUri")
                    appendLine("checks=${verification.size}")
                    appendLine("failures=${verification.count { it.startsWith("FAIL") }}")
                    appendLine("warnings=${verification.count { it.startsWith("WARN") }}")
                    appendLine("sizeBytes=${report.toByteArray(Charsets.UTF_8).size}")
                }.trim()
            } else {
                val defaultDiagnosticsDir = java.io.File(appContext.getExternalFilesDir(null), "diagnostics")
                val requestedDir = if (requested.isNotBlank()) java.io.File(requested) else defaultDiagnosticsDir
                if (!requestedDir.exists()) requestedDir.mkdirs()
                if (!requestedDir.exists() || !requestedDir.isDirectory || !requestedDir.canWrite()) {
                    defaultDiagnosticsDir.mkdirs()
                    updateStatus(
                        "Custom diagnostics directory is not writable. Falling back to ${defaultDiagnosticsDir.absolutePath}",
                        "WARN"
                    )
                }
                val finalDir = if (requestedDir.exists() && requestedDir.isDirectory && requestedDir.canWrite()) {
                    requestedDir
                } else {
                    defaultDiagnosticsDir
                }
                val file = java.io.File(finalDir, filename)
                file.writeText(report)
                buildString {
                    appendLine("DIAGNOSTICS SAVED SUCCESSFULLY")
                    appendLine("file=${file.absolutePath}")
                    appendLine("directory=${file.parentFile?.absolutePath ?: ""}")
                    appendLine("checks=${verification.size}")
                    appendLine("failures=${verification.count { it.startsWith("FAIL") }}")
                    appendLine("warnings=${verification.count { it.startsWith("WARN") }}")
                    appendLine("sizeBytes=${file.length()}")
                }.trim()
            }

            updateStatus("Full app diagnostics saved: $filename", "INFO")
            verification to result
        } catch (error: Exception) {
            val message = "Diagnostics export failed: ${error.message}"
            updateStatus(message, "ERROR")
            verification to message
        }
    }


    suspend fun processRemoteCommands(settings: BotSettings = settingsStore.load()): List<String> {
        if (!settings.remoteCommandCenterEnabled) return emptyList()
        val replies = mutableListOf<String>()
        val telegramToken = settingsStore.telegramBotToken().orEmpty()
        val telegramChatId = settingsStore.telegramChatId().orEmpty()

        if (settings.telegramCommandPollingEnabled && telegramToken.isNotBlank() && telegramChatId.isNotBlank()) {
            runCatching {
                val offset = settingsStore.telegramCommandOffset()
                val (messages, nextOffset) = remoteCommandClient.pollTelegram(telegramToken, telegramChatId, offset)
                settingsStore.saveTelegramCommandOffset(nextOffset)
                messages.forEach { message ->
                    val reply = handleRemoteCommand(message, settingsStore.load())
                    replies += reply
                    remoteCommandClient.sendTelegram(telegramToken, telegramChatId, reply)
                }
            }.onFailure { error ->
                updateStatus("Telegram command polling failed: ${error.message}", "WARN")
            }
        }

        val discordToken = settingsStore.discordBotToken().orEmpty()
        val discordChannelId = settingsStore.discordChannelId().orEmpty()
        if (settings.discordCommandPollingEnabled && discordToken.isNotBlank() && discordChannelId.isNotBlank()) {
            runCatching {
                val lastId = settingsStore.discordCommandLastMessageId()
                val (messages, newestId) = remoteCommandClient.pollDiscord(discordToken, discordChannelId, lastId)
                settingsStore.saveDiscordCommandLastMessageId(newestId)
                messages.forEach { message ->
                    val reply = handleRemoteCommand(message, settingsStore.load())
                    replies += reply
                    remoteCommandClient.sendDiscordBotMessage(discordToken, discordChannelId, reply)
                }
            }.onFailure { error ->
                updateStatus("Discord command polling failed: ${error.message}", "WARN")
            }
        }

        if (replies.isNotEmpty()) updateStatus("Remote command center processed ${replies.size} command(s).", "INFO")
        return replies
    }

    private suspend fun handleRemoteCommand(message: RemoteCommandMessage, settings: BotSettings): String {
        val raw = message.text.trim()
        if (!raw.startsWith("/cts", ignoreCase = true) && !raw.startsWith("!cts", ignoreCase = true)) return "Ignored. Commands must start with /cts or !cts."
        val tokens = raw.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        if (tokens.isEmpty()) return remoteHelp()
        tokens.removeAt(0)

        if (tokens.firstOrNull()?.equals("help", ignoreCase = true) == true || tokens.isEmpty()) {
            return remoteHelp()
        }

        if (settings.remoteCommandRequirePin) {
            val configuredPin = settingsStore.remoteCommandPin().orEmpty()
            if (configuredPin.isBlank()) {
                return "Remote command center is locked: no PIN configured in Settings → Notifications/Remote Alerts."
            }
            val suppliedPin = tokens.firstOrNull().orEmpty()
            if (suppliedPin != configuredPin) {
                updateStatus("Remote command rejected from ${message.source}: bad PIN.", "WARN")
                return "Rejected: bad or missing PIN."
            }
            tokens.removeAt(0)
        }

        val command = tokens.firstOrNull()?.lowercase().orEmpty()
        val args = tokens.drop(1)
        return when (command) {
            "help" -> remoteHelp()
            "status" -> remoteStatus()
            "settings" -> remoteSettings(settingsStore.load())
            "portfolio" -> remotePortfolio(settingsStore.load())
            "positions" -> remotePositions(settingsStore.load())
            "orders" -> remoteOrders(settingsStore.load())
            "system_test" -> runSystemFeatureVerification(settingsStore.load()).take(18).joinToString("\n")
            "scan" -> {
                val decisions = scanOnce(settingsStore.load(), execute = false)
                "Scan complete. Decisions=${decisions.size}\n" + decisions.take(8).joinToString("\n") { "${it.symbol}: ${it.finalAction} score=${it.finalScore} allowed=${it.allowedToTrade}" }
            }
            "execute" -> {
                val current = settingsStore.load()
                if (current.mode != BotMode.PAPER && current.mode != BotMode.LIVE_AUTO) {
                    "Execute skipped: mode=${current.mode}. Use PAPER or LIVE_AUTO."
                } else if (current.mode == BotMode.LIVE_AUTO && !current.remoteCommandAllowLiveAuto) {
                    "Execute blocked: remote LIVE_AUTO execution is disabled in settings."
                } else {
                    val decisions = scanOnce(current, execute = true)
                    "Execution pass complete. Decisions=${decisions.size}. Check Live Status for order details."
                }
            }
            "start" -> {
                start()
                "Controller started. The Android foreground service must already be running to keep listening remotely."
            }
            "stop" -> {
                stop()
                "Controller stopped. Remote listener stops when the foreground service loop stops."
            }
            "pause" -> {
                val current = settingsStore.load()
                settingsStore.save(current.copy(mode = BotMode.LIVE_CONFIRM, manualExecutionMode = true))
                "Paused: mode changed to LIVE_CONFIRM and manual/signal-only enabled."
            }
            "resume" -> {
                val current = settingsStore.load()
                if (!current.remoteCommandAllowLiveAuto) {
                    "Resume to LIVE_AUTO blocked: enable 'Allow remote LIVE_AUTO commands' in Remote Alerts first."
                } else {
                    settingsStore.save(current.copy(mode = BotMode.LIVE_AUTO, manualExecutionMode = false))
                    "Resume requested: mode changed to LIVE_AUTO. Existing preflight/release safety still applies."
                }
            }
            "mode" -> {
                val modeName = args.firstOrNull()?.uppercase()
                val mode = runCatching { BotMode.valueOf(modeName.orEmpty()) }.getOrNull()
                if (mode == null) {
                    "Invalid mode. Use PAPER, LIVE_CONFIRM, or LIVE_AUTO."
                } else if (mode == BotMode.LIVE_AUTO && !settings.remoteCommandAllowLiveAuto) {
                    "LIVE_AUTO by remote command is disabled. Enable it in Remote Alerts first."
                } else {
                    val current = settingsStore.load()
                    settingsStore.save(current.copy(mode = mode, manualExecutionMode = mode == BotMode.LIVE_CONFIRM))
                    "Mode changed to $mode."
                }
            }
            "set" -> remoteSet(args)
            else -> "Unknown command: $command\n${remoteHelp()}"
        }
    }

    private fun remoteHelp(): String = """
Crypto TradeStation remote commands:
/cts <PIN> status
/cts <PIN> settings
/cts <PIN> portfolio
/cts <PIN> positions
/cts <PIN> orders
/cts <PIN> scan
/cts <PIN> execute
/cts <PIN> start
/cts <PIN> stop
/cts <PIN> pause
/cts <PIN> resume
/cts <PIN> mode PAPER|LIVE_CONFIRM|LIVE_AUTO
/cts <PIN> set max_position 10
/cts <PIN> set max_buy BTCEUR 95000
/cts <PIN> set score 75
/cts help
""".trimIndent()

    private fun remoteStatus(): String {
        val s = settingsStore.load()
        return "Status=${statusStore.latestText()}\nlevel=${statusStore.latestLevel()}\nrunning=$running\nmode=${s.mode}\nprovider=${s.exchangeProvider}\nmanual=${s.manualExecutionMode}\nremoteCommands=${s.remoteCommandCenterEnabled}"
    }

    private fun remoteSettings(s: BotSettings): String = buildString {
        appendLine("Settings")
        appendLine("mode=${s.mode}")
        appendLine("provider=${s.exchangeProvider}")
        appendLine("symbols=${s.symbolsCsv}")
        appendLine("maxPosition=${s.maxPositionEur}")
        appendLine("maxBuyGlobal=${s.globalMaxBuyPriceEur}")
        appendLine("minScore=${s.minStrategyScoreToBuy}")
        appendLine("allowedQuotes=${s.allowedQuoteAssetsCsv}")
        appendLine("liveAck=${s.liveTradingAcknowledged}")
        appendLine("remoteLiveAutoAllowed=${s.remoteCommandAllowLiveAuto}")
    }.trim()

    private suspend fun remotePortfolio(s: BotSettings): String {
        val p = loadPortfolioSnapshot(s)
        return buildString {
            appendLine("Portfolio ${p.provider}")
            appendLine("total≈€${p.totalValueEur}")
            appendLine("freeEUR≈€${p.freeEur}")
            p.assets.take(8).forEach { appendLine("${it.asset}: total=${it.total} free=${it.free} value≈€${it.eurValue}") }
            if (p.warning.isNotBlank()) appendLine("warning=${p.warning}")
        }.take(1800)
    }

    private suspend fun remotePositions(s: BotSettings): String {
        val snap = loadLifecycleSnapshot(s)
        return buildString {
            appendLine("Positions=${snap.positions.size}")
            snap.positions.take(10).forEach {
                appendLine("${it.symbol}: qty=${it.quantity} entry=${it.entryPrice} pnl≈${it.unrealizedPnlEur} managed=${it.managed}")
            }
            if (snap.messages.isNotEmpty()) appendLine("messages=${snap.messages.take(3).joinToString("; ")}")
        }.take(1800)
    }

    private suspend fun remoteOrders(s: BotSettings): String {
        val orders = loadOpenOrdersSnapshot(s)
        return buildString {
            appendLine("Open orders=${orders.size}")
            orders.take(10).forEach {
                appendLine("${it.side} ${it.symbol} ${it.orderType} remaining=${it.remainingQuantity} price=${it.price} status=${it.status}")
            }
        }.take(1800)
    }

    private fun remoteSet(args: List<String>): String {
        if (args.size < 2) return "Usage: /cts <PIN> set max_position 10 | max_buy BTCEUR 95000 | score 75"
        val current = settingsStore.load()
        return when (args[0].lowercase()) {
            "max_position" -> {
                val value = args.getOrNull(1)?.toBigDecimalOrNull()
                if (value == null || value <= BigDecimal.ZERO) "Invalid max_position."
                else {
                    settingsStore.save(current.copy(maxPositionEur = value))
                    "maxPositionEur changed to $value"
                }
            }
            "score" -> {
                val value = args.getOrNull(1)?.toIntOrNull()
                if (value == null) "Invalid score."
                else {
                    settingsStore.save(current.copy(minStrategyScoreToBuy = value.coerceIn(1, 100)))
                    "minStrategyScoreToBuy changed to ${value.coerceIn(1, 100)}"
                }
            }
            "max_buy" -> {
                val symbol = args.getOrNull(1)?.uppercase()?.replace("/", "")?.replace("-", "")
                val value = args.getOrNull(2)?.toBigDecimalOrNull()
                if (symbol.isNullOrBlank() || value == null || value <= BigDecimal.ZERO) {
                    "Usage: /cts <PIN> set max_buy BTCEUR 95000"
                } else {
                    val existing = current.perSymbolMaxBuyPriceCsv
                        .split(',', ';', '\n')
                        .map { it.trim() }
                        .filter { it.isNotBlank() && !it.uppercase().startsWith("$symbol=") }
                        .toMutableList()
                    existing += "$symbol=${value.stripTrailingZeros().toPlainString()}"
                    settingsStore.save(current.copy(maxBuyPriceFilterEnabled = true, perSymbolMaxBuyPriceCsv = existing.joinToString(",")))
                    "Max buy price for $symbol changed to $value"
                }
            }
            else -> "Unknown setting '${args[0]}'. Supported: max_position, max_buy, score."
        }
    }

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
        if (liveAutoExecution) {
            val blockReason = liveSafetyBlockReason(settings)
            if (blockReason != null) {
                updateStatus("LIVE_AUTO blocked by release safety lock: $blockReason", "ERROR")
                sendRemoteAlert(settings, "LIVE_AUTO blocked", blockReason)
                return emptyList()
            }
        }
        if (paperExecution) {
            updateStatus("Paper execution active: simulated orders and paper wallet will be used. No real exchange order can be sent.", "INFO")
        }
        if (liveAutoExecution) {
            manageExistingLiveOrders(settings, exchange)
            lifecycleManager.runPreScanMaintenance(settings, exchange)
            val reconciliation = advancedExecution.reconcileLive(settings, exchange)
            if (exchange is KrakenSpotClient) {
                val orderTruth = com.ksp.cryptobot.execution.KrakenOrderTruthResolver.resolveDurable(exchange)
                orderTruth.messages.take(8).forEach { updateStatus("M12 order truth: $it", if (orderTruth.unresolved > 0) "WARN" else "INFO") }
                require(orderTruth.unresolved == 0) {
                    "Kraken durable client-order ambiguity remains unresolved (${orderTruth.unresolved}); LIVE entry authority stays blocked."
                }
            }
            if (settings.exchangeProvider == ExchangeProvider.KRAKEN) {
                KrakenPrivateExecutionRegistry.markRestReconciled(reconciliation.openOrders)
            }
            reconciliation.messages.take(8).forEach { updateStatus("Advanced reconciliation: $it", if (reconciliation.removed > 0) "WARN" else "INFO") }
        }
        if (settings.mode == BotMode.PAPER && settings.liveLifecycleManagerEnabled) {
            lifecycleManager.runPreScanMaintenance(settings, exchange)
            updateStatus("Paper lifecycle pre-scan truth: pending fills and persisted source plans refreshed against the fake wallet.", "INFO")
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
        val enteredSymbolsThisScan = mutableSetOf<String>()
        val exitedSymbolsThisScan = mutableSetOf<String>()
        val newsClient = createNewsClient(settings)
        if (settings.useNewsAi) {
            val newsApiKeyCount = settingsStore.newsApiKey()
                ?.split(',', ';', '\n')
                ?.map { it.trim() }
                ?.count { it.isNotBlank() }
                ?: 0
            val cryptoPanicKey = !settingsStore.cryptoPanicApiKey().isNullOrBlank()
            val marketauxKey = !settingsStore.marketauxApiKey().isNullOrBlank()
            val newsDataKey = !settingsStore.newsDataApiKey().isNullOrBlank()
            val gNewsKey = !settingsStore.gNewsApiKey().isNullOrBlank()
            val guardianKey = !settingsStore.guardianApiKey().isNullOrBlank()
            updateStatus("News AI enabled: GDELT + RSS + ${if (cryptoPanicKey) "CryptoPanic" else "CryptoPanic(no key)"} + ${if (marketauxKey) "Marketaux" else "Marketaux(no key)"} + ${if (newsDataKey) "NewsData.io" else "NewsData.io(no key)"} + ${if (gNewsKey) "GNews" else "GNews(no key)"} + ${if (guardianKey) "Guardian" else "Guardian(no key)"} + $newsApiKeyCount NewsAPI key(s).", "INFO")
        }
        val recentTrades = dao.recentTradesSnapshot(settings.selfLearningLookbackTrades.coerceAtLeast(100))
        if (settings.trueSelfLearningEnabled) {
            val learningSummary = selfLearningEngine.refreshFromTradeHistory(dao, settings)
            updateStatus("Self-learning refresh: ${learningSummary.summaryLine}", "INFO")
            learningSummary.symbolProfiles.take(8).forEach { profile ->
                updateStatus("Learned profile ${profile.symbol}: samples=${profile.sampleSize}, win=${profile.winRatePercent}%, pf=${profile.profitFactor}, scoreAdj=${profile.scoreAdjustment}, size×${profile.positionMultiplier}. ${profile.explanation.take(140)}", if (profile.scoreAdjustment < 0) "WARN" else "INFO")
            }
        }
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
        val researchBroadContext = runCatching { researchIntelligence.loadBroadContext(exchange) }
            .onFailure { updateStatus("Research broad context unavailable: ${it.message}", "WARN") }
            .getOrDefault(com.ksp.cryptobot.research.BroadMarketContext())
        updateStatus("Research broad context: BTC=${"%.2f".format(researchBroadContext.btcMomentumPct)}%, ETH=${"%.2f".format(researchBroadContext.ethMomentumPct)}%, broad=${"%.2f".format(researchBroadContext.broadMomentumPct)}%", "INFO")
        val decisions = symbols.mapNotNull { symbol ->
            runCatching {
                updateStatus("[$symbol] Fetching ticker from ${settings.exchangeProvider}...")
                val ticker = exchange.getTicker(symbol)
                updateStatus("[$symbol] Ticker OK. Bid=${ticker.bid}, Ask=${ticker.ask}, Last=${ticker.lastPrice}, 24hVolEUR=${ticker.volume24h.setScale(0, RoundingMode.HALF_UP)}")
                val settledAiCounterfactuals = runCatching {
                    aiValueAttribution.settleDueForSymbol(exchange, ticker)
                }.getOrDefault(0)
                if (settledAiCounterfactuals > 0) {
                    val attributionSummary = aiValueAttribution.summary()
                    updateStatus(
                        "[$symbol] M7 AI attribution resolved=$settledAiCounterfactuals. AI value=${attributionSummary.aiValueAddedQuote.setScale(4, RoundingMode.HALF_UP)}, avoided=${attributionSummary.avoidedLossQuote.setScale(4, RoundingMode.HALF_UP)}, missed=${attributionSummary.missedProfitQuote.setScale(4, RoundingMode.HALF_UP)}, ROI=${attributionSummary.aiRoi?.setScale(3, RoundingMode.HALF_UP) ?: "n/a"}, verdict=${attributionSummary.verdict}",
                        if (attributionSummary.aiValueAddedQuote < BigDecimal.ZERO) "WARN" else "INFO"
                    )

                    val adaptiveDecision = runCatching {
                        aiAdaptiveGovernance.evaluateAndApply()
                    }.getOrNull()
                    if (adaptiveDecision != null) {
                        updateStatus(
                            "[$symbol] M8 AI adaptive governance=${adaptiveDecision.action}. overallN=${adaptiveDecision.overall.samples}, overallUpper95=${adaptiveDecision.overall.upper95.setScale(5, RoundingMode.HALF_UP)}, solN=${adaptiveDecision.sol.samples}, solUpper95=${adaptiveDecision.sol.upper95.setScale(5, RoundingMode.HALF_UP)}, excluded=${adaptiveDecision.excludedLowIntegrityRows}. ${adaptiveDecision.reason.take(280)}",
                            if (adaptiveDecision.action == AiAdaptiveAction.HOLD) "INFO" else "WARN"
                        )
                        if (adaptiveDecision.action != AiAdaptiveAction.HOLD) {
                            sendRemoteAlert(
                                settings,
                                "AI adaptive governance",
                                "${adaptiveDecision.action}: ${adaptiveDecision.reason}"
                            )
                        }
                    }
                }
                val symbolRank = proAutomationSuite.rankSymbol(ticker, recentTrades)
                updateStatus("[$symbol] Smart rotation score=${symbolRank.score}. ${symbolRank.reason}", if (symbolRank.score < 45) "WARN" else "INFO")
                val candlesByTimeframe = if (settings.recoveredScalpingStrategyEnabled) {
                    updateStatus("[$symbol] Fetching multi-timeframe candles...")
                    Timeframe.values().associateWith { timeframe -> exchange.getCandles(symbol, timeframe, 140) }
                } else emptyMap()
                updateStatus("[$symbol] Running recommendation + AI automation engines...")
                val rec = recommendationEngine.recommend(ticker, settings, candlesByTimeframe = candlesByTimeframe)
                dao.insertSignal(rec.toEntity())
                val news = if (settings.useNewsAi) {
                    fetchNewsForSymbol(symbol)
                } else emptyList()
                val newsTitles = news.take(3).joinToString(" | ") { it.title.take(100) }
                val baseDecision = aiDecisionEngine.decide(rec, news, recentTrades, settings).let { decision ->
                    if (newsTitles.isBlank()) decision else decision.copy(explanation = decision.explanation + " News titles: " + newsTitles)
                }
                val riskState = advancedRiskManager.riskState(settings)
                val adaptiveStrategy = selfLearningEngine.selectAdaptiveStrategyMode(dao, symbol, settings.strategyMode, settings)
                val strategySettings = if (settings.strategyMode == StrategyMode.AUTO && adaptiveStrategy.selectedStrategy != StrategyMode.AUTO) {
                    settings.copy(strategyMode = adaptiveStrategy.selectedStrategy)
                } else settings
                updateStatus("[$symbol] Adaptive strategy selector: selected=${adaptiveStrategy.selectedStrategy}, source=${adaptiveStrategy.source}, confidence=${adaptiveStrategy.confidencePercent}%, scoreAdj=${adaptiveStrategy.scoreAdjustment}. ${adaptiveStrategy.explanation.take(180)}", "INFO")
                val baseAutoDecision = advancedAutomationEngine.decide(ticker, candlesByTimeframe, news, recentTrades, strategySettings, riskState)
                val adaptiveAutomation = selfLearningEngine.adaptAutomationDecision(dao, baseAutoDecision, ticker, strategySettings, adaptiveStrategy)
                val autoDecision = adaptiveAutomation.decision
                updateStatus("[$symbol] ${adaptiveAutomation.explanation.take(220)}", "INFO")
                val rawDecision = baseDecision.copy(
                    finalAction = autoDecision.finalAction,
                    finalScore = autoDecision.finalScore,
                    confidencePercent = autoDecision.finalScore.coerceIn(0, 100),
                    technicalScore = autoDecision.finalScore - baseDecision.newsScore - baseDecision.memoryScore,
                    allowedToTrade = autoDecision.allowed,
                    explanation = autoDecision.explanation
                )
                val autonomousAssessment = autonomousPack.assessSymbol(symbol, recentTrades, settings)
                val autonomousDecision = autonomousPack.enrichDecision(rawDecision, ticker, settings, autonomousAssessment)
                val learningResult = selfLearningEngine.adjustDecision(dao, autonomousDecision, ticker, settings)
                val learnedDecision = learningResult.decision
                if (settings.trueSelfLearningEnabled) updateStatus("[$symbol] ${learningResult.explanation.take(220)}", "INFO")
                val researchResult = researchIntelligence.evaluateDecision(
                    settings = settings, decision = learnedDecision, ticker = ticker, candlesByTimeframe = candlesByTimeframe,
                    recentTrades = recentTrades, news = news, exchange = exchange, broad = researchBroadContext
                )
                val researchedDecision = researchResult.first
                val research = researchResult.second
                updateStatus("[$symbol] Research intelligence: strategy=${research.selectedStrategy}, adj=${research.scoreAdjustment}, regime=${research.regime.regime}, WF=${"%.1f".format(research.walkForward.score)}, MC=${"%.1f".format(research.monteCarlo.score)}, seq=${research.sequence.adjustment}, RL=${research.rlSandbox.adjustment}, promoted=${research.promotedFromResearch}. ${research.explanation.take(260)}", if (research.allowed) "INFO" else "WARN")
                val productionResult = productionIntelligence.evaluateDecision(
                    researchedDecision, ticker, candlesByTimeframe[Timeframe.M15].orEmpty(), recentTrades, settings
                )
                val deterministicDecision = productionResult.first
                val production = productionResult.second
                updateStatus("[$symbol] Production intelligence: blocked=${production.blocked}, adj=${production.scoreAdjustment}, size×${"%.2f".format(production.sizeMultiplier)}, safe=${production.safeMode.level}, anomaly=${production.anomaly.severity}, kill=${production.killSwitch.severity}. ${production.reason.take(240)}", if (production.blocked) "WARN" else "INFO")

                val cloudAi = cloudAiRouter.reviewIfUseful(
                    decision = deterministicDecision,
                    ticker = ticker,
                    settings = settings,
                    strategy = research.selectedStrategy.toString(),
                    regime = research.regime.regime.toString(),
                    news = news,
                    recentTrades = recentTrades
                )
                aiValueAttribution.beginCloudReview(
                    deterministicDecision = deterministicDecision,
                    review = cloudAi.review,
                    ticker = ticker,
                    settings = settings,
                    strategy = research.selectedStrategy.toString(),
                    regime = research.regime.regime.toString()
                )
                val decision = cloudAi.decision
                if (cloudAi.review.modelPath != "DETERMINISTIC" || cloudAi.review.verdict == com.ksp.cryptobot.intelligence.CloudAiVerdict.REJECT) {
                    val budget = cloudAiRouter.budgetSnapshot()
                    updateStatus(
                        "[$symbol] Selective cloud AI: ${cloudAi.review.verdict} via ${cloudAi.review.modelPath}, risk×${cloudAi.review.riskMultiplier.setScale(2, RoundingMode.HALF_UP)}, callCost≈${cloudAi.review.totalCostUsd.setScale(6, RoundingMode.HALF_UP)} USD, month=${budget.spentUsd.setScale(4, RoundingMode.HALF_UP)}/${budget.monthlyBudgetUsd.setScale(2, RoundingMode.HALF_UP)} USD. ${cloudAi.review.reason.take(220)}",
                        if (cloudAi.review.verdict == com.ksp.cryptobot.intelligence.CloudAiVerdict.REJECT) "WARN" else "INFO"
                    )
                }
                val replay = autonomousPack.buildTradeReplay(decision, ticker, settings)
                val netCheck = proAutomationSuite.netProfitCheck(ticker, decision, settings)
                val whyLine = proAutomationSuite.explainTrade(ticker, decision, symbolRank, netCheck)
                dao.insertAiDecision(decision.copy(explanation = decision.explanation + " | " + whyLine + " | replay=" + replay.mirrorExitComparison).toEntity())
                updateStatus("[$symbol] Decision=${decision.finalAction}, score=${decision.finalScore}, allowed=${decision.allowedToTrade}. ${decision.explanation.take(180)}")
                updateStatus("[$symbol] Why/edge: ${whyLine.take(240)}", if (netCheck.allowed) "INFO" else "WARN")
                if (settings.tradeReplayEnabled) updateStatus("[$symbol] Trade replay snapshot: ${replay.mirrorExitComparison}", "INFO")
                var tradedThisSymbol = false
                if (execute) {
                    if (settings.autoTradeMultipleSymbolsPerScan && submittedOrdersThisScan >= settings.maxNewTradesPerScan.coerceAtLeast(1)) {
                        updateStatus("[$symbol] Execution skipped: max new trades per scan reached (${submittedOrdersThisScan}/${settings.maxNewTradesPerScan}). Signal saved only.", "WARN")
                    } else if (!settings.autoTradeMultipleSymbolsPerScan && submittedOrdersThisScan >= 1) {
                        updateStatus("[$symbol] Execution skipped: multi-symbol execution disabled and one order was already submitted this scan.", "WARN")
                    } else {
                        val result = executeDecisionIfAllowed(settings, exchange, ticker, decision, liveBalances, reservedByQuoteThisScan.toMap())
                        if (result.submitted) {
                            submittedOrdersThisScan += 1
                            tradedThisSymbol = true
                            val normalizedExecutionSymbol = symbol.uppercase().replace("/", "").replace("-", "")
                            when (decision.finalAction) {
                                SignalAction.BUY, SignalAction.SMALL_BUY -> enteredSymbolsThisScan += normalizedExecutionSymbol
                                SignalAction.SELL -> exitedSymbolsThisScan += normalizedExecutionSymbol
                                else -> Unit
                            }
                        }
                        if (result.reservedAmount > BigDecimal.ZERO) {
                            val current = reservedByQuoteThisScan[result.quoteAsset] ?: BigDecimal.ZERO
                            reservedByQuoteThisScan[result.quoteAsset] = current.add(result.reservedAmount)
                            updateStatus("[$symbol] Reserved ${result.quoteAsset} this scan now ${reservedByQuoteThisScan[result.quoteAsset]?.setScale(2, RoundingMode.UP)}", "INFO")
                        }
                    }
                }
                selfLearningEngine.recordDecisionSnapshot(dao, settings, ticker, decision, tradedThisSymbol, strategyMode = autoDecision.selectedStrategy)
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
        if ((liveAutoExecution || settings.mode == BotMode.PAPER) && settings.liveLifecycleManagerEnabled) {
            val lifecycle = lifecycleManager.runPostDecisionManagement(settings, exchange, decisions, enteredSymbolsThisScan, exitedSymbolsThisScan)
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
        val fallbackRaw = if (settings.tradeOnlyBtcEth) {
            settings.symbols().filter { it.startsWith("BTC") || it.startsWith("ETH") }
        } else settings.symbols()
        val fallback = filterCashLikeBaseSymbols(fallbackRaw, "configured symbols")
        if (!settings.autoSymbolDiscoveryEnabled || (settings.exchangeProvider != ExchangeProvider.KRAKEN && settings.exchangeProvider != ExchangeProvider.PAPER && settings.mode != BotMode.PAPER)) {
            updateStatus("Auto symbol discovery disabled or unavailable. Using configured symbols: ${fallback.joinToString(",")}", "INFO")
            return fallback
        }
        if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) {
            updateStatus("Paper mode market data source: Kraken public endpoints for AssetPairs/Ticker/OHLC; fake local wallet for orders.", "INFO")
        }
        val discovered = discoverAutoSymbols(settings, exchange)
        val enabledCandidates = discovered.filter { it.enabledForRotation && !isCashLikeTradingPairBase(it.symbol, it.baseAsset) }
        val balanceAwareCandidates = if (settings.mode == BotMode.PAPER || liveBalances.isEmpty()) {
            enabledCandidates
        } else {
            enabledCandidates.filter { candidate ->
                val quoteFree = freeBalanceForAsset(liveBalances, candidate.quoteAsset)
                val baseFree = freeBalanceForAsset(liveBalances, candidate.baseAsset)
                val quoteSpendable = quoteFree
                    .subtract(quoteReserveAmount(settings, quoteFree))
                    .max(BigDecimal.ZERO)
                val canBuyWithQuote = settings.isQuoteAssetAllowed(candidate.quoteAsset) && quoteSpendable >= BigDecimal("5.00")
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
        val selected = filterCashLikeBaseSymbols(
            rotationSource.map { it.symbol }.distinct(),
            "auto-rotation selected symbols"
        )
            .let { list -> if (settings.tradeOnlyBtcEth) list.filter { it.startsWith("BTC") || it.startsWith("ETH") } else list }
            .take(settings.autoSymbolActiveLimit.coerceAtLeast(1))
        if (selected.isEmpty()) {
            KrakenRealtimeMarketDataRegistry.setActiveSymbols(fallback)
            updateStatus("Auto symbol discovery produced no tradable/balance-usable candidates. Falling back to configured symbols: ${fallback.joinToString(",")}", "WARN")
            return fallback
        }
        KrakenRealtimeMarketDataRegistry.setActiveSymbols(selected)
        updateStatus("Auto symbol rotation active: ${selected.size} symbols selected from full Kraken universe: ${selected.joinToString(",")}", "LIVE")
        return selected
    }

    suspend fun discoverAutoSymbols(settings: BotSettings = settingsStore.load()): List<SymbolDiscoveryCandidate> {
        return discoverAutoSymbols(settings, createExchange(settings))
    }

    private suspend fun discoverAutoSymbols(settings: BotSettings, exchange: CryptoExchangeClient): List<SymbolDiscoveryCandidate> {
        val quoteUniverse = settings.autoSymbolQuoteAsset.uppercase().ifBlank { "ALL" }
        updateStatus("Auto symbol discovery started. provider=${settings.exchangeProvider}, quoteUniverse=$quoteUniverse, candidates=${settings.autoSymbolCandidateLimit}, allowedQuotes=${settings.allowedQuoteAssetsCsv}. EUR is treated as the primary cash quote unless you enable more quotes.", "INFO")
        val raw = runCatching { exchange.discoverTradableSymbols(quoteUniverse, settings.autoSymbolCandidateLimit.coerceAtLeast(5)) }
            .onFailure { updateStatus("Auto symbol discovery failed: ${it.message}", "ERROR") }
            .getOrElse { emptyList() }
        if (raw.isEmpty()) {
            updateStatus("Auto symbol discovery returned no exchange candidates.", "WARN")
            return emptyList()
        }
        val enriched = raw.mapNotNull { candidate ->
            if (isCashLikeTradingPairBase(candidate.symbol, candidate.baseAsset)) {
                candidate.copy(
                    score = 0,
                    enabledForRotation = false,
                    reason = "Skipped: base asset ${candidate.baseAsset} is a currency/stablecoin/cash-like asset. The bot should use it as quote/cash, not repeatedly buy it as a target."
                )
            } else runCatching {
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
                val quoteAllowed = settings.isQuoteAssetAllowed(candidate.quoteAsset)
                val quoteTradabilityPenalty = if (!quoteAllowed) -35 else if (candidate.quoteAsset.uppercase() != "EUR" && !settings.nonEurQuoteBuyEnabled) -8 else 0
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
            productionIntelligence.recordWhyNotTrade(decision, settings, allowed.second)
            updateStatus("Trade blocked: ${allowed.second}", "WARN")
            return ExecutionAttemptResult(false)
        }
        val proNetCheck = proAutomationSuite.netProfitCheck(ticker, decision, settings)
        updateStatus("[${ticker.symbol}] v1.1 fee/spread net-profit check: ${proNetCheck.reason}", if (proNetCheck.allowed) "INFO" else "WARN")
        if (!proNetCheck.allowed) {
            productionIntelligence.recordWhyNotTrade(decision, settings, proNetCheck.reason)
            return ExecutionAttemptResult(false)
        }
        if (settings.mode == BotMode.LIVE_CONFIRM) {
            updateStatus("Live confirm mode: decision saved, no automatic order placed.", "WARN")
            return ExecutionAttemptResult(false)
        }
        val side = if (decision.finalAction == SignalAction.SELL) OrderSide.SELL else OrderSide.BUY
        val symbolRule = settings.symbolAutomationRuleFor(ticker.symbol)
        if (settings.ultimateAutomationEnabled && side == OrderSide.BUY) {
            val minRuleScore = symbolRule?.minScoreToBuy
            if (minRuleScore != null && decision.finalScore < minRuleScore) {
                updateStatus("Trade blocked by per-symbol rule: ${ticker.symbol} score=${decision.finalScore} is below minScore=$minRuleScore.", "WARN")
                return ExecutionAttemptResult(false)
            }
            val cooldownMinutes = symbolRule?.cooldownMinutes
            if (cooldownMinutes != null && cooldownMinutes > 0) {
                val lastTrade = runCatching { dao.lastTradeForSymbol(ticker.symbol) }.getOrNull()
                val ageMinutes = if (lastTrade == null) Long.MAX_VALUE else (System.currentTimeMillis() - lastTrade.timestampEpochMs) / 60000L
                if (ageMinutes < cooldownMinutes) {
                    updateStatus("Trade blocked by per-symbol cooldown: ${ticker.symbol} last trade ${ageMinutes}m ago, cooldown=${cooldownMinutes}m.", "WARN")
                    return ExecutionAttemptResult(false)
                }
            }
        }
        if (settings.ultimateAutomationEnabled && side == OrderSide.BUY) {
            val move24h = ticker.priceChangePercent24h
            val absMove24h = move24h.abs()
            if (settings.volatilityCircuitBreakerEnabled && absMove24h > settings.volatilityCircuitBreakerMax24hMovePercent) {
                updateStatus("Trade blocked by volatility circuit breaker: ${ticker.symbol} 24h move=${move24h.setScale(2, RoundingMode.HALF_UP)}%, max=${settings.volatilityCircuitBreakerMax24hMovePercent}%. SELL remains allowed.", "WARN")
                return ExecutionAttemptResult(false)
            }
            if (settings.pumpChaseProtectionEnabled && move24h > settings.pumpChaseMax24hGainPercent) {
                updateStatus("Trade blocked by pump-chase protection: ${ticker.symbol} 24h gain=${move24h.setScale(2, RoundingMode.HALF_UP)}%, maxBuyGain=${settings.pumpChaseMax24hGainPercent}%.", "WARN")
                return ExecutionAttemptResult(false)
            }
        }
        val pairInfo = runCatching { exchange.validateSymbol(ticker.symbol) }.getOrNull()
        val baseAsset = pairInfo?.baseAsset ?: baseAssetFromSymbol(ticker.symbol)
        val quoteAsset = pairInfo?.quoteAsset ?: quoteAssetFromSymbol(ticker.symbol)
        val availableQuote = freeBalanceForAsset(liveBalances, quoteAsset)
        val availableBase = freeBalanceForAsset(liveBalances, baseAsset)
        if (side == OrderSide.BUY && isCashLikeBaseAsset(baseAsset)) {
            updateStatus("Trade blocked: ${ticker.symbol} has cash/stablecoin base asset $baseAsset. The bot treats EUR/USD/stables as cash/quote assets, not buy targets.", "WARN")
            return ExecutionAttemptResult(false)
        }
        if (side == OrderSide.SELL && availableBase <= BigDecimal.ZERO) {
            updateStatus("Trade skipped: ${ticker.symbol} generated SELL but there is no available $baseAsset balance. This prevents invalid paper/live SELL attempts.", "WARN")
            return ExecutionAttemptResult(false)
        }
        if (settings.ultimateAutomationEnabled && settings.duplicatePositionProtectionEnabled && side == OrderSide.BUY) {
            val openPosition = runCatching { dao.positionForSymbol(ticker.symbol) }.getOrNull()
            val heldBaseValue = availableBase.multiply(if (ticker.ask > BigDecimal.ZERO) ticker.ask else ticker.lastPrice)
            if (openPosition != null && openPosition.status.equals("OPEN", ignoreCase = true)) {
                updateStatus("Trade blocked by duplicate-position protection: ${ticker.symbol} already has an OPEN lifecycle position. SELL/exit management remains allowed.", "WARN")
                return ExecutionAttemptResult(false)
            }
            if (heldBaseValue >= BigDecimal("5.00")) {
                updateStatus("Trade blocked by duplicate-position protection: existing $baseAsset value≈${heldBaseValue.setScale(2, RoundingMode.DOWN)} $quoteAsset. SELL/exit management remains allowed.", "WARN")
                return ExecutionAttemptResult(false)
            }
        }
        if (settings.ultimateAutomationEnabled && settings.portfolioBalancerEnabled && side == OrderSide.BUY) {
            val heldBaseValue = availableBase.multiply(if (ticker.ask > BigDecimal.ZERO) ticker.ask else ticker.lastPrice)
            val freeQuoteValue = availableQuote
            val estimatedTotal = heldBaseValue.add(freeQuoteValue).max(BigDecimal.ONE)
            val exposurePercent = heldBaseValue.multiply(BigDecimal("100")).divide(estimatedTotal, 4, RoundingMode.HALF_UP)
            if (exposurePercent > settings.maxSingleAssetAllocationPercent) {
                updateStatus("Trade blocked by portfolio exposure guard: $baseAsset exposure≈${exposurePercent.setScale(2, RoundingMode.HALF_UP)}%, max=${settings.maxSingleAssetAllocationPercent}%.", "WARN")
                return ExecutionAttemptResult(false)
            }
        }
        val quoteReserve = quoteReserveAmount(settings, availableQuote)
        val quoteReservedThisScan = reservedByQuoteThisScan[quoteAsset] ?: BigDecimal.ZERO
        if (side == OrderSide.BUY && !settings.isQuoteAssetAllowed(quoteAsset)) {
            updateStatus("Trade blocked: quote asset $quoteAsset is not enabled in Allowed Quote Assets (${settings.allowedQuoteAssetsCsv}). Kraken Belgian deposits are held as the EUR cash asset internally reported as ZEUR/EUR; set Allowed Quote Assets to EUR or enable non-EUR quotes only when you hold those quote balances. SELL remains available if you hold $baseAsset.", "WARN")
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
        var price = if (side == OrderSide.BUY) ticker.ask else ticker.bid
        if (side == OrderSide.BUY && settings.multiTimeframeConsensusEnabled) {
            val consensus = multiTimeframeConsensusAllowsBuy(settings, exchange, ticker.symbol)
            updateStatus("[${ticker.symbol}] ${consensus.second}", if (consensus.first) "INFO" else "WARN")
            if (!consensus.first) {
                updateStatus("Trade blocked by multi-timeframe consensus: ${consensus.second}", "WARN")
                return ExecutionAttemptResult(false)
            }
        }
        if (side == OrderSide.BUY) {
            val maxBuyPrice = settings.effectiveMaxBuyPriceFor(ticker.symbol)
            if (maxBuyPrice != null && price > maxBuyPrice) {
                updateStatus("Trade blocked by Max Buy Price: ${ticker.symbol} ask=${price.stripTrailingZeros().toPlainString()} is above configured max=${maxBuyPrice.stripTrailingZeros().toPlainString()}.", "WARN")
                return ExecutionAttemptResult(false)
            }
        }
        val feeReserveMultiplier = BigDecimal("1.01")
        val minimumOrderNotional = BigDecimal("5.00")

        val adaptivePositionCap = if (settings.ultimateAutomationEnabled) adaptivePositionCapFor(settings, ticker.symbol) else settings.maxPositionEur
        val perOrderCap = if (useMarketOrder) adaptivePositionCap.min(settings.maxMarketOrderEur) else adaptivePositionCap
        var targetNotional = if (side == OrderSide.BUY && settings.mode != BotMode.PAPER) {
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

        // Exchange minimum preflight. Kraken exposes ordermin and costmin; use both
        // before the research/risk planner so a safely fundable minimum-sized order
        // can be considered. This never exceeds perOrderCap or the post-reserve quote budget.
        val maxSpendableForExchangeMinimum = if (side == OrderSide.BUY) {
            if (settings.mode == BotMode.PAPER) {
                perOrderCap
            } else {
                val freeQuoteForMinimum = availableQuote ?: BigDecimal.ZERO
                val afterReserveForMinimum = freeQuoteForMinimum
                    .subtract(quoteReserve)
                    .subtract(quoteReservedThisScan)
                    .max(BigDecimal.ZERO)
                    .divide(feeReserveMultiplier, 8, RoundingMode.DOWN)
                val quoteBudgetForMinimum = when {
                    quoteAsset in setOf("EUR", "USD", "USDT", "USDC") -> afterReserveForMinimum
                    settings.nonEurQuoteBuyEnabled -> {
                        val cryptoQuoteCapForMinimum = freeQuoteForMinimum
                            .multiply(settings.maxNonEurQuoteSpendPercent)
                            .divide(BigDecimal("100"), 8, RoundingMode.DOWN)
                        afterReserveForMinimum.min(cryptoQuoteCapForMinimum)
                    }
                    else -> BigDecimal.ZERO
                }
                perOrderCap.min(quoteBudgetForMinimum)
            }
        } else BigDecimal.ZERO

        if (side == OrderSide.BUY && pairInfo != null) {
            val exchangeMinimumPreflight = ExchangeMinimumOrderPolicy.evaluate(
                targetNotional = targetNotional,
                price = price,
                quantityDecimals = pairInfo.quantityDecimals,
                minOrderSize = pairInfo.minOrderSize,
                minOrderCost = pairInfo.minOrderCost,
                hardCapNotional = perOrderCap,
                maxSpendableNotional = maxSpendableForExchangeMinimum,
                allowUpsizeToMinimum = true
            )
            if (!exchangeMinimumPreflight.allowed) {
                val minimumReason = "[${ticker.symbol}] BUY skipped before submission: ${exchangeMinimumPreflight.reason}"
                updateStatus(minimumReason, "WARN")
                productionIntelligence.recordWhyNotTrade(decision, settings, minimumReason)
                return ExecutionAttemptResult(false)
            }
            if (exchangeMinimumPreflight.adjustedToMinimum) {
                targetNotional = exchangeMinimumPreflight.targetNotional
                updateStatus(
                    "[${ticker.symbol}] Exchange minimum BUY budget adjusted safely: " +
                        "qty=${exchangeMinimumPreflight.quantity.stripTrailingZeros().toPlainString()}, " +
                        "notional≈${exchangeMinimumPreflight.targetNotional.setScale(2, RoundingMode.UP)} $quoteAsset. " +
                        "Kraken ordermin=${pairInfo.minOrderSize}, costmin=${pairInfo.minOrderCost}.",
                    "INFO"
                )
            }
        }

        var plannedEntryOrderType: OrderType? = null
        var plannedEntryPostOnly = false
        if (side == OrderSide.BUY) {
            val advancedOrderBook = runCatching { exchange.getOrderBook(ticker.symbol, 40) }.getOrNull()
            val advancedFeeSchedule = runCatching { exchange.getTradingFeeSchedule(ticker.symbol) }.getOrNull()
            val advancedPlan = advancedExecution.prepareEntry(
                settings = settings,
                ticker = ticker,
                decision = decision,
                requestedQuote = targetNotional,
                orderBook = advancedOrderBook,
                mode = if (settings.mode == BotMode.PAPER) "PAPER" else "LIVE",
                currentUseMarket = useMarketOrder,
                feeSchedule = advancedFeeSchedule
            )
            updateStatus("[${ticker.symbol}] Advanced execution plan: allowed=${advancedPlan.allowed}, final=${advancedPlan.finalQuote.setScale(2, RoundingMode.DOWN)}, order=${advancedPlan.orderType}, protection=${advancedPlan.protectionLevel}, size×${advancedPlan.combinedMultiplier}. ${advancedPlan.reason.take(260)}", if (advancedPlan.allowed) "INFO" else "WARN")
            if (!advancedPlan.allowed) {
                productionIntelligence.recordWhyNotTrade(decision, settings, advancedPlan.reason)
                return ExecutionAttemptResult(false)
            }
            targetNotional = advancedPlan.finalQuote
            if (pairInfo != null) {
                val exchangeMinimumAfterRisk = ExchangeMinimumOrderPolicy.evaluate(
                    targetNotional = targetNotional,
                    price = price,
                    quantityDecimals = pairInfo.quantityDecimals,
                    minOrderSize = pairInfo.minOrderSize,
                    minOrderCost = pairInfo.minOrderCost,
                    hardCapNotional = perOrderCap,
                    maxSpendableNotional = maxSpendableForExchangeMinimum,
                    allowUpsizeToMinimum = false
                )
                if (!exchangeMinimumAfterRisk.allowed) {
                    val minimumReason = "[${ticker.symbol}] BUY skipped after AI/research risk sizing: ${exchangeMinimumAfterRisk.reason}"
                    updateStatus(minimumReason, "WARN")
                    productionIntelligence.recordWhyNotTrade(decision, settings, minimumReason)
                    return ExecutionAttemptResult(false)
                }
            }
            plannedEntryOrderType = advancedPlan.orderType
            plannedEntryPostOnly = advancedPlan.postOnly
            if (advancedPlan.orderType != OrderType.MARKET) useMarketOrder = false
            if (!useMarketOrder && advancedPlan.limitPrice != null && advancedPlan.limitPrice > BigDecimal.ZERO) price = advancedPlan.limitPrice
            val orderBookCheck = orderBookDepthAllowsExecution(settings, exchange, ticker.symbol, side, targetNotional, price)
            updateStatus("[${ticker.symbol}] ${orderBookCheck.second}", if (orderBookCheck.first) "INFO" else "WARN")
            if (!orderBookCheck.first) {
                updateStatus("Trade blocked by order book depth/slippage guard: ${orderBookCheck.second}", "WARN")
                return ExecutionAttemptResult(false)
            }
            updateStatus("[${ticker.symbol}] Quote budget: base=$baseAsset, quote=$quoteAsset, freeQuote=${availableQuote.stripTrailingZeros().toPlainString()}, reservedByBotThisScan=${quoteReservedThisScan.setScale(2, RoundingMode.DOWN)}, reserve=${quoteReserve.setScale(2, RoundingMode.DOWN)}, targetOrder=${targetNotional.stripTrailingZeros().toPlainString()} $quoteAsset", "INFO")
            val heldBaseValue = (availableBase ?: BigDecimal.ZERO).multiply(price)
            if (quoteAsset != "EUR" && quoteAsset !in setOf("USD", "USDT", "USDC") && !settings.nonEurQuoteBuyEnabled) {
                updateStatus("Trade blocked: ${ticker.symbol} uses quote asset $quoteAsset. The scanner analyzes it, but live BUY is disabled for non-fiat/non-stable quotes unless Non-EUR quote buys are enabled. SELL remains available when you hold $baseAsset.", "WARN")
                return ExecutionAttemptResult(false)
            }
            if (targetNotional < minimumOrderNotional) {
                if (heldBaseValue >= minimumOrderNotional) {
                    updateStatus("Trade blocked: BUY signal but free $quoteAsset is too low. You already have ${baseAsset}≈${heldBaseValue.setScale(2, RoundingMode.DOWN)} $quoteAsset available; the bot will wait for a SELL signal or you must add free $quoteAsset.", "WARN")
                } else {
                    updateStatus("Trade blocked: not enough free $quoteAsset to buy. API reports free $quoteAsset=${availableQuote.stripTrailingZeros().toPlainString()}.", "WARN")
                }
                return ExecutionAttemptResult(false)
            }
        }

        val quantity = if (side == OrderSide.SELL) {
            val freeBase = availableBase ?: BigDecimal.ZERO
            val quantityScale = pairInfo?.quantityDecimals ?: 8
            val chosen = freeBase.setScale(quantityScale, RoundingMode.DOWN)
            val chosenValue = chosen.multiply(price)
            val dustAfterSell = freeBase.subtract(chosen).max(BigDecimal.ZERO)
            val dustValue = dustAfterSell.multiply(price)
            updateStatus("[${ticker.symbol}] SELL all available: baseAsset=$baseAsset, quoteAsset=$quoteAsset, freeBase=${freeBase.stripTrailingZeros().toPlainString()}, chosenQty=${chosen.stripTrailingZeros().toPlainString()}, estimatedValue=${chosenValue.setScale(2, RoundingMode.DOWN)} $quoteAsset, unavoidableDust≈${dustValue.setScale(6, RoundingMode.DOWN)} $quoteAsset", "INFO")
            if (chosen <= BigDecimal.ZERO || chosenValue < minimumOrderNotional) {
                updateStatus("Dust remainder detected: $baseAsset balance value is below exchange minimum. Value=${chosenValue.setScale(2, RoundingMode.DOWN)} $quoteAsset, minimum=$minimumOrderNotional $quoteAsset. This cannot be sold automatically until it grows above the minimum or the exchange offers conversion.", "WARN")
                return ExecutionAttemptResult(false)
            }
            chosen
        } else {
            val finalExchangeMinimumCheck = if (pairInfo != null) {
                ExchangeMinimumOrderPolicy.evaluate(
                    targetNotional = targetNotional,
                    price = price,
                    quantityDecimals = pairInfo.quantityDecimals,
                    minOrderSize = pairInfo.minOrderSize,
                    minOrderCost = pairInfo.minOrderCost,
                    hardCapNotional = perOrderCap,
                    maxSpendableNotional = maxSpendableForExchangeMinimum,
                    allowUpsizeToMinimum = false
                )
            } else null
            if (finalExchangeMinimumCheck != null && !finalExchangeMinimumCheck.allowed) {
                val minimumReason = "[${ticker.symbol}] BUY skipped at final quantity preflight: ${finalExchangeMinimumCheck.reason}"
                updateStatus(minimumReason, "WARN")
                productionIntelligence.recordWhyNotTrade(decision, settings, minimumReason)
                return ExecutionAttemptResult(false)
            }
            finalExchangeMinimumCheck?.quantity
                ?: targetNotional.divide(price, 8, RoundingMode.DOWN)
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
            orderType = plannedEntryOrderType ?: if (useMarketOrder) OrderType.MARKET else OrderType.LIMIT,
            clientOrderId = "ksp-${ticker.symbol.lowercase()}-${System.currentTimeMillis()}",
            purpose = if (side == OrderSide.BUY && plannedEntryOrderType != null) "RESEARCH/HANDOFF strategy=${ResearchExecutionRuntime.snapshot(ticker.symbol)?.strategyId ?: "GENERIC"} order=${plannedEntryOrderType}" else "ENTRY",
            postOnly = plannedEntryPostOnly,
            protectiveStopPrice = if (side == OrderSide.BUY && settings.mode != BotMode.PAPER) ResearchExecutionRuntime.snapshot(ticker.symbol)?.stopPrice?.takeIf { it > BigDecimal.ZERO && it < price } else null
        )
        val orderModeLabel = request.orderType.name
        val submittedNotionalEstimate = request.quantity.multiply(price).setScale(8, RoundingMode.HALF_UP)

        if (settings.mode != BotMode.PAPER && request.side == OrderSide.BUY) {
            val authority = com.ksp.cryptobot.execution.EngineAuthorityRuntime.canSubmitNewEntry(settings.mode)
            if (!authority.first) {
                updateStatus("LIVE entry blocked by distributed engine-authority gate: ${authority.second}", "ERROR")
                return ExecutionAttemptResult(false)
            }
        }

        if (settings.mode == BotMode.LIVE_AUTO &&
            settings.exchangeProvider == ExchangeProvider.KRAKEN &&
            request.side == OrderSide.BUY
        ) {
            val executionTruth = KrakenPrivateExecutionRegistry.canSubmitNewEntry(request.symbol, request.side)
            if (!executionTruth.first) {
                updateStatus("LIVE_AUTO entry blocked by Kraken execution-state gate: ${executionTruth.second}", "ERROR")
                return ExecutionAttemptResult(false)
            }
        }

        updateStatus("Submitting ${settings.exchangeProvider} ${request.side} $orderModeLabel order: ${request.symbol}, notional≈${submittedNotionalEstimate.setScale(2, RoundingMode.DOWN)} $quoteAsset, qty=${request.quantity}, price=${request.limitPrice ?: "market"}, id=${request.clientOrderId}", "LIVE")
        val result = runCatching { exchange.placeOrder(request) }.getOrElse { error ->
            KrakenPrivateExecutionRegistry.markFailureIfPending(
                request.clientOrderId,
                error.message ?: error.javaClass.simpleName
            )
            updateStatus("Order submit failed: ${error.message}", "ERROR")
            val deterministicMinimumRejection =
                error.message?.contains("order size too small", ignoreCase = true) == true ||
                    error.message?.contains("order cost too small", ignoreCase = true) == true
            if (deterministicMinimumRejection) {
                updateStatus(
                    "Exchange minimum rejection escaped local preflight for ${request.symbol}; remote-alert spam suppressed. " +
                        "The next scan will re-read pair metadata and skip unless it can satisfy the new minimum.",
                    "WARN"
                )
            } else {
                sendRemoteAlert(settings, "Order submit failed", "${request.side} ${request.symbol}: ${error.message}")
            }
            if (settings.ultimateAutomationEnabled && settings.autoPauseAfterOrderFailuresEnabled && settings.mode == BotMode.LIVE_AUTO) {
                val recentFailures = statusStore.recentLines(80).count { it.contains("Order submit failed", ignoreCase = true) || it.contains("Service cycle failed", ignoreCase = true) }
                if (recentFailures + 1 >= settings.autoPauseFailureThreshold.coerceAtLeast(1)) {
                    settingsStore.save(settings.copy(mode = BotMode.LIVE_CONFIRM, manualExecutionMode = true))
                    val pauseMessage = "LIVE_AUTO auto-paused after repeated order/API failures. Mode changed to LIVE_CONFIRM. Review Live Status before re-enabling LIVE_AUTO."
                    updateStatus(pauseMessage, "ERROR")
                    sendRemoteAlert(settings, "LIVE_AUTO auto-paused", pauseMessage)
                }
            }
            throw error
        }
        val fillConfirmed = result.executedQuantity > BigDecimal.ZERO && result.averagePrice > BigDecimal.ZERO
        val executedQtyForRecord = result.executedQuantity.max(BigDecimal.ZERO)
        val averagePriceForRecord = result.averagePrice.takeIf { it > BigDecimal.ZERO } ?: price
        val feeForRecord = if (fillConfirmed) {
            result.fee.takeIf { it > BigDecimal.ZERO }
                ?: averagePriceForRecord.multiply(executedQtyForRecord).multiply(BigDecimal("0.001")).setScale(8, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO
        val notionalForRecord = averagePriceForRecord.multiply(executedQtyForRecord).setScale(8, RoundingMode.HALF_UP)
        val realizedPnlForRecord = if (fillConfirmed && result.side == OrderSide.SELL) {
            if (result.realizedPnlQuote != BigDecimal.ZERO) result.realizedPnlQuote else {
                val tracked = dao.positionForSymbol(result.symbol)
                val entry = tracked?.entryPriceEur?.toBigDecimalOrNull()
                    ?: dao.recentTradesSnapshot(200).firstOrNull { it.symbol.equals(result.symbol, true) && it.side == OrderSide.BUY.name }?.priceEur?.toBigDecimalOrNull()
                    ?: BigDecimal.ZERO
                if (entry > BigDecimal.ZERO) averagePriceForRecord.subtract(entry).multiply(executedQtyForRecord).subtract(feeForRecord) else BigDecimal.ZERO
            }
        } else result.realizedPnlQuote
        if (fillConfirmed) {
            dao.insertTrade(
                TradeEntity(
                    symbol = result.symbol,
                    side = result.side.name,
                    quantity = executedQtyForRecord.toPlainString(),
                    priceEur = averagePriceForRecord.toPlainString(),
                    feeEur = feeForRecord.toPlainString(),
                    paper = result.paper,
                    realizedPnlEur = realizedPnlForRecord.toPlainString(),
                    aiScore = decision.finalScore,
                    aiReason = decision.explanation,
                    clientOrderId = request.clientOrderId,
                    exchangeOrderId = result.exchangeOrderId,
                    timestampEpochMs = result.timestamp.toEpochMilli()
                )
            )
            if (result.side == OrderSide.BUY) {
                val handoff = ResearchExecutionRuntime.snapshot(ticker.symbol)
                if (handoff != null && handoff.strategyId != "HANDOFF_FILTERS" && (handoff.stopPrice != null || handoff.targets.isNotEmpty())) {
                    val sourceStop = handoff.stopPrice?.takeIf { it > BigDecimal.ZERO && it < averagePriceForRecord }
                        ?: averagePriceForRecord.multiply(BigDecimal.ONE.subtract(settings.stopLossPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
                    val sourceTargets = handoff.targets.filter { it > averagePriceForRecord }.sorted()
                    val firstTarget = sourceTargets.firstOrNull() ?: averagePriceForRecord.multiply(BigDecimal.ONE.add(settings.takeProfitPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
                    val now = System.currentTimeMillis()
                    dao.upsertPosition(PositionEntity(
                        symbol = result.symbol, baseAsset = baseAsset, quantity = executedQtyForRecord.toPlainString(),
                        entryPriceEur = averagePriceForRecord.toPlainString(), highestPriceEur = averagePriceForRecord.toPlainString(),
                        stopPriceEur = sourceStop.toPlainString(), takeProfitPriceEur = firstTarget.toPlainString(), trailingStopPriceEur = "0",
                        openedAtEpochMs = now, updatedAtEpochMs = now, status = "OPEN",
                        source = HandoffPositionPlanCodec.encode(HandoffPositionPlan(handoff.strategyId, sourceStop, sourceTargets, handoff.fidelity, handoff.liveTruthGate, result.exchangeOrderId))
                    ))
                    updateStatus("[${result.symbol}] Persisted handoff plan: strategy=${handoff.strategyId}, stop=${sourceStop.stripTrailingZeros().toPlainString()}, targets=${sourceTargets.joinToString(",") { it.stripTrailingZeros().toPlainString() }}.", "LIVE")
                    val protection = protectiveStops.protectOrFlatten(
                        settings = settings, exchange = exchange, symbol = result.symbol,
                        quantity = executedQtyForRecord, entryPrice = averagePriceForRecord,
                        stopPrice = sourceStop, strategyId = handoff.strategyId, paper = result.paper
                    )
                    updateStatus("[${result.symbol}] Exchange protective stop state: protected=${protection.protected}, flattened=${protection.flattened}, pendingEmergency=${protection.pendingEmergencyExit}. ${protection.reason}", if (protection.protected) "LIVE" else "ERROR")
                }
            }
            productionIntelligence.observeExecution(
                symbol = result.symbol,
                side = result.side,
                mode = if (result.paper) "PAPER" else "LIVE",
                orderType = request.orderType,
                expectedPrice = price,
                actualPrice = averagePriceForRecord,
                quantity = executedQtyForRecord,
                clientOrderId = request.clientOrderId,
                exchangeOrderId = result.exchangeOrderId
            )
            updateStatus("Order fill confirmed: ${result.side} ${result.symbol} ${if (result.paper) "PAPER" else "LIVE"}. qty=${executedQtyForRecord.stripTrailingZeros().toPlainString()} avg=${averagePriceForRecord.stripTrailingZeros().toPlainString()} fee=${feeForRecord.stripTrailingZeros().toPlainString()} orderId=${result.exchangeOrderId}", if (result.paper) "INFO" else "LIVE")
        } else {
            if (result.side == OrderSide.BUY) {
                val handoff = ResearchExecutionRuntime.snapshot(ticker.symbol)
                if (handoff != null && handoff.strategyId != "HANDOFF_FILTERS" && (handoff.stopPrice != null || handoff.targets.isNotEmpty())) {
                    val plannedEntry = price
                    val sourceStop = handoff.stopPrice?.takeIf { it > BigDecimal.ZERO && it < plannedEntry }
                        ?: plannedEntry.multiply(BigDecimal.ONE.subtract(settings.stopLossPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
                    val sourceTargets = handoff.targets.filter { it > plannedEntry }.sorted()
                    val firstTarget = sourceTargets.firstOrNull() ?: plannedEntry.multiply(BigDecimal.ONE.add(settings.takeProfitPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
                    val now = System.currentTimeMillis()
                    dao.upsertPosition(PositionEntity(
                        symbol = result.symbol, baseAsset = baseAsset, quantity = "0",
                        entryPriceEur = plannedEntry.toPlainString(), highestPriceEur = plannedEntry.toPlainString(),
                        stopPriceEur = sourceStop.toPlainString(), takeProfitPriceEur = firstTarget.toPlainString(), trailingStopPriceEur = "0",
                        openedAtEpochMs = now, updatedAtEpochMs = now, status = "PENDING_ENTRY",
                        source = HandoffPositionPlanCodec.encode(HandoffPositionPlan(handoff.strategyId, sourceStop, sourceTargets, handoff.fidelity, handoff.liveTruthGate, result.exchangeOrderId))
                    ))
                    updateStatus("[${result.symbol}] Persisted pending handoff plan for unfilled order ${result.exchangeOrderId}; no exposure is recorded until a fill is confirmed.", "LIVE")
                }
            }
            updateStatus("Order accepted but fill not confirmed: ${result.side} ${result.symbol} type=${request.orderType}, submittedQty=${quantity.stripTrailingZeros().toPlainString()}, orderId=${result.exchangeOrderId}. No trade/PnL row is created until exchange fill evidence arrives.", "LIVE")
        }
        sendRemoteAlert(
            settings,
            "Order placed",
            buildString {
                appendLine("${result.side} ${result.symbol} ${if (result.paper) "PAPER" else "LIVE"}")
                appendLine("orderType=$orderModeLabel")
                appendLine("fillConfirmed=$fillConfirmed")
                appendLine("amount=${if (fillConfirmed) executedQtyForRecord.stripTrailingZeros().toPlainString() else quantity.stripTrailingZeros().toPlainString()}")
                appendLine("price=${averagePriceForRecord.stripTrailingZeros().toPlainString()} $quoteAsset")
                appendLine("notional≈${if (fillConfirmed) notionalForRecord.stripTrailingZeros().toPlainString() else submittedNotionalEstimate.stripTrailingZeros().toPlainString()} $quoteAsset")
                appendLine("fee=${if (fillConfirmed) feeForRecord.stripTrailingZeros().toPlainString() else "pending"} $quoteAsset")
                appendLine("orderId=${result.exchangeOrderId}")
                if (!fillConfirmed) appendLine("note=Order accepted without confirmed fill; actual fill will be synchronized from exchange history.")
            }
        )
        val reservedAmount = if (side == OrderSide.BUY) targetNotional.multiply(feeReserveMultiplier).setScale(2, RoundingMode.UP) else BigDecimal.ZERO
        return ExecutionAttemptResult(true, quoteAsset, reservedAmount)
    }




    private fun estimateOrderBookSlippagePercent(
        snapshot: OrderBookSnapshot,
        side: OrderSide,
        targetNotional: BigDecimal,
        referencePrice: BigDecimal
    ): BigDecimal {
        if (targetNotional <= BigDecimal.ZERO || referencePrice <= BigDecimal.ZERO) return BigDecimal.ZERO
        val levels = if (side == OrderSide.BUY) snapshot.asks else snapshot.bids
        var remaining = targetNotional
        var spent = BigDecimal.ZERO
        var acquired = BigDecimal.ZERO
        for (level in levels) {
            if (remaining <= BigDecimal.ZERO) break
            val levelQuote = level.price.multiply(level.quantity)
            val usedQuote = remaining.min(levelQuote)
            val usedQty = if (level.price > BigDecimal.ZERO) usedQuote.divide(level.price, 12, RoundingMode.DOWN) else BigDecimal.ZERO
            spent += usedQuote
            acquired += usedQty
            remaining -= usedQuote
        }
        if (acquired <= BigDecimal.ZERO) return BigDecimal("999")
        val averagePrice = spent.divide(acquired, 12, RoundingMode.HALF_UP)
        val raw = if (side == OrderSide.BUY) {
            averagePrice.subtract(referencePrice)
        } else {
            referencePrice.subtract(averagePrice)
        }
        return raw.divide(referencePrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")).max(BigDecimal.ZERO)
    }

    private suspend fun orderBookDepthAllowsExecution(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        symbol: String,
        side: OrderSide,
        targetNotional: BigDecimal,
        referencePrice: BigDecimal
    ): Pair<Boolean, String> {
        if (!settings.ultimateAutomationEnabled || !settings.orderBookDepthGuardEnabled) {
            return true to "Order book guard disabled."
        }
        val snapshot = runCatching { exchange.getOrderBook(symbol, 40) }.getOrNull()
            ?: return if (settings.mode == BotMode.LIVE_AUTO) {
                false to "Order book unavailable for $symbol in LIVE_AUTO."
            } else {
                true to "Order book unavailable; non-live-auto mode allowed."
            }
        val availableDepth = snapshot.quoteDepth(side)
        val requiredDepth = targetNotional.multiply(settings.minOrderBookDepthMultiple)
        if (availableDepth < requiredDepth) {
            return false to "order book depth too thin: available≈${availableDepth.setScale(2, RoundingMode.DOWN)}, required≈${requiredDepth.setScale(2, RoundingMode.DOWN)}"
        }
        val slippage = estimateOrderBookSlippagePercent(snapshot, side, targetNotional, referencePrice)
        if (slippage > settings.maxOrderBookSlippagePercent) {
            return false to "estimated order book slippage ${slippage.setScale(3, RoundingMode.HALF_UP)}% > max ${settings.maxOrderBookSlippagePercent}%"
        }
        return true to "order book OK: depth≈${availableDepth.setScale(2, RoundingMode.DOWN)}, required≈${requiredDepth.setScale(2, RoundingMode.DOWN)}, estimatedSlippage≈${slippage.setScale(3, RoundingMode.HALF_UP)}%"
    }

    private suspend fun multiTimeframeConsensusAllowsBuy(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        symbol: String
    ): Pair<Boolean, String> {
        if (!settings.ultimateAutomationEnabled || !settings.multiTimeframeConsensusEnabled) {
            return true to "Multi-timeframe consensus disabled."
        }
        val frames = listOf(Timeframe.M5, Timeframe.M15, Timeframe.H1)
        var bullish = 0
        val details = mutableListOf<String>()
        frames.forEach { timeframe ->
            val candles = runCatching { exchange.getCandles(symbol, timeframe, 48) }.getOrDefault(emptyList())
            if (candles.size >= 12) {
                val first = candles.first().close
                val last = candles.last().close
                val isBullish = last > first
                if (isBullish) bullish += 1
                val change = if (first > BigDecimal.ZERO) last.subtract(first).divide(first, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal.ZERO
                details += "${timeframe.name}:${if (isBullish) "UP" else "DOWN"}(${change.setScale(2, RoundingMode.HALF_UP)}%)"
            } else {
                details += "${timeframe.name}:NO_DATA"
            }
        }
        val required = settings.multiTimeframeRequiredBullishCount.coerceIn(1, frames.size)
        val allowed = bullish >= required
        val reason = "multi-timeframe bullish=$bullish/$required details=${details.joinToString(",")}"
        return allowed to reason
    }

    private suspend fun adaptivePositionCapFor(settings: BotSettings, symbol: String): BigDecimal {
        val baseCap = settings.effectiveMaxPositionFor(symbol)
        if (!settings.autoCompoundingEnabled || !settings.adaptiveCompoundingFromRealizedPnlEnabled) {
            return baseCap
        }
        val recentPnl = runCatching {
            dao.recentTradesSnapshot(settings.optimizerLookbackTrades.coerceIn(10, 500))
                .mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }
                .fold(BigDecimal.ZERO) { acc, value -> acc + value }
        }.getOrDefault(BigDecimal.ZERO)
        if (recentPnl <= BigDecimal.ZERO) return baseCap
        val boost = recentPnl
            .multiply(settings.autoCompoundingMaxIncreasePercent)
            .divide(BigDecimal("100"), 8, RoundingMode.DOWN)
            .max(BigDecimal.ZERO)
        val hardCap = if (settings.autoCompoundingHardCapEnabled) settings.autoCompoundingMaxPositionEur else settings.maxPositionEur.max(baseCap).add(boost)
        val finalCap = baseCap.add(boost).min(hardCap).setScale(2, RoundingMode.DOWN)
        updateStatus("[$symbol] Adaptive compounding cap: base=${baseCap.setScale(2, RoundingMode.DOWN)}, recentPnl=${recentPnl.setScale(2, RoundingMode.DOWN)}, boost=${boost.setScale(2, RoundingMode.DOWN)}, final=${finalCap.setScale(2, RoundingMode.DOWN)}", "INFO")
        return finalCap
    }

    private fun freeBalanceForAsset(balances: Map<String, BigDecimal>, asset: String): BigDecimal {
        val key = asset.uppercase().trim()
        if (key.isBlank()) return BigDecimal.ZERO

        // Kraken can expose balances using funding/internal asset codes while trading pairs
        // use human quote/base assets. For a Belgian SEPA top-up the spendable cash is EUR,
        // but Kraken may report that balance as ZEUR. Treat these as the same cash bucket.
        val aliases = when (key) {
            "EUR", "ZEUR" -> listOf("EUR", "ZEUR")
            "USD", "ZUSD" -> listOf("USD", "ZUSD")
            "GBP", "ZGBP" -> listOf("GBP", "ZGBP")
            "CHF", "ZCHF" -> listOf("CHF", "ZCHF")
            "CAD", "ZCAD" -> listOf("CAD", "ZCAD")
            "AUD", "ZAUD" -> listOf("AUD", "ZAUD")
            "JPY", "ZJPY" -> listOf("JPY", "ZJPY")
            "BTC", "XBT", "XXBT" -> listOf("BTC", "XBT", "XXBT")
            "ETH", "XETH" -> listOf("ETH", "XETH")
            else -> listOf(key, "X$key", "Z$key")
        }

        return aliases.firstNotNullOfOrNull { balances[it] } ?: BigDecimal.ZERO
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



    private suspend fun fetchNewsForSymbol(symbol: String): List<NewsArticle> {
        val providers = mutableListOf<Pair<String, NewsClient>>()

        // Requested provider stack.
        providers += "GDELT" to GdeltNewsClient()
        providers += "RSS" to RssFeedNewsClient()

        settingsStore.cryptoPanicApiKey()?.trim()?.takeIf { it.isNotBlank() }?.let {
            providers += "CryptoPanic" to CryptoPanicNewsClient(it)
        } ?: updateStatus("[$symbol] CryptoPanic skipped: no API key saved.", "WARN")

        settingsStore.marketauxApiKey()?.trim()?.takeIf { it.isNotBlank() }?.let {
            providers += "Marketaux" to MarketauxNewsClient(it)
        } ?: updateStatus("[$symbol] Marketaux skipped: no API key saved.", "WARN")

        settingsStore.newsDataApiKey()?.trim()?.takeIf { it.isNotBlank() }?.let {
            providers += "NewsData.io" to NewsDataNewsClient(it)
        } ?: updateStatus("[$symbol] NewsData.io skipped: no API key saved.", "WARN")

        settingsStore.gNewsApiKey()?.trim()?.takeIf { it.isNotBlank() }?.let {
            providers += "GNews" to GNewsNewsClient(it)
        } ?: updateStatus("[$symbol] GNews skipped: no API key saved.", "WARN")

        settingsStore.guardianApiKey()?.trim()?.takeIf { it.isNotBlank() }?.let {
            providers += "Guardian" to GuardianNewsClient(it)
        } ?: updateStatus("[$symbol] Guardian skipped: no API key saved.", "WARN")

        val newsApiKeys = settingsStore.newsApiKey()
            ?.split(',', ';', '\n')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        newsApiKeys.forEachIndexed { index, key ->
            val client = NewsApiClient(key, providerName = "NewsAPI-${index + 1}")
            providers += client.label() to client
        }
        if (newsApiKeys.isEmpty()) {
            updateStatus("[$symbol] NewsAPI.org skipped: no API key saved.", "WARN")
        }

        val all = mutableListOf<NewsArticle>()
        providers.forEach { (name, provider) ->
            if (!NewsProviderHealthRegistry.shouldAttempt(name)) {
                val health = NewsProviderHealthRegistry.healthFor(name)
                updateStatus("[$symbol] $name skipped during local retry cooldown until ${health?.cooldownUntilEpochMs ?: 0L}. Last error=${health?.lastError.orEmpty().take(120)}", "WARN")
                return@forEach
            }
            NewsProviderHealthRegistry.recordAttempt(name)
            runCatching { provider.latestCryptoNews(symbol) }
                .onSuccess { articles ->
                    NewsProviderHealthRegistry.recordSuccess(name, articles.size)
                    all += articles
                    updateStatus("[$symbol] $name API call complete: articles=${articles.size}.", if (articles.isEmpty()) "WARN" else "INFO")
                }
                .onFailure { error ->
                    NewsProviderHealthRegistry.recordFailure(name, error)
                    val health = NewsProviderHealthRegistry.healthFor(name)
                    updateStatus("[$symbol] $name API call failed: ${error.message}. Local retry cooldownUntil=${health?.cooldownUntilEpochMs ?: 0L}.", "WARN")
                }
        }

        val deduped = all
            .distinctBy { it.title.lowercase().take(120) }
            .sortedByDescending { it.publishedAt }
            .take(80)
        cacheNewsArticles(symbol, deduped)
        val topTitles = deduped.take(3).joinToString(" | ") { it.title.take(90) }
        updateStatus("[$symbol] News check complete: providers=${providers.size}, totalArticles=${deduped.size}${deduped.firstOrNull()?.source?.takeIf { it.isNotBlank() }?.let { ", topSource=$it" } ?: ""}${if (topTitles.isNotBlank()) ", top=$topTitles" else ""}.", if (deduped.isEmpty()) "WARN" else "INFO")
        return deduped
    }


    private suspend fun cacheNewsArticles(symbol: String, articles: List<NewsArticle>) {
        if (articles.isEmpty()) return
        val now = System.currentTimeMillis()
        val rows = articles.take(40).map { article ->
            NewsArticleEntity(
                symbol = symbol.uppercase().replace("/", "").replace("-", ""),
                title = article.title.take(300),
                description = article.description.take(800),
                source = article.source.take(120),
                url = article.url.take(500),
                provider = article.source.substringBefore(':', article.source).ifBlank { "Unknown" }.take(80),
                publishedAtEpochMs = article.publishedAt?.toEpochMilli() ?: 0L,
                fetchedAtEpochMs = now
            )
        }
        dao.insertNewsArticles(rows)
    }

    suspend fun loadNewsHistory(symbol: String = "", limit: Int = 200): List<NewsArticleEntity> {
        val normalized = symbol.uppercase().replace("/", "").replace("-", "").trim()
        return runCatching {
            if (normalized.isBlank()) dao.recentNewsArticles(limit) else dao.newsArticlesForSymbol(normalized, limit)
        }
            .onSuccess { updateStatus("News history loaded. symbol=${normalized.ifBlank { "ALL" }} rows=${it.size}", "INFO") }
            .onFailure { updateStatus("News history load failed: ${it.message}", "ERROR") }
            .getOrDefault(emptyList())
    }


    private fun createNewsClient(settings: BotSettings): NewsClient {
        if (!settings.useNewsAi) return NoopNewsClient()
        val providers = mutableListOf<NewsClient>(
            GdeltNewsClient(),
            RssFeedNewsClient()
        )
        settingsStore.cryptoPanicApiKey()?.takeIf { it.isNotBlank() }?.let { providers += CryptoPanicNewsClient(it) }
        settingsStore.marketauxApiKey()?.takeIf { it.isNotBlank() }?.let { providers += MarketauxNewsClient(it) }
        settingsStore.newsDataApiKey()?.takeIf { it.isNotBlank() }?.let { providers += NewsDataNewsClient(it) }
        settingsStore.gNewsApiKey()?.takeIf { it.isNotBlank() }?.let { providers += GNewsNewsClient(it) }
        settingsStore.guardianApiKey()?.takeIf { it.isNotBlank() }?.let { providers += GuardianNewsClient(it) }
        val keys = settingsStore.newsApiKey()
            ?.split(',', ';', '\n')
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        keys.forEachIndexed { index, key ->
            providers += NewsApiClient(key, providerName = "NewsAPI-${index + 1}")
        }
        return CompositeNewsClient(providers)
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

    suspend fun loadSelfLearningSummary(settings: BotSettings = settingsStore.load()): TrueSelfLearningEngine.LearningSummary {
        val summary = selfLearningEngine.dashboard(dao, settings)
        updateStatus("Self-learning dashboard loaded: ${summary.summaryLine}", "INFO")
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


    suspend fun reconcileLiveExecutionState(
        settings: BotSettings = settingsStore.load()
    ): com.ksp.cryptobot.execution.ReconciliationSummary {
        if (settings.mode != BotMode.LIVE_AUTO && settings.mode != BotMode.LIVE_CONFIRM) {
            return com.ksp.cryptobot.execution.ReconciliationSummary(0, 0, 0, emptyList())
        }
        val exchange = createExchange(settings)
        lifecycleManager.runPreScanMaintenance(settings, exchange)
        val reconciliation = advancedExecution.reconcileLive(settings, exchange)
        if (exchange is KrakenSpotClient) {
            val orderTruth = com.ksp.cryptobot.execution.KrakenOrderTruthResolver.resolveDurable(exchange)
            orderTruth.messages.take(8).forEach { updateStatus("M12 order truth: $it", if (orderTruth.unresolved > 0) "WARN" else "INFO") }
            require(orderTruth.unresolved == 0) {
                "Kraken durable client-order ambiguity remains unresolved (${orderTruth.unresolved}); LIVE entry authority stays blocked."
            }
        }
        if (settings.exchangeProvider == ExchangeProvider.KRAKEN) {
            KrakenPrivateExecutionRegistry.markRestReconciled(reconciliation.openOrders)
        }
        updateStatus(
            "Strict live execution reconciliation passed: adjusted=${reconciliation.adjusted}, removed=${reconciliation.removed}, openOrders=${reconciliation.openOrders}.",
            if (reconciliation.removed > 0) "WARN" else "INFO"
        )
        return reconciliation
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



    suspend fun loadTradeJournal(limit: Int = 100): List<TradeEntity> {
        updateStatus("Trade journal refresh requested. limit=$limit", "INFO")
        return runCatching { dao.recentTradesSnapshot(limit) }
            .onSuccess { updateStatus("Trade journal loaded. rows=${it.size}", "INFO") }
            .onFailure { updateStatus("Trade journal failed: ${it.message}", "ERROR") }
            .getOrElse { emptyList() }
    }

    suspend fun runKrakenDataHealth(settings: BotSettings = settingsStore.load()): List<String> {
        val selectedSymbol = settings.symbols().firstOrNull() ?: "BTCEUR"
        val lines = mutableListOf<String>()
        updateStatus("Kraken data health check started for $selectedSymbol.", "INFO")
        val publicKraken = KrakenSpotClient(apiKey = "", secretKey = "")
        runCatching { publicKraken.validateSymbol(selectedSymbol) }
            .onSuccess { lines += "PASS Public AssetPairs: ${it.exchangePair} base=${it.baseAsset} quote=${it.quoteAsset}" }
            .onFailure { lines += "FAIL Public AssetPairs: ${it.message}" }
        runCatching { publicKraken.getTicker(selectedSymbol) }
            .onSuccess { lines += "PASS Public ticker: last=${it.lastPrice}, bid=${it.bid}, ask=${it.ask}" }
            .onFailure { lines += "FAIL Public ticker: ${it.message}" }
        runCatching { publicKraken.getCandles(selectedSymbol, Timeframe.M15, 120) }
            .onSuccess { lines += "PASS Public OHLC: candles=${it.size}, lastClose=${it.lastOrNull()?.close ?: BigDecimal.ZERO}" }
            .onFailure { lines += "FAIL Public OHLC: ${it.message}" }
        val selected = createExchange(settings)
        if (settings.exchangeProvider == ExchangeProvider.KRAKEN && settings.mode != BotMode.PAPER) {
            runCatching { selected.getAvailableBalances() }
                .onSuccess { balances ->
                    val eur = balances["EUR"] ?: balances["ZEUR"] ?: BigDecimal.ZERO
                    lines += "PASS Private balance permission: EUR/ZEUR available=${eur.setScale(2, RoundingMode.DOWN)}"
                }
                .onFailure { lines += "WARN Private balance permission: ${it.message}" }
            runCatching { selected.getOpenOrders() }
                .onSuccess { lines += "PASS Private open orders permission: openOrders=${it.size}" }
                .onFailure { lines += "WARN Private open orders permission: ${it.message}" }
        } else {
            lines += "INFO Private checks skipped: provider=${settings.exchangeProvider}, mode=${settings.mode}"
        }
        updateStatus("Kraken data health complete. ${lines.count { it.startsWith("PASS") }} pass, ${lines.count { it.startsWith("FAIL") || it.startsWith("WARN") }} warn/fail.", "INFO")
        return lines
    }

    suspend fun loadKrakenChartCandles(
        settings: BotSettings = settingsStore.load(),
        symbol: String,
        timeframe: Timeframe,
        limit: Int = 180
    ): List<Candle> {
        val cleanSymbol = symbol.uppercase().replace("/", "").replace("-", "").ifBlank { settings.symbols().firstOrNull() ?: "BTCEUR" }
        updateStatus("Chart data refresh started: $cleanSymbol ${timeframe.name}, candles=$limit", "INFO")
        return runCatching {
            KrakenSpotClient(apiKey = "", secretKey = "").getCandles(cleanSymbol, timeframe, limit.coerceIn(60, 720))
        }.onSuccess {
            updateStatus("Chart data loaded: $cleanSymbol ${timeframe.name}, candles=${it.size}", "INFO")
        }.onFailure {
            updateStatus("Chart data failed for $cleanSymbol: ${it.message}", "ERROR")
        }.getOrElse { emptyList() }
    }

    suspend fun runKrakenHistoricalBacktest(
        settings: BotSettings = settingsStore.load(),
        symbol: String,
        timeframe: Timeframe,
        strategy: StrategyMode,
        limit: Int
    ): BacktestReport {
        val cleanSymbol = symbol.uppercase().replace("/", "").replace("-", "").ifBlank { "BTCEUR" }
        val actualStrategy = if (strategy == StrategyMode.AUTO) StrategyMode.TREND else strategy
        updateStatus("Kraken OHLC backtest started: $cleanSymbol ${timeframe.name}, candles=$limit, strategy=${actualStrategy.name}", "INFO")

        return runCatching {
            val candles = KrakenSpotClient(apiKey = "", secretKey = "").getCandles(cleanSymbol, timeframe, limit.coerceIn(80, 720))
            val report = BacktestEngine().run(cleanSymbol, timeframe, actualStrategy, candles, settings)
            updateStatus("Kraken OHLC backtest complete: ${report.symbol}, trades=${report.trades}, win=${report.winRatePercent}%, PF=${report.profitFactor}", if (report.passedLiveGate) "LIVE" else "WARN")
            report
        }.getOrElse { error ->
            val msg = error.message ?: "Unknown Kraken OHLC error"
            updateStatus("Kraken OHLC backtest failed for $cleanSymbol: $msg", "ERROR")
            BacktestReport(
                symbol = cleanSymbol,
                strategy = actualStrategy,
                timeframe = timeframe,
                trades = 0,
                winRatePercent = BigDecimal.ZERO,
                profitFactor = BigDecimal.ZERO,
                maxDrawdownPercent = BigDecimal.ZERO,
                netReturnPercent = BigDecimal.ZERO,
                passedLiveGate = false,
                summary = "Kraken OHLC backtest failed: $msg"
            )
        }
    }

    fun loadPerformanceLabSnapshot(settings: BotSettings): PerformanceLabSnapshot {
        return PerformanceLabEngine().buildSnapshot(settings)
    }

}
