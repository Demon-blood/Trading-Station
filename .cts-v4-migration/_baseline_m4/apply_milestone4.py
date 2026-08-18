#!/usr/bin/env python3
"""Apply cumulative Crypto TradeStation Android v4 Milestone 4 to v3.2.5, M1, M2, or M3."""
from __future__ import annotations
import re, shutil, subprocess, sys
from pathlib import Path
HERE = Path(__file__).resolve().parent

def fail(msg: str) -> None: raise SystemExit(msg)

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1: fail(f"Cannot patch {label}: expected exactly one match, found {count}.")
    return text.replace(old, new, 1)

def run_m3(repo: Path) -> None:
    script = HERE / "_apply_m3_baseline_for_m4.py"
    result = subprocess.run([sys.executable, str(script), str(repo)], text=True, capture_output=True)
    if result.returncode != 0:
        fail("Cumulative M3 baseline failed before M4 patching:\n" + result.stdout + result.stderr)
    print(result.stdout.strip())

def backup(paths: list[Path], repo: Path) -> Path:
    root = repo / ".v4_m4_backup"; root.mkdir(exist_ok=True)
    for p in paths:
        if p.exists():
            t = root / p.relative_to(repo); t.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(p, t)
    return root

def patch_controller(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "AdvancedExecutionCoordinator" not in text:
        text = replace_once(text, "import com.ksp.cryptobot.governance.ProductionIntelligenceEngine\n",
                            "import com.ksp.cryptobot.governance.ProductionIntelligenceEngine\nimport com.ksp.cryptobot.execution.AdvancedExecutionCoordinator\nimport com.ksp.cryptobot.research.ResearchExecutionRuntime\nimport com.ksp.cryptobot.research.HandoffPositionPlan\nimport com.ksp.cryptobot.research.HandoffPositionPlanCodec\n",
                            "controller M4 import")
        text = replace_once(text, "    private val productionIntelligence = ProductionIntelligenceEngine(AppDatabase.get(appContext).governanceDao())\n",
                            "    private val productionIntelligence = ProductionIntelligenceEngine(AppDatabase.get(appContext).governanceDao())\n    private val advancedExecution = AdvancedExecutionCoordinator(dao, AppDatabase.get(appContext).governanceDao())\n",
                            "controller M4 field")
    if "import com.ksp.cryptobot.research.ResearchExecutionRuntime" not in text:
        text = text.replace("import com.ksp.cryptobot.execution.AdvancedExecutionCoordinator\n", "import com.ksp.cryptobot.execution.AdvancedExecutionCoordinator\nimport com.ksp.cryptobot.research.ResearchExecutionRuntime\nimport com.ksp.cryptobot.research.HandoffPositionPlan\nimport com.ksp.cryptobot.research.HandoffPositionPlanCodec\n", 1)
    if "TradeLifecycleManager(dao, statusStore, AppDatabase.get(appContext).governanceDao())" not in text:
        text = replace_once(text, "    private val lifecycleManager = TradeLifecycleManager(dao, statusStore)\n",
                            "    private val lifecycleManager = TradeLifecycleManager(dao, statusStore, AppDatabase.get(appContext).governanceDao())\n",
                            "controller lifecycle governance")
    if "Advanced reconciliation:" not in text:
        old='''        if (liveAutoExecution) {\n            manageExistingLiveOrders(settings, exchange)\n            lifecycleManager.runPreScanMaintenance(settings, exchange)\n        }\n'''
        new='''        if (liveAutoExecution) {\n            manageExistingLiveOrders(settings, exchange)\n            lifecycleManager.runPreScanMaintenance(settings, exchange)\n            val reconciliation = advancedExecution.reconcileLive(settings, exchange)\n            reconciliation.messages.take(8).forEach { updateStatus("Advanced reconciliation: $it", if (reconciliation.removed > 0) "WARN" else "INFO") }\n        }\n'''
        text = replace_once(text, old, new, "controller reconciliation hook")
    if "var price = if (side == OrderSide.BUY)" not in text:
        text = replace_once(text, "        val price = if (side == OrderSide.BUY) ticker.ask else ticker.bid\n",
                            "        var price = if (side == OrderSide.BUY) ticker.ask else ticker.bid\n",
                            "controller mutable execution price")
    if "var targetNotional = if (side == OrderSide.BUY" not in text:
        text = replace_once(text, "        val targetNotional = if (side == OrderSide.BUY && settings.mode != BotMode.PAPER) {\n",
                            "        var targetNotional = if (side == OrderSide.BUY && settings.mode != BotMode.PAPER) {\n",
                            "controller mutable target notional")
    if "Advanced execution plan:" not in text:
        marker='''\n        if (side == OrderSide.BUY) {\n            val orderBookCheck = orderBookDepthAllowsExecution(settings, exchange, ticker.symbol, side, targetNotional, price)\n'''
        block='''\n        var plannedEntryOrderType: OrderType? = null\n        var plannedEntryPostOnly = false\n        if (side == OrderSide.BUY) {\n            val advancedOrderBook = runCatching { exchange.getOrderBook(ticker.symbol, 40) }.getOrNull()\n            val advancedPlan = advancedExecution.prepareEntry(\n                settings = settings, ticker = ticker, decision = decision, requestedQuote = targetNotional,\n                orderBook = advancedOrderBook, mode = if (settings.mode == BotMode.PAPER) "PAPER" else "LIVE", currentUseMarket = useMarketOrder\n            )\n            updateStatus("[${ticker.symbol}] Advanced execution plan: allowed=${advancedPlan.allowed}, final=${advancedPlan.finalQuote.setScale(2, RoundingMode.DOWN)}, order=${advancedPlan.orderType}, protection=${advancedPlan.protectionLevel}, size×${advancedPlan.combinedMultiplier}. ${advancedPlan.reason.take(260)}", if (advancedPlan.allowed) "INFO" else "WARN")\n            if (!advancedPlan.allowed) {\n                productionIntelligence.recordWhyNotTrade(decision, settings, advancedPlan.reason)\n                return ExecutionAttemptResult(false)\n            }\n            targetNotional = advancedPlan.finalQuote\n            plannedEntryOrderType = advancedPlan.orderType\n            plannedEntryPostOnly = advancedPlan.postOnly\n            if (advancedPlan.orderType != OrderType.MARKET) useMarketOrder = false\n            if (!useMarketOrder && advancedPlan.limitPrice != null && advancedPlan.limitPrice > BigDecimal.ZERO) price = advancedPlan.limitPrice\n            val orderBookCheck = orderBookDepthAllowsExecution(settings, exchange, ticker.symbol, side, targetNotional, price)\n'''
        text = replace_once(text, marker, block, "controller advanced entry plan")
    if "plannedEntryOrderType ?: if (useMarketOrder)" not in text:
        text = replace_once(text,
'''            orderType = if (useMarketOrder) OrderType.MARKET else OrderType.LIMIT,\n            clientOrderId = "ksp-${ticker.symbol.lowercase()}-${System.currentTimeMillis()}"\n''',
'''            orderType = plannedEntryOrderType ?: if (useMarketOrder) OrderType.MARKET else OrderType.LIMIT,\n            clientOrderId = "ksp-${ticker.symbol.lowercase()}-${System.currentTimeMillis()}",\n            purpose = if (side == OrderSide.BUY && plannedEntryOrderType != null) "RESEARCH/HANDOFF strategy=${ResearchExecutionRuntime.snapshot(ticker.symbol)?.strategyId ?: "GENERIC"} order=${plannedEntryOrderType}" else "ENTRY",
            postOnly = plannedEntryPostOnly\n''', "controller preserve advanced entry order type")
        text = text.replace('val orderModeLabel = if (useMarketOrder) "MARKET" else "LIMIT"', 'val orderModeLabel = request.orderType.name', 1)
    # Idempotent hardening for migrated sources produced before post-only semantics existed.
    if "postOnly = plannedEntryPostOnly" not in text and "plannedEntryPostOnly" in text:
        request_anchor = '            purpose = if (side == OrderSide.BUY && plannedEntryOrderType != null) "RESEARCH/HANDOFF strategy=${ResearchExecutionRuntime.snapshot(ticker.symbol)?.strategyId ?: \"GENERIC\"} order=${plannedEntryOrderType}" else "ENTRY"\n'
        if request_anchor in text:
            text = text.replace(request_anchor, request_anchor.rstrip('\n') + ',\n            postOnly = plannedEntryPostOnly\n', 1)
    if "val fillConfirmed = result.executedQuantity" not in text:
        old='''        val executedQtyForRecord = result.executedQuantity.takeIf { it > BigDecimal.ZERO } ?: quantity
        val averagePriceForRecord = result.averagePrice.takeIf { it > BigDecimal.ZERO } ?: price
        val feeForRecord = result.fee.takeIf { it > BigDecimal.ZERO }
            ?: averagePriceForRecord.multiply(executedQtyForRecord).multiply(BigDecimal("0.001")).setScale(8, RoundingMode.HALF_UP)
        val notionalForRecord = averagePriceForRecord.multiply(executedQtyForRecord).setScale(8, RoundingMode.HALF_UP)
        dao.insertTrade(
            TradeEntity(
                symbol = result.symbol,
                side = result.side.name,
                quantity = executedQtyForRecord.toPlainString(),
                priceEur = averagePriceForRecord.toPlainString(),
                feeEur = feeForRecord.toPlainString(),
                paper = result.paper,
                aiScore = decision.finalScore,
                aiReason = decision.explanation,
                clientOrderId = request.clientOrderId,
                exchangeOrderId = result.exchangeOrderId,
                timestampEpochMs = result.timestamp.toEpochMilli()
            )
        )
        updateStatus("Order placed: ${result.side} ${result.symbol} ${if (result.paper) "PAPER" else "LIVE"}. qty=${executedQtyForRecord.stripTrailingZeros().toPlainString()} avg=${averagePriceForRecord.stripTrailingZeros().toPlainString()} fee=${feeForRecord.stripTrailingZeros().toPlainString()} orderId=${result.exchangeOrderId}", if (result.paper) "INFO" else "LIVE")
'''
        new='''        val fillConfirmed = result.executedQuantity > BigDecimal.ZERO && result.averagePrice > BigDecimal.ZERO
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
'''
        # M3 inserts productionIntelligence.observeExecution(...) between the trade row and
        # the legacy "Order placed" status line. Accept either the pre-M3 or post-M3 form
        # instead of requiring one obsolete exact text block.
        if text.count(old) == 1:
            text = text.replace(old, new, 1)
        else:
            m3_observe = '''        productionIntelligence.observeExecution(
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
'''
            legacy_status = '''        updateStatus("Order placed: ${result.side} ${result.symbol} ${if (result.paper) "PAPER" else "LIVE"}. qty=${executedQtyForRecord.stripTrailingZeros().toPlainString()} avg=${averagePriceForRecord.stripTrailingZeros().toPlainString()} fee=${feeForRecord.stripTrailingZeros().toPlainString()} orderId=${result.exchangeOrderId}", if (result.paper) "INFO" else "LIVE")
'''
            # Build the exact post-M3 variant from the pre-M3 block.
            old_m3 = old.replace(legacy_status, m3_observe + legacy_status, 1)
            if text.count(old_m3) == 1:
                text = text.replace(old_m3, new, 1)
            else:
                fail(f"Cannot patch controller exchange fill truth: expected pre-M3 or post-M3 block; preM3={text.count(old)}, postM3={text.count(old_m3)}.")
        text = text.replace('appendLine("amount=${executedQtyForRecord.stripTrailingZeros().toPlainString()}")', 'appendLine("fillConfirmed=$fillConfirmed")\\n                appendLine("amount=${if (fillConfirmed) executedQtyForRecord.stripTrailingZeros().toPlainString() else quantity.stripTrailingZeros().toPlainString()}")', 1)
        text = text.replace('appendLine("notional≈${notionalForRecord.stripTrailingZeros().toPlainString()} $quoteAsset")', 'appendLine("notional≈${if (fillConfirmed) notionalForRecord.stripTrailingZeros().toPlainString() else submittedNotionalEstimate.stripTrailingZeros().toPlainString()} $quoteAsset")', 1)
        text = text.replace('appendLine("fee=${feeForRecord.stripTrailingZeros().toPlainString()} $quoteAsset")', 'appendLine("fee=${if (fillConfirmed) feeForRecord.stripTrailingZeros().toPlainString() else "pending"} $quoteAsset")', 1)
        text = text.replace('if (result.executedQuantity <= BigDecimal.ZERO) appendLine("note=Kraken did not report a fill yet; showing submitted quantity/price estimate.")', 'if (!fillConfirmed) appendLine("note=Order accepted without confirmed fill; actual fill will be synchronized from exchange history.")', 1)
    # Idempotent accounting hardening for already-migrated controllers.
    if "realizedPnlEur = realizedPnlForRecord.toPlainString()" not in text and "val fillConfirmed = result.executedQuantity" in text:
        if "val realizedPnlForRecord =" not in text:
            text = text.replace(
                '        val notionalForRecord = averagePriceForRecord.multiply(executedQtyForRecord).setScale(8, RoundingMode.HALF_UP)\n',
                '        val notionalForRecord = averagePriceForRecord.multiply(executedQtyForRecord).setScale(8, RoundingMode.HALF_UP)\n        val realizedPnlForRecord = if (fillConfirmed && result.side == OrderSide.SELL) {\n            if (result.realizedPnlQuote != BigDecimal.ZERO) result.realizedPnlQuote else {\n                val tracked = dao.positionForSymbol(result.symbol)\n                val entry = tracked?.entryPriceEur?.toBigDecimalOrNull()\n                    ?: dao.recentTradesSnapshot(200).firstOrNull { it.symbol.equals(result.symbol, true) && it.side == OrderSide.BUY.name }?.priceEur?.toBigDecimalOrNull()\n                    ?: BigDecimal.ZERO\n                if (entry > BigDecimal.ZERO) averagePriceForRecord.subtract(entry).multiply(executedQtyForRecord).subtract(feeForRecord) else BigDecimal.ZERO\n            }\n        } else result.realizedPnlQuote\n', 1)
        text = text.replace('                    paper = result.paper,\n                    aiScore = decision.finalScore,', '                    paper = result.paper,\n                    realizedPnlEur = realizedPnlForRecord.toPlainString(),\n                    aiScore = decision.finalScore,', 1)
    # Paper lifecycle pre-scan truth: process the same persisted stop/target state using the fake wallet.
    if "Paper lifecycle pre-scan truth" not in text:
        live_block = '''        if (liveAutoExecution) {
            manageExistingLiveOrders(settings, exchange)
            lifecycleManager.runPreScanMaintenance(settings, exchange)
            val reconciliation = advancedExecution.reconcileLive(settings, exchange)
            reconciliation.messages.take(8).forEach { updateStatus("Advanced reconciliation: $it", if (reconciliation.removed > 0) "WARN" else "INFO") }
        }
'''
        if live_block in text:
            text = text.replace(live_block, live_block + '''        if (settings.mode == BotMode.PAPER && settings.liveLifecycleManagerEnabled) {
            lifecycleManager.runPreScanMaintenance(settings, exchange)
            updateStatus("Paper lifecycle pre-scan truth: pending fills and persisted source plans refreshed against the fake wallet.", "INFO")
        }
''', 1)
    text = text.replace(
        '        if (liveAutoExecution && settings.liveLifecycleManagerEnabled) {\n            val lifecycle = lifecycleManager.runPostDecisionManagement(settings, exchange, decisions)\n',
        '        if ((liveAutoExecution || settings.mode == BotMode.PAPER) && settings.liveLifecycleManagerEnabled) {\n            val lifecycle = lifecycleManager.runPostDecisionManagement(settings, exchange, decisions)\n', 1)
    # Exchange-level source stop protection. A sourced LIVE entry is not considered protected
    # merely because the app persisted a stop price; Kraken must acknowledge/retain a stop order.
    if "import com.ksp.cryptobot.execution.ProtectiveStopManager" not in text:
        text = text.replace("import com.ksp.cryptobot.execution.AdvancedExecutionCoordinator\n", "import com.ksp.cryptobot.execution.AdvancedExecutionCoordinator\nimport com.ksp.cryptobot.execution.ProtectiveStopManager\n", 1)
    if "private val protectiveStops = ProtectiveStopManager" not in text:
        field_anchor = "    private val advancedExecution = AdvancedExecutionCoordinator(dao, AppDatabase.get(appContext).governanceDao())\n"
        if field_anchor in text:
            text = text.replace(field_anchor, field_anchor + "    private val protectiveStops = ProtectiveStopManager(dao, AppDatabase.get(appContext).governanceDao())\n", 1)
    if "protectiveStopPrice = if (side == OrderSide.BUY" not in text and "postOnly = plannedEntryPostOnly" in text:
        text = text.replace(
            "            postOnly = plannedEntryPostOnly\n",
            "            postOnly = plannedEntryPostOnly,\n            protectiveStopPrice = if (side == OrderSide.BUY && settings.mode != BotMode.PAPER) ResearchExecutionRuntime.snapshot(ticker.symbol)?.stopPrice?.takeIf { it > BigDecimal.ZERO && it < price } else null\n",
            1
        )
    if "Exchange protective stop state:" not in text:
        protect_anchor = '''                    updateStatus("[${result.symbol}] Persisted handoff plan: strategy=${handoff.strategyId}, stop=${sourceStop.stripTrailingZeros().toPlainString()}, targets=${sourceTargets.joinToString(",") { it.stripTrailingZeros().toPlainString() }}.", "LIVE")
'''
        protect_block = protect_anchor + '''                    val protection = protectiveStops.protectOrFlatten(
                        settings = settings, exchange = exchange, symbol = result.symbol,
                        quantity = executedQtyForRecord, entryPrice = averagePriceForRecord,
                        stopPrice = sourceStop, strategyId = handoff.strategyId, paper = result.paper
                    )
                    updateStatus("[${result.symbol}] Exchange protective stop state: protected=${protection.protected}, flattened=${protection.flattened}, pendingEmergency=${protection.pendingEmergencyExit}. ${protection.reason}", if (protection.protected) "LIVE" else "ERROR")
'''
        if protect_anchor in text:
            text = text.replace(protect_anchor, protect_block, 1)
    path.write_text(text, encoding="utf-8")

def patch_lifecycle(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "import com.ksp.cryptobot.research.HandoffPositionPlanCodec" not in text and "import com.ksp.cryptobot.execution.AdvancedExitOptimizer" in text:
        text = text.replace("import com.ksp.cryptobot.execution.AdvancedExitOptimizer\n", "import com.ksp.cryptobot.execution.AdvancedExitOptimizer\nimport com.ksp.cryptobot.research.HandoffSideIntent\nimport com.ksp.cryptobot.research.ResearchExecutionRuntime\nimport com.ksp.cryptobot.research.HandoffPositionPlanCodec\nimport com.ksp.cryptobot.data.ProductionIntelligenceStateEntity\n", 1)
    if "AdvancedExitOptimizer" not in text:
        text = replace_once(text, "import com.ksp.cryptobot.data.TradeEntity\n",
                            "import com.ksp.cryptobot.data.TradeEntity\nimport com.ksp.cryptobot.data.GovernanceDao\nimport com.ksp.cryptobot.data.GovernanceEventEntity\nimport com.ksp.cryptobot.data.ProductionIntelligenceStateEntity\nimport com.ksp.cryptobot.execution.AdvancedExitOptimizer\nimport com.ksp.cryptobot.research.HandoffSideIntent\nimport com.ksp.cryptobot.research.ResearchExecutionRuntime\nimport com.ksp.cryptobot.research.HandoffPositionPlanCodec\n",
                            "lifecycle M4 imports")
        text = replace_once(text, "    private val statusStore: BotStatusStore\n) {\n",
                            "    private val statusStore: BotStatusStore,\n    private val governanceDao: GovernanceDao? = null\n) {\n",
                            "lifecycle governance constructor")
        text = replace_once(text, "    private val spikeProfitTimingEngine = SpikeProfitTimingEngine()\n",
                            "    private val spikeProfitTimingEngine = SpikeProfitTimingEngine()\n    private val advancedExitOptimizer = AdvancedExitOptimizer(governanceDao)\n",
                            "lifecycle exit optimizer field")
    if "val handoffProtective = handoffIntent" not in text:
        text = replace_once(text,
'''        val riskOffSell = settings.forceSellOnBearishSignal && bearish && decision?.allowedToTrade == true\n        val hitStop = settings.autoStopLossEnabled && position.currentPrice <= position.stopPrice && position.stopPrice > BigDecimal.ZERO\n''',
'''        val riskOffSell = settings.forceSellOnBearishSignal && bearish && decision?.allowedToTrade == true\n        val handoffDirective = ResearchExecutionRuntime.snapshot(symbol)\n        val handoffIntent = handoffDirective?.sideIntent\n        val handoffProtective = handoffIntent == HandoffSideIntent.EXIT || handoffIntent == HandoffSideIntent.REDUCE\n        val hitStop = settings.autoStopLossEnabled && position.currentPrice <= position.stopPrice && position.stopPrice > BigDecimal.ZERO\n''', "lifecycle handoff protective detection")
        text = replace_once(text,
'''        val reason = when {\n            hitTrailing && !spikeTiming.shouldHold -> "trailing-stop profit capture"\n            hitTakeProfit && !spikeTiming.shouldHold -> "take-profit target reached"\n            hitStop -> "stop-loss protection"\n            riskOffSell -> "AI bearish/risk-off sell signal"\n''',
'''        val reason = when {\n            hitStop -> "stop-loss protection"\n            handoffIntent == HandoffSideIntent.EXIT -> "handoff-source protective full exit"\n            handoffIntent == HandoffSideIntent.REDUCE -> "handoff-source protective reduction"\n            hitTrailing && !spikeTiming.shouldHold -> "trailing-stop profit capture"\n            hitTakeProfit && !spikeTiming.shouldHold -> "take-profit target reached"\n            riskOffSell -> "AI bearish/risk-off sell signal"\n''', "lifecycle handoff protective reason")
        text = replace_once(text,
'''        val learnedHold = selfLearningEngine.evaluateLearnedHoldExit(dao, settings, position, reason, decision)\n        if (learnedHold.shouldHold) {\n            out += "[$symbol] Learned HOLD instead of sell: ${learnedHold.explanation}"\n            log(out.last(), "LEARN")\n            return out\n        } else if (settings.learnedHoldForProfitEnabled && learnedHold.explanation.isNotBlank()) {\n            out += "[$symbol] Learned hold check: ${learnedHold.explanation}"\n        }\n''',
'''        if (!handoffProtective) {\n            val learnedHold = selfLearningEngine.evaluateLearnedHoldExit(dao, settings, position, reason, decision)\n            if (learnedHold.shouldHold) {\n                out += "[$symbol] Learned HOLD instead of sell: ${learnedHold.explanation}"\n                log(out.last(), "LEARN")\n                return out\n            } else if (settings.learnedHoldForProfitEnabled && learnedHold.explanation.isNotBlank()) {\n                out += "[$symbol] Learned hold check: ${learnedHold.explanation}"\n            }\n        } else {\n            out += "[$symbol] Learned-hold deferral bypassed for handoff protective ${handoffIntent}; source protection cannot be converted into a hold."\n        }\n''', "lifecycle prevent learned hold overriding handoff protection")
    if "val exitPlan = advancedExitOptimizer.optimize" not in text:
        old='''        val sellPercent = if (hitTakeProfit && settings.enablePartialTakeProfit && position.unrealizedPnlPercent > BigDecimal.ZERO) {\n            settings.partialExitPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP).coerceIn(BigDecimal("0.05"), BigDecimal.ONE)\n        } else BigDecimal.ONE\n        val qty = position.freeQuantity.multiply(sellPercent).setScale(8, RoundingMode.DOWN)\n'''
        new='''        val exitPlan = advancedExitOptimizer.optimize(settings, position, decision, reason)\n        out += "[$symbol] ${exitPlan.reason}"\n        if (!exitPlan.shouldExit || exitPlan.sellFraction <= BigDecimal.ZERO) {\n            out += "[$symbol] Advanced exit optimizer deferred this soft exit."\n            log(out.last(), "LEARN")\n            return out\n        }\n        val sellPercent = exitPlan.sellFraction\n        val qty = position.freeQuantity.multiply(sellPercent).setScale(8, RoundingMode.DOWN)\n'''
        text = replace_once(text, old, new, "lifecycle advanced exit sizing")
        text = replace_once(text,
'''            limitPrice = if (settings.enableMarketOrders) null else position.currentPrice,\n            orderType = if (settings.enableMarketOrders) OrderType.MARKET else OrderType.LIMIT,\n            clientOrderId = "ksp-exit-${symbol.lowercase()}-${System.currentTimeMillis()}",\n            reduceOnly = true,\n            purpose = reason\n''',
'''            limitPrice = if (exitPlan.orderType == OrderType.MARKET) null else position.currentPrice,\n            orderType = exitPlan.orderType,\n            clientOrderId = "ksp-exit-${symbol.lowercase()}-${System.currentTimeMillis()}",\n            reduceOnly = true,\n            purpose = "$reason; ${exitPlan.method}"\n''', "lifecycle advanced exit order")
        text = text.replace('reason=$reason, qty=$qty, type=${request.orderType}', 'reason=$reason/${exitPlan.method}, qty=$qty, type=${request.orderType}', 1)
        text = text.replace('recordTradeFromLifecycle(symbol, OrderSide.SELL, qty, position.currentPrice, placed.exchangeOrderId, reason)', 'recordTradeFromLifecycle(symbol, OrderSide.SELL, qty, position.currentPrice, placed.exchangeOrderId, "$reason; ${exitPlan.method}")', 1)
    if "handoff_protective_exit_failure" not in text:
        text = replace_once(text,
'''            .onFailure { error ->\n                val msg = "[$symbol] Automatic SELL failed ($reason): ${error.message}"\n                out += msg\n                log(msg, "ERROR")\n            }\n''',
'''            .onFailure { error ->\n                val msg = "[$symbol] Automatic SELL failed ($reason): ${error.message}"\n                out += msg\n                log(msg, "ERROR")\n                if (handoffProtective) {\n                    governanceDao?.insertEvent(GovernanceEventEntity(\n                        eventType = "handoff_protective_exit_failure", symbol = symbol, strategy = handoffDirective?.strategyId ?: settings.strategyMode.name,\n                        mode = settings.mode.name, severity = "CRITICAL", blocked = true, sizeMultiplier = 0.0,\n                        reason = "Protective handoff ${handoffIntent} failed to execute: ${error.message}. New entries must enter safe mode until the operational error is cleared."\n                    ))\n                }\n            }\n''', "lifecycle protective exit failure escalation")
    if "Pending handoff entry reconciled from Kraken fill" not in text:
        text = replace_once(text,
'        closed.take(50).forEach { order ->\n            if (order.executedQuantity <= BigDecimal.ZERO) return@forEach\n',
'        closed.take(50).forEach { order ->\n            val pendingPosition = dao.positionForSymbol(order.symbol)\n            val pendingPlan = HandoffPositionPlanCodec.decode(pendingPosition?.source)\n            if (pendingPosition != null && pendingPlan != null && pendingPlan.entryOrderId == order.exchangeOrderId) {\n                if (order.side == OrderSide.BUY && order.executedQuantity > BigDecimal.ZERO && order.price > BigDecimal.ZERO) {\n                    val stop = pendingPlan.stopPrice?.takeIf { it > BigDecimal.ZERO && it < order.price }\n                        ?: pendingPosition.stopPriceEur.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO && it < order.price }\n                        ?: BigDecimal.ZERO\n                    val nextTarget = pendingPlan.remainingTargets.firstOrNull { it > order.price } ?: BigDecimal.ZERO\n                    dao.upsertPosition(pendingPosition.copy(\n                        quantity = order.executedQuantity.toPlainString(), entryPriceEur = order.price.toPlainString(), highestPriceEur = order.price.toPlainString(),\n                        stopPriceEur = stop.toPlainString(), takeProfitPriceEur = nextTarget.toPlainString(), status = "OPEN", updatedAtEpochMs = System.currentTimeMillis()\n                    ))\n                    log("Pending handoff entry reconciled from Kraken fill: ${order.symbol} qty=${order.executedQuantity} price=${order.price} strategy=${pendingPlan.strategyId}", "LIVE")\n                } else if (order.executedQuantity <= BigDecimal.ZERO) {\n                    dao.updatePositionStatus(order.symbol, "ENTRY_CANCELLED", System.currentTimeMillis())\n                    log("Pending handoff entry closed without fill: ${order.symbol} order=${order.exchangeOrderId} status=${order.status}.", "INFO")\n                }\n            }\n            if (order.executedQuantity <= BigDecimal.ZERO) return@forEach\n', "lifecycle reconcile pending handoff fill")
    if "persistedHandoffPlan = HandoffPositionPlanCodec.decode" not in text:
        text = replace_once(text,
'''        val symbol = position.symbol
        val hasSellOrder = openOrders.any { it.symbol == symbol && it.side == OrderSide.SELL && it.remainingQuantity > BigDecimal.ZERO }
''',
'''        val symbol = position.symbol
        val persistedPosition = dao.positionForSymbol(symbol)
        val persistedHandoffPlan = HandoffPositionPlanCodec.decode(persistedPosition?.source)
        val hasSellOrder = openOrders.any { it.symbol == symbol && it.side == OrderSide.SELL && it.remainingQuantity > BigDecimal.ZERO }
''', "lifecycle persisted handoff plan")
    if "Handoff target $hitTarget consumed" not in text:
        text = text.replace('''                if (sellPercent >= BigDecimal.ONE) dao.updatePositionStatus(symbol, "EXIT_SUBMITTED", System.currentTimeMillis())
''','''                if (sellPercent >= BigDecimal.ONE) {
                    dao.updatePositionStatus(symbol, "EXIT_SUBMITTED", System.currentTimeMillis())
                } else if (hitTakeProfit && persistedHandoffPlan != null && persistedPosition != null) {
                    val hitTarget = persistedHandoffPlan.remainingTargets.firstOrNull { position.currentPrice >= it }
                    if (hitTarget != null) {
                        val nextPlan = HandoffPositionPlanCodec.afterTarget(persistedHandoffPlan, hitTarget)
                        val nextTarget = nextPlan.remainingTargets.firstOrNull() ?: BigDecimal.ZERO
                        dao.upsertPosition(persistedPosition.copy(takeProfitPriceEur = nextTarget.toPlainString(), source = HandoffPositionPlanCodec.encode(nextPlan), updatedAtEpochMs = System.currentTimeMillis()))
                        out += "[$symbol] Handoff target $hitTarget consumed; nextTarget=${if (nextTarget > BigDecimal.ZERO) nextTarget.stripTrailingZeros().toPlainString() else "none"}."
                    }
                }
''',1)
    if "val persistedPlan = HandoffPositionPlanCodec.decode(prev?.source)" not in text:
        text = replace_once(text,
'''            val stop = entry.multiply(BigDecimal.ONE.subtract(settings.stopLossPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
            val take = entry.multiply(BigDecimal.ONE.add(settings.takeProfitPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
''',
'''            val persistedPlan = HandoffPositionPlanCodec.decode(prev?.source)
            val defaultStop = entry.multiply(BigDecimal.ONE.subtract(settings.stopLossPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
            val defaultTake = entry.multiply(BigDecimal.ONE.add(settings.takeProfitPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
            val stop = persistedPlan?.stopPrice?.takeIf { it > BigDecimal.ZERO && it < entry }
                ?: prev?.stopPriceEur?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO && it < entry }
                ?: defaultStop
            val take = if (persistedPlan != null) persistedPlan.remainingTargets.firstOrNull() ?: BigDecimal.ZERO
                else prev?.takeProfitPriceEur?.toBigDecimalOrNull()?.takeIf { it > entry } ?: defaultTake
''', "lifecycle preserve source stop targets")
        text = text.replace('source = "LIVE_PORTFOLIO"', 'source = prev?.source?.takeIf { it.startsWith("HANDOFF_V1|") } ?: "LIVE_PORTFOLIO"', 1)
    if "UNPROTECTED_POSITION:" not in text:
        old='''                    governanceDao?.insertEvent(GovernanceEventEntity(
                        eventType = "handoff_protective_exit_failure", symbol = symbol, strategy = handoffDirective?.strategyId ?: settings.strategyMode.name,
                        mode = settings.mode.name, severity = "CRITICAL", blocked = true, sizeMultiplier = 0.0,
                        reason = "Protective handoff ${handoffIntent} failed to execute: ${error.message}. New entries must enter safe mode until the operational error is cleared."
                    ))
'''
        new='''                    governanceDao?.insertEvent(GovernanceEventEntity(
                        eventType = "handoff_protective_exit_failure", symbol = symbol, strategy = handoffDirective?.strategyId ?: settings.strategyMode.name,
                        mode = settings.mode.name, severity = "CRITICAL", blocked = true, sizeMultiplier = 0.0,
                        reason = "UNPROTECTED_POSITION: Protective handoff ${handoffIntent} failed to execute: ${error.message}. New entries must enter safe mode until the operational error is cleared."
                    ))
                    governanceDao?.putState(ProductionIntelligenceStateEntity(
                        key = "UNPROTECTED_POSITION:${symbol.uppercase()}",
                        value = "strategy=${handoffDirective?.strategyId ?: settings.strategyMode.name}; intent=$handoffIntent; error=${error.message}; timestamp=${System.currentTimeMillis()}"
                    ))
'''
        text = replace_once(text, old, new, "lifecycle unprotected position state")
    if "Lifecycle SELL accepted without confirmed fill" not in text:
        old_success = '''            .onSuccess { placed ->
                val msg = "[$symbol] Automatic SELL submitted by lifecycle manager: reason=$reason/${exitPlan.method}, qty=$qty, type=${request.orderType}, orderId=${placed.exchangeOrderId}."
                out += msg
                log(msg, "LIVE")
                recordTradeFromLifecycle(symbol, OrderSide.SELL, qty, position.currentPrice, placed.exchangeOrderId, "$reason; ${exitPlan.method}")
                if (sellPercent >= BigDecimal.ONE) {
                    dao.updatePositionStatus(symbol, "EXIT_SUBMITTED", System.currentTimeMillis())
                } else if (hitTakeProfit && persistedHandoffPlan != null && persistedPosition != null) {
                    val hitTarget = persistedHandoffPlan.remainingTargets.firstOrNull { position.currentPrice >= it }
                    if (hitTarget != null) {
                        val nextPlan = HandoffPositionPlanCodec.afterTarget(persistedHandoffPlan, hitTarget)
                        val nextTarget = nextPlan.remainingTargets.firstOrNull() ?: BigDecimal.ZERO
                        dao.upsertPosition(persistedPosition.copy(takeProfitPriceEur = nextTarget.toPlainString(), source = HandoffPositionPlanCodec.encode(nextPlan), updatedAtEpochMs = System.currentTimeMillis()))
                        out += "[$symbol] Handoff target $hitTarget consumed; nextTarget=${if (nextTarget > BigDecimal.ZERO) nextTarget.stripTrailingZeros().toPlainString() else "none"}."
                    }
                }
            }
'''
        new_success = '''            .onSuccess { placed ->
                val fillConfirmed = placed.executedQuantity > BigDecimal.ZERO && placed.averagePrice > BigDecimal.ZERO
                val strategyId = persistedHandoffPlan?.strategyId ?: handoffDirective?.strategyId ?: settings.strategyMode.name
                if (fillConfirmed) {
                    val actualQty = placed.executedQuantity.min(position.freeQuantity).setScale(8, RoundingMode.DOWN)
                    val actualPrice = placed.averagePrice
                    val actualFee = placed.fee.max(BigDecimal.ZERO)
                    val realized = if (placed.realizedPnlQuote != BigDecimal.ZERO) placed.realizedPnlQuote
                        else actualPrice.subtract(position.entryPrice).multiply(actualQty).subtract(actualFee)
                    val msg = "[$symbol] Automatic SELL filled by lifecycle manager: reason=$reason/${exitPlan.method}, strategy=$strategyId, qty=$actualQty, avg=$actualPrice, fee=$actualFee, realized=$realized, orderId=${placed.exchangeOrderId}."
                    out += msg
                    log(msg, "LIVE")
                    recordTradeFromLifecycle(symbol, OrderSide.SELL, actualQty, actualPrice, actualFee, realized, placed.exchangeOrderId, "$reason; ${exitPlan.method}", strategyId)
                    if (sellPercent >= BigDecimal.ONE || actualQty >= position.freeQuantity) {
                        dao.updatePositionStatus(symbol, "EXIT_SUBMITTED", System.currentTimeMillis())
                    } else if (hitTakeProfit && persistedHandoffPlan != null && persistedPosition != null) {
                        val hitTarget = persistedHandoffPlan.remainingTargets.firstOrNull { position.currentPrice >= it }
                        if (hitTarget != null) {
                            val nextPlan = HandoffPositionPlanCodec.afterTarget(persistedHandoffPlan, hitTarget)
                            val nextTarget = nextPlan.remainingTargets.firstOrNull() ?: BigDecimal.ZERO
                            dao.upsertPosition(persistedPosition.copy(takeProfitPriceEur = nextTarget.toPlainString(), source = HandoffPositionPlanCodec.encode(nextPlan), updatedAtEpochMs = System.currentTimeMillis()))
                            out += "[$symbol] Handoff target $hitTarget consumed after confirmed fill; nextTarget=${if (nextTarget > BigDecimal.ZERO) nextTarget.stripTrailingZeros().toPlainString() else "none"}."
                        }
                    }
                } else {
                    val msg = "[$symbol] Lifecycle SELL accepted without confirmed fill: reason=$reason/${exitPlan.method}, strategy=$strategyId, submittedQty=$qty, type=${request.orderType}, orderId=${placed.exchangeOrderId}. No realized trade/PnL is recorded until exchange fill evidence arrives."
                    out += msg
                    log(msg, "LIVE")
                    if (sellPercent >= BigDecimal.ONE) dao.updatePositionStatus(symbol, "EXIT_SUBMITTED", System.currentTimeMillis())
                }
            }
'''
        if old_success in text:
            text = text.replace(old_success, new_success, 1)
        else:
            # Already-partially-migrated sources can still be hardened by replacing the old accounting call.
            text = text.replace(
                '                recordTradeFromLifecycle(symbol, OrderSide.SELL, qty, position.currentPrice, placed.exchangeOrderId, "$reason; ${exitPlan.method}")\n',
                '                val fillConfirmed = placed.executedQuantity > BigDecimal.ZERO && placed.averagePrice > BigDecimal.ZERO\n                if (!fillConfirmed) {\n                    out += "[$symbol] Lifecycle SELL accepted without confirmed fill: orderId=${placed.exchangeOrderId}. No realized trade/PnL is recorded until exchange fill evidence arrives."\n                    log(out.last(), "LIVE")\n                    return@onSuccess\n                }\n                val strategyId = persistedHandoffPlan?.strategyId ?: handoffDirective?.strategyId ?: settings.strategyMode.name\n                val actualQty = placed.executedQuantity.min(position.freeQuantity).setScale(8, RoundingMode.DOWN)\n                val actualPrice = placed.averagePrice\n                val actualFee = placed.fee.max(BigDecimal.ZERO)\n                val realized = if (placed.realizedPnlQuote != BigDecimal.ZERO) placed.realizedPnlQuote else actualPrice.subtract(position.entryPrice).multiply(actualQty).subtract(actualFee)\n                recordTradeFromLifecycle(symbol, OrderSide.SELL, actualQty, actualPrice, actualFee, realized, placed.exchangeOrderId, "$reason; ${exitPlan.method}", strategyId)\n', 1)
    if "realizedPnlEur = realized.toPlainString()" not in text:
        old_fn = '''    private suspend fun recordTradeFromLifecycle(symbol: String, side: OrderSide, quantity: BigDecimal, price: BigDecimal, orderId: String, reason: String) {
        dao.insertTrade(
            TradeEntity(
                symbol = symbol,
                side = side.name,
                quantity = quantity.toPlainString(),
                priceEur = price.toPlainString(),
                feeEur = "0.00",
                paper = false,
                aiReason = "Lifecycle exit: $reason",
                clientOrderId = "lifecycle-${System.currentTimeMillis()}",
                exchangeOrderId = orderId,
                timestampEpochMs = System.currentTimeMillis()
            )
        )
        dao.insertTaxReportRow(
            TaxReportEntity(
                timestampEpochMs = System.currentTimeMillis(),
                symbol = symbol,
                side = side.name,
                quantity = quantity.toPlainString(),
                priceEur = price.toPlainString(),
                feeEur = "0.00",
                realizedGainEur = "0.00",
                note = "Lifecycle managed exit: $reason. Review realized gain with Kraken export/accountant."
            )
        )
    }
'''
        new_fn = '''    private suspend fun recordTradeFromLifecycle(symbol: String, side: OrderSide, quantity: BigDecimal, price: BigDecimal, fee: BigDecimal, realized: BigDecimal, orderId: String, reason: String, strategyId: String) {
        dao.insertTrade(
            TradeEntity(
                symbol = symbol,
                side = side.name,
                quantity = quantity.toPlainString(),
                priceEur = price.toPlainString(),
                feeEur = fee.toPlainString(),
                paper = false,
                realizedPnlEur = realized.toPlainString(),
                aiReason = "Lifecycle exit [$strategyId]: $reason",
                clientOrderId = "lifecycle-${System.currentTimeMillis()}",
                exchangeOrderId = orderId,
                timestampEpochMs = System.currentTimeMillis()
            )
        )
        dao.insertTaxReportRow(
            TaxReportEntity(
                timestampEpochMs = System.currentTimeMillis(),
                symbol = symbol,
                side = side.name,
                quantity = quantity.toPlainString(),
                priceEur = price.toPlainString(),
                feeEur = fee.toPlainString(),
                realizedGainEur = realized.toPlainString(),
                note = "Lifecycle managed exit [$strategyId]: $reason. Exchange fill fee and realized P/L recorded from confirmed execution evidence."
            )
        )
    }
'''
        if old_fn in text:
            text = text.replace(old_fn, new_fn, 1)
    if "val syncedRealized = if (order.side == OrderSide.SELL" not in text:
        anchor = '            if (order.executedQuantity <= BigDecimal.ZERO) return@forEach\n            val exists = dao.recentTradesSnapshot(300).any { it.exchangeOrderId == order.exchangeOrderId }\n'
        repl = '            if (order.executedQuantity <= BigDecimal.ZERO) return@forEach\n            val syncedStrategyId = pendingPlan?.strategyId ?: HandoffPositionPlanCodec.decode(pendingPosition?.source)?.strategyId ?: "KRAKEN_SYNC"\n            val syncedEntry = pendingPosition?.entryPriceEur?.toBigDecimalOrNull() ?: BigDecimal.ZERO\n            val syncedRealized = if (order.side == OrderSide.SELL && syncedEntry > BigDecimal.ZERO && order.price > BigDecimal.ZERO) order.price.subtract(syncedEntry).multiply(order.executedQuantity).subtract(order.fee) else BigDecimal.ZERO\n            val exists = dao.recentTradesSnapshot(300).any { it.exchangeOrderId == order.exchangeOrderId }\n'
        if anchor in text:
            text = text.replace(anchor, repl, 1)
            text = text.replace('                        paper = false,\n                        aiScore = 0,\n                        aiReason = "Synced from Kraken closed orders: ${order.description}",', '                        paper = false,\n                        realizedPnlEur = syncedRealized.toPlainString(),\n                        aiScore = 0,\n                        aiReason = "Synced Kraken fill [$syncedStrategyId]: ${order.description}",', 1)
    # lifecycle request purpose strategy tagging for deferred PAPER/live fills.
    text = text.replace(
        '            purpose = "$reason; ${exitPlan.method}"\n',
        '            purpose = "$reason; ${exitPlan.method}; strategy=${persistedHandoffPlan?.strategyId ?: handoffDirective?.strategyId ?: settings.strategyMode.name}"\n', 1)
    # Paper mode uses the lifecycle manager too, but Kraken-history sync stays live-only.
    text = text.replace('        if (settings.syncKrakenHistory) syncClosedOrders(exchange)\n', '        if (settings.syncKrakenHistory && settings.mode == BotMode.LIVE_AUTO) syncClosedOrders(exchange)\n', 1)
    text = text.replace(
        '        if (!settings.liveLifecycleManagerEnabled || settings.mode != BotMode.LIVE_AUTO) {\n',
        '        if (!settings.liveLifecycleManagerEnabled || (settings.mode != BotMode.LIVE_AUTO && settings.mode != BotMode.PAPER)) {\n', 1)
    # Ensure confirmed lifecycle fills retain PAPER/LIVE identity and tax rows are live-only.
    if 'recordTradeFromLifecycle(symbol, OrderSide.SELL, actualQty, actualPrice, actualFee, realized, placed.paper,' not in text:
        text = text.replace(
            'recordTradeFromLifecycle(symbol, OrderSide.SELL, actualQty, actualPrice, actualFee, realized, placed.exchangeOrderId, "$reason; ${exitPlan.method}", strategyId)',
            'recordTradeFromLifecycle(symbol, OrderSide.SELL, actualQty, actualPrice, actualFee, realized, placed.paper, placed.exchangeOrderId, "$reason; ${exitPlan.method}", strategyId)')
        text = text.replace(
            'private suspend fun recordTradeFromLifecycle(symbol: String, side: OrderSide, quantity: BigDecimal, price: BigDecimal, fee: BigDecimal, realized: BigDecimal, orderId: String, reason: String, strategyId: String)',
            'private suspend fun recordTradeFromLifecycle(symbol: String, side: OrderSide, quantity: BigDecimal, price: BigDecimal, fee: BigDecimal, realized: BigDecimal, paper: Boolean, orderId: String, reason: String, strategyId: String)')
        text = text.replace('                paper = false,\n                realizedPnlEur = realized.toPlainString(),', '                paper = paper,\n                realizedPnlEur = realized.toPlainString(),', 1)
        tax_old = '''        dao.insertTaxReportRow(
            TaxReportEntity(
                timestampEpochMs = System.currentTimeMillis(),
                symbol = symbol,
                side = side.name,
                quantity = quantity.toPlainString(),
                priceEur = price.toPlainString(),
                feeEur = fee.toPlainString(),
                realizedGainEur = realized.toPlainString(),
                note = "Lifecycle managed exit [$strategyId]: $reason. Exchange fill fee and realized P/L recorded from confirmed execution evidence."
            )
        )
'''
        if tax_old in text:
            text = text.replace(tax_old, '''        if (!paper) {
            dao.insertTaxReportRow(
                TaxReportEntity(
                    timestampEpochMs = System.currentTimeMillis(),
                    symbol = symbol,
                    side = side.name,
                    quantity = quantity.toPlainString(),
                    priceEur = price.toPlainString(),
                    feeEur = fee.toPlainString(),
                    realizedGainEur = realized.toPlainString(),
                    note = "Lifecycle managed exit [$strategyId]: $reason. Exchange fill fee and realized P/L recorded from confirmed execution evidence."
                )
            )
        }
''',1)
    # Paper mode marker lives in generated source so installer idempotency can be audited.
    if 'Paper mode uses the lifecycle manager' not in text and 'LifecycleSnapshot' in text:
        text = text.replace('            val snapshot = snapshot(settings, exchange, listOf("Lifecycle manager not active for mode=${settings.mode}."))', '            val snapshot = snapshot(settings, exchange, listOf("Lifecycle manager not active for mode=${settings.mode}. Paper mode uses the lifecycle manager when enabled."))',1)
    # Pending PAPER entries adopt the confirmed deferred fill price rather than the staged trigger/limit.
    if 'prev?.status.equals("PENDING_ENTRY", true)' not in text:
        old_entry = '''            val entry = prev?.entryPriceEur?.toBigDecimalOrNull()
                ?: lastBuy?.priceEur?.toBigDecimalOrNull()
                ?: current
'''
        new_entry = '''            val previousEntry = prev?.entryPriceEur?.toBigDecimalOrNull()
            val confirmedBuyEntry = lastBuy?.priceEur?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
            val entry = if (prev?.status.equals("PENDING_ENTRY", true) && confirmedBuyEntry != null) confirmedBuyEntry
                else previousEntry ?: confirmedBuyEntry ?: current
'''
        if old_entry in text:
            text = text.replace(old_entry,new_entry,1)
    # Exchange-level stop lifecycle integration. Existing sourced positions may have their
    # base quantity held by a Kraken stop, so position.total (not free) determines exposure.
    if "import com.ksp.cryptobot.execution.ProtectiveStopManager" not in text:
        text = text.replace("import com.ksp.cryptobot.execution.AdvancedExitOptimizer\n", "import com.ksp.cryptobot.execution.AdvancedExitOptimizer\nimport com.ksp.cryptobot.execution.ProtectiveStopManager\n", 1)
    if "private val protectiveStops = governanceDao?.let" not in text:
        field_anchor = "    private val advancedExitOptimizer = AdvancedExitOptimizer(governanceDao)\n"
        if field_anchor in text:
            text = text.replace(field_anchor, field_anchor + "    private val protectiveStops = governanceDao?.let { ProtectiveStopManager(dao, it) }\n", 1)
    text = text.replace("        if (position.freeQuantity <= BigDecimal.ZERO) return out\n", "        if (position.quantity <= BigDecimal.ZERO) return out\n", 1)
    if "val protectiveStopOrders = openOrders.filter" not in text:
        old = "        val hasSellOrder = openOrders.any { it.symbol == symbol && it.side == OrderSide.SELL && it.remainingQuantity > BigDecimal.ZERO }\n"
        new = '''        val protectiveStopOrders = openOrders.filter { it.symbol == symbol && it.side == OrderSide.SELL && it.orderType == OrderType.STOP_LOSS && it.remainingQuantity > BigDecimal.ZERO }
        val hasExchangeProtectiveStop = protectiveStopOrders.isNotEmpty()
        val hasSellOrder = openOrders.any { it.symbol == symbol && it.side == OrderSide.SELL && it.orderType != OrderType.STOP_LOSS && it.remainingQuantity > BigDecimal.ZERO }
'''
        if old in text: text = text.replace(old,new,1)
    if "Exchange protective stop remains authoritative at technical stop" not in text:
        hit_anchor = "        val hitTakeProfit = settings.autoTakeProfitEnabled && position.currentPrice >= position.takeProfitPrice && position.takeProfitPrice > BigDecimal.ZERO\n"
        hit_block = hit_anchor + '''        if (hitStop && hasExchangeProtectiveStop && settings.mode != BotMode.PAPER) {
            out += "[$symbol] Exchange protective stop remains authoritative at technical stop=${position.stopPrice}. ExistingStopOrders=${protectiveStopOrders.joinToString(",") { it.exchangeOrderId }}. No duplicate app-side SELL is sent while Kraken owns the stop."
            log(out.last(), "LIVE")
            return out
        }
'''
        if hit_anchor in text: text=text.replace(hit_anchor,hit_block,1)
    # Kraken history synchronization is live-only. PAPER already journals its deferred fills itself.
    text = text.replace("        if (settings.syncKrakenHistory) syncClosedOrders(exchange)\n", "        if (settings.syncKrakenHistory && settings.mode != BotMode.PAPER) syncClosedOrders(settings, exchange)\n", 1)
    text = text.replace("        if (settings.syncKrakenHistory && settings.mode == BotMode.LIVE_AUTO) syncClosedOrders(exchange)\n", "        if (settings.syncKrakenHistory && settings.mode != BotMode.PAPER) syncClosedOrders(settings, exchange)\n", 1)
    text = text.replace("    private suspend fun syncClosedOrders(exchange: CryptoExchangeClient) {\n", "    private suspend fun syncClosedOrders(settings: BotSettings, exchange: CryptoExchangeClient) {\n", 1)
    if "Deferred handoff fill protection:" not in text:
        reconcile_anchor = '''                    log("Pending handoff entry reconciled from Kraken fill: ${order.symbol} qty=${order.executedQuantity} price=${order.price} strategy=${pendingPlan.strategyId}", "LIVE")
'''
        reconcile_block = reconcile_anchor + '''                    val protection = protectiveStops?.protectOrFlatten(
                        settings = settings, exchange = exchange, symbol = order.symbol,
                        quantity = order.executedQuantity, entryPrice = order.price,
                        stopPrice = stop, strategyId = pendingPlan.strategyId, paper = false
                    )
                    if (protection != null) log("Deferred handoff fill protection: protected=${protection.protected}, flattened=${protection.flattened}, pendingEmergency=${protection.pendingEmergencyExit}. ${protection.reason}", if (protection.protected) "LIVE" else "ERROR")
'''
        if reconcile_anchor in text: text=text.replace(reconcile_anchor,reconcile_block,1)
    if "val sourceRequiredExit = persistedHandoffPlan != null" not in text:
        optimizer_anchor = '''        val exitPlan = advancedExitOptimizer.optimize(settings, position, decision, reason)
        out += "[$symbol] ${exitPlan.reason}"
        if (!exitPlan.shouldExit || exitPlan.sellFraction <= BigDecimal.ZERO) {
            out += "[$symbol] Advanced exit optimizer deferred this soft exit."
            log(out.last(), "LEARN")
            return out
        }
        val sellPercent = exitPlan.sellFraction
        val qty = position.freeQuantity.multiply(sellPercent).setScale(8, RoundingMode.DOWN)
'''
        optimizer_block = '''        val exitPlan = advancedExitOptimizer.optimize(settings, position, decision, reason)
        out += "[$symbol] ${exitPlan.reason}"
        val sourceRequiredExit = persistedHandoffPlan != null && (hitStop || handoffProtective || hitTakeProfit)
        if ((!exitPlan.shouldExit || exitPlan.sellFraction <= BigDecimal.ZERO) && !sourceRequiredExit) {
            out += "[$symbol] Advanced exit optimizer deferred this soft exit."
            log(out.last(), "LEARN")
            return out
        }
        val sellPercent = when {
            hitStop -> BigDecimal.ONE
            handoffIntent == HandoffSideIntent.EXIT -> BigDecimal.ONE
            handoffIntent == HandoffSideIntent.REDUCE -> exitPlan.sellFraction.coerceIn(BigDecimal("0.25"), BigDecimal("0.50"))
            persistedHandoffPlan != null && hitTakeProfit -> BigDecimal.ONE
            else -> exitPlan.sellFraction
        }
        var sellableQuantity = position.freeQuantity
        val sourceManagedLiveExit = persistedHandoffPlan != null && settings.mode != BotMode.PAPER
        var cancelledProtectiveStop = false
        if (sourceManagedLiveExit && hasExchangeProtectiveStop) {
            val cancelled = protectiveStops?.cancelProtectiveStops(exchange, symbol)
            if (cancelled == null || !cancelled.first) {
                val msg = "[$symbol] Source-managed exit BLOCKED: existing Kraken protective stop could not be cancelled safely; refusing a competing SELL that could oversell the position."
                out += msg; log(msg, "ERROR")
                governanceDao?.insertEvent(GovernanceEventEntity(eventType="protective_stop_cancel_failure",symbol=symbol,strategy=(persistedHandoffPlan?.strategyId ?: handoffDirective?.strategyId ?: settings.strategyMode.name),mode=settings.mode.name,severity="CRITICAL",blocked=true,sizeMultiplier=0.0,reason=msg))
                return out
            }
            cancelledProtectiveStop = cancelled.second.isNotEmpty()
            val refreshed = runCatching { exchange.getPortfolioBalances() }.getOrDefault(emptyList())
            sellableQuantity = refreshed.firstOrNull { it.asset.equals(position.baseAsset, ignoreCase = true) }?.free ?: position.quantity
        }
        if (!sourceManagedLiveExit && sellableQuantity <= BigDecimal.ZERO) return out
        if (sourceManagedLiveExit && sellableQuantity <= BigDecimal.ZERO) {
            val restore = protectiveStops?.restoreAfterManagedExit(settings,exchange,symbol,position.quantity,position.entryPrice,position.stopPrice,(persistedHandoffPlan?.strategyId ?: handoffDirective?.strategyId ?: settings.strategyMode.name),false)
            val msg = "[$symbol] Protective stop cancelled but no sellable quantity became available; protection restoration=${restore?.protected}. ${restore?.reason.orEmpty()}"
            out += msg; log(msg,"ERROR"); return out
        }
        val qty = sellableQuantity.multiply(sellPercent).setScale(8, RoundingMode.DOWN)
'''
        if optimizer_anchor in text: text=text.replace(optimizer_anchor,optimizer_block,1)
    if "val lifecycleOrderType = if (sourceManagedLiveExit)" not in text:
        request_anchor='''        val request = OrderRequest(
            symbol = symbol,
            side = OrderSide.SELL,
            quantity = qty,
            limitPrice = if (exitPlan.orderType == OrderType.MARKET) null else position.currentPrice,
            orderType = exitPlan.orderType,
'''
        request_block='''        val lifecycleOrderType = if (sourceManagedLiveExit) OrderType.MARKET else exitPlan.orderType
        val request = OrderRequest(
            symbol = symbol,
            side = OrderSide.SELL,
            quantity = qty,
            limitPrice = if (lifecycleOrderType == OrderType.MARKET) null else position.currentPrice,
            orderType = lifecycleOrderType,
'''
        if request_anchor in text: text=text.replace(request_anchor,request_block,1)
    if "Protective stop restored after managed partial exit" not in text:
        text = text.replace("val actualQty = placed.executedQuantity.min(position.freeQuantity).setScale(8, RoundingMode.DOWN)", "val actualQty = placed.executedQuantity.min(sellableQuantity).setScale(8, RoundingMode.DOWN)", 1)
        partial_anchor='''                    if (sellPercent >= BigDecimal.ONE || actualQty >= position.freeQuantity) {
                        dao.updatePositionStatus(symbol, "EXIT_SUBMITTED", System.currentTimeMillis())
                    } else if (hitTakeProfit && persistedHandoffPlan != null && persistedPosition != null) {
'''
        partial_block='''                    val remainingAfterFill = position.quantity.subtract(actualQty).max(BigDecimal.ZERO)
                    if (sellPercent >= BigDecimal.ONE || remainingAfterFill <= BigDecimal.ZERO) {
                        dao.updatePositionStatus(symbol, "EXIT_SUBMITTED", System.currentTimeMillis())
                    } else if (hitTakeProfit && persistedHandoffPlan != null && persistedPosition != null) {
'''
        if partial_anchor in text: text=text.replace(partial_anchor,partial_block,1)
        target_tail='''                            out += "[$symbol] Handoff target $hitTarget consumed after confirmed fill; nextTarget=${if (nextTarget > BigDecimal.ZERO) nextTarget.stripTrailingZeros().toPlainString() else "none"}."
                        }
                    }
'''
        target_repl='''                            out += "[$symbol] Handoff target $hitTarget consumed after confirmed fill; nextTarget=${if (nextTarget > BigDecimal.ZERO) nextTarget.stripTrailingZeros().toPlainString() else "none"}."
                        }
                    }
                    if (sourceManagedLiveExit && remainingAfterFill > BigDecimal.ZERO && persistedHandoffPlan != null) {
                        val restored = protectiveStops?.restoreAfterManagedExit(settings,exchange,symbol,remainingAfterFill,position.entryPrice,position.stopPrice,(persistedHandoffPlan?.strategyId ?: handoffDirective?.strategyId ?: settings.strategyMode.name),false)
                        out += "[$symbol] Protective stop restored after managed partial exit: protected=${restored?.protected}. ${restored?.reason.orEmpty()}"
                        log(out.last(), if (restored?.protected == true) "LIVE" else "ERROR")
                    }
'''
        if target_tail in text: text=text.replace(target_tail,target_repl,1)
        pending_anchor='''                    if (sellPercent >= BigDecimal.ONE) dao.updatePositionStatus(symbol, "EXIT_SUBMITTED", System.currentTimeMillis())
'''
        pending_repl='''                    if (sourceManagedLiveExit && cancelledProtectiveStop && persistedHandoffPlan != null) {
                        val restored = protectiveStops?.restoreAfterManagedExit(settings,exchange,symbol,position.quantity,position.entryPrice,position.stopPrice,(persistedHandoffPlan?.strategyId ?: handoffDirective?.strategyId ?: settings.strategyMode.name),false)
                        out += "[$symbol] Exit not yet filled; original protective stop restoration=${restored?.protected}. ${restored?.reason.orEmpty()}"
                        log(out.last(), if (restored?.protected == true) "LIVE" else "ERROR")
                    }
                    if (sellPercent >= BigDecimal.ONE) dao.updatePositionStatus(symbol, "EXIT_SUBMITTED", System.currentTimeMillis())
'''
        if pending_anchor in text: text=text.replace(pending_anchor,pending_repl,1)
    if "Restore source protection after managed exit submission failure" not in text:
        fail_anchor='''                if (handoffProtective) {
                    governanceDao?.insertEvent(GovernanceEventEntity(
'''
        restore='''                // Restore source protection after managed exit submission failure if we cancelled it first.
                if (sourceManagedLiveExit && cancelledProtectiveStop && persistedHandoffPlan != null) {
                    val restored = protectiveStops?.restoreAfterManagedExit(settings,exchange,symbol,position.quantity,position.entryPrice,position.stopPrice,(persistedHandoffPlan?.strategyId ?: handoffDirective?.strategyId ?: settings.strategyMode.name),false)
                    out += "[$symbol] Exit submission failed; protective stop restoration=${restored?.protected}. ${restored?.reason.orEmpty()}"
                    log(out.last(), if (restored?.protected == true) "LIVE" else "ERROR")
                }
'''+fail_anchor
        if fail_anchor in text: text=text.replace(fail_anchor,restore,1)
    path.write_text(text, encoding="utf-8")

def patch_paper_exchange(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "AdvancedExecutionEventEntity" not in text:
        text = replace_once(text, "import com.ksp.cryptobot.core.*\n",
                            "import com.ksp.cryptobot.core.*\nimport com.ksp.cryptobot.data.AdvancedExecutionEventEntity\nimport com.ksp.cryptobot.data.AppDatabase\n",
                            "paper execution imports")
    if "private val context: Context? = null" not in text:
        text = replace_once(text, "class PaperExchangeClient(\n    context: Context? = null,\n",
                            "class PaperExchangeClient(\n    private val context: Context? = null,\n",
                            "paper context field")
    if "paperDepthQuote" not in text:
        old='''        val ticker = getTicker(clean)
        val price = request.limitPrice?.takeIf { it > BigDecimal.ZERO }
            ?: if (request.side == OrderSide.BUY) ticker.ask else ticker.bid
        val requestedQty = request.quantity.max(BigDecimal.ZERO)
        val notional = price.multiply(requestedQty).setScale(8, RoundingMode.HALF_UP)
        val fee = notional.multiply(BigDecimal("0.001")).setScale(8, RoundingMode.HALF_UP)
        val wallet = balances().toMutableMap()
'''
        new='''        val ticker = getTicker(clean)
        val requestedQty = request.quantity.max(BigDecimal.ZERO)
        val paperBook = krakenPublicMarketData?.let { live -> runCatching { live.getOrderBook(clean, 40) }.getOrNull() }
        val paperLevels = if (request.side == OrderSide.BUY) paperBook?.asks.orEmpty() else paperBook?.bids.orEmpty()
        val paperDepthQuote = paperLevels.take(10).fold(BigDecimal.ZERO) { acc, level -> acc.add(level.price.multiply(level.quantity)) }
        val referencePrice = if (request.side == OrderSide.BUY) ticker.ask else ticker.bid
        val requestedNotional = referencePrice.multiply(requestedQty).setScale(8, RoundingMode.HALF_UP)
        val spreadPct = if (ticker.lastPrice > BigDecimal.ZERO) ticker.ask.subtract(ticker.bid).abs().divide(ticker.lastPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal.ZERO
        val depthRatio = if (paperDepthQuote > BigDecimal.ZERO) requestedNotional.divide(paperDepthQuote.max(BigDecimal.ONE), 8, RoundingMode.HALF_UP).min(BigDecimal.ONE) else BigDecimal.ZERO
        val depthPenaltyPct = depthRatio.multiply(BigDecimal("0.18"))
        val volatilityPenaltyPct = spreadPct.multiply(BigDecimal("0.10")).min(BigDecimal("0.20"))
        val baseSlippagePct = BigDecimal("0.03")
        val slippagePct = if (request.orderType == OrderType.MARKET) baseSlippagePct.add(depthPenaltyPct).add(volatilityPenaltyPct) else BigDecimal.ZERO
        val marketReference = if (request.side == OrderSide.BUY) ticker.ask else ticker.bid
        val marketPrice = if (request.side == OrderSide.BUY) {
            marketReference.multiply(BigDecimal.ONE.add(slippagePct.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
        } else {
            marketReference.multiply(BigDecimal.ONE.subtract(slippagePct.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)))
        }
        val price = request.limitPrice?.takeIf { it > BigDecimal.ZERO } ?: marketPrice
        var fillRatio = BigDecimal.ONE
        if (request.orderType == OrderType.LIMIT && request.limitPrice != null) {
            fillRatio = when (request.side) {
                OrderSide.BUY -> when { request.limitPrice >= ticker.ask -> BigDecimal.ONE; request.limitPrice > ticker.bid -> BigDecimal("0.50"); else -> BigDecimal("0.25") }
                OrderSide.SELL -> when { request.limitPrice <= ticker.bid -> BigDecimal.ONE; request.limitPrice < ticker.ask -> BigDecimal("0.50"); else -> BigDecimal("0.25") }
            }
        }
        if (paperDepthQuote > BigDecimal.ZERO && requestedNotional > paperDepthQuote.multiply(BigDecimal("0.20"))) {
            val depthFill = paperDepthQuote.multiply(BigDecimal("0.20")).divide(requestedNotional.max(BigDecimal.ONE), 8, RoundingMode.DOWN).coerceIn(BigDecimal("0.25"), BigDecimal.ONE)
            fillRatio = fillRatio.min(depthFill)
        }
        val executableRequestedQty = requestedQty.multiply(fillRatio).setScale(8, RoundingMode.DOWN)
        val feeRate = if (request.orderType == OrderType.LIMIT) BigDecimal("0.0016") else BigDecimal("0.0026")
        val wallet = balances().toMutableMap()
'''
        text = replace_once(text, old, new, "paper realistic execution preamble")
        text = text.replace('val maxQty = if (price > BigDecimal.ZERO) spendable.divide(price.multiply(BigDecimal("1.001")), 8, RoundingMode.DOWN) else BigDecimal.ZERO\n                val qty = requestedQty.min(maxQty).setScale(8, RoundingMode.DOWN)',
                            'val maxQty = if (price > BigDecimal.ZERO) spendable.divide(price.multiply(BigDecimal.ONE.add(feeRate)), 8, RoundingMode.DOWN) else BigDecimal.ZERO\n                val qty = executableRequestedQty.min(maxQty).setScale(8, RoundingMode.DOWN)', 1)
        text = text.replace('val buyFee = cost.multiply(BigDecimal("0.001")).setScale(8, RoundingMode.HALF_UP)', 'val buyFee = cost.multiply(feeRate).setScale(8, RoundingMode.HALF_UP)', 1)
        text = text.replace('val qty = requestedQty.min(freeBase).setScale(8, RoundingMode.DOWN)', 'val qty = executableRequestedQty.min(freeBase).setScale(8, RoundingMode.DOWN)', 1)
        text = text.replace('val sellFee = proceeds.multiply(BigDecimal("0.001")).setScale(8, RoundingMode.HALF_UP)', 'val sellFee = proceeds.multiply(feeRate).setScale(8, RoundingMode.HALF_UP)', 1)
        old_return='''        saveBalances(wallet)
        return OrderResult(
            exchangeOrderId = request.clientOrderId,
            symbol = clean,
            side = request.side,
            executedQuantity = executedQty,
            averagePrice = price,
            fee = price.multiply(executedQty).multiply(BigDecimal("0.001")).setScale(8, RoundingMode.HALF_UP),
            paper = true
        )
'''
        new_return='''        saveBalances(wallet)
        context?.let { ctx ->
            val finalNotional = price.multiply(executedQty).setScale(8, RoundingMode.HALF_UP)
            val band = when { requestedNotional < BigDecimal("10") -> "micro"; requestedNotional < BigDecimal("25") -> "small"; requestedNotional < BigDecimal("100") -> "medium"; else -> "large" }
            AppDatabase.get(ctx).governanceDao().insertAdvancedExecution(AdvancedExecutionEventEntity(
                eventType = "paper_execution", symbol = clean, strategy = "PAPER", mode = "PAPER", side = request.side.name,
                severity = if (fillRatio < BigDecimal.ONE) "WARN" else "INFO", requestedQuote = requestedNotional.toDouble(), finalQuote = finalNotional.toDouble(),
                multiplier = fillRatio.toDouble(), recommendedOrderType = request.orderType.name, reasonCategory = if (fillRatio < BigDecimal.ONE) "partial_fill" else "filled",
                requestedSizeBand = band, qualityTier = "paper", blocked = false,
                reason = "realistic paper execution: fill=${fillRatio.setScale(2, RoundingMode.HALF_UP)}, feeRate=${feeRate.multiply(BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)}%, slippage=${slippagePct.setScale(3, RoundingMode.HALF_UP)}%, depth=${paperDepthQuote.setScale(2, RoundingMode.DOWN)}"
            ))
        }
        return OrderResult(
            exchangeOrderId = request.clientOrderId,
            symbol = clean,
            side = request.side,
            executedQuantity = executedQty,
            averagePrice = price,
            fee = price.multiply(executedQty).multiply(feeRate).setScale(8, RoundingMode.HALF_UP),
            paper = true
        )
'''
        text = replace_once(text, old_return, new_return, "paper execution result")
    path.write_text(text, encoding="utf-8")

def patch_version(gradle: Path) -> None:
    text=gradle.read_text(encoding="utf-8")
    text,c1=re.subn(r'versionCode\s*=\s*(?:97|100|101|102|103)\b','versionCode = 103',text,count=1)
    text,c2=re.subn(r'versionName\s*=\s*"(?:3\.2\.5|4\.0\.0-m1|4\.0\.0-m2|4\.0\.0-m3|4\.0\.0-m4)"','versionName = "4.0.0-m4"',text,count=1)
    if c1 != 1 or c2 != 1: fail(f"Cannot patch Gradle version: code={c1}, name={c2}")
    gradle.write_text(text,encoding="utf-8")

def main() -> None:
    if len(sys.argv)!=2: fail("Usage: python apply_milestone4.py <path-to-Trading-Station-repo>")
    repo=Path(sys.argv[1]).resolve(); src=repo/'app/src/main/java/com/ksp/cryptobot'
    controller=src/'core/BotController.kt'; lifecycle=src/'lifecycle/TradeLifecycleManager.kt'; paper=src/'exchange/PaperExchangeClient.kt'; gradle=repo/'app/build.gradle.kts'
    if not all(p.exists() for p in (controller,lifecycle,paper,gradle)): fail("Target does not look like the current Demon-blood/Trading-Station source tree.")
    # M3 installer is cumulative and, because this package's overlay is cumulative, also copies the M4 data/execution source files.
    run_m3(repo)
    b=backup([controller,lifecycle,paper,gradle],repo)
    patch_controller(controller); patch_lifecycle(lifecycle); patch_paper_exchange(paper); patch_version(gradle)
    print("Applied cumulative Crypto TradeStation Android v4 Milestone 4.")
    print(f"M4 backup: {b}")
    print("Database: explicit migrations 6->7->8->9->10; no destructive fallback.")
    print("Execution: production size multiplier, capital ladder, portfolio allocation, liquidity sizing, order-type optimization, reconciliation, exit optimization.")
    print("Next build: ./gradlew clean :app:assembleDebug")

if __name__=='__main__': main()
