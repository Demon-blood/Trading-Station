#!/usr/bin/env python3
"""Crypto TradeStation v4 full integration + UX cleanup.

Run AFTER apply_milestone6.py. This migration removes the temporary V4 Systems
container, distributes v4 tools to their existing domain hubs, hardens lifecycle
entry/exit semantics, and adds regression tests / static integration contracts.

It intentionally does not change applicationId, Room schema, version identity,
or signer configuration.
"""
from __future__ import annotations

import sys
from pathlib import Path


def fail(message: str) -> None:
    raise SystemExit(f"[CTS full integration cleanup] {message}")


def require(path: Path) -> None:
    if not path.exists():
        fail(f"Required file missing: {path}")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


LIFECYCLE_TEST = r'''package com.ksp.cryptobot.lifecycle

import com.ksp.cryptobot.core.AiDecision
import com.ksp.cryptobot.core.SignalAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleExitSemanticsTest {
    private fun decision(action: SignalAction, allowed: Boolean = true) = AiDecision(
        symbol = "KASEUR",
        finalAction = action,
        finalScore = 60,
        confidencePercent = 60,
        technicalScore = 60,
        newsScore = 0,
        memoryScore = 0,
        allowedToTrade = allowed,
        explanation = "test"
    )

    @Test fun avoidMeansDoNotEnterNotSell() {
        assertFalse(isExplicitLifecycleSell(decision(SignalAction.AVOID)))
        assertFalse(isExplicitLifecycleSell(decision(SignalAction.STRONG_AVOID)))
        assertFalse(isExplicitLifecycleSell(decision(SignalAction.WAIT)))
    }

    @Test fun onlyExplicitAllowedSellIsSoftSellSignal() {
        assertTrue(isExplicitLifecycleSell(decision(SignalAction.SELL, true)))
        assertFalse(isExplicitLifecycleSell(decision(SignalAction.SELL, false)))
    }

    @Test fun newEntryDefersSoftSignalExit() {
        assertTrue(shouldDeferSoftLifecycleExitForChurn(true, false, true, false, 0, 15))
        assertTrue(shouldDeferSoftLifecycleExitForChurn(true, false, false, false, 2, 15))
        assertFalse(shouldDeferSoftLifecycleExitForChurn(true, false, false, false, 16, 15))
    }

    @Test fun protectiveExitIsNotDeferredBySoftChurnGuard() {
        assertFalse(shouldDeferSoftLifecycleExitForChurn(true, true, true, false, 0, 15))
    }

    @Test fun alreadyExitedSymbolCannotReceiveSecondSoftExitThisScan() {
        assertTrue(shouldDeferSoftLifecycleExitForChurn(true, false, false, true, 60, 15))
    }
}
'''


def patch_v4_panel_exports(repo: Path) -> None:
    path = repo / "app/src/main/java/com/ksp/cryptobot/ui/V4ControlCenterScreen.kt"
    require(path)
    text = path.read_text(encoding="utf-8")
    if "fun V4ResearchPanel()" not in text:
        fail("V4 research panel missing")
    text = text.replace("private fun V4ResearchPanel()", "fun V4ResearchPanel()", 1)
    text = text.replace("private fun V4RecoveryPanel()", "fun V4RecoveryPanel()", 1)
    path.write_text(text, encoding="utf-8")


def patch_main_ui(repo: Path) -> None:
    path = repo / "app/src/main/java/com/ksp/cryptobot/MainActivity.kt"
    require(path)
    text = path.read_text(encoding="utf-8")

    # Remove the migration-only V4 Systems top-level navigation container.
    text = text.replace("import com.ksp.cryptobot.ui.V4ControlCenterScreen\n", "")
    text = text.replace("import com.ksp.cryptobot.ui.V4ResearchPanel\n", "")
    text = text.replace("import com.ksp.cryptobot.ui.V4RecoveryPanel\n", "")

    import_anchor = "import com.ksp.cryptobot.settings.AppSettingsStore\n"
    domain_imports = (
        "import com.ksp.cryptobot.settings.AppSettingsStore\n"
        "import com.ksp.cryptobot.ui.CloudShareScreen\n"
        "import com.ksp.cryptobot.ui.V4ResearchPanel\n"
        "import com.ksp.cryptobot.ui.V4RecoveryPanel\n"
        "import com.ksp.cryptobot.news.NewsProviderHealthRegistry\n"
    )
    if "import com.ksp.cryptobot.ui.CloudShareScreen" not in text:
        text = replace_once(text, import_anchor, domain_imports, "domain UI imports")

    text = text.replace('    V4_SYSTEMS("V4 Systems"),\n', '')
    text = text.replace('            AppTab.V4_SYSTEMS,\n', '')
    text = text.replace('                AppTab.V4_SYSTEMS -> V4ControlCenterScreen()\n', '')

    # Domain destinations are internal routes only, never top-level tabs.
    if 'RESEARCH_SETTINGS("Research Intelligence")' not in text:
        text = replace_once(
            text,
            '    STRATEGY("Strategy Lab"),\n',
            '    STRATEGY("Strategy Lab"),\n    RESEARCH_SETTINGS("Research Intelligence"),\n',
            "research destination"
        )
    if 'CLOUDSHARE_SETTINGS("CloudShare")' not in text:
        text = replace_once(
            text,
            '    BACKUP("Backup/Restore"),\n',
            '    BACKUP("Backup/Restore"),\n    CLOUDSHARE_SETTINGS("CloudShare"),\n    RECOVERY_TOOLS("Recovery Tools"),\n',
            "backup domain destinations"
        )

    # One settings hierarchy with descriptive child pages.
    text = text.replace('    BASIC_SETTINGS("Basic Settings"),', '    BASIC_SETTINGS("Connection & Trading"),')
    text = text.replace('    ADVANCED_SETTINGS("Advanced Settings"),', '    ADVANCED_SETTINGS("Automation & Risk"),')
    text = text.replace(
        'HubActionCard("Basic Settings", "Provider, Kraken/API keys, symbols and main risk fields."',
        'HubActionCard("Connection & Trading", "Exchange, credentials, symbols and primary trading controls."'
    )
    text = text.replace(
        'HubActionCard("Advanced Settings", "Clean unified automation controls: price caps, per-symbol rules, live guards, duplicate-position protection and risk limits."',
        'HubActionCard("Automation & Risk", "Automation policy, position sizing, execution guards, cooldowns and risk limits."'
    )

    # AVOID is an entry-avoidance state, not exit pressure.
    old_metrics = '''    val buySignals = decisions.count { it.finalAction == SignalAction.BUY || it.finalAction == SignalAction.SMALL_BUY }
    val sellSignals = decisions.count { it.finalAction == SignalAction.SELL || it.finalAction == SignalAction.AVOID || it.finalAction == SignalAction.STRONG_AVOID }
'''
    new_metrics = '''    val buySignals = decisions.count { it.finalAction == SignalAction.BUY || it.finalAction == SignalAction.SMALL_BUY }
    val sellSignals = decisions.count { it.finalAction == SignalAction.SELL }
    val avoidedEntries = decisions.count { it.finalAction == SignalAction.AVOID || it.finalAction == SignalAction.STRONG_AVOID }
'''
    if old_metrics in text:
        text = text.replace(old_metrics, new_metrics, 1)
    elif "val avoidedEntries =" not in text:
        fail("AI action metrics anchor changed")
    text = text.replace(
        'item { MetricCard("Exit pressure", sellSignals.toString(), "SELL/AVOID", Danger) }',
        'item { MetricCard("Exit signals", sellSignals.toString(), "Explicit SELL only", Danger) }\n                item { MetricCard("Avoid entries", avoidedEntries.toString(), "AVOID / STRONG AVOID", Amber) }'
    )

    # Portfolio rotation uses the same action contract. AVOID reduces entry
    # appetite but is not counted as a liquidation command.
    rotation_old = '''    val sellSignals = decisions.count { it.finalAction == SignalAction.SELL || it.finalAction == SignalAction.AVOID || it.finalAction == SignalAction.STRONG_AVOID }
    val buySignals = decisions.count { it.finalAction == SignalAction.BUY || it.finalAction == SignalAction.SMALL_BUY }
'''
    rotation_new = '''    val sellSignals = decisions.count { it.finalAction == SignalAction.SELL }
    val avoidSignals = decisions.count { it.finalAction == SignalAction.AVOID || it.finalAction == SignalAction.STRONG_AVOID }
    val buySignals = decisions.count { it.finalAction == SignalAction.BUY || it.finalAction == SignalAction.SMALL_BUY }
'''
    if rotation_old in text:
        text = text.replace(rotation_old, rotation_new, 1)
    text = text.replace(
        'item { MetricCard("Exit signals", sellSignals.toString(), "Current AI", Danger) }',
        'item { MetricCard("Exit signals", sellSignals.toString(), "Explicit SELL", Danger) }\n                item { MetricCard("Avoid entries", avoidSignals.toString(), "Do not enter", Amber) }'
    )
    text = text.replace(
        'Text("3. Reduce exposure when several holdings show SELL/AVOID.", color = Muted)',
        'Text("3. Reduce exposure only on explicit exit/protection signals; AVOID only blocks new entries.", color = Muted)'
    )

    # Remove implementation-generation labels from normal user-facing copy.
    text = text.replace('SectionTitle("v1.2 Autonomous Intelligence",', 'SectionTitle("Autonomous Intelligence",')
    text = text.replace('SectionTitle("Smart Exit Engine v2",', 'SectionTitle("Smart Exit Engine",')
    text = text.replace(
        'Text("v2 behavior: hold strong continuation candidates, scale/lock profits on exhaustion, and keep emergency exits above learned holds.", color = Muted)',
        'Text("Exit behavior: hold strong continuation candidates, scale/lock profits on exhaustion, and keep emergency exits above learned holds.", color = Muted)'
    )
    text = text.replace(
        'asset.free > BigDecimal.ZERO -> "Available for automatic SELL orders when the strategy turns bearish."',
        'asset.free > BigDecimal.ZERO -> "Available for automatic SELL orders when an explicit exit or protection rule triggers."'
    )

    # Research belongs to AI/Strategy.
    if 'HubActionCard("Research Intelligence"' not in text:
        anchor = '        item { HubActionCard("Strategy Lab", "Technical strategy, scoring logic and strategy controls.", "Open", { onOpen(AppTab.STRATEGY) }) }\n'
        text = replace_once(
            text,
            anchor,
            anchor + '        item { HubActionCard("Research Intelligence", "Professional research, handoff truth, robustness and external-context controls.", "Open", { onOpen(AppTab.RESEARCH_SETTINGS) }) }\n',
            "AI research hub card"
        )

    # CloudShare/recovery belong to Settings/Backup, not a generic V4 screen.
    if 'HubActionCard("CloudShare Sync"' not in text:
        anchor = '        item { HubActionCard("Backup / Restore", "Export safe text backup and restore safe defaults.", "Open", { onOpen(AppTab.BACKUP) }) }\n'
        addition = (
            anchor
            + '        item { HubActionCard("CloudShare Sync", "Optional collective-data sync. Local trading remains independent when disabled.", "Open", { onOpen(AppTab.CLOUDSHARE_SETTINGS) }) }\n'
            + '        item { HubActionCard("Recovery Tools", "Supplemental v4 backup, redacted diagnostics and operational-data maintenance.", "Open", { onOpen(AppTab.RECOVERY_TOOLS) }) }\n'
        )
        text = replace_once(text, anchor, addition, "settings backup domain cards")

    route_anchor = '                AppTab.STRATEGY -> StrategyScreen(settings = settings, onToggleStrategy = { persistSettings(settings.copy(recoveredScalpingStrategyEnabled = it)) })\n'
    if 'AppTab.RESEARCH_SETTINGS -> V4ResearchPanel()' not in text:
        text = replace_once(
            text,
            route_anchor,
            route_anchor + '                AppTab.RESEARCH_SETTINGS -> V4ResearchPanel()\n',
            "research route"
        )

    backup_route_anchor = '                AppTab.BACKUP -> BackupRestoreScreen(\n'
    if backup_route_anchor not in text:
        fail("Backup route anchor missing")
    if 'AppTab.CLOUDSHARE_SETTINGS -> CloudShareScreen()' not in text:
        text = text.replace(
            backup_route_anchor,
            '                AppTab.CLOUDSHARE_SETTINGS -> CloudShareScreen()\n'
            '                AppTab.RECOVERY_TOOLS -> V4RecoveryPanel()\n'
            + backup_route_anchor,
            1
        )

    # News health belongs in News Dashboard.
    text = text.replace(
        'SectionTitle("News Dashboard", "Cached per-symbol articles from GDELT, RSS, Marketaux, NewsData.io, GNews, Guardian and NewsAPI.org, used by AI signal scoring.")',
        'SectionTitle("News Dashboard", "Cached per-symbol articles and provider health for GDELT, RSS, CryptoPanic, Marketaux, NewsData.io, GNews, Guardian and NewsAPI.org.")'
    )
    text = text.replace(
        'Text("During scans, every symbol checks GDELT + RSS + Marketaux + NewsData.io + GNews + Guardian + NewsAPI.org, stores articles locally, and adds article titles into the AI decision explanation.", color = Muted)',
        'Text("During scans, every symbol checks the enabled news ensemble, stores articles locally, applies provider cooldowns after failures, and feeds fresh article context into AI scoring.", color = Muted)'
    )
    if 'val providerHealth = NewsProviderHealthRegistry.snapshot()' not in text:
        news_anchor = '    val grouped = newsHistory.groupBy { it.symbol }.toList().sortedByDescending { it.second.size }\n\n    LazyColumn(\n'
        text = replace_once(
            text,
            news_anchor,
            '    val grouped = newsHistory.groupBy { it.symbol }.toList().sortedByDescending { it.second.size }\n'
            '    val providerHealth = NewsProviderHealthRegistry.snapshot()\n\n    LazyColumn(\n',
            "news provider health state"
        )
        news_metric_anchor = '''        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MetricCard("Cached articles", newsHistory.size.toString(), "Local DB", Electric) }
                item { MetricCard("Symbols", grouped.size.toString(), "With cached news", Mint) }
                item { MetricCard("Visible", visibleNews.size.toString(), selectedSymbol.ifBlank { "All symbols" }, Amber) }
            }
        }
'''
        health_block = news_metric_anchor + '''        if (providerHealth.isNotEmpty()) {
            item {
                GlassCard {
                    SectionTitle("Provider Health", "Runtime provider state for this app process. Cooldowns stop repeated calls to failing/quota-limited providers.")
                    providerHealth.forEach { health ->
                        val healthColor = when {
                            health.coolingDown() -> Amber
                            health.lastSuccessEpochMs > 0L && health.lastSuccessEpochMs >= health.lastFailureEpochMs -> Mint
                            else -> Muted
                        }
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(health.provider, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            StatusPill(health.status, healthColor)
                        }
                        Text("articles=${health.lastArticleCount} • failures=${health.consecutiveFailures}${if (health.coolingDown()) " • cooldown active" else ""}", color = Muted, style = MaterialTheme.typography.bodySmall)
                        if (health.lastError.isNotBlank()) Text(health.lastError, color = Amber, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
'''
        text = replace_once(text, news_metric_anchor, health_block, "news provider health UI")

    text = text.replace(
        'add("PASS", "Grouped Navigation", "Top tabs route to Dashboard, AI, Self Learning, Chart, Settings and Notifications hubs.")',
        'add("PASS", "Navigation ownership", "Top-level navigation stays domain-based; research is under AI, provider health under News, recovery/CloudShare under Settings/Backup, and v4 verification under System Test.")'
    )

    path.write_text(text, encoding="utf-8")


def patch_lifecycle(repo: Path) -> None:
    path = repo / "app/src/main/java/com/ksp/cryptobot/lifecycle/TradeLifecycleManager.kt"
    require(path)
    text = path.read_text(encoding="utf-8")

    if "internal fun isExplicitLifecycleSell" not in text:
        anchor = "/**\n * Full live lifecycle layer.\n"
        helper = '''internal fun isExplicitLifecycleSell(decision: AiDecision?): Boolean =
    decision?.finalAction == SignalAction.SELL && decision.allowedToTrade

internal fun shouldDeferSoftLifecycleExitForChurn(
    explicitSell: Boolean,
    handoffProtective: Boolean,
    enteredThisScan: Boolean,
    exitedThisScan: Boolean,
    positionAgeMinutes: Long,
    minHoldMinutes: Int
): Boolean = explicitSell && !handoffProtective && (
    enteredThisScan || exitedThisScan || positionAgeMinutes < minHoldMinutes.coerceAtLeast(0)
)

'''
        text = replace_once(text, anchor, helper + anchor, "lifecycle semantic helpers")

    old_sig = '''    suspend fun runPostDecisionManagement(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        decisions: List<AiDecision>
    ): LifecycleSnapshot {
'''
    new_sig = '''    suspend fun runPostDecisionManagement(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        decisions: List<AiDecision>,
        enteredSymbolsThisScan: Set<String> = emptySet(),
        exitedSymbolsThisScan: Set<String> = emptySet()
    ): LifecycleSnapshot {
'''
    if old_sig in text:
        text = text.replace(old_sig, new_sig, 1)
    elif "enteredSymbolsThisScan: Set<String>" not in text:
        fail("lifecycle post-decision signature changed")

    if "enteredSymbolsThisScan, exitedSymbolsThisScan" not in text:
        text = replace_once(
            text,
            "val managedMessages = managePosition(settings, exchange, position, decision, openOrders)",
            "val managedMessages = managePosition(settings, exchange, position, decision, openOrders, enteredSymbolsThisScan, exitedSymbolsThisScan)",
            "lifecycle managePosition call"
        )

    old_private_sig = '''    private suspend fun managePosition(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        position: PositionInfo,
        decision: AiDecision?,
        openOrders: List<LiveOrderInfo>
    ): List<String> {
'''
    new_private_sig = '''    private suspend fun managePosition(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        position: PositionInfo,
        decision: AiDecision?,
        openOrders: List<LiveOrderInfo>,
        enteredSymbolsThisScan: Set<String>,
        exitedSymbolsThisScan: Set<String>
    ): List<String> {
'''
    if old_private_sig in text:
        text = text.replace(old_private_sig, new_private_sig, 1)
    elif "enteredSymbolsThisScan: Set<String>" not in text[text.find("private suspend fun managePosition"):text.find("private suspend fun managePosition") + 800]:
        fail("lifecycle managePosition signature changed")

    old_bearish = '''        val bearish = decision?.finalAction == SignalAction.SELL || decision?.finalAction == SignalAction.AVOID || decision?.finalAction == SignalAction.STRONG_AVOID
        val riskOffSell = settings.forceSellOnBearishSignal && bearish && decision?.allowedToTrade == true
'''
    new_bearish = '''        // Action contract: AVOID/STRONG_AVOID mean "do not open a new entry".
        // Only an explicit, allowed SELL is a discretionary signal exit.
        val explicitSignalSell = isExplicitLifecycleSell(decision)
        val riskOffSell = settings.forceSellOnBearishSignal && explicitSignalSell
'''
    if old_bearish in text:
        text = text.replace(old_bearish, new_bearish, 1)
    elif "val explicitSignalSell = isExplicitLifecycleSell(decision)" not in text:
        fail("lifecycle bearish semantics anchor changed")

    handoff_anchor = '        val handoffProtective = handoffIntent == HandoffSideIntent.EXIT || handoffIntent == HandoffSideIntent.REDUCE\n'
    if "val softSignalExitDeferred" not in text:
        churn = handoff_anchor + '''        val normalizedSymbol = symbol.uppercase().replace("/", "").replace("-", "")
        val enteredThisScan = normalizedSymbol in enteredSymbolsThisScan
        val exitedThisScan = normalizedSymbol in exitedSymbolsThisScan
        val trackedForChurn = dao.positionForSymbol(symbol)
        val positionAgeMinutes = trackedForChurn?.openedAtEpochMs?.let { opened ->
            ((System.currentTimeMillis() - opened).coerceAtLeast(0L) / 60_000L)
        } ?: Long.MAX_VALUE
        val softSignalExitDeferred = shouldDeferSoftLifecycleExitForChurn(
            explicitSell = riskOffSell,
            handoffProtective = handoffProtective,
            enteredThisScan = enteredThisScan,
            exitedThisScan = exitedThisScan,
            positionAgeMinutes = positionAgeMinutes,
            minHoldMinutes = settings.cooldownAfterBuyMinutes
        )
'''
        text = replace_once(text, handoff_anchor, churn, "lifecycle churn context")

    text = text.replace(
        '            riskOffSell -> "AI bearish/risk-off sell signal"',
        '            riskOffSell && !softSignalExitDeferred -> "explicit AI SELL signal"',
        1
    )

    if "Soft SELL deferred" not in text:
        reason_anchor = '        if (reason == null) {\n'
        note = '''        if (softSignalExitDeferred) {
            val minHold = settings.cooldownAfterBuyMinutes.coerceAtLeast(0)
            val why = when {
                exitedThisScan -> "an exit was already submitted for this symbol in the current scan"
                enteredThisScan -> "the position was entered in the current scan"
                else -> "position age ${positionAgeMinutes}m is below the ${minHold}m soft-signal hold floor"
            }
            out += "[$symbol] Soft SELL deferred to prevent churn: $why. Hard stop-loss, source-protective and profit-protection exits remain active."
        }

'''
        text = replace_once(text, reason_anchor, note + reason_anchor, "lifecycle churn explanation")

    # Re-entry truth: latest BUY starts a fresh lifecycle.
    refresh_anchor = '''        positions.forEach { p ->
            val prev = dao.positionForSymbol(p.symbol)
            val highest = listOf(prev?.highestPriceEur?.toBigDecimalOrNull() ?: BigDecimal.ZERO, p.highestPrice, p.currentPrice).maxOrNull() ?: p.currentPrice
'''
    if refresh_anchor in text and "val reopenedByLatestBuy" not in text:
        repl = '''        val recentLifecycleTrades = dao.recentTradesSnapshot(500)
        positions.forEach { p ->
            val prev = dao.positionForSymbol(p.symbol)
            val latestBuy = recentLifecycleTrades.firstOrNull { it.symbol.equals(p.symbol, ignoreCase = true) && it.side.equals(OrderSide.BUY.name, ignoreCase = true) }
            val reopenedByLatestBuy = latestBuy != null && (prev == null || !prev.status.equals("OPEN", ignoreCase = true) || latestBuy.timestampEpochMs > prev.openedAtEpochMs)
            val highest = if (reopenedByLatestBuy) p.currentPrice else listOf(prev?.highestPriceEur?.toBigDecimalOrNull() ?: BigDecimal.ZERO, p.highestPrice, p.currentPrice).maxOrNull() ?: p.currentPrice
'''
        text = text.replace(refresh_anchor, repl, 1)
        text = text.replace(
            '                    openedAtEpochMs = prev?.openedAtEpochMs ?: now,\n',
            '                    openedAtEpochMs = if (reopenedByLatestBuy) latestBuy!!.timestampEpochMs else prev?.openedAtEpochMs ?: now,\n',
            1
        )

    # Entry-price truth has two legitimate pre-cleanup shapes. Milestone 4
    # upgrades the original fallback chain so a PENDING_ENTRY adopts its first
    # confirmed BUY fill. Preserve that behavior while adding reopened-position
    # lifecycle truth. Do not fail merely because M4 already modernized this block.
    legacy_entry_anchor = '''            val entry = prev?.entryPriceEur?.toBigDecimalOrNull()
                ?: lastBuy?.priceEur?.toBigDecimalOrNull()
                ?: current
'''
    m4_entry_anchor = '''            val previousEntry = prev?.entryPriceEur?.toBigDecimalOrNull()
            val confirmedBuyEntry = lastBuy?.priceEur?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            val entry = if (prev?.status.equals("PENDING_ENTRY", true) && confirmedBuyEntry != null) confirmedBuyEntry
                else previousEntry ?: confirmedBuyEntry ?: current
'''
    entry_repl = '''            val previousEntry = prev?.entryPriceEur?.toBigDecimalOrNull()
            val confirmedBuyEntry = lastBuy?.priceEur?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            val latestBuyIsNewLifecycle = lastBuy != null && (
                prev == null ||
                    (!prev.status.equals("OPEN", ignoreCase = true) && !prev.status.equals("PENDING_ENTRY", ignoreCase = true)) ||
                    lastBuy.timestampEpochMs > prev.openedAtEpochMs
            )
            val entry = when {
                prev?.status.equals("PENDING_ENTRY", true) && confirmedBuyEntry != null -> confirmedBuyEntry
                latestBuyIsNewLifecycle && confirmedBuyEntry != null -> confirmedBuyEntry
                previousEntry != null && previousEntry > BigDecimal.ZERO -> previousEntry
                confirmedBuyEntry != null -> confirmedBuyEntry
                else -> current
            }
'''
    if "val latestBuyIsNewLifecycle" in text:
        pass
    elif m4_entry_anchor in text:
        text = text.replace(m4_entry_anchor, entry_repl, 1)
    elif legacy_entry_anchor in text:
        text = text.replace(legacy_entry_anchor, entry_repl, 1)
    else:
        fail("lifecycle entry-price block is unknown after Milestone 6; refusing an unsafe rewrite")

    # Reconcile stale OPEN rows only after a successful balance read.
    if "Stale lifecycle position closed" not in text:
        return_anchor = '''        return positions
    }

    private suspend fun buildLivePositions'''
        close_block = '''        runCatching { exchange.getPortfolioBalances() }.onSuccess { balances ->
            val heldBases = balances.filter { it.total > BigDecimal.ZERO }.map { it.asset.uppercase() }.toSet()
            dao.openPositionsSnapshot().filter { row ->
                row.baseAsset.uppercase() !in heldBases && !row.status.equals("PENDING_ENTRY", ignoreCase = true)
            }.forEach { stale ->
                dao.updatePositionStatus(stale.symbol, "CLOSED", System.currentTimeMillis())
                log("[${stale.symbol}] Stale lifecycle position closed after confirmed zero exchange balance for ${stale.baseAsset}.", "INFO")
            }
        }.onFailure { error ->
            log("Lifecycle stale-position reconciliation skipped because portfolio balance verification failed: ${error.message}", "WARN")
        }
        return positions
    }

    private suspend fun buildLivePositions'''
        text = replace_once(text, return_anchor, close_block, "stale lifecycle reconciliation")

    # M4 already hardens lifecycle accounting. Require that effective code.
    if "Lifecycle SELL accepted without confirmed fill" not in text or "recordTradeFromLifecycle(symbol, OrderSide.SELL, actualQty" not in text:
        fail("M4 confirmed-fill lifecycle accounting is missing after Milestone 6")

    path.write_text(text, encoding="utf-8")


def patch_controller(repo: Path) -> None:
    path = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    require(path)
    text = path.read_text(encoding="utf-8")

    if "enteredSymbolsThisScan" not in text:
        anchor = '''        val reservedByQuoteThisScan = mutableMapOf<String, BigDecimal>()
        var submittedOrdersThisScan = 0
'''
        text = replace_once(
            text,
            anchor,
            anchor + '''        val enteredSymbolsThisScan = mutableSetOf<String>()
        val exitedSymbolsThisScan = mutableSetOf<String>()
''',
            "same-scan lifecycle state"
        )

        submit_anchor = '''                        if (result.submitted) {
                            submittedOrdersThisScan += 1
                            tradedThisSymbol = true
                        }
'''
        submit_repl = '''                        if (result.submitted) {
                            submittedOrdersThisScan += 1
                            tradedThisSymbol = true
                            val normalizedExecutionSymbol = symbol.uppercase().replace("/", "").replace("-", "")
                            when (decision.finalAction) {
                                SignalAction.BUY, SignalAction.SMALL_BUY -> enteredSymbolsThisScan += normalizedExecutionSymbol
                                SignalAction.SELL -> exitedSymbolsThisScan += normalizedExecutionSymbol
                                else -> Unit
                            }
                        }
'''
        text = replace_once(text, submit_anchor, submit_repl, "same-scan execution classification")

        text = replace_once(
            text,
            "val lifecycle = lifecycleManager.runPostDecisionManagement(settings, exchange, decisions)",
            "val lifecycle = lifecycleManager.runPostDecisionManagement(settings, exchange, decisions, enteredSymbolsThisScan, exitedSymbolsThisScan)",
            "lifecycle same-scan context"
        )

    path.write_text(text, encoding="utf-8")


def add_tests(repo: Path) -> None:
    write(repo / "app/src/test/java/com/ksp/cryptobot/lifecycle/LifecycleExitSemanticsTest.kt", LIFECYCLE_TEST)


def validate(repo: Path) -> None:
    main = (repo / "app/src/main/java/com/ksp/cryptobot/MainActivity.kt").read_text(encoding="utf-8")
    lifecycle = (repo / "app/src/main/java/com/ksp/cryptobot/lifecycle/TradeLifecycleManager.kt").read_text(encoding="utf-8")
    controller = (repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt").read_text(encoding="utf-8")
    checks = {
        "no V4 Systems top-level tab": "V4_SYSTEMS" not in main and "V4 Systems" not in main,
        "no duplicate Settings Truth UI": "Settings Truth" not in main,
        "research routed under AI": "AppTab.RESEARCH_SETTINGS -> V4ResearchPanel()" in main,
        "CloudShare routed under Settings/Backup": "AppTab.CLOUDSHARE_SETTINGS -> CloudShareScreen()" in main,
        "recovery routed under Settings/Backup": "AppTab.RECOVERY_TOOLS -> V4RecoveryPanel()" in main,
        "AVOID not lifecycle SELL": "explicitSignalSell = isExplicitLifecycleSell(decision)" in lifecycle and "SignalAction.AVOID ||" not in lifecycle,
        "soft churn guard": "Soft SELL deferred to prevent churn" in lifecycle,
        "same scan context wired": "enteredSymbolsThisScan" in controller and "exitedSymbolsThisScan" in controller,
        "confirmed-fill lifecycle accounting": "Lifecycle SELL accepted without confirmed fill" in lifecycle,
        "reopened entry truth": "latestBuyIsNewLifecycle" in lifecycle and "reopenedByLatestBuy" in lifecycle,
        "stale position reconciliation guarded by successful balance fetch": "Stale lifecycle position closed after confirmed zero exchange balance" in lifecycle,
        "news health in News domain": "NewsProviderHealthRegistry.snapshot()" in main,
        "settings hierarchy renamed": "Connection & Trading" in main and "Automation & Risk" in main,
    }
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
    failed = [name for name, ok in checks.items() if not ok]
    if failed:
        fail("integration validation failed: " + ", ".join(failed))


def main() -> None:
    repo = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()
    require(repo / "app/src/main/java/com/ksp/cryptobot/MainActivity.kt")
    patch_v4_panel_exports(repo)
    patch_main_ui(repo)
    patch_lifecycle(repo)
    patch_controller(repo)
    add_tests(repo)
    validate(repo)
    print("[CTS full integration cleanup] Applied successfully.")


if __name__ == "__main__":
    main()
