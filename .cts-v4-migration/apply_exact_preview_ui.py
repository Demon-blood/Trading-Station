#!/usr/bin/env python3
from __future__ import annotations
import sys
from pathlib import Path

def fail(msg: str) -> None:
    raise SystemExit("[CTS exact preview UI] " + msg)

def replace_once(text: str, old: str, new: str, label: str) -> str:
    n = text.count(old)
    if n != 1:
        fail(f"{label}: expected 1 match, found {n}")
    return text.replace(old, new, 1)

def replace_composable(text: str, name: str, replacement: str) -> str:
    marker = "@Composable\nprivate fun " + name
    start = text.find(marker)
    if start < 0:
        fail(f"Composable {name} not found")
    nxt = text.find("\n@Composable\nprivate fun ", start + len(marker))
    if nxt < 0:
        fail(f"Composable {name} end not found")
    return text[:start] + replacement.rstrip() + "\n\n" + text[nxt + 1:]

def patch_imports(text: str) -> str:
    if "import androidx.compose.foundation.clickable\n" not in text:
        text = text.replace(
            "import androidx.compose.foundation.background\n",
            "import androidx.compose.foundation.background\nimport androidx.compose.foundation.clickable\n",
            1,
        )
    if "import androidx.compose.material3.Switch\n" not in text:
        text = text.replace(
            "import androidx.compose.material3.Surface\n",
            "import androidx.compose.material3.Surface\nimport androidx.compose.material3.Switch\n",
            1,
        )
    return text

HEADER = r"""@Composable
private fun HeaderBar(currentTab: AppTab, status: String, mode: BotMode, level: String) {
    val title = when (currentTab) {
        AppTab.DASHBOARD -> "Dashboard"
        AppTab.PORTFOLIO, AppTab.POSITIONS, AppTab.ORDERS, AppTab.HISTORY -> "Portfolio"
        AppTab.AI, AppTab.AI_SIGNALS, AppTab.STRATEGY, AppTab.BACKTEST, AppTab.REGIME,
        AppTab.PERFORMANCE, AppTab.SANDBOX, AppTab.PORTFOLIO_ROTATION, AppTab.AUTO_TUNER,
        AppTab.SELF_LEARNING, AppTab.SELF_LEARNING_MAIN, AppTab.LEARNING_INSPECTOR,
        AppTab.SMART_EXIT, AppTab.RESEARCH_SETTINGS -> "AI & Research"
        AppTab.NEWS -> "News & Intelligence"
        AppTab.SETTINGS, AppTab.BASIC_SETTINGS, AppTab.ADVANCED_SETTINGS, AppTab.BACKUP,
        AppTab.CLOUDSHARE_SETTINGS, AppTab.RECOVERY_TOOLS, AppTab.SYSTEM_TEST, AppTab.HEALTH,
        AppTab.REMOTE_ALERTS, AppTab.NOTIFICATIONS, AppTab.NOTIFICATION_LOGS,
        AppTab.KRAKEN_HEALTH, AppTab.RISK -> "Settings"
        else -> currentTab.label
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(22.dp)) {
            drawLine(TextPrimary, Offset(2f, size.height * .30f), Offset(size.width - 2f, size.height * .30f), 2.2f, StrokeCap.Round)
            drawLine(TextPrimary, Offset(2f, size.height * .50f), Offset(size.width * .72f, size.height * .50f), 2.2f, StrokeCap.Round)
            drawLine(TextPrimary, Offset(2f, size.height * .70f), Offset(size.width * .88f, size.height * .70f), 2.2f, StrokeCap.Round)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            if (currentTab == AppTab.DASHBOARD) {
                Text(status, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        StatusDot(levelColor(level))
        Spacer(Modifier.width(8.dp))
        Text(
            if (mode == BotMode.PAPER) "PAPER" else if (mode == BotMode.LIVE_AUTO) "LIVE" else "CONFIRM",
            color = modeColor(mode),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PremiumNavIcon(tab: AppTab, selected: Boolean) {
    val c = if (selected) Electric else Muted
    Canvas(Modifier.size(22.dp)) {
        val w = size.width
        val h = size.height
        when (tab) {
            AppTab.DASHBOARD -> {
                val p = Path().apply {
                    moveTo(w * .15f, h * .48f); lineTo(w * .50f, h * .20f); lineTo(w * .85f, h * .48f)
                    moveTo(w * .25f, h * .43f); lineTo(w * .25f, h * .82f); lineTo(w * .75f, h * .82f); lineTo(w * .75f, h * .43f)
                }
                drawPath(p, c, style = DrawStroke(width = 2.2f, cap = StrokeCap.Round))
            }
            AppTab.PORTFOLIO -> {
                drawRoundRect(c, topLeft = Offset(w*.16f,h*.28f), size = androidx.compose.ui.geometry.Size(w*.68f,h*.52f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f,4f), style = DrawStroke(2.2f))
                drawLine(c, Offset(w*.35f,h*.28f), Offset(w*.35f,h*.18f), 2.2f)
                drawLine(c, Offset(w*.35f,h*.18f), Offset(w*.66f,h*.18f), 2.2f)
                drawLine(c, Offset(w*.66f,h*.18f), Offset(w*.66f,h*.28f), 2.2f)
            }
            AppTab.AI -> {
                drawCircle(c, radius = w*.26f, center = Offset(w*.5f,h*.5f), style = DrawStroke(2.2f))
                drawCircle(c, radius = 2.4f, center = Offset(w*.42f,h*.46f))
                drawCircle(c, radius = 2.4f, center = Offset(w*.60f,h*.46f))
                drawLine(c, Offset(w*.40f,h*.62f), Offset(w*.60f,h*.62f), 2.0f, StrokeCap.Round)
                drawLine(c, Offset(w*.50f,h*.08f), Offset(w*.50f,h*.22f), 2f)
            }
            AppTab.NEWS -> {
                drawRoundRect(c, topLeft = Offset(w*.18f,h*.15f), size = androidx.compose.ui.geometry.Size(w*.64f,h*.70f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f,3f), style = DrawStroke(2.2f))
                drawLine(c, Offset(w*.28f,h*.33f), Offset(w*.70f,h*.33f), 2f)
                drawLine(c, Offset(w*.28f,h*.49f), Offset(w*.70f,h*.49f), 2f)
                drawLine(c, Offset(w*.28f,h*.65f), Offset(w*.58f,h*.65f), 2f)
            }
            AppTab.SETTINGS -> {
                drawCircle(c, radius = w*.22f, center = Offset(w*.5f,h*.5f), style = DrawStroke(2.2f))
                drawCircle(c, radius = w*.07f, center = Offset(w*.5f,h*.5f), style = DrawStroke(2f))
            }
            else -> drawCircle(c, radius = w*.23f, center = Offset(w*.5f,h*.5f), style = DrawStroke(2.2f))
        }
    }
}"""

TABS = r"""@Composable
private fun AppTabs(currentTab: AppTab, onTabSelected: (AppTab) -> Unit) {
    val root = when (currentTab) {
        AppTab.PORTFOLIO, AppTab.POSITIONS, AppTab.ORDERS, AppTab.HISTORY, AppTab.TAX -> AppTab.PORTFOLIO
        AppTab.AI, AppTab.AI_SIGNALS, AppTab.STRATEGY, AppTab.BACKTEST, AppTab.REGIME,
        AppTab.PERFORMANCE, AppTab.SANDBOX, AppTab.PORTFOLIO_ROTATION, AppTab.AUTO_TUNER,
        AppTab.SELF_LEARNING, AppTab.SELF_LEARNING_MAIN, AppTab.LEARNING_INSPECTOR,
        AppTab.SMART_EXIT, AppTab.RESEARCH_SETTINGS -> AppTab.AI
        AppTab.NEWS -> AppTab.NEWS
        AppTab.SETTINGS, AppTab.BASIC_SETTINGS, AppTab.ADVANCED_SETTINGS, AppTab.BACKUP,
        AppTab.CLOUDSHARE_SETTINGS, AppTab.RECOVERY_TOOLS, AppTab.SYSTEM_TEST, AppTab.HEALTH,
        AppTab.REMOTE_ALERTS, AppTab.NOTIFICATIONS, AppTab.NOTIFICATION_LOGS,
        AppTab.KRAKEN_HEALTH, AppTab.RISK, AppTab.STATUS, AppTab.BOT, AppTab.SYMBOLS -> AppTab.SETTINGS
        else -> AppTab.DASHBOARD
    }
    Surface(color = Color(0xFF08111D), border = BorderStroke(1.dp, Stroke), shadowElevation = 10.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(AppTab.DASHBOARD, AppTab.PORTFOLIO, AppTab.AI, AppTab.NEWS, AppTab.SETTINGS).forEach { tab ->
                val selected = root == tab
                Column(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable { onTabSelected(tab) }.padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    PremiumNavIcon(tab, selected)
                    Text(
                        when (tab) {
                            AppTab.DASHBOARD -> "Dashboard"
                            AppTab.PORTFOLIO -> "Portfolio"
                            AppTab.AI -> "AI"
                            AppTab.NEWS -> "News"
                            else -> "Settings"
                        },
                        color = if (selected) Electric else Muted,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }
    }
}"""

DASH = r"""@Composable
private fun DashboardScreen(
    settings: BotSettings,
    status: String,
    decisions: List<AiDecision>,
    activePositionSymbols: List<String>,
    portfolioSnapshot: PortfolioSnapshot?,
    lifecycleSnapshot: LifecycleSnapshot?,
    trades: List<TradeEntity>,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onScan: () -> Unit,
    onExecute: () -> Unit,
    onOpenNews: () -> Unit
) {
    val total = portfolioSnapshot?.totalValueEur ?: BigDecimal.ZERO
    val free = portfolioSnapshot?.freeEur ?: BigDecimal.ZERO
    val invested = total.subtract(free).max(BigDecimal.ZERO)
    val realized24h = trades.filter { it.timestampEpochMs >= System.currentTimeMillis() - 86_400_000L }
        .mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }
        .fold(BigDecimal.ZERO) { a, b -> a + b }
    val positions = lifecycleSnapshot?.positions.orEmpty()
    val chartValues = remember(total, trades) { portfolioTrendValues(total, trades) }
    val dashboardDecisions = decisions.sortedByDescending { it.finalScore }.take(4)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            GlassCard {
                Text("Portfolio Value", color = Muted, style = MaterialTheme.typography.labelMedium)
                Text("€${total.setScale(2, RoundingMode.HALF_UP)}", color = Mint, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text(
                    "24H P/L   ${if (realized24h >= BigDecimal.ZERO) "+" else ""}€${realized24h.setScale(2, RoundingMode.HALF_UP)}",
                    color = if (realized24h >= BigDecimal.ZERO) Mint else Danger,
                    fontWeight = FontWeight.Bold
                )
                PremiumPortfolioAreaChart(chartValues)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PremiumMiniStat("Invested", "€${invested.setScale(2, RoundingMode.DOWN)}")
                    PremiumMiniStat("Available", "€${free.setScale(2, RoundingMode.DOWN)}")
                    PremiumMiniStat("Positions", positions.size.toString())
                }
            }
        }
        item {
            GlassCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Active Positions", fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    Text(positions.size.toString(), color = Muted)
                }
                if (positions.isEmpty()) Text("No active positions", color = Muted)
                positions.take(5).forEach { PremiumPositionSummary(it) }
            }
        }
        if (dashboardDecisions.isNotEmpty()) {
            item {
                GlassCard {
                    Text("Top AI Signals", fontWeight = FontWeight.ExtraBold)
                    dashboardDecisions.forEach { PremiumSignalRow(it) }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onScan, modifier = Modifier.weight(1f)) { Text("Scan") }
                Button(onClick = onStart, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Mint, contentColor = Color(0xFF06130F))) { Text("Start Bot") }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Stop") }
            }
        }
    }
}

private fun portfolioTrendValues(total: BigDecimal, trades: List<TradeEntity>): List<BigDecimal> {
    val realized = trades
        .filter { it.side.uppercase().contains("SELL") }
        .sortedBy { it.timestampEpochMs }
        .map { it.realizedPnlEur.toBigDecimalOrNull() ?: BigDecimal.ZERO }
        .takeLast(18)
    if (realized.isEmpty()) return List(8) { total.max(BigDecimal.ONE) }
    val sum = realized.fold(BigDecimal.ZERO) { a, b -> a + b }
    var running = total.subtract(sum)
    val out = mutableListOf(running)
    realized.forEach { running = running.add(it); out += running }
    return out
}

@Composable
private fun PremiumPortfolioAreaChart(values: List<BigDecimal>) {
    val safe = values.ifEmpty { listOf(BigDecimal.ONE, BigDecimal.ONE) }
    Canvas(Modifier.fillMaxWidth().height(105.dp)) {
        val min = safe.minOrNull() ?: BigDecimal.ZERO
        val max = safe.maxOrNull() ?: BigDecimal.ONE
        val range = max.subtract(min).takeIf { it > BigDecimal.ZERO } ?: BigDecimal.ONE
        val line = Path()
        safe.forEachIndexed { i, v ->
            val x = if (safe.size <= 1) 0f else size.width * i / safe.lastIndex.toFloat()
            val ratio = v.subtract(min).divide(range, 8, RoundingMode.HALF_UP).toFloat()
            val y = size.height * (.82f - ratio * .66f)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val area = Path().apply {
            addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close()
        }
        drawPath(area, brush = Brush.verticalGradient(listOf(Mint.copy(alpha=.28f), Mint.copy(alpha=.02f))))
        drawPath(line, color = Mint, style = DrawStroke(width = 3.2f, cap = StrokeCap.Round))
    }
}

@Composable
private fun PremiumMiniStat(label: String, value: String) {
    Column {
        Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PremiumPositionSummary(p: com.ksp.cryptobot.core.PositionInfo) {
    val pnlColor = if (p.unrealizedPnlEur >= BigDecimal.ZERO) Mint else Danger
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = pnlColor.copy(alpha=.13f), border = BorderStroke(1.dp, pnlColor.copy(alpha=.30f))) {
            Text(p.baseAsset.take(1), modifier = Modifier.padding(horizontal=9.dp, vertical=5.dp), color=pnlColor, fontWeight=FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(p.symbol.replace("EUR", "/EUR"), fontWeight = FontWeight.Bold)
            Text(p.baseAsset, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("€${p.currentPrice.multiply(p.quantity).setScale(2, RoundingMode.HALF_UP)}", fontWeight = FontWeight.Bold)
            Text("${if (p.unrealizedPnlPercent >= BigDecimal.ZERO) "+" else ""}${p.unrealizedPnlPercent.setScale(2, RoundingMode.HALF_UP)}%", color = pnlColor, style = MaterialTheme.typography.labelSmall)
        }
    }
}"""

PORTFOLIO = r"""@Composable
private fun PortfolioScreen(
    settings: BotSettings,
    snapshot: PortfolioSnapshot?,
    lifecycleSnapshot: LifecycleSnapshot?,
    onRefresh: () -> Unit,
    onOpen: (AppTab) -> Unit
) {
    val assets = snapshot?.assets.orEmpty().filter { it.eurValue > BigDecimal.ZERO }
    val total = snapshot?.totalValueEur ?: BigDecimal.ZERO
    val positions = lifecycleSnapshot?.positions.orEmpty()
    val colors = listOf(Electric, Color(0xFF63B3FF), Color(0xFFFFA726), Mint, Color(0xFF8C9AAF))
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 2.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            PremiumSegmentTabs(
                labels = listOf("Positions","Orders","History","Allocations"),
                selected = 0,
                onSelect = { i ->
                    when (i) {
                        1 -> onOpen(AppTab.ORDERS)
                        2 -> onOpen(AppTab.HISTORY)
                        else -> Unit
                    }
                }
            )
        }
        item {
            GlassCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Total Value", color = Muted, style = MaterialTheme.typography.labelMedium)
                        Text("€${total.setScale(2,RoundingMode.HALF_UP)}", color = Mint, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                    }
                    OutlinedButton(onClick = onRefresh) { Text("Refresh") }
                }
                AllocationDonut(assets.map { it.eurValue }, colors)
                assets.take(6).forEachIndexed { idx, a ->
                    val pct = if (total > BigDecimal.ZERO) a.eurValue.multiply(BigDecimal("100")).divide(total, 1, RoundingMode.HALF_UP) else BigDecimal.ZERO
                    Row(Modifier.fillMaxWidth().padding(vertical=3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(colors[idx % colors.size]))
                        Spacer(Modifier.width(8.dp))
                        Text(a.asset, modifier = Modifier.weight(1f), fontWeight=FontWeight.Bold)
                        Text("${pct}%", color = Muted, modifier=Modifier.width(58.dp))
                        Text("€${a.eurValue.setScale(2,RoundingMode.HALF_UP)}", fontWeight=FontWeight.Bold)
                    }
                }
            }
        }
        item {
            GlassCard {
                Text("Active Positions", fontWeight=FontWeight.ExtraBold)
                if (positions.isEmpty()) Text("No active positions", color=Muted)
                positions.forEach { PremiumPositionSummary(it) }
            }
        }
    }
}

@Composable
private fun AllocationDonut(values: List<BigDecimal>, colors: List<Color>) {
    val total = values.fold(BigDecimal.ZERO) { a,b -> a+b }
    Box(Modifier.fillMaxWidth().height(176.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(142.dp)) {
            if (total <= BigDecimal.ZERO) {
                drawArc(Stroke, -90f, 360f, false, style = DrawStroke(width=24f, cap=StrokeCap.Butt))
            } else {
                var start = -90f
                values.take(6).forEachIndexed { i, v ->
                    val sweep = v.divide(total, 8, RoundingMode.HALF_UP).multiply(BigDecimal("360")).toFloat()
                    drawArc(colors[i % colors.size], start, sweep, false, style = DrawStroke(width=24f, cap=StrokeCap.Butt))
                    start += sweep
                }
            }
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally) {
            Text("Asset", color=Muted, style=MaterialTheme.typography.labelSmall)
            Text("Allocation", fontWeight=FontWeight.ExtraBold)
        }
    }
}"""

AI = r"""@Composable
private fun AiHubScreen(
    decisions: List<AiDecision>,
    settings: BotSettings,
    performanceLabSnapshot: PerformanceLabSnapshot?,
    onOpen: (AppTab) -> Unit,
    onRefreshPerformance: () -> Unit
) {
    val avg = if (decisions.isEmpty()) 50 else decisions.map { it.finalScore }.average().toInt()
    val buys = decisions.count { it.finalAction == SignalAction.BUY || it.finalAction == SignalAction.SMALL_BUY }
    val sells = decisions.count { it.finalAction == SignalAction.SELL }
    val bias = when {
        buys > sells && avg >= 55 -> "BULLISH"
        sells > buys && avg <= 45 -> "BEARISH"
        else -> "NEUTRAL"
    }
    val biasColor = when (bias) { "BULLISH" -> Mint; "BEARISH" -> Danger; else -> Amber }
    val candidates = performanceLabSnapshot?.candidates.orEmpty()
    val win = if (candidates.isEmpty()) 0 else candidates.map { if (it.liveTrades > 0) it.liveWinRatePercent else it.paperWinRatePercent }.average().toInt()
    val pf = candidates.maxOfOrNull { if (it.liveTrades > 0) it.liveProfitFactor else it.paperProfitFactor } ?: BigDecimal.ZERO

    LazyColumn(
        modifier=Modifier.fillMaxSize().padding(horizontal=16.dp),
        contentPadding=PaddingValues(top=2.dp,bottom=18.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        item {
            PremiumSegmentTabs(listOf("AI Signals","Research","Backtest"), 0) { i ->
                when(i){ 0->onOpen(AppTab.AI_SIGNALS); 1->onOpen(AppTab.RESEARCH_SETTINGS); 2->onOpen(AppTab.BACKTEST) }
            }
        }
        item {
            GlassCard {
                Text("Market Bias", color=Muted, style=MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(bias, color=biasColor, style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.ExtraBold)
                        Text("Confidence", color=Muted, style=MaterialTheme.typography.labelSmall)
                    }
                    ConfidenceGauge(avg.coerceIn(0,100), biasColor)
                }
            }
        }
        item {
            GlassCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                    Text("Top AI Signals", fontWeight=FontWeight.ExtraBold, modifier=Modifier.weight(1f))
                    TextButton(onClick={onOpen(AppTab.AI_SIGNALS)}) { Text("View All") }
                }
                decisions.sortedByDescending { it.finalScore }.take(5).forEach { PremiumSignalRow(it) }
                if (decisions.isEmpty()) Text("Run an AI scan to populate live signals.", color=Muted)
            }
        }
        item {
            GlassCard {
                Text("AI Performance (30D)", fontWeight=FontWeight.ExtraBold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
                    Column { Text("Win Rate", color=Muted, style=MaterialTheme.typography.labelSmall); Text("${win}%", color=Mint, style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.ExtraBold) }
                    Column(horizontalAlignment=Alignment.End) { Text("Profit Factor", color=Muted, style=MaterialTheme.typography.labelSmall); Text(pf.setScale(2,RoundingMode.HALF_UP).toPlainString(), color=Mint, style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.ExtraBold) }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick=onRefreshPerformance, modifier=Modifier.fillMaxWidth()) { Text("Refresh Performance") }
            }
        }
    }
}

@Composable
private fun ConfidenceGauge(value: Int, color: Color) {
    Box(Modifier.size(88.dp), contentAlignment=Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(Stroke, 145f, 250f, false, style=DrawStroke(width=9f, cap=StrokeCap.Round))
            drawArc(color, 145f, 250f*(value.coerceIn(0,100)/100f), false, style=DrawStroke(width=9f, cap=StrokeCap.Round))
        }
        Text("$value%", fontWeight=FontWeight.ExtraBold)
    }
}

@Composable
private fun PremiumSignalRow(d: AiDecision) {
    val c = actionColor(d.finalAction)
    Row(Modifier.fillMaxWidth().padding(vertical=6.dp), verticalAlignment=Alignment.CenterVertically) {
        Surface(shape=CircleShape, color=c.copy(alpha=.12f)) { Text(d.symbol.take(1), modifier=Modifier.padding(horizontal=8.dp,vertical=4.dp),color=c,fontWeight=FontWeight.Bold) }
        Spacer(Modifier.width(9.dp))
        Text(d.symbol.replace("EUR","/EUR"), modifier=Modifier.weight(1f), fontWeight=FontWeight.Bold)
        Text(d.finalAction.name.replace('_',' '), color=c, style=MaterialTheme.typography.labelMedium, fontWeight=FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text("${d.confidencePercent}%", color=Muted, style=MaterialTheme.typography.labelMedium)
    }
}"""

NEWS = r"""@Composable
private fun NewsScreen(
    settings: BotSettings,
    newsHistory: List<NewsArticleEntity>,
    activeSymbols: List<String>,
    decisions: List<AiDecision>,
    onToggleNews: (Boolean) -> Unit,
    onRefreshHistory: (String) -> Unit,
    onScanNews: (String) -> Unit
) {
    var selectedSymbol by remember(settings.symbolsCsv, activeSymbols) {
        mutableStateOf((activeSymbols + settings.symbols()).firstOrNull()?.uppercase()?.replace("/","")?.replace("-","") ?: "")
    }
    val symbols = (settings.symbols() + activeSymbols + newsHistory.map { it.symbol }).map { it.uppercase().replace("/","").replace("-","") }.filter { it.isNotBlank() }.distinct()
    val visible = if (selectedSymbol.isBlank()) newsHistory else newsHistory.filter { it.symbol.equals(selectedSymbol,true) }
    val avgNews = if (decisions.isEmpty()) 0 else decisions.map { it.newsScore }.average().toInt()
    val sentiment = when { avgNews >= 8 -> "BULLISH"; avgNews <= -8 -> "BEARISH"; else -> "NEUTRAL" }
    val sentimentColor = when(sentiment) { "BULLISH"->Mint; "BEARISH"->Danger; else->Amber }
    val sentimentScore = (50 + avgNews).coerceIn(0,100)

    LazyColumn(
        modifier=Modifier.fillMaxSize().padding(horizontal=16.dp),
        contentPadding=PaddingValues(top=2.dp,bottom=18.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        item {
            GlassCard {
                Text("Overall News Sentiment", color=Muted, style=MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                    Text(sentiment, color=sentimentColor, style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.ExtraBold, modifier=Modifier.weight(1f))
                    Text("$sentimentScore/100", fontWeight=FontWeight.Bold)
                }
                LinearProgressIndicator(progress=sentimentScore/100f, modifier=Modifier.fillMaxWidth().height(5.dp), color=sentimentColor, trackColor=Stroke)
            }
        }
        item {
            GlassCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                    Text("Top Stories", fontWeight=FontWeight.ExtraBold, modifier=Modifier.weight(1f))
                    Switch(checked=settings.useNewsAi, onCheckedChange=onToggleNews)
                }
                visible.take(6).forEach { article ->
                    Row(Modifier.fillMaxWidth().padding(vertical=7.dp), verticalAlignment=Alignment.CenterVertically) {
                        Surface(shape=CircleShape,color=Electric.copy(alpha=.13f)) {
                            Text(article.symbol.take(1).ifBlank { "N" }, modifier=Modifier.padding(horizontal=9.dp,vertical=5.dp), color=Electric, fontWeight=FontWeight.Bold)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(article.title, fontWeight=FontWeight.Bold, maxLines=2, overflow=TextOverflow.Ellipsis)
                            Text(article.source.ifBlank { article.provider }, color=Muted, style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                if (visible.isEmpty()) Text("No cached stories yet.", color=Muted)
            }
        }
        item {
            LazyRow(horizontalArrangement=Arrangement.spacedBy(7.dp)) {
                item { FilterChip(selected=selectedSymbol.isBlank(), onClick={selectedSymbol=""; onRefreshHistory("")}, label={Text("ALL")}) }
                items(symbols) { s -> FilterChip(selected=selectedSymbol.equals(s,true), onClick={selectedSymbol=s;onRefreshHistory(s)}, label={Text(s)}) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={onScanNews(selectedSymbol)}, modifier=Modifier.weight(1f), colors=ButtonDefaults.buttonColors(containerColor=Electric)) { Text("Scan News") }
                OutlinedButton(onClick={onRefreshHistory(selectedSymbol)}, modifier=Modifier.weight(1f)) { Text("Refresh") }
            }
        }
    }
}"""

SETTINGS = r"""@Composable
private fun SettingsHubScreen(
    settings: BotSettings,
    systemTestLines: List<String>,
    portfolioSnapshot: PortfolioSnapshot?,
    onOpen: (AppTab) -> Unit,
    onModeChange: (BotMode) -> Unit,
    onLiveAckChange: (Boolean) -> Unit,
    onRunSystemTest: () -> Unit,
    onApplySafeDefaults: () -> Unit
) {
    var section by remember { mutableStateOf(0) }
    LazyColumn(
        modifier=Modifier.fillMaxSize().padding(horizontal=16.dp),
        contentPadding=PaddingValues(top=2.dp,bottom=18.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        item { PremiumSegmentTabs(listOf("Connection & Trading","Automation & Risk"), section) { section=it } }
        if (section == 0) {
            item {
                GlassCard {
                    Text("Exchange Connection", fontWeight=FontWeight.ExtraBold)
                    PremiumSettingsRow("Exchange", settings.exchangeProvider.name.replace('_',' '), settings.exchangeProvider == ExchangeProvider.KRAKEN)
                    PremiumSettingsRow("Status", if (settings.exchangeProvider == ExchangeProvider.KRAKEN) "Connected / configured" else settings.exchangeProvider.name, true)
                    PremiumSettingsAction("API Management") { onOpen(AppTab.BASIC_SETTINGS) }
                }
            }
            item {
                GlassCard {
                    Text("Trading Mode", fontWeight=FontWeight.ExtraBold)
                    PremiumSettingsSwitch("Live Trading", settings.mode == BotMode.LIVE_AUTO) { enabled -> onModeChange(if(enabled) BotMode.LIVE_AUTO else BotMode.LIVE_CONFIRM) }
                    PremiumSettingsSwitch("Paper Trading", settings.mode == BotMode.PAPER) { enabled -> if(enabled) onModeChange(BotMode.PAPER) }
                    PremiumSettingsSwitch("Live Acknowledgement", settings.liveTradingAcknowledged, onLiveAckChange)
                }
            }
            item {
                GlassCard {
                    Text("Account", fontWeight=FontWeight.ExtraBold)
                    PremiumSettingsRow("Account Balance", "€${(portfolioSnapshot?.totalValueEur ?: BigDecimal.ZERO).setScale(2,RoundingMode.HALF_UP)}", false)
                    PremiumSettingsRow("Available EUR", "€${(portfolioSnapshot?.freeEur ?: BigDecimal.ZERO).setScale(2,RoundingMode.HALF_UP)}", false)
                    PremiumSettingsRow("Currency", "EUR", false)
                }
            }
        } else {
            item {
                GlassCard {
                    Text("Automation", fontWeight=FontWeight.ExtraBold)
                    PremiumSettingsSwitch("Enable Auto Trading", settings.ultimateAutomationEnabled) { onOpen(AppTab.ADVANCED_SETTINGS) }
                    PremiumSettingsSwitch("AI Auto Trading", settings.autoSelectStrategy) { onOpen(AppTab.ADVANCED_SETTINGS) }
                    PremiumSettingsSwitch("Auto Exit Manager", settings.autoExitManagerEnabled) { onOpen(AppTab.ADVANCED_SETTINGS) }
                    PremiumSettingsSwitch("Auto Stop Loss", settings.autoStopLossEnabled) { onOpen(AppTab.ADVANCED_SETTINGS) }
                    PremiumSettingsSwitch("Auto Take Profit", settings.autoTakeProfitEnabled) { onOpen(AppTab.ADVANCED_SETTINGS) }
                    PremiumSettingsSwitch("Trailing Stop", settings.enableTrailingStop) { onOpen(AppTab.ADVANCED_SETTINGS) }
                }
            }
            item {
                GlassCard {
                    Text("Risk Management", fontWeight=FontWeight.ExtraBold)
                    PremiumSettingsRow("Max Position (EUR)", "€${settings.maxPositionEur}", false)
                    PremiumSettingsRow("Max Daily Loss (EUR)", "€${settings.maxDailyLossEur}", false)
                    PremiumSettingsRow("Max Trades Per Day", settings.maxTradesPerDay.toString(), false)
                    PremiumSettingsAction("Open Automation & Risk") { onOpen(AppTab.ADVANCED_SETTINGS) }
                }
            }
        }
        item {
            GlassCard {
                Text("Data & System", fontWeight=FontWeight.ExtraBold)
                PremiumSettingsAction("Backup & Recovery") { onOpen(AppTab.BACKUP) }
                PremiumSettingsAction("CloudShare") { onOpen(AppTab.CLOUDSHARE_SETTINGS) }
                PremiumSettingsAction("System Test") { onOpen(AppTab.SYSTEM_TEST) }
                PremiumSettingsAction("Notifications") { onOpen(AppTab.REMOTE_ALERTS) }
            }
        }
    }
}

@Composable
private fun PremiumSegmentTabs(labels: List<String>, selected: Int, onSelect: (Int)->Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical=2.dp), horizontalArrangement=Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { i,label ->
            val active = i==selected
            Surface(
                modifier=Modifier.weight(1f).clickable{onSelect(i)},
                shape=RoundedCornerShape(8.dp),
                color=if(active) Electric.copy(alpha=.12f) else Color.Transparent,
                border=BorderStroke(1.dp, if(active) Electric else Stroke)
            ) {
                Text(label, modifier=Modifier.padding(vertical=9.dp,horizontal=6.dp), color=if(active) Electric else Muted, fontWeight=if(active) FontWeight.Bold else FontWeight.Medium, style=MaterialTheme.typography.labelMedium, maxLines=1, overflow=TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun PremiumSettingsRow(label:String, value:String, positive:Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical=9.dp), verticalAlignment=Alignment.CenterVertically) {
        Text(label, modifier=Modifier.weight(1f), color=TextPrimary)
        Text(value, color=if(positive) Mint else TextPrimary, style=MaterialTheme.typography.bodySmall, fontWeight=FontWeight.Medium)
    }
}

@Composable
private fun PremiumSettingsAction(label:String,onClick:()->Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(vertical=10.dp), verticalAlignment=Alignment.CenterVertically) {
        Text(label, modifier=Modifier.weight(1f))
        Text("›", color=Muted, style=MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun PremiumSettingsSwitch(label:String,checked:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical=5.dp), verticalAlignment=Alignment.CenterVertically) {
        Text(label, modifier=Modifier.weight(1f))
        Switch(checked=checked,onCheckedChange=onChange)
    }
}"""

SYSTEM = r"""@Composable
private fun SystemTestScreen(
    settings: BotSettings,
    lines: List<String>,
    onRun: () -> Unit
) {
    val pass = lines.count { it.startsWith("PASS") }
    val fail = lines.count { it.startsWith("FAIL") }
    val warn = lines.count { it.startsWith("WARN") }
    val total = (pass+fail+warn).coerceAtLeast(1)
    val pct = (pass*100/total).coerceIn(0,100)
    val operational = fail==0 && lines.isNotEmpty()
    LazyColumn(
        modifier=Modifier.fillMaxSize().padding(horizontal=16.dp),
        contentPadding=PaddingValues(top=2.dp,bottom=18.dp),
        verticalArrangement=Arrangement.spacedBy(10.dp)
    ) {
        item {
            GlassCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                    Canvas(Modifier.size(44.dp)) {
                        val p=Path().apply{
                            moveTo(size.width*.5f,size.height*.08f); lineTo(size.width*.84f,size.height*.20f); lineTo(size.width*.78f,size.height*.64f); lineTo(size.width*.5f,size.height*.90f); lineTo(size.width*.22f,size.height*.64f); lineTo(size.width*.16f,size.height*.20f); close()
                        }
                        drawPath(p, color=if(operational) Mint else Amber, style=DrawStroke(width=3f))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if(operational) "ALL SYSTEMS OPERATIONAL" else "SYSTEM VERIFICATION", color=if(operational) Mint else Amber, fontWeight=FontWeight.ExtraBold)
                        Text("Overall Health", color=Muted, style=MaterialTheme.typography.labelSmall)
                    }
                    Text("$pct%", color=if(operational) Mint else Amber, fontWeight=FontWeight.ExtraBold)
                }
                LinearProgressIndicator(progress=pct/100f, modifier=Modifier.fillMaxWidth().height(5.dp), color=if(operational) Mint else Amber, trackColor=Stroke)
                OutlinedButton(onClick=onRun, modifier=Modifier.fillMaxWidth()) { Text("Run Full System Test") }
            }
        }
        item {
            GlassCard {
                Text("Systems", fontWeight=FontWeight.ExtraBold)
                if(lines.isEmpty()) Text("No system test has been run yet.", color=Muted)
                lines.take(30).forEach { row ->
                    val parts=row.split("|").map{it.trim()}
                    val st=parts.getOrNull(0)?:"INFO"
                    val name=parts.getOrNull(1)?:"Check"
                    val ok=st=="PASS"
                    Row(Modifier.fillMaxWidth().padding(vertical=6.dp), verticalAlignment=Alignment.CenterVertically) {
                        StatusDot(if(ok) Mint else if(st=="FAIL") Danger else Amber)
                        Spacer(Modifier.width(9.dp))
                        Text(name, modifier=Modifier.weight(1f), fontWeight=FontWeight.Medium)
                        Text(if(ok) "OK" else st, color=if(ok) Mint else if(st=="FAIL") Danger else Amber, style=MaterialTheme.typography.labelMedium, fontWeight=FontWeight.Bold)
                    }
                }
            }
        }
    }
}"""

GLASS = r"""@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier=Modifier.fillMaxWidth(),
        shape=RoundedCornerShape(12.dp),
        colors=CardDefaults.cardColors(containerColor=Panel),
        border=BorderStroke(1.dp, Stroke)
    ) {
        Column(modifier=Modifier.padding(14.dp), verticalArrangement=Arrangement.spacedBy(8.dp), content=content)
    }
}"""

TOGGLE = r"""@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier=Modifier.fillMaxWidth().padding(vertical=3.dp), verticalAlignment=Alignment.CenterVertically) {
        Text(label, modifier=Modifier.weight(1f))
        Switch(checked=checked, onCheckedChange=onCheckedChange)
    }
}"""

def patch_main(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    text = patch_imports(text)

    old_colors = """private val SpaceBlack = Color(0xFF081326)
private val Panel = Color(0xFF0F1B33)
private val PanelAlt = Color(0xFF142544)
private val Stroke = Color(0xFF2A4471)
private val Electric = Color(0xFF47B8FF)
private val Mint = Color(0xFF55F0DE)
private val Amber = Color(0xFFFFC857)
private val Danger = Color(0xFFFF5E8A)
private val Muted = Color(0xFFA5B4D0)
private val TextPrimary = Color(0xFFEAF3FF)
"""
    new_colors = """private val SpaceBlack = Color(0xFF07111D)
private val Panel = Color(0xFF0D1827)
private val PanelAlt = Color(0xFF111D2D)
private val Stroke = Color(0xFF1B2A3B)
private val Electric = Color(0xFF8B5CF6)
private val Mint = Color(0xFF4ADE80)
private val Amber = Color(0xFFF5B942)
private val Danger = Color(0xFFF87171)
private val Muted = Color(0xFF8D9AAA)
private val TextPrimary = Color(0xFFF4F7FB)
"""
    if old_colors in text:
        text = text.replace(old_colors, new_colors, 1)

    text = replace_once(
        text,
        """            HeaderBar(status = status, mode = settings.mode, level = statusLevel)
            AppTabs(currentTab = currentTab, onTabSelected = { currentTab = it })

            when (currentTab) {""",
        """            HeaderBar(currentTab = currentTab, status = status, mode = settings.mode, level = statusLevel)
            Box(modifier = Modifier.weight(1f)) {
                when (currentTab) {""",
        "root shell start"
    )
    text = replace_once(
        text,
        """                )
            }
        }
    }
}

@Composable
private fun HeaderBar""",
        """                )
                }
            }
            AppTabs(currentTab = currentTab, onTabSelected = { currentTab = it })
        }
    }
}

@Composable
private fun HeaderBar""",
        "root shell end"
    )

    text = replace_once(
        text,
        """                    activePositionSymbols = activeChartSymbols,
                    onStart =""",
        """                    activePositionSymbols = activeChartSymbols,
                    portfolioSnapshot = portfolioSnapshot,
                    lifecycleSnapshot = lifecycleSnapshot,
                    trades = tradeJournal,
                    onStart =""",
        "dashboard args"
    )
    text = replace_once(
        text,
        """                        }
                    }
                )
                AppTab.NEWS -> NewsScreen(""",
        """                        }
                    },
                    onOpen = { currentTab = it }
                )
                AppTab.NEWS -> NewsScreen(""",
        "portfolio route"
    )
    text = replace_once(
        text,
        """                    activeSymbols = activeChartSymbols,
                    onToggleNews =""",
        """                    activeSymbols = activeChartSymbols,
                    decisions = decisions,
                    onToggleNews =""",
        "news args"
    )
    text = replace_once(
        text,
        """                    systemTestLines = systemTestLines,
                    onOpen =""",
        """                    systemTestLines = systemTestLines,
                    portfolioSnapshot = portfolioSnapshot,
                    onOpen =""",
        "settings args"
    )

    text = replace_composable(text, "HeaderBar", HEADER)
    text = replace_composable(text, "AppTabs", TABS)
    text = replace_composable(text, "DashboardScreen", DASH)
    text = replace_composable(text, "PortfolioScreen", PORTFOLIO)
    text = replace_composable(text, "AiHubScreen", AI)
    text = replace_composable(text, "NewsScreen", NEWS)
    text = replace_composable(text, "SettingsHubScreen", SETTINGS)
    text = replace_composable(text, "SystemTestScreen", SYSTEM)
    text = replace_composable(text, "GlassCard", GLASS)
    text = replace_composable(text, "ToggleRow", TOGGLE)

    text = text.replace("RoundedCornerShape(22.dp)", "RoundedCornerShape(12.dp)")
    text = text.replace("RoundedCornerShape(26.dp)", "RoundedCornerShape(12.dp)")
    text = text.replace("Color(0xFF14345F)", "Color(0xFF151E31)")
    text = text.replace("Color(0x664DA3FF)", "Color(0x668B5CF6)")
    path.write_text(text, encoding="utf-8")

def validate(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    checks = {
        "purple premium accent": "0xFF8B5CF6" in text,
        "five-root bottom nav": "listOf(AppTab.DASHBOARD, AppTab.PORTFOLIO, AppTab.AI, AppTab.NEWS, AppTab.SETTINGS)" in text,
        "dashboard area graph": "PremiumPortfolioAreaChart" in text,
        "portfolio donut": "AllocationDonut" in text,
        "AI confidence gauge": "ConfidenceGauge" in text,
        "news sentiment card": "Overall News Sentiment" in text,
        "segmented settings": 'Connection & Trading","Automation & Risk' in text,
        "system operational card": "ALL SYSTEMS OPERATIONAL" in text,
        "switch settings": "Switch(checked=checked" in text,
    }
    for k, v in checks.items():
        print(("PASS" if v else "FAIL") + " | " + k)
    bad = [k for k,v in checks.items() if not v]
    if bad:
        fail("visual contract failed: " + ", ".join(bad))

def main() -> None:
    repo = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()
    main_file = repo / "app/src/main/java/com/ksp/cryptobot/MainActivity.kt"
    if not main_file.exists():
        fail(f"missing {main_file}")
    patch_main(main_file)
    validate(main_file)
    print("[CTS exact preview UI] Applied successfully.")

if __name__ == "__main__":
    main()
