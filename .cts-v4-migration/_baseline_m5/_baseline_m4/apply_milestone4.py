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
                            "import com.ksp.cryptobot.governance.ProductionIntelligenceEngine\nimport com.ksp.cryptobot.execution.AdvancedExecutionCoordinator\n",
                            "controller M4 import")
        text = replace_once(text, "    private val productionIntelligence = ProductionIntelligenceEngine(AppDatabase.get(appContext).governanceDao())\n",
                            "    private val productionIntelligence = ProductionIntelligenceEngine(AppDatabase.get(appContext).governanceDao())\n    private val advancedExecution = AdvancedExecutionCoordinator(dao, AppDatabase.get(appContext).governanceDao())\n",
                            "controller M4 field")
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
        block='''\n        if (side == OrderSide.BUY) {\n            val advancedOrderBook = runCatching { exchange.getOrderBook(ticker.symbol, 40) }.getOrNull()\n            val advancedPlan = advancedExecution.prepareEntry(\n                settings = settings, ticker = ticker, decision = decision, requestedQuote = targetNotional,\n                orderBook = advancedOrderBook, mode = if (settings.mode == BotMode.PAPER) "PAPER" else "LIVE", currentUseMarket = useMarketOrder\n            )\n            updateStatus("[${ticker.symbol}] Advanced execution plan: allowed=${advancedPlan.allowed}, final=${advancedPlan.finalQuote.setScale(2, RoundingMode.DOWN)}, order=${advancedPlan.orderType}, protection=${advancedPlan.protectionLevel}, size×${advancedPlan.combinedMultiplier}. ${advancedPlan.reason.take(260)}", if (advancedPlan.allowed) "INFO" else "WARN")\n            if (!advancedPlan.allowed) {\n                productionIntelligence.recordWhyNotTrade(decision, settings, advancedPlan.reason)\n                return ExecutionAttemptResult(false)\n            }\n            targetNotional = advancedPlan.finalQuote\n            if (useMarketOrder && advancedPlan.orderType == OrderType.LIMIT) useMarketOrder = false\n            if (!useMarketOrder && advancedPlan.limitPrice != null && advancedPlan.limitPrice > BigDecimal.ZERO) price = advancedPlan.limitPrice\n            val orderBookCheck = orderBookDepthAllowsExecution(settings, exchange, ticker.symbol, side, targetNotional, price)\n'''
        text = replace_once(text, marker, block, "controller advanced entry plan")
    path.write_text(text, encoding="utf-8")

def patch_lifecycle(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    if "AdvancedExitOptimizer" not in text:
        text = replace_once(text, "import com.ksp.cryptobot.data.TradeEntity\n",
                            "import com.ksp.cryptobot.data.TradeEntity\nimport com.ksp.cryptobot.data.GovernanceDao\nimport com.ksp.cryptobot.execution.AdvancedExitOptimizer\n",
                            "lifecycle M4 imports")
        text = replace_once(text, "    private val statusStore: BotStatusStore\n) {\n",
                            "    private val statusStore: BotStatusStore,\n    governanceDao: GovernanceDao? = null\n) {\n",
                            "lifecycle governance constructor")
        text = replace_once(text, "    private val spikeProfitTimingEngine = SpikeProfitTimingEngine()\n",
                            "    private val spikeProfitTimingEngine = SpikeProfitTimingEngine()\n    private val advancedExitOptimizer = AdvancedExitOptimizer(governanceDao)\n",
                            "lifecycle exit optimizer field")
    if "val exitPlan = advancedExitOptimizer.optimize" not in text:
        old='''        val sellPercent = if (hitTakeProfit && settings.enablePartialTakeProfit && position.unrealizedPnlPercent > BigDecimal.ZERO) {\n            settings.partialExitPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP).coerceIn(BigDecimal("0.05"), BigDecimal.ONE)\n        } else BigDecimal.ONE\n        val qty = position.freeQuantity.multiply(sellPercent).setScale(8, RoundingMode.DOWN)\n'''
        new='''        val exitPlan = advancedExitOptimizer.optimize(settings, position, decision, reason)\n        out += "[$symbol] ${exitPlan.reason}"\n        if (!exitPlan.shouldExit || exitPlan.sellFraction <= BigDecimal.ZERO) {\n            out += "[$symbol] Advanced exit optimizer deferred this soft exit."\n            log(out.last(), "LEARN")\n            return out\n        }\n        val sellPercent = exitPlan.sellFraction\n        val qty = position.freeQuantity.multiply(sellPercent).setScale(8, RoundingMode.DOWN)\n'''
        text = replace_once(text, old, new, "lifecycle advanced exit sizing")
        text = replace_once(text,
'''            limitPrice = if (settings.enableMarketOrders) null else position.currentPrice,\n            orderType = if (settings.enableMarketOrders) OrderType.MARKET else OrderType.LIMIT,\n            clientOrderId = "ksp-exit-${symbol.lowercase()}-${System.currentTimeMillis()}",\n            reduceOnly = true,\n            purpose = reason\n''',
'''            limitPrice = if (exitPlan.orderType == OrderType.MARKET) null else position.currentPrice,\n            orderType = exitPlan.orderType,\n            clientOrderId = "ksp-exit-${symbol.lowercase()}-${System.currentTimeMillis()}",\n            reduceOnly = true,\n            purpose = "$reason; ${exitPlan.method}"\n''', "lifecycle advanced exit order")
        text = text.replace('reason=$reason, qty=$qty, type=${request.orderType}', 'reason=$reason/${exitPlan.method}, qty=$qty, type=${request.orderType}', 1)
        text = text.replace('recordTradeFromLifecycle(symbol, OrderSide.SELL, qty, position.currentPrice, placed.exchangeOrderId, reason)', 'recordTradeFromLifecycle(symbol, OrderSide.SELL, qty, position.currentPrice, placed.exchangeOrderId, "$reason; ${exitPlan.method}")', 1)
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
