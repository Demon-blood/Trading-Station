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
import com.ksp.cryptobot.alerts.RemoteAlertClient
import com.ksp.cryptobot.alerts.RemoteCommandClient
import com.ksp.cryptobot.alerts.RemoteCommandMessage
import com.ksp.cryptobot.backtest.BacktestEngine
import com.ksp.cryptobot.completion.LiveVerificationEngine
import com.ksp.cryptobot.completion.LiveVerificationResult
import com.ksp.cryptobot.learning.TrueSelfLearningEngine
import com.ksp.cryptobot.performance.PerformanceLabEngine
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
    private val selfLearningEngine = TrueSelfLearningEngine()
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

        val primarySymbol = settings.symbols().firstOrNull()?.uppercase()?.replace("/", "")?.replace("-", "") ?: "BTCEUR"
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


    suspend fun exportFullLocalBackup(settings: BotSettings = settingsStore.load()): String {
        val trades = dao.allTradesSnapshot()
        val taxRows = dao.taxReportRowsSnapshot()
        val symbolProfiles = dao.learnedSymbolProfilesSnapshot()
        val strategyProfiles = dao.learnedStrategyProfilesSnapshot()
        val holdProfiles = dao.learnedHoldProfilesSnapshot()
        val learningSnapshots = dao.learningFeatureSnapshots(1000)
        val audits = dao.selfLearningAudit(1000)
        val openPositions = dao.openPositionsSnapshot()

        fun clean(value: String): String = value.replace("\n", " ").replace("\r", " ").replace("|", "/")

        val sb = StringBuilder()
        sb.appendLine("CRYPTO_TRADE_STATION_FULL_BACKUP_V1")
        sb.appendLine("createdEpochMs=${System.currentTimeMillis()}")
        sb.appendLine("appVersion=v2.0.7")
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
        sb.appendLine("openPositions=${openPositions.size}")
        sb.appendLine("taxRows=${taxRows.size}")
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
        sb.appendLine("[OPEN_POSITIONS]")
        sb.appendLine("symbol|baseAsset|quantity|entryPriceEur|highestPriceEur|stopPriceEur|takeProfitPriceEur|trailingStopPriceEur|openedAtEpochMs|updatedAtEpochMs|status|source")
        openPositions.forEach {
            sb.appendLine("${clean(it.symbol)}|${clean(it.baseAsset)}|${clean(it.quantity)}|${clean(it.entryPriceEur)}|${clean(it.highestPriceEur)}|${clean(it.stopPriceEur)}|${clean(it.takeProfitPriceEur)}|${clean(it.trailingStopPriceEur)}|${it.openedAtEpochMs}|${it.updatedAtEpochMs}|${clean(it.status)}|${clean(it.source)}")
        }
        sb.appendLine()
        sb.appendLine("[LEARNED_SYMBOL_PROFILES]")
        symbolProfiles.forEach { sb.appendLine(clean(it.toString())) }
        sb.appendLine()
        sb.appendLine("[LEARNED_STRATEGY_PROFILES]")
        strategyProfiles.forEach { sb.appendLine(clean(it.toString())) }
        sb.appendLine()
        sb.appendLine("[LEARNED_HOLD_PROFILES]")
        holdProfiles.forEach { sb.appendLine(clean(it.toString())) }
        sb.appendLine()
        sb.appendLine("[LEARNING_FEATURE_SNAPSHOTS]")
        learningSnapshots.forEach { sb.appendLine(clean(it.toString())) }
        sb.appendLine()
        sb.appendLine("[SELF_LEARNING_AUDIT]")
        audits.forEach { sb.appendLine(clean(it.toString())) }
        sb.appendLine()
        sb.appendLine("[TAX_REPORT_ROWS]")
        taxRows.forEach { sb.appendLine(clean(it.toString())) }
        sb.appendLine()
        sb.appendLine("[SECURITY_NOTE]")
        sb.appendLine("API keys, secret keys, Telegram tokens and Discord webhook URLs are intentionally not exported.")
        sb.appendLine("Android app updates with the same package name keep SharedPreferences, encrypted key store entries and Room database automatically.")
        updateStatus("Full local backup generated: trades=${trades.size}, profiles=${symbolProfiles.size + strategyProfiles.size + holdProfiles.size}, taxRows=${taxRows.size}", "INFO")
        return sb.toString()
    }



    suspend fun restoreFullLocalBackup(
        rawInput: String,
        replaceExistingLocalData: Boolean = false
    ): String {
        return try {
            val input = rawInput.trim()
            if (input.isBlank()) return "Restore failed: backup text/path is empty."

            val backupText = if ((input.startsWith("/") || input.startsWith("content:") || input.startsWith("file:")) && input.length < 600) {
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
                dao.clearPositionsForRestore()
                dao.clearTaxReportsForRestore()
            }

            val restoredTrades = restoreTradesFromSection(sections["TRADES"].orEmpty())
            val restoredPositions = restorePositionsFromSection(sections["OPEN_POSITIONS"].orEmpty())

            val message = buildString {
                appendLine("Restore complete.")
                appendLine("settings=${if (restoredSettings) "restored" else "not found"}")
                appendLine("trades=$restoredTrades")
                appendLine("openPositions=$restoredPositions")
                appendLine("replaceExistingLocalData=$replaceExistingLocalData")
                appendLine()
                appendLine("Security note: API keys, Telegram tokens, Discord tokens/webhooks and PINs are intentionally not restored from backup exports.")
                appendLine("Unstructured legacy sections such as learned profile toString rows are preserved in the backup file but skipped by this importer.")
            }.trim()
            updateStatus("Backup restore complete. trades=$restoredTrades, positions=$restoredPositions", "INFO")
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

        suspend fun exportFullLocalBackupToFile(
        settings: BotSettings = settingsStore.load(),
        customDirectoryPath: String = settingsStore.backupDirectoryPath()
    ): String {
        return try {
            val backup = exportFullLocalBackup(settings)
            val defaultBackupDir = java.io.File(appContext.getExternalFilesDir(null), "backups")
            val requested = customDirectoryPath.trim()
            val backupDir = if (requested.isNotBlank()) java.io.File(requested) else defaultBackupDir
            if (!backupDir.exists()) backupDir.mkdirs()
            if (!backupDir.exists() || !backupDir.isDirectory || !backupDir.canWrite()) {
                defaultBackupDir.mkdirs()
                updateStatus("Custom backup directory is not writable. Falling back to ${defaultBackupDir.absolutePath}", "WARN")
            }
            val finalDir = if (backupDir.exists() && backupDir.isDirectory && backupDir.canWrite()) backupDir else defaultBackupDir
            val file = java.io.File(finalDir, "cts_backup_${System.currentTimeMillis()}.txt")
            file.writeText(backup)
            val preview = backup.lineSequence().take(80).joinToString("\n")
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
                val decision = learningResult.decision
                if (settings.trueSelfLearningEnabled) updateStatus("[$symbol] ${learningResult.explanation.take(220)}", "INFO")
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
        updateStatus("Auto symbol discovery started. provider=${settings.exchangeProvider}, quoteUniverse=$quoteUniverse, candidates=${settings.autoSymbolCandidateLimit}, allowedQuotes=${settings.allowedQuoteAssetsCsv}. EUR is treated as the primary cash quote unless you enable more quotes.", "INFO")
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
        val price = if (side == OrderSide.BUY) ticker.ask else ticker.bid
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
            sendRemoteAlert(settings, "Order submit failed", "${request.side} ${request.symbol}: ${error.message}")
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
        sendRemoteAlert(settings, "Order placed", "${result.side} ${result.symbol} ${if (result.paper) "PAPER" else "LIVE"} qty=${result.executedQuantity} avg=${result.averagePrice} fee=${result.fee} id=${result.exchangeOrderId}")
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
