package com.ksp.cryptobot

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.ksp.cryptobot.core.AiDecision
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
import com.ksp.cryptobot.pro.ProAutomationSuite
import com.ksp.cryptobot.service.BotForegroundService
import com.ksp.cryptobot.settings.AppSettingsStore
import com.ksp.cryptobot.status.BotStatusStore
import com.ksp.cryptobot.learning.TrueSelfLearningEngine
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.math.BigDecimal

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
                                action = BotForegroundService.ACTION_START
                            })
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
    AI("AI Signals"),
    STRATEGY("Strategy Lab"),
    BACKTEST("Backtest Lab"),
    REGIME("Regime"),
    ORDERS("Orders"),
    POSITIONS("Positions"),
    AUTONOMOUS("Autonomous"),
    SELF_LEARNING("Self Learning"),
    PRO("Pro Systems"),
    PORTFOLIO("Portfolio"),
    NEWS("News Intel"),
    TAX("Belgium Tax"),
    RISK("Risk Center"),
    HISTORY("History"),
    SETTINGS("Settings"),
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
    onLoadSelfLearning: (BotSettings, (TrueSelfLearningEngine.LearningSummary) -> Unit) -> Unit
) {
    var settings by remember { mutableStateOf(store.load()) }
    var currentTab by remember { mutableStateOf(AppTab.DASHBOARD) }
    var status by remember { mutableStateOf(statusStore.latestText()) }
    var statusLevel by remember { mutableStateOf(statusStore.latestLevel()) }
    var statusHistory by remember { mutableStateOf(statusStore.recentLines()) }
    var decisions by remember { mutableStateOf(sampleDecisions()) }
    var portfolioSnapshot by remember { mutableStateOf<PortfolioSnapshot?>(null) }
    var liveOrders by remember { mutableStateOf<List<LiveOrderInfo>>(emptyList()) }
    var lifecycleSnapshot by remember { mutableStateOf<LifecycleSnapshot?>(null) }
    var symbolCandidates by remember { mutableStateOf<List<SymbolDiscoveryCandidate>>(emptyList()) }
    var taxExportSummary by remember { mutableStateOf<TaxExportSummary?>(null) }
    var remoteCommand by remember { mutableStateOf("/status") }
    var remoteResult by remember { mutableStateOf<RemoteCommandResult?>(null) }
    var selfLearningSummary by remember { mutableStateOf<TrueSelfLearningEngine.LearningSummary?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            status = statusStore.latestText()
            statusLevel = statusStore.latestLevel()
            statusHistory = statusStore.recentLines()
            delay(1000L)
        }
    }

    LaunchedEffect(currentTab, settings.exchangeProvider) {
        if (currentTab == AppTab.PORTFOLIO) {
            onLoadPortfolio(settings) { result ->
                portfolioSnapshot = result
                statusStore.write("Portfolio auto-refresh complete. Total≈€${result.totalValueEur}")
                status = "Portfolio loaded: €${result.totalValueEur}"
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
                    onStart = { statusStore.write("Start button pressed from dashboard."); onStart(); status = "Foreground live scanner started" },
                    onStop = { statusStore.write("Stop button pressed from dashboard.", "WARN"); onStop(); status = "Bot stopped" },
                    onScan = {
                        status = "Scanning market + AI inputs..."
                        onScan(settings, false) { result ->
                            decisions = result.ifEmpty { sampleDecisions() }
                            statusStore.write("Manual scan complete from dashboard. Decisions=${result.size}")
                            status = "Scan complete"
                        }
                    },
                    onExecute = {
                        status = "Running guarded execution pass..."
                        onScan(settings, settings.mode == BotMode.PAPER || settings.mode == BotMode.LIVE_AUTO) { result ->
                            decisions = result.ifEmpty { sampleDecisions() }
                            statusStore.write("Manual execution pass complete from dashboard. Decisions=${result.size}")
                            status = "Execution pass complete"
                        }
                    }
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
                AppTab.AI -> AiSignalsScreen(decisions = decisions, settings = settings)
                AppTab.STRATEGY -> StrategyScreen(settings = settings, onToggleStrategy = { persistSettings(settings.copy(recoveredScalpingStrategyEnabled = it)) })
                AppTab.BACKTEST -> BacktestLabScreen(settings = settings)
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
                AppTab.SELF_LEARNING -> SelfLearningScreen(
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
                AppTab.PRO -> ProSystemsScreen(settings = settings)
                AppTab.PORTFOLIO -> PortfolioScreen(
                    settings = settings,
                    snapshot = portfolioSnapshot,
                    onRefresh = {
                        statusStore.write("Portfolio refresh requested from UI.")
                        onLoadPortfolio(settings) { result ->
                            portfolioSnapshot = result
                            statusStore.write("Portfolio refresh complete. Total≈€${result.totalValueEur}")
                            status = "Portfolio loaded: €${result.totalValueEur}"
                        }
                    }
                )
                AppTab.NEWS -> NewsScreen(settings = settings, onToggleNews = { persistSettings(settings.copy(useNewsAi = it)) })
                AppTab.TAX -> TaxScreen()
                AppTab.HISTORY -> HistoryScreen(settings = settings)
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
                AppTab.SETTINGS -> SettingsScreen(
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
                Text("v1.8.3 CTS", color = Mint, fontWeight = FontWeight.Bold)
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
            AppTab.STATUS,
            AppTab.BOT,
            AppTab.SYMBOLS,
            AppTab.AI,
            AppTab.SELF_LEARNING,
            AppTab.STRATEGY,
            AppTab.AUTONOMOUS,
            AppTab.PRO,
            AppTab.ORDERS,
            AppTab.POSITIONS,
            AppTab.PORTFOLIO,
            AppTab.TAX,
            AppTab.RISK,
            AppTab.BACKTEST,
            AppTab.REGIME,
            AppTab.NEWS,
            AppTab.HISTORY,
            AppTab.ADVANCED_SETTINGS,
            AppTab.SETTINGS
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
    onStart: () -> Unit,
    onStop: () -> Unit,
    onScan: () -> Unit,
    onExecute: () -> Unit
) {
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
                secondaryButton = "Execute Pass",
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
            }
        }
        item {
            GlassCard {
                SectionTitle("Quick Controls", "Use these when you want to actively supervise the bot.")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { ElevatedButton(onClick = onStart, colors = ButtonDefaults.elevatedButtonColors(containerColor = Mint, contentColor = Color(0xFF06130F))) { Text("Start") } }
                    item { OutlinedButton(onClick = onStop) { Text("Stop") } }
                    item { Button(onClick = onScan) { Text("Scan") } }
                }
            }
        }
        item { SectionTitle("Top AI Decisions", status) }
        items(decisions.take(4)) { DecisionCard(it) }
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
                    item { OutlinedButton(onClick = onStart) { Text("Start Service") } }
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
private fun AiSignalsScreen(decisions: List<AiDecision>, settings: BotSettings) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("AI Signals", "Recovered scalping strategy + news sentiment + previous-trade memory.") }
        item {
            GlassCard {
                ToggleInfo("Recovered scalping strategy", settings.recoveredScalpingStrategyEnabled)
                ToggleInfo("News sentiment", settings.useNewsAi)
                ToggleInfo("Trade memory learning", settings.useTradeMemoryAi)
                ToggleInfo("Tax-aware selling", settings.taxOptimization)
            }
        }
        items(decisions) { DecisionCard(decision = it, expanded = true) }
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
private fun BacktestLabScreen(settings: BotSettings) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Backtest Lab", "Tests strategies before the app is allowed to trust LIVE_AUTO automation.") }
        item {
            HeroCard(
                title = "Live Gate Rules",
                subtitle = "LIVE_AUTO can be configured to require paper/backtest evidence before fully automatic execution.",
                primaryButton = "Run Sample Test",
                secondaryButton = "Forward Test",
                onPrimary = {},
                onSecondary = {}
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Required Trades", settings.requiredPaperTrades.toString(), "Paper/backtest sample", Electric) }
                item { MetricCard("Min Win Rate", "${settings.requiredPaperWinRatePercent}%", "Gate threshold", Mint) }
                item { MetricCard("Profit Factor", settings.requiredProfitFactor.toPlainString(), "Minimum quality", Amber) }
                item { MetricCard("Max Drawdown", "${settings.maxDrawdownPercent}%", "Safety ceiling", Danger) }
            }
        }
        item {
            GlassCard {
                SectionTitle("Automated forward testing", "The app can run real-time paper trades and only unlock live auto when conditions are met.")
                ToggleInfo("Backtest gate", settings.enableBacktestGate)
                ToggleInfo("Forward-test gate", settings.enableForwardTestGate)
                ToggleInfo("Auto safe mode", settings.enableAutoSafeMode)
                Text("Recommended: keep both gates enabled until at least ${settings.requiredPaperTrades} simulated trades are recorded.", color = Muted)
            }
        }
    }
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
        LinearProgressIndicator(progress = { (position.unrealizedPnlPercent.abs().min(BigDecimal("10")).divide(BigDecimal("10"), 2, java.math.RoundingMode.HALF_UP).toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp), color = pnlColor, trackColor = Stroke)
        Text("entry=${position.entryPrice.setScale(4, java.math.RoundingMode.DOWN)} • now=${position.currentPrice.setScale(4, java.math.RoundingMode.DOWN)} • high=${position.highestPrice.setScale(4, java.math.RoundingMode.DOWN)}", color = Muted)
        Text("TP=${position.takeProfitPrice.setScale(4, java.math.RoundingMode.DOWN)} • SL=${position.stopPrice.setScale(4, java.math.RoundingMode.DOWN)} • trail=${position.trailingStopPrice.setScale(4, java.math.RoundingMode.DOWN)}", color = Muted)
        Text("P/L≈€${position.unrealizedPnlEur.setScale(2, java.math.RoundingMode.HALF_UP)} • ${position.reason}", color = pnlColor)
    }
}

@Composable
private fun HistoryScreen(settings: BotSettings) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Trade Memory Brain", "Every trade can become training data for future AI decisions.") }
        item {
            GlassCard {
                ToggleInfo("Previous-trade memory AI", settings.useTradeMemoryAi)
                Text("Stored factors: symbol, strategy, regime, score, news score, volatility, spread, result, hold time and TP/SL outcome.", color = Muted)
                Divider(color = Stroke)
                Text("Example learned rule", fontWeight = FontWeight.Bold)
                Text("SOL high-ATR setups underperform → apply penalty until performance improves.", color = Muted)
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Memory Window", "75", "Recent similar trades", Electric) }
                item { MetricCard("Loss Cooldown", "${settings.lossCooldownMinutes}m", "After losing streak", Danger) }
                item { MetricCard("Safe Mode", if (settings.enableAutoSafeMode) "ON" else "OFF", "Auto-lock risk", Mint) }
            }
        }
    }
}

@Composable
private fun PortfolioScreen(
    settings: BotSettings,
    snapshot: PortfolioSnapshot?,
    onRefresh: () -> Unit
) {
    val assets = snapshot?.assets.orEmpty()
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
                    LiveBalanceRow(asset = asset, progress = pct)
                }
            }
        }
    }
}

@Composable
private fun LiveBalanceRow(asset: com.ksp.cryptobot.core.BalanceInfo, progress: Float) {
    GlassCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(asset.asset, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                Text("total=${asset.total.stripTrailingZeros().toPlainString()} • free=${asset.free.stripTrailingZeros().toPlainString()} • held=${asset.holdTrade.stripTrailingZeros().toPlainString()}", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Text("€${asset.eurValue.setScale(2, java.math.RoundingMode.DOWN)}", color = Mint, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Mint, trackColor = Stroke)
        val actionText = when {
            asset.asset == "EUR" && asset.free >= BigDecimal("5.00") -> "Available for automatic BUY orders."
            asset.asset == "EUR" -> "Too low for BUY orders. Deposit/convert to free EUR if you want buys."
            asset.free > BigDecimal.ZERO -> "Available for automatic SELL orders when the strategy turns bearish."
            else -> "Not currently free for bot trading."
        }
        Text(actionText, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NewsScreen(settings: BotSettings, onToggleNews: (Boolean) -> Unit) {
    val headlines = listOf(
        "BTC macro sentiment scanner",
        "ETH network and ETF-related keyword monitor",
        "Exchange risk and regulatory keyword alerts",
        "Market crash / liquidation warning feed"
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("News Intelligence", "NewsAPI-powered sentiment layer for AI scoring.") }
        item {
            GlassCard {
                ToggleRow("Use news sentiment in AI decisions", settings.useNewsAi, onToggleNews)
                Text("When enabled, the AI layer can adjust confidence using recent market headlines.", color = Muted)
            }
        }
        items(headlines) { headline ->
            GlassCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(Electric)
                    Spacer(Modifier.width(10.dp))
                    Text(headline, fontWeight = FontWeight.SemiBold)
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
    var maxTradesHour by remember(settings) { mutableStateOf(settings.maxTradesPerHour.toString()) }
    var maxLivePositions by remember(settings) { mutableStateOf(settings.maxSimultaneousLivePositions.toString()) }
    var maxPosition by remember(settings) { mutableStateOf(settings.maxPositionEur.toPlainString()) }
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
        maxTradesPerHour = maxTradesHour.toIntOrNull()?.coerceIn(1, 100) ?: settings.maxTradesPerHour,
        maxSimultaneousLivePositions = maxLivePositions.toIntOrNull()?.coerceIn(1, 50) ?: settings.maxSimultaneousLivePositions,
        maxPositionEur = maxPosition.toBigDecimalOrNull() ?: settings.maxPositionEur,
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
                OutlinedTextField(value = maxPosition, onValueChange = { maxPosition = it }, label = { Text("Max position size") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = maxNewTrades, onValueChange = { maxNewTrades = it }, label = { Text("Max new trades per scan") }, modifier = Modifier.fillMaxWidth())
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
                OutlinedTextField(value = newsKey, onValueChange = onNewsKey, label = { Text("NewsAPI key optional") }, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
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
        LinearProgressIndicator(progress = { decision.finalScore.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp), color = accent, trackColor = Stroke)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniScore("Tech", decision.technicalScore)
            MiniScore("News", decision.newsScore)
            MiniScore("Memory", decision.memoryScore)
        }
        if (expanded) {
            Divider(color = Stroke)
            Text(decision.explanation, color = Muted)
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
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp), color = Mint, trackColor = Stroke)
    }
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
