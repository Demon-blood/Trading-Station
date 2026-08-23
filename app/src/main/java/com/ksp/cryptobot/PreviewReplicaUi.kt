package com.ksp.cryptobot

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Newspaper
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ksp.cryptobot.cloudshare.CloudShareSettingsStore
import com.ksp.cryptobot.core.AiDecision
import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.ExchangeProvider
import com.ksp.cryptobot.core.LifecycleSnapshot
import com.ksp.cryptobot.core.OrderSide
import com.ksp.cryptobot.core.PortfolioSnapshot
import com.ksp.cryptobot.core.SignalAction
import com.ksp.cryptobot.data.NewsArticleEntity
import com.ksp.cryptobot.data.TradeEntity
import com.ksp.cryptobot.news.NewsProviderHealthRegistry
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

private val PreviewBackground = Color(0xFF0C1522)
private val PreviewTop = Color(0xFF0A1320)
private val PreviewCard = Color(0xFF141D2A)
private val PreviewCardAlt = Color(0xFF111A27)
private val PreviewDivider = Color(0xFF243143)
private val PreviewPurple = Color(0xFF8B5CF6)
private val PreviewPurpleSoft = Color(0xFF9C75FF)
private val PreviewGreen = Color(0xFF62DE67)
private val PreviewRed = Color(0xFFFF5D69)
private val PreviewOrange = Color(0xFFFFA31A)
private val PreviewBlue = Color(0xFF68B7E8)
private val PreviewMint = Color(0xFF77D6BD)
private val PreviewText = Color(0xFFF2F5F8)
private val PreviewMuted = Color(0xFF8E9BAB)
private val PreviewMuted2 = Color(0xFF687687)
private val PreviewBlack = Color(0xFF05090E)

private val previewTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss")

fun previewParentTab(tab: AppTab): AppTab = when (tab) {
    AppTab.AI_SIGNALS,
    AppTab.AI_SIGNAL_DETAIL,
    AppTab.STRATEGY,
    AppTab.SANDBOX,
    AppTab.BACKTEST,
    AppTab.REGIME,
    AppTab.AUTONOMOUS,
    AppTab.SELF_LEARNING,
    AppTab.SELF_LEARNING_MAIN,
    AppTab.LEARNING_INSPECTOR,
    AppTab.PERFORMANCE,
    AppTab.PRO,
    AppTab.SMART_EXIT,
    AppTab.PORTFOLIO_ROTATION,
    AppTab.AUTO_TUNER,
    AppTab.RELEASE_SAFETY,
    AppTab.RESEARCH_SETTINGS -> AppTab.AI

    AppTab.ORDERS,
    AppTab.POSITIONS,
    AppTab.HISTORY,
    AppTab.TAX,
    AppTab.CHART,
    AppTab.CHART_MAIN,
    AppTab.TRADE_OVERLAY,
    AppTab.REPLAY,
    AppTab.TRADE_JOURNAL -> AppTab.PORTFOLIO

    AppTab.BASIC_SETTINGS,
    AppTab.ADVANCED_SETTINGS,
    AppTab.SYSTEM_TEST,
    AppTab.HEALTH,
    AppTab.NOTIFICATIONS,
    AppTab.NOTIFICATION_LOGS,
    AppTab.REMOTE_ALERTS,
    AppTab.BACKUP,
    AppTab.KRAKEN_HEALTH,
    AppTab.CLOUDSHARE_SETTINGS,
    AppTab.RECOVERY_TOOLS,
    AppTab.RISK,
    AppTab.SYMBOLS,
    AppTab.BOT,
    AppTab.STATUS -> AppTab.SETTINGS

    else -> AppTab.DASHBOARD
}

private fun isPrimaryPreviewTab(tab: AppTab): Boolean = tab in setOf(
    AppTab.DASHBOARD, AppTab.PORTFOLIO, AppTab.AI, AppTab.NEWS, AppTab.SETTINGS
)

private fun previewTopTitle(tab: AppTab, detailSymbol: String?): String = when (tab) {
    AppTab.DASHBOARD -> "Dashboard"
    AppTab.PORTFOLIO -> "Portfolio"
    AppTab.AI -> "AI & Research"
    AppTab.NEWS -> "News & Intelligence"
    AppTab.SETTINGS -> "Settings"
    AppTab.POSITIONS -> "Active Positions"
    AppTab.ORDERS -> "Orders"
    AppTab.HISTORY -> "History"
    AppTab.AI_SIGNAL_DETAIL -> "${previewPair(detailSymbol.orEmpty())} – AI Signal"
    AppTab.AI_SIGNALS -> "AI Signals"
    AppTab.SYSTEM_TEST -> "System Diagnostics"
    AppTab.BASIC_SETTINGS -> "Connection & Trading"
    AppTab.ADVANCED_SETTINGS -> "Automation & Risk"
    AppTab.BACKUP -> "Settings"
    AppTab.RESEARCH_SETTINGS -> "AI & Research"
    AppTab.CLOUDSHARE_SETTINGS -> "CloudShare"
    AppTab.RECOVERY_TOOLS -> "Recovery"
    else -> tab.label
}

@Composable
fun PreviewAppTopBar(
    currentTab: AppTab,
    detailSymbol: String? = null,
    onNavigate: (AppTab) -> Unit,
    onBack: () -> Unit,
    onAction: () -> Unit
) {
    val showMenu = currentTab == AppTab.DASHBOARD || currentTab == AppTab.PORTFOLIO
    var menuExpanded by remember(currentTab) { mutableStateOf(false) }
    val actionIcon: ImageVector? = when (currentTab) {
        AppTab.DASHBOARD -> Icons.Rounded.Info
        AppTab.PORTFOLIO, AppTab.POSITIONS, AppTab.ORDERS -> Icons.Rounded.Refresh
        AppTab.SYSTEM_TEST -> Icons.Rounded.Security
        else -> null
    }
    val quickNavigation = listOf(
        AppTab.DASHBOARD to "Dashboard",
        AppTab.PORTFOLIO to "Portfolio",
        AppTab.AI to "AI & Research",
        AppTab.NEWS to "News & Intelligence",
        AppTab.SETTINGS to "Settings",
        AppTab.POSITIONS to "Active Positions",
        AppTab.ORDERS to "Orders",
        AppTab.TRADE_JOURNAL to "Trade Journal",
        AppTab.SYSTEM_TEST to "System Diagnostics"
    )
    Surface(color = PreviewTop, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                IconButton(
                    onClick = {
                        if (showMenu) menuExpanded = true else onBack()
                    }
                ) {
                    Icon(
                        imageVector = if (showMenu) Icons.Rounded.Menu else Icons.Rounded.ArrowBack,
                        contentDescription = if (showMenu) "Open quick navigation" else "Back",
                        tint = PreviewText,
                        modifier = Modifier.size(22.dp)
                    )
                }
                if (showMenu) {
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        modifier = Modifier.background(PreviewCard)
                    ) {
                        Text(
                            "Quick Navigation",
                            color = PreviewMuted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        quickNavigation.forEach { (tab, label) ->
                            val selected = currentTab == tab || previewParentTab(currentTab) == tab
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        label,
                                        color = if (selected) PreviewPurpleSoft else PreviewText,
                                        fontSize = 14.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onNavigate(tab)
                                }
                            )
                        }
                    }
                }
            }
            Text(
                previewTopTitle(currentTab, detailSymbol),
                color = PreviewText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (actionIcon != null) {
                IconButton(onClick = onAction) {
                    Icon(actionIcon, contentDescription = "Action", tint = PreviewText, modifier = Modifier.size(21.dp))
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }
        Divider(color = PreviewDivider.copy(alpha = 0.55f), thickness = 0.7.dp)
    }
}

@Composable
fun PreviewBottomNavigation(currentTab: AppTab, onTabSelected: (AppTab) -> Unit) {
    val parent = if (isPrimaryPreviewTab(currentTab)) currentTab else previewParentTab(currentTab)
    val items = listOf(
        Triple(AppTab.DASHBOARD, Icons.Rounded.Home, "Dashboard"),
        Triple(AppTab.PORTFOLIO, Icons.Rounded.AccountBalanceWallet, "Portfolio"),
        Triple(AppTab.AI, Icons.Rounded.Analytics, "AI"),
        Triple(AppTab.NEWS, Icons.Rounded.Newspaper, "News"),
        Triple(AppTab.SETTINGS, Icons.Rounded.Settings, "Settings")
    )
    Surface(color = PreviewTop, modifier = Modifier.fillMaxWidth()) {
        Column {
            Divider(color = PreviewDivider, thickness = 0.7.dp)
            Row(
                modifier = Modifier.fillMaxWidth().height(72.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { (tab, icon, label) ->
                    val selected = parent == tab
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().clickable { onTabSelected(tab) }.padding(top = 8.dp, bottom = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(icon, contentDescription = label, tint = if (selected) PreviewPurpleSoft else PreviewMuted, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(label, color = if (selected) PreviewPurpleSoft else PreviewMuted, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewCardBox(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Surface(
        modifier = modifier.fillMaxWidth().then(clickModifier),
        color = PreviewCard,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(0.7.dp, PreviewDivider.copy(alpha = 0.72f))
    ) {
        Box(Modifier.padding(14.dp)) { content() }
    }
}

@Composable
private fun PreviewSectionHeader(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = PreviewText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (!trailing.isNullOrBlank()) Text(trailing, color = PreviewText, fontSize = 13.sp)
    }
}

private fun euro(value: BigDecimal): String = "€" + value.setScale(2, RoundingMode.HALF_UP).toPlainString()
private fun pct(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%"
private fun safeBig(value: String): BigDecimal = value.toBigDecimalOrNull() ?: BigDecimal.ZERO
private fun tradeNotional(trade: TradeEntity): BigDecimal = safeBig(trade.quantity).multiply(safeBig(trade.priceEur))

fun previewPair(symbol: String): String {
    val clean = symbol.uppercase().replace("/", "").replace("-", "")
    val quotes = listOf("USDT", "USDC", "EUR", "USD", "BTC", "ETH")
    val quote = quotes.firstOrNull { clean.endsWith(it) } ?: return clean
    val base = clean.removeSuffix(quote)
    return if (base.isBlank()) clean else "$base/$quote"
}

private fun previewBase(symbol: String): String = previewPair(symbol).substringBefore('/')

private fun friendlyAsset(base: String): String = when (base.uppercase()) {
    "BTC", "XBT" -> "Bitcoin"
    "ETH" -> "Ethereum"
    "KAS" -> "Kaspa"
    "HBAR" -> "Hedera"
    "SOL" -> "Solana"
    "XRP" -> "XRP"
    "ADA" -> "Cardano"
    "DOGE" -> "Dogecoin"
    "DOT" -> "Polkadot"
    else -> base.uppercase()
}

@Composable
private fun AssetIcon(base: String, size: Int = 28) {
    val normalized = base.uppercase().replace("XBT", "BTC")
    val background = when (normalized) {
        "BTC" -> PreviewOrange
        "ETH" -> Color(0xFF66768D)
        "KAS" -> Color(0xFF75D3CB)
        "HBAR" -> PreviewBlack
        "SOL" -> Color(0xFF6547D8)
        "XRP" -> Color(0xFF303946)
        else -> Color(0xFF344358)
    }
    val label = when (normalized) {
        "BTC" -> "₿"
        "ETH" -> "◆"
        "HBAR" -> "H"
        else -> normalized.take(1)
    }
    Surface(shape = CircleShape, color = background, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.46f).sp)
        }
    }
}

private fun realized24h(trades: List<TradeEntity>): BigDecimal {
    val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
    return trades.asSequence().filter { it.timestampEpochMs >= cutoff }.map { safeBig(it.realizedPnlEur) }.fold(BigDecimal.ZERO, BigDecimal::add)
}

private fun turnover24h(trades: List<TradeEntity>): BigDecimal {
    val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L
    return trades.asSequence().filter { it.timestampEpochMs >= cutoff }.map(::tradeNotional).fold(BigDecimal.ZERO, BigDecimal::add)
}

private fun portfolioTrend(total: BigDecimal, trades: List<TradeEntity>): List<Float> {
    val rows = trades.sortedBy { it.timestampEpochMs }.takeLast(24)
    if (rows.isEmpty()) return listOf(0.5f, 0.5f)
    val net = rows.map { safeBig(it.realizedPnlEur) }
    var running = total.subtract(net.fold(BigDecimal.ZERO) { a, b -> a.add(b) })
    val values = mutableListOf(running)
    net.forEach { running = running.add(it); values += running }
    val min = values.minOrNull() ?: BigDecimal.ZERO
    val max = values.maxOrNull() ?: BigDecimal.ONE
    val range = max.subtract(min).takeIf { it > BigDecimal.ZERO } ?: BigDecimal.ONE
    return values.map { it.subtract(min).divide(range, 8, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f) }
}

@Composable
private fun PreviewAreaSparkline(points: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        val xStep = size.width / (points.size - 1).coerceAtLeast(1)
        fun y(v: Float) = size.height - (size.height * (0.13f + v * 0.74f))
        val path = Path().apply {
            moveTo(0f, y(points.first()))
            points.drop(1).forEachIndexed { index, value -> lineTo((index + 1) * xStep, y(value)) }
        }
        val fill = Path().apply {
            moveTo(0f, size.height)
            lineTo(0f, y(points.first()))
            points.drop(1).forEachIndexed { index, value -> lineTo((index + 1) * xStep, y(value)) }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(fill, brush = Brush.verticalGradient(listOf(PreviewGreen.copy(alpha = 0.22f), PreviewGreen.copy(alpha = 0.01f))))
        drawPath(path, color = PreviewGreen, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
        drawCircle(PreviewGreen, radius = 3.2f, center = Offset(size.width, y(points.last())))
    }
}

@Composable
fun PreviewDashboardScreen(
    settings: BotSettings,
    status: String,
    portfolio: PortfolioSnapshot?,
    lifecycle: LifecycleSnapshot?,
    decisions: List<AiDecision>,
    trades: List<TradeEntity>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onScan: () -> Unit,
    onExecute: () -> Unit,
    onOpenNews: () -> Unit
) {
    val total = portfolio?.totalValueEur ?: BigDecimal.ZERO
    val available = portfolio?.freeEur ?: BigDecimal.ZERO
    val invested = total.subtract(available).max(BigDecimal.ZERO)
    val pnl24 = realized24h(trades)
    val pct24 = if (total.subtract(pnl24) > BigDecimal.ZERO) pnl24.multiply(BigDecimal("100")).divide(total.subtract(pnl24), 4, RoundingMode.HALF_UP) else BigDecimal.ZERO
    val positions = lifecycle?.positions.orEmpty().filter { it.quantity > BigDecimal.ZERO }.take(4)
    val trend = portfolioTrend(total, trades)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PreviewCardBox {
                Column {
                    Text("Portfolio Value", color = PreviewMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(3.dp))
                    Text(euro(total), color = PreviewGreen, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("24H P/L", color = PreviewMuted, fontSize = 10.sp)
                    Text(
                        (if (pnl24 >= BigDecimal.ZERO) "+" else "") + euro(pnl24) + " (" + (if (pct24 >= BigDecimal.ZERO) "+" else "") + pct(pct24) + ")",
                        color = if (pnl24 >= BigDecimal.ZERO) PreviewGreen else PreviewRed,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    PreviewAreaSparkline(trend, Modifier.fillMaxWidth().height(78.dp).padding(top = 4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        DashboardStat("Invested", euro(invested))
                        DashboardStat("Available", euro(available))
                        DashboardStat("24H Volume", euro(turnover24h(trades)))
                    }
                }
            }
        }
        item {
            PreviewCardBox {
                Column {
                    PreviewSectionHeader("Active Positions", positions.size.toString())
                    Spacer(Modifier.height(7.dp))
                    if (positions.isEmpty()) {
                        Text("No active positions", color = PreviewMuted, fontSize = 12.sp)
                    } else {
                        positions.forEachIndexed { index, p ->
                            DashboardPositionRow(p)
                            if (index != positions.lastIndex) Divider(color = PreviewDivider.copy(alpha = 0.65f), modifier = Modifier.padding(vertical = 7.dp))
                        }
                    }
                }
            }
        }
        item {
            PreviewCardBox {
                Column {
                    PreviewSectionHeader("Bot Controls")
                    Spacer(Modifier.height(6.dp))
                    Text(status, color = PreviewMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PreviewPrimaryButton("Scan", onScan, Modifier.weight(1f))
                        PreviewOutlineButton("Execute", onExecute, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PreviewOutlineButton("Start", onStart, Modifier.weight(1f))
                        PreviewOutlineButton("Stop", onStop, Modifier.weight(1f))
                        PreviewOutlineButton("News", onOpenNews, Modifier.weight(1f))
                    }
                    val latest = decisions.maxByOrNull { it.finalScore }
                    if (latest != null) {
                        Spacer(Modifier.height(9.dp))
                        Divider(color = PreviewDivider)
                        Spacer(Modifier.height(8.dp))
                        Text("Latest AI: ${previewPair(latest.symbol)} ${latest.finalAction.name.replace('_', ' ')} • ${latest.confidencePercent}%", color = previewActionColor(latest.finalAction), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardStat(label: String, value: String) {
    Column {
        Text(label, color = PreviewMuted, fontSize = 9.sp)
        Text(value, color = PreviewText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DashboardPositionRow(position: com.ksp.cryptobot.core.PositionInfo) {
    val base = previewBase(position.symbol)
    val value = position.currentPrice.multiply(position.quantity)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AssetIcon(base, 26)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(previewPair(position.symbol), color = PreviewText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(friendlyAsset(base), color = PreviewMuted, fontSize = 9.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(euro(value), color = PreviewText, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Text((if (position.unrealizedPnlPercent >= BigDecimal.ZERO) "+" else "") + pct(position.unrealizedPnlPercent), color = if (position.unrealizedPnlPercent >= BigDecimal.ZERO) PreviewGreen else PreviewRed, fontSize = 10.sp)
        }
    }
}

@Composable
private fun PreviewPrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PreviewPurple, contentColor = Color.White),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
    ) { Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun PreviewOutlineButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        shape = RoundedCornerShape(6.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, PreviewPurple),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = PreviewPurpleSoft),
        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)
    ) { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun PreviewPortfolioScreen(
    settings: BotSettings,
    snapshot: PortfolioSnapshot?,
    lifecycleSnapshot: LifecycleSnapshot?,
    trades: List<TradeEntity>,
    onRefresh: () -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Positions", "Orders", "History", "Allocations")
    val assets = snapshot?.assets.orEmpty().filter { it.total > BigDecimal.ZERO || it.eurValue > BigDecimal.ZERO }
    val total = snapshot?.totalValueEur ?: BigDecimal.ZERO
    val pnl24 = realized24h(trades)
    val pct24 = if (total.subtract(pnl24) > BigDecimal.ZERO) pnl24.multiply(BigDecimal("100")).divide(total.subtract(pnl24), 4, RoundingMode.HALF_UP) else BigDecimal.ZERO

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 0.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PreviewSegmentTabs(labels = tabs, selected = tab, onSelected = { tab = it })
        }
        when (tab) {
            0, 3 -> {
                item {
                    PreviewCardBox {
                        Column {
                            Text("Total Value", color = PreviewMuted, fontSize = 10.sp)
                            Text(euro(total), color = PreviewGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("24H P/L", color = PreviewMuted, fontSize = 9.sp)
                            Text((if (pnl24 >= BigDecimal.ZERO) "+" else "") + euro(pnl24) + " (" + (if (pct24 >= BigDecimal.ZERO) "+" else "") + pct(pct24) + ")", color = if (pnl24 >= BigDecimal.ZERO) PreviewGreen else PreviewRed, fontSize = 10.sp)
                            Spacer(Modifier.height(10.dp))
                            AssetAllocationDonut(assets = assets, total = total, modifier = Modifier.fillMaxWidth().height(160.dp))
                            Spacer(Modifier.height(5.dp))
                            assets.take(8).forEach { asset -> AllocationRow(asset.asset, asset.eurValue, total) }
                        }
                    }
                }
            }
            1 -> {
                val orders = lifecycleSnapshot?.openOrders.orEmpty()
                if (orders.isEmpty()) item { PreviewEmptyCard("No open orders") }
                items(orders) { order ->
                    PreviewCardBox {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            AssetIcon(previewBase(order.symbol), 28)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("${order.side.name} ${previewPair(order.symbol)}", color = PreviewText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Text("${order.orderType.name} • ${order.status}", color = PreviewMuted, fontSize = 10.sp)
                            }
                            Text(order.remainingQuantity.stripTrailingZeros().toPlainString(), color = PreviewText, fontSize = 11.sp)
                        }
                    }
                }
            }
            2 -> {
                if (trades.isEmpty()) item { PreviewEmptyCard("No trade history") }
                items(trades.take(80)) { trade -> PreviewTradeRow(trade) }
            }
        }
        item {
            Spacer(Modifier.height(2.dp))
            PreviewOutlineButton("Refresh Portfolio", onRefresh, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PreviewEmptyCard(message: String) {
    PreviewCardBox { Text(message, color = PreviewMuted, fontSize = 12.sp) }
}

@Composable
private fun PreviewTradeRow(trade: TradeEntity) {
    val pnl = safeBig(trade.realizedPnlEur)
    PreviewCardBox {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AssetIcon(previewBase(trade.symbol), 28)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("${trade.side} ${previewPair(trade.symbol)}", color = PreviewText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text("${trade.quantity} @ ${trade.priceEur}", color = PreviewMuted, fontSize = 10.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (trade.paper) "PAPER" else "LIVE", color = PreviewPurpleSoft, fontSize = 9.sp)
                Text((if (pnl > BigDecimal.ZERO) "+" else "") + euro(pnl), color = if (pnl >= BigDecimal.ZERO) PreviewGreen else PreviewRed, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun AssetAllocationDonut(assets: List<com.ksp.cryptobot.core.BalanceInfo>, total: BigDecimal, modifier: Modifier = Modifier) {
    val palette = listOf(PreviewPurple, PreviewBlue, PreviewMint, PreviewOrange, Color(0xFFA592DF), Color(0xFF4F6B87), Color(0xFF66C57A))
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(118.dp)) {
            var start = -90f
            val stroke = Stroke(width = 23f, cap = StrokeCap.Butt)
            if (assets.isEmpty() || total <= BigDecimal.ZERO) {
                drawArc(PreviewDivider, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = stroke)
            } else {
                assets.take(7).forEachIndexed { index, asset ->
                    val share = asset.eurValue.divide(total, 8, RoundingMode.HALF_UP).toFloat().coerceAtLeast(0f)
                    val sweep = share * 360f
                    if (sweep > 0.1f) drawArc(palette[index % palette.size], startAngle = start, sweepAngle = sweep, useCenter = false, style = stroke)
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Asset", color = PreviewText, fontSize = 11.sp)
            Text("Allocation", color = PreviewText, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AllocationRow(assetName: String, value: BigDecimal, total: BigDecimal) {
    val share = if (total > BigDecimal.ZERO) value.multiply(BigDecimal("100")).divide(total, 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(previewAssetColor(assetName), RoundedCornerShape(1.dp))) {}
        Spacer(Modifier.width(8.dp))
        Text(assetName.uppercase().replace("XBT", "BTC"), color = PreviewText, fontSize = 11.sp, modifier = Modifier.weight(1f))
        Text(pct(share), color = PreviewText, fontSize = 10.sp, modifier = Modifier.width(52.dp))
        Text(euro(value), color = PreviewText, fontSize = 10.sp)
    }
}

private fun previewAssetColor(asset: String): Color = when (asset.uppercase().replace("XBT", "BTC")) {
    "BTC" -> PreviewOrange
    "ETH" -> PreviewBlue
    "KAS" -> PreviewMint
    "HBAR" -> PreviewPurple
    else -> Color(0xFF7E72BD)
}

@Composable
private fun PreviewSegmentTabs(labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { index, label ->
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelected(index) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(label, color = if (selected == index) PreviewPurpleSoft else PreviewMuted, fontSize = 10.sp, fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal)
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.8f).height(2.dp).background(if (selected == index) PreviewPurple else Color.Transparent)) {}
            }
        }
    }
}

@Composable
fun PreviewAiHubScreen(
    decisions: List<AiDecision>,
    settings: BotSettings,
    performanceLabSnapshot: com.ksp.cryptobot.core.PerformanceLabSnapshot?,
    trades: List<TradeEntity>,
    onOpen: (AppTab) -> Unit,
    onSelectSignal: (AiDecision) -> Unit,
    onScan: () -> Unit
) {
    var segment by remember { mutableIntStateOf(0) }
    val segments = listOf("AI Signals", "Research", "Backtest")
    val scored = decisions.sortedByDescending { it.finalScore }
    val average = if (scored.isEmpty()) 50 else scored.map { it.finalScore }.average().toInt().coerceIn(0, 100)
    val buys = scored.count { it.finalAction == SignalAction.BUY || it.finalAction == SignalAction.SMALL_BUY }
    val sells = scored.count { it.finalAction == SignalAction.SELL }
    val bias = when {
        buys > sells && average >= 55 -> "BULLISH"
        sells > buys && average <= 45 -> "BEARISH"
        else -> "NEUTRAL"
    }
    val biasColor = when (bias) { "BULLISH" -> PreviewGreen; "BEARISH" -> PreviewRed; else -> PreviewMuted }
    val cutoff30d = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
    val sellTrades = trades.filter { it.side.equals("SELL", true) && it.timestampEpochMs >= cutoff30d }
    val wins = sellTrades.count { safeBig(it.realizedPnlEur) > BigDecimal.ZERO }
    val losses = sellTrades.filter { safeBig(it.realizedPnlEur) < BigDecimal.ZERO }
    val winRate = if (sellTrades.isEmpty()) BigDecimal.ZERO else BigDecimal(wins * 100).divide(BigDecimal(sellTrades.size), 1, RoundingMode.HALF_UP)
    val grossWin = sellTrades.filter { safeBig(it.realizedPnlEur) > BigDecimal.ZERO }.fold(BigDecimal.ZERO) { a, t -> a.add(safeBig(t.realizedPnlEur)) }
    val grossLoss = losses.fold(BigDecimal.ZERO) { a, t -> a.add(safeBig(t.realizedPnlEur).abs()) }
    val pf = if (grossLoss > BigDecimal.ZERO) grossWin.divide(grossLoss, 2, RoundingMode.HALF_UP) else if (grossWin > BigDecimal.ZERO) BigDecimal("99") else BigDecimal.ZERO

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { PreviewSegmentTabs(segments, segment) { segment = it } }
        when (segment) {
            1 -> {
                item {
                    PreviewCardBox(onClick = { onOpen(AppTab.RESEARCH_SETTINGS) }) {
                        Column {
                            PreviewSectionHeader("Research Intelligence")
                            Spacer(Modifier.height(6.dp))
                            Text("Professional research, handoff truth, robustness validation and external context.", color = PreviewMuted, fontSize = 11.sp)
                            Spacer(Modifier.height(10.dp))
                            PreviewOutlineButton("Open Research", { onOpen(AppTab.RESEARCH_SETTINGS) }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            2 -> {
                item {
                    PreviewCardBox(onClick = { onOpen(AppTab.BACKTEST) }) {
                        Column {
                            PreviewSectionHeader("Backtest Lab")
                            Spacer(Modifier.height(6.dp))
                            Text("Kraken OHLC backtests and forward gates before live promotion.", color = PreviewMuted, fontSize = 11.sp)
                            Spacer(Modifier.height(10.dp))
                            PreviewOutlineButton("Open Backtest", { onOpen(AppTab.BACKTEST) }, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            else -> {
                item {
                    PreviewCardBox {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Market Bias", color = PreviewMuted, fontSize = 10.sp)
                                Spacer(Modifier.height(6.dp))
                                Text(bias, color = biasColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(13.dp))
                                Text("Confidence", color = PreviewMuted, fontSize = 10.sp)
                            }
                            PreviewConfidenceGauge(average, biasColor, Modifier.size(92.dp))
                        }
                    }
                }
                item {
                    PreviewCardBox {
                        Column {
                            PreviewSectionHeader("Top AI Signals")
                            Spacer(Modifier.height(6.dp))
                            if (scored.isEmpty()) {
                                Text("No AI scan loaded yet", color = PreviewMuted, fontSize = 11.sp)
                            } else {
                                scored.take(4).forEachIndexed { index, decision ->
                                    AiSignalRow(decision) { onSelectSignal(decision) }
                                    if (index < scored.take(4).lastIndex) Divider(color = PreviewDivider.copy(alpha = 0.65f), modifier = Modifier.padding(vertical = 5.dp))
                                }
                            }
                            Spacer(Modifier.height(7.dp))
                            Row(Modifier.fillMaxWidth().clickable { onOpen(AppTab.AI_SIGNALS) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("View All AI Signals", color = PreviewText, fontSize = 11.sp, modifier = Modifier.weight(1f))
                                Icon(Icons.Rounded.ChevronRight, null, tint = PreviewMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                item {
                    PreviewCardBox {
                        Column {
                            PreviewSectionHeader("AI Performance (30D)")
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text("Win Rate", color = PreviewMuted, fontSize = 10.sp)
                                    Text(pct(winRate), color = PreviewGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Profit Factor", color = PreviewMuted, fontSize = 10.sp)
                                    Text(pf.stripTrailingZeros().toPlainString(), color = PreviewGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            performanceLabSnapshot?.summaryLine?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.height(7.dp)); Text(it, color = PreviewMuted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                item { PreviewOutlineButton("Scan AI Signals", onScan, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun AiSignalRow(decision: AiDecision, onClick: () -> Unit) {
    val action = decision.finalAction.name.replace('_', ' ')
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AssetIcon(previewBase(decision.symbol), 24)
        Spacer(Modifier.width(8.dp))
        Text(previewPair(decision.symbol), color = PreviewText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(70.dp))
        Text(action, color = previewActionColor(decision.finalAction), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(58.dp))
        Text(previewSignalDescriptor(decision), color = PreviewMuted, fontSize = 9.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${decision.confidencePercent}%", color = PreviewText, fontSize = 10.sp)
    }
}

private fun previewSignalDescriptor(decision: AiDecision): String = when (decision.finalAction) {
    SignalAction.BUY -> "Strong Uptrend"
    SignalAction.SMALL_BUY -> "Momentum Building"
    SignalAction.WATCH -> "Watch"
    SignalAction.WAIT -> "Neutral"
    SignalAction.AVOID -> "Weak Momentum"
    SignalAction.STRONG_AVOID -> "Risk Elevated"
    SignalAction.SELL -> "Exit Signal"
}

private fun previewActionColor(action: SignalAction): Color = when (action) {
    SignalAction.BUY, SignalAction.SMALL_BUY -> PreviewGreen
    SignalAction.SELL, SignalAction.AVOID, SignalAction.STRONG_AVOID -> PreviewRed
    SignalAction.WATCH -> PreviewOrange
    else -> PreviewMuted
}

@Composable
private fun PreviewConfidenceGauge(value: Int, color: Color, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 9f, cap = StrokeCap.Round)
            drawArc(PreviewDivider, startAngle = 150f, sweepAngle = 240f, useCenter = false, style = stroke)
            drawArc(color, startAngle = 150f, sweepAngle = 240f * value.coerceIn(0, 100) / 100f, useCenter = false, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$value%", color = PreviewText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text("Confidence", color = PreviewMuted, fontSize = 8.sp)
        }
    }
}

@Composable
fun PreviewAiSignalsListScreen(
    decisions: List<AiDecision>,
    settings: BotSettings,
    activePositionSymbols: List<String>,
    onScan: () -> Unit,
    onSelectSignal: (AiDecision) -> Unit
) {
    val active = activePositionSymbols.map { it.uppercase().replace("/", "").replace("-", "") }.toSet()
    val rows = decisions.sortedWith(compareByDescending<AiDecision> { active.contains(it.symbol.uppercase().replace("/", "").replace("-", "")) }.thenByDescending { it.finalScore })
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PreviewCardBox {
                Column {
                    Text("Real-time AI decisions", color = PreviewText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("${rows.size} signal(s) • Strategy ${settings.strategyMode.name.replace('_', ' ')}", color = PreviewMuted, fontSize = 10.sp)
                    Spacer(Modifier.height(9.dp))
                    PreviewOutlineButton("Scan AI Signals", onScan, Modifier.fillMaxWidth())
                }
            }
        }
        if (rows.isEmpty()) item { PreviewEmptyCard("No AI signals loaded") }
        items(rows) { decision ->
            PreviewCardBox(onClick = { onSelectSignal(decision) }) {
                Column {
                    AiSignalRow(decision) { onSelectSignal(decision) }
                    Spacer(Modifier.height(5.dp))
                    Text(decision.explanation, color = PreviewMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun PreviewAiSignalDetailScreen(decision: AiDecision?, candles: List<Candle>) {
    if (decision == null) {
        Box(Modifier.fillMaxSize().background(PreviewBackground), contentAlignment = Alignment.Center) { Text("No signal selected", color = PreviewMuted) }
        return
    }
    val recent20 = candles.takeLast(20)
    val recent60 = candles.takeLast(60)
    val support1 = recent20.minOfOrNull { it.low }
    val support2 = recent60.minOfOrNull { it.low }
    val resistance1 = recent20.maxOfOrNull { it.high }
    val resistance2 = recent60.maxOfOrNull { it.high }
    val actionColor = previewActionColor(decision.finalAction)
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PreviewCardBox {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(decision.finalAction.name.replace('_', ' '), color = actionColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(previewSignalDescriptor(decision), color = PreviewText, fontSize = 11.sp)
                    }
                    PreviewConfidenceGauge(decision.confidencePercent, actionColor, Modifier.size(94.dp))
                }
            }
        }
        item {
            PreviewCardBox {
                Column {
                    PreviewSectionHeader("Analysis")
                    Spacer(Modifier.height(9.dp))
                    Text(decision.explanation, color = PreviewText, fontSize = 11.sp, lineHeight = 16.sp)
                    Spacer(Modifier.height(7.dp))
                    Text("Technical ${decision.technicalScore} • News ${decision.newsScore} • Memory ${decision.memoryScore}", color = PreviewMuted, fontSize = 9.sp)
                }
            }
        }
        item {
            PreviewCardBox {
                Column {
                    PreviewSectionHeader("Key Levels")
                    Spacer(Modifier.height(8.dp))
                    KeyLevelRow("Support 1", support1)
                    KeyLevelRow("Support 2", support2)
                    KeyLevelRow("Resistance 1", resistance1)
                    KeyLevelRow("Resistance 2", resistance2)
                    if (candles.isEmpty()) {
                        Spacer(Modifier.height(6.dp)); Text("Load chart data to calculate live support/resistance levels.", color = PreviewMuted, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyLevelRow(label: String, price: BigDecimal?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, color = PreviewText, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Text(price?.let(::euro) ?: "—", color = PreviewText, fontSize = 10.sp)
    }
}

@Composable
fun PreviewNewsScreen(
    settings: BotSettings,
    newsHistory: List<NewsArticleEntity>,
    activeSymbols: List<String>,
    decisions: List<AiDecision>,
    onToggleNews: (Boolean) -> Unit,
    onRefreshHistory: (String) -> Unit,
    onScanNews: (String) -> Unit
) {
    val newsScores = decisions.map { it.newsScore }
    val sentiment = if (newsScores.isEmpty()) 50 else (50 + newsScores.average().toInt()).coerceIn(0, 100)
    val label = when { sentiment >= 60 -> "BULLISH"; sentiment <= 40 -> "BEARISH"; else -> "NEUTRAL" }
    val sentimentColor = when (label) { "BULLISH" -> PreviewGreen; "BEARISH" -> PreviewRed; else -> PreviewMuted }
    val stories = newsHistory.sortedByDescending { it.publishedAtEpochMs }.take(8)
    val providers = NewsProviderHealthRegistry.snapshot()
    val targetSymbol = (activeSymbols + settings.symbols()).firstOrNull().orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PreviewCardBox {
                Column {
                    Text("Overall News Sentiment", color = PreviewMuted, fontSize = 10.sp)
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = sentimentColor, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("$sentiment/100", color = PreviewText, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(7.dp))
                    LinearProgressIndicator(
                        progress = sentiment / 100f,
                        modifier = Modifier.fillMaxWidth().height(5.dp),
                        color = sentimentColor,
                        trackColor = PreviewDivider
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("Updated from the latest stored decision/news inputs", color = PreviewMuted2, fontSize = 8.sp, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            PreviewCardBox {
                Column {
                    PreviewSectionHeader("Top Stories")
                    Spacer(Modifier.height(7.dp))
                    if (stories.isEmpty()) {
                        Text("No cached stories yet", color = PreviewMuted, fontSize = 11.sp)
                    } else {
                        stories.take(5).forEachIndexed { index, story ->
                            NewsStoryRow(story)
                            if (index < minOf(4, stories.lastIndex)) Divider(color = PreviewDivider.copy(alpha = 0.65f), modifier = Modifier.padding(vertical = 6.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth().clickable { onRefreshHistory("") }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("View All News", color = PreviewText, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.ChevronRight, null, tint = PreviewMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        item {
            PreviewCardBox {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("News Intelligence", color = PreviewText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Switch(
                            checked = settings.useNewsAi,
                            onCheckedChange = onToggleNews,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PreviewGreen, uncheckedThumbColor = PreviewMuted, uncheckedTrackColor = PreviewDivider)
                        )
                    }
                    if (providers.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        providers.take(8).forEach { provider ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                                Text(provider.provider, color = PreviewMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
                                val healthy = provider.status == "HEALTHY" || provider.status == "READY" || provider.status == "EMPTY"
                                Text(provider.status, color = if (healthy) PreviewGreen else PreviewRed, fontSize = 9.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PreviewOutlineButton("Refresh", { onRefreshHistory("") }, Modifier.weight(1f))
                        PreviewPrimaryButton("Scan News", { onScanNews(targetSymbol) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsStoryRow(story: NewsArticleEntity) {
    val base = previewBase(story.symbol)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AssetIcon(base.ifBlank { "N" }, 27)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(story.title, color = PreviewText, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val age = previewAge(story.publishedAtEpochMs)
            Text(age, color = PreviewMuted2, fontSize = 8.sp)
        }
        Text(story.provider.ifBlank { story.source }.take(10), color = PreviewGreen, fontSize = 8.sp, maxLines = 1)
    }
}

private fun previewAge(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val mins = ((System.currentTimeMillis() - epochMs).coerceAtLeast(0L) / 60000L)
    return when {
        mins < 60 -> "${mins}m ago"
        mins < 1440 -> "${mins / 60}h ago"
        else -> "${mins / 1440}d ago"
    }
}

@Composable
fun PreviewSettingsScreen(
    settings: BotSettings,
    portfolio: PortfolioSnapshot?,
    onPersist: (BotSettings) -> Unit,
    onOpen: (AppTab) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    val labels = listOf("Connection & Trading", "Automation & Risk")
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { PreviewSettingsSegment(labels, tab) { tab = it } }
        if (tab == 0) {
            item {
                PreviewCardBox {
                    Column {
                        PreviewSectionHeader("Exchange Connection")
                        Spacer(Modifier.height(8.dp))
                        SettingsValueRow("Exchange", settings.exchangeProvider.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
                        SettingsValueRow("Status", previewExchangeStatus(settings, portfolio), valueColor = if ((settings.exchangeProvider == ExchangeProvider.KRAKEN && portfolio != null) || settings.exchangeProvider == ExchangeProvider.PAPER) PreviewGreen else PreviewMuted)
                        SettingsNavRow("API Management") { onOpen(AppTab.BASIC_SETTINGS) }
                    }
                }
            }
            item {
                PreviewCardBox {
                    Column {
                        PreviewSectionHeader("Trading Mode")
                        Spacer(Modifier.height(8.dp))
                        SettingsValueRow("Live Trading", if (settings.mode == BotMode.LIVE_AUTO) "ENABLED" else "DISABLED", if (settings.mode == BotMode.LIVE_AUTO) PreviewGreen else PreviewRed)
                        SettingsValueRow("Paper Trading", if (settings.mode == BotMode.PAPER) "ENABLED" else "DISABLED", if (settings.mode == BotMode.PAPER) PreviewGreen else PreviewRed)
                        SettingsNavRow("Change Trading Mode") { onOpen(AppTab.BASIC_SETTINGS) }
                    }
                }
            }
            item {
                PreviewCardBox {
                    Column {
                        PreviewSectionHeader("Account")
                        Spacer(Modifier.height(8.dp))
                        SettingsValueRow("Account Balance", euro(portfolio?.totalValueEur ?: BigDecimal.ZERO))
                        SettingsValueRow("Currency", "EUR")
                        SettingsValueRow("Server Time", previewTimeFormatter.format(java.time.ZonedDateTime.now()))
                    }
                }
            }
            item {
                PreviewCardBox {
                    Column {
                        PreviewSectionHeader("Data & System")
                        Spacer(Modifier.height(4.dp))
                        SettingsNavRow("Backup & Recovery") { onOpen(AppTab.BACKUP) }
                        SettingsNavRow("CloudShare") { onOpen(AppTab.CLOUDSHARE_SETTINGS) }
                        SettingsNavRow("System Diagnostics") { onOpen(AppTab.SYSTEM_TEST) }
                        SettingsNavRow("Notifications") { onOpen(AppTab.NOTIFICATIONS) }
                    }
                }
            }
        } else {
            item {
                PreviewCardBox {
                    Column {
                        PreviewSectionHeader("Automation")
                        Spacer(Modifier.height(5.dp))
                        SettingsToggleRow("Enable Auto Trading", settings.ultimateAutomationEnabled) { onPersist(settings.copy(ultimateAutomationEnabled = it)) }
                        SettingsToggleRow("AI Auto Trading", settings.autoTradeMultipleSymbolsPerScan) { onPersist(settings.copy(autoTradeMultipleSymbolsPerScan = it)) }
                        SettingsToggleRow("Auto Exit Manager", settings.autoExitManagerEnabled) { onPersist(settings.copy(autoExitManagerEnabled = it)) }
                        SettingsToggleRow("Auto Stop Loss", settings.autoStopLossEnabled) { onPersist(settings.copy(autoStopLossEnabled = it)) }
                        SettingsToggleRow("Auto Take Profit", settings.autoTakeProfitEnabled) { onPersist(settings.copy(autoTakeProfitEnabled = it)) }
                        SettingsToggleRow("Trailing Stop", settings.enableTrailingStop) { onPersist(settings.copy(enableTrailingStop = it)) }
                    }
                }
            }
            item {
                PreviewCardBox {
                    Column {
                        PreviewSectionHeader("Risk Management")
                        Spacer(Modifier.height(7.dp))
                        SettingsValueRow("Max Position (EUR)", euro(settings.maxPositionEur))
                        SettingsValueRow("Max Daily Loss (EUR)", euro(settings.maxDailyLossEur))
                        SettingsValueRow("Max Trades Per Day", settings.maxTradesPerDay.toString())
                        SettingsValueRow("Max Trades Per Hour", settings.maxTradesPerHour.toString())
                        SettingsValueRow("Max Active Positions", settings.maxSimultaneousLivePositions.toString())
                        SettingsNavRow("Advanced Risk Controls") { onOpen(AppTab.ADVANCED_SETTINGS) }
                    }
                }
            }
        }
    }
}

private fun previewExchangeStatus(settings: BotSettings, portfolio: PortfolioSnapshot?): String = when (settings.exchangeProvider) {
    ExchangeProvider.KRAKEN -> if (portfolio != null) "Connected" else "Not verified"
    ExchangeProvider.PAPER -> "Paper"
    ExchangeProvider.BINANCE_READ_ONLY -> "Read Only"
    ExchangeProvider.MANUAL -> "Manual"
    else -> "Configured"
}

@Composable
private fun PreviewSettingsSegment(labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            Surface(
                modifier = Modifier.weight(1f).height(36.dp).clickable { onSelected(index) },
                color = if (selected == index) PreviewCardAlt else Color.Transparent,
                shape = RoundedCornerShape(5.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (selected == index) PreviewPurple else PreviewDivider)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected == index) PreviewPurpleSoft else PreviewMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun SettingsValueRow(label: String, value: String, valueColor: Color = PreviewText) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = PreviewText, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SettingsNavRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = PreviewText, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, null, tint = PreviewMuted, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun SettingsToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = PreviewText, fontSize = 10.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            modifier = Modifier.size(width = 42.dp, height = 26.dp),
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PreviewGreen, uncheckedThumbColor = PreviewMuted, uncheckedTrackColor = PreviewDivider)
        )
    }
}

@Composable
fun PreviewSystemTestScreen(
    settings: BotSettings,
    lines: List<String>,
    diagnosticsDirectoryPath: String,
    onDiagnosticsDirectoryPathChanged: (String) -> Unit,
    onOpen: (AppTab) -> Unit,
    onRun: () -> Unit,
    onRunAndSave: (String, (String) -> Unit) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var diagnosticsStatus by remember { mutableStateOf("Ready") }
    var selectedDiagnosticsDirectory by remember(diagnosticsDirectoryPath) { mutableStateOf(diagnosticsDirectoryPath) }
    val diagnosticsFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { selected ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    selected,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            selectedDiagnosticsDirectory = selected.toString()
            onDiagnosticsDirectoryPathChanged(selectedDiagnosticsDirectory)
            diagnosticsStatus = "Diagnostics destination selected"
        }
    }

    val pass = lines.count { it.startsWith("PASS") }
    val fail = lines.count { it.startsWith("FAIL") }
    val warn = lines.count { it.startsWith("WARN") }
    val total = max(1, pass + fail + warn)
    val score = ((pass * 100f) / total).coerceIn(0f, 100f)
    val healthy = fail == 0 && lines.isNotEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            PreviewSettingsSegment(listOf("Backup & Recovery", "System"), 1) {
                if (it == 0) onOpen(AppTab.BACKUP)
            }
        }
        item {
            PreviewCardBox {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (healthy) Icons.Rounded.Security else Icons.Rounded.WarningAmber,
                            null,
                            tint = if (healthy) PreviewGreen else PreviewOrange,
                            modifier = Modifier.size(35.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (healthy) "ALL SYSTEMS OPERATIONAL"
                            else if (lines.isEmpty()) "SYSTEM DIAGNOSTICS NOT RUN"
                            else "SYSTEM CHECK REQUIRED",
                            color = if (healthy) PreviewGreen else PreviewOrange,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("Last Full Test", color = PreviewMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
                        Text(
                            if (lines.isEmpty()) "—" else previewTimeFormatter.format(java.time.ZonedDateTime.now()),
                            color = PreviewText,
                            fontSize = 9.sp
                        )
                    }
                    Spacer(Modifier.height(9.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("Overall Health", color = PreviewMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))
                        Text(
                            "${score.toInt()}%",
                            color = if (healthy) PreviewGreen else PreviewOrange,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress = score / 100f,
                        modifier = Modifier.fillMaxWidth().height(5.dp),
                        color = if (healthy) PreviewGreen else PreviewOrange,
                        trackColor = PreviewDivider
                    )
                }
            }
        }
        item {
            PreviewCardBox {
                Column {
                    PreviewSectionHeader("Systems")
                    Spacer(Modifier.height(5.dp))
                    if (lines.isEmpty()) {
                        SystemLine("Settings & Persistence", "PENDING")
                        SystemLine("News & Data Providers", "PENDING")
                        SystemLine("AI & Research Engine", "PENDING")
                        SystemLine("M3 Governance", "PENDING")
                        SystemLine("M4 Execution Guard", "PENDING")
                        SystemLine("Lifecycle & Risk", "PENDING")
                        SystemLine("Learning & Journal", "PENDING")
                        SystemLine("CloudShare & Recovery", "PENDING")
                    } else {
                        lines.take(24).forEach { line ->
                            val parts = line.split("|").map { it.trim() }
                            SystemLine(parts.getOrNull(1) ?: line.take(40), parts.firstOrNull() ?: "INFO")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    PreviewOutlineButton(
                        if (lines.isEmpty()) "Run Full System Test" else "Run System Test Again",
                        onRun,
                        Modifier.fillMaxWidth()
                    )
                }
            }
        }
        item {
            PreviewCardBox {
                Column {
                    PreviewSectionHeader("Full App Diagnostics")
                    Spacer(Modifier.height(7.dp))
                    SettingsValueRow(
                        "Diagnostics Folder",
                        selectedDiagnosticsDirectory.ifBlank { "Default app diagnostics folder" }
                    )
                    SettingsValueRow(
                        "Report Contents",
                        "System + runtime + trading + provider health"
                    )
                    Spacer(Modifier.height(7.dp))
                    PreviewOutlineButton(
                        "Select Diagnostics Folder",
                        { diagnosticsFolderPicker.launch(null) },
                        Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(7.dp))
                    PreviewPrimaryButton(
                        "Run & Save Full Diagnostics",
                        {
                            diagnosticsStatus = "Running full diagnostics..."
                            onRunAndSave(selectedDiagnosticsDirectory) { result ->
                                diagnosticsStatus = result
                            }
                        },
                        Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(9.dp))
                    Text(
                        diagnosticsStatus,
                        color = if (diagnosticsStatus.contains("fail", true) || diagnosticsStatus.contains("error", true)) PreviewRed else PreviewGreen,
                        fontSize = 9.sp,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The saved report excludes API keys, exchange secrets, Telegram/Discord credentials, news API keys and remote-control PINs.",
                        color = PreviewMuted,
                        fontSize = 8.sp
                    )
                }
            }
        }
        item {
            Text(
                "Mode ${settings.mode.name.replace('_', ' ')} • Provider ${settings.exchangeProvider.name.replace('_', ' ')}",
                color = PreviewMuted,
                fontSize = 9.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
@Composable
private fun SystemLine(label: String, status: String) {
    val normalized = status.uppercase()
    val color = when (normalized) { "PASS", "OK" -> PreviewGreen; "FAIL" -> PreviewRed; "WARN" -> PreviewOrange; else -> PreviewMuted }
    val icon = when (normalized) { "PASS", "OK" -> Icons.Rounded.CheckCircle; "FAIL" -> Icons.Rounded.ErrorOutline; "WARN" -> Icons.Rounded.WarningAmber; else -> Icons.Rounded.Sync }
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, color = PreviewText, fontSize = 9.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(normalized, color = color, fontSize = 9.sp)
    }
}

@Composable
fun PreviewPositionsScreen(settings: BotSettings, snapshot: LifecycleSnapshot?, onRefresh: () -> Unit) {
    val positions = snapshot?.positions.orEmpty().filter { it.quantity > BigDecimal.ZERO }
    val totalValue = positions.fold(BigDecimal.ZERO) { acc, p -> acc.add(p.currentPrice.multiply(p.quantity)) }
    val pnl = positions.fold(BigDecimal.ZERO) { acc, p -> acc.add(p.unrealizedPnlEur) }
    val pct = if (totalValue.subtract(pnl) > BigDecimal.ZERO) pnl.multiply(BigDecimal("100")).divide(totalValue.subtract(pnl), 4, RoundingMode.HALF_UP) else BigDecimal.ZERO
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 3.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Total Value", color = PreviewMuted, fontSize = 9.sp)
                    Text(euro(totalValue), color = PreviewGreen, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Open P/L", color = PreviewMuted, fontSize = 9.sp)
                    Text((if (pnl >= BigDecimal.ZERO) "+" else "") + euro(pnl) + " (" + (if (pct >= BigDecimal.ZERO) "+" else "") + pct(pct) + ")", color = if (pnl >= BigDecimal.ZERO) PreviewGreen else PreviewRed, fontSize = 9.sp)
                }
            }
        }
        if (positions.isEmpty()) item { PreviewEmptyCard("No active positions") }
        items(positions) { position ->
            PreviewCardBox {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AssetIcon(previewBase(position.symbol), 28)
                        Spacer(Modifier.width(9.dp))
                        Text(previewPair(position.symbol), color = PreviewText, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.ChevronRight, null, tint = PreviewMuted, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.height(9.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TinyValue("Amount", "${position.quantity.stripTrailingZeros().toPlainString()} ${previewBase(position.symbol)}")
                        TinyValue("Avg. Price", euro(position.entryPrice))
                        TinyValue("Current", euro(position.currentPrice), Alignment.End)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("P/L", color = PreviewMuted, fontSize = 8.sp)
                    Text((if (position.unrealizedPnlEur >= BigDecimal.ZERO) "+" else "") + euro(position.unrealizedPnlEur) + " (" + (if (position.unrealizedPnlPercent >= BigDecimal.ZERO) "+" else "") + pct(position.unrealizedPnlPercent) + ")", color = if (position.unrealizedPnlEur >= BigDecimal.ZERO) PreviewGreen else PreviewRed, fontSize = 10.sp)
                }
            }
        }
        item { PreviewOutlineButton("Refresh Positions", onRefresh, Modifier.fillMaxWidth()) }
        item { Text("Exit manager ${if (settings.autoExitManagerEnabled) "ON" else "OFF"} • Stop loss ${if (settings.autoStopLossEnabled) "ON" else "OFF"}", color = PreviewMuted, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
    }
}

@Composable
private fun TinyValue(label: String, value: String, alignment: Alignment.Horizontal = Alignment.Start) {
    Column(horizontalAlignment = alignment) {
        Text(label, color = PreviewMuted, fontSize = 8.sp)
        Text(value, color = PreviewText, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PreviewBackupRecoveryScreen(
    settings: BotSettings,
    onOpen: (AppTab) -> Unit,
    backupDirectoryPath: String,
    onBackupDirectoryPathChanged: (String) -> Unit,
    onExportFullBackup: (String, (String) -> Unit) -> Unit,
    onRestoreFullBackup: (String, Boolean, (String) -> Unit) -> Unit,
    onApplySafeDefaults: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cloudStore = remember { CloudShareSettingsStore(context) }
    var status by remember { mutableStateOf("Ready") }
    var selectedBackupDirectory by remember(backupDirectoryPath) { mutableStateOf(backupDirectoryPath) }
    var selectedRestore by remember { mutableStateOf("") }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { selected ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    selected,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            selectedBackupDirectory = selected.toString()
            onBackupDirectoryPathChanged(selectedBackupDirectory)
            status = "Backup destination selected"
        }
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedRestore = uri?.toString().orEmpty()
        if (selectedRestore.isNotBlank()) status = "Backup selected for restore"
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { PreviewSettingsSegment(listOf("Backup & Recovery", "System"), 0) { if (it == 1) onOpen(AppTab.SYSTEM_TEST) } }
        item {
            PreviewCardBox {
                Column {
                    PreviewSectionHeader("CloudShare Backup")
                    Spacer(Modifier.height(8.dp))
                    SettingsValueRow("Backup Folder", selectedBackupDirectory.ifBlank { "Default app backup folder" })
                    SettingsValueRow("CloudShare Status", if (cloudStore.enabled) "Enabled" else "Disabled", if (cloudStore.enabled) PreviewGreen else PreviewMuted)
                    Spacer(Modifier.height(7.dp))
                    PreviewOutlineButton("Open CloudShare Setup", { onOpen(AppTab.CLOUDSHARE_SETTINGS) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(7.dp))
                    PreviewOutlineButton("Select Backup Folder", { folderPicker.launch(null) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(7.dp))
                    PreviewOutlineButton("Run Backup Now", {
                        onExportFullBackup(selectedBackupDirectory) { result -> status = result }
                    }, Modifier.fillMaxWidth())
                }
            }
        }
        item {
            PreviewCardBox {
                Column {
                    PreviewSectionHeader("Recovery")
                    Spacer(Modifier.height(8.dp))
                    SettingsValueRow("Selected Backup", if (selectedRestore.isBlank()) "None" else "Ready")
                    SettingsValueRow("Restore Mode", "Manual")
                    Spacer(Modifier.height(7.dp))
                    PreviewOutlineButton("Select Backup File", { filePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(7.dp))
                    PreviewOutlineButton("Run Restore Now", {
                        if (selectedRestore.isBlank()) status = "Select a backup file first"
                        else onRestoreFullBackup(selectedRestore, false) { result -> status = result }
                    }, Modifier.fillMaxWidth())
                }
            }
        }
        item {
            PreviewCardBox {
                Column {
                    Text(status, color = if (status.contains("error", true) || status.contains("fail", true)) PreviewRed else PreviewGreen, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    PreviewOutlineButton("Apply Safe Defaults", onApplySafeDefaults, Modifier.fillMaxWidth())
                    Text("Mode=${settings.mode.name.replace('_', ' ')} • Local data remains authoritative when CloudShare is disabled.", color = PreviewMuted, fontSize = 8.sp, modifier = Modifier.padding(top = 7.dp))
                }
            }
        }
    }
}
