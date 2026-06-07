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
import com.ksp.cryptobot.service.BotForegroundService
import com.ksp.cryptobot.settings.AppSettingsStore
import com.ksp.cryptobot.status.BotStatusStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.math.BigDecimal

private val SpaceBlack = Color(0xFF070A12)
private val Panel = Color(0xFF101727)
private val PanelAlt = Color(0xFF162033)
private val Stroke = Color(0xFF25344F)
private val Electric = Color(0xFF4DA3FF)
private val Mint = Color(0xFF39F5B6)
private val Amber = Color(0xFFFFC857)
private val Danger = Color(0xFFFF5C7A)
private val Muted = Color(0xFF9DA9C2)
private val TextPrimary = Color(0xFFEAF2FF)

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
            KspTradingTheme {
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
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun KspTradingTheme(content: @Composable () -> Unit) {
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
    PORTFOLIO("Portfolio"),
    NEWS("News Intel"),
    TAX("Belgium Tax"),
    RISK("Risk Center"),
    HISTORY("History"),
    SETTINGS("Settings")
}

@Composable
private fun AdvancedBotApp(
    store: AppSettingsStore,
    statusStore: BotStatusStore,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onScan: (BotSettings, Boolean, (List<AiDecision>) -> Unit) -> Unit
) {
    var settings by remember { mutableStateOf(store.load()) }
    var currentTab by remember { mutableStateOf(AppTab.DASHBOARD) }
    var status by remember { mutableStateOf(statusStore.latestText()) }
    var statusLevel by remember { mutableStateOf(statusStore.latestLevel()) }
    var statusHistory by remember { mutableStateOf(statusStore.recentLines()) }
    var decisions by remember { mutableStateOf(sampleDecisions()) }

    LaunchedEffect(Unit) {
        while (true) {
            status = statusStore.latestText()
            statusLevel = statusStore.latestLevel()
            statusHistory = statusStore.recentLines()
            delay(1000L)
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
                        onScan(settings, settings.mode != BotMode.PAPER) { result ->
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
                AppTab.AI -> AiSignalsScreen(decisions = decisions, settings = settings)
                AppTab.STRATEGY -> StrategyScreen(settings = settings, onToggleStrategy = { persistSettings(settings.copy(recoveredScalpingStrategyEnabled = it)) })
                AppTab.BACKTEST -> BacktestLabScreen(settings = settings)
                AppTab.REGIME -> RegimeScreen(settings = settings)
                AppTab.ORDERS -> OrdersScreen(settings = settings)
                AppTab.PORTFOLIO -> PortfolioScreen(settings = settings)
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
                AppTab.SETTINGS -> SettingsScreen(
                    apiKey = apiKey,
                    secretKey = secretKey,
                    newsKey = newsKey,
                    onApiKey = { apiKey = it },
                    onSecretKey = { secretKey = it },
                    onNewsKey = { newsKey = it },
                    settings = settings,
                    onExchangeProvider = { provider -> persistSettings(settings.copy(exchangeProvider = provider, manualExecutionMode = provider == ExchangeProvider.MANUAL || provider == ExchangeProvider.BINANCE_READ_ONLY)) },
                    onManualMode = { persistSettings(settings.copy(manualExecutionMode = it)) },
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
                Text("KSP Crypto AI", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Android-only multi-exchange Belgium mode", color = Muted)
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
                Text("v0.8.3", color = Mint, fontWeight = FontWeight.Bold)
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
        AppTab.values().forEach { tab ->
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
                ToggleInfo("Exchange provider: ${settings.exchangeProvider}", settings.exchangeProvider == ExchangeProvider.KRAKEN)
                ToggleInfo("Mode: ${settings.mode}", settings.mode == BotMode.LIVE_AUTO)
                ToggleInfo("Live acknowledgement", settings.liveTradingAcknowledged)
                ToggleInfo("Manual execution mode OFF", !settings.manualExecutionMode)
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
private fun OrdersScreen(settings: BotSettings) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Smart Orders", "Spread-aware limit entries, stale-order cancellation, partial TP and trailing stops.") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Order Mode", settings.orderManagementMode.name.replace('_', ' '), "Execution style", Electric) }
                item { MetricCard("Stale Cancel", "${settings.staleOrderTimeoutSeconds}s", "Auto-cancel timeout", Amber) }
                item { MetricCard("Partial TP", "${settings.partialTakeProfitPercent}%", "First target size", Mint) }
            }
        }
        item {
            GlassCard {
                ToggleInfo("Trailing stop", settings.enableTrailingStop)
                ToggleInfo("Break-even stop", settings.enableBreakEvenStop)
                ToggleInfo("Partial take-profit", settings.enablePartialTakeProfit)
                ToggleInfo("Smart requote", settings.smartLimitRequote)
                Text("Market orders remain disabled by design. The bot uses guarded limit order planning.", color = Muted)
            }
        }
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
private fun PortfolioScreen(settings: BotSettings) {
    val rows = listOf(
        Triple("BTC", "€1,250.00", 0.72f),
        Triple("ETH", "€740.00", 0.43f),
        Triple("Cash", "€510.00", 0.29f)
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Portfolio", "Local Android view for exposure, allocation and bot limits.") }
        item {
            HeroCard(
                title = "Estimated Portfolio",
                subtitle = "Connect exchange balance fetch next to replace placeholder allocation cards with live balances.",
                primaryButton = "Refresh",
                secondaryButton = "Export",
                onPrimary = {},
                onSecondary = {}
            )
        }
        items(rows) { row ->
            AllocationRow(symbol = row.first, value = row.second, progress = row.third)
        }
        item { WarningCard("Portfolio screen is structured for live balance integration. Current display is a polished UI placeholder until balance endpoints are enabled.") }
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
    onNewsAi: (Boolean) -> Unit,
    onMemoryAi: (Boolean) -> Unit,
    onSaveKeys: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("Settings", "Multi-exchange Belgium mode, secure keys, AI modules and app behavior.") }
        item {
            GlassCard {
                SectionTitle("Exchange Provider", "Choose a legal connector. Binance is read-only in Belgium mode.")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ExchangeProvider.values().toList()) { provider ->
                        FilterChip(
                            selected = settings.exchangeProvider == provider,
                            onClick = { onExchangeProvider(provider) },
                            label = { Text(provider.name.replace('_', ' ')) }
                        )
                    }
                }
                ToggleRow("Manual execution mode / signal-only", settings.manualExecutionMode, onManualMode)
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
        item { WarningCard("Do not use VPNs, false residency, borrowed accounts or other bypass methods. Use a provider that legally supports your Belgian account, or keep the app in manual/paper mode.") }
    }
}

private fun exchangeProviderWarning(provider: ExchangeProvider): String = when (provider) {
    ExchangeProvider.PAPER -> "Paper trading only. No real orders are sent."
    ExchangeProvider.BINANCE_READ_ONLY -> "Belgium mode: Binance trading remains disabled. Signals and read-only market data only."
    ExchangeProvider.KRAKEN -> "Verify Kraken API spot trading is available for your Belgian account before enabling LIVE_AUTO."
    ExchangeProvider.COINBASE_ADVANCED -> "Verify Coinbase Advanced API spot trading is available for your Belgian account before enabling LIVE_AUTO."
    ExchangeProvider.BITVAVO -> "Verify Bitvavo API spot trading is available for your Belgian account before enabling LIVE_AUTO."
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
