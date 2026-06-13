package com.ksp.cryptobot

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ksp.cryptobot.core.AiDecision
import com.ksp.cryptobot.core.Timeframe
import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.BacktestReport
import com.ksp.cryptobot.backtest.BacktestEngine
import com.ksp.cryptobot.core.BotController
import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.SignalAction
import com.ksp.cryptobot.core.StrategyMode
import com.ksp.cryptobot.core.ExchangeProvider
import com.ksp.cryptobot.core.OrderManagementMode
import com.ksp.cryptobot.core.PortfolioSnapshot
import com.ksp.cryptobot.core.TaxExportSummary
import com.ksp.cryptobot.core.RemoteCommandResult
import com.ksp.cryptobot.core.LiveOrderInfo
import com.ksp.cryptobot.core.LifecycleSnapshot
import com.ksp.cryptobot.core.SymbolDiscoveryCandidate
import com.ksp.cryptobot.core.StrategyPromotionCandidate
import com.ksp.cryptobot.core.PromotionStatus
import com.ksp.cryptobot.core.PerformanceLabSnapshot
import com.ksp.cryptobot.pro.ProAutomationSuite
import com.ksp.cryptobot.service.BotForegroundService
import com.ksp.cryptobot.settings.AppSettingsStore
import com.ksp.cryptobot.status.BotStatusStore
import com.ksp.cryptobot.learning.TrueSelfLearningEngine
import com.ksp.cryptobot.data.TradeEntity
import com.ksp.cryptobot.data.NewsArticleEntity
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.math.RoundingMode

private val SpaceBlack = Color(0xFF081326)
private val Panel = Color(0xFF0F1B33)
private val PanelAlt = Color(0xFF142544)
private val Stroke = Color(0xFF2A4471)
private val Electric = Color(0xFF47B8FF)
private val Mint = Color(0xFF55F0DE)
private val Amber = Color(0xFFFFC857)
private val Danger = Color(0xFFFF5E8A)
private val Muted = Color(0xFFA5B4D0)
private val TextPrimary = Color(0xFFEAF3FF)

class MainActivity : ComponentActivity() {
    private lateinit var controller: BotController
    private lateinit var settingsStore: AppSettingsStore
    private lateinit var statusStore: BotStatusStore

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = BotController(applicationContext)
        settingsStore = AppSettingsStore(applicationContext)
        statusStore = BotStatusStore(applicationContext)
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            CryptoTradeStationTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = SpaceBlack) {
                    AdvancedBotApp(
                        store = settingsStore,
                        statusStore = statusStore,
                        onStart = {
                            startForegroundService(Intent(this, BotForegroundService::class.java).apply {
                                action = BotForegroundService.ACTION_START_BACKGROUND_AUTO
                            })
                            statusStore.write("Background auto bot start requested from Dashboard.", "INFO")
                        },
                        onStop = {
                            startService(Intent(this, BotForegroundService::class.java).apply {
                                action = BotForegroundService.ACTION_STOP
                            })
                        },
                        onScan = { settings, execute, callback ->
                            lifecycleScope.launch {
                                callback(controller.scanOnce(settings, execute))
                            }
                        },
                        onLoadPortfolio = { settings, callback ->
                            lifecycleScope.launch {
                                callback(controller.loadPortfolioSnapshot(settings))
                            }
                        },
                        onLoadOrders = { settings, callback ->
                            lifecycleScope.launch {
                                callback(controller.loadOpenOrdersSnapshot(settings))
                            }
                        },
                        onLoadLifecycle = { settings, callback ->
                            lifecycleScope.launch {
                                callback(controller.loadLifecycleSnapshot(settings))
                            }
                        },
                        onCancelOrder = { settings, orderId, callback ->
                            lifecycleScope.launch {
                                callback(controller.cancelLiveOrder(orderId, settings))
                            }
                        },
                        onValidateSymbols = { settings ->
                            lifecycleScope.launch {
                                controller.validateConfiguredSymbols(settings)
                            }
                        },
                        onDiscoverSymbols = { settings, callback ->
                            lifecycleScope.launch {
                                callback(controller.discoverAutoSymbols(settings))
                            }
                        },
                        onExportTax = { settings, callback ->
                            lifecycleScope.launch {
                                callback(controller.exportBelgianTaxCsv(settings))
                            }
                        },
                        onRemoteCommand = { settings, command, callback ->
                            callback(controller.parseRemoteCommand(command, settings))
                        },
                        onLoadSelfLearning = { settings, callback ->
                            lifecycleScope.launch { callback(controller.loadSelfLearningSummary(settings)) }
                        },
                        onLoadPerformanceLab = { settings, callback ->
                            lifecycleScope.launch { callback(controller.loadPerformanceLabSnapshot(settings)) }
                        },
                        onRunHistoricalBacktest = { settings, symbol, timeframe, strategy, limit, callback ->
                            lifecycleScope.launch {
                                callback(controller.runKrakenHistoricalBacktest(settings, symbol, timeframe, strategy, limit))
                            }
                        },
                        onLoadChartCandles = { settings, symbol, timeframe, limit, callback ->
                            lifecycleScope.launch {
                                callback(controller.loadKrakenChartCandles(settings, symbol, timeframe, limit))
                            }
                        },
                        onLoadTradeJournal = { limit, callback ->
                            lifecycleScope.launch {
                                callback(controller.loadTradeJournal(limit))
                            }
                        },
                        onLoadNewsHistory = { symbol, limit, callback ->
                            lifecycleScope.launch {
                                callback(controller.loadNewsHistory(symbol, limit))
                            }
                        },
                        onRunKrakenHealth = { settings, callback ->
                            lifecycleScope.launch {
                                callback(controller.runKrakenDataHealth(settings))
                            }
                        },
                        onRunSystemTest = { settings, callback ->
                            lifecycleScope.launch {
                                callback(controller.runSystemFeatureVerification(settings))
                            }
                        },
                        onExportFullBackup = { settings, customBackupDirectory, callback ->
                            lifecycleScope.launch {
                                settingsStore.saveBackupDirectoryPath(customBackupDirectory)
                                callback(controller.exportFullLocalBackupToFile(settings, customBackupDirectory))
                            }
                        },
                        onRestoreFullBackup = { backupInput, replaceExisting, callback ->
                            lifecycleScope.launch {
                                callback(controller.restoreFullLocalBackup(backupInput, replaceExisting))
                            }
                        },
                        onTestTelegram = { settings, callback ->
                            lifecycleScope.launch {
                                callback(controller.sendTelegramTestAlert(settings))
                            }
                        },
                        onTestDiscord = { settings, callback ->
                            lifecycleScope.launch {
                                callback(controller.sendDiscordTestAlert(settings))
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CryptoTradeStationTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = SpaceBlack,
            surface = Panel,
            primary = Electric,
            secondary = Mint,
            tertiary = Amber,
            error = Danger,
            onBackground = TextPrimary,
            onSurface = TextPrimary,
            onPrimary = Color.White,
            onSecondary = Color(0xFF06130F)
        ),
        content = content
    )
}

private enum class AppTab(val label: String) {
    DASHBOARD("Dashboard"),
    STATUS("Live Status"),
    BOT("Bot"),
    AI("AI"),
    AI_SIGNALS("AI Signals"),
    NEWS("News Dashboard"),
    CHART("Chart"),
    CHART_MAIN("Live Chart"),
    TRADE_OVERLAY("Trade Overlay"),
    REPLAY("Trade Replay"),
    TRADE_JOURNAL("Trade Journal"),
    STRATEGY("Strategy Lab"),
    SANDBOX("Strategy Sandbox"),
    BACKTEST("Backtest Lab"),
    REGIME("Regime"),
    ORDERS("Orders"),
    POSITIONS("Positions"),
    AUTONOMOUS("Autonomous"),
    SELF_LEARNING("Self Learning"),
    SELF_LEARNING_MAIN("Learning Summary"),
    LEARNING_INSPECTOR("Learning DB"),
    PERFORMANCE("Performance Lab"),
    PRO("Pro Systems"),
    KRAKEN_HEALTH("Kraken Health"),
    SMART_EXIT("Smart Exit v2"),
    PORTFOLIO_ROTATION("Rotation"),
    AUTO_TUNER("Auto-Tuner"),
    RELEASE_SAFETY("Release Safety"),
    PORTFOLIO("Portfolio"),
    TAX("Belgium Tax"),
    RISK("Risk Center"),
    HISTORY("History"),
    SETTINGS("Settings"),
    BASIC_SETTINGS("Basic Settings"),
    SYSTEM_TEST("System Test"),
    HEALTH("Build Health"),
    NOTIFICATIONS("Notifications"),
    NOTIFICATION_LOGS("Event Logs"),
    REMOTE_ALERTS("Remote Alerts"),
    BACKUP("Backup/Restore"),
    ADVANCED_SETTINGS("Advanced Settings"),
    SYMBOLS("Symbol Scanner")
}

@Composable
private fun AdvancedBotApp(
    store: AppSettingsStore,
    statusStore: BotStatusStore,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onScan: (BotSettings, Boolean, (List<AiDecision>) -> Unit) -> Unit,
    onLoadPortfolio: (BotSettings, (PortfolioSnapshot) -> Unit) -> Unit,
    onLoadOrders: (BotSettings, (List<LiveOrderInfo>) -> Unit) -> Unit,
    onLoadLifecycle: (BotSettings, (LifecycleSnapshot) -> Unit) -> Unit,
    onCancelOrder: (BotSettings, String, (Boolean) -> Unit) -> Unit,
    onValidateSymbols: (BotSettings) -> Unit,
    onDiscoverSymbols: (BotSettings, (List<SymbolDiscoveryCandidate>) -> Unit) -> Unit,
    onExportTax: (BotSettings, (TaxExportSummary) -> Unit) -> Unit,
    onRemoteCommand: (BotSettings, String, (RemoteCommandResult) -> Unit) -> Unit,
    onLoadSelfLearning: (BotSettings, (TrueSelfLearningEngine.LearningSummary) -> Unit) -> Unit,
    onLoadPerformanceLab: (BotSettings, (PerformanceLabSnapshot) -> Unit) -> Unit,
    onRunHistoricalBacktest: (BotSettings, String, Timeframe, StrategyMode, Int, (BacktestReport) -> Unit) -> Unit,
    onLoadChartCandles: (BotSettings, String, Timeframe, Int, (List<Candle>) -> Unit) -> Unit,
    onLoadTradeJournal: (Int, (List<TradeEntity>) -> Unit) -> Unit,
    onLoadNewsHistory: (String, Int, (List<NewsArticleEntity>) -> Unit) -> Unit,
    onRunKrakenHealth: (BotSettings, (List<String>) -> Unit) -> Unit,
    onRunSystemTest: (BotSettings, (List<String>) -> Unit) -> Unit,
    onExportFullBackup: (BotSettings, String, (String) -> Unit) -> Unit,
    onRestoreFullBackup: (String, Boolean, (String) -> Unit) -> Unit,
    onTestTelegram: (BotSettings, (Boolean) -> Unit) -> Unit,
    onTestDiscord: (BotSettings, (Boolean) -> Unit) -> Unit
) {
    var settings by remember { mutableStateOf(store.load()) }
    var currentTab by remember { mutableStateOf(AppTab.DASHBOARD) }
    var status by remember { mutableStateOf(statusStore.latestText()) }
    var statusLevel by remember { mutableStateOf(statusStore.latestLevel()) }
    var statusHistory by remember { mutableStateOf(statusStore.recentLines()) }
    var decisions by remember { mutableStateOf<List<AiDecision>>(emptyList()) }
    var portfolioSnapshot by remember { mutableStateOf<PortfolioSnapshot?>(null) }
    var liveOrders by remember { mutableStateOf<List<LiveOrderInfo>>(emptyList()) }
    var lifecycleSnapshot by remember { mutableStateOf<LifecycleSnapshot?>(null) }
    var symbolCandidates by remember { mutableStateOf<List<SymbolDiscoveryCandidate>>(emptyList()) }
    var taxExportSummary by remember { mutableStateOf<TaxExportSummary?>(null) }
    var remoteCommand by remember { mutableStateOf("/status") }
    var remoteResult by remember { mutableStateOf<RemoteCommandResult?>(null) }
    var selfLearningSummary by remember { mutableStateOf<TrueSelfLearningEngine.LearningSummary?>(null) }
    var performanceLabSnapshot by remember { mutableStateOf<PerformanceLabSnapshot?>(null) }
    var chartCandles by remember { mutableStateOf<List<Candle>>(emptyList()) }
    var chartSymbol by remember { mutableStateOf(settings.symbols().firstOrNull() ?: "BTCEUR") }
    var chartTimeframe by remember { mutableStateOf(Timeframe.M15) }
    var tradeJournal by remember { mutableStateOf<List<TradeEntity>>(emptyList()) }
    var newsHistory by remember { mutableStateOf<List<NewsArticleEntity>>(emptyList()) }
    var krakenHealthLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var systemTestLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var telegramBotToken by remember { mutableStateOf(store.telegramBotToken().orEmpty()) }
    var telegramChatId by remember { mutableStateOf(store.telegramChatId().orEmpty()) }
    var discordWebhookUrl by remember { mutableStateOf(store.discordWebhookUrl().orEmpty()) }
    var remoteCommandPin by remember { mutableStateOf(store.remoteCommandPin().orEmpty()) }
    var discordBotToken by remember { mutableStateOf(store.discordBotToken().orEmpty()) }
    var discordCommandChannelId by remember { mutableStateOf(store.discordChannelId().orEmpty()) }
    var liveChartAutoRefresh by remember { mutableStateOf(true) }
    val activeChartSymbols = remember(lifecycleSnapshot, portfolioSnapshot, tradeJournal, settings.symbolsCsv) {
        val lifecycleSymbols = lifecycleSnapshot?.positions
            ?.filter { it.quantity > BigDecimal.ZERO }
            ?.map { it.symbol.uppercase().replace("/", "").replace("-", "") }
            .orEmpty()
        val portfolioSymbols = portfolioSnapshot?.assets
            ?.filter { it.total > BigDecimal.ZERO || it.free > BigDecimal.ZERO }
            ?.mapNotNull { asset ->
                val clean = asset.asset.uppercase().replace("Z", "").replace(".", "").trim()
                when {
                    clean == "EUR" || clean == "USD" || clean == "USDT" || clean == "USDC" -> null
                    clean.isBlank() -> null
                    else -> "${clean}EUR"
                }
            }
            .orEmpty()
        val tradedSymbols = tradeJournal
            .map { it.symbol.uppercase().replace("/", "").replace("-", "") }
        (lifecycleSymbols + portfolioSymbols + tradedSymbols + settings.symbols())
            .map { it.uppercase().replace("/", "").replace("-", "") }
            .filter { it.isNotBlank() }
            .distinct()
    }

    LaunchedEffect(Unit) {
        while (true) {
            status = statusStore.latestText()
            statusLevel = statusStore.latestLevel()
            statusHistory = statusStore.recentLines()
            delay(1000L)
        }
    }

    LaunchedEffect(currentTab, settings.exchangeProvider) {
        if (currentTab == AppTab.DASHBOARD) {
            onLoadLifecycle(settings) { result ->
                lifecycleSnapshot = result
                statusStore.write("Dashboard active-position symbols loaded. positions=${result.positions.size}")
            }
            onLoadPortfolio(settings) { result ->
                portfolioSnapshot = result
                statusStore.write("Dashboard portfolio symbols loaded. assets=${result.assets.size}")
            }
            onLoadTradeJournal(200) { result -> tradeJournal = result }
        }
        if (currentTab == AppTab.AI_SIGNALS) {
            onLoadLifecycle(settings) { result ->
                lifecycleSnapshot = result
                statusStore.write("AI Signals active-position symbols loaded. positions=${result.positions.size}")
            }
            onLoadPortfolio(settings) { result ->
                portfolioSnapshot = result
                statusStore.write("AI Signals portfolio symbols loaded. assets=${result.assets.size}")
            }
            onLoadTradeJournal(200) { result -> tradeJournal = result }
        }
        if (currentTab == AppTab.NEWS) {
            onLoadNewsHistory("", 200) { result ->
                newsHistory = result
                statusStore.write("News dashboard loaded. cachedArticles=${result.size}")
            }
        }
        if (currentTab == AppTab.PORTFOLIO) {
            onLoadPortfolio(settings) { result ->
                portfolioSnapshot = result
                statusStore.write("Portfolio auto-refresh complete. Total≈€${result.totalValueEur}")
                status = "Portfolio loaded: €${result.totalValueEur}"
            }
            onLoadLifecycle(settings) { result ->
                lifecycleSnapshot = result
                statusStore.write("Portfolio lifecycle guards loaded. positions=${result.positions.size}")
            }
        }
        if (currentTab == AppTab.ORDERS) {
            onLoadOrders(settings) { result ->
                liveOrders = result
                statusStore.write("Open orders auto-refresh complete. Count=${result.size}")
                status = "Open orders loaded: ${result.size}"
            }
        }
        if (currentTab == AppTab.POSITIONS) {
            onLoadLifecycle(settings) { result ->
                lifecycleSnapshot = result
                statusStore.write("Lifecycle auto-refresh complete. Positions=${result.positions.size}")
                status = "Lifecycle loaded: ${result.positions.size} position(s)"
            }
        }
        if (currentTab == AppTab.SYMBOLS) {
            onDiscoverSymbols(settings) { result ->
                symbolCandidates = result
                statusStore.write("Auto symbol scanner loaded. Candidates=${result.size}, enabled=${result.count { it.enabledForRotation }}")
                status = "Symbol scanner loaded: ${result.count { it.enabledForRotation }} enabled"
            }
        }
        if (currentTab == AppTab.SELF_LEARNING) {
            onLoadSelfLearning(settings) { result ->
                selfLearningSummary = result
                statusStore.write("Self-learning dashboard loaded. ${result.summaryLine}")
                status = "Self-learning loaded"
            }
        }
        if (currentTab == AppTab.PERFORMANCE) {
            onLoadLifecycle(settings) { result ->
                lifecycleSnapshot = result
                statusStore.write("Performance Lab active-position symbols loaded. positions=${result.positions.size}")
            }
            onLoadPortfolio(settings) { result ->
                portfolioSnapshot = result
                statusStore.write("Performance Lab portfolio symbols loaded. assets=${result.assets.size}")
            }
            onLoadTradeJournal(200) { result -> tradeJournal = result }
            val perfSymbols = (settings.symbols() + activeChartSymbols).map { it.uppercase().replace("/", "").replace("-", "") }.distinct()
            onLoadPerformanceLab(settings.copy(symbolsCsv = perfSymbols.joinToString(","))) { result ->
                performanceLabSnapshot = result
                statusStore.write("${result.summaryLine}. Symbols=${perfSymbols.size}")
                status = "Performance Lab loaded"
            }
        }
        if (currentTab == AppTab.AUTO_TUNER) {
            onLoadLifecycle(settings) { result ->
                lifecycleSnapshot = result
                statusStore.write("Auto-Tuner active-position symbols loaded. positions=${result.positions.size}")
            }
            onLoadPortfolio(settings) { result ->
                portfolioSnapshot = result
                statusStore.write("Auto-Tuner portfolio symbols loaded. assets=${result.assets.size}")
            }
            onLoadTradeJournal(200) { result -> tradeJournal = result }
        }
        if (currentTab == AppTab.CHART || currentTab == AppTab.TRADE_OVERLAY) {
            while (currentTab == AppTab.CHART || currentTab == AppTab.TRADE_OVERLAY) {
                onLoadLifecycle(settings) { result ->
                    lifecycleSnapshot = result
                    val active = result.positions.map { it.symbol }.distinct()
                    statusStore.write("Chart active-position symbols refreshed. active=${active.joinToString(",").ifBlank { "none" }}")
                }
                onLoadPortfolio(settings) { result -> portfolioSnapshot = result }
                onLoadChartCandles(settings, chartSymbol, chartTimeframe, 240) { result ->
                    chartCandles = result
                    statusStore.write("Live chart auto-refreshed. Symbol=$chartSymbol timeframe=${chartTimeframe.name} candles=${result.size}")
                    status = "Live chart refreshed: $chartSymbol ${chartTimeframe.name}"
                }
                onLoadTradeJournal(200) { result -> tradeJournal = result }
                if (!liveChartAutoRefresh) break
                delay(30_000L)
            }
        }
        if (currentTab == AppTab.TRADE_JOURNAL || currentTab == AppTab.CHART || currentTab == AppTab.REPLAY) {
            onLoadTradeJournal(100) { result ->
                tradeJournal = result
                statusStore.write("Trade journal auto-loaded. rows=${result.size}")
            }
        }
        if (currentTab == AppTab.KRAKEN_HEALTH) {
            onRunKrakenHealth(settings) { result ->
                krakenHealthLines = result
                statusStore.write("Kraken health loaded. rows=${result.size}")
                status = "Kraken health check complete"
            }
        }
    }
    var apiKey by remember { mutableStateOf("") }
    var secretKey by remember { mutableStateOf("") }
    var newsKey by remember { mutableStateOf("") }
    var symbols by remember { mutableStateOf(settings.symbolsCsv) }
    var maxPosition by remember { mutableStateOf(settings.maxPositionEur.toPlainString()) }
    var maxLoss by remember { mutableStateOf(settings.maxDailyLossEur.toPlainString()) }
    var maxTrades by remember { mutableStateOf(settings.maxTradesPerDay.toString()) }
    var maxSpread by remember { mutableStateOf(settings.maxSpreadPercent.toPlainString()) }

    fun persistSettings(newSettings: BotSettings) {
        settings = newSettings
        store.save(newSettings)
        status = "Settings saved"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF09111F), SpaceBlack, Color(0xFF090B12))
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HeaderBar(status = status, mode = settings.mode, level = statusLevel)
            AppTabs(currentTab = currentTab, onTabSelected = { currentTab = it })

            when (currentTab) {
                AppTab.DASHBOARD -> DashboardScreen(
                    settings = settings,
                    status = status,
                    decisions = decisions,
                    activePositionSymbols = activeChartSymbols,
                    onStart = { statusStore.write("Start button pressed from dashboard."); onStart(); status = "Foreground live scanner started" },
                    onStop = { statusStore.write("Stop button pressed from dashboard.", "WARN"); onStop(); status = "Bot stopped" },
                    onScan = {
                        status = "Scanning market + AI inputs..."
                        val scanSymbols = (settings.symbols() + activeChartSymbols).map { it.uppercase().replace("/", "").replace("-", "") }.distinct()
                        val scanSettings = settings.copy(symbolsCsv = scanSymbols.joinToString(","))
                        onScan(scanSettings, false) { result ->
                            decisions = result
                            statusStore.write("Manual scan complete from dashboard. Symbols=${scanSymbols.size}, Decisions=${result.size}")
                            status = "Scan complete"
                        }
                    },
                    onExecute = {
                        status = "Running guarded execution pass..."
                        val scanSymbols = (settings.symbols() + activeChartSymbols).map { it.uppercase().replace("/", "").replace("-", "") }.distinct()
                        val scanSettings = settings.copy(symbolsCsv = scanSymbols.joinToString(","))
                        onScan(scanSettings, settings.mode == BotMode.PAPER || settings.mode == BotMode.LIVE_AUTO) { result ->
                            decisions = result
                            statusStore.write("Manual execution pass complete from dashboard. Symbols=${scanSymbols.size}, Decisions=${result.size}")
                            status = "Execution pass complete"
                        }
                    },
                    onOpenNews = { currentTab = AppTab.NEWS }
                )
                AppTab.STATUS -> LiveStatusScreen(
                    status = status,
                    level = statusLevel,
                    history = statusHistory,
                    settings = settings,
                    onClear = { statusStore.clear(); statusHistory = statusStore.recentLines() }
                )
                AppTab.BOT -> BotControlScreen(
                    settings = settings,
                    symbols = symbols,
                    onSymbolsChange = { symbols = it },
                    onModeChange = { persistSettings(settings.copy(mode = it)) },
                    onLiveAckChange = { persistSettings(settings.copy(liveTradingAcknowledged = it)) },
                    onSave = {
                        persistSettings(settings.copy(symbolsCsv = symbols))
                    },
                    onStart = { statusStore.write("Start button pressed from Bot tab."); onStart(); status = "Bot service active" },
                    onStop = { statusStore.write("Stop button pressed from Bot tab.", "WARN"); onStop(); status = "Bot service stopped" }
                )
                AppTab.SYMBOLS -> SymbolScannerScreen(
                    settings = settings,
                    candidates = symbolCandidates,
                    onRefresh = {
                        statusStore.write("Auto symbol scanner refresh requested from UI.")
                        onDiscoverSymbols(settings) { result ->
                            symbolCandidates = result
                            status = "Symbol scanner loaded: ${result.count { it.enabledForRotation }} enabled"
                        }
                    },
                    onEnableAutoDiscovery = { enabled -> persistSettings(settings.copy(autoSymbolDiscoveryEnabled = enabled)) },
                    onUseTopSymbols = {
                        val selected = symbolCandidates.filter { it.enabledForRotation }.take(settings.autoSymbolActiveLimit.coerceAtLeast(1)).joinToString(",") { it.symbol }
                        if (selected.isNotBlank()) {
                            symbols = selected
                            persistSettings(settings.copy(symbolsCsv = selected))
                            statusStore.write("Configured symbols replaced by scanner selection: $selected", "INFO")
                            status = "Symbols updated from scanner"
                        }
                    }
                )
                AppTab.AI -> AiHubScreen(
                    decisions = decisions,
                    settings = settings,
                    performanceLabSnapshot = performanceLabSnapshot,
                    onOpen = { currentTab = it },
                    onRefreshPerformance = {
                        val perfSymbols = (settings.symbols() + activeChartSymbols).map { it.uppercase().replace("/", "").replace("-", "") }.distinct()
                        onLoadPerformanceLab(settings.copy(symbolsCsv = perfSymbols.joinToString(","))) { result ->
                            performanceLabSnapshot = result
                            statusStore.write("${result.summaryLine}. Symbols=${perfSymbols.size}")
                            status = "Performance Lab refreshed"
                        }
                    }
                )
                AppTab.AI_SIGNALS -> AiSignalsScreen(
                    decisions = decisions,
                    settings = settings,
                    activePositionSymbols = activeChartSymbols,
                    onScan = {
                        val scanSymbols = (settings.symbols() + activeChartSymbols).map { it.uppercase().replace("/", "").replace("-", "") }.distinct()
                        val scanSettings = settings.copy(symbolsCsv = scanSymbols.joinToString(","))
                        status = "AI Signals scan running..."
                        onScan(scanSettings, false) { result ->
                            decisions = result
                            statusStore.write("AI Signals scan complete. Symbols=${scanSymbols.size}, Decisions=${result.size}")
                            status = "AI Signals scan complete"
                        }
                    }
                )
                AppTab.CHART -> ChartScreen(
                    settings = settings,
                    candles = chartCandles,
                    selectedSymbol = chartSymbol,
                    selectedTimeframe = chartTimeframe,
                    activePositionSymbols = activeChartSymbols,
                    latestDecision = decisions.firstOrNull { it.symbol.equals(chartSymbol, ignoreCase = true) },
                    trades = tradeJournal.filter { it.symbol.equals(chartSymbol, ignoreCase = true) },
                    autoRefresh = liveChartAutoRefresh,
                    onAutoRefreshChange = { liveChartAutoRefresh = it },
                    onSymbolChange = { symbol ->
                        chartSymbol = symbol
                        onLoadChartCandles(settings, symbol, chartTimeframe, 240) { result ->
                            chartCandles = result
                            status = "Chart loaded: $symbol ${chartTimeframe.name}"
                        }
                        onLoadTradeJournal(200) { result -> tradeJournal = result }
                    },
                    onTimeframeChange = { timeframe ->
                        chartTimeframe = timeframe
                        onLoadChartCandles(settings, chartSymbol, timeframe, 240) { result ->
                            chartCandles = result
                            status = "Chart loaded: $chartSymbol ${timeframe.name}"
                        }
                        onLoadTradeJournal(200) { result -> tradeJournal = result }
                    },
                    onRefresh = {
                        onLoadChartCandles(settings, chartSymbol, chartTimeframe, 240) { result ->
                            chartCandles = result
                            statusStore.write("Unified live chart refreshed. Symbol=$chartSymbol timeframe=${chartTimeframe.name} candles=${result.size}")
                            status = "Chart refreshed"
                        }
                        onLoadTradeJournal(200) { result -> tradeJournal = result }
                    }
                )
                AppTab.CHART_MAIN -> ChartScreen(
                    settings = settings,
                    candles = chartCandles,
                    selectedSymbol = chartSymbol,
                    selectedTimeframe = chartTimeframe,
                    activePositionSymbols = activeChartSymbols,
                    latestDecision = decisions.firstOrNull { it.symbol.equals(chartSymbol, ignoreCase = true) },
                    trades = tradeJournal.filter { it.symbol.equals(chartSymbol, ignoreCase = true) },
                    autoRefresh = liveChartAutoRefresh,
                    onAutoRefreshChange = { liveChartAutoRefresh = it },
                    onSymbolChange = { symbol ->
                        chartSymbol = symbol
                        onLoadChartCandles(settings, symbol, chartTimeframe, 180) { result ->
                            chartCandles = result
                            status = "Chart loaded: $symbol ${chartTimeframe.name}"
                        }
                    },
                    onTimeframeChange = { timeframe ->
                        chartTimeframe = timeframe
                        onLoadChartCandles(settings, chartSymbol, timeframe, 180) { result ->
                            chartCandles = result
                            status = "Chart loaded: $chartSymbol ${timeframe.name}"
                        }
                    },
                    onRefresh = {
                        onLoadChartCandles(settings, chartSymbol, chartTimeframe, 180) { result ->
                            chartCandles = result
                            statusStore.write("Chart manually refreshed. Symbol=$chartSymbol timeframe=${chartTimeframe.name} candles=${result.size}")
                            status = "Chart refreshed"
                        }
                        onLoadTradeJournal(100) { result -> tradeJournal = result }
                    }
                )
                AppTab.TRADE_OVERLAY -> TradeOverlayScreen(
                    settings = settings,
                    candles = chartCandles,
                    trades = tradeJournal,
                    selectedSymbol = chartSymbol,
                    onRefresh = {
                        onLoadChartCandles(settings, chartSymbol, chartTimeframe, 240) { result -> chartCandles = result }
                        onLoadTradeJournal(100) { result -> tradeJournal = result }
                        status = "Trade overlay refreshed"
                    }
                )
                AppTab.REPLAY -> TradeReplayScreen(
                    settings = settings,
                    candles = chartCandles,
                    selectedSymbol = chartSymbol,
                    onLoadReplayData = {
                        onLoadChartCandles(settings, chartSymbol, Timeframe.M5, 240) { result ->
                            chartCandles = result
                            statusStore.write("Trade replay data loaded. Symbol=$chartSymbol candles=${result.size}")
                            status = "Trade replay loaded"
                        }
                    }
                )
                AppTab.TRADE_JOURNAL -> TradeJournalScreen(
                    trades = tradeJournal,
                    onRefresh = {
                        onLoadTradeJournal(200) { result ->
                            tradeJournal = result
                            statusStore.write("Trade journal manually refreshed. rows=${result.size}")
                            status = "Trade journal refreshed"
                        }
                    }
                )
                AppTab.STRATEGY -> StrategyScreen(settings = settings, onToggleStrategy = { persistSettings(settings.copy(recoveredScalpingStrategyEnabled = it)) })
                AppTab.SANDBOX -> StrategySandboxScreen(
                    settings = settings,
                    onRunHistoricalBacktest = onRunHistoricalBacktest
                )
                AppTab.BACKTEST -> BacktestLabScreen(settings = settings, onRunHistoricalBacktest = onRunHistoricalBacktest)
                AppTab.REGIME -> RegimeScreen(settings = settings)
                AppTab.ORDERS -> OrdersScreen(
                    settings = settings,
                    orders = liveOrders,
                    onRefresh = {
                        statusStore.write("Open-order refresh requested from UI.")
                        onLoadOrders(settings) { result ->
                            liveOrders = result
                            status = "Open orders loaded: ${result.size}"
                        }
                    },
                    onCancel = { orderId ->
                        onCancelOrder(settings, orderId) { cancelled ->
                            status = if (cancelled) "Order cancelled" else "Cancel failed or unsupported"
                            onLoadOrders(settings) { result -> liveOrders = result }
                        }
                    }
                )
                AppTab.POSITIONS -> PositionsScreen(
                    settings = settings,
                    snapshot = lifecycleSnapshot,
                    onRefresh = {
                        statusStore.write("Lifecycle refresh requested from UI.")
                        onLoadLifecycle(settings) { result ->
                            lifecycleSnapshot = result
                            status = "Lifecycle loaded: ${result.positions.size} position(s)"
                        }
                    }
                )
                AppTab.AUTONOMOUS -> AutonomousScreen(
                    settings = settings,
                    taxExportSummary = taxExportSummary,
                    remoteCommand = remoteCommand,
                    remoteResult = remoteResult,
                    onRemoteCommandChange = { remoteCommand = it },
                    onExportTax = {
                        onExportTax(settings) { summary ->
                            taxExportSummary = summary
                            statusStore.write("Tax export generated from Autonomous tab. rows=${summary.rowCount}")
                            status = "Tax export ready: ${summary.rowCount} rows"
                        }
                    },
                    onRunRemoteCommand = {
                        onRemoteCommand(settings, remoteCommand) { result ->
                            remoteResult = result
                            statusStore.write("Remote command preview: ${result.message}")
                            status = result.message
                        }
                    }
                )
                AppTab.SELF_LEARNING -> SelfLearningHubScreen(
                    settings = settings,
                    summary = selfLearningSummary,
                    onOpen = { currentTab = it },
                    onRefresh = {
                        onLoadSelfLearning(settings) { result ->
                            selfLearningSummary = result
                            statusStore.write("Self-learning refreshed from hub. ${result.summaryLine}")
                            status = "Self-learning refreshed"
                        }
                    }
                )
                AppTab.SELF_LEARNING_MAIN -> SelfLearningScreen(
                    settings = settings,
                    summary = selfLearningSummary,
                    onRefresh = {
                        onLoadSelfLearning(settings) { result ->
                            selfLearningSummary = result
                            statusStore.write("Self-learning manual refresh complete. ${result.summaryLine}")
                            status = "Self-learning refreshed"
                        }
                    }
                )
                AppTab.LEARNING_INSPECTOR -> LearningInspectorScreen(
                    summary = selfLearningSummary,
                    settings = settings,
                    onRefresh = {
                        onLoadSelfLearning(settings) { result ->
                            selfLearningSummary = result
                            statusStore.write("Learning inspector refreshed. ${result.summaryLine}")
                            status = "Learning inspector refreshed"
                        }
                    }
                )
                AppTab.PERFORMANCE -> PerformanceLabScreen(
                    snapshot = performanceLabSnapshot,
                    settings = settings,
                    activePositionSymbols = activeChartSymbols,
                    onRefresh = {
                        val perfSymbols = (settings.symbols() + activeChartSymbols).map { it.uppercase().replace("/", "").replace("-", "") }.distinct()
                        onLoadPerformanceLab(settings.copy(symbolsCsv = perfSymbols.joinToString(","))) { result ->
                            performanceLabSnapshot = result
                            statusStore.write("${result.summaryLine}. Symbols=${perfSymbols.size}")
                            status = "Performance Lab refreshed"
                        }
                    }
                )
                AppTab.PRO -> ProSystemsScreen(settings = settings)
                AppTab.KRAKEN_HEALTH -> KrakenHealthMonitorScreen(
                    settings = settings,
                    lines = krakenHealthLines,
                    onRefresh = {
                        onRunKrakenHealth(settings) { result ->
                            krakenHealthLines = result
                            statusStore.write("Kraken data health manually refreshed. rows=${result.size}")
                            status = "Kraken data health refreshed"
                        }
                    }
                )
                AppTab.SMART_EXIT -> SmartExitV2Screen(settings = settings, trades = tradeJournal)
                AppTab.PORTFOLIO_ROTATION -> PortfolioRotationEngineScreen(settings = settings, decisions = decisions, trades = tradeJournal)
                AppTab.AUTO_TUNER -> StrategyAutoTunerScreen(
                    settings = settings,
                    activePositionSymbols = activeChartSymbols,
                    onApplySettings = { updated ->
                        persistSettings(updated)
                        statusStore.write("Auto-Tuner applied best strategy to live settings: strategy=${updated.strategyMode}, symbols=${updated.symbolsCsv}", "INFO")
                        status = "Auto-Tuner settings applied"
                    },
                    onRunHistoricalBacktest = onRunHistoricalBacktest
                )
                AppTab.RELEASE_SAFETY -> ReleaseSafetyLockScreen(settings = settings, healthLines = krakenHealthLines)
                AppTab.PORTFOLIO -> PortfolioScreen(
                    settings = settings,
                    snapshot = portfolioSnapshot,
                    lifecycleSnapshot = lifecycleSnapshot,
                    onRefresh = {
                        statusStore.write("Portfolio refresh requested from UI.")
                        onLoadPortfolio(settings) { result ->
                            portfolioSnapshot = result
                            statusStore.write("Portfolio refresh complete. Total≈€${result.totalValueEur}")
                            status = "Portfolio loaded: €${result.totalValueEur}"
                        }
                        onLoadLifecycle(settings) { result ->
                            lifecycleSnapshot = result
                            statusStore.write("Portfolio lifecycle guard refresh complete. positions=${result.positions.size}")
                        }
                    }
                )
                AppTab.NEWS -> NewsScreen(
                    settings = settings,
                    newsHistory = newsHistory,
                    activeSymbols = activeChartSymbols,
                    onToggleNews = { persistSettings(settings.copy(useNewsAi = it)) },
                    onRefreshHistory = { symbol ->
                        onLoadNewsHistory(symbol, 200) { result ->
                            newsHistory = result
                            statusStore.write("News dashboard refreshed. symbol=${symbol.ifBlank { "ALL" }} rows=${result.size}")
                            status = "News dashboard refreshed"
                        }
                    },
                    onScanNews = { symbol ->
                        val scanSymbol = symbol.ifBlank { settings.symbols().firstOrNull() ?: "BTCEUR" }
                        onScan(settings.copy(symbolsCsv = scanSymbol), false) { result ->
                            decisions = result
                            onLoadNewsHistory(scanSymbol, 200) { rows -> newsHistory = rows }
                            statusStore.write("News scan complete for $scanSymbol. decisions=${result.size}")
                            status = "News scan complete: $scanSymbol"
                        }
                    }
                )
                AppTab.TAX -> TaxScreen()
                AppTab.HISTORY -> HistoryScreen(
                    settings = settings,
                    trades = tradeJournal,
                    events = statusHistory,
                    onRefresh = {
                        onLoadTradeJournal(250) { result ->
                            tradeJournal = result
                            statusStore.write("History refreshed. trades=${result.size}", "INFO")
                            status = "History refreshed"
                        }
                    }
                )
                AppTab.RISK -> RiskScreen(
                    settings = settings,
                    maxPosition = maxPosition,
                    maxLoss = maxLoss,
                    maxTrades = maxTrades,
                    maxSpread = maxSpread,
                    onMaxPosition = { maxPosition = it },
                    onMaxLoss = { maxLoss = it },
                    onMaxTrades = { maxTrades = it },
                    onMaxSpread = { maxSpread = it },
                    onTaxOptimization = { persistSettings(settings.copy(taxOptimization = it)) },
                    onTradeOnlyBtcEth = { persistSettings(settings.copy(tradeOnlyBtcEth = it)) },
                    onSave = {
                        persistSettings(
                            settings.copy(
                                maxPositionEur = maxPosition.toBigDecimalOrNull() ?: BigDecimal("25.00"),
                                maxDailyLossEur = maxLoss.toBigDecimalOrNull() ?: BigDecimal("15.00"),
                                maxTradesPerDay = maxTrades.toIntOrNull() ?: 4,
                                maxSpreadPercent = maxSpread.toBigDecimalOrNull() ?: BigDecimal("0.35")
                            )
                        )
                    }
                )
                AppTab.ADVANCED_SETTINGS -> AdvancedSettingsScreen(
                    settings = settings,
                    onApply = { updated ->
                        persistSettings(updated)
                        maxPosition = updated.maxPositionEur.toPlainString()
                        maxLoss = updated.maxDailyLossEur.toPlainString()
                        maxTrades = updated.maxTradesPerDay.toString()
                        maxSpread = updated.maxSpreadPercent.toPlainString()
                        statusStore.write("Advanced editable settings saved from UI.")
                        status = "Advanced settings saved"
                    }
                )
                AppTab.SETTINGS -> SettingsHubScreen(
                    settings = settings,
                    systemTestLines = systemTestLines,
                    onOpen = { currentTab = it },
                    onModeChange = { mode ->
                        persistSettings(settings.copy(
                            mode = mode,
                            manualExecutionMode = if (mode == BotMode.LIVE_AUTO) false else settings.manualExecutionMode
                        ))
                        statusStore.write("Trading mode changed to $mode from Settings hub.", "INFO")
                        status = "Mode changed to $mode"
                    },
                    onLiveAckChange = { acknowledged ->
                        persistSettings(settings.copy(liveTradingAcknowledged = acknowledged))
                        statusStore.write("Live acknowledgement changed to $acknowledged from Settings hub.", if (acknowledged) "INFO" else "WARN")
                        status = "Live acknowledgement ${if (acknowledged) "enabled" else "disabled"}"
                    },
                    onRunSystemTest = {
                        onRunSystemTest(settings) { result ->
                            systemTestLines = result
                            statusStore.write("System test completed from Settings hub. rows=${result.size}", "INFO")
                            status = "System test complete"
                        }
                    },
                    onApplySafeDefaults = {
                        val safe = settings.copy(
                            exchangeProvider = ExchangeProvider.PAPER,
                            mode = BotMode.PAPER,
                            manualExecutionMode = false,
                            allowedQuoteAssetsCsv = "EUR",
                            autoSymbolQuoteAsset = "ALL",
                            nonEurQuoteBuyEnabled = false,
                            maxNewTradesPerScan = 1,
                            maxTradesPerHour = 3,
                            maxSimultaneousLivePositions = 3,
                            minimumQuoteReservePercent = BigDecimal("20.0"),
                            trueSelfLearningEnabled = true,
                            spikeProfitTimingEnabled = true,
                            enableBacktestGate = true,
                            enableForwardTestGate = true
                        )
                        persistSettings(safe)
                        statusStore.write("Clean Settings hub applied safe Belgium defaults.", "INFO")
                        status = "Safe defaults applied"
                    }
                )
                AppTab.SYSTEM_TEST -> SystemTestScreen(
                    settings = settings,
                    lines = systemTestLines,
                    onRun = {
                        onRunSystemTest(settings) { result ->
                            systemTestLines = result
                            statusStore.write("System test manually completed. rows=${result.size}", "INFO")
                            status = "System test complete"
                        }
                    }
                )
                AppTab.BASIC_SETTINGS -> SettingsScreen(
                    apiKey = apiKey,
                    secretKey = secretKey,
                    newsKey = newsKey,
                    onApiKey = { apiKey = it },
                    onSecretKey = { secretKey = it },
                    onNewsKey = { newsKey = it },
                    settings = settings,
                    onExchangeProvider = { provider ->
                        persistSettings(settings.copy(
                            exchangeProvider = provider,
                            mode = if (provider == ExchangeProvider.PAPER) BotMode.PAPER else settings.mode,
                            manualExecutionMode = provider == ExchangeProvider.MANUAL || provider == ExchangeProvider.BINANCE_READ_ONLY
                        ))
                    },
                    onModeChange = { mode ->
                        persistSettings(settings.copy(
                            mode = mode,
                            manualExecutionMode = if (mode == BotMode.LIVE_AUTO) false else settings.manualExecutionMode
                        ))
                        statusStore.write("Trading mode changed to $mode from Basic Settings.", "INFO")
                        status = "Mode changed to $mode"
                    },
                    onLiveAckChange = { acknowledged ->
                        persistSettings(settings.copy(liveTradingAcknowledged = acknowledged))
                        statusStore.write("Live acknowledgement changed to $acknowledged from Basic Settings.", if (acknowledged) "INFO" else "WARN")
                        status = "Live acknowledgement ${if (acknowledged) "enabled" else "disabled"}"
                    },
                    onManualMode = { persistSettings(settings.copy(manualExecutionMode = it)) },
                    onMarketOrders = { persistSettings(settings.copy(enableMarketOrders = it)) },
                    onNewsAi = { persistSettings(settings.copy(useNewsAi = it)) },
                    onMemoryAi = { persistSettings(settings.copy(useTradeMemoryAi = it)) },
                    onSaveKeys = {
                        if (apiKey.isNotBlank() && secretKey.isNotBlank()) {
                            store.saveExchangeKeys(settings.exchangeProvider, apiKey, secretKey)
                            statusStore.write("${settings.exchangeProvider.name} API credentials saved locally.")
                        }
                        if (newsKey.isNotBlank()) store.saveNewsApiKey(newsKey)
                        status = "${settings.exchangeProvider.name.replace('_', ' ')} secrets saved locally"
                    }
                )
                AppTab.HEALTH -> BuildHealthScreen(
                    settings = settings,
                    status = status,
                    statusLevel = statusLevel,
                    onApplySafeDefaults = {
                        val safe = settings.copy(
                            exchangeProvider = ExchangeProvider.PAPER,
                            mode = BotMode.PAPER,
                            manualExecutionMode = false,
                            allowedQuoteAssetsCsv = "EUR",
                            autoSymbolQuoteAsset = "ALL",
                            nonEurQuoteBuyEnabled = false,
                            maxNewTradesPerScan = 1,
                            maxTradesPerHour = 3,
                            maxSimultaneousLivePositions = 3,
                            minimumQuoteReservePercent = BigDecimal("20.0"),
                            trueSelfLearningEnabled = true,
                            spikeProfitTimingEnabled = true,
                            enableBacktestGate = true,
                            enableForwardTestGate = true
                        )
                        persistSettings(safe)
                        statusStore.write("Safe Belgium/Kraken/PAPER defaults applied.", "INFO")
                        status = "Safe defaults applied"
                    }
                )
                AppTab.NOTIFICATIONS -> NotificationsHubScreen(
                    history = statusHistory,
                    settings = settings,
                    onOpen = { currentTab = it }
                )
                AppTab.NOTIFICATION_LOGS -> NotificationCenterScreen(history = statusHistory, settings = settings)
                AppTab.REMOTE_ALERTS -> RemoteAlertsScreen(
                    settings = settings,
                    telegramBotToken = telegramBotToken,
                    telegramChatId = telegramChatId,
                    discordWebhookUrl = discordWebhookUrl,
                    remoteCommandPin = remoteCommandPin,
                    discordBotToken = discordBotToken,
                    discordCommandChannelId = discordCommandChannelId,
                    onTelegramBotToken = { telegramBotToken = it },
                    onTelegramChatId = { telegramChatId = it },
                    onDiscordWebhookUrl = { discordWebhookUrl = it },
                    onRemoteCommandPin = { remoteCommandPin = it },
                    onDiscordBotToken = { discordBotToken = it },
                    onDiscordCommandChannelId = { discordCommandChannelId = it },
                    onSave = {
                        store.saveTelegramConfig(telegramBotToken, telegramChatId)
                        store.saveDiscordWebhook(discordWebhookUrl)
                        store.saveRemoteCommandPin(remoteCommandPin)
                        store.saveDiscordBotCommandConfig(discordBotToken, discordCommandChannelId)
                        val updated = settings.copy(
                            telegramRemoteControlEnabled = telegramBotToken.isNotBlank() && telegramChatId.isNotBlank(),
                            discordRemoteControlEnabled = discordWebhookUrl.isNotBlank(),
                            remoteCommandCenterEnabled = remoteCommandPin.isNotBlank() && (
                                (telegramBotToken.isNotBlank() && telegramChatId.isNotBlank()) ||
                                (discordBotToken.isNotBlank() && discordCommandChannelId.isNotBlank())
                            ),
                            telegramCommandPollingEnabled = telegramBotToken.isNotBlank() && telegramChatId.isNotBlank(),
                            discordCommandPollingEnabled = discordBotToken.isNotBlank() && discordCommandChannelId.isNotBlank(),
                            remoteCommandRequirePin = true,
                            remoteCommandAllowLiveAuto = settings.remoteCommandAllowLiveAuto
                        )
                        persistSettings(updated)
                        statusStore.write("Remote alert settings saved. Telegram=${updated.telegramRemoteControlEnabled}, Discord=${updated.discordRemoteControlEnabled}", "INFO")
                        status = "Remote alerts saved"
                    },
                    onTestTelegram = {
                        onTestTelegram(settings.copy(telegramRemoteControlEnabled = true)) { ok ->
                            status = if (ok) "Telegram test sent" else "Telegram test failed"
                        }
                    },
                    onTestDiscord = {
                        onTestDiscord(settings.copy(discordRemoteControlEnabled = true)) { ok ->
                            status = if (ok) "Discord test sent" else "Discord test failed"
                        }
                    }
                )
                AppTab.BACKUP -> BackupRestoreScreen(
                    settings = settings,
                    backupDirectoryPath = store.backupDirectoryPath(),
                    onBackupDirectoryPathChanged = { store.saveBackupDirectoryPath(it) },
                    onExportFullBackup = { customBackupDirectory, callback ->
                        onExportFullBackup(settings, customBackupDirectory) { result ->
                            callback(result)
                            statusStore.write("Full settings/data backup generated from Backup screen.", "INFO")
                            status = "Full backup generated"
                        }
                    },
                    onRestoreFullBackup = { backupInput, replaceExisting, callback ->
                        onRestoreFullBackup(backupInput, replaceExisting) { result ->
                            callback(result)
                            settings = store.load()
                            statusStore.write("Backup restore requested from Backup screen.", "INFO")
                            status = "Backup restore complete"
                        }
                    },
                    onApplySafeDefaults = {
                        val safe = settings.copy(
                            exchangeProvider = ExchangeProvider.PAPER,
                            mode = BotMode.PAPER,
                            manualExecutionMode = false,
                            allowedQuoteAssetsCsv = "EUR",
                            autoSymbolQuoteAsset = "ALL",
                            nonEurQuoteBuyEnabled = false,
                            maxNewTradesPerScan = 1,
                            maxTradesPerHour = 3,
                            maxSimultaneousLivePositions = 3,
                            minimumQuoteReservePercent = BigDecimal("20.0"),
                            trueSelfLearningEnabled = true,
                            spikeProfitTimingEnabled = true,
                            enableBacktestGate = true,
                            enableForwardTestGate = true
                        )
                        persistSettings(safe)
                        statusStore.write("Backup/Restore applied safe defaults.", "INFO")
                        status = "Safe defaults restored"
                    }
                )
            }
        }
    }
}

@Composable
private fun HeaderBar(status: String, mode: BotMode, level: String) {
    Column(modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Crypto TradeStation", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Adaptive crypto trading and market intelligence", color = Muted)
            }
            StatusPill(text = mode.name.replace('_', ' '), color = modeColor(mode))
        }
        Spacer(Modifier.height(10.dp))
        GlassCard {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(levelColor(level))
                Spacer(Modifier.width(10.dp))
                Text(status, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                StatusPill(level, levelColor(level))
                Spacer(Modifier.width(8.dp))
                Text("v3.1.3 CTS", color = Mint, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun AppTabs(currentTab: AppTab, onTabSelected: (AppTab) -> Unit) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val liveTabs = listOf(
            AppTab.DASHBOARD,
            AppTab.AI,
            AppTab.NEWS,
            AppTab.SELF_LEARNING,
            AppTab.CHART,
            AppTab.PORTFOLIO,
            AppTab.SETTINGS,
            AppTab.NOTIFICATIONS
        )
        liveTabs.forEach { tab ->
            FilterChip(
                selected = currentTab == tab,
                onClick = { onTabSelected(tab) },
                label = { Text(tab.label) }
            )
        }
    }
}

@Composable
private fun DashboardScreen(
    settings: BotSettings,
    status: String,
    decisions: List<AiDecision>,
    activePositionSymbols: List<String>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onScan: () -> Unit,
    onExecute: () -> Unit,
    onOpenNews: () -> Unit
) {
    val activeSet = activePositionSymbols.map { it.uppercase().replace("/", "").replace("-", "") }.toSet()
    val dashboardDecisions = decisions
        .sortedWith(compareByDescending<AiDecision> { activeSet.contains(it.symbol.uppercase().replace("/", "").replace("-", "")) }
            .thenByDescending { it.finalScore })
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            HeroCard(
                title = "Live AI Trading Console",
                subtitle = "Auto strategy selection, backtest gate, regime detection, smart orders, tax guard, trade memory and news intelligence in one Android app.",
                primaryButton = "Scan Market",
                secondaryButton = "Execute Once",
                onPrimary = onScan,
                onSecondary = onExecute
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Mode", settings.mode.name.replace('_', ' '), "Current execution profile", modeColor(settings.mode)) }
                item { MetricCard("Max Position", "€${settings.maxPositionEur}", "Per trade exposure", Electric) }
                item { MetricCard("Daily Loss Guard", "€${settings.maxDailyLossEur}", "Auto-block threshold", Danger) }
                item { MetricCard("Symbols", settings.symbols().size.toString(), settings.symbolsCsv, Mint) }
                item { MetricCard("Active", activePositionSymbols.size.toString(), activePositionSymbols.take(4).joinToString(",").ifBlank { "none" }, Amber) }
            }
        }
        item {
            GlassCard {
                SectionTitle("Bot Start Controls", "Start Background Auto Bot keeps scanning/executing automatically while the persistent Android service is running.")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color(0xFF06130F))
                    ) { Text("Start Background Auto Bot") }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { OutlinedButton(onClick = onStop) { Text("Stop Bot") } }
                        item { Button(onClick = onScan) { Text("Scan Once") } }
                        item { OutlinedButton(onClick = onExecute) { Text("Execute Once") } }
                        item { OutlinedButton(onClick = onOpenNews) { Text("News Dashboard") } }
                    }
                }
            }
        }
        item { SectionTitle("Top AI Decisions", if (dashboardDecisions.isEmpty()) "No live scan results yet. Press Scan Market to load real decisions." else status) }
        if (dashboardDecisions.isEmpty()) {
            item {
                GlassCard {
                    Text("No real AI decisions loaded yet.", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Older builds showed hardcoded BTCEUR/ETHEUR sample decisions here. This build removes those placeholder cards. Press Scan Market to scan configured symbols plus active positions.", color = Muted)
                    if (activePositionSymbols.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Detected active chart symbols: ${activePositionSymbols.joinToString(", ")}", color = Amber)
                    }
                }
            }
        } else {
            items(dashboardDecisions.take(8)) { decision ->
                val normalized = decision.symbol.uppercase().replace("/", "").replace("-", "")
                Column {
                    if (activeSet.contains(normalized)) {
                        Text("ACTIVE POSITION", color = Amber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    }
                    DecisionCard(decision)
                }
            }
        }
    }
}

@Composable
private fun LiveStatusScreen(
    status: String,
    level: String,
    history: List<String>,
    settings: BotSettings,
    onClear: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Live Bot Status", "This shows exactly what the foreground service, AI engine, exchange connector, and execution guard are doing.") }
        item {
            GlassCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(levelColor(level))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Current step", color = Muted)
                        Text(status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    StatusPill(level, levelColor(level))
                }
            }
        }
        item {
            GlassCard {
                SectionTitle("Trading Readiness", "If one of these is wrong, live orders will be blocked before reaching Kraken.")
                val paperReady = settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER
                val liveReady = settings.exchangeProvider == ExchangeProvider.KRAKEN && settings.mode == BotMode.LIVE_AUTO
                ToggleInfo("Exchange provider: ${settings.exchangeProvider}", paperReady || settings.exchangeProvider == ExchangeProvider.KRAKEN)
                ToggleInfo("Execution mode: ${settings.mode}", paperReady || settings.mode == BotMode.LIVE_AUTO)
                ToggleInfo("Paper execution active", paperReady)
                ToggleInfo("Live acknowledgement", paperReady || settings.liveTradingAcknowledged)
                ToggleInfo("Manual execution mode OFF", !settings.manualExecutionMode)
                ToggleInfo("Market orders", settings.enableMarketOrders || paperReady)
                ToggleInfo("Max position > 0", settings.maxPositionEur > BigDecimal.ZERO)
                ToggleInfo("Symbols: ${settings.symbolsCsv}", settings.symbols().isNotEmpty())
            }
        }
        item {
            GlassCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Status Timeline", "Newest entry first. Errors from Kraken or the strategy engine appear here.")
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClear) { Text("Clear") }
                }
                Spacer(Modifier.height(8.dp))
                if (history.isEmpty()) {
                    Text("No status events yet. Start the service or run an execution pass.", color = Muted)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        history.forEach { line ->
                            StatusLogRow(line)
                        }
                    }
                }
            }
        }
        item { WarningCard("If the timeline says WATCH, WAIT, score too low, manual/read-only, missing credentials, or live acknowledgement disabled, the bot is working but correctly refusing to place a live order.") }
    }
}

@Composable
private fun StatusLogRow(line: String) {
    val color = when {
        line.contains("[ERROR]") -> Danger
        line.contains("[WARN]") -> Amber
        line.contains("[LIVE]") -> Mint
        else -> Muted
    }
    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF0B1220), border = BorderStroke(1.dp, Stroke)) {
        Text(line, modifier = Modifier.fillMaxWidth().padding(10.dp), color = color)
    }
}

@Composable
private fun BotControlScreen(
    settings: BotSettings,
    symbols: String,
    onSymbolsChange: (String) -> Unit,
    onModeChange: (BotMode) -> Unit,
    onLiveAckChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Bot Control", "Choose how aggressive the bot is allowed to be.") }
        item {
            GlassCard {
                Text("Execution Mode", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(BotMode.values().toList()) { mode ->
                        FilterChip(selected = settings.mode == mode, onClick = { onModeChange(mode) }, label = { Text(mode.name.replace('_', ' ')) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                ToggleRow("I understand live trading can lose real money", settings.liveTradingAcknowledged, onLiveAckChange)
            }
        }
        item {
            GlassCard {
                SectionTitle("Market Universe", "Comma-separated symbols for the selected exchange. Binance-style symbols like BTCEUR/ETHEUR are still accepted for paper and read-only scans.")
                OutlinedTextField(value = symbols, onValueChange = onSymbolsChange, label = { Text("Symbols") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Button(onClick = onSave) { Text("Save Symbols") } }
                    item { Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color(0xFF06130F))) { Text("Start Background Auto Bot") } }
                    item { OutlinedButton(onClick = onStop) { Text("Stop") } }
                }
            }
        }
        item { WarningCard("Live-auto mode still uses execution guards. Keep withdrawal permission disabled on exchange API keys.") }
    }
}


@Composable
private fun SymbolScannerScreen(
    settings: BotSettings,
    candidates: List<SymbolDiscoveryCandidate>,
    onRefresh: () -> Unit,
    onEnableAutoDiscovery: (Boolean) -> Unit,
    onUseTopSymbols: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Auto Symbol Scanner", "All Kraken spot markets are discovered, validated, scored, ranked and used for multi-symbol rotation.") }
        item {
            GlassCard {
                ToggleRow("Auto symbol discovery", settings.autoSymbolDiscoveryEnabled, onEnableAutoDiscovery)
                Spacer(Modifier.height(8.dp))
                ToggleInfo("Provider must be Kraken", settings.exchangeProvider == ExchangeProvider.KRAKEN)
                ToggleInfo("Candidates scanned", candidates.isNotEmpty())
                ToggleInfo("Enabled symbols: ${candidates.count { it.enabledForRotation }}", candidates.any { it.enabledForRotation })
                Text("Universe: ${settings.autoSymbolQuoteAsset}. Allowed quotes: ${settings.allowedQuoteAssetsCsv}", color = Muted)
                Text("Limits: spread ≤ ${settings.autoSymbolMaxSpreadPercent}%, min 24h quote-volume ${settings.autoSymbolMinVolume24hEur}, active limit ${settings.autoSymbolActiveLimit}", color = Muted)
                Text("Rotation safety: max new trades/scan ${settings.maxNewTradesPerScan}, max trades/hour ${settings.maxTradesPerHour}, max positions ${settings.maxSimultaneousLivePositions}", color = Muted)
                Text("Reserve guard: keep ${settings.minimumQuoteReserveAmount} or ${settings.minimumQuoteReservePercent}% of quote balance before BUY orders.", color = Muted)
                Text("Cooldowns: buy ${settings.cooldownAfterBuyMinutes}m, sell ${settings.cooldownAfterSellMinutes}m, loss ${settings.cooldownAfterLossMinutes}m.", color = Muted)
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Button(onClick = onRefresh) { Text("Scan Kraken Symbols") } }
                    item { OutlinedButton(onClick = onUseTopSymbols, enabled = candidates.any { it.enabledForRotation }) { Text("Use Top Symbols") } }
                }
            }
        }
        item {
            GlassCard {
                SectionTitle("Selected Rotation", "These are the symbols currently allowed by the scanner.")
                val enabled = candidates.filter { it.enabledForRotation }.take(settings.autoSymbolActiveLimit.coerceAtLeast(1))
                if (enabled.isEmpty()) {
                    Text("No enabled candidates yet. Tap Scan Kraken Symbols. If none pass, reduce min quote-volume or increase max spread carefully.", color = Muted)
                } else {
                    Text(enabled.joinToString(",") { it.symbol }, color = Mint, fontWeight = FontWeight.Bold)
                }
            }
        }
        items(candidates.take(60)) { candidate ->
            SymbolCandidateCard(candidate)
        }
    }
}

@Composable
private fun SymbolCandidateCard(candidate: SymbolDiscoveryCandidate) {
    val color = when {
        candidate.enabledForRotation -> Mint
        candidate.tradable -> Amber
        else -> Danger
    }
    GlassCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(candidate.symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Pair ${candidate.exchangePair} • ${candidate.baseAsset}/${candidate.quoteAsset} • ${candidate.quoteAsset} quote", color = Muted)
            }
            StatusPill(if (candidate.enabledForRotation) "ENABLED" else "SKIPPED", color)
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item { MetricMini("Score", candidate.score.toString(), color) }
            item { MetricMini("Spread", "${candidate.spreadPercent}%", Electric) }
            item { MetricMini("24h Vol", "€${candidate.volume24hEur}", Mint) }
            item { MetricMini("24h", "${candidate.change24hPercent}%", Amber) }
            item { MetricMini("Min", candidate.minOrderSize.stripTrailingZeros().toPlainString(), Muted) }
        }
        Spacer(Modifier.height(8.dp))
        Text(candidate.reason, color = Muted)
    }
}

@Composable
private fun MetricMini(label: String, value: String, color: Color) {
    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF0B1220), border = BorderStroke(1.dp, Stroke)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(value, color = color, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AiSignalsScreen(
    decisions: List<AiDecision>,
    settings: BotSettings,
    activePositionSymbols: List<String>,
    onScan: () -> Unit
) {
    val activeSet = activePositionSymbols.map { it.uppercase().replace("/", "").replace("-", "") }.toSet()
    val sortedDecisions = decisions
        .sortedWith(compareByDescending<AiDecision> { activeSet.contains(it.symbol.uppercase().replace("/", "").replace("-", "")) }
            .thenByDescending { it.finalScore })
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("AI Signals", "Real scan decisions for configured symbols plus active positions. No BTC/ETH placeholders.") }
        item {
            GlassCard {
                ToggleInfo("Recovered scalping strategy", settings.recoveredScalpingStrategyEnabled)
                ToggleInfo("News sentiment", settings.useNewsAi)
                ToggleInfo("Trade memory learning", settings.useTradeMemoryAi)
                ToggleInfo("Tax-aware selling", settings.taxOptimization)
                ToggleInfo("Active position symbols", activePositionSymbols.isNotEmpty())
                Spacer(Modifier.height(10.dp))
                Text("Scan universe: ${(settings.symbols() + activePositionSymbols).distinct().joinToString(", ").ifBlank { "none" }}", color = Muted)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onScan, colors = ButtonDefaults.buttonColors(containerColor = Electric), modifier = Modifier.fillMaxWidth()) {
                    Text("Scan AI Signals")
                }
            }
        }
        if (sortedDecisions.isEmpty()) {
            item {
                GlassCard {
                    Text("No real AI signal scan loaded yet.", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Press Scan AI Signals to scan configured symbols plus active positions. Older builds could show BTCEUR/ETHEUR placeholders; this screen now waits for real scan results.", color = Muted)
                    if (activePositionSymbols.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Detected active symbols: ${activePositionSymbols.joinToString(", ")}", color = Amber)
                    }
                }
            }
        } else {
            items(sortedDecisions) { decision ->
                val normalized = decision.symbol.uppercase().replace("/", "").replace("-", "")
                Column {
                    if (activeSet.contains(normalized)) {
                        Text("ACTIVE POSITION", color = Amber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    }
                    DecisionCard(decision = decision, expanded = true)
                }
            }
        }
    }
}


@Composable
private fun StrategyScreen(settings: BotSettings, onToggleStrategy: (Boolean) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Recovered Scalping Strategy", "Multi-timeframe EMA + OBV + ATR + momentum, adapted from the earlier TradingView/Binance bot concept.") }
        item {
            GlassCard {
                ToggleRow("Enable recovered scalping strategy", settings.recoveredScalpingStrategyEnabled, onToggleStrategy)
                Text("Entry filter", color = Muted)
                Text("Buy only when at least ${settings.minTrendAgreement}/3 timeframes agree and the strategy score reaches ${settings.minStrategyScoreToBuy}.", fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            GlassCard {
                SectionTitle("Strategy Library", "Implemented now plus strong future candidates.")
                Text("Currently implemented/selectable: SCALPING, TREND, BREAKOUT, REVERSAL, NEWS_MOMENTUM, plus AUTO/adaptive selection.", color = Muted)
                Divider(color = Stroke)
                Text("Good next additions:", fontWeight = FontWeight.Bold)
                Text("• Mean reversion / RSI-Bollinger squeeze", color = Muted)
                Text("• VWAP pullback / intraday fair-value strategy", color = Muted)
                Text("• Donchian breakout / volatility expansion", color = Muted)
                Text("• Range grid with inventory/risk caps", color = Muted)
                Text("• Market-making spread capture with order-book imbalance", color = Muted)
                Text("• Funding/news-event risk-off strategy", color = Muted)
                Text("• Pairs/relative-strength rotation between correlated assets", color = Muted)
                Text("• DCA accumulation mode with crash protection", color = Muted)
                Text("• Momentum spike continuation with time-based exit", color = Muted)
                Text("• Volume anomaly / whale-move detection from order book depth", color = Muted)
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("EMA Fast", settings.emaFastPeriod.toString(), "Fast trend line", Electric) }
                item { MetricCard("EMA Slow", settings.emaSlowPeriod.toString(), "Slow trend line", Mint) }
                item { MetricCard("OBV Lookback", settings.obvLookback.toString(), "Volume confirmation", Amber) }
                item { MetricCard("ATR Period", settings.atrPeriod.toString(), "Volatility sizing", Danger) }
            }
        }
        item {
            GlassCard {
                SectionTitle("Decision formula", "The technical score becomes the base AI score.")
                Text("Score = EMA trend agreement + OBV confirmation + short-term momentum - ATR volatility penalty + liquidity/spread adjustment", color = Muted)
                Divider(color = Stroke)
                Text("Take-profit target: ATR × ${settings.takeProfitAtrMultiplier}", fontWeight = FontWeight.Bold)
                Text("Stop-loss target: ATR × ${settings.stopLossAtrMultiplier}", fontWeight = FontWeight.Bold)
                Text("The AI layer then adjusts this with news sentiment and previous-trade memory before execution guards decide whether a live order is allowed.", color = Muted)
            }
        }
        item { WarningCard("This strategy is not failproof. It is implemented as a guarded scalping system with risk caps, spread filters, and live-mode acknowledgement.") }
    }
}





@Composable
private fun HubActionCard(
    title: String,
    subtitle: String,
    buttonText: String,
    onClick: () -> Unit
) {
    GlassCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Button(onClick = onClick, colors = ButtonDefaults.buttonColors(containerColor = Electric)) {
                Text(buttonText)
            }
        }
    }
}

@Composable
private fun AiHubScreen(
    decisions: List<AiDecision>,
    settings: BotSettings,
    performanceLabSnapshot: PerformanceLabSnapshot?,
    onOpen: (AppTab) -> Unit,
    onRefreshPerformance: () -> Unit
) {
    val buySignals = decisions.count { it.finalAction == SignalAction.BUY || it.finalAction == SignalAction.SMALL_BUY }
    val sellSignals = decisions.count { it.finalAction == SignalAction.SELL || it.finalAction == SignalAction.AVOID || it.finalAction == SignalAction.STRONG_AVOID }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("AI Center", "Signals, strategies, backtests, performance, rotation, auto-tuning and live safety in one place.") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("AI decisions", decisions.size.toString(), "Latest scan", Electric) }
                item { MetricCard("Buy pressure", buySignals.toString(), "BUY/SMALL_BUY", Mint) }
                item { MetricCard("Exit pressure", sellSignals.toString(), "SELL/AVOID", Danger) }
                item { MetricCard("Strategy", settings.strategyMode.name, "Selected mode", Amber) }
            }
        }
        item { HubActionCard("AI Signals", "Latest combined AI decisions and explanations.", "Open", { onOpen(AppTab.AI_SIGNALS) }) }
        item { HubActionCard("Strategy Lab", "Technical strategy, scoring logic and strategy controls.", "Open", { onOpen(AppTab.STRATEGY) }) }
        item { HubActionCard("Backtest Lab", "Run Kraken OHLC backtests and forward tests.", "Open", { onOpen(AppTab.BACKTEST) }) }
        item { HubActionCard("Regime Detection", "Trend, volatility and market regime behavior.", "Open", { onOpen(AppTab.REGIME) }) }
        item { HubActionCard("Performance Lab", performanceLabSnapshot?.summaryLine ?: "Paper/live strategy promotion overview.", "Open", { onOpen(AppTab.PERFORMANCE) }) }
        item { HubActionCard("Strategy Sandbox", "Compare strategy candidates before applying live risk.", "Open", { onOpen(AppTab.SANDBOX) }) }
        item { HubActionCard("Portfolio Rotation", "EUR-first allocation and rotation policy.", "Open", { onOpen(AppTab.PORTFOLIO_ROTATION) }) }
        item { HubActionCard("Strategy Auto-Tuner", "Test multiple strategies and recommend the strongest candidate.", "Open", { onOpen(AppTab.AUTO_TUNER) }) }
        item { HubActionCard("Release Safety", "Final LIVE_AUTO safety checklist.", "Open", { onOpen(AppTab.RELEASE_SAFETY) }) }
        item {
            GlassCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Refresh AI performance", fontWeight = FontWeight.ExtraBold)
                        Text("Reloads the Performance Lab summary without changing tabs.", color = Muted)
                    }
                    OutlinedButton(onClick = onRefreshPerformance) { Text("Refresh") }
                }
            }
        }
    }
}

@Composable
private fun ChartHubScreen(
    settings: BotSettings,
    candles: List<Candle>,
    trades: List<TradeEntity>,
    selectedSymbol: String,
    selectedTimeframe: Timeframe,
    activePositionSymbols: List<String>,
    latestDecision: AiDecision?,
    autoRefresh: Boolean,
    onOpen: (AppTab) -> Unit,
    onRefresh: () -> Unit
) {
    val symbolTrades = trades.filter { it.symbol.equals(selectedSymbol, ignoreCase = true) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Chart Center", "Live Kraken chart, trade markers, replay and journal tools grouped together.") }
        item {
            GlassCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("$selectedSymbol $selectedTimeframe", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                        Text("Candles=${candles.size} • Trades=${symbolTrades.size} • Auto-refresh=${if (autoRefresh) "ON" else "OFF"}", color = Muted)
                    }
                    StatusPill(latestDecision?.finalAction?.name ?: "WAIT", actionColor(latestDecision?.finalAction ?: SignalAction.WAIT))
                }
                Spacer(Modifier.height(12.dp))
                CandlestickChart(candles = candles, settings = settings, trades = symbolTrades, windowSize = 72, panOffset = 0, showVolume = true)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = Electric)) { Text("Refresh") }
                    OutlinedButton(onClick = { onOpen(AppTab.CHART_MAIN) }) { Text("Full Chart") }
                }
            }
        }
        item { HubActionCard("Unified Live Chart", "Single chart with candles, volume, zoom/pan, auto-refresh, TP/SL and entry/exit markers.", "Open", { onOpen(AppTab.CHART_MAIN) }) }
        item { HubActionCard("Trade Overlay Details", "Marker statistics and position overlay details for the same chart data.", "Open", { onOpen(AppTab.TRADE_OVERLAY) }) }
        item { HubActionCard("Trade Replay", "Replay candles step-by-step.", "Open", { onOpen(AppTab.REPLAY) }) }
        item { HubActionCard("Trade Journal", "Local paper/live trade database with fees, AI score and P/L.", "Open", { onOpen(AppTab.TRADE_JOURNAL) }) }
    }
}

@Composable
private fun SelfLearningHubScreen(
    settings: BotSettings,
    summary: TrueSelfLearningEngine.LearningSummary?,
    onOpen: (AppTab) -> Unit,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Self Learning Center", "All learning, adaptive strategy, learned hold and spike-timing tools grouped together.") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Learning", if (settings.trueSelfLearningEnabled) "ON" else "OFF", "Global", Mint) }
                item { MetricCard("Profiles", (summary?.symbolProfiles?.size ?: 0).toString(), "Symbols", Electric) }
                item { MetricCard("Strategies", (summary?.strategyProfiles?.size ?: 0).toString(), "Strategies", Amber) }
                item { MetricCard("Hold profiles", (summary?.holdProfiles?.size ?: 0).toString(), "Learned hold", Mint) }
            }
        }
        item {
            GlassCard {
                Text(summary?.summaryLine ?: "No self-learning summary loaded yet.", color = Muted)
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = Electric)) { Text("Refresh Learning") }
            }
        }
        item { HubActionCard("Learning Summary", "Self-learning summary and refresh controls.", "Open", { onOpen(AppTab.SELF_LEARNING_MAIN) }) }
        item { HubActionCard("Learning DB Inspector", "Symbol profiles, strategy profiles, confidence, boost and penalty logic.", "Open", { onOpen(AppTab.LEARNING_INSPECTOR) }) }
        item { HubActionCard("Smart Exit v2", "Learned hold, spike timing, trailing and profit-lock behavior.", "Open", { onOpen(AppTab.SMART_EXIT) }) }
    }
}


@Composable
private fun SystemTestScreen(
    settings: BotSettings,
    lines: List<String>,
    onRun: () -> Unit
) {
    val pass = lines.count { it.startsWith("PASS") }
    val fail = lines.count { it.startsWith("FAIL") }
    val warn = lines.count { it.startsWith("WARN") }
    val notConfigured = lines.count { it.startsWith("NOT_CONFIGURED") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("System Test + Feature Verification", "On-device checks for live wiring, Kraken data, alerts, chart feed, trade journal and release safety.") }
        item {
            GlassCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Feature verification", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                        Text("Provider=${settings.exchangeProvider} • Mode=${settings.mode}", color = Muted)
                    }
                    Button(onClick = onRun, colors = ButtonDefaults.buttonColors(containerColor = Electric)) {
                        Text("Run Tests")
                    }
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("PASS", pass.toString(), "Working", Mint) }
                item { MetricCard("FAIL", fail.toString(), "Needs fix", Danger) }
                item { MetricCard("WARN", warn.toString(), "Check", Amber) }
                item { MetricCard("Not configured", notConfigured.toString(), "Setup needed", Muted) }
            }
        }
        if (lines.isEmpty()) {
            item {
                GlassCard {
                    Text("No system test has been run yet.", color = Muted)
                    Text("Press Run Tests. Telegram/Discord tests only run when credentials are configured and enabled.", color = Amber)
                    Text("The live order path is verified as wired, but this test does not place a real order for safety.", color = Amber)
                    Text("LIVE_AUTO background start now runs this verification first and blocks startup if critical FAIL checks are found.", color = Amber)
                }
            }
        } else {
            items(lines) { row ->
                val parts = row.split("|").map { it.trim() }
                val status = parts.getOrNull(0) ?: "INFO"
                val name = parts.getOrNull(1) ?: "Check"
                val detail = parts.drop(2).joinToString(" | ").ifBlank { row }
                val color = when (status) {
                    "PASS" -> Mint
                    "FAIL" -> Danger
                    "WARN" -> Amber
                    "NOT_CONFIGURED" -> Muted
                    else -> Electric
                }
                GlassCard {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.ExtraBold)
                            Text(detail, color = Muted)
                        }
                        StatusPill(status, color)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHubScreen(
    settings: BotSettings,
    systemTestLines: List<String>,
    onOpen: (AppTab) -> Unit,
    onModeChange: (BotMode) -> Unit,
    onLiveAckChange: (Boolean) -> Unit,
    onRunSystemTest: () -> Unit,
    onApplySafeDefaults: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Settings Center", "All editable settings, API keys, alerts, backups and safety controls grouped together.") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Provider", settings.exchangeProvider.name, "Exchange", Electric) }
                item { MetricCard("Mode", settings.mode.name, "Trading", Mint) }
                item { MetricCard("Quotes", settings.allowedQuoteAssetsCsv, "Allowed", Amber) }
                item { MetricCard("Max position", "€${settings.maxPositionEur}", "Risk", Danger) }
            }
        }
        item {
            GlassCard {
                SectionTitle("Trading Mode", "This controls whether the background bot runs in paper mode, live confirmation mode, or full live auto mode.")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(BotMode.values().toList()) { mode ->
                        FilterChip(
                            selected = settings.mode == mode,
                            onClick = { onModeChange(mode) },
                            label = { Text(mode.name.replace('_', ' ')) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Current mode: ${settings.mode.name.replace('_', ' ')}", color = modeColor(settings.mode), fontWeight = FontWeight.Bold)
                Text("PAPER = fake/local orders using live Kraken data. LIVE_CONFIRM = live scanning without auto execution. LIVE_AUTO = guarded automatic live execution.", color = Muted)
            }
        }
        item {
            GlassCard {
                SectionTitle("Live Acknowledgement", "Required before LIVE_AUTO can send real Kraken orders.")
                ToggleRow("I understand live trading can lose real money", settings.liveTradingAcknowledged, onLiveAckChange)
                Text("Current: ${if (settings.liveTradingAcknowledged) "ACKNOWLEDGED" else "NOT ACKNOWLEDGED"}", color = if (settings.liveTradingAcknowledged) Mint else Danger, fontWeight = FontWeight.Bold)
                Text("Keep Kraken API withdrawal permission OFF. This acknowledgement only permits guarded trading logic; it does not bypass safety checks.", color = Amber)
            }
        }
        item { HubActionCard("Basic Settings", "Provider, Kraken/API keys, symbols and main risk fields.", "Open", { onOpen(AppTab.BASIC_SETTINGS) }) }
        item {
            GlassCard {
                val failures = systemTestLines.count { it.startsWith("FAIL") }
                val warnings = systemTestLines.count { it.startsWith("WARN") }
                val notConfigured = systemTestLines.count { it.startsWith("NOT_CONFIGURED") }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("System Test + Feature Verification", fontWeight = FontWeight.ExtraBold)
                        Text("Checks Kraken data, chart feed, trade journal, alerts, safety lock and live wiring.", color = Muted)
                        Text("Last result: fail=$failures, warn=$warnings, not configured=$notConfigured, rows=${systemTestLines.size}", color = if (failures == 0) Mint else Danger)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Button(onClick = onRunSystemTest, colors = ButtonDefaults.buttonColors(containerColor = Electric)) { Text("Run") }
                        TextButton(onClick = { onOpen(AppTab.SYSTEM_TEST) }) { Text("Details") }
                    }
                }
            }
        }
        item { HubActionCard("Advanced Settings", "Clean unified automation controls: price caps, per-symbol rules, live guards, duplicate-position protection and risk limits.", "Open", { onOpen(AppTab.ADVANCED_SETTINGS) }) }
        item { HubActionCard("Remote Alerts", "Telegram bot token, Telegram chat ID and Discord webhook.", "Open", { onOpen(AppTab.REMOTE_ALERTS) }) }
        item { HubActionCard("Backup / Restore", "Export safe text backup and restore safe defaults.", "Open", { onOpen(AppTab.BACKUP) }) }
        item { HubActionCard("Build Health", "Pre-push status and app health checklist.", "Open", { onOpen(AppTab.HEALTH) }) }
        item {
            GlassCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Safe Belgium/Kraken defaults", fontWeight = FontWeight.ExtraBold)
                        Text("Applies PAPER mode, EUR quote, safety gates, and conservative live-risk defaults.", color = Muted)
                    }
                    Button(onClick = onApplySafeDefaults, colors = ButtonDefaults.buttonColors(containerColor = Electric)) { Text("Apply") }
                }
            }
        }
    }
}

@Composable
private fun NotificationsHubScreen(
    history: List<String>,
    settings: BotSettings,
    onOpen: (AppTab) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Notifications + Logs", "Remote alerts, local event history, status, orders and Kraken diagnostics grouped together.") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Events", history.size.toString(), "Recent logs", Electric) }
                item { MetricCard("Telegram", if (settings.telegramRemoteControlEnabled) "ON" else "OFF", "Remote", Mint) }
                item { MetricCard("Discord", if (settings.discordRemoteControlEnabled) "ON" else "OFF", "Webhook", Amber) }
                item { MetricCard("Watchdog", if (settings.watchdogEnabled) "ON" else "OFF", "Safety", Danger) }
            }
        }
        item {
            GlassCard {
                Text("Latest events", fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                history.take(8).forEach {
                    Text("• $it", color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item { HubActionCard("Event Logs", "Full notification center and recent bot event feed.", "Open", { onOpen(AppTab.NOTIFICATION_LOGS) }) }
        item { HubActionCard("Remote Alerts", "Telegram and Discord setup/testing.", "Open", { onOpen(AppTab.REMOTE_ALERTS) }) }
        item { HubActionCard("Live Status", "Current bot/service status.", "Open", { onOpen(AppTab.STATUS) }) }
        item { HubActionCard("Orders", "Open orders, cancel tools and live order status.", "Open", { onOpen(AppTab.ORDERS) }) }
        item { HubActionCard("Kraken Health", "Public/private API diagnostics and balance checks.", "Open", { onOpen(AppTab.KRAKEN_HEALTH) }) }
        item { HubActionCard("History", "Saved history, tax, logs and previous activity.", "Open", { onOpen(AppTab.HISTORY) }) }
    }
}

@Composable
private fun ChartScreen(
    settings: BotSettings,
    candles: List<Candle>,
    selectedSymbol: String,
    selectedTimeframe: Timeframe,
    activePositionSymbols: List<String>,
    latestDecision: AiDecision?,
    trades: List<TradeEntity>,
    autoRefresh: Boolean,
    onAutoRefreshChange: (Boolean) -> Unit,
    onSymbolChange: (String) -> Unit,
    onTimeframeChange: (Timeframe) -> Unit,
    onRefresh: () -> Unit
) {
    val symbols = (activePositionSymbols + settings.symbols())
        .map { it.uppercase().replace("/", "").replace("-", "") }
        .filter { it.isNotBlank() }
        .distinct()
        .ifEmpty { listOf("BTCEUR", "ETHEUR") }
    val activeSet = activePositionSymbols.map { it.uppercase().replace("/", "").replace("-", "") }.toSet()
    var windowSize by remember { mutableStateOf(72) }
    var panOffset by remember { mutableStateOf(0) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionTitle("Unified Live Chart", "One live auto-updating chart with candles, volume, TP/SL, current price, zoom/pan, AI status and actual entry/exit markers.")
        }
        item {
            GlassCard {
                Column {
                    Text("Active Position Charts", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (activePositionSymbols.isEmpty()) {
                        Text("No active positions detected yet. The list refreshes from Portfolio + Lifecycle while this chart tab is open.", color = Muted)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(activePositionSymbols) { symbol ->
                                FilterChip(
                                    selected = symbol.equals(selectedSymbol, ignoreCase = true),
                                    onClick = { onSymbolChange(symbol) },
                                    label = { Text(symbol) }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("All chart symbols", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(symbols) { symbol ->
                            FilterChip(
                                selected = symbol.equals(selectedSymbol, ignoreCase = true),
                                onClick = { onSymbolChange(symbol) },
                                label = { Text(if (activeSet.contains(symbol)) "$symbol • ACTIVE" else symbol) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Timeframe", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf(Timeframe.M5, Timeframe.M15, Timeframe.H1, Timeframe.H4)) { tf ->
                            FilterChip(
                                selected = tf == selectedTimeframe,
                                onClick = { onTimeframeChange(tf) },
                                label = { Text(tf.name) }
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    ToggleRow("Live auto-refresh every 30 seconds", autoRefresh, onAutoRefreshChange)
                    Spacer(Modifier.height(8.dp))
                    Text("View window: $windowSize candles • Pan offset: $panOffset candles", color = Muted)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { ElevatedButton(onClick = onRefresh) { Text("Refresh Now") } }
                        item { OutlinedButton(onClick = { windowSize = (windowSize - 24).coerceAtLeast(24) }) { Text("Zoom In") } }
                        item { OutlinedButton(onClick = { windowSize = (windowSize + 24).coerceAtMost(240) }) { Text("Zoom Out") } }
                        item { OutlinedButton(onClick = { panOffset = (panOffset + 12).coerceAtMost(240) }) { Text("Pan Left") } }
                        item { OutlinedButton(onClick = { panOffset = (panOffset - 12).coerceAtLeast(0) }) { Text("Pan Right") } }
                        item { OutlinedButton(onClick = { panOffset = 0; windowSize = 72 }) { Text("Reset") } }
                    }
                }
            }
        }

        item {
            GlassCard {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("$selectedSymbol ${selectedTimeframe.name}", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                            Text("Candles=${candles.size} • Trades=${trades.size} • Auto-refresh=${if (autoRefresh) "ON" else "OFF"}", color = Muted)
                        }
                        StatusPill(latestDecision?.finalAction?.name ?: "NO SIGNAL", actionColor(latestDecision?.finalAction ?: SignalAction.WAIT))
                    }
                    Spacer(Modifier.height(12.dp))
                    CandlestickChart(candles = candles, settings = settings, trades = trades, windowSize = windowSize, panOffset = panOffset, showVolume = true)
                    Spacer(Modifier.height(12.dp))
                    ChartStats(candles = candles, decision = latestDecision, settings = settings)
                }
            }
        }

        item {
            GlassCard {
                SectionTitle("Chart feature status", "All chart features are consolidated into the single live chart above.")
                ToggleInfo("Live Kraken OHLC feed", candles.isNotEmpty())
                ToggleInfo("Live auto-refresh", autoRefresh)
                ToggleInfo("Candlestick bodies + wicks", true)
                ToggleInfo("Volume bars", true)
                ToggleInfo("Zoom / pan controls", true)
                ToggleInfo("Active position symbols", activePositionSymbols.isNotEmpty())
                ToggleInfo("Actual entry/exit markers", trades.isNotEmpty())
                ToggleInfo("TP / SL overlay lines", true)
                ToggleInfo("Current price marker", true)
                ToggleInfo("Spike timing", settings.spikeProfitTimingEnabled)
                ToggleInfo("Learned hold", settings.learnedHoldForProfitEnabled)
                ToggleInfo("Trailing stop", settings.enableTrailingStop)
                Text("Markers appear after paper/live trades exist in the local trade journal.", color = Muted)
            }
        }
    }
}

@Composable
private fun PriceLineChart(candles: List<Candle>, settings: BotSettings) {
    val lineColor = Mint
    val tpColor = Amber
    val slColor = Danger
    val gridColor = Stroke
    val closes = candles.map { it.close }.filter { it > BigDecimal.ZERO }

    if (closes.size < 2) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(PanelAlt, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("No chart data yet. Press Refresh Kraken Chart.", color = Muted)
        }
        return
    }

    val minPrice = closes.minOrNull() ?: BigDecimal.ZERO
    val maxPrice = closes.maxOrNull() ?: BigDecimal.ONE
    val range = (maxPrice - minPrice).takeIf { it > BigDecimal.ZERO } ?: BigDecimal.ONE
    val last = closes.last()
    val tp = last.multiply(BigDecimal.ONE.add(settings.takeProfitPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
    val sl = last.multiply(BigDecimal.ONE.subtract(settings.stopLossPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PanelAlt)
            .padding(8.dp)
    ) {
        val w = size.width
        val h = size.height
        val leftPad = 10f
        val rightPad = 10f
        val topPad = 14f
        val bottomPad = 18f
        val chartW = w - leftPad - rightPad
        val chartH = h - topPad - bottomPad

        repeat(4) { idx ->
            val y = topPad + chartH * idx / 3f
            drawLine(color = gridColor, start = Offset(leftPad, y), end = Offset(w - rightPad, y), strokeWidth = 1f)
        }

        fun priceToY(price: BigDecimal): Float {
            val normalized = price.subtract(minPrice).divide(range, 8, RoundingMode.HALF_UP).toFloat()
            return topPad + chartH * (1f - normalized)
        }

        val path = Path()
        closes.forEachIndexed { idx, close ->
            val x = leftPad + chartW * idx / (closes.lastIndex.coerceAtLeast(1)).toFloat()
            val y = priceToY(close)
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = lineColor, style = DrawStroke(width = 4f, cap = StrokeCap.Round))

        fun overlayLine(price: BigDecimal, color: Color) {
            val bounded = price.coerceIn(minPrice, maxPrice)
            val y = priceToY(bounded)
            drawLine(color = color, start = Offset(leftPad, y), end = Offset(w - rightPad, y), strokeWidth = 2f)
        }
        overlayLine(tp, tpColor)
        overlayLine(sl, slColor)

        val lastX = w - rightPad
        val lastY = priceToY(last)
        drawCircle(color = Electric, radius = 7f, center = Offset(lastX, lastY))
    }
}

@Composable
private fun ChartStats(candles: List<Candle>, decision: AiDecision?, settings: BotSettings) {
    val last = candles.lastOrNull()?.close ?: BigDecimal.ZERO
    val high = candles.maxOfOrNull { it.high } ?: BigDecimal.ZERO
    val low = candles.minOfOrNull { it.low } ?: BigDecimal.ZERO
    val first = candles.firstOrNull()?.close ?: BigDecimal.ZERO
    val change = if (first > BigDecimal.ZERO) {
        last.subtract(first).divide(first, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
    } else BigDecimal.ZERO

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { MetricCard("Last", "€${last.setScale(2, RoundingMode.HALF_UP)}", "Kraken close", Electric) }
        item { MetricCard("Change", "${change}%", "Loaded candles", if (change >= BigDecimal.ZERO) Mint else Danger) }
        item { MetricCard("High", "€${high.setScale(2, RoundingMode.HALF_UP)}", "Window high", Amber) }
        item { MetricCard("Low", "€${low.setScale(2, RoundingMode.HALF_UP)}", "Window low", Danger) }
        item { MetricCard("AI", decision?.finalAction?.name ?: "WAIT", "Score ${decision?.finalScore ?: 0}", actionColor(decision?.finalAction ?: SignalAction.WAIT)) }
    }
}

@Composable
private fun BuildHealthScreen(
    settings: BotSettings,
    status: String,
    statusLevel: String,
    onApplySafeDefaults: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Build Health", "Quick pre-push checklist for the current app build.") }
        item {
            GlassCard {
                Column {
                    Text("Crypto TradeStation v1.8.8 CTS", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                    Text("Current status: $status", color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    StatusPill(statusLevel, levelColor(statusLevel))
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Provider", settings.exchangeProvider.name, "Execution connector", Electric) }
                item { MetricCard("Mode", settings.mode.name, "Trading mode", Mint) }
                item { MetricCard("Quotes", settings.allowedQuoteAssetsCsv, "Allowed buys", Amber) }
                item { MetricCard("Universe", settings.autoSymbolQuoteAsset, "Symbol scan", Electric) }
            }
        }
        item {
            GlassCard {
                SectionTitle("Safety gates", "Recommended checks before pushing live automation.")
                ToggleInfo("Backtest gate", settings.enableBacktestGate)
                ToggleInfo("Forward-test gate", settings.enableForwardTestGate)
                ToggleInfo("Self-learning", settings.trueSelfLearningEnabled)
                ToggleInfo("Spike timing", settings.spikeProfitTimingEnabled)
                ToggleInfo("Non-EUR quote buys", settings.nonEurQuoteBuyEnabled)
                Text("For Belgium/Kraken, EUR should remain your primary quote balance unless you intentionally fund another quote asset.", color = Muted)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onApplySafeDefaults, colors = ButtonDefaults.buttonColors(containerColor = Electric)) {
                    Text("Apply Safe Belgium Defaults")
                }
            }
        }
        item {
            GlassCard {
                SectionTitle("Export / backup checklist", "Before a release, back up API-free settings and verify the APK build.")
                Text("1. Build with GitHub Actions.", color = Muted)
                Text("2. Install debug APK.", color = Muted)
                Text("3. Test PAPER scan/execution.", color = Muted)
                Text("4. Run Kraken Backtest and Chart refresh.", color = Muted)
                Text("5. Keep withdrawal permissions disabled on Kraken API keys.", color = Muted)
            }
        }
    }
}

@Composable
private fun NotificationCenterScreen(history: List<String>, settings: BotSettings) {
    val groupedHistory = history
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .entries
        .toList()
        .take(50)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Notification Center", "Trade, risk, learning, and system alerts in one place.") }
        item {
            GlassCard {
                SectionTitle("Notification routing", "Foreground service notifications remain active while the bot runs.")
                ToggleInfo("Watchdog alerts", settings.watchdogEnabled)
                ToggleInfo("Telegram remote control", settings.telegramRemoteControlEnabled)
                ToggleInfo("Discord remote control", settings.discordRemoteControlEnabled)
                ToggleInfo("Explain every decision", settings.selfLearningExplainEveryDecision)
            }
        }
        item {
            GlassCard {
                Text("Recent bot events", fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                if (groupedHistory.isEmpty()) {
                    Text("No events recorded yet.", color = Muted)
                } else {
                    groupedHistory.forEach { entry ->
                        val countText = if (entry.value > 1) " ×${entry.value}" else ""
                        Text("• ${entry.key}$countText", color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
        item {
            GlassCard {
                SectionTitle("Notification cleanup", "Repeated identical events are grouped so the same message does not appear three times.")
                Text("• Buy / sell executed", color = Muted)
                Text("• Trade blocked with reason", color = Muted)
                Text("• Risk-off / emergency stop", color = Muted)
                Text("• Learning promoted or demoted strategy", color = Muted)
                Text("• Kraken API/balance issue", color = Muted)
            }
        }
    }
}



@Composable
private fun RemoteAlertsScreen(
    settings: BotSettings,
    telegramBotToken: String,
    telegramChatId: String,
    discordWebhookUrl: String,
    remoteCommandPin: String,
    discordBotToken: String,
    discordCommandChannelId: String,
    onTelegramBotToken: (String) -> Unit,
    onTelegramChatId: (String) -> Unit,
    onDiscordWebhookUrl: (String) -> Unit,
    onRemoteCommandPin: (String) -> Unit,
    onDiscordBotToken: (String) -> Unit,
    onDiscordCommandChannelId: (String) -> Unit,
    onSave: () -> Unit,
    onTestTelegram: () -> Unit,
    onTestDiscord: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Telegram + Discord Live Alerts", "Configure real remote alerts for live/paper orders, blocked live mode and exchange errors.") }
        item {
            GlassCard {
                SectionTitle("Telegram", "Create a Telegram bot with BotFather, then paste the bot token and chat ID.")
                OutlinedTextField(
                    value = telegramBotToken,
                    onValueChange = onTelegramBotToken,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Telegram bot token") },
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = telegramChatId,
                    onValueChange = onTelegramChatId,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Telegram chat ID") }
                )
                ToggleInfo("Telegram enabled after save", settings.telegramRemoteControlEnabled)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = Electric)) { Text("Save Alerts") }
                    OutlinedButton(onClick = onTestTelegram) { Text("Test Telegram") }
                }
            }
        }
        item {
            GlassCard {
                SectionTitle("Discord", "Paste a Discord webhook URL from your server channel integrations.")
                OutlinedTextField(
                    value = discordWebhookUrl,
                    onValueChange = onDiscordWebhookUrl,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Discord webhook URL") },
                    visualTransformation = PasswordVisualTransformation()
                )
                ToggleInfo("Discord enabled after save", settings.discordRemoteControlEnabled)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = Electric)) { Text("Save Alerts") }
                    OutlinedButton(onClick = onTestDiscord) { Text("Test Discord") }
                }
            }
        }
        item {
            GlassCard {
                SectionTitle("Remote Command Center", "Control the bot from Telegram or Discord while the Android foreground service is running.")
                OutlinedTextField(
                    value = remoteCommandPin,
                    onValueChange = onRemoteCommandPin,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Remote command PIN") },
                    visualTransformation = PasswordVisualTransformation()
                )
                ToggleInfo("Remote command center enabled after save", settings.remoteCommandCenterEnabled)
                ToggleInfo("Telegram command polling after save", settings.telegramCommandPollingEnabled)
                ToggleInfo("Discord command polling after save", settings.discordCommandPollingEnabled)
                ToggleInfo("Remote LIVE_AUTO commands", settings.remoteCommandAllowLiveAuto)
                Text("Telegram commands use your existing bot token/chat ID. Discord commands require a Discord bot token and channel ID, not only a webhook.", color = Amber)
                Text("Commands: /cts <PIN> status, portfolio, positions, orders, scan, pause, resume, mode PAPER, set max_position 10, set max_buy BTCEUR 95000.", color = Muted)
            }
        }
        item {
            GlassCard {
                SectionTitle("Discord Command Bot", "Only needed if you want inbound Discord commands. Webhooks can send alerts, but cannot read commands.")
                OutlinedTextField(
                    value = discordBotToken,
                    onValueChange = onDiscordBotToken,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Discord bot token for commands") },
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = discordCommandChannelId,
                    onValueChange = onDiscordCommandChannelId,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Discord command channel ID") }
                )
                Text("The bot must be added to your server and allowed to read/send messages in this channel.", color = Muted)
                Button(onClick = onSave, colors = ButtonDefaults.buttonColors(containerColor = Electric), modifier = Modifier.fillMaxWidth()) {
                    Text("Save Command Settings")
                }
            }
        }
        item {
            GlassCard {
                SectionTitle("Live alert events", "These are now wired into the live execution path.")
                Text("• Order placed", color = Muted)
                Text("• Order submit failed", color = Muted)
                Text("• LIVE_AUTO blocked by release safety lock", color = Muted)
                Text("• Telegram/Discord test alerts", color = Muted)
                Text("Live alerts require internet access and valid Telegram/Discord credentials.", color = Amber)
            }
        }
    }
}

@Composable
private fun CandlestickChart(
    candles: List<Candle>,
    settings: BotSettings,
    trades: List<TradeEntity> = emptyList(),
    windowSize: Int = 72,
    panOffset: Int = 0,
    showVolume: Boolean = true
) {
    val upColor = Mint
    val downColor = Danger
    val tpColor = Amber
    val slColor = Danger
    val gridColor = Stroke

    if (candles.size < 2) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(PanelAlt, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("No chart data yet. Press Refresh Now or enable live auto-refresh.", color = Muted)
        }
        return
    }

    val safeWindow = windowSize.coerceIn(24, 240)
    val safePan = panOffset.coerceAtLeast(0)
    val visible = candles.dropLast(safePan).takeLast(safeWindow).ifEmpty { candles.takeLast(safeWindow) }
    val priceMin = visible.minOf { it.low }
    val priceMax = visible.maxOf { it.high }
    val last = visible.last().close
    val tp = last.multiply(BigDecimal.ONE.add(settings.takeProfitPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
    val sl = last.multiply(BigDecimal.ONE.subtract(settings.stopLossPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
    val tradePrices = trades.mapNotNull { it.priceEur.toBigDecimalOrNull() }
    val minPrice = (listOf(priceMin, last, tp, sl) + tradePrices).minOrNull() ?: priceMin
    val maxPrice = (listOf(priceMax, last, tp, sl) + tradePrices).maxOrNull() ?: priceMax
    val range = (maxPrice - minPrice).takeIf { it > BigDecimal.ZERO } ?: BigDecimal.ONE
    val firstTime = visible.first().openTimeEpochMs
    val lastTime = visible.last().openTimeEpochMs
    val timeRange = (lastTime - firstTime).coerceAtLeast(1L)
    val lastBuy = trades.lastOrNull { it.side.uppercase().contains("BUY") }?.priceEur?.toBigDecimalOrNull()
    val lastSell = trades.lastOrNull { it.side.uppercase().contains("SELL") }?.priceEur?.toBigDecimalOrNull()

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PanelAlt)
            .padding(8.dp)
    ) {
        val w = size.width
        val h = size.height
        val leftPad = 14f
        val rightPad = 14f
        val topPad = 18f
        val bottomPad = 24f
        val volumeHeight = if (showVolume) h * 0.22f else 0f
        val priceChartBottom = h - bottomPad - volumeHeight - if (showVolume) 12f else 0f
        val chartW = w - leftPad - rightPad
        val chartH = priceChartBottom - topPad
        val candleSlot = chartW / visible.size.coerceAtLeast(1)
        val bodyWidth = (candleSlot * 0.62f).coerceIn(3f, 18f)

        fun priceToY(price: BigDecimal): Float {
            val normalized = price.subtract(minPrice).divide(range, 8, RoundingMode.HALF_UP).toFloat()
            return topPad + chartH * (1f - normalized)
        }

        fun timeToX(epochMs: Long): Float {
            val bounded = epochMs.coerceIn(firstTime, lastTime)
            return leftPad + chartW * ((bounded - firstTime).toFloat() / timeRange.toFloat())
        }

        repeat(5) { idx ->
            val y = topPad + chartH * idx / 4f
            drawLine(color = gridColor, start = Offset(leftPad, y), end = Offset(w - rightPad, y), strokeWidth = 1f)
        }

        visible.forEachIndexed { idx, candle ->
            val x = leftPad + candleSlot * idx + candleSlot / 2f
            val color = if (candle.close >= candle.open) upColor else downColor
            val highY = priceToY(candle.high)
            val lowY = priceToY(candle.low)
            val openY = priceToY(candle.open)
            val closeY = priceToY(candle.close)
            drawLine(color = color, start = Offset(x, highY), end = Offset(x, lowY), strokeWidth = 2f)
            drawLine(
                color = color,
                start = Offset(x, openY),
                end = Offset(x, closeY),
                strokeWidth = bodyWidth,
                cap = StrokeCap.Round
            )
        }

        fun overlayLine(price: BigDecimal?, color: Color, stroke: Float = 2f) {
            if (price == null) return
            val y = priceToY(price.coerceIn(minPrice, maxPrice))
            drawLine(color = color, start = Offset(leftPad, y), end = Offset(w - rightPad, y), strokeWidth = stroke)
        }

        overlayLine(tp, tpColor, 2f)
        overlayLine(sl, slColor, 2f)
        overlayLine(lastBuy, Mint.copy(alpha = 0.75f), 1.5f)
        overlayLine(lastSell, Danger.copy(alpha = 0.75f), 1.5f)

        trades.takeLast(80).forEach { trade: TradeEntity ->
            val tradePrice = trade.priceEur.toBigDecimalOrNull() ?: return@forEach
            val x = timeToX(trade.timestampEpochMs)
            val y = priceToY(tradePrice.coerceIn(minPrice, maxPrice))
            val isBuy = trade.side.uppercase().contains("BUY")
            val markerColor = if (isBuy) Mint else Danger
            val marker = Path()
            if (isBuy) {
                marker.moveTo(x, y - 9f)
                marker.lineTo(x - 7f, y + 7f)
                marker.lineTo(x + 7f, y + 7f)
            } else {
                marker.moveTo(x, y + 9f)
                marker.lineTo(x - 7f, y - 7f)
                marker.lineTo(x + 7f, y - 7f)
            }
            marker.close()
            drawPath(marker, color = markerColor)
            drawCircle(color = Color.White.copy(alpha = 0.75f), radius = 2f, center = Offset(x, y))
        }

        val lastX = w - rightPad
        val lastY = priceToY(last)
        drawLine(color = Electric.copy(alpha = 0.75f), start = Offset(leftPad, lastY), end = Offset(w - rightPad, lastY), strokeWidth = 1.5f)
        drawCircle(color = Electric, radius = 7f, center = Offset(lastX, lastY))

        if (showVolume) {
            val maxVolume = visible.maxOfOrNull { it.volume } ?: BigDecimal.ONE
            val volTop = priceChartBottom + 12f
            val volBase = h - bottomPad
            drawLine(color = gridColor, start = Offset(leftPad, volBase), end = Offset(w - rightPad, volBase), strokeWidth = 1f)
            visible.forEachIndexed { idx, candle ->
                val x = leftPad + candleSlot * idx + candleSlot / 2f
                val normalized = if (maxVolume > BigDecimal.ZERO) candle.volume.divide(maxVolume, 8, RoundingMode.HALF_UP).toFloat() else 0f
                val barH = ((volBase - volTop) * normalized).coerceAtLeast(1f)
                val color = if (candle.close >= candle.open) upColor.copy(alpha = 0.45f) else downColor.copy(alpha = 0.45f)
                drawLine(
                    color = color,
                    start = Offset(x, volBase),
                    end = Offset(x, volBase - barH),
                    strokeWidth = (candleSlot * 0.48f).coerceAtLeast(2f),
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
private fun TradeReplayScreen(
    settings: BotSettings,
    candles: List<Candle>,
    selectedSymbol: String,
    onLoadReplayData: () -> Unit
) {
    var index by remember(candles) { mutableStateOf(0) }
    val visible = candles.takeLast(120)
    val boundedIndex = index.coerceIn(0, (visible.size - 1).coerceAtLeast(0))
    val current = visible.getOrNull(boundedIndex)
    val previous = visible.take(boundedIndex + 1)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Trade Replay Mode", "Replay recent Kraken candles and inspect how the bot would read the move.") }
        item {
            GlassCard {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(selectedSymbol, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                            Text("Replay candle ${boundedIndex + 1}/${visible.size.coerceAtLeast(1)}", color = Muted)
                        }
                        StatusPill(if (current != null && current.close >= current.open) "UP" else "DOWN", if (current != null && current.close >= current.open) Mint else Danger)
                    }
                    Spacer(Modifier.height(12.dp))
                    ElevatedButton(onClick = onLoadReplayData) { Text("Load Replay Data") }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { index = (boundedIndex - 1).coerceAtLeast(0) }) { Text("Prev") }
                        Button(onClick = { index = (boundedIndex + 1).coerceAtMost((visible.size - 1).coerceAtLeast(0)) }) { Text("Next") }
                    }
                }
            }
        }
        item {
            GlassCard {
                CandlestickChart(candles = previous.ifEmpty { visible.take(1) }, settings = settings, trades = emptyList())
                Spacer(Modifier.height(12.dp))
                if (current == null) {
                    Text("No replay candles loaded yet.", color = Muted)
                } else {
                    val change = if (current.open > BigDecimal.ZERO) {
                        current.close.subtract(current.open).divide(current.open, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
                    } else BigDecimal.ZERO
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { MetricCard("Open", "€${current.open.setScale(2, RoundingMode.HALF_UP)}", "Replay candle", Electric) }
                        item { MetricCard("Close", "€${current.close.setScale(2, RoundingMode.HALF_UP)}", "Replay candle", Mint) }
                        item { MetricCard("Move", "$change%", "Candle change", if (change >= BigDecimal.ZERO) Mint else Danger) }
                        item { MetricCard("Volume", current.volume.setScale(0, RoundingMode.HALF_UP).toPlainString(), "Kraken OHLC", Amber) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LearningInspectorScreen(
    summary: TrueSelfLearningEngine.LearningSummary?,
    settings: BotSettings,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Learning Inspector", "Shows what the self-learning system is currently allowed to influence.") }
        item {
            GlassCard {
                Column {
                    Text(summary?.summaryLine ?: "No learning summary loaded yet.", fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    ElevatedButton(onClick = onRefresh) { Text("Refresh Learning Summary") }
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Learning", if (settings.trueSelfLearningEnabled) "ON" else "OFF", "Global switch", Mint) }
                item { MetricCard("Samples", settings.selfLearningMinSamples.toString(), "Minimum trust", Electric) }
                item { MetricCard("Boost", settings.selfLearningMaxScoreBoost.toString(), "Max score boost", Amber) }
                item { MetricCard("Penalty", settings.selfLearningMaxScorePenalty.toString(), "Max score penalty", Danger) }
            }
        }
        item {
            GlassCard {
                SectionTitle("Learning influence", "What the engine can change after enough evidence exists.")
                ToggleInfo("Position sizing", settings.selfLearningPositionSizingEnabled)
                ToggleInfo("Auto-disable weak symbols", settings.selfLearningAutoDisableEnabled)
                ToggleInfo("Adaptive strategy learning", settings.adaptiveStrategyLearningEnabled)
                ToggleInfo("Learned hold for profit", settings.learnedHoldForProfitEnabled)
                ToggleInfo("Spike profit timing", settings.spikeProfitTimingEnabled)
                Text("This inspector is intentionally evidence-focused. A symbol/strategy should only get live trust after enough samples.", color = Muted)
            }
        }
    }
}

@Composable
private fun StrategySandboxScreen(
    settings: BotSettings,
    onRunHistoricalBacktest: (BotSettings, String, Timeframe, StrategyMode, Int, (BacktestReport) -> Unit) -> Unit
) {
    var reports by remember { mutableStateOf<List<BacktestReport>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    val symbol = settings.symbols().firstOrNull() ?: "BTCEUR"
    val strategies = listOf(StrategyMode.SCALPING, StrategyMode.TREND, StrategyMode.BREAKOUT, StrategyMode.REVERSAL, StrategyMode.NEWS_MOMENTUM)

    fun runSandbox() {
        running = true
        reports = emptyList()
        strategies.forEach { strategy ->
            onRunHistoricalBacktest(settings, symbol, Timeframe.M15, strategy, 360) { result ->
                reports = (reports + result).sortedByDescending { it.profitFactor }
                if (reports.size >= strategies.size) running = false
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Strategy Sandbox", "Runs multiple strategies on Kraken OHLC before saving or trusting them.") }
        item {
            HeroCard(
                title = "Sandbox $symbol",
                subtitle = if (running) "Testing strategies on Kraken OHLC..." else "Compare strategy behavior before pushing live risk.",
                primaryButton = if (running) "Running..." else "Run Sandbox",
                secondaryButton = "Clear",
                onPrimary = { if (!running) runSandbox() },
                onSecondary = { reports = emptyList(); running = false }
            )
        }
        if (running) {
            item {
                GlassCard {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Running ${reports.size}/${strategies.size} completed tests...", color = Muted)
                }
            }
        }
        if (reports.isEmpty()) {
            item { GlassCard { Text("No sandbox results yet.", color = Muted) } }
        } else {
            items(reports.take(50)) { report ->
                BacktestReportCard(title = "Sandbox ${report.strategy.name}", report = report)
            }
        }
    }
}

@Composable
private fun BackupRestoreScreen(
    settings: BotSettings,
    backupDirectoryPath: String,
    onBackupDirectoryPathChanged: (String) -> Unit,
    onExportFullBackup: (String, (String) -> Unit) -> Unit,
    onRestoreFullBackup: (String, Boolean, (String) -> Unit) -> Unit,
    onApplySafeDefaults: () -> Unit
) {
    var backupText by remember { mutableStateOf("") }
    var customBackupDirectory by remember(backupDirectoryPath) { mutableStateOf(backupDirectoryPath) }
    val context = LocalContext.current
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            customBackupDirectory = it.toString()
            onBackupDirectoryPathChanged(it.toString())
        }
    }
    var restoreInput by remember { mutableStateOf("") }
    val restoreFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            restoreInput = it.toString()
        }
    }
    var restoreResult by remember { mutableStateOf("") }
    var replaceExistingLocalData by remember { mutableStateOf(false) }
    val displayText = if (backupText.length > 24000) {
        backupText.take(24000) + "\n\n[Preview truncated in UI for stability. Full backup is saved in the file path shown above.]"
    } else backupText

    fun generateSettingsSummaryBackup(): String {
        return listOf(
            "Crypto TradeStation settings summary backup",
            "version=v2.0.7",
            "mode=${settings.mode}",
            "provider=${settings.exchangeProvider}",
            "symbols=${settings.symbolsCsv}",
            "allowedQuotes=${settings.allowedQuoteAssetsCsv}",
            "autoUniverse=${settings.autoSymbolQuoteAsset}",
            "maxPositionEur=${settings.maxPositionEur}",
            "maxBuyPriceFilterEnabled=${settings.maxBuyPriceFilterEnabled}",
            "globalMaxBuyPriceEur=${settings.globalMaxBuyPriceEur}",
            "perSymbolMaxBuyPriceCsv=${settings.perSymbolMaxBuyPriceCsv}",
            "ultimateAutomationEnabled=${settings.ultimateAutomationEnabled}",
            "perSymbolRulesEnabled=${settings.perSymbolRulesEnabled}",
            "perSymbolRulesCsv=${settings.perSymbolRulesCsv}",
            "autoCompoundingHardCapEnabled=${settings.autoCompoundingHardCapEnabled}",
            "autoCompoundingMaxPositionEur=${settings.autoCompoundingMaxPositionEur}",
            "autoPauseAfterOrderFailuresEnabled=${settings.autoPauseAfterOrderFailuresEnabled}",
            "autoPauseFailureThreshold=${settings.autoPauseFailureThreshold}",
            "autoPauseMinutes=${settings.autoPauseMinutes}",
            "volatilityCircuitBreakerEnabled=${settings.volatilityCircuitBreakerEnabled}",
            "volatilityCircuitBreakerMax24hMovePercent=${settings.volatilityCircuitBreakerMax24hMovePercent}",
            "pumpChaseProtectionEnabled=${settings.pumpChaseProtectionEnabled}",
            "pumpChaseMax24hGainPercent=${settings.pumpChaseMax24hGainPercent}",
            "duplicatePositionProtectionEnabled=${settings.duplicatePositionProtectionEnabled}",
            "adaptiveCompoundingFromRealizedPnlEnabled=${settings.adaptiveCompoundingFromRealizedPnlEnabled}",
            "dynamicScanIntervalEnabled=${settings.dynamicScanIntervalEnabled}",
            "dynamicScanFastSeconds=${settings.dynamicScanFastSeconds}",
            "dynamicScanSlowSeconds=${settings.dynamicScanSlowSeconds}",
            "multiTimeframeConsensusEnabled=${settings.multiTimeframeConsensusEnabled}",
            "multiTimeframeRequiredBullishCount=${settings.multiTimeframeRequiredBullishCount}",
            "ultimateReadinessScoreEnabled=${settings.ultimateReadinessScoreEnabled}",
            "remoteCommandCenterEnabled=${settings.remoteCommandCenterEnabled}",
            "telegramCommandPollingEnabled=${settings.telegramCommandPollingEnabled}",
            "discordCommandPollingEnabled=${settings.discordCommandPollingEnabled}",
            "remoteCommandRequirePin=${settings.remoteCommandRequirePin}",
            "remoteCommandAllowLiveAuto=${settings.remoteCommandAllowLiveAuto}",
            "orderBookDepthGuardEnabled=${settings.orderBookDepthGuardEnabled}",
            "maxOrderBookSlippagePercent=${settings.maxOrderBookSlippagePercent}",
            "minOrderBookDepthMultiple=${settings.minOrderBookDepthMultiple}",
            "maxTradesPerDay=${settings.maxTradesPerDay}",
            "maxTradesPerHour=${settings.maxTradesPerHour}",
            "maxLivePositions=${settings.maxSimultaneousLivePositions}",
            "selfLearning=${settings.trueSelfLearningEnabled}",
            "spikeTiming=${settings.spikeProfitTimingEnabled}",
            "backtestGate=${settings.enableBacktestGate}",
            "forwardGate=${settings.enableForwardTestGate}"
        ).joinToString("\n")
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Backup / Restore", "Folder-picker export/import for the complete local app state.") }
        item {
            GlassCard {
                Column {
                    GlassCard {
                        SectionTitle("Backup folder", "Use Android's folder picker. Manual path entry has been removed.")
                        Text(
                            if (customBackupDirectory.isBlank()) "No folder selected yet. Export will use the default app backup folder." else "Selected folder: $customBackupDirectory",
                            color = Muted,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { directoryPicker.launch(null) }, colors = ButtonDefaults.buttonColors(containerColor = Electric)) {
                                Text("Select Folder")
                            }
                            OutlinedButton(onClick = {
                                customBackupDirectory = ""
                                onBackupDirectoryPathChanged("")
                            }) {
                                Text("Use Default")
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            onBackupDirectoryPathChanged(customBackupDirectory)
                            onExportFullBackup(customBackupDirectory) { backupText = it }
                        }, colors = ButtonDefaults.buttonColors(containerColor = Electric)) {
                            Text("Export All Settings + Data")
                        }
                        OutlinedButton(onClick = { backupText = generateSettingsSummaryBackup() }) {
                            Text("Settings Summary")
                        }
                        OutlinedButton(onClick = onApplySafeDefaults) {
                            Text("Restore Safe Defaults")
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = displayText,
                        onValueChange = { },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 10,
                        readOnly = true,
                        label = { Text("Backup file path / preview") }
                    )
                }
            }
        }
        item {
            GlassCard {
                SectionTitle("Load / Restore Backup", "Use Android's file picker to choose a Crypto TradeStation backup file.")
                Text(
                    if (restoreInput.isBlank()) "No backup file selected yet." else "Selected backup file: $restoreInput",
                    color = Muted,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                ToggleRow("Replace existing local trade/position data before restore", replaceExistingLocalData) { replaceExistingLocalData = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { restoreFilePicker.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = Electric)
                    ) {
                        Text("Select Backup File")
                    }
                    OutlinedButton(
                        enabled = restoreInput.isNotBlank(),
                        onClick = { onRestoreFullBackup(restoreInput, replaceExistingLocalData) { restoreResult = it } }
                    ) {
                        Text("Restore Selected")
                    }
                }
                if (restoreResult.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(restoreResult, color = Mint)
                }
                Text("Restores: settings, secure credentials, trade journal, positions, tax rows and learning data when present in the backup.", color = Amber)
            }
        }
        item {
            GlassCard {
                SectionTitle("Automatic save behavior", "Normal app updates keep local data automatically as long as the package name stays the same.")
                Text("Saved automatically: settings, local database, trade journal, learning profiles, tax rows and local bot history.", color = Muted)
                Text("Manual path entry was removed. Use Select Folder or Use Default, then press Export All Settings + Data.", color = Muted)
                Text("Manual restore text/path entry was removed. Use Select Backup File, then Restore Selected.", color = Muted)
                Text("Full backup includes credentials/tokens/PINs because this backup is meant to restore literally everything. Keep the backup file private.", color = Amber)
            }
        }
    }
}


@Composable
private fun TradeOverlayScreen(
    settings: BotSettings,
    candles: List<Candle>,
    trades: List<TradeEntity>,
    selectedSymbol: String,
    onRefresh: () -> Unit
) {
    val symbolTrades = trades.filter { it.symbol.equals(selectedSymbol, ignoreCase = true) }
    val buys = symbolTrades.count { it.side.uppercase().contains("BUY") }
    val sells = symbolTrades.count { it.side.uppercase().contains("SELL") }
    val realized = symbolTrades.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }.fold(BigDecimal.ZERO) { a, b -> a + b }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Real Trade Marker + Position Overlay", "Shows actual trade history markers on the Kraken chart.") }
        item {
            GlassCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(selectedSymbol, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                        Text("Markers are loaded from the local trade journal database.", color = Muted)
                    }
                    Button(onClick = onRefresh) { Text("Refresh") }
                }
                Spacer(Modifier.height(12.dp))
                CandlestickChart(candles = candles, settings = settings, trades = symbolTrades)
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Buys", buys.toString(), "Journal markers", Mint) }
                item { MetricCard("Sells", sells.toString(), "Journal markers", Danger) }
                item { MetricCard("Realized", "€${realized.setScale(2, RoundingMode.HALF_UP)}", "Known P/L", if (realized >= BigDecimal.ZERO) Mint else Danger) }
                item { MetricCard("TP/SL", "ON", "Overlay lines", Amber) }
            }
        }
        item {
            GlassCard {
                SectionTitle("Position overlay logic", "The chart now supports actual BUY/SELL dots, TP/SL overlay lines, and current price marker.")
                Text("Next deeper layer: draw partial-sell markers, average-entry bands and position-size bubbles per fill.", color = Muted)
            }
        }
    }
}

@Composable
private fun TradeJournalScreen(trades: List<TradeEntity>, onRefresh: () -> Unit) {
    val realized = trades.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }.fold(BigDecimal.ZERO) { a, b -> a + b }
    val buyCount = trades.count { it.side.uppercase().contains("BUY") }
    val sellCount = trades.count { it.side.uppercase().contains("SELL") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SectionTitle("Real Trade Journal", "Every local paper/live trade with AI score, fees, reason and P/L.") }
        item {
            GlassCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Journal Summary", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                        Text("Rows loaded: ${trades.size}", color = Muted)
                    }
                    Button(onClick = onRefresh) { Text("Refresh") }
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { MetricCard("Buys", buyCount.toString(), "Entries", Mint) }
                    item { MetricCard("Sells", sellCount.toString(), "Exits", Danger) }
                    item { MetricCard("Realized P/L", "€${realized.setScale(2, RoundingMode.HALF_UP)}", "Known from DB", if (realized >= BigDecimal.ZERO) Mint else Danger) }
                }
            }
        }
        if (trades.isEmpty()) {
            item { GlassCard { Text("No trade rows found yet. Run PAPER mode or sync live history first.", color = Muted) } }
        } else {
            items(trades.take(100)) { trade ->
                GlassCard {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${trade.symbol} ${trade.side}", fontWeight = FontWeight.ExtraBold)
                                Text("Qty ${trade.quantity} @ €${trade.priceEur} | Fee €${trade.feeEur}", color = Muted)
                            }
                            StatusPill(if (trade.paper) "PAPER" else "LIVE", if (trade.paper) Electric else Mint)
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("P/L €${trade.realizedPnlEur} | AI ${trade.aiScore}", color = if ((trade.realizedPnlEur.toBigDecimalOrNull() ?: BigDecimal.ZERO) >= BigDecimal.ZERO) Mint else Danger)
                        if (trade.aiReason.isNotBlank()) Text(trade.aiReason, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun KrakenHealthMonitorScreen(settings: BotSettings, lines: List<String>, onRefresh: () -> Unit) {
    val pass = lines.count { it.startsWith("PASS") }
    val warn = lines.count { it.startsWith("WARN") }
    val fail = lines.count { it.startsWith("FAIL") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Kraken Live Data Health Monitor", "Diagnoses public market data, private permissions, balances and open orders.") }
        item {
            GlassCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Provider ${settings.exchangeProvider}", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                        Text("Mode ${settings.mode} | Symbols ${settings.symbolsCsv}", color = Muted)
                    }
                    Button(onClick = onRefresh) { Text("Run Check") }
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { MetricCard("PASS", pass.toString(), "Checks", Mint) }
                    item { MetricCard("WARN", warn.toString(), "Warnings", Amber) }
                    item { MetricCard("FAIL", fail.toString(), "Failures", Danger) }
                }
            }
        }
        if (lines.isEmpty()) {
            item { GlassCard { Text("No health check loaded yet. Press Run Check.", color = Muted) } }
        } else {
            items(lines) { line ->
                val color = when {
                    line.startsWith("PASS") -> Mint
                    line.startsWith("WARN") -> Amber
                    line.startsWith("FAIL") -> Danger
                    else -> Muted
                }
                GlassCard { Text(line, color = color) }
            }
        }
    }
}

@Composable
private fun SmartExitV2Screen(settings: BotSettings, trades: List<TradeEntity>) {
    val recentSells = trades.filter { it.side.uppercase().contains("SELL") }.take(30)
    val avgPnl = if (recentSells.isEmpty()) BigDecimal.ZERO else {
        recentSells.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }.fold(BigDecimal.ZERO) { a, b -> a + b }
            .divide(BigDecimal(recentSells.size), 4, RoundingMode.HALF_UP)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Smart Exit Engine v2", "Partial scaling, spike-aware hold, profit-lock and trailing logic overview.") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("TP", "${settings.takeProfitPercent}%", "Base target", Mint) }
                item { MetricCard("SL", "${settings.stopLossPercent}%", "Hard protection", Danger) }
                item { MetricCard("Trail", "${settings.trailingDistancePercent}%", "Base trailing", Amber) }
                item { MetricCard("Avg sell P/L", "€${avgPnl.setScale(2, RoundingMode.HALF_UP)}", "Recent sells", if (avgPnl >= BigDecimal.ZERO) Mint else Danger) }
            }
        }
        item {
            GlassCard {
                SectionTitle("Exit decision stack", "Order of protection for sell decisions.")
                ToggleInfo("Hard stop-loss never overridden", settings.autoStopLossEnabled)
                ToggleInfo("Spike profit timing", settings.spikeProfitTimingEnabled)
                ToggleInfo("Learned hold TP deferral", settings.learnedHoldAllowTakeProfitDeferral)
                ToggleInfo("Learned hold trailing deferral", settings.learnedHoldAllowTrailingDeferral)
                ToggleInfo("Smart profit lock", settings.smartProfitLockEnabled)
                Text("v2 behavior: hold strong continuation candidates, scale/lock profits on exhaustion, and keep emergency exits above learned holds.", color = Muted)
            }
        }
    }
}

@Composable
private fun PortfolioRotationEngineScreen(settings: BotSettings, decisions: List<AiDecision>, trades: List<TradeEntity>) {
    val symbols = settings.symbols()
    val sellSignals = decisions.count { it.finalAction == SignalAction.SELL || it.finalAction == SignalAction.AVOID || it.finalAction == SignalAction.STRONG_AVOID }
    val buySignals = decisions.count { it.finalAction == SignalAction.BUY || it.finalAction == SignalAction.SMALL_BUY }
    val recentSymbols = trades.map { it.symbol }.distinct().take(6)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Portfolio Rotation Engine", "Ranks whether to hold EUR, hold current assets, or rotate into stronger symbols.") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Universe", symbols.size.toString(), "Configured symbols", Electric) }
                item { MetricCard("Buy signals", buySignals.toString(), "Current AI", Mint) }
                item { MetricCard("Exit signals", sellSignals.toString(), "Current AI", Danger) }
                item { MetricCard("Max positions", settings.maxSimultaneousLivePositions.toString(), "Risk cap", Amber) }
            }
        }
        item {
            GlassCard {
                SectionTitle("Rotation policy", "How the bot should think about capital allocation.")
                Text("1. Keep EUR as primary cash for Belgium/Kraken deposits.", color = Muted)
                Text("2. Prefer symbols with stronger AI score and approved Performance Lab status.", color = Muted)
                Text("3. Reduce exposure when several holdings show SELL/AVOID.", color = Muted)
                Text("4. Avoid crypto-to-crypto buys unless explicitly enabled.", color = Muted)
                Text("Recently traded: ${recentSymbols.joinToString(", ").ifBlank { "none" }}", color = Mint)
            }
        }
    }
}

@Composable
private fun StrategyAutoTunerScreen(
    settings: BotSettings,
    activePositionSymbols: List<String>,
    onApplySettings: (BotSettings) -> Unit,
    onRunHistoricalBacktest: (BotSettings, String, Timeframe, StrategyMode, Int, (BacktestReport) -> Unit) -> Unit
) {
    var reports by remember { mutableStateOf<List<BacktestReport>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var expectedReports by remember { mutableStateOf(0) }
    var appliedMessage by remember { mutableStateOf("") }
    val rawSymbols = (settings.symbols() + activePositionSymbols)
        .map { it.uppercase().replace("/", "").replace("-", "") }
        .filter { it.isNotBlank() }
        .distinct()
        .ifEmpty { listOf("BTCEUR", "ETHEUR") }
    val maxAutoTuneSymbols = 8
    val symbols = rawSymbols.take(maxAutoTuneSymbols)
    val symbolsCapped = rawSymbols.size > symbols.size
    val activeSet = activePositionSymbols.map { it.uppercase().replace("/", "").replace("-", "") }.toSet()
    val strategies = listOf(StrategyMode.SCALPING, StrategyMode.TREND, StrategyMode.BREAKOUT, StrategyMode.REVERSAL, StrategyMode.NEWS_MOMENTUM)

    fun sortedReports(input: List<BacktestReport>): List<BacktestReport> =
        input.sortedWith(
            compareByDescending<BacktestReport> { activeSet.contains(it.symbol.uppercase().replace("/", "").replace("-", "")) }
                .thenByDescending { it.passedLiveGate }
                .thenByDescending { it.profitFactor }
                .thenByDescending { it.netReturnPercent }
        )

    fun runAutoTune() {
        val tests = symbols.flatMap { symbol -> strategies.map { strategy -> symbol to strategy } }
        running = true
        appliedMessage = ""
        reports = emptyList()
        expectedReports = tests.size
        fun runNext(index: Int) {
            if (index >= tests.size) {
                running = false
                return
            }
            val (symbol, strategy) = tests[index]
            onRunHistoricalBacktest(settings.copy(symbolsCsv = symbols.joinToString(",")), symbol, Timeframe.M15, strategy, 360) { report ->
                val updated = sortedReports(reports + report)
                reports = updated
                runNext(index + 1)
            }
        }
        if (tests.isEmpty()) {
            running = false
            appliedMessage = "No symbols available for auto-tune."
        } else {
            runNext(0)
        }
    }

    fun applyBestToLiveSettings() {
        val best = reports.firstOrNull()
        if (best == null) {
            appliedMessage = "No auto-tune result to apply yet."
            return
        }
        if (!best.passedLiveGate) {
            appliedMessage = "Best candidate did not pass the live gate, so it was not applied."
            return
        }
        val updated = settings.copy(
            symbolsCsv = symbols.joinToString(","),
            strategyMode = best.strategy,
            enableBacktestGate = true,
            enableForwardTestGate = true,
            adaptiveStrategyLearningEnabled = true,
            autoTradeMultipleSymbolsPerScan = true,
            autoSymbolDiscoveryEnabled = true
        )
        onApplySettings(updated)
        appliedMessage = "Applied to live settings: strategy=${best.strategy.name}, universe=${symbols.joinToString(",")}. Existing LIVE_AUTO safety gates still apply."
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Strategy Auto-Tuner", "Crash-safe sequential strategy testing for configured symbols plus active positions.") }
        item {
            HeroCard(
                title = "Auto-tune ${symbols.size} symbol(s)",
                subtitle = if (running) "Sequential safe mode: ${reports.size}/$expectedReports OHLC tests..." else "Universe: ${symbols.joinToString(", ")}${if (symbolsCapped) " + ${rawSymbols.size - symbols.size} capped" else ""}",
                primaryButton = if (running) "Running..." else "Run Auto-Tune",
                secondaryButton = "Clear",
                onPrimary = { if (!running) runAutoTune() },
                onSecondary = { reports = emptyList(); running = false; appliedMessage = "" }
            )
        }
        item {
            GlassCard {
                Text("Live usage", fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(6.dp))
                Text("Auto-Tune now runs sequentially and caps large universes to prevent UI freezes/crashes. After tests finish, you can apply the best passed candidate to the live strategy settings.", color = Muted)
                Text("This changes strategyMode and symbol universe, but does not bypass LIVE_AUTO preflight, release safety, risk limits, or order-book guards.", color = Amber)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { applyBestToLiveSettings() },
                    enabled = !running && reports.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Electric),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Apply Best Passed Strategy To Live Settings")
                }
                if (appliedMessage.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(appliedMessage, color = if (appliedMessage.startsWith("Applied")) Mint else Amber)
                }
            }
        }
        if (reports.isNotEmpty()) {
            val best = reports.first()
            item {
                GlassCard {
                    Text("Best: ${best.symbol} — ${best.strategy.name}", fontWeight = FontWeight.ExtraBold, color = Mint)
                    Text("Profit factor ${best.profitFactor}, win ${best.winRatePercent}%, drawdown ${best.maxDrawdownPercent}%, liveGate=${best.passedLiveGate}", color = Muted)
                    if (activeSet.contains(best.symbol.uppercase().replace("/", "").replace("-", ""))) {
                        Text("Best candidate is from an ACTIVE POSITION symbol.", color = Amber, fontWeight = FontWeight.Bold)
                    }
                }
            }
            items(reports) { report ->
                val normalized = report.symbol.uppercase().replace("/", "").replace("-", "")
                Column {
                    if (activeSet.contains(normalized)) {
                        Text("ACTIVE POSITION", color = Amber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    }
                    BacktestReportCard(title = "Auto-tune ${report.symbol} ${report.strategy.name}", report = report)
                }
            }
        } else {
            item {
                GlassCard {
                    Text("No auto-tune results yet.", color = Muted)
                    Text("Press Run Auto-Tune to test strategies sequentially. Large universes are capped to keep the app stable.", color = Muted)
                }
            }
        }
    }
}

@Composable
private fun ReleaseSafetyLockScreen(settings: BotSettings, healthLines: List<String>) {
    val checks = listOf(
        "Provider selected" to (settings.exchangeProvider != ExchangeProvider.MANUAL),
        "Paper or live acknowledged" to (settings.mode == BotMode.PAPER || settings.liveTradingAcknowledged),
        "EUR quote allowed" to settings.allowedQuoteAssetsCsv.uppercase().contains("EUR"),
        "Non-EUR quote buys off" to !settings.nonEurQuoteBuyEnabled,
        "Backtest gate enabled" to settings.enableBacktestGate,
        "Forward-test gate enabled" to settings.enableForwardTestGate,
        "Small position cap" to (settings.maxPositionEur <= BigDecimal("25.00")),
        "Self-learning enabled" to settings.trueSelfLearningEnabled,
        "Stop-loss enabled" to settings.autoStopLossEnabled,
        "Kraken health has no FAIL" to healthLines.none { it.startsWith("FAIL") }
    )
    val passed = checks.count { it.second }
    val liveReady = passed == checks.size

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Release Mode / Safety Lock", "Final checklist before trusting live automation.") }
        item {
            GlassCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(if (liveReady) "LIVE READY" else "BLOCKED", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.headlineMedium, color = if (liveReady) Mint else Danger)
                        Text("$passed/${checks.size} checks passed", color = Muted)
                    }
                    StatusPill(if (liveReady) "READY" else "FIX", if (liveReady) Mint else Danger)
                }
            }
        }
        items(checks) { check ->
            GlassCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(check.first, modifier = Modifier.weight(1f), color = TextPrimary)
                    StatusPill(if (check.second) "PASS" else "BLOCK", if (check.second) Mint else Danger)
                }
            }
        }
        item {
            GlassCard {
                Text("This safety lock cannot verify Kraken withdrawal permission from the API response. Confirm manually that withdrawal permission is disabled.", color = Amber)
            }
        }
    }
}

@Composable
private fun BacktestLabScreen(
    settings: BotSettings,
    onRunHistoricalBacktest: (BotSettings, String, Timeframe, StrategyMode, Int, (BacktestReport) -> Unit) -> Unit
) {
    var report by remember { mutableStateOf<BacktestReport?>(null) }
    var forwardReport by remember { mutableStateOf<BacktestReport?>(null) }
    var message by remember { mutableStateOf("Ready. These tests now use real Kraken public OHLC candles, not generated sample candles.") }
    var loading by remember { mutableStateOf(false) }

    fun selectedSymbols(): List<String> {
        return settings.symbolsCsv
            .split(",")
            .map { it.trim().uppercase().replace("/", "").replace("-", "") }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("BTCEUR", "ETHEUR") }
    }

    fun runSampleBacktest() {
        val symbol = selectedSymbols().firstOrNull() ?: "BTCEUR"
        val strategy = if (settings.strategyMode == StrategyMode.AUTO) StrategyMode.TREND else settings.strategyMode
        loading = true
        message = "Downloading Kraken OHLC candles for $symbol..."
        onRunHistoricalBacktest(settings, symbol, Timeframe.M15, strategy, 720) { result ->
            report = result
            loading = false
            message = "Kraken historical backtest completed for ${result.symbol}."
        }
    }

    fun runForwardTest() {
        val symbol = selectedSymbols().drop(1).firstOrNull() ?: selectedSymbols().firstOrNull() ?: "ETHEUR"
        val strategy = if (settings.strategyMode == StrategyMode.AUTO) StrategyMode.SCALPING else settings.strategyMode
        loading = true
        message = "Downloading recent Kraken OHLC candles for forward test on $symbol..."
        onRunHistoricalBacktest(settings, symbol, Timeframe.M5, strategy, 240) { result ->
            forwardReport = result
            loading = false
            message = "Kraken forward-test simulation completed for ${result.symbol}."
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Backtest Lab", "Tests strategies using Kraken public historical OHLC data before LIVE_AUTO is trusted.") }
        item {
            HeroCard(
                title = "Kraken Historical Test Gate",
                subtitle = message,
                primaryButton = if (loading) "Loading..." else "Run Kraken Backtest",
                secondaryButton = if (loading) "Loading..." else "Forward Test",
                onPrimary = { if (!loading) runSampleBacktest() },
                onSecondary = { if (!loading) runForwardTest() }
            )
        }

        if (loading) {
            item {
                GlassCard {
                    Column {
                        Text("Fetching Kraken OHLC data...", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("This uses Kraken public market data and does not require API keys.", color = Muted)
                    }
                }
            }
        }

        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Required Trades", settings.requiredPaperTrades.toString(), "Paper/backtest sample", Electric) }
                item { MetricCard("Min Win Rate", "${settings.requiredPaperWinRatePercent}%", "Gate threshold", Mint) }
                item { MetricCard("Profit Factor", settings.requiredProfitFactor.toPlainString(), "Minimum quality", Amber) }
                item { MetricCard("Max Drawdown", "${settings.maxDrawdownPercent}%", "Safety ceiling", Danger) }
            }
        }

        report?.let { result ->
            item {
                BacktestReportCard(
                    title = "Kraken Historical Backtest Result",
                    report = result
                )
            }
        }

        forwardReport?.let { result ->
            item {
                BacktestReportCard(
                    title = "Kraken Forward Test Result",
                    report = result
                )
            }
        }

        item {
            GlassCard {
                SectionTitle("Automated forward testing", "The app can use real Kraken OHLC candles to check whether a symbol/strategy should stay PAPER-first or be considered live-ready.")
                ToggleInfo("Backtest gate", settings.enableBacktestGate)
                ToggleInfo("Forward-test gate", settings.enableForwardTestGate)
                ToggleInfo("Auto safe mode", settings.enableAutoSafeMode)
                Text("Recommended: keep both gates enabled until at least ${settings.requiredPaperTrades} simulated trades are recorded.", color = Muted)
            }
        }
    }
}

@Composable
private fun BacktestReportCard(title: String, report: BacktestReport) {
    val resultColor = if (report.passedLiveGate) Mint else Amber
    GlassCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium)
                    Text("${report.symbol} • ${report.strategy.name} • ${report.timeframe.name}", color = Muted)
                }
                StatusPill(if (report.passedLiveGate) "PASSED" else "WATCH", resultColor)
            }

            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricBox("Trades", report.trades.toString(), Modifier.weight(1f))
                MetricBox("Win rate", "${report.winRatePercent}%", Modifier.weight(1f))
                MetricBox("Profit factor", report.profitFactor.toPlainString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricBox("Net return", "${report.netReturnPercent}%", Modifier.weight(1f))
                MetricBox("Max DD", "${report.maxDrawdownPercent}%", Modifier.weight(1f))
                MetricBox("Gate", if (report.passedLiveGate) "Live-ready" else "Paper-first", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text(report.summary, color = Muted)
        }
    }
}

private fun generateSampleBacktestCandles(symbol: String, count: Int, forwardMode: Boolean): List<Candle> {
    val start = System.currentTimeMillis() - count * 60_000L
    var price = when {
        symbol.startsWith("BTC") -> BigDecimal("64000.00")
        symbol.startsWith("ETH") -> BigDecimal("3100.00")
        else -> BigDecimal("100.00")
    }

    val candles = mutableListOf<Candle>()
    for (i in 0 until count) {
        val trend = if (forwardMode) BigDecimal("0.0018") else BigDecimal("0.0012")
        val wave = BigDecimal(((i % 13) - 6).toString()).multiply(BigDecimal("0.0009"))
        val pulse = if (i % 37 == 0) BigDecimal("0.012") else BigDecimal.ZERO
        val change = trend.add(wave).add(pulse)

        val open = price
        val close = price.multiply(BigDecimal.ONE.add(change)).setScale(2, RoundingMode.HALF_UP)
        val high = maxOf(open, close).multiply(BigDecimal("1.004")).setScale(2, RoundingMode.HALF_UP)
        val low = minOf(open, close).multiply(BigDecimal("0.996")).setScale(2, RoundingMode.HALF_UP)
        val volume = BigDecimal("100000").add(BigDecimal((i * 317 % 25000).toString()))

        candles += Candle(
            symbol = symbol,
            openTimeEpochMs = start + i * 60_000L,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            timeframe = if (forwardMode) Timeframe.M5 else Timeframe.M15
        )
        price = close
    }
    return candles
}

@Composable
private fun RegimeScreen(settings: BotSettings) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Market Regime Detection", "The bot changes behavior depending on trend, volatility and news-risk conditions.") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("BTC Regime", "Trend Up", "Scalping + trend allowed", Mint) }
                item { MetricCard("ETH Regime", "Sideways", "Scalping only", Amber) }
                item { MetricCard("SOL Regime", "High Vol", "Reduced size", Danger) }
            }
        }
        item {
            GlassCard {
                SectionTitle("Automatic strategy routing", "AUTO mode selects the strategy that fits the current regime.")
                Text("Trending up: Trend or scalping mode", color = Mint)
                Text("Sideways: Scalping/reversal only", color = Amber)
                Text("High volatility or risk-off: reduce size, raise threshold or block", color = Danger)
                Text("Current strategy mode: ${settings.strategyMode.name}", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun OrdersScreen(
    settings: BotSettings,
    orders: List<LiveOrderInfo>,
    onRefresh: () -> Unit,
    onCancel: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Live Orders", "Fully live Kraken order monitor: open orders, stale cancel/requote, market/limit mode and manual cancel.") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Open Orders", orders.size.toString(), "Live exchange count", Electric) }
                item { MetricCard("Order Mode", settings.orderManagementMode.name.replace('_', ' '), "Execution style", Mint) }
                item { MetricCard("Stale Cancel", "${settings.staleOrderTimeoutSeconds}s", "Auto-cancel timeout", Amber) }
                item { MetricCard("Market Orders", if (settings.enableMarketOrders) "ON" else "OFF", "Kraken toggle", if (settings.enableMarketOrders) Danger else Muted) }
            }
        }
        item {
            GlassCard {
                ToggleInfo("Max trades/hour guard", settings.maxTradesPerHour > 0)
                ToggleInfo("Symbol cooldowns", settings.cooldownAfterBuyMinutes > 0 || settings.cooldownAfterSellMinutes > 0)
                ToggleInfo("Quote reserve guard", settings.minimumQuoteReserveAmount > BigDecimal.ZERO || settings.minimumQuoteReservePercent > BigDecimal.ZERO)
                ToggleInfo("Trailing stop setting", settings.enableTrailingStop)
                ToggleInfo("Break-even stop setting", settings.enableBreakEvenStop)
                ToggleInfo("Partial take-profit setting", settings.enablePartialTakeProfit)
                ToggleInfo("Smart requote", settings.smartLimitRequote)
                Text("The foreground service now syncs open orders every scan and cancels stale limit orders when smart requote is enabled.", color = Muted)
                Spacer(Modifier.height(10.dp))
                Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Text("Refresh Live Open Orders") }
            }
        }
        if (orders.isEmpty()) {
            item { WarningCard("No live open orders returned by ${settings.exchangeProvider}. If the bot placed market orders, they may fill immediately and not appear here.") }
        } else {
            items(orders) { order ->
                LiveOrderCard(order = order, onCancel = { onCancel(order.exchangeOrderId) })
            }
        }
    }
}

@Composable
private fun LiveOrderCard(order: LiveOrderInfo, onCancel: () -> Unit) {
    GlassCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("${order.side} ${order.symbol}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("${order.orderType} • ${order.status} • id=${order.exchangeOrderId.take(10)}…", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            StatusPill(order.side.name, if (order.side.name == "BUY") Mint else Danger)
        }
        Spacer(Modifier.height(8.dp))
        Text("price=${order.price.stripTrailingZeros().toPlainString()} • qty=${order.quantity.stripTrailingZeros().toPlainString()} • remaining=${order.remainingQuantity.stripTrailingZeros().toPlainString()}", color = Muted)
        if (order.description.isNotBlank()) Text(order.description, color = Muted, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel Order") }
    }
}


@Composable
private fun PositionsScreen(
    settings: BotSettings,
    snapshot: LifecycleSnapshot?,
    onRefresh: () -> Unit
) {
    val positions = snapshot?.positions.orEmpty()
    val performance = snapshot?.performance
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Live Positions + Profit Maximizer", "Automatic lifecycle manager tracks held crypto, high-water marks, TP/SL, trailing exits and bearish AI exits.") }
        item {
            HeroCard(
                title = "Lifecycle Manager ${if (settings.liveLifecycleManagerEnabled) "ON" else "OFF"}",
                subtitle = "TP=${settings.takeProfitPercent}% • SL=${settings.stopLossPercent}% • trailing after ${settings.trailingActivationPercent}% with ${settings.trailingDistancePercent}% distance.",
                primaryButton = "Refresh Positions",
                secondaryButton = "Auto Exit ${if (settings.autoExitManagerEnabled) "ON" else "OFF"}",
                onPrimary = onRefresh,
                onSecondary = {}
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Positions", positions.size.toString(), "Live held assets", Mint) }
                item { MetricCard("Trades", (performance?.totalTrades ?: 0).toString(), "Synced/local trades", Electric) }
                item { MetricCard("Win Rate", "${performance?.winRatePercent ?: BigDecimal.ZERO}%", "Closed P/L rows", Amber) }
                item { MetricCard("Realized P/L", "€${performance?.realizedPnlEur ?: BigDecimal.ZERO}", "Estimated local", if ((performance?.realizedPnlEur ?: BigDecimal.ZERO) >= BigDecimal.ZERO) Mint else Danger) }
            }
        }
        item {
            GlassCard {
                SectionTitle("Automatic Exit Rules", "The bot tries to capture profits without assuming it can know the absolute top.")
                ToggleInfo("Auto take-profit", settings.autoTakeProfitEnabled)
                ToggleInfo("Auto stop-loss", settings.autoStopLossEnabled)
                ToggleInfo("Profit maximizer / trailing exits", settings.profitMaximizerEnabled)
                ToggleInfo("Sell on bearish AI signal", settings.forceSellOnBearishSignal)
                ToggleInfo("Sync Kraken closed orders", settings.syncKrakenHistory)
            }
        }
        if (snapshot == null) {
            item { WarningCard("Press Refresh Positions after selecting Kraken and saving API keys. The lifecycle manager uses live portfolio balances and local/Kraken trade history.") }
        } else if (positions.isEmpty()) {
            item { WarningCard("No live crypto positions were detected. Free EUR can be used for BUY orders; held crypto appears here once available through Kraken's portfolio API.") }
        } else {
            items(positions) { position -> PositionCard(position) }
        }
        snapshot?.messages?.takeIf { it.isNotEmpty() }?.let { messages ->
            item {
                GlassCard {
                    SectionTitle("Lifecycle Messages", "Latest automatic-management notes.")
                    messages.forEach { Text(it, color = Muted) }
                }
            }
        }
    }
}

@Composable
private fun PositionCard(position: com.ksp.cryptobot.core.PositionInfo) {
    val pnlColor = if (position.unrealizedPnlEur >= BigDecimal.ZERO) Mint else Danger
    GlassCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(position.symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("qty=${position.quantity.stripTrailingZeros().toPlainString()} • free=${position.freeQuantity.stripTrailingZeros().toPlainString()}", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            StatusPill("${position.unrealizedPnlPercent.setScale(2, java.math.RoundingMode.HALF_UP)}%", pnlColor)
        }
        LinearProgressIndicator(progress = (position.unrealizedPnlPercent.abs().min(BigDecimal("10")).divide(BigDecimal("10"), 2, java.math.RoundingMode.HALF_UP).toFloat()).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().height(8.dp), color = pnlColor, trackColor = Stroke)
        Text("entry=${position.entryPrice.setScale(4, java.math.RoundingMode.DOWN)} • now=${position.currentPrice.setScale(4, java.math.RoundingMode.DOWN)} • high=${position.highestPrice.setScale(4, java.math.RoundingMode.DOWN)}", color = Muted)
        Text("TP=${position.takeProfitPrice.setScale(4, java.math.RoundingMode.DOWN)} • SL=${position.stopPrice.setScale(4, java.math.RoundingMode.DOWN)} • trail=${position.trailingStopPrice.setScale(4, java.math.RoundingMode.DOWN)}", color = Muted)
        Text("P/L≈€${position.unrealizedPnlEur.setScale(2, java.math.RoundingMode.HALF_UP)} • ${position.reason}", color = pnlColor)
    }
}

@Composable
private fun HistoryScreen(
    settings: BotSettings,
    trades: List<TradeEntity>,
    events: List<String>,
    onRefresh: () -> Unit
) {
    val realized = trades.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }.fold(BigDecimal.ZERO) { a, b -> a + b }
    val buyCount = trades.count { it.side.uppercase().contains("BUY") }
    val sellCount = trades.count { it.side.uppercase().contains("SELL") }
    val uniqueEvents = events.map { it.trim() }.filter { it.isNotBlank() }.distinct().take(60)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SectionTitle("Actual History", "Real local trade journal plus deduplicated bot events. This replaces the old static Trade Memory explanation screen.")
        }
        item {
            GlassCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("History Summary", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                        Text("Trades=${trades.size} • Events=${uniqueEvents.size}", color = Muted)
                    }
                    Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = Electric)) { Text("Refresh") }
                }
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { MetricCard("Buys", buyCount.toString(), "Entries", Mint) }
                    item { MetricCard("Sells", sellCount.toString(), "Exits", Danger) }
                    item { MetricCard("Realized P/L", "€${realized.setScale(2, RoundingMode.HALF_UP)}", "Known local DB", if (realized >= BigDecimal.ZERO) Mint else Danger) }
                    item { MetricCard("Mode", settings.mode.name, "Current mode", modeColor(settings.mode)) }
                }
            }
        }
        item {
            GlassCard {
                SectionTitle("Trade History", "Last 100 paper/live trade rows recorded by the app.")
                if (trades.isEmpty()) {
                    Text("No trade rows found yet. Run PAPER/LIVE execution or restore a backup with trades.", color = Muted)
                } else {
                    trades.take(100).forEach { trade ->
                        Text("${trade.symbol} ${trade.side} • qty=${trade.quantity} • price=€${trade.priceEur} • fee=€${trade.feeEur} • ${if (trade.paper) "PAPER" else "LIVE"}", color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("P/L €${trade.realizedPnlEur} • AI ${trade.aiScore} • id=${trade.exchangeOrderId}", color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        item {
            GlassCard {
                SectionTitle("Bot Event History", "Deduplicated recent status/events from the foreground service and UI.")
                if (uniqueEvents.isEmpty()) {
                    Text("No bot events recorded yet.", color = Muted)
                } else {
                    uniqueEvents.forEach { event ->
                        Text("• $event", color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PortfolioScreen(
    settings: BotSettings,
    snapshot: PortfolioSnapshot?,
    lifecycleSnapshot: LifecycleSnapshot?,
    onRefresh: () -> Unit
) {
    val assets = snapshot?.assets.orEmpty()
    val lifecyclePositions = lifecycleSnapshot?.positions.orEmpty()
    val positionsByAsset = lifecyclePositions.associateBy { it.baseAsset.uppercase() }
    val total = snapshot?.totalValueEur ?: BigDecimal.ZERO
    val freeEur = snapshot?.freeEur ?: BigDecimal.ZERO
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle(if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) "Paper Portfolio" else "Live Portfolio", "Reads balances from the selected provider. PAPER uses a local simulated wallet; KRAKEN uses live exchange balances.") }
        item {
            HeroCard(
                title = "Total Portfolio ≈ €${total.setScale(2, java.math.RoundingMode.DOWN)}",
                subtitle = "Provider: ${settings.exchangeProvider}. Free EUR available for BUY orders: €${freeEur.setScale(2, java.math.RoundingMode.DOWN)}.",
                primaryButton = if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) "Refresh Paper" else "Refresh Live",
                secondaryButton = "Open Risk",
                onPrimary = onRefresh,
                onSecondary = {}
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Total Value", "€${total.setScale(2, java.math.RoundingMode.DOWN)}", "Estimated from live balances", Mint) }
                item { MetricCard("Free EUR", "€${freeEur.setScale(2, java.math.RoundingMode.DOWN)}", "BUY budget", Electric) }
                item { MetricCard("Assets", assets.size.toString(), "Positive balances", Amber) }
                item { MetricCard("Guarded", lifecyclePositions.size.toString(), "TP/SL/trailing positions", Electric) }
                item { MetricCard("Mode", settings.mode.name, "Execution mode", modeColor(settings.mode)) }
            }
        }
        if (snapshot == null) {
            item { WarningCard(if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) "Press Refresh Paper to load the local simulated wallet. PAPER mode starts with simulated EUR and updates after paper buys/sells." else "Press Refresh Live to load balances from ${settings.exchangeProvider}. The bot can only BUY with free quote balance and can only SELL crypto that the exchange reports as free/available.") }
        } else {
            item { WarningCard(snapshot.warning) }
            if (assets.isEmpty()) {
                item { WarningCard("No positive balances were returned by the selected exchange API. Check API permissions and selected exchange provider.") }
            } else {
                items(assets) { asset ->
                    val pct = if (total > BigDecimal.ZERO && asset.eurValue > BigDecimal.ZERO) {
                        asset.eurValue.divide(total, 4, java.math.RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
                    } else 0f
                    LiveBalanceRow(asset = asset, progress = pct, position = positionsByAsset[asset.asset.uppercase()])
                }
            }
        }
    }
}

@Composable
private fun LiveBalanceRow(
    asset: com.ksp.cryptobot.core.BalanceInfo,
    progress: Float,
    position: com.ksp.cryptobot.core.PositionInfo? = null
) {
    GlassCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(asset.asset, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("total=${asset.total.stripTrailingZeros().toPlainString()} • free=${asset.free.stripTrailingZeros().toPlainString()} • held=${asset.holdTrade.stripTrailingZeros().toPlainString()}", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("€${asset.eurValue.setScale(2, java.math.RoundingMode.DOWN)}", color = Mint, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(8.dp), color = Mint, trackColor = Stroke)
        if (asset.eurValue > BigDecimal.ZERO && asset.eurValue < BigDecimal("5.00")) {
            Spacer(Modifier.height(8.dp))
            Text("Dust balance: below typical exchange minimum order value, may be impossible to sell/convert automatically.", color = Amber)
        }
        val actionText = when {
            asset.asset == "EUR" && asset.free >= BigDecimal("5.00") -> "Available for automatic BUY orders."
            asset.asset == "EUR" -> "Too low for BUY orders. Deposit/convert to free EUR if you want buys."
            asset.free > BigDecimal.ZERO -> "Available for automatic SELL orders when the strategy turns bearish."
            else -> "Not currently free for bot trading."
        }
        Text(actionText, color = Muted, style = MaterialTheme.typography.bodySmall)
        position?.let { pos ->
            Spacer(Modifier.height(10.dp))
            Divider(color = Stroke)
            Spacer(Modifier.height(8.dp))
            val pnlColor = if (pos.unrealizedPnlEur >= BigDecimal.ZERO) Mint else Danger
            val trailingArmed = settingsTrailingArmedPlaceholder(pos)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Position guards", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                StatusPill(if (pos.managed) "MANAGED" else "WATCH", if (pos.managed) Mint else Amber)
            }
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { StatusPill(if (pos.takeProfitPrice > BigDecimal.ZERO) "TP ARMED" else "TP OFF", if (pos.takeProfitPrice > BigDecimal.ZERO) Mint else Muted) }
                item { StatusPill(if (pos.stopPrice > BigDecimal.ZERO) "SL ARMED" else "SL OFF", if (pos.stopPrice > BigDecimal.ZERO) Danger else Muted) }
                item { StatusPill(if (trailingArmed) "TRAILING ARMED" else "TRAILING WAIT", if (trailingArmed) Amber else Muted) }
            }
            Spacer(Modifier.height(8.dp))
            Text("entry=${pos.entryPrice.setScale(4, java.math.RoundingMode.DOWN)} • now=${pos.currentPrice.setScale(4, java.math.RoundingMode.DOWN)} • high=${pos.highestPrice.setScale(4, java.math.RoundingMode.DOWN)}", color = Muted)
            Text("TP=${pos.takeProfitPrice.setScale(4, java.math.RoundingMode.DOWN)} • SL=${pos.stopPrice.setScale(4, java.math.RoundingMode.DOWN)} • trail=${pos.trailingStopPrice.setScale(4, java.math.RoundingMode.DOWN)}", color = Muted)
            Text("P/L≈€${pos.unrealizedPnlEur.setScale(2, java.math.RoundingMode.HALF_UP)} • ${pos.unrealizedPnlPercent.setScale(2, java.math.RoundingMode.HALF_UP)}% • ${pos.reason}", color = pnlColor)
        }
    }
}

@Composable
private fun NewsScreen(
    settings: BotSettings,
    newsHistory: List<NewsArticleEntity>,
    activeSymbols: List<String>,
    onToggleNews: (Boolean) -> Unit,
    onRefreshHistory: (String) -> Unit,
    onScanNews: (String) -> Unit
) {
    var selectedSymbol by remember(settings.symbolsCsv, activeSymbols) {
        mutableStateOf((activeSymbols + settings.symbols()).firstOrNull()?.uppercase()?.replace("/", "")?.replace("-", "") ?: "")
    }
    val symbols = (settings.symbols() + activeSymbols + newsHistory.map { it.symbol })
        .map { it.uppercase().replace("/", "").replace("-", "") }
        .filter { it.isNotBlank() }
        .distinct()
    val visibleNews = if (selectedSymbol.isBlank()) newsHistory else newsHistory.filter { it.symbol.equals(selectedSymbol, ignoreCase = true) }
    val grouped = newsHistory.groupBy { it.symbol }.toList().sortedByDescending { it.second.size }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("News Dashboard", "Cached per-symbol articles from NewsAPI + CryptoCompare, used by AI signal scoring.") }
        item {
            GlassCard {
                ToggleRow("Use news sentiment in AI decisions", settings.useNewsAi, onToggleNews)
                Text("During scans, every symbol fetches news, stores articles locally, and adds article titles into the AI decision explanation.", color = Muted)
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = selectedSymbol.isBlank(),
                            onClick = {
                                selectedSymbol = ""
                                onRefreshHistory("")
                            },
                            label = { Text("ALL") }
                        )
                    }
                    items(symbols) { symbol ->
                        FilterChip(
                            selected = selectedSymbol.equals(symbol, ignoreCase = true),
                            onClick = {
                                selectedSymbol = symbol
                                onRefreshHistory(symbol)
                            },
                            label = { Text(symbol) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onScanNews(selectedSymbol) },
                        colors = ButtonDefaults.buttonColors(containerColor = Electric)
                    ) { Text("Scan News") }
                    OutlinedButton(onClick = { onRefreshHistory(selectedSymbol) }) { Text("Refresh Cache") }
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Cached articles", newsHistory.size.toString(), "Local DB", Electric) }
                item { MetricCard("Symbols", grouped.size.toString(), "With cached news", Mint) }
                item { MetricCard("Visible", visibleNews.size.toString(), selectedSymbol.ifBlank { "All symbols" }, Amber) }
            }
        }
        if (grouped.isNotEmpty()) {
            item {
                GlassCard {
                    Text("Per-symbol cache", fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(6.dp))
                    grouped.take(12).forEach { (symbol, rows) ->
                        Text("$symbol: ${rows.size} article(s)", color = if (symbol.equals(selectedSymbol, ignoreCase = true)) Mint else Muted)
                    }
                }
            }
        }
        if (visibleNews.isEmpty()) {
            item {
                GlassCard {
                    Text("No cached news yet.", fontWeight = FontWeight.Bold)
                    Text("Press Scan News or run a normal market scan. Articles are stored after each symbol news check.", color = Muted)
                }
            }
        } else {
            items(visibleNews.take(80)) { article ->
                GlassCard {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(article.symbol, Electric)
                        Spacer(Modifier.width(8.dp))
                        Text(article.source.ifBlank { article.provider }, color = Muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(article.title, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (article.description.isNotBlank()) {
                        Text(article.description, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    Text("provider=${article.provider} • fetched=${article.fetchedAtEpochMs}", color = Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}


@Composable
private fun TaxScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Belgium Tax Guard", "Estimated tracking for realized gains, taxable events and yearly planning.") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Realized Gains", "€0.00", "Current tax year", Mint) }
                item { MetricCard("Estimated Tax", "€0.00", "Before confirmed tax review", Amber) }
                item { MetricCard("Export", "CSV", "Trade and tax lots", Electric) }
            }
        }
        item {
            GlassCard {
                SectionTitle("Tax-aware sell blocker", "The bot can penalize sells when they create avoidable realized gains.")
                TaxRow("Track every buy/sell", true)
                TaxRow("Estimate gain before sell", true)
                TaxRow("Separate transfer vs taxable sale", true)
                TaxRow("Manual accountant review needed", true)
            }
        }
        item { WarningCard("This is an estimation module, not official Belgian tax advice. Export records and verify with a qualified Belgian tax professional.") }
    }
}

@Composable
private fun RiskScreen(
    settings: BotSettings,
    maxPosition: String,
    maxLoss: String,
    maxTrades: String,
    maxSpread: String,
    onMaxPosition: (String) -> Unit,
    onMaxLoss: (String) -> Unit,
    onMaxTrades: (String) -> Unit,
    onMaxSpread: (String) -> Unit,
    onTaxOptimization: (Boolean) -> Unit,
    onTradeOnlyBtcEth: (Boolean) -> Unit,
    onSave: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Risk Center", "Position sizing, spread filter, loss cap and trading universe controls.") }
        item {
            GlassCard {
                OutlinedTextField(value = maxPosition, onValueChange = onMaxPosition, label = { Text("Max position EUR") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = maxLoss, onValueChange = onMaxLoss, label = { Text("Max daily loss EUR") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = maxTrades, onValueChange = onMaxTrades, label = { Text("Max trades/day") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = maxSpread, onValueChange = onMaxSpread, label = { Text("Max spread %") }, modifier = Modifier.fillMaxWidth())
                ToggleRow("Tax optimization guard", settings.taxOptimization, onTaxOptimization)
                ToggleRow("Restrict to BTC/ETH only", settings.tradeOnlyBtcEth, onTradeOnlyBtcEth)
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text("Save Risk Profile") }
            }
        }
    }
}



@Composable
private fun AutonomousScreen(
    settings: BotSettings,
    taxExportSummary: TaxExportSummary?,
    remoteCommand: String,
    remoteResult: RemoteCommandResult?,
    onRemoteCommandChange: (String) -> Unit,
    onExportTax: () -> Unit,
    onRunRemoteCommand: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("v1.2 Autonomous Intelligence", "Self-selection, self-optimization, bad-symbol lockout, shadow paper comparison, trade replay, remote command parsing, tax export and Android watchdog controls.") }
        item {
            HeroCard(
                title = "Autonomous Pack ${if (settings.selfOptimizationEnabled) "ON" else "OFF"}",
                subtitle = "The bot automatically chooses symbol strategy, penalizes weak symbols, records trade replay context and keeps all live execution behind Kraken/risk/balance guards.",
                primaryButton = "Export Tax CSV",
                secondaryButton = "Parse Command",
                onPrimary = onExportTax,
                onSecondary = onRunRemoteCommand
            )
        }
        item {
            GlassCard {
                SectionTitle("Self-Optimization", "Automatic strategy and symbol behavior.")
                ToggleInfo("Per-symbol strategy selector", settings.autonomousStrategyPerSymbolEnabled)
                ToggleInfo("Self-optimization engine", settings.selfOptimizationEnabled)
                ToggleInfo("Auto-disable bad symbols", settings.autoDisableBadSymbolsEnabled)
                Text("Bad symbols: disable logic ${settings.badSymbolDisableHours}h when win rate < ${settings.minSymbolWinRatePercent}% or profit factor < ${settings.minSymbolProfitFactor}.", color = Muted)
                Text("Optimizer lookback: ${settings.optimizerLookbackTrades} trades per symbol.", color = Muted)
            }
        }
        item {
            GlassCard {
                SectionTitle("Paper/Live Learning", "The bot records alternative outcomes to improve future behavior.")
                ToggleInfo("Shadow paper comparison", settings.shadowPaperComparisonEnabled)
                ToggleInfo("Trade replay snapshots", settings.tradeReplayEnabled)
                ToggleInfo("Dry-run mirror mode", settings.dryRunMirrorModeEnabled)
                Text("Each decision stores a replay explanation so you can see why a trade was taken or skipped and compare live exits against alternative paper exits.", color = Muted)
            }
        }
        item {
            GlassCard {
                SectionTitle("Autonomous Safety", "Stops automation from becoming uncontrolled.")
                ToggleInfo("Portfolio reserve manager", settings.portfolioReserveManagerV12Enabled)
                ToggleInfo("Crash recovery watchdog", settings.crashRecoveryWatchdogV12Enabled)
                ToggleInfo("Remote command parser", settings.remoteCommandParserEnabled)
                Text("Target EUR reserve: ${settings.minimumEurReservePercent}% • Battery pause threshold: ${settings.pauseBelowBatteryPercent}%", color = Muted)
            }
        }
        item {
            GlassCard {
                SectionTitle("Command Parser", "Local command parser for status/pause/resume style commands.")
                OutlinedTextField(value = remoteCommand, onValueChange = onRemoteCommandChange, label = { Text("Command") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRunRemoteCommand, modifier = Modifier.fillMaxWidth()) { Text("Parse Command") }
                remoteResult?.let { result ->
                    Spacer(Modifier.height(8.dp))
                    Text(result.message, color = if (result.accepted) Mint else Amber)
                }
            }
        }
        item {
            GlassCard {
                SectionTitle("Belgian Tax Export", "Generates CSV rows from lifecycle tax rows or fallback trade history.")
                Button(onClick = onExportTax, modifier = Modifier.fillMaxWidth()) { Text("Generate ${settings.taxExportYear} Tax CSV") }
                taxExportSummary?.let { summary ->
                    Spacer(Modifier.height(8.dp))
                    Text("Rows: ${summary.rowCount}", color = Muted)
                    Text("Estimated realized gain: €${summary.realizedGainEur.setScale(2, java.math.RoundingMode.HALF_UP)}", color = Mint)
                    Text(summary.csv.take(600), color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { WarningCard("v1.2 makes the bot more automatic, but it still cannot guarantee maximum profit. It improves process quality by choosing strategies, disabling weak symbols, tracking replay context and exporting records.") }
    }
}


@Composable
private fun SelfLearningScreen(
    settings: BotSettings,
    summary: TrueSelfLearningEngine.LearningSummary?,
    onRefresh: () -> Unit
) {
    val profiles = summary?.symbolProfiles.orEmpty()
    val strategies = summary?.strategyProfiles.orEmpty()
    val holdProfiles = summary?.holdProfiles.orEmpty()
    val audit = summary?.audit.orEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("v1.7 True Self-Learning", "Persistent symbol/strategy profiles that learn from paper and live trade outcomes.") }
        item {
            HeroCard(
                title = if (settings.trueSelfLearningEnabled) "Self-Learning ON" else "Self-Learning OFF",
                subtitle = summary?.summaryLine ?: "Press Refresh Learning to load persisted profiles and audit events.",
                primaryButton = "Refresh Learning",
                secondaryButton = "Min samples: ${settings.selfLearningMinSamples}",
                onPrimary = onRefresh,
                onSecondary = {}
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Profiles", profiles.size.toString(), "Learned symbols", Electric) }
                item { MetricCard("Strategies", strategies.size.toString(), "Learned strategy keys", Mint) }
                item { MetricCard("Hold Profiles", holdProfiles.size.toString(), "Learned hold symbols", Electric) }
                item { MetricCard("Max Boost", settings.selfLearningMaxScoreBoost.toString(), "Score cap", Amber) }
                item { MetricCard("Max Penalty", settings.selfLearningMaxScorePenalty.toString(), "Risk cap", Danger) }
            }
        }
        item {
            GlassCard {
                SectionTitle("Learning Controls", "These settings bound the bot's adaptation so it cannot become uncontrolled.")
                ToggleInfo("True self-learning", settings.trueSelfLearningEnabled)
                ToggleInfo("Position-size learning", settings.selfLearningPositionSizingEnabled)
                ToggleInfo("Auto-disable bad symbols", settings.selfLearningAutoDisableEnabled)
                ToggleInfo("Paper/live separation", settings.selfLearningPaperAndLiveSeparated)
                ToggleInfo("Explain every learned decision", settings.selfLearningExplainEveryDecision)
                ToggleInfo("Learned hold for profit", settings.learnedHoldForProfitEnabled)
                ToggleInfo("Spike profit timing", settings.spikeProfitTimingEnabled)
                Text("Lookback: ${settings.selfLearningLookbackTrades} trades • Minimum samples: ${settings.selfLearningMinSamples} per symbol", color = Muted)
                Text("Hold learning: min ${settings.learnedHoldMinSamples} exits • confidence ≥ ${settings.learnedHoldConfidenceThresholdPercent}% • min profit ${settings.learnedHoldMinProfitPercent}%", color = Muted)
                Text("Spike timing: historical spike ≥ ${settings.spikeTimingHistoricalSpikeThresholdPercent}% • hold until ${settings.spikeTimingHoldUntilProgressPercent}% of typical spike • sell near ${settings.spikeTimingExhaustionProgressPercent}%/weakness", color = Muted)
            }
        }
        if (profiles.isEmpty()) {
            item { WarningCard("No learned profiles yet. Run PAPER mode or live mode until enough completed trades exist. The engine will stay neutral until sample-size protection is satisfied.") }
        } else {
            item { SectionTitle("Learned Symbol Profiles", "Score adjustment, position multiplier and preferred strategy per symbol.") }
            items(profiles.take(30)) { profile ->
                GlassCard {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile.symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                            Text("samples=${profile.sampleSize} • wins=${profile.wins} • losses=${profile.losses}", color = Muted)
                        }
                        val adjColor = if (profile.scoreAdjustment >= 0) Mint else Danger
                        StatusPill("${if (profile.scoreAdjustment >= 0) "+" else ""}${profile.scoreAdjustment}", adjColor)
                    }
                    Text("Win ${profile.winRatePercent}% • PF ${profile.profitFactor} • Net €${profile.netPnlEur} • Avg €${profile.averagePnlEur}", color = Muted)
                    Text("Strategy=${profile.preferredStrategy} • size×${profile.positionMultiplier} • confidence=${profile.confidence}%", color = Muted)
                    if (profile.disabledUntilEpochMs > System.currentTimeMillis()) Text("Temporarily disabled by learning guard until ${profile.disabledUntilEpochMs}", color = Danger)
                    Text(profile.explanation, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (holdProfiles.isNotEmpty()) {
            item { SectionTitle("Learned Hold Profiles", "Symbols where the bot learned whether to hold longer instead of selling immediately at TP/trailing exits.") }
            items(holdProfiles.take(20)) { profile ->
                GlassCard {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("samples=${profile.sampleSize} • profitable=${profile.profitableExits} • losing=${profile.losingExits}", color = Muted)
                        }
                        StatusPill("hold ${profile.holdConfidencePercent}%", if (profile.holdConfidencePercent >= settings.learnedHoldConfidenceThresholdPercent) Mint else Amber)
                    }
                    Text("Continuation win ${profile.continuationWinRatePercent}% • avgHold ${profile.averageHoldMinutes}m • net €${profile.netPnlEur}", color = Muted)
                    Text("deferTP=${profile.shouldDeferTakeProfit} • deferTrailing=${profile.shouldDeferTrailingExit} • hold×${profile.holdMultiplier}", color = Muted)
                    Text(profile.explanation, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (strategies.isNotEmpty()) {
            item { SectionTitle("Learned Strategy Profiles", "Strategy-level performance memory.") }
            items(strategies.take(12)) { strategy ->
                GlassCard {
                    Text(strategy.strategyKey, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("samples=${strategy.sampleSize} • win=${strategy.winRatePercent}% • PF=${strategy.profitFactor} • scoreAdj=${strategy.scoreAdjustment} • size×${strategy.positionMultiplier}", color = Muted)
                    Text(strategy.explanation, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (audit.isNotEmpty()) {
            item { SectionTitle("Learning Audit", "Recent profile updates and learning decisions.") }
            items(audit.take(20)) { row ->
                GlassCard {
                    Text("${row.eventType} • ${row.symbol}", fontWeight = FontWeight.Bold)
                    Text(row.message, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { WarningCard("Self-learning changes scores and sizing hints only. It does not bypass exchange, balance, reserve, cooldown, spread, market-order or Belgian compliance guards.") }
    }
}

@Composable
private fun ProSystemsScreen(settings: BotSettings) {
    val pro = remember { ProAutomationSuite() }
    val readiness = remember(settings) { pro.readiness(settings) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("v1.1/v1.2 Pro Systems", "Closed-loop automation modules that make the live bot more observable, adaptive and protective.") }
        item {
            GlassCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (readiness.allowed) Mint else Amber)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automation readiness", color = Muted)
                        Text(readiness.level, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (readiness.allowed) Mint else Amber)
                    }
                    StatusPill(readiness.level, if (readiness.allowed) Mint else Amber)
                }
                readiness.lines.forEach { line -> ToggleInfo(line, line.startsWith("OK")) }
            }
        }
        item {
            GlassCard {
                SectionTitle("Live Intelligence", "These modules run through the service, controller or diagnostics layer.")
                ToggleInfo("Kraken REST live ticker monitor", settings.enableKrakenWebSocketFeed)
                ToggleInfo("Smart profit-lock engine", settings.smartProfitLockEnabled)
                ToggleInfo("Fee/spread net-profit filter", settings.enableNetProfitFilter)
                ToggleInfo("Why traded / why skipped explanations", settings.saveWhyTradedExplanations)
                ToggleInfo("Strategy optimizer", settings.strategyOptimizerEnabled)
                ToggleInfo("Portfolio balancer", settings.portfolioBalancerEnabled)
                ToggleInfo("Android watchdog", settings.watchdogEnabled)
                ToggleInfo("Dry-run mirror exits", settings.dryRunMirrorModeEnabled)
                ToggleInfo("Local explainable ML score", settings.localMlScoringEnabled)
            }
        }
        item {
            GlassCard {
                SectionTitle("Smart Profit Lock", "Designed to capture more upside without pretending to know the exact market top.")
                Text("Activation: +${settings.smartProfitLockActivationPercent}%", color = Muted)
                Text("Trailing distance: ${settings.smartProfitLockTrailingDistancePercent}%", color = Muted)
                Text("Partial TP trigger: +${settings.smartProfitLockPartialTakeProfitPercent}%", color = Muted)
                Text("Partial exit size: ${settings.smartProfitLockPartialExitPercent}%", color = Muted)
            }
        }
        item {
            GlassCard {
                SectionTitle("Portfolio Rules", "The bot can automatically avoid overexposure before opening new buys.")
                Text("Minimum EUR reserve: ${settings.minimumEurReservePercent}%", color = Muted)
                Text("Max single-asset allocation: ${settings.maxSingleAssetAllocationPercent}%", color = Muted)
                Text("Auto-compounding: ${if (settings.autoCompoundingEnabled) "enabled" else "disabled"}", color = Muted)
            }
        }
        item { WarningCard("v1.1 makes the bot more automatic, but it cannot guarantee maximum possible profits. It uses profit-locking, exits, risk gates and explanations to improve process quality and reduce uncontrolled live-trading behavior.") }
    }
}


@Composable
private fun AdvancedSettingsScreen(
    settings: BotSettings,
    onApply: (BotSettings) -> Unit
) {
    var minAiScore by remember(settings) { mutableStateOf(settings.minStrategyScoreToBuy.toString()) }
    var timeframeAgreement by remember(settings) { mutableStateOf(settings.minTrendAgreement.toString()) }
    var allowedQuotes by remember(settings) { mutableStateOf(settings.allowedQuoteAssetsCsv) }
    var candidateLimit by remember(settings) { mutableStateOf(settings.autoSymbolCandidateLimit.toString()) }
    var activeLimit by remember(settings) { mutableStateOf(settings.autoSymbolActiveLimit.toString()) }
    var maxNewTrades by remember(settings) { mutableStateOf(settings.maxNewTradesPerScan.toString()) }
    var maxTradesDay by remember(settings) { mutableStateOf(settings.maxTradesPerDay.toString()) }
    var maxTradesHour by remember(settings) { mutableStateOf(settings.maxTradesPerHour.toString()) }
    var maxLivePositions by remember(settings) { mutableStateOf(settings.maxSimultaneousLivePositions.toString()) }
    var maxPosition by remember(settings) { mutableStateOf(settings.maxPositionEur.toPlainString()) }
    var maxBuyPriceEnabled by remember(settings) { mutableStateOf(settings.maxBuyPriceFilterEnabled) }
    var globalMaxBuyPrice by remember(settings) { mutableStateOf(settings.globalMaxBuyPriceEur.toPlainString()) }
    var perSymbolMaxBuyPrice by remember(settings) { mutableStateOf(settings.perSymbolMaxBuyPriceCsv) }
    var ultimateAutomation by remember(settings) { mutableStateOf(settings.ultimateAutomationEnabled) }
    var perSymbolRules by remember(settings) { mutableStateOf(settings.perSymbolRulesEnabled) }
    var perSymbolRulesText by remember(settings) { mutableStateOf(settings.perSymbolRulesCsv) }
    var compoundingHardCap by remember(settings) { mutableStateOf(settings.autoCompoundingHardCapEnabled) }
    var compoundingMaxPosition by remember(settings) { mutableStateOf(settings.autoCompoundingMaxPositionEur.toPlainString()) }
    var failureAutoPause by remember(settings) { mutableStateOf(settings.autoPauseAfterOrderFailuresEnabled) }
    var failureAutoPauseThreshold by remember(settings) { mutableStateOf(settings.autoPauseFailureThreshold.toString()) }
    var failureAutoPauseMinutes by remember(settings) { mutableStateOf(settings.autoPauseMinutes.toString()) }
    var volatilityCircuitBreaker by remember(settings) { mutableStateOf(settings.volatilityCircuitBreakerEnabled) }
    var volatilityMaxMove by remember(settings) { mutableStateOf(settings.volatilityCircuitBreakerMax24hMovePercent.toPlainString()) }
    var pumpChaseProtection by remember(settings) { mutableStateOf(settings.pumpChaseProtectionEnabled) }
    var pumpChaseMaxGain by remember(settings) { mutableStateOf(settings.pumpChaseMax24hGainPercent.toPlainString()) }
    var duplicatePositionProtection by remember(settings) { mutableStateOf(settings.duplicatePositionProtectionEnabled) }
    var adaptiveRealizedPnlCompounding by remember(settings) { mutableStateOf(settings.adaptiveCompoundingFromRealizedPnlEnabled) }
    var dynamicScan by remember(settings) { mutableStateOf(settings.dynamicScanIntervalEnabled) }
    var dynamicFastSeconds by remember(settings) { mutableStateOf(settings.dynamicScanFastSeconds.toString()) }
    var dynamicSlowSeconds by remember(settings) { mutableStateOf(settings.dynamicScanSlowSeconds.toString()) }
    var multiTimeframeConsensus by remember(settings) { mutableStateOf(settings.multiTimeframeConsensusEnabled) }
    var multiTimeframeRequired by remember(settings) { mutableStateOf(settings.multiTimeframeRequiredBullishCount.toString()) }
    var ultimateReadinessScore by remember(settings) { mutableStateOf(settings.ultimateReadinessScoreEnabled) }
    var orderBookDepthGuard by remember(settings) { mutableStateOf(settings.orderBookDepthGuardEnabled) }
    var maxOrderBookSlippage by remember(settings) { mutableStateOf(settings.maxOrderBookSlippagePercent.toPlainString()) }
    var minOrderBookDepthMultiple by remember(settings) { mutableStateOf(settings.minOrderBookDepthMultiple.toPlainString()) }
    var minReserveAmount by remember(settings) { mutableStateOf(settings.minimumQuoteReserveAmount.toPlainString()) }
    var minReservePercent by remember(settings) { mutableStateOf(settings.minimumQuoteReservePercent.toPlainString()) }
    var maxLimitSpread by remember(settings) { mutableStateOf(settings.maxSpreadPercent.toPlainString()) }
    var maxMarketSpread by remember(settings) { mutableStateOf(settings.marketOrderSlippageWarningPercent.toPlainString()) }
    var buyCooldown by remember(settings) { mutableStateOf(settings.cooldownAfterBuyMinutes.toString()) }
    var sellCooldown by remember(settings) { mutableStateOf(settings.cooldownAfterSellMinutes.toString()) }
    var lossCooldown by remember(settings) { mutableStateOf(settings.cooldownAfterLossMinutes.toString()) }
    var autoDiscovery by remember(settings) { mutableStateOf(settings.autoSymbolDiscoveryEnabled) }
    var multiSymbol by remember(settings) { mutableStateOf(settings.autoTradeMultipleSymbolsPerScan) }
    var marketOrders by remember(settings) { mutableStateOf(settings.enableMarketOrders) }
    var fallbackToLimit by remember(settings) { mutableStateOf(settings.fallbackToLimitWhenMarketBlocked) }
    var nonEurBuys by remember(settings) { mutableStateOf(settings.nonEurQuoteBuyEnabled) }
    var liquidityBlacklist by remember(settings) { mutableStateOf(settings.liquidityBlacklistEnabled) }
    var autoDisableBadSymbols by remember(settings) { mutableStateOf(settings.autoDisableBadSymbolsEnabled) }
    var trueSelfLearning by remember(settings) { mutableStateOf(settings.trueSelfLearningEnabled) }
    var learningMinSamples by remember(settings) { mutableStateOf(settings.selfLearningMinSamples.toString()) }
    var learningLookback by remember(settings) { mutableStateOf(settings.selfLearningLookbackTrades.toString()) }
    var learningMaxBoost by remember(settings) { mutableStateOf(settings.selfLearningMaxScoreBoost.toString()) }
    var learningMaxPenalty by remember(settings) { mutableStateOf(settings.selfLearningMaxScorePenalty.toString()) }
    var learningPositionSizing by remember(settings) { mutableStateOf(settings.selfLearningPositionSizingEnabled) }
    var learningAutoDisable by remember(settings) { mutableStateOf(settings.selfLearningAutoDisableEnabled) }

    fun smallBalanceSettings(): BotSettings = settings.copy(
        minStrategyScoreToBuy = 68,
        minTrendAgreement = 2,
        allowedQuoteAssetsCsv = "EUR",
        autoSymbolCandidateLimit = 250,
        autoSymbolActiveLimit = 15,
        maxNewTradesPerScan = 1,
        maxTradesPerHour = 3,
        maxSimultaneousLivePositions = 3,
        maxPositionEur = BigDecimal("5"),
        maxBuyPriceFilterEnabled = false,
        globalMaxBuyPriceEur = BigDecimal.ZERO,
        perSymbolMaxBuyPriceCsv = "",
        ultimateAutomationEnabled = true,
        perSymbolRulesEnabled = false,
        perSymbolRulesCsv = "",
        autoCompoundingHardCapEnabled = true,
        autoCompoundingMaxPositionEur = BigDecimal("15"),
        autoPauseAfterOrderFailuresEnabled = true,
        autoPauseFailureThreshold = 3,
        autoPauseMinutes = 60,
        minimumQuoteReserveAmount = BigDecimal("3"),
        minimumQuoteReservePercent = BigDecimal("5"),
        maxSpreadPercent = BigDecimal("0.50"),
        marketOrderSlippageWarningPercent = BigDecimal("0.25"),
        cooldownAfterBuyMinutes = 15,
        cooldownAfterSellMinutes = 30,
        cooldownAfterLossMinutes = 120,
        autoSymbolDiscoveryEnabled = true,
        autoTradeMultipleSymbolsPerScan = true,
        enableMarketOrders = true,
        fallbackToLimitWhenMarketBlocked = true,
        nonEurQuoteBuyEnabled = false,
        liquidityBlacklistEnabled = true,
        autoDisableBadSymbolsEnabled = false,
        trueSelfLearningEnabled = true,
        selfLearningMinSamples = 10,
        selfLearningPositionSizingEnabled = true,
        selfLearningAutoDisableEnabled = true
    )

    fun balancedSettings(): BotSettings = settings.copy(
        minStrategyScoreToBuy = 72,
        minTrendAgreement = 2,
        allowedQuoteAssetsCsv = "EUR",
        autoSymbolCandidateLimit = 250,
        autoSymbolActiveLimit = 20,
        maxNewTradesPerScan = 2,
        maxTradesPerHour = 3,
        maxSimultaneousLivePositions = 3,
        maxPositionEur = BigDecimal("10"),
        maxBuyPriceFilterEnabled = false,
        globalMaxBuyPriceEur = BigDecimal.ZERO,
        perSymbolMaxBuyPriceCsv = "",
        ultimateAutomationEnabled = true,
        perSymbolRulesEnabled = false,
        perSymbolRulesCsv = "",
        autoCompoundingHardCapEnabled = true,
        autoCompoundingMaxPositionEur = BigDecimal("35"),
        autoPauseAfterOrderFailuresEnabled = true,
        autoPauseFailureThreshold = 3,
        autoPauseMinutes = 60,
        minimumQuoteReserveAmount = BigDecimal("10"),
        minimumQuoteReservePercent = BigDecimal("20"),
        maxSpreadPercent = BigDecimal("0.35"),
        marketOrderSlippageWarningPercent = BigDecimal("0.25"),
        cooldownAfterBuyMinutes = 15,
        cooldownAfterSellMinutes = 30,
        cooldownAfterLossMinutes = 120,
        autoSymbolDiscoveryEnabled = true,
        autoTradeMultipleSymbolsPerScan = true,
        enableMarketOrders = true,
        fallbackToLimitWhenMarketBlocked = true,
        nonEurQuoteBuyEnabled = false,
        liquidityBlacklistEnabled = true,
        autoDisableBadSymbolsEnabled = false,
        trueSelfLearningEnabled = true,
        selfLearningMinSamples = 10,
        selfLearningPositionSizingEnabled = true,
        selfLearningAutoDisableEnabled = true
    )

    fun aggressiveSettings(): BotSettings = settings.copy(
        minStrategyScoreToBuy = 63,
        minTrendAgreement = 2,
        allowedQuoteAssetsCsv = "EUR",
        autoSymbolCandidateLimit = 350,
        autoSymbolActiveLimit = 30,
        maxNewTradesPerScan = 3,
        maxTradesPerHour = 6,
        maxSimultaneousLivePositions = 5,
        maxPositionEur = BigDecimal("10"),
        maxBuyPriceFilterEnabled = false,
        globalMaxBuyPriceEur = BigDecimal.ZERO,
        perSymbolMaxBuyPriceCsv = "",
        ultimateAutomationEnabled = true,
        perSymbolRulesEnabled = false,
        perSymbolRulesCsv = "",
        autoCompoundingHardCapEnabled = true,
        autoCompoundingMaxPositionEur = BigDecimal("50"),
        autoPauseAfterOrderFailuresEnabled = true,
        autoPauseFailureThreshold = 3,
        autoPauseMinutes = 60,
        minimumQuoteReserveAmount = BigDecimal("2"),
        minimumQuoteReservePercent = BigDecimal("3"),
        maxSpreadPercent = BigDecimal("0.75"),
        marketOrderSlippageWarningPercent = BigDecimal("0.35"),
        cooldownAfterBuyMinutes = 5,
        cooldownAfterSellMinutes = 10,
        cooldownAfterLossMinutes = 60,
        autoSymbolDiscoveryEnabled = true,
        autoTradeMultipleSymbolsPerScan = true,
        enableMarketOrders = true,
        fallbackToLimitWhenMarketBlocked = true,
        nonEurQuoteBuyEnabled = false,
        liquidityBlacklistEnabled = true,
        autoDisableBadSymbolsEnabled = false,
        trueSelfLearningEnabled = true,
        selfLearningMinSamples = 10,
        selfLearningPositionSizingEnabled = true,
        selfLearningAutoDisableEnabled = true
    )

    fun applyToFields(profile: BotSettings) {
        minAiScore = profile.minStrategyScoreToBuy.toString()
        timeframeAgreement = profile.minTrendAgreement.toString()
        allowedQuotes = profile.allowedQuoteAssetsCsv
        candidateLimit = profile.autoSymbolCandidateLimit.toString()
        activeLimit = profile.autoSymbolActiveLimit.toString()
        maxNewTrades = profile.maxNewTradesPerScan.toString()
        maxTradesHour = profile.maxTradesPerHour.toString()
        maxLivePositions = profile.maxSimultaneousLivePositions.toString()
        maxPosition = profile.maxPositionEur.toPlainString()
        maxBuyPriceEnabled = profile.maxBuyPriceFilterEnabled
        globalMaxBuyPrice = profile.globalMaxBuyPriceEur.toPlainString()
        perSymbolMaxBuyPrice = profile.perSymbolMaxBuyPriceCsv
        ultimateAutomation = profile.ultimateAutomationEnabled
        perSymbolRules = profile.perSymbolRulesEnabled
        perSymbolRulesText = profile.perSymbolRulesCsv
        compoundingHardCap = profile.autoCompoundingHardCapEnabled
        compoundingMaxPosition = profile.autoCompoundingMaxPositionEur.toPlainString()
        failureAutoPause = profile.autoPauseAfterOrderFailuresEnabled
        failureAutoPauseThreshold = profile.autoPauseFailureThreshold.toString()
        failureAutoPauseMinutes = profile.autoPauseMinutes.toString()
        volatilityCircuitBreaker = profile.volatilityCircuitBreakerEnabled
        volatilityMaxMove = profile.volatilityCircuitBreakerMax24hMovePercent.toPlainString()
        pumpChaseProtection = profile.pumpChaseProtectionEnabled
        pumpChaseMaxGain = profile.pumpChaseMax24hGainPercent.toPlainString()
        duplicatePositionProtection = profile.duplicatePositionProtectionEnabled
        adaptiveRealizedPnlCompounding = profile.adaptiveCompoundingFromRealizedPnlEnabled
        dynamicScan = profile.dynamicScanIntervalEnabled
        dynamicFastSeconds = profile.dynamicScanFastSeconds.toString()
        dynamicSlowSeconds = profile.dynamicScanSlowSeconds.toString()
        multiTimeframeConsensus = profile.multiTimeframeConsensusEnabled
        multiTimeframeRequired = profile.multiTimeframeRequiredBullishCount.toString()
        ultimateReadinessScore = profile.ultimateReadinessScoreEnabled
        orderBookDepthGuard = profile.orderBookDepthGuardEnabled
        maxOrderBookSlippage = profile.maxOrderBookSlippagePercent.toPlainString()
        minOrderBookDepthMultiple = profile.minOrderBookDepthMultiple.toPlainString()
        minReserveAmount = profile.minimumQuoteReserveAmount.toPlainString()
        minReservePercent = profile.minimumQuoteReservePercent.toPlainString()
        maxLimitSpread = profile.maxSpreadPercent.toPlainString()
        maxMarketSpread = profile.marketOrderSlippageWarningPercent.toPlainString()
        buyCooldown = profile.cooldownAfterBuyMinutes.toString()
        sellCooldown = profile.cooldownAfterSellMinutes.toString()
        lossCooldown = profile.cooldownAfterLossMinutes.toString()
        autoDiscovery = profile.autoSymbolDiscoveryEnabled
        multiSymbol = profile.autoTradeMultipleSymbolsPerScan
        marketOrders = profile.enableMarketOrders
        fallbackToLimit = profile.fallbackToLimitWhenMarketBlocked
        nonEurBuys = profile.nonEurQuoteBuyEnabled
        liquidityBlacklist = profile.liquidityBlacklistEnabled
        autoDisableBadSymbols = profile.autoDisableBadSymbolsEnabled
        trueSelfLearning = profile.trueSelfLearningEnabled
        learningMinSamples = profile.selfLearningMinSamples.toString()
        learningLookback = profile.selfLearningLookbackTrades.toString()
        learningMaxBoost = profile.selfLearningMaxScoreBoost.toString()
        learningMaxPenalty = profile.selfLearningMaxScorePenalty.toString()
        learningPositionSizing = profile.selfLearningPositionSizingEnabled
        learningAutoDisable = profile.selfLearningAutoDisableEnabled
    }

    fun editedSettings(): BotSettings = settings.copy(
        minStrategyScoreToBuy = minAiScore.toIntOrNull()?.coerceIn(1, 100) ?: settings.minStrategyScoreToBuy,
        minTrendAgreement = timeframeAgreement.toIntOrNull()?.coerceIn(1, 3) ?: settings.minTrendAgreement,
        allowedQuoteAssetsCsv = allowedQuotes.uppercase().replace(" ", ""),
        autoSymbolCandidateLimit = candidateLimit.toIntOrNull()?.coerceIn(1, 1000) ?: settings.autoSymbolCandidateLimit,
        autoSymbolActiveLimit = activeLimit.toIntOrNull()?.coerceIn(1, 100) ?: settings.autoSymbolActiveLimit,
        maxNewTradesPerScan = maxNewTrades.toIntOrNull()?.coerceIn(1, 20) ?: settings.maxNewTradesPerScan,
        maxTradesPerDay = maxTradesDay.toIntOrNull()?.coerceAtLeast(0) ?: settings.maxTradesPerDay,
        maxTradesPerHour = maxTradesHour.toIntOrNull()?.coerceIn(1, 100) ?: settings.maxTradesPerHour,
        maxSimultaneousLivePositions = maxLivePositions.toIntOrNull()?.coerceIn(1, 50) ?: settings.maxSimultaneousLivePositions,
        maxPositionEur = maxPosition.toBigDecimalOrNull() ?: settings.maxPositionEur,
        maxBuyPriceFilterEnabled = maxBuyPriceEnabled,
        globalMaxBuyPriceEur = globalMaxBuyPrice.toBigDecimalOrNull() ?: settings.globalMaxBuyPriceEur,
        perSymbolMaxBuyPriceCsv = perSymbolMaxBuyPrice.uppercase().replace(" ", ""),
        ultimateAutomationEnabled = ultimateAutomation,
        perSymbolRulesEnabled = perSymbolRules,
        perSymbolRulesCsv = perSymbolRulesText.uppercase().replace(" ", ""),
        autoCompoundingHardCapEnabled = compoundingHardCap,
        autoCompoundingMaxPositionEur = compoundingMaxPosition.toBigDecimalOrNull() ?: settings.autoCompoundingMaxPositionEur,
        autoPauseAfterOrderFailuresEnabled = failureAutoPause,
        autoPauseFailureThreshold = failureAutoPauseThreshold.toIntOrNull()?.coerceIn(1, 20) ?: settings.autoPauseFailureThreshold,
        autoPauseMinutes = failureAutoPauseMinutes.toIntOrNull()?.coerceIn(1, 1440) ?: settings.autoPauseMinutes,
        volatilityCircuitBreakerEnabled = volatilityCircuitBreaker,
        volatilityCircuitBreakerMax24hMovePercent = volatilityMaxMove.toBigDecimalOrNull() ?: settings.volatilityCircuitBreakerMax24hMovePercent,
        pumpChaseProtectionEnabled = pumpChaseProtection,
        pumpChaseMax24hGainPercent = pumpChaseMaxGain.toBigDecimalOrNull() ?: settings.pumpChaseMax24hGainPercent,
        duplicatePositionProtectionEnabled = duplicatePositionProtection,
        adaptiveCompoundingFromRealizedPnlEnabled = adaptiveRealizedPnlCompounding,
        dynamicScanIntervalEnabled = dynamicScan,
        dynamicScanFastSeconds = dynamicFastSeconds.toLongOrNull()?.coerceIn(15L, 3600L) ?: settings.dynamicScanFastSeconds,
        dynamicScanSlowSeconds = dynamicSlowSeconds.toLongOrNull()?.coerceIn(15L, 7200L) ?: settings.dynamicScanSlowSeconds,
        multiTimeframeConsensusEnabled = multiTimeframeConsensus,
        multiTimeframeRequiredBullishCount = multiTimeframeRequired.toIntOrNull()?.coerceIn(1, 3) ?: settings.multiTimeframeRequiredBullishCount,
        ultimateReadinessScoreEnabled = ultimateReadinessScore,
        orderBookDepthGuardEnabled = orderBookDepthGuard,
        maxOrderBookSlippagePercent = maxOrderBookSlippage.toBigDecimalOrNull() ?: settings.maxOrderBookSlippagePercent,
        minOrderBookDepthMultiple = minOrderBookDepthMultiple.toBigDecimalOrNull() ?: settings.minOrderBookDepthMultiple,
        minimumQuoteReserveAmount = minReserveAmount.toBigDecimalOrNull() ?: settings.minimumQuoteReserveAmount,
        minimumQuoteReservePercent = minReservePercent.toBigDecimalOrNull() ?: settings.minimumQuoteReservePercent,
        maxSpreadPercent = maxLimitSpread.toBigDecimalOrNull() ?: settings.maxSpreadPercent,
        marketOrderSlippageWarningPercent = maxMarketSpread.toBigDecimalOrNull() ?: settings.marketOrderSlippageWarningPercent,
        cooldownAfterBuyMinutes = buyCooldown.toIntOrNull()?.coerceAtLeast(0) ?: settings.cooldownAfterBuyMinutes,
        cooldownAfterSellMinutes = sellCooldown.toIntOrNull()?.coerceAtLeast(0) ?: settings.cooldownAfterSellMinutes,
        cooldownAfterLossMinutes = lossCooldown.toIntOrNull()?.coerceAtLeast(0) ?: settings.cooldownAfterLossMinutes,
        autoSymbolDiscoveryEnabled = autoDiscovery,
        autoTradeMultipleSymbolsPerScan = multiSymbol,
        enableMarketOrders = marketOrders,
        fallbackToLimitWhenMarketBlocked = fallbackToLimit,
        nonEurQuoteBuyEnabled = nonEurBuys,
        liquidityBlacklistEnabled = liquidityBlacklist,
        autoDisableBadSymbolsEnabled = autoDisableBadSymbols,
        trueSelfLearningEnabled = trueSelfLearning,
        selfLearningMinSamples = learningMinSamples.toIntOrNull()?.coerceIn(3, 200) ?: settings.selfLearningMinSamples,
        selfLearningLookbackTrades = learningLookback.toIntOrNull()?.coerceIn(20, 5000) ?: settings.selfLearningLookbackTrades,
        selfLearningMaxScoreBoost = learningMaxBoost.toIntOrNull()?.coerceIn(0, 30) ?: settings.selfLearningMaxScoreBoost,
        selfLearningMaxScorePenalty = learningMaxPenalty.toIntOrNull()?.coerceIn(0, 40) ?: settings.selfLearningMaxScorePenalty,
        selfLearningPositionSizingEnabled = learningPositionSizing,
        selfLearningAutoDisableEnabled = learningAutoDisable
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Advanced Editable Settings", "These are the controls that were previously hidden inside the bot logic. Save after changing values.") }
        item {
            GlassCard {
                SectionTitle("Quick Profiles", "These apply and save immediately. You do not need to press Save after using a profile.")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Button(onClick = {
                        val profile = smallBalanceSettings()
                        applyToFields(profile)
                        onApply(profile)
                    }) { Text("Apply Small Balance") } }
                    item { OutlinedButton(onClick = {
                        val profile = balancedSettings()
                        applyToFields(profile)
                        onApply(profile)
                    }) { Text("Apply Balanced") } }
                    item { OutlinedButton(onClick = {
                        val profile = aggressiveSettings()
                        applyToFields(profile)
                        onApply(profile)
                    }) { Text("Apply Aggressive") } }
                }
            }
        }
        item {
            GlassCard {
                SectionTitle("Ultimate Automation", "Clean automation layer: per-symbol rules, adaptive position caps, and automatic pause after repeated order failures.")
                ToggleRow("Enable Ultimate Automation layer", ultimateAutomation) { ultimateAutomation = it }
                ToggleRow("Enable per-symbol automation rules", perSymbolRules) { perSymbolRules = it }
                OutlinedTextField(
                    value = perSymbolRulesText,
                    onValueChange = { perSymbolRulesText = it },
                    label = { Text("Rules: SYMBOL=maxPosition|minScore|maxBuyPrice|cooldownMinutes") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Text("Example: BTCEUR=20|78|95000|30;ETHEUR=10|74|3500|20. Empty fields can be skipped with 0.", color = Muted)
                ToggleRow("Auto-compounding hard cap", compoundingHardCap) { compoundingHardCap = it }
                OutlinedTextField(value = compoundingMaxPosition, onValueChange = { compoundingMaxPosition = it }, label = { Text("Max adaptive position cap EUR") }, modifier = Modifier.fillMaxWidth())
                ToggleRow("Auto-pause LIVE_AUTO after repeated order/API failures", failureAutoPause) { failureAutoPause = it }
                OutlinedTextField(value = failureAutoPauseThreshold, onValueChange = { failureAutoPauseThreshold = it }, label = { Text("Failure threshold before auto-pause") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = failureAutoPauseMinutes, onValueChange = { failureAutoPauseMinutes = it }, label = { Text("Pause minutes note") }, modifier = Modifier.fillMaxWidth())
                Text("Auto-pause changes LIVE_AUTO to LIVE_CONFIRM/manual signal mode so the bot stops submitting real orders until you review status.", color = Amber)
                Spacer(Modifier.height(12.dp))
                SectionTitle("Live Guard Automation", "Blocks bad automatic BUY entries during extreme moves, pumps, or unstable scanning conditions.")
                ToggleRow("Volatility circuit breaker", volatilityCircuitBreaker) { volatilityCircuitBreaker = it }
                OutlinedTextField(value = volatilityMaxMove, onValueChange = { volatilityMaxMove = it }, label = { Text("Max absolute 24h move % before BUY block") }, modifier = Modifier.fillMaxWidth())
                ToggleRow("Pump-chase protection", pumpChaseProtection) { pumpChaseProtection = it }
                OutlinedTextField(value = pumpChaseMaxGain, onValueChange = { pumpChaseMaxGain = it }, label = { Text("Max 24h gain % before BUY block") }, modifier = Modifier.fillMaxWidth())
                ToggleRow("Duplicate-position protection", duplicatePositionProtection) { duplicatePositionProtection = it }
                Text("This blocks extra BUY entries when the app already has an open lifecycle position or existing base holding for the same symbol. It does not block SELL/exit management.", color = Muted)
                ToggleRow("Adaptive compounding from realized P/L", adaptiveRealizedPnlCompounding) { adaptiveRealizedPnlCompounding = it }
                ToggleRow("Multi-timeframe consensus before BUY", multiTimeframeConsensus) { multiTimeframeConsensus = it }
                OutlinedTextField(value = multiTimeframeRequired, onValueChange = { multiTimeframeRequired = it }, label = { Text("Required bullish frames, 1-3") }, modifier = Modifier.fillMaxWidth())
                ToggleRow("Ultimate readiness score in System Test", ultimateReadinessScore) { ultimateReadinessScore = it }
                ToggleRow("Order book depth / slippage guard", orderBookDepthGuard) { orderBookDepthGuard = it }
                OutlinedTextField(value = maxOrderBookSlippage, onValueChange = { maxOrderBookSlippage = it }, label = { Text("Max estimated order book slippage %") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = minOrderBookDepthMultiple, onValueChange = { minOrderBookDepthMultiple = it }, label = { Text("Minimum order book depth multiple") }, modifier = Modifier.fillMaxWidth())
                Text("Uses live Kraken Depth data in LIVE_AUTO to avoid thin books and bad execution. This blocks BUY when execution quality is poor.", color = Muted)
                ToggleRow("Dynamic scan interval", dynamicScan) { dynamicScan = it }
                OutlinedTextField(value = dynamicFastSeconds, onValueChange = { dynamicFastSeconds = it }, label = { Text("Fast scan seconds") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = dynamicSlowSeconds, onValueChange = { dynamicSlowSeconds = it }, label = { Text("Slow scan seconds") }, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            GlassCard {
                SectionTitle("Signal Confidence", "Lower score = more trades. Higher score = fewer but stricter trades.")
                OutlinedTextField(value = minAiScore, onValueChange = { minAiScore = it }, label = { Text("Minimum AI / strategy score") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = timeframeAgreement, onValueChange = { timeframeAgreement = it }, label = { Text("Required timeframe agreement, 1-3") }, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            GlassCard {
                SectionTitle("Auto Symbol Discovery", "Controls how many Kraken pairs are discovered, ranked and used in rotation.")
                ToggleRow("Auto Symbol Discovery", autoDiscovery) { autoDiscovery = it }
                ToggleRow("Trade multiple symbols per scan", multiSymbol) { multiSymbol = it }
                OutlinedTextField(value = allowedQuotes, onValueChange = { allowedQuotes = it }, label = { Text("Allowed quote assets, e.g. EUR or EUR,USD,USDT") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = candidateLimit, onValueChange = { candidateLimit = it }, label = { Text("Candidate scan limit") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = activeLimit, onValueChange = { activeLimit = it }, label = { Text("Active rotation size") }, modifier = Modifier.fillMaxWidth())
                ToggleRow("Allow non-EUR quote buys", nonEurBuys) { nonEurBuys = it }
            }
        }
        item {
            GlassCard {
                SectionTitle("Position, Reserve and Trade Limits", "Prevents the bot from spending too much or opening too many trades.")
                OutlinedTextField(value = maxPosition, onValueChange = { maxPosition = it }, label = { Text("Max position size / max spend per buy") }, modifier = Modifier.fillMaxWidth())
                ToggleRow("Enable Max Buy Price filter", maxBuyPriceEnabled) { maxBuyPriceEnabled = it }
                OutlinedTextField(value = globalMaxBuyPrice, onValueChange = { globalMaxBuyPrice = it }, label = { Text("Global max buy price, 0 = disabled") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = perSymbolMaxBuyPrice, onValueChange = { perSymbolMaxBuyPrice = it }, label = { Text("Per-symbol max buy prices, e.g. BTCEUR=95000,ETHEUR=3500") }, modifier = Modifier.fillMaxWidth())
                Text("If enabled, BUY orders are blocked when the current ask is above the configured max buy price. SELL orders are not blocked.", color = Muted)
                OutlinedTextField(value = maxNewTrades, onValueChange = { maxNewTrades = it }, label = { Text("Max new trades per scan") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = maxTradesDay, onValueChange = { maxTradesDay = it }, label = { Text("Max trades/day (0=∞)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = maxTradesHour, onValueChange = { maxTradesHour = it }, label = { Text("Max trades per hour") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = maxLivePositions, onValueChange = { maxLivePositions = it }, label = { Text("Max simultaneous live positions") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = minReserveAmount, onValueChange = { minReserveAmount = it }, label = { Text("Minimum quote reserve amount") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = minReservePercent, onValueChange = { minReservePercent = it }, label = { Text("Minimum quote reserve percent") }, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            GlassCard {
                SectionTitle("Spread, Market Orders and Cooldowns", "Controls execution strictness.")
                ToggleRow("Allow market orders", marketOrders) { marketOrders = it }
                ToggleRow("Fallback to limit when market is unsafe", fallbackToLimit) { fallbackToLimit = it }
                ToggleRow("Liquidity blacklist", liquidityBlacklist) { liquidityBlacklist = it }
                ToggleRow("Auto-disable bad symbols", autoDisableBadSymbols) { autoDisableBadSymbols = it }
                OutlinedTextField(value = maxMarketSpread, onValueChange = { maxMarketSpread = it }, label = { Text("Max market spread %") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = maxLimitSpread, onValueChange = { maxLimitSpread = it }, label = { Text("Max limit spread %") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = buyCooldown, onValueChange = { buyCooldown = it }, label = { Text("Buy cooldown minutes") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = sellCooldown, onValueChange = { sellCooldown = it }, label = { Text("Sell cooldown minutes") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = lossCooldown, onValueChange = { lossCooldown = it }, label = { Text("Loss cooldown minutes") }, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            GlassCard {
                SectionTitle("True Self-Learning", "Persistent learning from completed PAPER and LIVE trades. Bounded score/sizing changes only.")
                ToggleRow("Enable true self-learning", trueSelfLearning) { trueSelfLearning = it }
                ToggleRow("Use learned position sizing", learningPositionSizing) { learningPositionSizing = it }
                ToggleRow("Allow learned auto-disable", learningAutoDisable) { learningAutoDisable = it }
                OutlinedTextField(value = learningMinSamples, onValueChange = { learningMinSamples = it }, label = { Text("Minimum samples per symbol") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = learningLookback, onValueChange = { learningLookback = it }, label = { Text("Learning lookback trades") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = learningMaxBoost, onValueChange = { learningMaxBoost = it }, label = { Text("Maximum score boost") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = learningMaxPenalty, onValueChange = { learningMaxPenalty = it }, label = { Text("Maximum score penalty") }, modifier = Modifier.fillMaxWidth())
            }
        }
        item {
            Button(onClick = { onApply(editedSettings()) }, modifier = Modifier.fillMaxWidth()) {
                Text("Save Advanced Settings Now")
            }
        }
        item { WarningCard("Use EUR-only and small position sizes until the Live Status tab confirms that the bot has free quote balance and is submitting orders as expected.") }
    }
}

@Composable
private fun SettingsScreen(
    apiKey: String,
    secretKey: String,
    newsKey: String,
    onApiKey: (String) -> Unit,
    onSecretKey: (String) -> Unit,
    onNewsKey: (String) -> Unit,
    settings: BotSettings,
    onExchangeProvider: (ExchangeProvider) -> Unit,
    onModeChange: (BotMode) -> Unit,
    onLiveAckChange: (Boolean) -> Unit,
    onManualMode: (Boolean) -> Unit,
    onMarketOrders: (Boolean) -> Unit,
    onNewsAi: (Boolean) -> Unit,
    onMemoryAi: (Boolean) -> Unit,
    onSaveKeys: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Settings", "Kraken live trading, paper trading, secure keys and risk controls.") }
        item {
            GlassCard {
                SectionTitle("Working Provider", "Kraken is the live trading connector. Paper and Manual modes are also fully usable.")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val liveProviders = listOf(ExchangeProvider.PAPER, ExchangeProvider.KRAKEN, ExchangeProvider.BITVAVO, ExchangeProvider.COINBASE_ADVANCED, ExchangeProvider.MANUAL, ExchangeProvider.BINANCE_READ_ONLY)
                    items(liveProviders) { provider ->
                        FilterChip(
                            selected = settings.exchangeProvider == provider,
                            onClick = { onExchangeProvider(provider) },
                            label = { Text(provider.name.replace('_', ' ')) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                SectionTitle("Trading Mode", "Choose how the bot is allowed to trade.")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(BotMode.values().toList()) { mode ->
                        FilterChip(
                            selected = settings.mode == mode,
                            onClick = { onModeChange(mode) },
                            label = { Text(mode.name.replace('_', ' ')) }
                        )
                    }
                }
                Text("PAPER uses live market data with fake local orders. LIVE_CONFIRM scans only. LIVE_AUTO allows guarded automatic live execution.", color = Muted)
                Spacer(Modifier.height(8.dp))
                ToggleRow("Live acknowledgement: I understand live trading can lose real money", settings.liveTradingAcknowledged, onLiveAckChange)
                ToggleRow("Manual execution mode / signal-only", settings.manualExecutionMode, onManualMode)
                ToggleRow("Allow Kraken market orders", settings.enableMarketOrders, onMarketOrders)
                Text("Market orders execute immediately and can slip. The bot blocks them when spread exceeds ${settings.marketOrderSlippageWarningPercent}% and caps size at €${settings.maxMarketOrderEur}.", color = Amber)
                Text(exchangeProviderWarning(settings.exchangeProvider), color = Amber)
            }
        }
        item {
            GlassCard {
                SectionTitle("Secure API Credentials", "Stored locally through Android Keystore-backed encrypted storage.")
                OutlinedTextField(value = apiKey, onValueChange = onApiKey, label = { Text("${settings.exchangeProvider.name.replace('_', ' ')} API key") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(value = secretKey, onValueChange = onSecretKey, label = { Text("${settings.exchangeProvider.name.replace('_', ' ')} secret key") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                OutlinedTextField(value = newsKey, onValueChange = onNewsKey, label = { Text("NewsAPI key(s), comma-separated") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                Button(onClick = onSaveKeys, modifier = Modifier.fillMaxWidth()) { Text("Save Secure Keys") }
            }
        }
        item {
            GlassCard {
                ToggleRow("News AI", settings.useNewsAi, onNewsAi)
                ToggleRow("Previous-trade memory AI", settings.useTradeMemoryAi, onMemoryAi)
            }
        }
        item {
            GlassCard {
                SectionTitle("Live Automation", "Only working live/paper features are exposed in this v1.5.0 live-audit build.")
                ToggleInfo("Live trade lifecycle manager", settings.liveLifecycleManagerEnabled)
                ToggleInfo("Auto exit manager", settings.autoExitManagerEnabled)
                ToggleInfo("Automatic take-profit", settings.autoTakeProfitEnabled)
                ToggleInfo("Automatic stop-loss", settings.autoStopLossEnabled)
                ToggleInfo("Profit maximizer / trailing exits", settings.profitMaximizerEnabled)
                ToggleInfo("Sell on bearish AI signal", settings.forceSellOnBearishSignal)
                ToggleInfo("Closed-order sync", settings.syncKrakenHistory)
                Text("No bot can guarantee maximum possible profit. This build attempts to maximize captured profit with smart profit-locking, multi-stage exits, fee-aware filtering, portfolio balancing, watchdog checks and strategy optimization.", color = Amber)
            }
        }
        item { WarningCard("Do not use VPNs, false residency, borrowed accounts or other bypass methods. Use a provider that legally supports your Belgian account, or keep the app in manual/paper mode.") }
    }
}

private fun exchangeProviderWarning(provider: ExchangeProvider): String = when (provider) {
    ExchangeProvider.PAPER -> "Paper trading only. No real orders are sent."
    ExchangeProvider.BINANCE_READ_ONLY -> "Belgium mode: Binance trading remains disabled. Signals and read-only market data only."
    ExchangeProvider.KRAKEN -> "Verify Kraken API spot trading is available for your Belgian account before enabling LIVE_AUTO."
    ExchangeProvider.COINBASE_ADVANCED -> "Coinbase Advanced live connector is implemented with JWT signing. Use only if your account/API key is enabled and your private key is in a compatible PKCS8 PEM format."
    ExchangeProvider.BITVAVO -> "Bitvavo REST live connector is implemented. Use only if your account/API permissions allow trading."
    ExchangeProvider.MANUAL -> "Manual mode creates trade plans. You place the order yourself in a compliant app."
}

@Composable
private fun HeroCard(
    title: String,
    subtitle: String,
    primaryButton: String,
    secondaryButton: String,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, Color(0x664DA3FF))
    ) {
        Box(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(Color(0xFF14345F), Color(0xFF111827), Color(0xFF0A101D))))
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text(subtitle, color = Muted)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { Button(onClick = onPrimary, colors = ButtonDefaults.buttonColors(containerColor = Electric)) { Text(primaryButton) } }
                    item { OutlinedButton(onClick = onSecondary) { Text(secondaryButton) } }
                }
            }
        }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Panel.copy(alpha = 0.92f)),
        border = BorderStroke(1.dp, Stroke)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun MetricCard(title: String, value: String, caption: String, accent: Color) {
    Card(
        modifier = Modifier.width(178.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = PanelAlt),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot(accent)
            Text(title, color = Muted, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(caption, color = Muted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DecisionCard(decision: AiDecision, expanded: Boolean = false) {
    val accent = actionColor(decision.finalAction)
    GlassCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(accent)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(decision.symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text(decision.finalAction.name.replace('_', ' '), color = accent, fontWeight = FontWeight.Bold)
            }
            StatusPill("${decision.confidencePercent}%", accent)
        }
        LinearProgressIndicator(progress = decision.finalScore.coerceIn(0, 100) / 100f, modifier = Modifier.fillMaxWidth().height(8.dp), color = accent, trackColor = Stroke)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniScore("Tech", decision.technicalScore)
            MiniScore("News", decision.newsScore)
            MiniScore("Memory", decision.memoryScore)
        }
        if (expanded) {
            Divider(color = Stroke)
            val newsMarker = " News titles: "
            val parts = decision.explanation.split(newsMarker, limit = 2)
            Text(parts.first(), color = Muted)
            if (parts.size > 1) {
                Spacer(Modifier.height(6.dp))
                Text("News titles", fontWeight = FontWeight.Bold, color = Amber)
                parts[1].split(" | ").take(3).forEach { title ->
                    Text("• $title", color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("Allowed to trade: ${if (decision.allowedToTrade) "Yes" else "No"}", color = if (decision.allowedToTrade) Mint else Danger)
        }
    }
}

@Composable
private fun MiniScore(label: String, score: Int) {
    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF0B1220), border = BorderStroke(1.dp, Stroke)) {
        Text("$label $score", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Muted)
    }
}

@Composable
private fun AllocationRow(symbol: String, value: String, progress: Float) {
    GlassCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(symbol, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(value, color = Mint, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().height(8.dp), color = Mint, trackColor = Stroke)
    }
}

private fun settingsTrailingArmedPlaceholder(position: com.ksp.cryptobot.core.PositionInfo): Boolean {
    return position.trailingStopPrice > BigDecimal.ZERO && position.highestPrice > position.entryPrice
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, color = Muted)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ToggleInfo(label: String, enabled: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (enabled) Mint else Muted)
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f))
        StatusPill(if (enabled) "ON" else "OFF", if (enabled) Mint else Muted)
    }
}

@Composable
private fun TaxRow(label: String, enabled: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        StatusPill(if (enabled) "Enabled" else "Off", if (enabled) Mint else Danger)
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF241B12)),
        border = BorderStroke(1.dp, Amber.copy(alpha = 0.55f))
    ) {
        Text(text, modifier = Modifier.padding(14.dp), color = Color(0xFFFFE2A5))
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.15f), border = BorderStroke(1.dp, color.copy(alpha = 0.55f))) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = color, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(modifier = Modifier.size(11.dp).clip(CircleShape).background(color))
}

private fun levelColor(level: String): Color = when (level.uppercase()) {
    "ERROR" -> Danger
    "WARN" -> Amber
    "LIVE" -> Mint
    else -> Electric
}

private fun modeColor(mode: BotMode): Color = when (mode) {
    BotMode.PAPER -> Electric
    BotMode.LIVE_CONFIRM -> Amber
    BotMode.LIVE_AUTO -> Danger
}

private fun actionColor(action: SignalAction): Color = when (action) {
    SignalAction.STRONG_AVOID, SignalAction.AVOID, SignalAction.SELL -> Danger
    SignalAction.WAIT, SignalAction.WATCH -> Amber
    SignalAction.SMALL_BUY, SignalAction.BUY -> Mint
}


@Composable
private fun PerformanceLabScreen(
    snapshot: PerformanceLabSnapshot?,
    settings: BotSettings,
    activePositionSymbols: List<String>,
    onRefresh: () -> Unit
) {
    val activeSet = activePositionSymbols.map { it.uppercase().replace("/", "").replace("-", "") }.toSet()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            GlassCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Performance Lab", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "Paper vs live comparison with automatic strategy promotion gates for configured symbols plus active positions.",
                        color = Muted
                    )
                    Text("Promotion universe: ${(settings.symbols() + activePositionSymbols).map { it.uppercase().replace("/", "").replace("-", "") }.distinct().joinToString(", ").ifBlank { "none" }}", color = Muted)
                    if (activePositionSymbols.isNotEmpty()) {
                        Text("Active positions included: ${activePositionSymbols.joinToString(", ")}", color = Amber)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill("Approved ${snapshot?.approvedCount ?: 0}", Mint)
                        StatusPill("Watch ${snapshot?.watchCount ?: 0}", Amber)
                        StatusPill("Blocked ${snapshot?.blockedCount ?: 0}", Danger)
                    }
                    Spacer(Modifier.height(12.dp))
                    ElevatedButton(onClick = onRefresh) {
                        Text("Refresh Performance Lab")
                    }
                }
            }
        }

        item {
            GlassCard {
                Column {
                    Text("Promotion Rules", fontWeight = FontWeight.Bold)
                    Text("Approved strategies can be trusted for small live size first.", color = Muted)
                    Text("Watch strategies remain paper-first or reduced size.", color = Muted)
                    Text("Blocked strategies should not be live-promoted yet.", color = Muted)
                    Text("Current max live size setting: €${settings.maxPositionEur}", color = Mint, fontWeight = FontWeight.Bold)
                }
            }
        }

        val candidates = snapshot?.candidates.orEmpty()
            .sortedWith(compareByDescending<StrategyPromotionCandidate> { activeSet.contains(it.symbol.uppercase().replace("/", "").replace("-", "")) }
                .thenByDescending { it.status == PromotionStatus.APPROVED }
                .thenByDescending { it.performanceScore })
        if (candidates.isEmpty()) {
            item {
                GlassCard {
                    Text("No performance snapshot loaded yet. Press Refresh Performance Lab.", color = Muted)
                }
            }
        } else {
            items(candidates) { candidate ->
                val normalized = candidate.symbol.uppercase().replace("/", "").replace("-", "")
                Column {
                    if (activeSet.contains(normalized)) {
                        Text("ACTIVE POSITION", color = Amber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    }
                    PerformanceCandidateCard(candidate)
                }
            }
        }
    }
}

@Composable
private fun PerformanceCandidateCard(candidate: StrategyPromotionCandidate) {
    val color = when (candidate.status) {
        PromotionStatus.APPROVED -> Mint
        PromotionStatus.WATCH -> Amber
        PromotionStatus.BLOCKED -> Danger
    }

    GlassCard {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${candidate.symbol} — ${candidate.strategy.name}", fontWeight = FontWeight.ExtraBold)
                    Text(candidate.reason, color = Muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                StatusPill(candidate.status.name, color)
            }

            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = candidate.performanceScore / 100f,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Text("Performance score: ${candidate.performanceScore}/100", color = color, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricBox("Paper trades", candidate.paperTrades.toString(), Modifier.weight(1f))
                MetricBox("Paper win", "${candidate.paperWinRatePercent}%", Modifier.weight(1f))
                MetricBox("Paper PF", candidate.paperProfitFactor.toPlainString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MetricBox("Live trades", candidate.liveTrades.toString(), Modifier.weight(1f))
                MetricBox("Live win", if (candidate.liveTrades == 0) "N/A" else "${candidate.liveWinRatePercent}%", Modifier.weight(1f))
                MetricBox("Live PF", if (candidate.liveTrades == 0) "N/A" else candidate.liveProfitFactor.toPlainString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Recommended position: €${candidate.recommendedPositionEur} | Paper DD: ${candidate.paperMaxDrawdownPercent}% | Live DD: ${candidate.liveMaxDrawdownPercent}%",
                color = Muted
            )
        }
    }
}

@Composable
private fun MetricBox(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = PanelAlt),
        border = BorderStroke(1.dp, Stroke),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
            Text(value, color = TextPrimary, fontWeight = FontWeight.Bold)
        }
    }
}

private fun sampleDecisions(): List<AiDecision> = listOf(
    AiDecision(
        symbol = "BTCEUR",
        finalAction = SignalAction.WATCH,
        finalScore = 64,
        confidencePercent = 68,
        technicalScore = 61,
        newsScore = 7,
        memoryScore = -4,
        allowedToTrade = false,
        explanation = "Trend is constructive, but the guard prefers waiting until spread and volatility improve."
    ),
    AiDecision(
        symbol = "ETHEUR",
        finalAction = SignalAction.SMALL_BUY,
        finalScore = 73,
        confidencePercent = 74,
        technicalScore = 66,
        newsScore = 5,
        memoryScore = 2,
        allowedToTrade = true,
        explanation = "Signal quality is acceptable for a small position under the current risk cap."
    )
)
