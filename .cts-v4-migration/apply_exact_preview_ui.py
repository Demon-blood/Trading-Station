#!/usr/bin/env python3
# Apply the approved Crypto TradeStation preview UI as the real Compose shell.
# Run AFTER apply_full_integration_cleanup.py.
from __future__ import annotations

import re
import sys
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"[CTS preview-exact redesign] {message}")


def require(path: Path) -> None:
    if not path.exists():
        fail(f"Required file missing: {path}")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def replace_regex_once(text: str, pattern: str, replacement: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.S)
    if count != 1:
        fail(f"{label}: expected exactly one regex match, found {count}")
    return updated


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


PREVIEW_SOURCE = 'package com.ksp.cryptobot\n\nimport androidx.activity.compose.rememberLauncherForActivityResult\nimport androidx.activity.result.contract.ActivityResultContracts\nimport androidx.compose.foundation.Canvas\nimport androidx.compose.foundation.background\nimport androidx.compose.foundation.border\nimport androidx.compose.foundation.clickable\nimport androidx.compose.foundation.layout.Arrangement\nimport androidx.compose.foundation.layout.Box\nimport androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.PaddingValues\nimport androidx.compose.foundation.layout.Row\nimport androidx.compose.foundation.layout.Spacer\nimport androidx.compose.foundation.layout.fillMaxHeight\nimport androidx.compose.foundation.layout.fillMaxSize\nimport androidx.compose.foundation.layout.fillMaxWidth\nimport androidx.compose.foundation.layout.height\nimport androidx.compose.foundation.layout.padding\nimport androidx.compose.foundation.layout.size\nimport androidx.compose.foundation.layout.width\nimport androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.LazyRow\nimport androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.shape.CircleShape\nimport androidx.compose.foundation.shape.RoundedCornerShape\nimport androidx.compose.material.icons.Icons\nimport androidx.compose.material.icons.rounded.AccountBalanceWallet\nimport androidx.compose.material.icons.rounded.Analytics\nimport androidx.compose.material.icons.rounded.ArrowBack\nimport androidx.compose.material.icons.rounded.Backup\nimport androidx.compose.material.icons.rounded.CheckCircle\nimport androidx.compose.material.icons.rounded.ChevronRight\nimport androidx.compose.material.icons.rounded.CloudDone\nimport androidx.compose.material.icons.rounded.ErrorOutline\nimport androidx.compose.material.icons.rounded.Home\nimport androidx.compose.material.icons.rounded.Info\nimport androidx.compose.material.icons.rounded.Menu\nimport androidx.compose.material.icons.rounded.Newspaper\nimport androidx.compose.material.icons.rounded.Refresh\nimport androidx.compose.material.icons.rounded.Restore\nimport androidx.compose.material.icons.rounded.Security\nimport androidx.compose.material.icons.rounded.Settings\nimport androidx.compose.material.icons.rounded.Sync\nimport androidx.compose.material.icons.rounded.Tune\nimport androidx.compose.material.icons.rounded.WarningAmber\nimport androidx.compose.material3.Button\nimport androidx.compose.material3.ButtonDefaults\nimport androidx.compose.material3.Divider\nimport androidx.compose.material3.Icon\nimport androidx.compose.material3.IconButton\nimport androidx.compose.material3.LinearProgressIndicator\nimport androidx.compose.material3.OutlinedButton\nimport androidx.compose.material3.Surface\nimport androidx.compose.material3.Switch\nimport androidx.compose.material3.SwitchDefaults\nimport androidx.compose.material3.Text\nimport androidx.compose.runtime.Composable\nimport androidx.compose.runtime.getValue\nimport androidx.compose.runtime.mutableIntStateOf\nimport androidx.compose.runtime.mutableStateOf\nimport androidx.compose.runtime.remember\nimport androidx.compose.runtime.setValue\nimport androidx.compose.ui.Alignment\nimport androidx.compose.ui.Modifier\nimport androidx.compose.ui.geometry.Offset\nimport androidx.compose.ui.graphics.Brush\nimport androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.Path\nimport androidx.compose.ui.graphics.StrokeCap\nimport androidx.compose.ui.graphics.drawscope.Stroke\nimport androidx.compose.ui.graphics.vector.ImageVector\nimport androidx.compose.ui.text.font.FontWeight\nimport androidx.compose.ui.text.style.TextOverflow\nimport androidx.compose.ui.unit.dp\nimport androidx.compose.ui.unit.sp\nimport com.ksp.cryptobot.cloudshare.CloudShareSettingsStore\nimport com.ksp.cryptobot.core.AiDecision\nimport com.ksp.cryptobot.core.BotMode\nimport com.ksp.cryptobot.core.BotSettings\nimport com.ksp.cryptobot.core.Candle\nimport com.ksp.cryptobot.core.ExchangeProvider\nimport com.ksp.cryptobot.core.LifecycleSnapshot\nimport com.ksp.cryptobot.core.OrderSide\nimport com.ksp.cryptobot.core.PortfolioSnapshot\nimport com.ksp.cryptobot.core.SignalAction\nimport com.ksp.cryptobot.data.NewsArticleEntity\nimport com.ksp.cryptobot.data.TradeEntity\nimport com.ksp.cryptobot.news.NewsProviderHealthRegistry\nimport java.math.BigDecimal\nimport java.math.RoundingMode\nimport java.time.Instant\nimport java.time.ZoneId\nimport java.time.format.DateTimeFormatter\nimport kotlin.math.max\n\nprivate val PreviewBackground = Color(0xFF0C1522)\nprivate val PreviewTop = Color(0xFF0A1320)\nprivate val PreviewCard = Color(0xFF141D2A)\nprivate val PreviewCardAlt = Color(0xFF111A27)\nprivate val PreviewDivider = Color(0xFF243143)\nprivate val PreviewPurple = Color(0xFF8B5CF6)\nprivate val PreviewPurpleSoft = Color(0xFF9C75FF)\nprivate val PreviewGreen = Color(0xFF62DE67)\nprivate val PreviewRed = Color(0xFFFF5D69)\nprivate val PreviewOrange = Color(0xFFFFA31A)\nprivate val PreviewBlue = Color(0xFF68B7E8)\nprivate val PreviewMint = Color(0xFF77D6BD)\nprivate val PreviewText = Color(0xFFF2F5F8)\nprivate val PreviewMuted = Color(0xFF8E9BAB)\nprivate val PreviewMuted2 = Color(0xFF687687)\nprivate val PreviewBlack = Color(0xFF05090E)\n\nprivate val previewTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm:ss")\n\nfun previewParentTab(tab: AppTab): AppTab = when (tab) {\n    AppTab.AI_SIGNALS,\n    AppTab.AI_SIGNAL_DETAIL,\n    AppTab.STRATEGY,\n    AppTab.SANDBOX,\n    AppTab.BACKTEST,\n    AppTab.REGIME,\n    AppTab.AUTONOMOUS,\n    AppTab.SELF_LEARNING,\n    AppTab.SELF_LEARNING_MAIN,\n    AppTab.LEARNING_INSPECTOR,\n    AppTab.PERFORMANCE,\n    AppTab.PRO,\n    AppTab.SMART_EXIT,\n    AppTab.PORTFOLIO_ROTATION,\n    AppTab.AUTO_TUNER,\n    AppTab.RELEASE_SAFETY,\n    AppTab.RESEARCH_SETTINGS -> AppTab.AI\n\n    AppTab.ORDERS,\n    AppTab.POSITIONS,\n    AppTab.HISTORY,\n    AppTab.TAX,\n    AppTab.CHART,\n    AppTab.CHART_MAIN,\n    AppTab.TRADE_OVERLAY,\n    AppTab.REPLAY,\n    AppTab.TRADE_JOURNAL -> AppTab.PORTFOLIO\n\n    AppTab.BASIC_SETTINGS,\n    AppTab.ADVANCED_SETTINGS,\n    AppTab.SYSTEM_TEST,\n    AppTab.HEALTH,\n    AppTab.NOTIFICATIONS,\n    AppTab.NOTIFICATION_LOGS,\n    AppTab.REMOTE_ALERTS,\n    AppTab.BACKUP,\n    AppTab.KRAKEN_HEALTH,\n    AppTab.CLOUDSHARE_SETTINGS,\n    AppTab.RECOVERY_TOOLS,\n    AppTab.RISK,\n    AppTab.SYMBOLS,\n    AppTab.BOT,\n    AppTab.STATUS -> AppTab.SETTINGS\n\n    else -> AppTab.DASHBOARD\n}\n\nprivate fun isPrimaryPreviewTab(tab: AppTab): Boolean = tab in setOf(\n    AppTab.DASHBOARD, AppTab.PORTFOLIO, AppTab.AI, AppTab.NEWS, AppTab.SETTINGS\n)\n\nprivate fun previewTopTitle(tab: AppTab, detailSymbol: String?): String = when (tab) {\n    AppTab.DASHBOARD -> "Dashboard"\n    AppTab.PORTFOLIO -> "Portfolio"\n    AppTab.AI -> "AI & Research"\n    AppTab.NEWS -> "News & Intelligence"\n    AppTab.SETTINGS -> "Settings"\n    AppTab.POSITIONS -> "Active Positions"\n    AppTab.ORDERS -> "Orders"\n    AppTab.HISTORY -> "History"\n    AppTab.AI_SIGNAL_DETAIL -> "${previewPair(detailSymbol.orEmpty())} – AI Signal"\n    AppTab.AI_SIGNALS -> "AI Signals"\n    AppTab.SYSTEM_TEST -> "System Test (v4 Systems)"\n    AppTab.BASIC_SETTINGS -> "Connection & Trading"\n    AppTab.ADVANCED_SETTINGS -> "Automation & Risk"\n    AppTab.BACKUP -> "Settings"\n    AppTab.RESEARCH_SETTINGS -> "AI & Research"\n    AppTab.CLOUDSHARE_SETTINGS -> "CloudShare"\n    AppTab.RECOVERY_TOOLS -> "Recovery"\n    else -> tab.label\n}\n\n@Composable\nfun PreviewAppTopBar(\n    currentTab: AppTab,\n    detailSymbol: String? = null,\n    onBack: () -> Unit,\n    onAction: () -> Unit\n) {\n    val showMenu = currentTab == AppTab.DASHBOARD || currentTab == AppTab.PORTFOLIO\n    val actionIcon: ImageVector? = when (currentTab) {\n        AppTab.DASHBOARD -> Icons.Rounded.Info\n        AppTab.PORTFOLIO, AppTab.POSITIONS, AppTab.ORDERS -> Icons.Rounded.Refresh\n        AppTab.SYSTEM_TEST -> Icons.Rounded.Security\n        else -> null\n    }\n    Surface(color = PreviewTop, modifier = Modifier.fillMaxWidth()) {\n        Row(\n            modifier = Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp),\n            verticalAlignment = Alignment.CenterVertically\n        ) {\n            IconButton(onClick = { if (!showMenu) onBack() }) {\n                Icon(\n                    imageVector = if (showMenu) Icons.Rounded.Menu else Icons.Rounded.ArrowBack,\n                    contentDescription = if (showMenu) "Menu" else "Back",\n                    tint = PreviewText,\n                    modifier = Modifier.size(22.dp)\n                )\n            }\n            Text(\n                previewTopTitle(currentTab, detailSymbol),\n                color = PreviewText,\n                fontSize = 18.sp,\n                fontWeight = FontWeight.SemiBold,\n                modifier = Modifier.weight(1f)\n            )\n            if (actionIcon != null) {\n                IconButton(onClick = onAction) {\n                    Icon(actionIcon, contentDescription = "Action", tint = PreviewText, modifier = Modifier.size(21.dp))\n                }\n            } else {\n                Spacer(Modifier.width(48.dp))\n            }\n        }\n        Divider(color = PreviewDivider.copy(alpha = 0.55f), thickness = 0.7.dp)\n    }\n}\n\n@Composable\nfun PreviewBottomNavigation(currentTab: AppTab, onTabSelected: (AppTab) -> Unit) {\n    val parent = if (isPrimaryPreviewTab(currentTab)) currentTab else previewParentTab(currentTab)\n    val items = listOf(\n        Triple(AppTab.DASHBOARD, Icons.Rounded.Home, "Dashboard"),\n        Triple(AppTab.PORTFOLIO, Icons.Rounded.AccountBalanceWallet, "Portfolio"),\n        Triple(AppTab.AI, Icons.Rounded.Analytics, "AI"),\n        Triple(AppTab.NEWS, Icons.Rounded.Newspaper, "News"),\n        Triple(AppTab.SETTINGS, Icons.Rounded.Settings, "Settings")\n    )\n    Surface(color = PreviewTop, modifier = Modifier.fillMaxWidth()) {\n        Column {\n            Divider(color = PreviewDivider, thickness = 0.7.dp)\n            Row(\n                modifier = Modifier.fillMaxWidth().height(72.dp),\n                horizontalArrangement = Arrangement.SpaceEvenly,\n                verticalAlignment = Alignment.CenterVertically\n            ) {\n                items.forEach { (tab, icon, label) ->\n                    val selected = parent == tab\n                    Column(\n                        modifier = Modifier.weight(1f).fillMaxHeight().clickable { onTabSelected(tab) }.padding(top = 8.dp, bottom = 6.dp),\n                        horizontalAlignment = Alignment.CenterHorizontally,\n                        verticalArrangement = Arrangement.Center\n                    ) {\n                        Icon(icon, contentDescription = label, tint = if (selected) PreviewPurpleSoft else PreviewMuted, modifier = Modifier.size(22.dp))\n                        Spacer(Modifier.height(4.dp))\n                        Text(label, color = if (selected) PreviewPurpleSoft else PreviewMuted, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)\n                    }\n                }\n            }\n        }\n    }\n}\n\n@Composable\nprivate fun PreviewCardBox(\n    modifier: Modifier = Modifier,\n    onClick: (() -> Unit)? = null,\n    content: @Composable () -> Unit\n) {\n    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier\n    Surface(\n        modifier = modifier.fillMaxWidth().then(clickModifier),\n        color = PreviewCard,\n        shape = RoundedCornerShape(8.dp),\n        border = androidx.compose.foundation.BorderStroke(0.7.dp, PreviewDivider.copy(alpha = 0.72f))\n    ) {\n        Box(Modifier.padding(14.dp)) { content() }\n    }\n}\n\n@Composable\nprivate fun PreviewSectionHeader(title: String, trailing: String? = null) {\n    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n        Text(title, color = PreviewText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))\n        if (!trailing.isNullOrBlank()) Text(trailing, color = PreviewText, fontSize = 13.sp)\n    }\n}\n\nprivate fun euro(value: BigDecimal): String = "€" + value.setScale(2, RoundingMode.HALF_UP).toPlainString()\nprivate fun pct(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%"\nprivate fun safeBig(value: String): BigDecimal = value.toBigDecimalOrNull() ?: BigDecimal.ZERO\nprivate fun tradeNotional(trade: TradeEntity): BigDecimal = safeBig(trade.quantity).multiply(safeBig(trade.priceEur))\n\nfun previewPair(symbol: String): String {\n    val clean = symbol.uppercase().replace("/", "").replace("-", "")\n    val quotes = listOf("USDT", "USDC", "EUR", "USD", "BTC", "ETH")\n    val quote = quotes.firstOrNull { clean.endsWith(it) } ?: return clean\n    val base = clean.removeSuffix(quote)\n    return if (base.isBlank()) clean else "$base/$quote"\n}\n\nprivate fun previewBase(symbol: String): String = previewPair(symbol).substringBefore(\'/\')\n\nprivate fun friendlyAsset(base: String): String = when (base.uppercase()) {\n    "BTC", "XBT" -> "Bitcoin"\n    "ETH" -> "Ethereum"\n    "KAS" -> "Kaspa"\n    "HBAR" -> "Hedera"\n    "SOL" -> "Solana"\n    "XRP" -> "XRP"\n    "ADA" -> "Cardano"\n    "DOGE" -> "Dogecoin"\n    "DOT" -> "Polkadot"\n    else -> base.uppercase()\n}\n\n@Composable\nprivate fun AssetIcon(base: String, size: Int = 28) {\n    val normalized = base.uppercase().replace("XBT", "BTC")\n    val background = when (normalized) {\n        "BTC" -> PreviewOrange\n        "ETH" -> Color(0xFF66768D)\n        "KAS" -> Color(0xFF75D3CB)\n        "HBAR" -> PreviewBlack\n        "SOL" -> Color(0xFF6547D8)\n        "XRP" -> Color(0xFF303946)\n        else -> Color(0xFF344358)\n    }\n    val label = when (normalized) {\n        "BTC" -> "₿"\n        "ETH" -> "◆"\n        "HBAR" -> "H"\n        else -> normalized.take(1)\n    }\n    Surface(shape = CircleShape, color = background, modifier = Modifier.size(size.dp)) {\n        Box(contentAlignment = Alignment.Center) {\n            Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.46f).sp)\n        }\n    }\n}\n\nprivate fun realized24h(trades: List<TradeEntity>): BigDecimal {\n    val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L\n    return trades.asSequence().filter { it.timestampEpochMs >= cutoff }.map { safeBig(it.realizedPnlEur) }.fold(BigDecimal.ZERO, BigDecimal::add)\n}\n\nprivate fun turnover24h(trades: List<TradeEntity>): BigDecimal {\n    val cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L\n    return trades.asSequence().filter { it.timestampEpochMs >= cutoff }.map(::tradeNotional).fold(BigDecimal.ZERO, BigDecimal::add)\n}\n\nprivate fun portfolioTrend(total: BigDecimal, trades: List<TradeEntity>): List<Float> {\n    val rows = trades.sortedBy { it.timestampEpochMs }.takeLast(24)\n    if (rows.isEmpty()) return listOf(0.5f, 0.5f)\n    val net = rows.map { safeBig(it.realizedPnlEur) }\n    var running = total.subtract(net.fold(BigDecimal.ZERO) { a, b -> a.add(b) })\n    val values = mutableListOf(running)\n    net.forEach { running = running.add(it); values += running }\n    val min = values.minOrNull() ?: BigDecimal.ZERO\n    val max = values.maxOrNull() ?: BigDecimal.ONE\n    val range = max.subtract(min).takeIf { it > BigDecimal.ZERO } ?: BigDecimal.ONE\n    return values.map { it.subtract(min).divide(range, 8, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f) }\n}\n\n@Composable\nprivate fun PreviewAreaSparkline(points: List<Float>, modifier: Modifier = Modifier) {\n    Canvas(modifier = modifier) {\n        if (points.size < 2) return@Canvas\n        val xStep = size.width / (points.size - 1).coerceAtLeast(1)\n        fun y(v: Float) = size.height - (size.height * (0.13f + v * 0.74f))\n        val path = Path().apply {\n            moveTo(0f, y(points.first()))\n            points.drop(1).forEachIndexed { index, value -> lineTo((index + 1) * xStep, y(value)) }\n        }\n        val fill = Path().apply {\n            moveTo(0f, size.height)\n            lineTo(0f, y(points.first()))\n            points.drop(1).forEachIndexed { index, value -> lineTo((index + 1) * xStep, y(value)) }\n            lineTo(size.width, size.height)\n            close()\n        }\n        drawPath(fill, brush = Brush.verticalGradient(listOf(PreviewGreen.copy(alpha = 0.22f), PreviewGreen.copy(alpha = 0.01f))))\n        drawPath(path, color = PreviewGreen, style = Stroke(width = 2.4f, cap = StrokeCap.Round))\n        drawCircle(PreviewGreen, radius = 3.2f, center = Offset(size.width, y(points.last())))\n    }\n}\n\n@Composable\nfun PreviewDashboardScreen(\n    settings: BotSettings,\n    status: String,\n    portfolio: PortfolioSnapshot?,\n    lifecycle: LifecycleSnapshot?,\n    decisions: List<AiDecision>,\n    trades: List<TradeEntity>,\n    onStart: () -> Unit,\n    onStop: () -> Unit,\n    onScan: () -> Unit,\n    onExecute: () -> Unit,\n    onOpenNews: () -> Unit\n) {\n    val total = portfolio?.totalValueEur ?: BigDecimal.ZERO\n    val available = portfolio?.freeEur ?: BigDecimal.ZERO\n    val invested = total.subtract(available).max(BigDecimal.ZERO)\n    val pnl24 = realized24h(trades)\n    val pct24 = if (total.subtract(pnl24) > BigDecimal.ZERO) pnl24.multiply(BigDecimal("100")).divide(total.subtract(pnl24), 4, RoundingMode.HALF_UP) else BigDecimal.ZERO\n    val positions = lifecycle?.positions.orEmpty().filter { it.quantity > BigDecimal.ZERO }.take(4)\n    val trend = portfolioTrend(total, trades)\n\n    LazyColumn(\n        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),\n        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),\n        verticalArrangement = Arrangement.spacedBy(8.dp)\n    ) {\n        item {\n            PreviewCardBox {\n                Column {\n                    Text("Portfolio Value", color = PreviewMuted, fontSize = 11.sp)\n                    Spacer(Modifier.height(3.dp))\n                    Text(euro(total), color = PreviewGreen, fontSize = 24.sp, fontWeight = FontWeight.Bold)\n                    Spacer(Modifier.height(8.dp))\n                    Text("24H P/L", color = PreviewMuted, fontSize = 10.sp)\n                    Text(\n                        (if (pnl24 >= BigDecimal.ZERO) "+" else "") + euro(pnl24) + " (" + (if (pct24 >= BigDecimal.ZERO) "+" else "") + pct(pct24) + ")",\n                        color = if (pnl24 >= BigDecimal.ZERO) PreviewGreen else PreviewRed,\n                        fontSize = 11.sp,\n                        fontWeight = FontWeight.SemiBold\n                    )\n                    PreviewAreaSparkline(trend, Modifier.fillMaxWidth().height(78.dp).padding(top = 4.dp))\n                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {\n                        DashboardStat("Invested", euro(invested))\n                        DashboardStat("Available", euro(available))\n                        DashboardStat("24H Volume", euro(turnover24h(trades)))\n                    }\n                }\n            }\n        }\n        item {\n            PreviewCardBox {\n                Column {\n                    PreviewSectionHeader("Active Positions", positions.size.toString())\n                    Spacer(Modifier.height(7.dp))\n                    if (positions.isEmpty()) {\n                        Text("No active positions", color = PreviewMuted, fontSize = 12.sp)\n                    } else {\n                        positions.forEachIndexed { index, p ->\n                            DashboardPositionRow(p)\n                            if (index != positions.lastIndex) Divider(color = PreviewDivider.copy(alpha = 0.65f), modifier = Modifier.padding(vertical = 7.dp))\n                        }\n                    }\n                }\n            }\n        }\n        item {\n            PreviewCardBox {\n                Column {\n                    PreviewSectionHeader("Bot Controls")\n                    Spacer(Modifier.height(6.dp))\n                    Text(status, color = PreviewMuted, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)\n                    Spacer(Modifier.height(10.dp))\n                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                        PreviewPrimaryButton("Scan", onScan, Modifier.weight(1f))\n                        PreviewOutlineButton("Execute", onExecute, Modifier.weight(1f))\n                    }\n                    Spacer(Modifier.height(8.dp))\n                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                        PreviewOutlineButton("Start", onStart, Modifier.weight(1f))\n                        PreviewOutlineButton("Stop", onStop, Modifier.weight(1f))\n                        PreviewOutlineButton("News", onOpenNews, Modifier.weight(1f))\n                    }\n                    val latest = decisions.maxByOrNull { it.finalScore }\n                    if (latest != null) {\n                        Spacer(Modifier.height(9.dp))\n                        Divider(color = PreviewDivider)\n                        Spacer(Modifier.height(8.dp))\n                        Text("Latest AI: ${previewPair(latest.symbol)} ${latest.finalAction.name.replace(\'_\', \' \')} • ${latest.confidencePercent}%", color = previewActionColor(latest.finalAction), fontSize = 11.sp)\n                    }\n                }\n            }\n        }\n    }\n}\n\n@Composable\nprivate fun DashboardStat(label: String, value: String) {\n    Column {\n        Text(label, color = PreviewMuted, fontSize = 9.sp)\n        Text(value, color = PreviewText, fontSize = 11.sp, fontWeight = FontWeight.Medium)\n    }\n}\n\n@Composable\nprivate fun DashboardPositionRow(position: com.ksp.cryptobot.core.PositionInfo) {\n    val base = previewBase(position.symbol)\n    val value = position.currentPrice.multiply(position.quantity)\n    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n        AssetIcon(base, 26)\n        Spacer(Modifier.width(9.dp))\n        Column(Modifier.weight(1f)) {\n            Text(previewPair(position.symbol), color = PreviewText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)\n            Text(friendlyAsset(base), color = PreviewMuted, fontSize = 9.sp)\n        }\n        Column(horizontalAlignment = Alignment.End) {\n            Text(euro(value), color = PreviewText, fontSize = 11.sp, fontWeight = FontWeight.Medium)\n            Text((if (position.unrealizedPnlPercent >= BigDecimal.ZERO) "+" else "") + pct(position.unrealizedPnlPercent), color = if (position.unrealizedPnlPercent >= BigDecimal.ZERO) PreviewGreen else PreviewRed, fontSize = 10.sp)\n        }\n    }\n}\n\n@Composable\nprivate fun PreviewPrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {\n    Button(\n        onClick = onClick,\n        modifier = modifier.height(36.dp),\n        shape = RoundedCornerShape(6.dp),\n        colors = ButtonDefaults.buttonColors(containerColor = PreviewPurple, contentColor = Color.White),\n        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)\n    ) { Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }\n}\n\n@Composable\nprivate fun PreviewOutlineButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {\n    OutlinedButton(\n        onClick = onClick,\n        modifier = modifier.height(36.dp),\n        shape = RoundedCornerShape(6.dp),\n        border = androidx.compose.foundation.BorderStroke(1.dp, PreviewPurple),\n        colors = ButtonDefaults.outlinedButtonColors(contentColor = PreviewPurpleSoft),\n        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)\n    ) { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium) }\n}\n\n@Composable\nfun PreviewPortfolioScreen(\n    settings: BotSettings,\n    snapshot: PortfolioSnapshot?,\n    lifecycleSnapshot: LifecycleSnapshot?,\n    trades: List<TradeEntity>,\n    onRefresh: () -> Unit\n) {\n    var tab by remember { mutableIntStateOf(0) }\n    val tabs = listOf("Positions", "Orders", "History", "Allocations")\n    val assets = snapshot?.assets.orEmpty().filter { it.total > BigDecimal.ZERO || it.eurValue > BigDecimal.ZERO }\n    val total = snapshot?.totalValueEur ?: BigDecimal.ZERO\n    val pnl24 = realized24h(trades)\n    val pct24 = if (total.subtract(pnl24) > BigDecimal.ZERO) pnl24.multiply(BigDecimal("100")).divide(total.subtract(pnl24), 4, RoundingMode.HALF_UP) else BigDecimal.ZERO\n\n    LazyColumn(\n        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),\n        contentPadding = PaddingValues(top = 0.dp, bottom = 16.dp),\n        verticalArrangement = Arrangement.spacedBy(8.dp)\n    ) {\n        item {\n            PreviewSegmentTabs(labels = tabs, selected = tab, onSelected = { tab = it })\n        }\n        when (tab) {\n            0, 3 -> {\n                item {\n                    PreviewCardBox {\n                        Column {\n                            Text("Total Value", color = PreviewMuted, fontSize = 10.sp)\n                            Text(euro(total), color = PreviewGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold)\n                            Text("24H P/L", color = PreviewMuted, fontSize = 9.sp)\n                            Text((if (pnl24 >= BigDecimal.ZERO) "+" else "") + euro(pnl24) + " (" + (if (pct24 >= BigDecimal.ZERO) "+" else "") + pct(pct24) + ")", color = if (pnl24 >= BigDecimal.ZERO) PreviewGreen else PreviewRed, fontSize = 10.sp)\n                            Spacer(Modifier.height(10.dp))\n                            AssetAllocationDonut(assets = assets, total = total, modifier = Modifier.fillMaxWidth().height(160.dp))\n                            Spacer(Modifier.height(5.dp))\n                            assets.take(8).forEach { asset -> AllocationRow(asset.asset, asset.eurValue, total) }\n                        }\n                    }\n                }\n            }\n            1 -> {\n                val orders = lifecycleSnapshot?.openOrders.orEmpty()\n                if (orders.isEmpty()) item { PreviewEmptyCard("No open orders") }\n                items(orders) { order ->\n                    PreviewCardBox {\n                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n                            AssetIcon(previewBase(order.symbol), 28)\n                            Spacer(Modifier.width(10.dp))\n                            Column(Modifier.weight(1f)) {\n                                Text("${order.side.name} ${previewPair(order.symbol)}", color = PreviewText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)\n                                Text("${order.orderType.name} • ${order.status}", color = PreviewMuted, fontSize = 10.sp)\n                            }\n                            Text(order.remainingQuantity.stripTrailingZeros().toPlainString(), color = PreviewText, fontSize = 11.sp)\n                        }\n                    }\n                }\n            }\n            2 -> {\n                if (trades.isEmpty()) item { PreviewEmptyCard("No trade history") }\n                items(trades.take(80)) { trade -> PreviewTradeRow(trade) }\n            }\n        }\n        item {\n            Spacer(Modifier.height(2.dp))\n            PreviewOutlineButton("Refresh Portfolio", onRefresh, Modifier.fillMaxWidth())\n        }\n    }\n}\n\n@Composable\nprivate fun PreviewEmptyCard(message: String) {\n    PreviewCardBox { Text(message, color = PreviewMuted, fontSize = 12.sp) }\n}\n\n@Composable\nprivate fun PreviewTradeRow(trade: TradeEntity) {\n    val pnl = safeBig(trade.realizedPnlEur)\n    PreviewCardBox {\n        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n            AssetIcon(previewBase(trade.symbol), 28)\n            Spacer(Modifier.width(10.dp))\n            Column(Modifier.weight(1f)) {\n                Text("${trade.side} ${previewPair(trade.symbol)}", color = PreviewText, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)\n                Text("${trade.quantity} @ ${trade.priceEur}", color = PreviewMuted, fontSize = 10.sp)\n            }\n            Column(horizontalAlignment = Alignment.End) {\n                Text(if (trade.paper) "PAPER" else "LIVE", color = PreviewPurpleSoft, fontSize = 9.sp)\n                Text((if (pnl > BigDecimal.ZERO) "+" else "") + euro(pnl), color = if (pnl >= BigDecimal.ZERO) PreviewGreen else PreviewRed, fontSize = 10.sp)\n            }\n        }\n    }\n}\n\n@Composable\nprivate fun AssetAllocationDonut(assets: List<com.ksp.cryptobot.core.BalanceInfo>, total: BigDecimal, modifier: Modifier = Modifier) {\n    val palette = listOf(PreviewPurple, PreviewBlue, PreviewMint, PreviewOrange, Color(0xFFA592DF), Color(0xFF4F6B87), Color(0xFF66C57A))\n    Box(modifier, contentAlignment = Alignment.Center) {\n        Canvas(Modifier.size(118.dp)) {\n            var start = -90f\n            val stroke = Stroke(width = 23f, cap = StrokeCap.Butt)\n            if (assets.isEmpty() || total <= BigDecimal.ZERO) {\n                drawArc(PreviewDivider, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = stroke)\n            } else {\n                assets.take(7).forEachIndexed { index, asset ->\n                    val share = asset.eurValue.divide(total, 8, RoundingMode.HALF_UP).toFloat().coerceAtLeast(0f)\n                    val sweep = share * 360f\n                    if (sweep > 0.1f) drawArc(palette[index % palette.size], startAngle = start, sweepAngle = sweep, useCenter = false, style = stroke)\n                    start += sweep\n                }\n            }\n        }\n        Column(horizontalAlignment = Alignment.CenterHorizontally) {\n            Text("Asset", color = PreviewText, fontSize = 11.sp)\n            Text("Allocation", color = PreviewText, fontSize = 11.sp)\n        }\n    }\n}\n\n@Composable\nprivate fun AllocationRow(assetName: String, value: BigDecimal, total: BigDecimal) {\n    val share = if (total > BigDecimal.ZERO) value.multiply(BigDecimal("100")).divide(total, 2, RoundingMode.HALF_UP) else BigDecimal.ZERO\n    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {\n        Box(Modifier.size(7.dp).background(previewAssetColor(assetName), RoundedCornerShape(1.dp))) {}\n        Spacer(Modifier.width(8.dp))\n        Text(assetName.uppercase().replace("XBT", "BTC"), color = PreviewText, fontSize = 11.sp, modifier = Modifier.weight(1f))\n        Text(pct(share), color = PreviewText, fontSize = 10.sp, modifier = Modifier.width(52.dp))\n        Text(euro(value), color = PreviewText, fontSize = 10.sp)\n    }\n}\n\nprivate fun previewAssetColor(asset: String): Color = when (asset.uppercase().replace("XBT", "BTC")) {\n    "BTC" -> PreviewOrange\n    "ETH" -> PreviewBlue\n    "KAS" -> PreviewMint\n    "HBAR" -> PreviewPurple\n    else -> Color(0xFF7E72BD)\n}\n\n@Composable\nprivate fun PreviewSegmentTabs(labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {\n    Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {\n        labels.forEachIndexed { index, label ->\n            Column(\n                Modifier.weight(1f).fillMaxHeight().clickable { onSelected(index) },\n                horizontalAlignment = Alignment.CenterHorizontally,\n                verticalArrangement = Arrangement.Center\n            ) {\n                Text(label, color = if (selected == index) PreviewPurpleSoft else PreviewMuted, fontSize = 10.sp, fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal)\n                Spacer(Modifier.height(8.dp))\n                Box(Modifier.fillMaxWidth(0.8f).height(2.dp).background(if (selected == index) PreviewPurple else Color.Transparent)) {}\n            }\n        }\n    }\n}\n\n@Composable\nfun PreviewAiHubScreen(\n    decisions: List<AiDecision>,\n    settings: BotSettings,\n    performanceLabSnapshot: com.ksp.cryptobot.core.PerformanceLabSnapshot?,\n    trades: List<TradeEntity>,\n    onOpen: (AppTab) -> Unit,\n    onSelectSignal: (AiDecision) -> Unit,\n    onScan: () -> Unit\n) {\n    var segment by remember { mutableIntStateOf(0) }\n    val segments = listOf("AI Signals", "Research", "Backtest")\n    val scored = decisions.sortedByDescending { it.finalScore }\n    val average = if (scored.isEmpty()) 50 else scored.map { it.finalScore }.average().toInt().coerceIn(0, 100)\n    val buys = scored.count { it.finalAction == SignalAction.BUY || it.finalAction == SignalAction.SMALL_BUY }\n    val sells = scored.count { it.finalAction == SignalAction.SELL }\n    val bias = when {\n        buys > sells && average >= 55 -> "BULLISH"\n        sells > buys && average <= 45 -> "BEARISH"\n        else -> "NEUTRAL"\n    }\n    val biasColor = when (bias) { "BULLISH" -> PreviewGreen; "BEARISH" -> PreviewRed; else -> PreviewMuted }\n    val cutoff30d = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L\n    val sellTrades = trades.filter { it.side.equals("SELL", true) && it.timestampEpochMs >= cutoff30d }\n    val wins = sellTrades.count { safeBig(it.realizedPnlEur) > BigDecimal.ZERO }\n    val losses = sellTrades.filter { safeBig(it.realizedPnlEur) < BigDecimal.ZERO }\n    val winRate = if (sellTrades.isEmpty()) BigDecimal.ZERO else BigDecimal(wins * 100).divide(BigDecimal(sellTrades.size), 1, RoundingMode.HALF_UP)\n    val grossWin = sellTrades.filter { safeBig(it.realizedPnlEur) > BigDecimal.ZERO }.fold(BigDecimal.ZERO) { a, t -> a.add(safeBig(t.realizedPnlEur)) }\n    val grossLoss = losses.fold(BigDecimal.ZERO) { a, t -> a.add(safeBig(t.realizedPnlEur).abs()) }\n    val pf = if (grossLoss > BigDecimal.ZERO) grossWin.divide(grossLoss, 2, RoundingMode.HALF_UP) else if (grossWin > BigDecimal.ZERO) BigDecimal("99") else BigDecimal.ZERO\n\n    LazyColumn(\n        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),\n        contentPadding = PaddingValues(bottom = 16.dp),\n        verticalArrangement = Arrangement.spacedBy(8.dp)\n    ) {\n        item { PreviewSegmentTabs(segments, segment) { segment = it } }\n        when (segment) {\n            1 -> {\n                item {\n                    PreviewCardBox(onClick = { onOpen(AppTab.RESEARCH_SETTINGS) }) {\n                        Column {\n                            PreviewSectionHeader("Research Intelligence")\n                            Spacer(Modifier.height(6.dp))\n                            Text("Professional research, handoff truth, robustness validation and external context.", color = PreviewMuted, fontSize = 11.sp)\n                            Spacer(Modifier.height(10.dp))\n                            PreviewOutlineButton("Open Research", { onOpen(AppTab.RESEARCH_SETTINGS) }, Modifier.fillMaxWidth())\n                        }\n                    }\n                }\n            }\n            2 -> {\n                item {\n                    PreviewCardBox(onClick = { onOpen(AppTab.BACKTEST) }) {\n                        Column {\n                            PreviewSectionHeader("Backtest Lab")\n                            Spacer(Modifier.height(6.dp))\n                            Text("Kraken OHLC backtests and forward gates before live promotion.", color = PreviewMuted, fontSize = 11.sp)\n                            Spacer(Modifier.height(10.dp))\n                            PreviewOutlineButton("Open Backtest", { onOpen(AppTab.BACKTEST) }, Modifier.fillMaxWidth())\n                        }\n                    }\n                }\n            }\n            else -> {\n                item {\n                    PreviewCardBox {\n                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n                            Column(Modifier.weight(1f)) {\n                                Text("Market Bias", color = PreviewMuted, fontSize = 10.sp)\n                                Spacer(Modifier.height(6.dp))\n                                Text(bias, color = biasColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)\n                                Spacer(Modifier.height(13.dp))\n                                Text("Confidence", color = PreviewMuted, fontSize = 10.sp)\n                            }\n                            PreviewConfidenceGauge(average, biasColor, Modifier.size(92.dp))\n                        }\n                    }\n                }\n                item {\n                    PreviewCardBox {\n                        Column {\n                            PreviewSectionHeader("Top AI Signals")\n                            Spacer(Modifier.height(6.dp))\n                            if (scored.isEmpty()) {\n                                Text("No AI scan loaded yet", color = PreviewMuted, fontSize = 11.sp)\n                            } else {\n                                scored.take(4).forEachIndexed { index, decision ->\n                                    AiSignalRow(decision) { onSelectSignal(decision) }\n                                    if (index < scored.take(4).lastIndex) Divider(color = PreviewDivider.copy(alpha = 0.65f), modifier = Modifier.padding(vertical = 5.dp))\n                                }\n                            }\n                            Spacer(Modifier.height(7.dp))\n                            Row(Modifier.fillMaxWidth().clickable { onOpen(AppTab.AI_SIGNALS) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {\n                                Text("View All AI Signals", color = PreviewText, fontSize = 11.sp, modifier = Modifier.weight(1f))\n                                Icon(Icons.Rounded.ChevronRight, null, tint = PreviewMuted, modifier = Modifier.size(18.dp))\n                            }\n                        }\n                    }\n                }\n                item {\n                    PreviewCardBox {\n                        Column {\n                            PreviewSectionHeader("AI Performance (30D)")\n                            Spacer(Modifier.height(10.dp))\n                            Row(Modifier.fillMaxWidth()) {\n                                Column(Modifier.weight(1f)) {\n                                    Text("Win Rate", color = PreviewMuted, fontSize = 10.sp)\n                                    Text(pct(winRate), color = PreviewGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)\n                                }\n                                Column(Modifier.weight(1f)) {\n                                    Text("Profit Factor", color = PreviewMuted, fontSize = 10.sp)\n                                    Text(pf.stripTrailingZeros().toPlainString(), color = PreviewGreen, fontSize = 18.sp, fontWeight = FontWeight.Bold)\n                                }\n                            }\n                            performanceLabSnapshot?.summaryLine?.takeIf { it.isNotBlank() }?.let {\n                                Spacer(Modifier.height(7.dp)); Text(it, color = PreviewMuted, fontSize = 9.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)\n                            }\n                        }\n                    }\n                }\n                item { PreviewOutlineButton("Scan AI Signals", onScan, Modifier.fillMaxWidth()) }\n            }\n        }\n    }\n}\n\n@Composable\nprivate fun AiSignalRow(decision: AiDecision, onClick: () -> Unit) {\n    val action = decision.finalAction.name.replace(\'_\', \' \')\n    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {\n        AssetIcon(previewBase(decision.symbol), 24)\n        Spacer(Modifier.width(8.dp))\n        Text(previewPair(decision.symbol), color = PreviewText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(70.dp))\n        Text(action, color = previewActionColor(decision.finalAction), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(58.dp))\n        Text(previewSignalDescriptor(decision), color = PreviewMuted, fontSize = 9.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)\n        Text("${decision.confidencePercent}%", color = PreviewText, fontSize = 10.sp)\n    }\n}\n\nprivate fun previewSignalDescriptor(decision: AiDecision): String = when (decision.finalAction) {\n    SignalAction.BUY -> "Strong Uptrend"\n    SignalAction.SMALL_BUY -> "Momentum Building"\n    SignalAction.WATCH -> "Watch"\n    SignalAction.WAIT -> "Neutral"\n    SignalAction.AVOID -> "Weak Momentum"\n    SignalAction.STRONG_AVOID -> "Risk Elevated"\n    SignalAction.SELL -> "Exit Signal"\n}\n\nprivate fun previewActionColor(action: SignalAction): Color = when (action) {\n    SignalAction.BUY, SignalAction.SMALL_BUY -> PreviewGreen\n    SignalAction.SELL, SignalAction.AVOID, SignalAction.STRONG_AVOID -> PreviewRed\n    SignalAction.WATCH -> PreviewOrange\n    else -> PreviewMuted\n}\n\n@Composable\nprivate fun PreviewConfidenceGauge(value: Int, color: Color, modifier: Modifier = Modifier) {\n    Box(modifier, contentAlignment = Alignment.Center) {\n        Canvas(Modifier.fillMaxSize()) {\n            val stroke = Stroke(width = 9f, cap = StrokeCap.Round)\n            drawArc(PreviewDivider, startAngle = 150f, sweepAngle = 240f, useCenter = false, style = stroke)\n            drawArc(color, startAngle = 150f, sweepAngle = 240f * value.coerceIn(0, 100) / 100f, useCenter = false, style = stroke)\n        }\n        Column(horizontalAlignment = Alignment.CenterHorizontally) {\n            Text("$value%", color = PreviewText, fontSize = 14.sp, fontWeight = FontWeight.Bold)\n            Text("Confidence", color = PreviewMuted, fontSize = 8.sp)\n        }\n    }\n}\n\n@Composable\nfun PreviewAiSignalsListScreen(\n    decisions: List<AiDecision>,\n    settings: BotSettings,\n    activePositionSymbols: List<String>,\n    onScan: () -> Unit,\n    onSelectSignal: (AiDecision) -> Unit\n) {\n    val active = activePositionSymbols.map { it.uppercase().replace("/", "").replace("-", "") }.toSet()\n    val rows = decisions.sortedWith(compareByDescending<AiDecision> { active.contains(it.symbol.uppercase().replace("/", "").replace("-", "")) }.thenByDescending { it.finalScore })\n    LazyColumn(\n        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),\n        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),\n        verticalArrangement = Arrangement.spacedBy(8.dp)\n    ) {\n        item {\n            PreviewCardBox {\n                Column {\n                    Text("Real-time AI decisions", color = PreviewText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)\n                    Text("${rows.size} signal(s) • Strategy ${settings.strategyMode.name.replace(\'_\', \' \')}", color = PreviewMuted, fontSize = 10.sp)\n                    Spacer(Modifier.height(9.dp))\n                    PreviewOutlineButton("Scan AI Signals", onScan, Modifier.fillMaxWidth())\n                }\n            }\n        }\n        if (rows.isEmpty()) item { PreviewEmptyCard("No AI signals loaded") }\n        items(rows) { decision ->\n            PreviewCardBox(onClick = { onSelectSignal(decision) }) {\n                Column {\n                    AiSignalRow(decision) { onSelectSignal(decision) }\n                    Spacer(Modifier.height(5.dp))\n                    Text(decision.explanation, color = PreviewMuted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)\n                }\n            }\n        }\n    }\n}\n\n@Composable\nfun PreviewAiSignalDetailScreen(decision: AiDecision?, candles: List<Candle>) {\n    if (decision == null) {\n        Box(Modifier.fillMaxSize().background(PreviewBackground), contentAlignment = Alignment.Center) { Text("No signal selected", color = PreviewMuted) }\n        return\n    }\n    val recent20 = candles.takeLast(20)\n    val recent60 = candles.takeLast(60)\n    val support1 = recent20.minOfOrNull { it.low }\n    val support2 = recent60.minOfOrNull { it.low }\n    val resistance1 = recent20.maxOfOrNull { it.high }\n    val resistance2 = recent60.maxOfOrNull { it.high }\n    val actionColor = previewActionColor(decision.finalAction)\n    LazyColumn(\n        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),\n        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),\n        verticalArrangement = Arrangement.spacedBy(8.dp)\n    ) {\n        item {\n            PreviewCardBox {\n                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n                    Column(Modifier.weight(1f)) {\n                        Text(decision.finalAction.name.replace(\'_\', \' \'), color = actionColor, fontSize = 22.sp, fontWeight = FontWeight.Bold)\n                        Spacer(Modifier.height(6.dp))\n                        Text(previewSignalDescriptor(decision), color = PreviewText, fontSize = 11.sp)\n                    }\n                    PreviewConfidenceGauge(decision.confidencePercent, actionColor, Modifier.size(94.dp))\n                }\n            }\n        }\n        item {\n            PreviewCardBox {\n                Column {\n                    PreviewSectionHeader("Analysis")\n                    Spacer(Modifier.height(9.dp))\n                    Text(decision.explanation, color = PreviewText, fontSize = 11.sp, lineHeight = 16.sp)\n                    Spacer(Modifier.height(7.dp))\n                    Text("Technical ${decision.technicalScore} • News ${decision.newsScore} • Memory ${decision.memoryScore}", color = PreviewMuted, fontSize = 9.sp)\n                }\n            }\n        }\n        item {\n            PreviewCardBox {\n                Column {\n                    PreviewSectionHeader("Key Levels")\n                    Spacer(Modifier.height(8.dp))\n                    KeyLevelRow("Support 1", support1)\n                    KeyLevelRow("Support 2", support2)\n                    KeyLevelRow("Resistance 1", resistance1)\n                    KeyLevelRow("Resistance 2", resistance2)\n                    if (candles.isEmpty()) {\n                        Spacer(Modifier.height(6.dp)); Text("Load chart data to calculate live support/resistance levels.", color = PreviewMuted, fontSize = 9.sp)\n                    }\n                }\n            }\n        }\n    }\n}\n\n@Composable\nprivate fun KeyLevelRow(label: String, price: BigDecimal?) {\n    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {\n        Text(label, color = PreviewText, fontSize = 10.sp, modifier = Modifier.weight(1f))\n        Text(price?.let(::euro) ?: "—", color = PreviewText, fontSize = 10.sp)\n    }\n}\n\n@Composable\nfun PreviewNewsScreen(\n    settings: BotSettings,\n    newsHistory: List<NewsArticleEntity>,\n    activeSymbols: List<String>,\n    decisions: List<AiDecision>,\n    onToggleNews: (Boolean) -> Unit,\n    onRefreshHistory: (String) -> Unit,\n    onScanNews: (String) -> Unit\n) {\n    val newsScores = decisions.map { it.newsScore }\n    val sentiment = if (newsScores.isEmpty()) 50 else (50 + newsScores.average().toInt()).coerceIn(0, 100)\n    val label = when { sentiment >= 60 -> "BULLISH"; sentiment <= 40 -> "BEARISH"; else -> "NEUTRAL" }\n    val sentimentColor = when (label) { "BULLISH" -> PreviewGreen; "BEARISH" -> PreviewRed; else -> PreviewMuted }\n    val stories = newsHistory.sortedByDescending { it.publishedAtEpochMs }.take(8)\n    val providers = NewsProviderHealthRegistry.snapshot()\n    val targetSymbol = (activeSymbols + settings.symbols()).firstOrNull().orEmpty()\n\n    LazyColumn(\n        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),\n        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),\n        verticalArrangement = Arrangement.spacedBy(8.dp)\n    ) {\n        item {\n            PreviewCardBox {\n                Column {\n                    Text("Overall News Sentiment", color = PreviewMuted, fontSize = 10.sp)\n                    Spacer(Modifier.height(4.dp))\n                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n                        Text(label, color = sentimentColor, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))\n                        Text("$sentiment/100", color = PreviewText, fontSize = 15.sp)\n                    }\n                    Spacer(Modifier.height(7.dp))\n                    LinearProgressIndicator(\n                        progress = sentiment / 100f,\n                        modifier = Modifier.fillMaxWidth().height(5.dp),\n                        color = sentimentColor,\n                        trackColor = PreviewDivider\n                    )\n                    Spacer(Modifier.height(6.dp))\n                    Text("Updated from the latest stored decision/news inputs", color = PreviewMuted2, fontSize = 8.sp, modifier = Modifier.fillMaxWidth())\n                }\n            }\n        }\n        item {\n            PreviewCardBox {\n                Column {\n                    PreviewSectionHeader("Top Stories")\n                    Spacer(Modifier.height(7.dp))\n                    if (stories.isEmpty()) {\n                        Text("No cached stories yet", color = PreviewMuted, fontSize = 11.sp)\n                    } else {\n                        stories.take(5).forEachIndexed { index, story ->\n                            NewsStoryRow(story)\n                            if (index < minOf(4, stories.lastIndex)) Divider(color = PreviewDivider.copy(alpha = 0.65f), modifier = Modifier.padding(vertical = 6.dp))\n                        }\n                    }\n                    Spacer(Modifier.height(4.dp))\n                    Row(Modifier.fillMaxWidth().clickable { onRefreshHistory("") }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {\n                        Text("View All News", color = PreviewText, fontSize = 11.sp, modifier = Modifier.weight(1f))\n                        Icon(Icons.Rounded.ChevronRight, null, tint = PreviewMuted, modifier = Modifier.size(18.dp))\n                    }\n                }\n            }\n        }\n        item {\n            PreviewCardBox {\n                Column {\n                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n                        Text("News Intelligence", color = PreviewText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))\n                        Switch(\n                            checked = settings.useNewsAi,\n                            onCheckedChange = onToggleNews,\n                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PreviewGreen, uncheckedThumbColor = PreviewMuted, uncheckedTrackColor = PreviewDivider)\n                        )\n                    }\n                    if (providers.isNotEmpty()) {\n                        Spacer(Modifier.height(4.dp))\n                        providers.take(8).forEach { provider ->\n                            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {\n                                Text(provider.provider, color = PreviewMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))\n                                val healthy = provider.status == "HEALTHY" || provider.status == "READY" || provider.status == "EMPTY"\n                                Text(provider.status, color = if (healthy) PreviewGreen else PreviewRed, fontSize = 9.sp)\n                            }\n                        }\n                    }\n                    Spacer(Modifier.height(8.dp))\n                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n                        PreviewOutlineButton("Refresh", { onRefreshHistory("") }, Modifier.weight(1f))\n                        PreviewPrimaryButton("Scan News", { onScanNews(targetSymbol) }, Modifier.weight(1f))\n                    }\n                }\n            }\n        }\n    }\n}\n\n@Composable\nprivate fun NewsStoryRow(story: NewsArticleEntity) {\n    val base = previewBase(story.symbol)\n    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n        AssetIcon(base.ifBlank { "N" }, 27)\n        Spacer(Modifier.width(9.dp))\n        Column(Modifier.weight(1f)) {\n            Text(story.title, color = PreviewText, fontSize = 10.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)\n            val age = previewAge(story.publishedAtEpochMs)\n            Text(age, color = PreviewMuted2, fontSize = 8.sp)\n        }\n        Text(story.provider.ifBlank { story.source }.take(10), color = PreviewGreen, fontSize = 8.sp, maxLines = 1)\n    }\n}\n\nprivate fun previewAge(epochMs: Long): String {\n    if (epochMs <= 0L) return ""\n    val mins = ((System.currentTimeMillis() - epochMs).coerceAtLeast(0L) / 60000L)\n    return when {\n        mins < 60 -> "${mins}m ago"\n        mins < 1440 -> "${mins / 60}h ago"\n        else -> "${mins / 1440}d ago"\n    }\n}\n\n@Composable\nfun PreviewSettingsScreen(\n    settings: BotSettings,\n    portfolio: PortfolioSnapshot?,\n    onPersist: (BotSettings) -> Unit,\n    onOpen: (AppTab) -> Unit\n) {\n    var tab by remember { mutableIntStateOf(0) }\n    val labels = listOf("Connection & Trading", "Automation & Risk")\n    LazyColumn(\n        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),\n        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),\n        verticalArrangement = Arrangement.spacedBy(8.dp)\n    ) {\n        item { PreviewSettingsSegment(labels, tab) { tab = it } }\n        if (tab == 0) {\n            item {\n                PreviewCardBox {\n                    Column {\n                        PreviewSectionHeader("Exchange Connection")\n                        Spacer(Modifier.height(8.dp))\n                        SettingsValueRow("Exchange", settings.exchangeProvider.name.replace(\'_\', \' \').lowercase().replaceFirstChar { it.uppercase() })\n                        SettingsValueRow("Status", previewExchangeStatus(settings, portfolio), valueColor = if ((settings.exchangeProvider == ExchangeProvider.KRAKEN && portfolio != null) || settings.exchangeProvider == ExchangeProvider.PAPER) PreviewGreen else PreviewMuted)\n                        SettingsNavRow("API Management") { onOpen(AppTab.BASIC_SETTINGS) }\n                    }\n                }\n            }\n            item {\n                PreviewCardBox {\n                    Column {\n                        PreviewSectionHeader("Trading Mode")\n                        Spacer(Modifier.height(8.dp))\n                        SettingsValueRow("Live Trading", if (settings.mode == BotMode.LIVE_AUTO) "ENABLED" else "DISABLED", if (settings.mode == BotMode.LIVE_AUTO) PreviewGreen else PreviewRed)\n                        SettingsValueRow("Paper Trading", if (settings.mode == BotMode.PAPER) "ENABLED" else "DISABLED", if (settings.mode == BotMode.PAPER) PreviewGreen else PreviewRed)\n                        SettingsNavRow("Change Trading Mode") { onOpen(AppTab.BASIC_SETTINGS) }\n                    }\n                }\n            }\n            item {\n                PreviewCardBox {\n                    Column {\n                        PreviewSectionHeader("Account")\n                        Spacer(Modifier.height(8.dp))\n                        SettingsValueRow("Account Balance", euro(portfolio?.totalValueEur ?: BigDecimal.ZERO))\n                        SettingsValueRow("Currency", "EUR")\n                        SettingsValueRow("Server Time", previewTimeFormatter.format(java.time.ZonedDateTime.now()))\n                    }\n                }\n            }\n            item {\n                PreviewCardBox {\n                    Column {\n                        PreviewSectionHeader("Data & System")\n                        Spacer(Modifier.height(4.dp))\n                        SettingsNavRow("Backup & Recovery") { onOpen(AppTab.BACKUP) }\n                        SettingsNavRow("CloudShare") { onOpen(AppTab.CLOUDSHARE_SETTINGS) }\n                        SettingsNavRow("System Test") { onOpen(AppTab.SYSTEM_TEST) }\n                        SettingsNavRow("Notifications") { onOpen(AppTab.NOTIFICATIONS) }\n                    }\n                }\n            }\n        } else {\n            item {\n                PreviewCardBox {\n                    Column {\n                        PreviewSectionHeader("Automation")\n                        Spacer(Modifier.height(5.dp))\n                        SettingsToggleRow("Enable Auto Trading", settings.ultimateAutomationEnabled) { onPersist(settings.copy(ultimateAutomationEnabled = it)) }\n                        SettingsToggleRow("AI Auto Trading", settings.autoTradeMultipleSymbolsPerScan) { onPersist(settings.copy(autoTradeMultipleSymbolsPerScan = it)) }\n                        SettingsToggleRow("Auto Exit Manager", settings.autoExitManagerEnabled) { onPersist(settings.copy(autoExitManagerEnabled = it)) }\n                        SettingsToggleRow("Auto Stop Loss", settings.autoStopLossEnabled) { onPersist(settings.copy(autoStopLossEnabled = it)) }\n                        SettingsToggleRow("Auto Take Profit", settings.autoTakeProfitEnabled) { onPersist(settings.copy(autoTakeProfitEnabled = it)) }\n                        SettingsToggleRow("Trailing Stop", settings.enableTrailingStop) { onPersist(settings.copy(enableTrailingStop = it)) }\n                    }\n                }\n            }\n            item {\n                PreviewCardBox {\n                    Column {\n                        PreviewSectionHeader("Risk Management")\n                        Spacer(Modifier.height(7.dp))\n                        SettingsValueRow("Max Position (EUR)", euro(settings.maxPositionEur))\n                        SettingsValueRow("Max Daily Loss (EUR)", euro(settings.maxDailyLossEur))\n                        SettingsValueRow("Max Trades Per Day", settings.maxTradesPerDay.toString())\n                        SettingsValueRow("Max Trades Per Hour", settings.maxTradesPerHour.toString())\n                        SettingsValueRow("Max Active Positions", settings.maxSimultaneousLivePositions.toString())\n                        SettingsNavRow("Advanced Risk Controls") { onOpen(AppTab.ADVANCED_SETTINGS) }\n                    }\n                }\n            }\n        }\n    }\n}\n\nprivate fun previewExchangeStatus(settings: BotSettings, portfolio: PortfolioSnapshot?): String = when (settings.exchangeProvider) {\n    ExchangeProvider.KRAKEN -> if (portfolio != null) "Connected" else "Not verified"\n    ExchangeProvider.PAPER -> "Paper"\n    ExchangeProvider.BINANCE_READ_ONLY -> "Read Only"\n    ExchangeProvider.MANUAL -> "Manual"\n    else -> "Configured"\n}\n\n@Composable\nprivate fun PreviewSettingsSegment(labels: List<String>, selected: Int, onSelected: (Int) -> Unit) {\n    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {\n        labels.forEachIndexed { index, label ->\n            Surface(\n                modifier = Modifier.weight(1f).height(36.dp).clickable { onSelected(index) },\n                color = if (selected == index) PreviewCardAlt else Color.Transparent,\n                shape = RoundedCornerShape(5.dp),\n                border = androidx.compose.foundation.BorderStroke(1.dp, if (selected == index) PreviewPurple else PreviewDivider)\n            ) {\n                Box(contentAlignment = Alignment.Center) {\n                    Text(label, color = if (selected == index) PreviewPurpleSoft else PreviewMuted, fontSize = 10.sp, fontWeight = FontWeight.Medium)\n                }\n            }\n        }\n    }\n}\n\n@Composable\nprivate fun SettingsValueRow(label: String, value: String, valueColor: Color = PreviewText) {\n    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {\n        Text(label, color = PreviewText, fontSize = 10.sp, modifier = Modifier.weight(1f))\n        Text(value, color = valueColor, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)\n    }\n}\n\n@Composable\nprivate fun SettingsNavRow(label: String, onClick: () -> Unit) {\n    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {\n        Text(label, color = PreviewText, fontSize = 10.sp, modifier = Modifier.weight(1f))\n        Icon(Icons.Rounded.ChevronRight, null, tint = PreviewMuted, modifier = Modifier.size(17.dp))\n    }\n}\n\n@Composable\nprivate fun SettingsToggleRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {\n    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {\n        Text(label, color = PreviewText, fontSize = 10.sp, modifier = Modifier.weight(1f))\n        Switch(\n            checked = checked,\n            onCheckedChange = onChecked,\n            modifier = Modifier.size(width = 42.dp, height = 26.dp),\n            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = PreviewGreen, uncheckedThumbColor = PreviewMuted, uncheckedTrackColor = PreviewDivider)\n        )\n    }\n}\n\n@Composable\nfun PreviewSystemTestScreen(settings: BotSettings, lines: List<String>, onRun: () -> Unit) {\n    val pass = lines.count { it.startsWith("PASS") }\n    val fail = lines.count { it.startsWith("FAIL") }\n    val warn = lines.count { it.startsWith("WARN") }\n    val total = max(1, pass + fail + warn)\n    val score = ((pass * 100f) / total).coerceIn(0f, 100f)\n    val healthy = fail == 0 && lines.isNotEmpty()\n    LazyColumn(\n        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),\n        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),\n        verticalArrangement = Arrangement.spacedBy(8.dp)\n    ) {\n        item {\n            PreviewCardBox {\n                Column {\n                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n                        Icon(if (healthy) Icons.Rounded.Security else Icons.Rounded.WarningAmber, null, tint = if (healthy) PreviewGreen else PreviewOrange, modifier = Modifier.size(35.dp))\n                        Spacer(Modifier.width(10.dp))\n                        Text(if (healthy) "ALL SYSTEMS OPERATIONAL" else if (lines.isEmpty()) "SYSTEM TEST NOT RUN" else "SYSTEM CHECK REQUIRED", color = if (healthy) PreviewGreen else PreviewOrange, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))\n                    }\n                    Spacer(Modifier.height(14.dp))\n                    Row(Modifier.fillMaxWidth()) {\n                        Text("Last Full Test", color = PreviewMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))\n                        Text(if (lines.isEmpty()) "—" else previewTimeFormatter.format(java.time.ZonedDateTime.now()), color = PreviewText, fontSize = 9.sp)\n                    }\n                    Spacer(Modifier.height(9.dp))\n                    Row(Modifier.fillMaxWidth()) {\n                        Text("Overall Health", color = PreviewMuted, fontSize = 9.sp, modifier = Modifier.weight(1f))\n                        Text("${score.toInt()}%", color = if (healthy) PreviewGreen else PreviewOrange, fontSize = 16.sp, fontWeight = FontWeight.Bold)\n                    }\n                    LinearProgressIndicator(progress = score / 100f, modifier = Modifier.fillMaxWidth().height(5.dp), color = if (healthy) PreviewGreen else PreviewOrange, trackColor = PreviewDivider)\n                }\n            }\n        }\n        item {\n            PreviewCardBox {\n                Column {\n                    PreviewSectionHeader("Systems")\n                    Spacer(Modifier.height(5.dp))\n                    if (lines.isEmpty()) {\n                        SystemLine("Settings & Persistence", "PENDING")\n                        SystemLine("News & Data Providers", "PENDING")\n                        SystemLine("AI & Research Engine", "PENDING")\n                        SystemLine("M3 Governance", "PENDING")\n                        SystemLine("M4 Execution Guard", "PENDING")\n                        SystemLine("Lifecycle & Risk", "PENDING")\n                        SystemLine("Learning & Journal", "PENDING")\n                        SystemLine("CloudShare & Recovery", "PENDING")\n                    } else {\n                        lines.take(20).forEach { line ->\n                            val parts = line.split("|").map { it.trim() }\n                            SystemLine(parts.getOrNull(1) ?: line.take(40), parts.firstOrNull() ?: "INFO")\n                        }\n                    }\n                }\n            }\n        }\n        item { PreviewPrimaryButton(if (lines.isEmpty()) "Run Full System Test" else "Run Test Again", onRun, Modifier.fillMaxWidth()) }\n        item { Text("Mode ${settings.mode.name.replace(\'_\', \' \')} • Provider ${settings.exchangeProvider.name.replace(\'_\', \' \')}", color = PreviewMuted, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 4.dp)) }\n    }\n}\n\n@Composable\nprivate fun SystemLine(label: String, status: String) {\n    val normalized = status.uppercase()\n    val color = when (normalized) { "PASS", "OK" -> PreviewGreen; "FAIL" -> PreviewRed; "WARN" -> PreviewOrange; else -> PreviewMuted }\n    val icon = when (normalized) { "PASS", "OK" -> Icons.Rounded.CheckCircle; "FAIL" -> Icons.Rounded.ErrorOutline; "WARN" -> Icons.Rounded.WarningAmber; else -> Icons.Rounded.Sync }\n    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {\n        Icon(icon, null, tint = color, modifier = Modifier.size(12.dp))\n        Spacer(Modifier.width(7.dp))\n        Text(label, color = PreviewText, fontSize = 9.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)\n        Text(normalized, color = color, fontSize = 9.sp)\n    }\n}\n\n@Composable\nfun PreviewPositionsScreen(settings: BotSettings, snapshot: LifecycleSnapshot?, onRefresh: () -> Unit) {\n    val positions = snapshot?.positions.orEmpty().filter { it.quantity > BigDecimal.ZERO }\n    val totalValue = positions.fold(BigDecimal.ZERO) { acc, p -> acc.add(p.currentPrice.multiply(p.quantity)) }\n    val pnl = positions.fold(BigDecimal.ZERO) { acc, p -> acc.add(p.unrealizedPnlEur) }\n    val pct = if (totalValue.subtract(pnl) > BigDecimal.ZERO) pnl.multiply(BigDecimal("100")).divide(totalValue.subtract(pnl), 4, RoundingMode.HALF_UP) else BigDecimal.ZERO\n    LazyColumn(\n        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),\n        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),\n        verticalArrangement = Arrangement.spacedBy(8.dp)\n    ) {\n        item {\n            Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 3.dp)) {\n                Column(Modifier.weight(1f)) {\n                    Text("Total Value", color = PreviewMuted, fontSize = 9.sp)\n                    Text(euro(totalValue), color = PreviewGreen, fontSize = 17.sp, fontWeight = FontWeight.Bold)\n                }\n                Column(horizontalAlignment = Alignment.End) {\n                    Text("Open P/L", color = PreviewMuted, fontSize = 9.sp)\n                    Text((if (pnl >= BigDecimal.ZERO) "+" else "") + euro(pnl) + " (" + (if (pct >= BigDecimal.ZERO) "+" else "") + pct(pct) + ")", color = if (pnl >= BigDecimal.ZERO) PreviewGreen else PreviewRed, fontSize = 9.sp)\n                }\n            }\n        }\n        if (positions.isEmpty()) item { PreviewEmptyCard("No active positions") }\n        items(positions) { position ->\n            PreviewCardBox {\n                Column {\n                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {\n                        AssetIcon(previewBase(position.symbol), 28)\n                        Spacer(Modifier.width(9.dp))\n                        Text(previewPair(position.symbol), color = PreviewText, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, modifier = Modifier.weight(1f))\n                        Icon(Icons.Rounded.ChevronRight, null, tint = PreviewMuted, modifier = Modifier.size(18.dp))\n                    }\n                    Spacer(Modifier.height(9.dp))\n                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {\n                        TinyValue("Amount", "${position.quantity.stripTrailingZeros().toPlainString()} ${previewBase(position.symbol)}")\n                        TinyValue("Avg. Price", euro(position.entryPrice))\n                        TinyValue("Current", euro(position.currentPrice), Alignment.End)\n                    }\n                    Spacer(Modifier.height(6.dp))\n                    Text("P/L", color = PreviewMuted, fontSize = 8.sp)\n                    Text((if (position.unrealizedPnlEur >= BigDecimal.ZERO) "+" else "") + euro(position.unrealizedPnlEur) + " (" + (if (position.unrealizedPnlPercent >= BigDecimal.ZERO) "+" else "") + pct(position.unrealizedPnlPercent) + ")", color = if (position.unrealizedPnlEur >= BigDecimal.ZERO) PreviewGreen else PreviewRed, fontSize = 10.sp)\n                }\n            }\n        }\n        item { PreviewOutlineButton("Refresh Positions", onRefresh, Modifier.fillMaxWidth()) }\n        item { Text("Exit manager ${if (settings.autoExitManagerEnabled) "ON" else "OFF"} • Stop loss ${if (settings.autoStopLossEnabled) "ON" else "OFF"}", color = PreviewMuted, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 4.dp)) }\n    }\n}\n\n@Composable\nprivate fun TinyValue(label: String, value: String, alignment: Alignment.Horizontal = Alignment.Start) {\n    Column(horizontalAlignment = alignment) {\n        Text(label, color = PreviewMuted, fontSize = 8.sp)\n        Text(value, color = PreviewText, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)\n    }\n}\n\n@Composable\nfun PreviewBackupRecoveryScreen(\n    settings: BotSettings,\n    backupDirectoryPath: String,\n    onBackupDirectoryPathChanged: (String) -> Unit,\n    onExportFullBackup: (String, (String) -> Unit) -> Unit,\n    onRestoreFullBackup: (String, Boolean, (String) -> Unit) -> Unit,\n    onApplySafeDefaults: () -> Unit\n) {\n    val context = androidx.compose.ui.platform.LocalContext.current\n    val cloudStore = remember { CloudShareSettingsStore(context) }\n    var status by remember { mutableStateOf("Ready") }\n    var selectedRestore by remember { mutableStateOf("") }\n    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->\n        uri?.let { selected ->\n            onBackupDirectoryPathChanged(selected.toString())\n            status = "Backup destination selected"\n        }\n    }\n    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->\n        selectedRestore = uri?.toString().orEmpty()\n        if (selectedRestore.isNotBlank()) status = "Backup selected for restore"\n    }\n\n    LazyColumn(\n        modifier = Modifier.fillMaxSize().background(PreviewBackground).padding(horizontal = 12.dp),\n        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),\n        verticalArrangement = Arrangement.spacedBy(8.dp)\n    ) {\n        item { PreviewSettingsSegment(listOf("Backup & Recovery", "System"), 0) { if (it == 1) status = "Open System Test from Settings" } }\n        item {\n            PreviewCardBox {\n                Column {\n                    PreviewSectionHeader("CloudShare Backup")\n                    Spacer(Modifier.height(8.dp))\n                    SettingsValueRow("Backup Folder", backupDirectoryPath.ifBlank { "Default app backup folder" })\n                    SettingsValueRow("CloudShare Status", if (cloudStore.enabled) "Enabled" else "Disabled", if (cloudStore.enabled) PreviewGreen else PreviewMuted)\n                    Spacer(Modifier.height(7.dp))\n                    PreviewOutlineButton("Select Backup Folder", { folderPicker.launch(null) }, Modifier.fillMaxWidth())\n                    Spacer(Modifier.height(7.dp))\n                    PreviewOutlineButton("Run Backup Now", {\n                        onExportFullBackup(backupDirectoryPath) { result -> status = result }\n                    }, Modifier.fillMaxWidth())\n                }\n            }\n        }\n        item {\n            PreviewCardBox {\n                Column {\n                    PreviewSectionHeader("Recovery")\n                    Spacer(Modifier.height(8.dp))\n                    SettingsValueRow("Selected Backup", if (selectedRestore.isBlank()) "None" else "Ready")\n                    SettingsValueRow("Restore Mode", "Manual")\n                    Spacer(Modifier.height(7.dp))\n                    PreviewOutlineButton("Select Backup File", { filePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, Modifier.fillMaxWidth())\n                    Spacer(Modifier.height(7.dp))\n                    PreviewOutlineButton("Run Restore Now", {\n                        if (selectedRestore.isBlank()) status = "Select a backup file first"\n                        else onRestoreFullBackup(selectedRestore, false) { result -> status = result }\n                    }, Modifier.fillMaxWidth())\n                }\n            }\n        }\n        item {\n            PreviewCardBox {\n                Column {\n                    Text(status, color = if (status.contains("error", true) || status.contains("fail", true)) PreviewRed else PreviewGreen, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)\n                    Spacer(Modifier.height(8.dp))\n                    PreviewOutlineButton("Apply Safe Defaults", onApplySafeDefaults, Modifier.fillMaxWidth())\n                    Text("Mode=${settings.mode.name.replace(\'_\', \' \')} • Local data remains authoritative when CloudShare is disabled.", color = PreviewMuted, fontSize = 8.sp, modifier = Modifier.padding(top = 7.dp))\n                }\n            }\n        }\n    }\n}\n'


PREVIEW_TEST_SOURCE = r'''package com.ksp.cryptobot

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewUiContractTest {
    @Test fun symbolPairFormattingMatchesPreviewLabels() {
        assertEquals("BTC/EUR", previewPair("BTCEUR"))
        assertEquals("KAS/EUR", previewPair("KAS/EUR"))
    }

    @Test fun detailRoutesKeepCorrectBottomNavigationParent() {
        assertEquals(AppTab.AI, previewParentTab(AppTab.AI_SIGNAL_DETAIL))
        assertEquals(AppTab.PORTFOLIO, previewParentTab(AppTab.POSITIONS))
        assertEquals(AppTab.SETTINGS, previewParentTab(AppTab.SYSTEM_TEST))
    }
}
'''


def patch_gradle(repo: Path) -> None:
    path = repo / "app/build.gradle.kts"
    require(path)
    text = path.read_text(encoding="utf-8")
    dependency = '    implementation("androidx.compose.material:material-icons-extended")\n'
    if 'material-icons-extended' not in text:
        marker = '    implementation("androidx.compose.material3:material3")\n'
        if marker not in text:
            fail("Compose Material3 dependency anchor missing")
        text = text.replace(marker, marker + dependency, 1)
    path.write_text(text, encoding="utf-8")


def patch_shared_preview_style(text: str) -> str:
    # Make legacy/deep screens inherit the approved preview component language.
    if "import androidx.compose.material3.Switch\n" not in text:
        text = text.replace("import androidx.compose.material3.Surface\n", "import androidx.compose.material3.Surface\nimport androidx.compose.material3.Switch\nimport androidx.compose.material3.SwitchDefaults\n", 1)

    hero_pattern = r'''@Composable\nprivate fun HeroCard\(\n    title: String,\n    subtitle: String,\n    primaryButton: String,\n    secondaryButton: String,\n    onPrimary: \(\) -> Unit,\n    onSecondary: \(\) -> Unit\n\) \{.*?\n\}\n\n@Composable\nprivate fun GlassCard'''
    hero_replacement = '''@Composable
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
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, Stroke)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPrimary,
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Electric),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp)
                ) { Text(primaryButton, style = MaterialTheme.typography.labelMedium) }
                OutlinedButton(
                    onClick = onSecondary,
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, Electric.copy(alpha = 0.65f)),
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp)
                ) { Text(secondaryButton, color = Electric, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun GlassCard'''
    text, count = re.subn(hero_pattern, hero_replacement, text, count=1, flags=re.S)
    if count == 0 and "shape = RoundedCornerShape(8.dp),\n        colors = CardDefaults.cardColors(containerColor = Panel)" not in text:
        fail("shared HeroCard style anchor changed")

    glass_pattern = r'''@Composable\nprivate fun GlassCard\(content: @Composable ColumnScope\.\(\) -> Unit\) \{.*?\n\}\n\n@Composable\nprivate fun MetricCard'''
    glass_replacement = '''@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, Stroke)
    ) {
        Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)
    }
}

@Composable
private fun MetricCard'''
    text, count = re.subn(glass_pattern, glass_replacement, text, count=1, flags=re.S)
    if count == 0 and "Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)" not in text:
        fail("shared GlassCard style anchor changed")

    metric_pattern = r'''@Composable\nprivate fun MetricCard\(title: String, value: String, caption: String, accent: Color\) \{.*?\n\}\n\n@Composable\nprivate fun DecisionCard'''
    metric_replacement = '''@Composable
private fun MetricCard(title: String, value: String, caption: String, accent: Color) {
    Card(
        modifier = Modifier.width(146.dp),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Panel),
        border = BorderStroke(1.dp, Stroke)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(value, color = accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(caption, color = Muted, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DecisionCard'''
    text, count = re.subn(metric_pattern, metric_replacement, text, count=1, flags=re.S)
    if count == 0 and "modifier = Modifier.width(146.dp)" not in text:
        fail("shared MetricCard style anchor changed")

    section_pattern = r'''@Composable\nprivate fun SectionTitle\(title: String, subtitle: String\) \{.*?\n\}\n\n@Composable\nprivate fun ToggleRow'''
    section_replacement = '''@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
        if (subtitle.isNotBlank()) Text(subtitle, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ToggleRow'''
    text, count = re.subn(section_pattern, section_replacement, text, count=1, flags=re.S)
    if count == 0 and "if (subtitle.isNotBlank()) Text(subtitle" not in text:
        fail("shared SectionTitle style anchor changed")

    toggle_pattern = r'''@Composable\nprivate fun ToggleRow\(label: String, checked: Boolean, onCheckedChange: \(Boolean\) -> Unit\) \{.*?\n\}\n\n@Composable\nprivate fun ToggleInfo'''
    toggle_replacement = '''@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = TextPrimary)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.size(width = 42.dp, height = 24.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Mint,
                uncheckedThumbColor = Muted,
                uncheckedTrackColor = PanelAlt,
                uncheckedBorderColor = Stroke
            )
        )
    }
}

@Composable
private fun ToggleInfo'''
    text, count = re.subn(toggle_pattern, toggle_replacement, text, count=1, flags=re.S)
    if count == 0 and "checkedTrackColor = Mint" not in text:
        fail("shared ToggleRow style anchor changed")

    info_pattern = r'''@Composable\nprivate fun ToggleInfo\(label: String, enabled: Boolean\) \{.*?\n\}\n\n@Composable\nprivate fun TaxRow'''
    info_replacement = '''@Composable
private fun ToggleInfo(label: String, enabled: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = TextPrimary, style = MaterialTheme.typography.bodySmall)
        Text(if (enabled) "ENABLED" else "DISABLED", color = if (enabled) Mint else Muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TaxRow'''
    text, count = re.subn(info_pattern, info_replacement, text, count=1, flags=re.S)
    if count == 0 and 'Text(if (enabled) "ENABLED" else "DISABLED"' not in text:
        fail("shared ToggleInfo style anchor changed")

    warning_pattern = r'''@Composable\nprivate fun WarningCard\(text: String\) \{.*?\n\}\n\n@Composable\nprivate fun StatusPill'''
    warning_replacement = '''@Composable
private fun WarningCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF20191D)),
        border = BorderStroke(1.dp, Danger.copy(alpha = 0.40f))
    ) {
        Text(text, modifier = Modifier.padding(11.dp), color = Color(0xFFF1C6CB), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StatusPill'''
    text, count = re.subn(warning_pattern, warning_replacement, text, count=1, flags=re.S)
    if count == 0 and "Color(0xFF20191D)" not in text:
        fail("shared WarningCard style anchor changed")

    pill_pattern = r'''@Composable\nprivate fun StatusPill\(text: String, color: Color\) \{.*?\n\}\n\n@Composable\nprivate fun StatusDot'''
    pill_replacement = '''@Composable
private fun StatusPill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(5.dp), color = Color.Transparent) {
        Text(text, modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp), color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun StatusDot'''
    text, count = re.subn(pill_pattern, pill_replacement, text, count=1, flags=re.S)
    if count == 0 and "shape = RoundedCornerShape(5.dp), color = Color.Transparent" not in text:
        fail("shared StatusPill style anchor changed")

    metricbox_pattern = r'''@Composable\nprivate fun MetricBox\(label: String, value: String, modifier: Modifier = Modifier\) \{.*?\n\}\n\nprivate fun sampleDecisions'''
    metricbox_replacement = '''@Composable
private fun MetricBox(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = PanelAlt),
        border = BorderStroke(1.dp, Stroke),
        shape = RoundedCornerShape(7.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(label, color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(value, color = TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun sampleDecisions'''
    text, count = re.subn(metricbox_pattern, metricbox_replacement, text, count=1, flags=re.S)
    if count == 0 and "shape = RoundedCornerShape(7.dp)" not in text:
        fail("shared MetricBox style anchor changed")

    return text


def patch_main_activity(repo: Path) -> None:
    path = repo / "app/src/main/java/com/ksp/cryptobot/MainActivity.kt"
    require(path)
    text = path.read_text(encoding="utf-8")

    if "AppTab.AI_SIGNAL_DETAIL -> PreviewAiSignalDetailScreen" in text and "PreviewBottomNavigation(currentTab = currentTab" in text:
        text = patch_shared_preview_style(text)
        path.write_text(text, encoding="utf-8")
        return

    if "import androidx.compose.foundation.layout.weight" not in text:
        text = text.replace("import androidx.compose.foundation.layout.width\n", "import androidx.compose.foundation.layout.width\nimport androidx.compose.foundation.layout.weight\n", 1)

    palette = {
        'private val SpaceBlack = Color(0xFF081326)': 'private val SpaceBlack = Color(0xFF0C1522)',
        'private val Panel = Color(0xFF0F1B33)': 'private val Panel = Color(0xFF141D2A)',
        'private val PanelAlt = Color(0xFF142544)': 'private val PanelAlt = Color(0xFF111A27)',
        'private val Stroke = Color(0xFF2A4471)': 'private val Stroke = Color(0xFF243143)',
        'private val Electric = Color(0xFF47B8FF)': 'private val Electric = Color(0xFF8B5CF6)',
        'private val Mint = Color(0xFF55F0DE)': 'private val Mint = Color(0xFF62DE67)',
        'private val Amber = Color(0xFFFFC857)': 'private val Amber = Color(0xFFFFA31A)',
        'private val Danger = Color(0xFFFF5E8A)': 'private val Danger = Color(0xFFFF5D69)',
        'private val Muted = Color(0xFFA5B4D0)': 'private val Muted = Color(0xFF8E9BAB)',
        'private val TextPrimary = Color(0xFFEAF3FF)': 'private val TextPrimary = Color(0xFFF2F5F8)',
    }
    for old, new in palette.items():
        if old in text:
            text = text.replace(old, new, 1)

    if "shapes = androidx.compose.material3.Shapes(" not in text:
        theme_anchor = """            onPrimary = Color.White,
            onSecondary = Color(0xFF06130F)
        ),
        content = content
"""
        theme_repl = """            onPrimary = Color.White,
            onSecondary = Color(0xFF06130F)
        ),
        shapes = androidx.compose.material3.Shapes(
            small = RoundedCornerShape(5.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(10.dp)
        ),
        content = content
"""
        text = replace_once(text, theme_anchor, theme_repl, "global preview shapes")

    if "window.statusBarColor = android.graphics.Color.rgb(10, 19, 32)" not in text:
        text = replace_once(
            text,
            "        super.onCreate(savedInstanceState)\n",
            "        super.onCreate(savedInstanceState)\n        window.statusBarColor = android.graphics.Color.rgb(10, 19, 32)\n        window.navigationBarColor = android.graphics.Color.rgb(10, 19, 32)\n",
            "system bar palette"
        )

    text = text.replace("private enum class AppTab", "enum class AppTab", 1)
    if 'AI_SIGNAL_DETAIL("AI Signal")' not in text:
        text = replace_once(text, '    AI_SIGNALS("AI Signals"),\n', '    AI_SIGNALS("AI Signals"),\n    AI_SIGNAL_DETAIL("AI Signal"),\n', "AI detail route")

    if "var selectedAiDecision by remember" not in text:
        text = replace_once(
            text,
            '    var decisions by remember { mutableStateOf<List<AiDecision>>(emptyList()) }\n',
            '    var decisions by remember { mutableStateOf<List<AiDecision>>(emptyList()) }\n    var selectedAiDecision by remember { mutableStateOf<AiDecision?>(null) }\n',
            "selected AI signal state"
        )

    text = text.replace(
        "        if (currentTab == AppTab.PORTFOLIO) {\n",
        "        if (currentTab == AppTab.PORTFOLIO || currentTab == AppTab.SETTINGS) {\n",
        1
    )

    old_shell = """        Column(modifier = Modifier.fillMaxSize()) {
            HeaderBar(status = status, mode = settings.mode, level = statusLevel)
            AppTabs(currentTab = currentTab, onTabSelected = { currentTab = it })

            when (currentTab) {
"""
    new_shell = """        Column(modifier = Modifier.fillMaxSize()) {
            PreviewAppTopBar(
                currentTab = currentTab,
                detailSymbol = selectedAiDecision?.symbol,
                onBack = { currentTab = previewParentTab(currentTab) },
                onAction = {
                    when (currentTab) {
                        AppTab.PORTFOLIO -> {
                            onLoadPortfolio(settings) { portfolioSnapshot = it }
                            onLoadLifecycle(settings) { lifecycleSnapshot = it }
                        }
                        AppTab.POSITIONS -> onLoadLifecycle(settings) { lifecycleSnapshot = it }
                        AppTab.ORDERS -> onLoadOrders(settings) { liveOrders = it }
                        AppTab.SYSTEM_TEST -> onRunSystemTest(settings) { systemTestLines = it }
                        else -> Unit
                    }
                }
            )
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                when (currentTab) {
"""
    text = replace_once(text, old_shell, new_shell, "preview app shell start")

    shell_end = """            }
        }
    }
}

@Composable
private fun HeaderBar"""
    shell_end_repl = """                }
            }
            PreviewBottomNavigation(currentTab = currentTab, onTabSelected = { currentTab = it })
        }
    }
}

@Composable
private fun HeaderBar"""
    text = replace_once(text, shell_end, shell_end_repl, "preview app shell end")

    dashboard_pattern = r'''                AppTab\.DASHBOARD -> DashboardScreen\(.*?\n                AppTab\.STATUS ->'''
    dashboard_repl = '''                AppTab.DASHBOARD -> PreviewDashboardScreen(
                    settings = settings,
                    status = status,
                    portfolio = portfolioSnapshot,
                    lifecycle = lifecycleSnapshot,
                    decisions = decisions,
                    trades = tradeJournal,
                    onStart = { statusStore.write("Start button pressed from dashboard."); onStart(); status = "Foreground live scanner started" },
                    onStop = { statusStore.write("Stop button pressed from dashboard.", "WARN"); onStop(); status = "Bot stopped" },
                    onScan = {
                        status = "Scanning market + AI inputs..."
                        val scanSymbols = (settings.symbols() + activeChartSymbols).map { it.uppercase().replace("/", "").replace("-", "") }.distinct()
                        onScan(settings.copy(symbolsCsv = scanSymbols.joinToString(",")), false) { result -> decisions = result; status = "Scan complete" }
                    },
                    onExecute = {
                        status = "Running guarded execution pass..."
                        val scanSymbols = (settings.symbols() + activeChartSymbols).map { it.uppercase().replace("/", "").replace("-", "") }.distinct()
                        onScan(settings.copy(symbolsCsv = scanSymbols.joinToString(",")), settings.mode == BotMode.PAPER || settings.mode == BotMode.LIVE_AUTO) { result -> decisions = result; status = "Execution pass complete" }
                    },
                    onOpenNews = { currentTab = AppTab.NEWS }
                )
                AppTab.STATUS ->'''
    text = replace_regex_once(text, dashboard_pattern, dashboard_repl, "preview dashboard route")

    ai_pattern = r'''                AppTab\.AI -> AiHubScreen\(.*?\n                AppTab\.AI_SIGNALS -> AiSignalsScreen\(.*?\n                AppTab\.CHART ->'''
    ai_repl = '''                AppTab.AI -> PreviewAiHubScreen(
                    decisions = decisions,
                    settings = settings,
                    performanceLabSnapshot = performanceLabSnapshot,
                    trades = tradeJournal,
                    onOpen = { currentTab = it },
                    onSelectSignal = { decision ->
                        selectedAiDecision = decision
                        chartSymbol = decision.symbol.uppercase().replace("/", "").replace("-", "")
                        onLoadChartCandles(settings, chartSymbol, Timeframe.M15, 180) { chartCandles = it }
                        currentTab = AppTab.AI_SIGNAL_DETAIL
                    },
                    onScan = {
                        val scanSymbols = (settings.symbols() + activeChartSymbols).map { it.uppercase().replace("/", "").replace("-", "") }.distinct()
                        onScan(settings.copy(symbolsCsv = scanSymbols.joinToString(",")), false) { result -> decisions = result; status = "AI Signals scan complete" }
                    }
                )
                AppTab.AI_SIGNALS -> PreviewAiSignalsListScreen(
                    decisions = decisions,
                    settings = settings,
                    activePositionSymbols = activeChartSymbols,
                    onScan = {
                        val scanSymbols = (settings.symbols() + activeChartSymbols).map { it.uppercase().replace("/", "").replace("-", "") }.distinct()
                        onScan(settings.copy(symbolsCsv = scanSymbols.joinToString(",")), false) { result -> decisions = result; status = "AI Signals scan complete" }
                    },
                    onSelectSignal = { decision ->
                        selectedAiDecision = decision
                        chartSymbol = decision.symbol.uppercase().replace("/", "").replace("-", "")
                        onLoadChartCandles(settings, chartSymbol, Timeframe.M15, 180) { chartCandles = it }
                        currentTab = AppTab.AI_SIGNAL_DETAIL
                    }
                )
                AppTab.AI_SIGNAL_DETAIL -> PreviewAiSignalDetailScreen(selectedAiDecision, chartCandles)
                AppTab.CHART ->'''
    text = replace_regex_once(text, ai_pattern, ai_repl, "preview AI routes")

    positions_pattern = r'''                AppTab\.POSITIONS -> PositionsScreen\(.*?\n                AppTab\.AUTONOMOUS ->'''
    positions_repl = '''                AppTab.POSITIONS -> PreviewPositionsScreen(
                    settings = settings,
                    snapshot = lifecycleSnapshot,
                    onRefresh = {
                        onLoadLifecycle(settings) { result -> lifecycleSnapshot = result; status = "Lifecycle loaded: ${result.positions.size} position(s)" }
                    }
                )
                AppTab.AUTONOMOUS ->'''
    text = replace_regex_once(text, positions_pattern, positions_repl, "preview positions route")

    portfolio_pattern = r'''                AppTab\.PORTFOLIO -> PortfolioScreen\(.*?\n                AppTab\.NEWS -> NewsScreen\('''
    portfolio_repl = '''                AppTab.PORTFOLIO -> PreviewPortfolioScreen(
                    settings = settings,
                    snapshot = portfolioSnapshot,
                    lifecycleSnapshot = lifecycleSnapshot,
                    trades = tradeJournal,
                    onRefresh = {
                        onLoadPortfolio(settings) { result -> portfolioSnapshot = result; status = "Portfolio loaded: €${result.totalValueEur}" }
                        onLoadLifecycle(settings) { lifecycleSnapshot = it }
                        onLoadTradeJournal(200) { tradeJournal = it }
                    }
                )
                AppTab.NEWS -> PreviewNewsScreen('''
    text = replace_regex_once(text, portfolio_pattern, portfolio_repl, "preview portfolio route")

    news_insert = '''                    activeSymbols = activeChartSymbols,
'''
    if 'AppTab.NEWS -> PreviewNewsScreen(' in text:
        region_start = text.index('                AppTab.NEWS -> PreviewNewsScreen(')
        region_end = text.index('                AppTab.TAX ->', region_start)
        region = text[region_start:region_end]
        if '                    decisions = decisions,\n' not in region:
            if news_insert not in region:
                fail("News activeSymbols anchor missing after portfolio replacement")
            region = region.replace(news_insert, news_insert + '                    decisions = decisions,\n', 1)
            text = text[:region_start] + region + text[region_end:]

    settings_pattern = r'''                AppTab\.SETTINGS -> SettingsHubScreen\(.*?\n                AppTab\.SYSTEM_TEST -> SystemTestScreen\('''
    settings_repl = '''                AppTab.SETTINGS -> PreviewSettingsScreen(
                    settings = settings,
                    portfolio = portfolioSnapshot,
                    onPersist = { updated ->
                        persistSettings(updated)
                        maxPosition = updated.maxPositionEur.toPlainString()
                        maxLoss = updated.maxDailyLossEur.toPlainString()
                        maxTrades = updated.maxTradesPerDay.toString()
                        maxSpread = updated.maxSpreadPercent.toPlainString()
                    },
                    onOpen = { currentTab = it }
                )
                AppTab.SYSTEM_TEST -> PreviewSystemTestScreen('''
    text = replace_regex_once(text, settings_pattern, settings_repl, "preview settings/system route")

    backup_marker = '                AppTab.BACKUP -> BackupRestoreScreen(\n'
    if backup_marker in text:
        text = text.replace(backup_marker, '                AppTab.BACKUP -> PreviewBackupRecoveryScreen(\n', 1)
    elif 'AppTab.BACKUP -> PreviewBackupRecoveryScreen(' not in text:
        fail("Backup route anchor missing")

    text = patch_shared_preview_style(text)

    old_gradient = """                Brush.verticalGradient(
                    listOf(Color(0xFF09111F), SpaceBlack, Color(0xFF090B12))
                )
"""
    if old_gradient in text:
        text = text.replace(old_gradient, '                Brush.verticalGradient(listOf(Color(0xFF0C1522), Color(0xFF0C1522)))\n', 1)

    path.write_text(text, encoding="utf-8")


def validate(repo: Path) -> None:
    main = (repo / "app/src/main/java/com/ksp/cryptobot/MainActivity.kt").read_text(encoding="utf-8")
    ui = (repo / "app/src/main/java/com/ksp/cryptobot/PreviewReplicaUi.kt").read_text(encoding="utf-8")
    gradle = (repo / "app/build.gradle.kts").read_text(encoding="utf-8")
    checks = {
        "preview UI source installed": "fun PreviewDashboardScreen" in ui and "fun PreviewSettingsScreen" in ui,
        "fixed bottom navigation": "PreviewBottomNavigation(currentTab = currentTab" in main,
        "compact top bar": "PreviewAppTopBar(" in main,
        "dashboard uses real state": "portfolio = portfolioSnapshot" in main and "trades = tradeJournal" in main,
        "portfolio donut implemented": "AssetAllocationDonut" in ui,
        "portfolio area graph implemented": "PreviewAreaSparkline" in ui,
        "AI confidence gauge implemented": "PreviewConfidenceGauge" in ui,
        "AI detail route wired": "AppTab.AI_SIGNAL_DETAIL -> PreviewAiSignalDetailScreen" in main,
        "news visual route wired": "AppTab.NEWS -> PreviewNewsScreen" in main and "decisions = decisions" in main,
        "single settings visual route": "AppTab.SETTINGS -> PreviewSettingsScreen" in main,
        "system test visual route": "AppTab.SYSTEM_TEST -> PreviewSystemTestScreen" in main,
        "positions visual route": "AppTab.POSITIONS -> PreviewPositionsScreen" in main,
        "backup visual route": "AppTab.BACKUP -> PreviewBackupRecoveryScreen" in main,
        "material icons dependency": "material-icons-extended" in gradle,
        "approved purple accent": "0xFF8B5CF6" in main and "0xFF8B5CF6" in ui,
        "approved green accent": "0xFF62DE67" in main and "0xFF62DE67" in ui,
        "deep cards use preview radius": "private fun GlassCard" in main and "Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content)" in main,
        "legacy toggles use preview switches": "checkedTrackColor = Mint" in main and "SwitchDefaults.colors" in main,
        "legacy hero uses preview card language": "private fun HeroCard" in main and "contentPadding = PaddingValues(horizontal = 13.dp, vertical = 7.dp)" in main,
    }
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
    failed = [name for name, ok in checks.items() if not ok]
    if failed:
        fail("visual integration validation failed: " + ", ".join(failed))


def main() -> None:
    repo = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()
    require(repo / "app/src/main/java/com/ksp/cryptobot/MainActivity.kt")
    patch_gradle(repo)
    write(repo / "app/src/main/java/com/ksp/cryptobot/PreviewReplicaUi.kt", PREVIEW_SOURCE)
    write(repo / "app/src/test/java/com/ksp/cryptobot/PreviewUiContractTest.kt", PREVIEW_TEST_SOURCE)
    patch_main_activity(repo)
    validate(repo)
    print("[CTS preview-exact redesign] Applied successfully.")


if __name__ == "__main__":
    main()
