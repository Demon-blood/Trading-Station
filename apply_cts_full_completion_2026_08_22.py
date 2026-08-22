#!/usr/bin/env python3
"""Crypto TradeStation v4.0.7 full completion tranche — 2026-08-22.

Run AFTER:
  * cumulative v4 source materialization,
  * v4.0.7 stabilization,
  * 2026-08-22 research-truth migration,
  * CTS readiness/balance/OrderIntent migration.

This migration implements the remaining handoff architecture while preserving provenance truth.
It never promotes an under-specified educator framework to source-exact execution.
"""
from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path

MARKER = "CTS_FULL_COMPLETION_20260822"


def fail(msg: str) -> None:
    raise SystemExit(f"[CTS full completion] {msg}")


def require(path: Path) -> None:
    if not path.exists():
        fail(f"Required file missing: {path}")


def read(path: Path) -> str:
    require(path)
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.rstrip() + "\n", encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str, required: bool = True) -> str:
    if new in text:
        return text
    n = text.count(old)
    if n != 1:
        if required:
            fail(f"{label}: expected one source match, found {n}")
        return text
    return text.replace(old, new, 1)


def copy_payload(repo: Path, bundle_root: Path) -> None:
    src = bundle_root / "payload"
    require(src)
    for p in src.rglob("*"):
        if p.is_file():
            rel = p.relative_to(src)
            dest = repo / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(p, dest)


def patch_models(path: Path) -> None:
    text = read(path)
    if "CTS_TURTLE_SPOT_SAFE" not in text:
        anchor = "    VOLUME_ANOMALY_WHALE_MOVE\n"
        if anchor not in text:
            # Newer enum may already have a trailing comma or additions.
            m = re.search(r"enum class StrategyMode \{(.*?)\n\}", text, re.S)
            if not m:
                fail("StrategyMode enum not found")
            body = m.group(1).rstrip()
            body = body.rstrip(",") + ",\n    CTS_TURTLE_SPOT_SAFE,\n    CTS_KAK_CLOSE_BREAK_RETEST_V1"
            text = text[:m.start(1)] + body + text[m.end(1):]
        else:
            text = text.replace(anchor, "    VOLUME_ANOMALY_WHALE_MOVE,\n    CTS_TURTLE_SPOT_SAFE,\n    CTS_KAK_CLOSE_BREAK_RETEST_V1\n", 1)
    elif "CTS_KAK_CLOSE_BREAK_RETEST_V1" not in text:
        text = text.replace("    CTS_TURTLE_SPOT_SAFE", "    CTS_TURTLE_SPOT_SAFE,\n    CTS_KAK_CLOSE_BREAK_RETEST_V1", 1)
    write(path, text)


def patch_multi_strategy(path: Path) -> None:
    text = read(path)
    if MARKER in text:
        return
    pkg_anchor = "import com.ksp.cryptobot.core.*\n"
    imports = (
        pkg_anchor
        + "import com.ksp.cryptobot.strategy.turtle.TurtleSpotSafeStrategy\n"
        + "import com.ksp.cryptobot.strategy.structure.KoroushCtsReferenceStrategy\n"
    )
    text = replace_once(text, pkg_anchor, imports, "strategy imports")
    class_anchor = "    private val scalper: MultiTimeframeScalpingStrategy = MultiTimeframeScalpingStrategy()\n"
    class_patch = class_anchor + (
        "    private val turtleSpotSafe = TurtleSpotSafeStrategy()\n"
        "    private val kakReference = KoroushCtsReferenceStrategy()\n"
        f"    // {MARKER}\n"
    )
    text = replace_once(text, class_anchor, class_patch, "strategy fields")

    ret = "        return candidates.maxByOrNull { it.score } ?: StrategyCandidate(StrategyMode.AUTO, 0, SignalAction.WAIT, \"No strategy candidate available.\", BigDecimal.ZERO, BigDecimal.ZERO)\n"
    extra = r'''        // Source-truth strategies are opt-in until their promotion lifecycle permits AUTO selection.
        if (settings.strategyMode == StrategyMode.CTS_TURTLE_SPOT_SAFE) {
            val spreadPct = if (ticker.lastPrice > BigDecimal.ZERO) ticker.ask.subtract(ticker.bid).abs()
                .divide(ticker.lastPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal("999")
            val liquidityAllowed = spreadPct <= settings.autoSymbolMaxSpreadPercent && ticker.volume24h >= settings.autoSymbolMinVolume24hEur
            candidates += turtleSpotSafe.candidate(ticker, candlesByTimeframe[Timeframe.H4].orEmpty(), false, liquidityAllowed).first
        }
        if (settings.strategyMode == StrategyMode.CTS_KAK_CLOSE_BREAK_RETEST_V1) {
            candidates += kakReference.candidate(ticker, candlesByTimeframe[Timeframe.H1].orEmpty())
        }
'''
    text = replace_once(text, ret, extra + ret, "strategy candidate integration")
    write(path, text)


def patch_automation_sell_safety(path: Path) -> None:
    text = read(path)
    old = "        val allowed = riskState.allowed && allowedBySignal && positionSize > BigDecimal.ZERO\n"
    new = (
        "        // Protective/reducing SELL is not disabled by entry risk locks. Holdings are verified later.\n"
        "        val allowed = if (action == SignalAction.SELL) true else riskState.allowed && allowedBySignal && positionSize > BigDecimal.ZERO\n"
    )
    if old in text:
        text = text.replace(old, new, 1)
    write(path, text)


def patch_controller(path: Path) -> None:
    text = read(path)
    if f"// {MARKER}" in text:
        return

    anchor = "import com.ksp.cryptobot.performance.PerformanceLabEngine\n"
    additions = anchor + r'''import com.ksp.cryptobot.market.PersistentCandleRepository
import com.ksp.cryptobot.execution.ExecutionStateRuntime
import com.ksp.cryptobot.execution.UnifiedOrderIntentRouter
import com.ksp.cryptobot.maintenance.DatabaseMaintenance
import com.ksp.cryptobot.news.NewsAcquisitionCoordinator
import com.ksp.cryptobot.strategy.provenance.SignalProvenance
import com.ksp.cryptobot.strategy.provenance.SignalProvenanceStore
import com.ksp.cryptobot.strategy.provenance.StrategyProvenanceRegistry
import com.ksp.cryptobot.learning.TrainingDomainPolicy
import com.ksp.cryptobot.release.CompletionReleaseGate
import com.ksp.cryptobot.risk.CorrelationClusterGuard
import com.ksp.cryptobot.risk.CostAwareRiskInput
import com.ksp.cryptobot.risk.CostAwareSpotRiskSizer
import com.ksp.cryptobot.risk.KrakenCostFallback20260822
import com.ksp.cryptobot.strategy.turtle.TurtleSpotSafeStrategy
'''
    text = replace_once(text, anchor, additions, "controller completion imports")

    field_anchor = "    private val remoteCommandClient = RemoteCommandClient()\n"
    fields = field_anchor + r'''    // CTS_FULL_COMPLETION_20260822
    private val candleRepository = PersistentCandleRepository(appContext)
    private val executionStateStore = ExecutionStateRuntime.install(appContext)
    private val newsAcquisitionCoordinator = NewsAcquisitionCoordinator()
    private val signalProvenanceStore = SignalProvenanceStore(appContext)
'''
    text = replace_once(text, field_anchor, fields, "controller completion fields")

    # Ensure strategy modes requiring history fetch candles even when recovered scalper toggle is off.
    old_cond = "                val candlesByTimeframe = if (settings.recoveredScalpingStrategyEnabled) {\n"
    if old_cond in text:
        new_cond = (
            "                val needsStrategyHistory = settings.recoveredScalpingStrategyEnabled || settings.strategyMode in setOf(StrategyMode.CTS_TURTLE_SPOT_SAFE, StrategyMode.CTS_KAK_CLOSE_BREAK_RETEST_V1)\n"
            "                val candlesByTimeframe = if (needsStrategyHistory) {\n"
        )
        text = text.replace(old_cond, new_cond, 1)

    old_fetch = "                    Timeframe.values().associateWith { timeframe -> exchange.getCandles(symbol, timeframe, 140) }\n"
    if old_fetch in text:
        new_fetch = r'''                    Timeframe.values().associateWith { timeframe ->
                        val fetchLimit = if (timeframe == Timeframe.H4) 720 else 180
                        val fresh = exchange.getCandles(symbol, timeframe, fetchLimit)
                        val persisted = candleRepository.mergeHistory(symbol, timeframe, fresh, if (timeframe == Timeframe.H4) 2500 else 1000)
                        updateStatus("[$symbol] Candle store ${timeframe.name}: fresh=${fresh.size}, committedPersisted=${persisted.size}, newest=${persisted.lastOrNull()?.openTimeEpochMs ?: 0L}", "INFO")
                        persisted
                    }
'''
        text = text.replace(old_fetch, new_fetch, 1)
    else:
        # Some migrated controller formats the associateWith block differently.
        text, n = re.subn(
            r"Timeframe\.values\(\)\.associateWith \{ timeframe -> exchange\.getCandles\(symbol, timeframe, \d+\) \}",
            '''Timeframe.values().associateWith { timeframe ->\n                        val fresh = exchange.getCandles(symbol, timeframe, if (timeframe == Timeframe.H4) 720 else 180)\n                        candleRepository.mergeHistory(symbol, timeframe, fresh, if (timeframe == Timeframe.H4) 2500 else 1000)\n                    }''',
            text, count=1
        )
        if n == 0:
            print("WARN: candle fetch shape not found; persistent repository exists but controller fetch integration was not rewritten.")

    # Periodic bounded telemetry retention; never VACUUM while trading.
    scan_anchor = "        val proReadiness = proAutomationSuite.readiness(settings)\n"
    if "DatabaseMaintenance.maybeRun" not in text and scan_anchor in text:
        text = text.replace(
            scan_anchor,
            "        val maintenance = DatabaseMaintenance.maybeRun(appContext, tradingActive = running)\n"
            "        if (maintenance.ran) updateStatus(\"Database retention: deleted≈${maintenance.deletedRowsApprox}; checkpoint=${maintenance.checkpoint}\", \"INFO\")\n"
            + scan_anchor,
            1,
        )

    # Use persistent CTS reservation amount in the already-centralized spendable calculation.
    text = text.replace("                ctsReserved = BigDecimal.ZERO, // no separate persistent ReservationLedger exists yet\n",
                        "                ctsReserved = executionStateStore.activeReserved(quoteAsset),\n")
    text = text.replace("                ctsReserved = BigDecimal.ZERO,\n                pendingOrderReserve = quoteReservedThisScan,\n",
                        "                ctsReserved = executionStateStore.activeReserved(quoteAsset),\n                pendingOrderReserve = quoteReservedThisScan,\n")

    # CTS Turtle cost-aware sizing is injected after the shared spendable/target-notional calculation.
    risk_anchor = """        if (side == OrderSide.BUY) {
            val orderBookCheck = orderBookDepthAllowsExecution(settings, exchange, ticker.symbol, side, targetNotional, price)
"""
    if "Turtle cost-aware 0.5% equity risk" not in text:
        risk_block = r'''        // Turtle cost-aware 0.5% equity risk. CTS adaptation; not historical Turtle sizing.
        if (side == OrderSide.BUY && settings.strategyMode == StrategyMode.CTS_TURTLE_SPOT_SAFE) {
            val portfolioEquity = runCatching { loadPortfolioSnapshot(settings).totalValueEur }.getOrDefault(BigDecimal.ZERO)
            val turtleEval = TurtleSpotSafeStrategy().evaluate(
                ticker = ticker,
                h4Candles = candleRepository.load(ticker.symbol, Timeframe.H4, 2500),
                hasOpenPosition = false,
                liquidityAllowed = true
            )
            val technicalStop = turtleEval.initialStopPrice
            if (technicalStop <= BigDecimal.ZERO || technicalStop >= price) {
                updateStatus("[${ticker.symbol}] Turtle risk blocked: invalid technical 2N stop=$technicalStop at entry=$price.", "WARN")
                return ExecutionAttemptResult(false)
            }
            val spreadRate = if (ticker.lastPrice > BigDecimal.ZERO) {
                ticker.ask.subtract(ticker.bid).abs().divide(ticker.lastPrice, 12, RoundingMode.HALF_UP)
            } else BigDecimal.ZERO
            val turtleRisk = CostAwareSpotRiskSizer.size(
                CostAwareRiskInput(
                    equityQuote = portfolioEquity,
                    riskFraction = BigDecimal("0.005"),
                    entryPrice = price,
                    stopPrice = technicalStop,
                    entryFeeRate = KrakenCostFallback20260822.conservativeEntryFeeRate,
                    exitFeeRate = KrakenCostFallback20260822.conservativeExitFeeRate,
                    roundTripSlippageRate = spreadRate,
                    hardNotionalCap = perOrderCap,
                    spendableQuote = spendableQuote.maxNotionalAfterFeeReserve(feeReserveMultiplier)
                )
            )
            updateStatus(
                "[${ticker.symbol}] Turtle cost-aware risk: ${turtleRisk.reason} feeFallback=${KrakenCostFallback20260822.sourceLabel}",
                if (turtleRisk.allowed) "INFO" else "WARN"
            )
            if (!turtleRisk.allowed) return ExecutionAttemptResult(false)
            targetNotional = targetNotional.min(turtleRisk.notionalQuote)
            if (targetNotional <= BigDecimal.ZERO) return ExecutionAttemptResult(false)
        }

'''
        if risk_anchor not in text:
            fail("Turtle risk insertion anchor changed after readiness/exchange-minimum patches")
        text = text.replace(risk_anchor, risk_block + risk_anchor, 1)

    # Reconcile crash-persistent reservations with broker open orders at start of executing scans.
    if "UnifiedOrderIntentRouter.reconcileOpenOrders" not in text:
        exec_anchor = "        val paperExecution = execute && (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER)\n"
        if exec_anchor in text:
            text = text.replace(exec_anchor, exec_anchor + r'''        if (execute) {
            val recoveryOrders = runCatching { exchange.getOpenOrders() }.getOrDefault(emptyList())
            UnifiedOrderIntentRouter.reconcileOpenOrders(recoveryOrders)
            updateStatus("Execution-state reconciliation: brokerOpenOrders=${recoveryOrders.size}, activeEURReserved=${executionStateStore.activeReserved("EUR")}", "INFO")
        }
''', 1)

    # Replace main direct broker submission with unified intent router.
    direct = "val result = runCatching { exchange.placeOrder(request) }.getOrElse { error ->"
    if direct in text:
        routed = r'''val result = runCatching {
            UnifiedOrderIntentRouter.submit(
                settings = settings,
                exchange = exchange,
                intent = orderIntent,
                quoteAsset = quoteAsset,
                reservationAmount = if (side == OrderSide.BUY) targetNotional.multiply(feeReserveMultiplier).setScale(2, RoundingMode.UP) else BigDecimal.ZERO,
                maxSpendableBeforeLedger = spendableQuote.spendable.add(spendableQuote.ctsReserved)
            ).result
        }.getOrElse { error ->'''
        text = text.replace(direct, routed, 1)
    elif "UnifiedOrderIntentRouter.submit(" not in text:
        fail("Main execution direct placeOrder anchor missing after OrderIntent migration")

    # Persistent ledger replaces the old ephemeral per-scan reservation accumulator after submit.
    text = text.replace(
        "        return ExecutionAttemptResult(true, quoteAsset, reservedAmount)\n",
        "        return ExecutionAttemptResult(true, quoteAsset, BigDecimal.ZERO) // persistent ReservationLedger is authoritative\n",
        1,
    )

    # Persist source/provenance sidecar for every completed decision. Fields not exposed by current engines remain explicit, not fabricated.
    why_anchor = "                val whyLine = proAutomationSuite.explainTrade(ticker, decision, symbolRank, netCheck)\n"
    if "signalProvenanceStore.record(" not in text and why_anchor in text:
        provenance = why_anchor + r'''                val provenance = StrategyProvenanceRegistry.forMode(autoDecision.selectedStrategy)
                signalProvenanceStore.record(
                    SignalProvenance(
                        strategyId = provenance.strategyId,
                        strategyVersion = provenance.version,
                        ruleProfileId = provenance.ruleProfileId,
                        provenanceType = provenance.provenanceType,
                        sourceIds = provenance.sourceIds,
                        symbol = ticker.symbol,
                        timeframe = if (settings.strategyMode == StrategyMode.CTS_TURTLE_SPOT_SAFE) "D1_AGGREGATED_FROM_COMMITTED_H4" else "MULTI",
                        marketRegime = autoDecision.marketRegime.name,
                        signalTimestampEpochMs = System.currentTimeMillis(),
                        featureSnapshot = mapOf(
                            "last" to ticker.lastPrice.toPlainString(), "bid" to ticker.bid.toPlainString(), "ask" to ticker.ask.toPlainString(),
                            "volume24h" to ticker.volume24h.toPlainString(), "change24hPct" to ticker.priceChangePercent24h.toPlainString()
                        ),
                        entryRuleResults = mapOf("allowedToTrade" to decision.allowedToTrade, "actionable" to (decision.finalAction in setOf(SignalAction.BUY,SignalAction.SMALL_BUY,SignalAction.SELL))),
                        invalidation = "stopLossPercent=${autoDecision.stopLossPercent}; exact structural invalidation is strategy-profile specific",
                        targetPlan = "takeProfitPercent=${autoDecision.takeProfitPercent}; lifecycle=${settings.liveLifecycleManagerEnabled}",
                        riskBudget = autoDecision.positionSizeEur.toPlainString(),
                        estimatedFees = "MODELED_IN_NET_PROFIT_GATE;numeric_component_not_exposed_by_current_result_type",
                        estimatedSlippage = "MODELED_BY_SPREAD_ORDERBOOK_GATES;numeric_component_not_exposed_by_current_result_type",
                        expectedNetR = "NOT_EXPOSED_AS_CALIBRATED_R;netProfitAllowed=${netCheck.allowed}"
                    )
                )
'''
        text = text.replace(why_anchor, provenance, 1)

    # Strategy registry must gate new handoff profiles from accidental live execution.
    guard_anchor = "        val capability = ExchangeCapabilityChecker.capability(settings.exchangeProvider)\n"
    if "Strategy provenance execution gate" not in text and guard_anchor in text:
        gate = guard_anchor + r'''        // Strategy provenance execution gate: research/framework profiles cannot become live because a mode name exists.
        val selectedDefinition = StrategyProvenanceRegistry.forMode(settings.strategyMode)
        if (sideForProvenance(decision) == OrderSide.BUY) {
            if (settings.mode == BotMode.PAPER && !selectedDefinition.enabledForPaper) {
                updateStatus("Trade blocked by Strategy provenance execution gate: ${selectedDefinition.strategyId} is not PAPER-enabled; lifecycle=${selectedDefinition.lifecycle} provenance=${selectedDefinition.provenanceType}.", "WARN")
                return ExecutionAttemptResult(false)
            }
            if (settings.mode != BotMode.PAPER && settings.strategyMode in setOf(StrategyMode.CTS_TURTLE_SPOT_SAFE, StrategyMode.CTS_KAK_CLOSE_BREAK_RETEST_V1) && !selectedDefinition.enabledForLive) {
                updateStatus("Trade blocked by Strategy provenance execution gate: ${selectedDefinition.strategyId} has not completed WALK_FORWARD→PAPER→SHADOW_LIVE→ELIGIBLE_FOR_LIVE.", "WARN")
                return ExecutionAttemptResult(false)
            }
        }
        if (sideForProvenance(decision) == OrderSide.BUY && settings.strategyMode == StrategyMode.CTS_TURTLE_SPOT_SAFE) {
            val candidateHistory = candleRepository.load(ticker.symbol, Timeframe.H4, 900)
            val btcFresh = if (ticker.symbol.equals("BTCEUR", true)) emptyList() else runCatching { exchange.getCandles("BTCEUR", Timeframe.H4, 720) }.getOrDefault(emptyList())
            if (btcFresh.isNotEmpty()) candleRepository.upsertKrakenRest("BTCEUR", Timeframe.H4, btcFresh)
            val btcHistory = if (ticker.symbol.equals("BTCEUR", true)) candidateHistory else candleRepository.load("BTCEUR", Timeframe.H4, 900)
            val openHistories = dao.openPositionsSnapshot().associate { p -> p.symbol to candleRepository.load(p.symbol, Timeframe.H4, 900) }
            val cluster = CorrelationClusterGuard.assessCandidate(ticker.symbol, candidateHistory, btcHistory, openHistories)
            updateStatus("[${ticker.symbol}] Turtle correlation cluster: ${cluster.reason}", if (cluster.allowed) "INFO" else "WARN")
            if (!cluster.allowed) return ExecutionAttemptResult(false)
        }
'''
        text = text.replace(guard_anchor, gate, 1)
        helper_anchor = "    private fun isCashLikeBaseAsset(asset: String): Boolean {\n"
        helper = "    private fun sideForProvenance(decision: AiDecision): OrderSide = if (decision.finalAction == SignalAction.SELL) OrderSide.SELL else OrderSide.BUY\n\n"
        if helper_anchor in text:
            text = text.replace(helper_anchor, helper + helper_anchor, 1)

    # Centralized news acquisition replaces symbol-by-symbol provider fan-out while keeping cache persistence.
    block_re = re.compile(
        r"        val all = mutableListOf<NewsArticle>\(\)\n        providers\.forEach \{ \(name, provider\) ->.*?        val deduped = all\n            \.distinctBy \{ it\.title\.lowercase\(\)\.take\(120\) \}\n            \.sortedByDescending \{ it\.publishedAt \}\n            \.take\(80\)\n",
        re.S,
    )
    m = block_re.search(text)
    if m:
        repl = r'''        val deduped = newsAcquisitionCoordinator.fetch(symbol, providers) { name, state, detail ->
            updateStatus("[$symbol] $name providerState=$state: $detail", if (state.name in setOf("RATE_LIMITED","QUOTA_EXHAUSTED","AUTH_ERROR","TEMPORARY_FAILURE")) "WARN" else "INFO")
        }
'''
        text = text[:m.start()] + repl + text[m.end():]
    elif "newsAcquisitionCoordinator.fetch" not in text:
        print("WARN: legacy news fan-out block shape changed; coordinator exists but fetchNewsForSymbol rewrite was not applied.")

    # Diagnostics sections.
    if "[CTS_COMPLETION]" not in text:
        dm = re.search(r'(?m)^(\s*)([A-Za-z_][A-Za-z0-9_]*)\.appendLine\("\[RECENT_STATUS_LOG\]"\)', text)
        if dm:
            ind,var = dm.group(1),dm.group(2)
            insert = (
                f'{ind}{var}.appendLine("[CTS_COMPLETION]")\n'
                f'{ind}CompletionReleaseGate.staticRuntimeContracts().checks.forEach {{ {var}.appendLine(it) }}\n'
                f'{ind}{var}.appendLine("strategyRegistry=" + StrategyProvenanceRegistry.all().joinToString(",") {{ it.strategyId + ":" + it.provenanceType + ":" + it.lifecycle }})\n'
                f'{ind}executionStateStore.diagnostics().forEach {{ {var}.appendLine(it) }}\n'
                f'{ind}newsAcquisitionCoordinator.snapshots().forEach {{ {var}.appendLine("newsBudget=" + it.toString()) }}\n'
                f'{ind}signalProvenanceStore.recent(10).forEach {{ {var}.appendLine("signalProvenance=" + it) }}\n'
                f'{ind}DatabaseMaintenance.storageDiagnostics(appContext).forEach {{ {var}.appendLine("dbRetention=" + it) }}\n'
                f'{ind}{var}.appendLine("learningDomain=" + TrainingDomainPolicy.current(settings) + "|separated=" + settings.selfLearningPaperAndLiveSeparated)\n'
                f'{ind}{var}.appendLine()\n'
            )
            text = text[:dm.start()] + insert + text[dm.start():]

    write(path, text)


def patch_learning(path: Path) -> None:
    text = read(path)
    if "TrainingDomainPolicy.current(settings)" in text:
        return
    # Imports.
    imp = "import com.ksp.cryptobot.data.TradeEntity\n"
    text = replace_once(text, imp, imp + "import com.ksp.cryptobot.learning.TrainingDomainPolicy\n", "learning domain import", required=False)

    old = "        val trades = dao.allTradesSnapshot().take(settings.selfLearningLookbackTrades.coerceAtLeast(20))\n        val now = System.currentTimeMillis()\n"
    new = r'''        val domain = TrainingDomainPolicy.current(settings)
        val rawTrades = dao.allTradesSnapshot().take(settings.selfLearningLookbackTrades.coerceAtLeast(20))
        val trades = TrainingDomainPolicy.filterTrades(rawTrades, domain, settings.selfLearningPaperAndLiveSeparated)
        TrainingDomainPolicy.assertNoCrossDomainUse(domain, trades, settings.selfLearningPaperAndLiveSeparated)
        val now = System.currentTimeMillis()
'''
    text = replace_once(text, old, new, "learning refresh domain filter")

    # Domain-key persisted profiles so PAPER refresh cannot overwrite LIVE profiles.
    text = text.replace("buildSymbolProfile(symbol, rows, settings, now).also { profile ->\n                dao.upsertLearnedSymbolProfile(profile)",
                        "buildSymbolProfile(symbol, rows, settings, now).copy(symbol = TrainingDomainPolicy.symbolKey(symbol, domain, settings.selfLearningPaperAndLiveSeparated)).also { profile ->\n                dao.upsertLearnedSymbolProfile(profile)")
    text = text.replace("buildStrategyProfile(strategy, rows, settings, now).also { profile -> dao.upsertLearnedStrategyProfile(profile) }",
                        "buildStrategyProfile(strategy, rows, settings, now).copy(strategyKey = TrainingDomainPolicy.strategyKey(strategy, domain, settings.selfLearningPaperAndLiveSeparated)).also { profile -> dao.upsertLearnedStrategyProfile(profile) }")
    text = text.replace("buildHoldProfile(symbol, rows, settings, now).also { profile ->",
                        "buildHoldProfile(symbol, rows, settings, now).copy(symbol = TrainingDomainPolicy.symbolKey(symbol, domain, settings.selfLearningPaperAndLiveSeparated)).also { profile ->")

    # Lookups use active domain keys.
    text = text.replace("dao.learnedSymbolProfile(decision.symbol.uppercase())",
                        "dao.learnedSymbolProfile(TrainingDomainPolicy.symbolKey(decision.symbol, TrainingDomainPolicy.current(settings), settings.selfLearningPaperAndLiveSeparated))")
    text = text.replace("dao.learnedSymbolProfile(normalized)",
                        "dao.learnedSymbolProfile(TrainingDomainPolicy.symbolKey(normalized, TrainingDomainPolicy.current(settings), settings.selfLearningPaperAndLiveSeparated))")
    text = text.replace(
        "val strategyProfiles = dao.learnedStrategyProfilesSnapshot()\n",
        "val strategyProfiles = dao.learnedStrategyProfilesSnapshot().filter { profile -> !settings.selfLearningPaperAndLiveSeparated || profile.strategyKey.startsWith(TrainingDomainPolicy.current(settings).name + \"|\") }\n",
        1
    )
    text = text.replace("dao.learnedHoldProfile(position.symbol.uppercase())",
                        "dao.learnedHoldProfile(TrainingDomainPolicy.symbolKey(position.symbol, TrainingDomainPolicy.current(settings), settings.selfLearningPaperAndLiveSeparated))")

    # Strategy profile values have a domain prefix when separation is enabled.
    text = text.replace("StrategyMode.valueOf(symbolProfile.preferredStrategy)", "StrategyMode.valueOf(TrainingDomainPolicy.stripDomain(symbolProfile.preferredStrategy))")
    text = text.replace("StrategyMode.valueOf(bestStrategy.strategyKey)", "StrategyMode.valueOf(TrainingDomainPolicy.stripDomain(bestStrategy.strategyKey))")
    text = text.replace("it.strategyKey == automation.selectedStrategy.name",
                        "it.strategyKey == TrainingDomainPolicy.strategyKey(automation.selectedStrategy.name, TrainingDomainPolicy.current(settings), settings.selfLearningPaperAndLiveSeparated)")

    # Summary carries proof of separation.
    text = text.replace(
        'val summary = "Learning refreshed: symbols=${symbolProfiles.size}, strategies=${strategyProfiles.size}, holdProfiles=${holdProfiles.size}, trades=${trades.size}, live=$liveCount, paper=$paperCount. Min sample=${settings.selfLearningMinSamples}."',
        'val summary = "Learning refreshed: domain=$domain, crossDomainSamplesUsed=0, symbols=${symbolProfiles.size}, strategies=${strategyProfiles.size}, holdProfiles=${holdProfiles.size}, trades=${trades.size}, live=$liveCount, paper=$paperCount. Min sample=${settings.selfLearningMinSamples}."'
    )
    write(path, text)


def patch_backtest(path: Path) -> None:
    text = read(path)
    run_anchor = "    fun run(symbol: String, timeframe: Timeframe, strategy: StrategyMode, candles: List<Candle>, settings: BotSettings): BacktestReport {\n"
    if "HandoffBacktestEngine.run" not in text:
        if run_anchor not in text: fail("BacktestEngine run anchor changed")
        inject = """        if (strategy in setOf(StrategyMode.CTS_TURTLE_SPOT_SAFE, StrategyMode.CTS_KAK_CLOSE_BREAK_RETEST_V1)) {
            return HandoffBacktestEngine.run(symbol, timeframe, strategy, candles, settings)
        }
"""
        text = text.replace(run_anchor, run_anchor + inject, 1)
    enum_line = '            StrategyMode.VOLUME_ANOMALY_WHALE_MOVE -> last.volume > avgVol.multiply(BigDecimal("2.0")) && last.close > last.open\n'
    add_line = enum_line + '            StrategyMode.CTS_TURTLE_SPOT_SAFE, StrategyMode.CTS_KAK_CLOSE_BREAK_RETEST_V1 -> false\n'
    if 'StrategyMode.CTS_TURTLE_SPOT_SAFE, StrategyMode.CTS_KAK_CLOSE_BREAK_RETEST_V1 -> false' not in text:
        if enum_line not in text: fail("BacktestEngine shouldEnter enum anchor changed")
        text = text.replace(enum_line, add_line, 1)
    write(path, text)


def patch_production(path: Path) -> None:
    text = read(path)
    if "EntryExitSafetyPolicy.evaluate" in text:
        return
    imp = "import com.ksp.cryptobot.data.*\n"
    if imp in text:
        text = text.replace(imp, imp + "import com.ksp.cryptobot.governance.EntryExitSafetyPolicy\nimport com.ksp.cryptobot.governance.KillSeverity\n", 1)
    old = r'''        val adjustment = (safe.scoreAdjustment + quality.scoreAdjustment + counterAdj).coerceIn(-12, 6)
        val liveMode = settings.mode == BotMode.LIVE_AUTO || settings.mode == BotMode.LIVE_CONFIRM
        val blocked = !anomaly.allowed || !kill.allowed || risk.blocked || (liveMode && safe.blockLiveEntries)
        val sizeMultiplier = (safe.sizeMultiplier * risk.multiplier).coerceIn(0.0, 1.0)
        val finalScore = (decision.finalScore + adjustment).coerceIn(0, 100)
        val adjustedAction = if (blocked && decision.finalAction in setOf(SignalAction.BUY, SignalAction.SMALL_BUY)) SignalAction.WAIT else decision.finalAction
        val adjusted = decision.copy(
            finalAction = adjustedAction,
            finalScore = finalScore,
            confidencePercent = minOf(decision.confidencePercent, finalScore),
            allowedToTrade = decision.allowedToTrade && !blocked,
'''
    new = r'''        val adjustment = (safe.scoreAdjustment + quality.scoreAdjustment + counterAdj).coerceIn(-12, 6)
        val liveMode = settings.mode == BotMode.LIVE_AUTO || settings.mode == BotMode.LIVE_CONFIRM
        val killSeverity = runCatching { KillSeverity.valueOf(kill.severity.uppercase()) }.getOrDefault(KillSeverity.NONE)
        val policy = EntryExitSafetyPolicy.evaluate(decision.finalAction, killSeverity, liveMode && safe.blockLiveEntries)
        val entryAction = decision.finalAction in setOf(SignalAction.BUY, SignalAction.SMALL_BUY)
        val entryBlocked = entryAction && (!anomaly.allowed || !kill.allowed || risk.blocked || policy.blockNewExposure)
        val blocked = entryBlocked
        val sizeMultiplier = (safe.sizeMultiplier * risk.multiplier).coerceIn(0.0, 1.0)
        val finalScore = (decision.finalScore + adjustment).coerceIn(0, 100)
        val adjustedAction = if (entryBlocked) SignalAction.WAIT else decision.finalAction
        val adjusted = decision.copy(
            finalAction = adjustedAction,
            finalScore = finalScore,
            confidencePercent = minOf(decision.confidencePercent, finalScore), // compatibility field; UI should treat as score unless calibrated
            allowedToTrade = if (decision.finalAction == SignalAction.SELL) true else decision.allowedToTrade && !entryBlocked,
'''
    if old not in text:
        fail("Production intelligence block shape changed; cannot guarantee protective-exit safety")
    text = text.replace(old, new, 1)
    write(path, text)


def patch_lifecycle(path: Path) -> None:
    text = read(path)
    if "UnifiedOrderIntentRouter.submit" in text:
        return
    imp = "import com.ksp.cryptobot.status.BotStatusStore\n"
    additions = imp + "import com.ksp.cryptobot.execution.OrderIntent\nimport com.ksp.cryptobot.execution.OrderIntentIds\nimport com.ksp.cryptobot.execution.UnifiedOrderIntentRouter\nimport com.ksp.cryptobot.strategy.turtle.TurtleSpotSafeStrategy\nimport com.ksp.cryptobot.strategy.turtle.TurtleSignalType\n"
    text = replace_once(text, imp, additions, "lifecycle imports")

    # Detect Turtle channel exit; suppress generic fixed TP/trailing for Turtle-managed positions.
    bearish_anchor = "        val riskOffSell = settings.forceSellOnBearishSignal && bearish && decision?.allowedToTrade == true\n"
    if bearish_anchor in text:
        turtle = bearish_anchor + r'''        val recentBuy = dao.recentTradesSnapshot(250).firstOrNull { it.symbol.equals(symbol, true) && it.side == OrderSide.BUY.name }
        val turtleManaged = recentBuy?.aiReason?.contains("CTS_TURTLE_SPOT_SAFE", ignoreCase = true) == true || settings.strategyMode == StrategyMode.CTS_TURTLE_SPOT_SAFE
        val turtleExit = if (turtleManaged) {
            val h4 = runCatching { exchange.getCandles(symbol, Timeframe.H4, 720) }.getOrDefault(emptyList())
            TurtleSpotSafeStrategy().evaluate(
                ticker = MarketTicker(symbol, position.currentPrice, position.currentPrice, position.currentPrice, BigDecimal.ZERO, BigDecimal.ZERO),
                h4Candles = h4,
                hasOpenPosition = true,
                liquidityAllowed = true
            ).type == TurtleSignalType.EXIT_LONG
        } else false
'''
        text = text.replace(bearish_anchor, turtle, 1)
        text = text.replace("val hitTrailing = settings.profitMaximizerEnabled && settings.enableTrailingStop", "val hitTrailing = !turtleManaged && settings.profitMaximizerEnabled && settings.enableTrailingStop", 1)
        text = text.replace("val hitTakeProfit = settings.autoTakeProfitEnabled", "val hitTakeProfit = !turtleManaged && settings.autoTakeProfitEnabled", 1)
        reason_anchor = "        val reason = when {\n"
        text = text.replace(reason_anchor, reason_anchor + '            turtleExit -> "CTS_TURTLE_SPOT_SAFE 20-day trend exit"\n', 1)

    # Replace request construction with OrderIntent.
    req_re = re.compile(r"        val request = OrderRequest\(\n            symbol = symbol,.*?        \)\n        val result = runCatching \{ exchange\.placeOrder\(request\) \}", re.S)
    m = req_re.search(text)
    if not m:
        fail("Lifecycle direct OrderRequest block not found")
    repl = r'''        val orderIntent = OrderIntent(
            pair = symbol,
            side = OrderSide.SELL,
            orderType = if (settings.enableMarketOrders) OrderType.MARKET else OrderType.LIMIT,
            requestedQuantity = qty,
            limitOrTriggerPrice = if (settings.enableMarketOrders) null else position.currentPrice,
            timeInForce = "GTC",
            reduceOnly = true,
            postOnly = false,
            protectiveStopPrice = null,
            purpose = reason,
            clientOrderId = OrderIntentIds.next("exit"),
            strategyId = if (turtleManaged) "CTS_TURTLE_SPOT_SAFE" else "CTS_LIFECYCLE",
            riskBudgetQuote = BigDecimal.ZERO
        )
        val request = orderIntent.toOrderRequest()
        val result = runCatching { UnifiedOrderIntentRouter.submit(settings, exchange, orderIntent).result }'''
    text = text[:m.start()] + repl + text[m.end():]

    # Do not record a trade until a fill is confirmed.
    on_success = '            .onSuccess { placed ->\n                val msg = "[$symbol] Automatic SELL submitted by lifecycle manager: reason=$reason, qty=$qty, type=${request.orderType}, orderId=${placed.exchangeOrderId}."\n                out += msg\n                log(msg, "LIVE")\n                recordTradeFromLifecycle(symbol, OrderSide.SELL, qty, position.currentPrice, placed.exchangeOrderId, reason)\n                if (sellPercent >= BigDecimal.ONE) dao.updatePositionStatus(symbol, "EXIT_SUBMITTED", System.currentTimeMillis())\n            }'
    replacement = r'''            .onSuccess { placed ->
                val msg = "[$symbol] Automatic SELL submitted by lifecycle manager: reason=$reason, requestedQty=$qty, filledQty=${placed.executedQuantity}, type=${request.orderType}, orderId=${placed.exchangeOrderId}."
                out += msg
                log(msg, "LIVE")
                if (placed.executedQuantity > BigDecimal.ZERO && placed.averagePrice > BigDecimal.ZERO) {
                    recordTradeFromLifecycle(symbol, OrderSide.SELL, placed.executedQuantity, placed.averagePrice, placed.exchangeOrderId, reason)
                    if (sellPercent >= BigDecimal.ONE) dao.updatePositionStatus(symbol, "EXIT_FILLED", System.currentTimeMillis())
                } else if (sellPercent >= BigDecimal.ONE) {
                    dao.updatePositionStatus(symbol, "EXIT_SUBMITTED", System.currentTimeMillis())
                }
            }'''
    if on_success in text:
        text = text.replace(on_success, replacement, 1)
    write(path, text)


def patch_protective(path: Path) -> None:
    text = read(path)
    if "UnifiedOrderIntentRouter.submit" in text:
        return
    # No extra import needed for same execution package except core OrderIntent already same package.
    # Standalone stop.
    pat = re.compile(r"        val standalone = runCatching \{\n            exchange\.placeOrder\(OrderRequest\(\n                symbol=symbol,\n                side=OrderSide\.SELL,\n                quantity=quantity,\n                limitPrice=stopPrice,\n                orderType=OrderType\.STOP_LOSS,\n                clientOrderId=.*?\n                reduceOnly=true,\n                purpose=.*?\n            \)\)\n        \}\.getOrNull\(\)", re.S)
    m = pat.search(text)
    if m:
        repl = r'''        val standalone = runCatching {
            val intent = OrderIntent(
                pair=symbol, side=OrderSide.SELL, orderType=OrderType.STOP_LOSS, requestedQuantity=quantity,
                limitOrTriggerPrice=stopPrice, timeInForce="GTC", reduceOnly=true, postOnly=false,
                protectiveStopPrice=null, purpose="PROTECTIVE_STOP strategy=$strategyId",
                clientOrderId=OrderIntentIds.next("stop"), strategyId=strategyId, riskBudgetQuote=BigDecimal.ZERO
            )
            UnifiedOrderIntentRouter.submit(settings, exchange, intent).result
        }.getOrNull()'''
        text = text[:m.start()] + repl + text[m.end():]
    else:
        fail("Protective standalone stop direct-order block not found")

    # Emergency market flatten.
    pat2 = re.compile(r"        val result=runCatching \{ exchange\.placeOrder\(OrderRequest\(\n            symbol=symbol,side=OrderSide\.SELL,quantity=quantity,orderType=OrderType\.MARKET,\n            clientOrderId=.*?\n            purpose=.*?\n        \)\) \}\.getOrNull\(\)", re.S)
    m2 = pat2.search(text)
    if m2:
        repl2 = r'''        val result=runCatching {
            val intent=OrderIntent(
                pair=symbol,side=OrderSide.SELL,orderType=OrderType.MARKET,requestedQuantity=quantity,
                limitOrTriggerPrice=null,timeInForce="GTC",reduceOnly=true,postOnly=false,protectiveStopPrice=null,
                purpose="EMERGENCY_FLATTEN_UNPROTECTED strategy=$strategyId cause=${cause.take(160)}",
                clientOrderId=OrderIntentIds.next("emrg"),strategyId=strategyId,riskBudgetQuote=BigDecimal.ZERO
            )
            UnifiedOrderIntentRouter.submit(settings,exchange,intent).result
        }.getOrNull()'''
        text = text[:m2.start()] + repl2 + text[m2.end():]
    else:
        fail("Protective emergency direct-order block not found")
    write(path, text)


def patch_external_context(path: Path) -> None:
    text = read(path)
    if "MarketReferenceNormalizer.normalize" in text:
        return
    func = re.compile(r"\n    fun crossMarket\(symbol:String, krakenPrice:Double\):ContextAssessment \{.*?\n    \}\n\n    fun labeledWallet", re.S)
    m = func.search(text)
    if not m:
        fail("ExternalContext crossMarket function shape changed")
    new = r'''
    fun crossMarket(symbol:String, krakenPrice:Double):ContextAssessment {
        if(krakenPrice<=0.0)return neutral("Binance public","INVALID","Cross-market reference skipped: invalid Kraken price.")
        val upper=symbol.uppercase().replace("/","").replace("-","")
        val quote=when{upper.endsWith("USDT")->"USDT";upper.endsWith("USD")->"USD";upper.endsWith("EUR")->"EUR";else->""}
        val base=upper.removeSuffix(quote).replace("XBT","BTC")
        val candidate="${base}USDT"
        return runCatching {
            val url="https://api.binance.com/api/v3/ticker/price".toHttpUrl().newBuilder().addQueryParameter("symbol",candidate).build()
            val body=client.newCall(Request.Builder().url(url).get().build()).execute().use{r->if(!r.isSuccessful)error("HTTP ${r.code}");r.body?.string().orEmpty()}
            val data=adapter.fromJson(body).orEmpty(); val ref=num(data["price"])
            if(ref<=0.0)return neutral("Binance public","NO_PRICE","Cross-market reference unavailable for $candidate.")
            val fx=when(quote){
                "USDT"->1.0
                "EUR","USD"->quoteConversionTargetPerUsdt(quote)
                else->null
            }
            val normalized=MarketReferenceNormalizer.normalize(ref,"USDT",quote,fx)
            val dev=MarketReferenceNormalizer.deviationPercent(krakenPrice,normalized)
                ?: return neutral("Binance public","FX_OR_SANITY_UNAVAILABLE",normalized.reason)
            val absDev=kotlin.math.abs(dev)
            when{ absDev>.85 -> ContextAssessment(true,-7,.90,"Binance public","DIVERGENCE","Currency-normalized cross-market divergence=${"%.2f".format(dev)}% vs $candidate; conversion=${normalized.conversionRate} $quote/USDT.")
                absDev<.25 -> ContextAssessment(true,2,1.0,"Binance public","CONFIRMED","Currency-normalized cross-market confirmation=${"%.2f".format(dev)}% vs $candidate; conversion=${normalized.conversionRate} $quote/USDT.")
                else -> neutral("Binance public","NEUTRAL","Currency-normalized deviation=${"%.2f".format(dev)}% vs $candidate; conversion=${normalized.conversionRate} $quote/USDT.") }
        }.getOrElse{ neutral("Binance public","ERROR","Cross-market reference unavailable: ${it.message}") }
    }

    private fun quoteConversionTargetPerUsdt(targetQuote:String):Double? = runCatching {
        val pair="USDT-${targetQuote.uppercase()}"
        val url="https://api.exchange.coinbase.com/products/$pair/ticker"
        val body=client.newCall(Request.Builder().url(url).get().build()).execute().use{r->if(!r.isSuccessful)error("HTTP ${r.code}");r.body?.string().orEmpty()}
        val data=adapter.fromJson(body).orEmpty(); num(data["price"]).takeIf{it>0.0}
    }.getOrNull()

    fun labeledWallet'''
    text = text[:m.start()] + new + text[m.end():]
    write(path, text)


def patch_professional_external(path: Path) -> None:
    text = read(path)
    if "INVALID_EXTERNAL_NUMBER" in text:
        return
    anchor = '            var score = if (totalMc > 0) 1 else 0; val reasons = mutableListOf<String>()\n'
    if anchor not in text:
        fail("DefiLlama totalMc score anchor changed")
    patch = anchor + r'''            if (totalMc > 0 && !ExternalNumericSanity.plausibleStablecoinLiquidityUsd(totalMc)) {
                return neutral("DefiLlama","INVALID_EXTERNAL_NUMBER","Rejected implausible stablecoin liquidity aggregate=$totalMc; no trading adjustment applied.")
            }
'''
    text = text.replace(anchor, patch, 1)
    write(path, text)



def patch_dashboard_info(main_path: Path, preview_path: Path) -> None:
    text = read(main_path)
    marker = "CTS_DASHBOARD_INFO_BUTTON_FIX_20260822"
    if marker not in text:
        import_anchor = "import androidx.compose.material3.Button\n"
        if "import androidx.compose.material3.AlertDialog\n" not in text:
            if import_anchor not in text:
                fail("Dashboard info fix: Material3 Button import anchor changed")
            text = text.replace(
                import_anchor,
                "import androidx.compose.material3.AlertDialog\n" + import_anchor,
                1
            )

        state_anchor = "    var settings by remember { mutableStateOf(store.load()) }\n"
        if state_anchor not in text:
            fail("Dashboard info fix: AdvancedBotApp settings-state anchor changed")
        text = text.replace(
            state_anchor,
            state_anchor +
            "    // CTS_DASHBOARD_INFO_BUTTON_FIX_20260822\n"
            "    var dashboardInfoVisible by remember { mutableStateOf(false) }\n",
            1
        )

        action_anchor = (
            "                    when (currentTab) {\n"
            "                        AppTab.PORTFOLIO -> {\n"
        )
        if action_anchor not in text:
            fail("Dashboard info fix: PreviewAppTopBar onAction switch anchor changed")
        text = text.replace(
            action_anchor,
            "                    when (currentTab) {\n"
            "                        AppTab.DASHBOARD -> dashboardInfoVisible = true\n"
            "                        AppTab.PORTFOLIO -> {\n",
            1
        )

        dialog_anchor = (
            "                }\n"
            "            )\n"
            "            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {\n"
        )
        if dialog_anchor not in text:
            fail("Dashboard info fix: top-bar/body anchor changed")

        dialog = r'''                }
            )

            if (dashboardInfoVisible) {
                val dashboardTotal = portfolioSnapshot?.totalValueEur ?: BigDecimal.ZERO
                val dashboardAvailable = portfolioSnapshot?.freeEur ?: BigDecimal.ZERO
                val dashboardInvested = dashboardTotal.subtract(dashboardAvailable).max(BigDecimal.ZERO)
                val dashboardPositions = lifecycleSnapshot?.positions.orEmpty()
                    .count { it.quantity > BigDecimal.ZERO }
                AlertDialog(
                    onDismissRequest = { dashboardInfoVisible = false },
                    title = { Text("Dashboard Information") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Mode: ${settings.mode} • Exchange: ${settings.exchangeProvider}")
                            Text(
                                "Portfolio Value: the current total account valuation reported by the selected provider. " +
                                    "Current snapshot: €${dashboardTotal.setScale(2, RoundingMode.HALF_UP)}."
                            )
                            Text(
                                "24H P/L: realized P/L recorded in the trade journal during the last rolling 24 hours. " +
                                    "It is not the same as all-time P/L or the change from the original PAPER starting balance."
                            )
                            Text(
                                "Invested: Portfolio Value minus currently available EUR. " +
                                    "Current: €${dashboardInvested.setScale(2, RoundingMode.HALF_UP)}."
                            )
                            Text(
                                "Available: free EUR reported by the portfolio snapshot. " +
                                    "Current: €${dashboardAvailable.setScale(2, RoundingMode.HALF_UP)}."
                            )
                            Text(
                                "24H Volume: the sum of trade notional recorded by CTS during the last rolling 24 hours; " +
                                    "it is not Kraken market-wide volume."
                            )
                            Text("Active Positions: $dashboardPositions open position(s) in the latest lifecycle snapshot.")
                            Text(
                                "Scan refreshes analysis without forcing a trade. Execute runs the configured execution path. " +
                                    "Start/Stop control the background bot, and News opens the news/intelligence area."
                            )
                        }
                    },
                    confirmButton = {
                        Button(onClick = { dashboardInfoVisible = false }) {
                            Text("Close")
                        }
                    }
                )
            }

            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
'''
        text = text.replace(dialog_anchor, dialog, 1)
        write(main_path, text)

    if preview_path.exists():
        preview = read(preview_path)
        old = 'Icon(actionIcon, contentDescription = "Action", tint = PreviewText, modifier = Modifier.size(21.dp))'
        new = '''Icon(
                        actionIcon,
                        contentDescription = when (currentTab) {
                            AppTab.DASHBOARD -> "Dashboard information"
                            AppTab.PORTFOLIO, AppTab.POSITIONS, AppTab.ORDERS -> "Refresh"
                            AppTab.SYSTEM_TEST -> "Run system test"
                            else -> "Action"
                        },
                        tint = PreviewText,
                        modifier = Modifier.size(21.dp)
                    )'''
        if old in preview:
            preview = preview.replace(old, new, 1)
            write(preview_path, preview)
        elif "Dashboard information" not in preview:
            fail("Dashboard info fix: PreviewReplicaUi action-icon anchor changed")


def patch_workflow(path: Path) -> None:
    text = read(path)
    # Fix any remaining late identity regression.
    text = text.replace('version_name = "4.0.6"', 'version_name = "4.0.7"')
    text = text.replace('version_code = 111', 'version_code = 112')
    text = text.replace("versionCode='111'", "versionCode='112'")
    text = text.replace("versionName='4.0.6'", "versionName='4.0.7'")
    text = text.replace("CryptoTradeStation-v4.0.6-", "CryptoTradeStation-v4.0.7-")
    text = text.replace("'versionName 4.0.6'", "'versionName 4.0.7'")
    text = text.replace("'versionCode 111'", "'versionCode 112'")
    text = text.replace("'V4ReleaseInfo 4.0.6'", "'V4ReleaseInfo 4.0.7'")
    text = text.replace('versionName = "4.0.6"', 'versionName = "4.0.7"')
    text = text.replace('VERSION_NAME = "4.0.6"', 'VERSION_NAME = "4.0.7"')
    text = text.replace('VERSION_CODE = 111', 'VERSION_CODE = 112')

    step = "Apply CTS full completion 2026-08-22"
    if step not in text:
        anchor = "      - name: Set update build identity\n"
        if anchor not in text:
            fail("Workflow Set update build identity anchor missing")
        block = r'''      - name: Apply CTS full completion 2026-08-22
        shell: bash
        run: |
          set -euo pipefail
          python3 -m py_compile .cts-v4-migration/apply_cts_full_completion_2026_08_22.py
          python3 .cts-v4-migration/apply_cts_full_completion_2026_08_22.py "$GITHUB_WORKSPACE" | tee full-completion-20260822.log

      - name: Validate CTS full-completion hard contracts
        shell: bash
        run: |
          set -euo pipefail
          python3 - <<'PYFULL'
          from pathlib import Path
          root=Path('app/src/main/java/com/ksp/cryptobot')
          required=[
              root/'strategy/provenance/StrategyProvenanceRegistry.kt',
              root/'strategy/turtle/TurtleSpotSafeStrategy.kt',
              root/'strategy/structure/KoroushCtsReferenceStrategy.kt',
              root/'market/PersistentCandleRepository.kt',
              root/'execution/ExecutionStateStore.kt',
              root/'portfolio/ReservationLedger.kt',
              root/'execution/UnifiedOrderIntentRouter.kt',
              root/'learning/TrainingDomain.kt',
              root/'governance/EntryExitSafetyPolicy.kt',
              root/'news/NewsAcquisitionCoordinator.kt',
              root/'maintenance/DatabaseMaintenance.kt',
              root/'research/MarketReferenceNormalizer.kt',
              root/'release/CompletionReleaseGate.kt',
              root/'risk/CostAwareSpotRiskSizer.kt',
              root/'backtest/HandoffBacktestEngine.kt',
          ]
          checks={f'file {p.name}':p.exists() for p in required}
          controller=(root/'core/BotController.kt').read_text(encoding='utf-8')
          learning=(root/'learning/TrueSelfLearningEngine.kt').read_text(encoding='utf-8')
          lifecycle=(root/'lifecycle/TradeLifecycleManager.kt').read_text(encoding='utf-8')
          protective=(root/'execution/ProtectiveStopManager.kt').read_text(encoding='utf-8')
          production=(root/'governance/ProductionIntelligenceEngine.kt').read_text(encoding='utf-8')
          external=(root/'research/ExternalContextEngines.kt').read_text(encoding='utf-8')
          prof=(root/'research/ProfessionalExternalIntelligenceEngine.kt').read_text(encoding='utf-8')
          models=(root/'core/Models.kt').read_text(encoding='utf-8')
          main=(root.parent/'MainActivity.kt').read_text(encoding='utf-8')
          preview=(root.parent/'PreviewReplicaUi.kt').read_text(encoding='utf-8')
          strategy=(root/'strategy/MultiStrategyEngine.kt').read_text(encoding='utf-8')
          checks.update({
              'Turtle enum': 'CTS_TURTLE_SPOT_SAFE' in models,
              'KAK reference enum': 'CTS_KAK_CLOSE_BREAK_RETEST_V1' in models,
              'persistent candle integration': 'candleRepository.mergeHistory' in controller,
              'persistent CTS reservations': 'executionStateStore.activeReserved' in controller,
              'main order route unified': 'UnifiedOrderIntentRouter.submit' in controller,
              'lifecycle route unified': 'UnifiedOrderIntentRouter.submit' in lifecycle and 'exchange.placeOrder(request)' not in lifecycle,
              'protective route unified': 'UnifiedOrderIntentRouter.submit' in protective and 'exchange.placeOrder(OrderRequest' not in protective,
              'learning domain isolation': 'TrainingDomainPolicy.filterTrades' in learning and 'crossDomainSamplesUsed=0' in learning,
              'protective SELL policy': 'EntryExitSafetyPolicy.evaluate' in production,
              'Turtle opt-in only': 'settings.strategyMode == StrategyMode.CTS_TURTLE_SPOT_SAFE' in strategy,
              'Turtle cost-aware 0.5% risk': 'CostAwareSpotRiskSizer.size' in controller and 'riskFraction = BigDecimal(\"0.005\")' in controller,
              'handoff backtests': 'HandoffBacktestEngine.run' in (root/'backtest/BacktestEngine.kt').read_text(encoding='utf-8'),
              'currency-normalized cross-market': 'MarketReferenceNormalizer.normalize' in external,
              'external numeric sanity': 'INVALID_EXTERNAL_NUMBER' in prof,
              'central news coordinator': 'newsAcquisitionCoordinator.fetch' in controller,
              'provenance sidecar': 'signalProvenanceStore.record' in controller,
              'release identity 4.0.7': 'versionName = "4.0.7"' in Path('app/build.gradle.kts').read_text(encoding='utf-8'),
              'dashboard info action wired': 'AppTab.DASHBOARD -> dashboardInfoVisible = true' in main and 'Dashboard Information' in main and 'Dashboard information' in preview,
          })
          # There may be exchange implementation placeOrder methods; only application orchestration paths are forbidden.
          direct=[]
          for p in root.rglob('*.kt'):
              if '/exchange/' in p.as_posix() or p.name in {'UnifiedOrderIntentRouter.kt'}: continue
              s=p.read_text(encoding='utf-8')
              if 'exchange.placeOrder(' in s:
                  direct.append(str(p))
          checks['no direct orchestration placeOrder calls']=not direct
          for name,ok in checks.items(): print(('PASS' if ok else 'FAIL')+' | '+name)
          if direct: print('Direct order callers:', *direct, sep='\n  ')
          bad=[n for n,o in checks.items() if not o]
          if bad: raise SystemExit('Full-completion release contract failed: '+', '.join(bad))
          PYFULL

'''
        text = text.replace(anchor, block + anchor, 1)

    # Failure logs.
    if "full-completion-20260822.log" not in text.split("Collect failure diagnostics")[-1]:
        text = text.replace("readiness-balance-orderintent.log ", "readiness-balance-orderintent.log full-completion-20260822.log ")
    write(path, text)


def static_validate(repo: Path) -> None:
    root=repo/"app/src/main/java/com/ksp/cryptobot"
    checks={
        "models Turtle": "CTS_TURTLE_SPOT_SAFE" in read(root/"core/Models.kt"),
        "models KAK": "CTS_KAK_CLOSE_BREAK_RETEST_V1" in read(root/"core/Models.kt"),
        "controller candle store": "candleRepository.mergeHistory" in read(root/"core/BotController.kt"),
        "controller ledger": "executionStateStore.activeReserved" in read(root/"core/BotController.kt"),
        "controller router": "UnifiedOrderIntentRouter.submit" in read(root/"core/BotController.kt"),
        "Turtle cost risk": "CostAwareSpotRiskSizer.size" in read(root/"core/BotController.kt"),
        "learning domain": "TrainingDomainPolicy.filterTrades" in read(root/"learning/TrueSelfLearningEngine.kt"),
        "lifecycle router": "UnifiedOrderIntentRouter.submit" in read(root/"lifecycle/TradeLifecycleManager.kt"),
        "protective router": "UnifiedOrderIntentRouter.submit" in read(root/"execution/ProtectiveStopManager.kt"),
        "production exit safety": "EntryExitSafetyPolicy.evaluate" in read(root/"governance/ProductionIntelligenceEngine.kt"),
        "normalized reference": "MarketReferenceNormalizer.normalize" in read(root/"research/ExternalContextEngines.kt"),
        "external sanity": "INVALID_EXTERNAL_NUMBER" in read(root/"research/ProfessionalExternalIntelligenceEngine.kt"),
        "dashboard info button": "AppTab.DASHBOARD -> dashboardInfoVisible = true" in read(root.parent/"MainActivity.kt") and "Dashboard Information" in read(root.parent/"MainActivity.kt"),
    }
    for name,ok in checks.items(): print(("PASS" if ok else "FAIL")+" | "+name)
    bad=[n for n,o in checks.items() if not o]
    if bad: fail("Static validation failed: "+", ".join(bad))


def main() -> None:
    if len(sys.argv) not in (2,3):
        fail("usage: apply_cts_full_completion_2026_08_22.py [--workflow-only] <repo-root>")
    workflow_only = len(sys.argv)==3 and sys.argv[1]=="--workflow-only"
    repo=Path(sys.argv[-1]).resolve()
    bundle_root=Path(__file__).resolve().parent
    if workflow_only:
        patch_workflow(repo/".github/workflows/android-v4-build.yml")
        print("[CTS full completion] Canonical workflow hook installed.")
        return

    copy_payload(repo,bundle_root)
    root=repo/"app/src/main/java/com/ksp/cryptobot"
    patch_models(root/"core/Models.kt")
    patch_multi_strategy(root/"strategy/MultiStrategyEngine.kt")
    patch_automation_sell_safety(root/"automation/AdvancedAutomationEngine.kt")
    patch_controller(root/"core/BotController.kt")
    patch_learning(root/"learning/TrueSelfLearningEngine.kt")
    patch_backtest(root/"backtest/BacktestEngine.kt")
    patch_production(root/"governance/ProductionIntelligenceEngine.kt")
    patch_lifecycle(root/"lifecycle/TradeLifecycleManager.kt")
    patch_protective(root/"execution/ProtectiveStopManager.kt")
    patch_external_context(root/"research/ExternalContextEngines.kt")
    patch_professional_external(root/"research/ProfessionalExternalIntelligenceEngine.kt")
    patch_dashboard_info(
        root.parent/"MainActivity.kt",
        root.parent/"PreviewReplicaUi.kt"
    )
    static_validate(repo)
    print("[CTS full completion] PASS")
    print("  - source provenance registry + Turtle/KAK reference separation")
    print("  - CTS_TURTLE_SPOT_SAFE implemented, PAPER-only until promotion")
    print("  - persistent committed candle history")
    print("  - persistent ReservationLedger + intent/fill idempotency")
    print("  - main/lifecycle/protective routes unified behind OrderIntent")
    print("  - PAPER/LIVE domain-isolated self-learning")
    print("  - HIGH/CRITICAL entry blocks preserve protective SELL")
    print("  - centralized news budgets/cache")
    print("  - bounded telemetry retention/WAL maintenance")
    print("  - currency-normalized external references + numeric sanity")
    print("  - hard release contracts and tests")


if __name__ == "__main__":
    main()
