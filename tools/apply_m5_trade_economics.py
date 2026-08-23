#!/usr/bin/env python3
from __future__ import annotations

import os
import sys
from pathlib import Path

NEW_ENGINE = "app/src/main/java/com/ksp/cryptobot/execution/TradeEconomicsEngine.kt"
NEW_TEST = "app/src/test/java/com/ksp/cryptobot/execution/TradeEconomicsEngineTest.kt"

def fail(message: str):
    raise SystemExit("ERROR | " + message)

def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)

def main():
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app/ tree:\n" + dirty)

    payload_root = Path(__file__).resolve().parent / "m5_payload"
    for rel in (NEW_ENGINE, NEW_TEST):
        source = payload_root / rel
        target = repo / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
        print("WRITE |", rel)

    # 1. AdvancedExecutionCoordinator: one final authoritative EV gate.
    advanced_path = repo / "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt"
    advanced = advanced_path.read_text(encoding="utf-8")

    advanced = replace_once(
        advanced,
        "import com.ksp.cryptobot.exchange.CryptoExchangeClient\n",
        "import com.ksp.cryptobot.exchange.CryptoExchangeClient\nimport com.ksp.cryptobot.exchange.TradingFeeSchedule\n",
        "Advanced execution fee schedule import"
    )

    advanced = replace_once(
        advanced,
        '''    private val liquiditySizer = LiquidityAwareSizer()
    private val orderTypeOptimizer = OrderTypeOptimizer()
''',
        '''    private val liquiditySizer = LiquidityAwareSizer()
    private val orderTypeOptimizer = OrderTypeOptimizer()
    private val tradeEconomics = TradeEconomicsEngine()
''',
        "Advanced execution economics engine property"
    )

    advanced = replace_once(
        advanced,
        '''        orderBook: OrderBookSnapshot?,
        mode: String,
        currentUseMarket: Boolean
    ): AdvancedEntryPlan {''',
        '''        orderBook: OrderBookSnapshot?,
        mode: String,
        currentUseMarket: Boolean,
        feeSchedule: TradingFeeSchedule? = null
    ): AdvancedEntryPlan {''',
        "Advanced execution economics fee parameter"
    )

    old_cost = '''        val finalPostOnly = directive?.postOnlyPreferred == true && finalOrderType == OrderType.LIMIT
        val costGate = roundTripCostGate(settings, ticker, trades, orderBook, finalQuote, finalOrderType, finalLimitOrTrigger, directive?.targets.orEmpty(), directive?.makerFeeRate, directive?.takerFeeRate, directive?.feeSource, finalPostOnly)
        if (!costGate.first) {
            val reason = "advanced execution cost gate blocked entry: ${costGate.second}"
            record("entry_cost_gate", decision.symbol, settings, mode, requestedQuote, finalQuote, BigDecimal.ZERO, finalOrderType.name, "round_trip_cost", sizeBand(requestedQuote), "", "blocked", true, reason, "WARN")
            return AdvancedEntryPlan(false, finalQuote, finalOrderType, finalLimitOrTrigger, BigDecimal.ZERO, protection.level, reason)
        }
'''
    new_cost = '''        val finalPostOnly = directive?.postOnlyPreferred == true && finalOrderType == OrderType.LIMIT
        val entryReference = (finalLimitOrTrigger ?: ticker.ask).takeIf { it > BigDecimal.ZERO } ?: ticker.lastPrice
        val targetPrice = directive?.targets
            ?.firstOrNull { it > entryReference }
            ?: entryReference.multiply(
                BigDecimal.ONE.add(settings.takeProfitPercent.divide(BigDecimal("100"), 12, RoundingMode.HALF_UP))
            )
        val stopPrice = directive?.stopPrice
            ?.takeIf { it > BigDecimal.ZERO && it < entryReference }
            ?: entryReference.multiply(
                BigDecimal.ONE.subtract(settings.stopLossPercent.divide(BigDecimal("100"), 12, RoundingMode.HALF_UP))
            )

        val directiveFeeSchedule = directive?.let { d ->
            val maker = d.makerFeeRate
            val taker = d.takerFeeRate
            if (maker != null && taker != null) {
                TradingFeeSchedule(
                    makerRate = maker,
                    takerRate = taker,
                    source = d.feeSource.ifBlank { "RESEARCH_HANDOFF" }
                )
            } else null
        }
        val economics = tradeEconomics.evaluate(
            TradeEconomicsInput(
                symbol = decision.symbol,
                strategyId = directive?.strategyId
                    ?.takeUnless { it.equals("HANDOFF_FILTERS", ignoreCase = true) }
                    ?: settings.strategyMode.name,
                notionalQuote = finalQuote,
                entryPrice = entryReference,
                targetPrice = targetPrice,
                stopPrice = stopPrice,
                orderType = finalOrderType,
                postOnly = finalPostOnly,
                ticker = ticker,
                orderBook = orderBook,
                recentTrades = trades,
                feeSchedule = feeSchedule ?: directiveFeeSchedule,
                publishRuntime = true,
                externalDecisionCostQuote = BigDecimal.ZERO,
                safetyMarginRate = BigDecimal("0.0025")
            )
        )
        record(
            "entry_economics",
            decision.symbol,
            settings,
            mode,
            requestedQuote,
            finalQuote,
            economics.netExpectedValueRate,
            finalOrderType.name,
            if (economics.allowed) "positive_net_ev" else "non_positive_net_ev",
            sizeBand(requestedQuote),
            "",
            if (economics.allowed) "normal" else "blocked",
            !economics.allowed,
            economics.reason,
            if (economics.allowed) "INFO" else "WARN"
        )
        if (!economics.allowed) {
            val reason = "M5 trade economics blocked entry: ${economics.reason}"
            return AdvancedEntryPlan(false, finalQuote, finalOrderType, finalLimitOrTrigger, BigDecimal.ZERO, protection.level, reason)
        }
'''
    advanced = replace_once(advanced, old_cost, new_cost, "Replace old advanced cost gate")

    advanced = advanced.replace(
        'optimizedOrder.reason + " | " + costGate.second',
        'optimizedOrder.reason + " | " + economics.reason'
    )
    advanced = advanced.replace(
        '| ${costGate.second}"',
        '| ${economics.reason}"'
    )
    if "costGate.second" in advanced:
        fail("Stale costGate.second remains in AdvancedExecutionCoordinator")

    start = advanced.find("    private fun roundTripCostGate(")
    end = advanced.find("    suspend fun diagnostics(", start)
    if start < 0 or end < 0:
        fail("Could not locate obsolete AdvancedExecutionCoordinator cost helper block")
    advanced = advanced[:start] + advanced[end:]

    advanced_path.write_text(advanced, encoding="utf-8")
    print("PATCH |", advanced_path.relative_to(repo))

    # 2. Research handoff cost gate delegates to the SAME EV engine.
    research_path = repo / "app/src/main/java/com/ksp/cryptobot/research/ResearchHandoffCostRiskEngine.kt"
    research = research_path.read_text(encoding="utf-8")

    research = replace_once(
        research,
        "import com.ksp.cryptobot.exchange.TradingFeeSchedule\n",
        "import com.ksp.cryptobot.exchange.TradingFeeSchedule\nimport com.ksp.cryptobot.execution.TradeEconomicsEngine\nimport com.ksp.cryptobot.execution.TradeEconomicsInput\n",
        "Research economics imports"
    )

    research = replace_once(
        research,
        '''class ResearchHandoffCostRiskEngine {
    companion object {''',
        '''class ResearchHandoffCostRiskEngine {
    private val tradeEconomics = TradeEconomicsEngine()

    companion object {''',
        "Research economics engine property"
    )

    cost_start = research.find("    fun costGate(")
    risk_start = research.find("    fun riskGate(", cost_start)
    if cost_start < 0 or risk_start < 0:
        fail("Could not locate research costGate/riskGate boundary")

    new_research_cost = r'''    fun costGate(
        candidate: HandoffTradeCandidate,
        ticker: MarketTicker,
        orderBook: OrderBookSnapshot?,
        recentTrades: List<TradeEntity>,
        feeSchedule: TradingFeeSchedule?,
        symbolInfo: ExchangeSymbolInfo?,
        probeNotionalQuote: BigDecimal,
        safetyMarginPct: BigDecimal
    ): HandoffCostAssessment {
        val rawEntry = candidate.entryPlan.intendedPrice ?: candidate.entryPlan.triggerPrice ?: ticker.ask
        val rawTarget = candidate.targets.firstOrNull()?.price
        val rawStop = candidate.invalidation.stopPrice

        val entry = normalizeEntryPrice(candidate, rawEntry, symbolInfo)
        val target = rawTarget?.let { normalizeTargetPrice(it, symbolInfo) }
        val stop = rawStop?.let { normalizeStopPrice(it, symbolInfo) }

        if (candidate.sideIntent != HandoffSideIntent.LONG_ENTRY ||
            entry <= BigDecimal.ZERO ||
            target == null || target <= entry ||
            stop == null || stop <= BigDecimal.ZERO || stop >= entry
        ) {
            return HandoffCostAssessment(
                false,
                TAKER_FALLBACK,
                TAKER_FALLBACK,
                spreadPct(ticker),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "M5 economics requires LONG_ENTRY with entry, target above entry, and protective stop below entry."
            )
        }

        val orderType = candidate.entryPlan.preferredOrderType
            ?: com.ksp.cryptobot.core.OrderType.LIMIT
        val postOnly = candidate.entryPlan.postOnlyPreferred &&
            orderType == com.ksp.cryptobot.core.OrderType.LIMIT
        val probe = probeNotionalQuote.coerceAtLeast(PRACTICAL_EUR_COST_MIN)
        val safetyRate = safetyMarginPct
            .divide(BigDecimal("100"), 12, RoundingMode.HALF_UP)
            .coerceIn(BigDecimal.ZERO, BigDecimal("0.05"))

        val economics = tradeEconomics.evaluate(
            TradeEconomicsInput(
                symbol = candidate.symbol,
                strategyId = candidate.strategyId,
                notionalQuote = probe,
                entryPrice = entry,
                targetPrice = target,
                stopPrice = stop,
                orderType = orderType,
                postOnly = postOnly,
                ticker = ticker,
                orderBook = orderBook,
                recentTrades = recentTrades,
                feeSchedule = feeSchedule,
                publishRuntime = false,
                externalDecisionCostQuote = BigDecimal.ZERO,
                safetyMarginRate = safetyRate
            )
        )

        return HandoffCostAssessment(
            allowed = economics.allowed,
            entryFeeRate = economics.entryFeeRate,
            exitFeeRate = economics.exitFeeRate,
            spreadPct = economics.spreadRate.multiply(BigDecimal("100")),
            entrySlippageQuote = economics.entrySlippageQuote,
            exitSlippageQuote = economics.expectedExitSlippageQuote,
            expectedGrossProfitQuote = economics.expectedWinQuote,
            expectedTotalCostQuote = economics.totalExpectedCostQuote,
            safetyMarginQuote = economics.safetyReserveQuote,
            expectedNetProfitQuote = economics.netExpectedValueQuote,
            reason = economics.reason
        )
    }

'''
    research = research[:cost_start] + new_research_cost + research[risk_start:]
    research_path.write_text(research, encoding="utf-8")
    print("PATCH |", research_path.relative_to(repo))

    # 3. BotController fetches live fee tier before final economics gate.
    controller_path = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    controller = controller_path.read_text(encoding="utf-8")

    old_call = '''            val advancedOrderBook = runCatching { exchange.getOrderBook(ticker.symbol, 40) }.getOrNull()
            val advancedPlan = advancedExecution.prepareEntry(
                settings = settings, ticker = ticker, decision = decision, requestedQuote = targetNotional,
                orderBook = advancedOrderBook, mode = if (settings.mode == BotMode.PAPER) "PAPER" else "LIVE", currentUseMarket = useMarketOrder
            )
'''
    new_call = '''            val advancedOrderBook = runCatching { exchange.getOrderBook(ticker.symbol, 40) }.getOrNull()
            val advancedFeeSchedule = runCatching { exchange.getTradingFeeSchedule(ticker.symbol) }.getOrNull()
            val advancedPlan = advancedExecution.prepareEntry(
                settings = settings,
                ticker = ticker,
                decision = decision,
                requestedQuote = targetNotional,
                orderBook = advancedOrderBook,
                mode = if (settings.mode == BotMode.PAPER) "PAPER" else "LIVE",
                currentUseMarket = useMarketOrder,
                feeSchedule = advancedFeeSchedule
            )
'''
    controller = replace_once(controller, old_call, new_call, "BotController final fee/economics handoff")

    # M4 correction: all spot BUYs are entries, regardless of strategy-purpose text.
    old_m4_gate = '''        if (settings.mode == BotMode.LIVE_AUTO &&
            settings.exchangeProvider == ExchangeProvider.KRAKEN &&
            request.side == OrderSide.BUY &&
            request.purpose.equals("ENTRY", ignoreCase = true)
        ) {'''
    new_m4_gate = '''        if (settings.mode == BotMode.LIVE_AUTO &&
            settings.exchangeProvider == ExchangeProvider.KRAKEN &&
            request.side == OrderSide.BUY
        ) {'''
    controller = replace_once(controller, old_m4_gate, new_m4_gate, "Fix M4 BUY entry-state gate purpose bypass")

    controller_path.write_text(controller, encoding="utf-8")
    print("PATCH |", controller_path.relative_to(repo))

    # 4. Kraken duplicate BUY defense also covers research/handoff BUYs.
    exchange_path = repo / "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt"
    exchange = exchange_path.read_text(encoding="utf-8")
    exchange = replace_once(
        exchange,
        '''        if (request.side == OrderSide.BUY && request.purpose.equals("ENTRY", ignoreCase = true)) {''',
        '''        if (request.side == OrderSide.BUY) {''',
        "Fix Kraken duplicate BUY purpose bypass"
    )
    exchange_path.write_text(exchange, encoding="utf-8")
    print("PATCH |", exchange_path.relative_to(repo))

    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    all_changed = changed | untracked
    allowed = {
        NEW_ENGINE,
        NEW_TEST,
        "app/src/main/java/com/ksp/cryptobot/execution/AdvancedExecutionCoordinator.kt",
        "app/src/main/java/com/ksp/cryptobot/research/ResearchHandoffCostRiskEngine.kt",
        "app/src/main/java/com/ksp/cryptobot/core/BotController.kt",
        "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt",
    }
    unexpected = sorted(all_changed - allowed)
    missing = sorted(allowed - all_changed)
    if unexpected:
        fail("Unexpected M5 app changes: " + ", ".join(unexpected))
    if missing:
        fail("Expected M5 app changes missing: " + ", ".join(missing))

    print("PASS | M5 patch changed only approved economics/integration files.")

if __name__ == "__main__":
    main()
