package com.ksp.cryptobot.backtest

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.strategy.MarketRegimeDetector
import com.ksp.cryptobot.strategy.StrategyTruthRegistry
import com.ksp.cryptobot.strategy.StrategyTruthRules
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * M18 truth-aligned historical validator.
 *
 * Critical invariants:
 * 1. Unsupported named strategies cannot pass a proxy backtest.
 * 2. Entry logic is the same StrategyTruthRules used by live selection.
 * 3. A close-generated signal enters on the NEXT bar open (no same-close look-ahead).
 * 4. If stop and target are both touched inside one OHLC bar, stop is assumed first.
 * 5. Baseline maker fees are charged on both entry and exit so raw gross returns
 *    cannot masquerade as strategy edge. M5/M20 remain the production economics gate.
 */
class BacktestEngine(
    private val regimeDetector: MarketRegimeDetector = MarketRegimeDetector()
) {
    companion object {
        // Current Kraken Tier-1 maker baseline used only for strategy validation.
        // Production economics still query/use the execution economics stack.
        private val BASELINE_MAKER_FEE_PER_SIDE_PERCENT = BigDecimal("0.40")
    }

    fun run(
        symbol: String,
        timeframe: Timeframe,
        strategy: StrategyMode,
        candles: List<Candle>,
        settings: BotSettings
    ): BacktestReport {
        if (strategy == StrategyMode.AUTO) {
            return truthBlocked(
                symbol, strategy, timeframe,
                "AUTO is a selector. A single-strategy backtest cannot truthfully backtest AUTO."
            )
        }

        val spec = StrategyTruthRegistry.spec(strategy)
            ?: return truthBlocked(symbol, strategy, timeframe, "No M18 strategy truth specification exists.")

        if (!spec.liveSelectable) {
            return truthBlocked(symbol, strategy, timeframe, StrategyTruthRegistry.truthBlockedReason(strategy))
        }
        if (!spec.singleTimeframeBacktestable) {
            return truthBlocked(
                symbol, strategy, timeframe,
                "TRUTH_BLOCKED ${strategy.name}: live implementation requires ${spec.requiredInputs.joinToString(", ")}; the existing run() API supplies one timeframe only."
            )
        }
        if (spec.primaryTimeframe != null && timeframe != spec.primaryTimeframe) {
            return truthBlocked(
                symbol, strategy, timeframe,
                "TRUTH_BLOCKED ${strategy.name}: canonical M18 implementation requires ${spec.primaryTimeframe}, but backtest requested $timeframe."
            )
        }
        if (candles.size < 90) {
            return BacktestReport(
                symbol, strategy, timeframe, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                false,
                "M18 truth backtest: not enough candles (${candles.size}/90)."
            )
        }

        val trades = mutableListOf<BacktestTrade>()
        var position: OpenBacktestPosition? = null
        var equity = BigDecimal("1000.00")
        var peakEquity = equity
        var maxDd = BigDecimal.ZERO

        // Keep one bar after every signal for next-open execution.
        for (i in 60 until candles.lastIndex) {
            val signalWindow = candles.subList(0, i + 1)
            val current = candles[i]
            val next = candles[i + 1]

            val open = position
            if (open == null) {
                val rule = StrategyTruthRules.evaluate(strategy, signalWindow, settings)
                if (!rule.enoughData || !rule.entry) continue

                val regime = regimeDetector.detect(
                    symbol,
                    mapOf(timeframe to signalWindow)
                )
                val regimeBlocked = regime.regime in spec.unsuitableRegimes
                val regimePreferred =
                    spec.suitableRegimes.isEmpty() || regime.regime in spec.suitableRegimes
                if (regimeBlocked || !regimePreferred) continue

                val entryPrice = next.open.takeIf { it > BigDecimal.ZERO } ?: next.close
                if (entryPrice <= BigDecimal.ZERO) continue

                position = OpenBacktestPosition(
                    entryEpochMs = next.openTimeEpochMs,
                    entryPrice = entryPrice,
                    takeProfitPercent = rule.takeProfitPercent,
                    stopLossPercent = rule.stopLossPercent,
                    entrySignalReason = rule.reason
                )
                continue
            }

            // The current loop bar may be the entry bar or any later completed bar.
            val stopPrice = open.entryPrice.multiply(
                BigDecimal.ONE.subtract(
                    open.stopLossPercent.divide(BigDecimal("100"), 12, RoundingMode.HALF_UP)
                )
            )
            val targetPrice = open.entryPrice.multiply(
                BigDecimal.ONE.add(
                    open.takeProfitPercent.divide(BigDecimal("100"), 12, RoundingMode.HALF_UP)
                )
            )

            val stopTouched = current.low <= stopPrice
            val targetTouched = current.high >= targetPrice

            val exit: ExitDecision? = when {
                stopTouched && targetTouched ->
                    ExitDecision(
                        stopPrice,
                        "SL (same-bar TP+SL ambiguity resolved conservatively to stop)",
                        current.openTimeEpochMs
                    )
                stopTouched ->
                    ExitDecision(stopPrice, "SL", current.openTimeEpochMs)
                targetTouched ->
                    ExitDecision(targetPrice, "TP", current.openTimeEpochMs)
                StrategyTruthRules.shouldExit(strategy, signalWindow, open.entryPrice, settings) -> {
                    val exitAtNextOpen = next.open.takeIf { it > BigDecimal.ZERO } ?: next.close
                    ExitDecision(
                        exitAtNextOpen,
                        "Strategy invalidation at next open",
                        next.openTimeEpochMs
                    )
                }
                else -> null
            }

            if (exit != null) {
                val grossPct = percentChange(open.entryPrice, exit.price)
                val feePct = BASELINE_MAKER_FEE_PER_SIDE_PERCENT.multiply(BigDecimal("2"))
                val netPct = grossPct.subtract(feePct).setScale(4, RoundingMode.HALF_UP)

                trades += BacktestTrade(
                    symbol = symbol,
                    strategy = strategy,
                    entryEpochMs = open.entryEpochMs,
                    exitEpochMs = exit.epochMs,
                    entryPrice = open.entryPrice,
                    exitPrice = exit.price,
                    side = OrderSide.BUY,
                    pnlPercent = netPct,
                    exitReason = "${exit.reason}; gross=${grossPct.setScale(3, RoundingMode.HALF_UP)}%; baselineFees=${feePct.setScale(2, RoundingMode.HALF_UP)}%"
                )

                equity = equity.multiply(
                    BigDecimal.ONE.add(netPct.divide(BigDecimal("100"), 12, RoundingMode.HALF_UP))
                ).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP)

                if (equity > peakEquity) peakEquity = equity
                if (peakEquity > BigDecimal.ZERO) {
                    val dd = peakEquity.subtract(equity)
                        .divide(peakEquity, 12, RoundingMode.HALF_UP)
                        .multiply(BigDecimal("100"))
                    if (dd > maxDd) maxDd = dd
                }
                position = null
            }
        }

        val wins = trades.filter { it.pnlPercent > BigDecimal.ZERO }
        val losses = trades.filter { it.pnlPercent < BigDecimal.ZERO }
        val winRate = if (trades.isNotEmpty()) {
            BigDecimal(wins.size)
                .multiply(BigDecimal("100"))
                .divide(BigDecimal(trades.size), 2, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        val grossProfit = wins.fold(BigDecimal.ZERO) { a, t -> a.add(t.pnlPercent) }
        val grossLoss = losses.fold(BigDecimal.ZERO) { a, t -> a.add(t.pnlPercent.abs()) }
        val profitFactor = when {
            grossLoss > BigDecimal.ZERO ->
                grossProfit.divide(grossLoss, 3, RoundingMode.HALF_UP)
            grossProfit > BigDecimal.ZERO ->
                BigDecimal("9.999")
            else -> BigDecimal.ZERO
        }

        val net = equity.subtract(BigDecimal("1000.00"))
            .divide(BigDecimal("1000.00"), 12, RoundingMode.HALF_UP)
            .multiply(BigDecimal("100"))
            .setScale(3, RoundingMode.HALF_UP)

        val passed =
            trades.size >= settings.requiredPaperTrades &&
            winRate >= BigDecimal(settings.requiredPaperWinRatePercent) &&
            profitFactor >= settings.requiredProfitFactor &&
            maxDd <= settings.maxDrawdownPercent &&
            net > BigDecimal.ZERO

        return BacktestReport(
            symbol = symbol,
            strategy = strategy,
            timeframe = timeframe,
            trades = trades.size,
            winRatePercent = winRate,
            profitFactor = profitFactor,
            maxDrawdownPercent = maxDd.setScale(2, RoundingMode.HALF_UP),
            netReturnPercent = net,
            passedLiveGate = passed,
            summary = buildString {
                append("M18 TRUTH_BACKTEST ${spec.canonicalName}; ")
                append("signals use shared live StrategyTruthRules; entries execute next-bar open; ")
                append("same-bar TP/SL resolves to SL; baseline maker fees=")
                append(BASELINE_MAKER_FEE_PER_SIDE_PERCENT)
                append("%/side. trades=${trades.size}, winRate=$winRate%, PF=$profitFactor, ")
                append("maxDD=${maxDd.setScale(2, RoundingMode.HALF_UP)}%, net=$net%, passed=$passed. ")
                append("M5/M20 production economics remain final.")
            }
        )
    }

    private data class ExitDecision(
        val price: BigDecimal,
        val reason: String,
        val epochMs: Long
    )

    private data class OpenBacktestPosition(
        val entryEpochMs: Long,
        val entryPrice: BigDecimal,
        val takeProfitPercent: BigDecimal,
        val stopLossPercent: BigDecimal,
        val entrySignalReason: String
    )

    private fun truthBlocked(
        symbol: String,
        strategy: StrategyMode,
        timeframe: Timeframe,
        reason: String
    ) = BacktestReport(
        symbol = symbol,
        strategy = strategy,
        timeframe = timeframe,
        trades = 0,
        winRatePercent = BigDecimal.ZERO,
        profitFactor = BigDecimal.ZERO,
        maxDrawdownPercent = BigDecimal.ZERO,
        netReturnPercent = BigDecimal.ZERO,
        passedLiveGate = false,
        summary = "M18 $reason"
    )

    private fun percentChange(first: BigDecimal, last: BigDecimal): BigDecimal {
        if (first <= BigDecimal.ZERO) return BigDecimal.ZERO
        return last.subtract(first)
            .divide(first, 12, RoundingMode.HALF_UP)
            .multiply(BigDecimal("100"))
    }
}
