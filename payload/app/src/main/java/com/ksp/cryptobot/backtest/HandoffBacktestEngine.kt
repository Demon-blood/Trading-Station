package com.ksp.cryptobot.backtest

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.risk.CostAwareRiskInput
import com.ksp.cryptobot.risk.CostAwareSpotRiskSizer
import com.ksp.cryptobot.risk.KrakenCostFallback20260822
import com.ksp.cryptobot.strategy.structure.KoroushCtsReferenceStrategy
import com.ksp.cryptobot.strategy.turtle.TurtleDailyBar
import com.ksp.cryptobot.strategy.turtle.TurtleSpotSafeStrategy
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset

/** Dedicated no-look-ahead research adapters. Never self-promotes to LIVE. */
object HandoffBacktestEngine {
    private val initialEquity = BigDecimal("1000.00")
    private val slippageStress = BigDecimal("0.0020") // CTS hypothesis: 0.20% round-trip stress.

    fun run(symbol: String, timeframe: Timeframe, strategy: StrategyMode, candles: List<Candle>, settings: BotSettings): BacktestReport =
        when (strategy) {
            StrategyMode.CTS_TURTLE_SPOT_SAFE -> runTurtle(symbol, timeframe, candles, settings)
            StrategyMode.CTS_KAK_CLOSE_BREAK_RETEST_V1 -> runKak(symbol, timeframe, candles)
            else -> error("HandoffBacktestEngine only handles handoff strategy modes.")
        }

    private fun runTurtle(symbol: String, timeframe: Timeframe, raw: List<Candle>, settings: BotSettings): BacktestReport {
        if (timeframe != Timeframe.H4) return report(symbol, StrategyMode.CTS_TURTLE_SPOT_SAFE, timeframe, emptyList(), BigDecimal.ZERO,
            "CTS_TURTLE_SPOT_SAFE requires H4 input so completed UTC daily bars can be reconstructed.")
        val candles = if (raw.size > 1) raw.dropLast(1) else emptyList() // Kraken final REST row may be uncommitted.
        if (candles.size < 360) return report(symbol, StrategyMode.CTS_TURTLE_SPOT_SAFE, timeframe, emptyList(), BigDecimal.ZERO,
            "Need >=360 committed H4 candles for a 55-day channel test; have ${candles.size}.")

        val turtle = TurtleSpotSafeStrategy()
        val trades = mutableListOf<BacktestTrade>()
        var equity = initialEquity
        var peak = equity
        var maxDd = BigDecimal.ZERO
        var inTrade = false
        var entry = BigDecimal.ZERO
        var entryTime = 0L
        var qty = BigDecimal.ZERO
        var stop = BigDecimal.ZERO
        var entryFee = BigDecimal.ZERO

        for (i in candles.indices) {
            if (i < 330) continue
            val current = candles[i]
            val currentDay = Instant.ofEpochMilli(current.openTimeEpochMs).atZone(ZoneOffset.UTC).toLocalDate()
            val daily = aggregateCompletedDaysBefore(candles.subList(0, i), currentDay)
            if (daily.size < 56) continue
            val n = turtle.wilderN(daily, 20)
            if (n <= BigDecimal.ZERO) continue
            val entryChannel = daily.takeLast(55).maxOf { it.high }
            val exitChannel = daily.takeLast(20).minOf { it.low }

            if (!inTrade && current.high > entryChannel) {
                val fill = if (current.open > entryChannel) current.open else entryChannel
                val technicalStop = fill.subtract(n.multiply(BigDecimal("2"))).max(BigDecimal("0.00000001"))
                val risk = CostAwareSpotRiskSizer.size(
                    CostAwareRiskInput(
                        equity, BigDecimal("0.005"), fill, technicalStop,
                        KrakenCostFallback20260822.conservativeEntryFeeRate,
                        KrakenCostFallback20260822.conservativeExitFeeRate,
                        slippageStress,
                        settings.effectiveMaxPositionFor(symbol),
                        equity
                    )
                )
                if (!risk.allowed || risk.quantity <= BigDecimal.ZERO) continue
                inTrade = true
                entry = fill
                entryTime = current.openTimeEpochMs
                qty = risk.quantity
                stop = technicalStop
                entryFee = qty.multiply(entry).multiply(KrakenCostFallback20260822.conservativeEntryFeeRate)
                continue
            }

            if (inTrade) {
                var exit: BigDecimal? = null
                var reason = ""
                if (current.low <= stop) {
                    exit = if (current.open < stop) current.open else stop
                    reason = "2N protective stop"
                } else if (current.low < exitChannel) {
                    exit = if (current.open < exitChannel) current.open else exitChannel
                    reason = "20-day low trend exit"
                }
                if (exit != null && exit > BigDecimal.ZERO) {
                    val exitFee = qty.multiply(exit).multiply(KrakenCostFallback20260822.conservativeExitFeeRate)
                    val slippage = qty.multiply(entry).multiply(slippageStress)
                    val pnl = qty.multiply(exit.subtract(entry)).subtract(entryFee).subtract(exitFee).subtract(slippage)
                    val invested = qty.multiply(entry).max(BigDecimal("0.00000001"))
                    val pnlPct = pnl.divide(invested, 10, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
                    equity = equity.add(pnl)
                    peak = peak.max(equity)
                    val dd = peak.subtract(equity).max(BigDecimal.ZERO).divide(peak, 10, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
                    maxDd = maxDd.max(dd)
                    trades += BacktestTrade(symbol, StrategyMode.CTS_TURTLE_SPOT_SAFE, entryTime, current.openTimeEpochMs,
                        entry, exit, OrderSide.BUY, pnlPct.setScale(4, RoundingMode.HALF_UP),
                        "$reason; feeFallback=${KrakenCostFallback20260822.sourceLabel}; slippageStress=0.20% roundtrip")
                    inTrade = false
                    qty = BigDecimal.ZERO
                    entryFee = BigDecimal.ZERO
                }
            }
        }
        return report(symbol, StrategyMode.CTS_TURTLE_SPOT_SAFE, timeframe, trades, maxDd,
            "CTS Turtle spot-safe System-2 adaptation: 55-day breakout, 20-day low exit, 2N initial stop, no pyramiding, 0.5% CTS risk, conservative fees, 0.20% CTS slippage stress. Not historical Turtle portfolio sizing.")
    }

    private fun runKak(symbol: String, timeframe: Timeframe, raw: List<Candle>): BacktestReport {
        if (timeframe != Timeframe.H1) return report(symbol, StrategyMode.CTS_KAK_CLOSE_BREAK_RETEST_V1, timeframe, emptyList(), BigDecimal.ZERO,
            "CTS_KAK_CLOSE_BREAK_RETEST_V1 requires H1 input. This is a CTS-defined profile, not a source-exact Koroush strategy.")
        val candles = if (raw.size > 1) raw.dropLast(1) else emptyList()
        if (candles.size < 140) return report(symbol, StrategyMode.CTS_KAK_CLOSE_BREAK_RETEST_V1, timeframe, emptyList(), BigDecimal.ZERO,
            "Need >=140 committed H1 candles; have ${candles.size}.")
        val model = KoroushCtsReferenceStrategy()
        val trades = mutableListOf<BacktestTrade>()
        var inTrade = false
        var entry = BigDecimal.ZERO
        var invalidation = BigDecimal.ZERO
        var target = BigDecimal.ZERO
        var entryTime = 0L
        var equity = initialEquity
        var peak = equity
        var maxDd = BigDecimal.ZERO
        for (i in 110 until candles.size) {
            val current = candles[i]
            if (!inTrade) {
                val ticker = MarketTicker(symbol, current.close, current.close, current.close, current.volume, BigDecimal.ZERO)
                val e = model.evaluate(ticker, candles.subList(0, i + 1))
                if (e.action == SignalAction.BUY && e.invalidation > BigDecimal.ZERO && e.invalidation < current.close) {
                    inTrade = true; entry = current.close; invalidation = e.invalidation; target = e.target; entryTime = current.openTimeEpochMs
                }
            } else {
                val exit = when {
                    current.low <= invalidation -> if (current.open < invalidation) current.open else invalidation
                    target > BigDecimal.ZERO && current.high >= target -> if (current.open > target) current.open else target
                    else -> null
                }
                if (exit != null) {
                    val grossPct = exit.subtract(entry).divide(entry, 10, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
                    val netPct = grossPct.subtract(BigDecimal("1.80")) // conservative 1.6% fees + 0.2% slippage stress
                    equity = equity.multiply(BigDecimal.ONE.add(netPct.divide(BigDecimal("100"), 10, RoundingMode.HALF_UP)))
                    peak = peak.max(equity)
                    maxDd = maxDd.max(peak.subtract(equity).max(BigDecimal.ZERO).divide(peak, 10, RoundingMode.HALF_UP).multiply(BigDecimal("100")))
                    trades += BacktestTrade(symbol, StrategyMode.CTS_KAK_CLOSE_BREAK_RETEST_V1, entryTime, current.openTimeEpochMs,
                        entry, exit, OrderSide.BUY, netPct.setScale(4, RoundingMode.HALF_UP),
                        "CTS_REFERENCE invalidation/2R target; 1.80% conservative cost stress")
                    inTrade = false
                }
            }
        }
        return report(symbol, StrategyMode.CTS_KAK_CLOSE_BREAK_RETEST_V1, timeframe, trades, maxDd,
            "CTS_REFERENCE research backtest. 7/30/100 framework inspiration is source-derived; exact fan/break/retest rules are CTS hypotheses. passedLiveGate is intentionally false.")
    }

    private fun aggregateCompletedDaysBefore(h4: List<Candle>, cutoff: java.time.LocalDate): List<TurtleDailyBar> =
        h4.groupBy { Instant.ofEpochMilli(it.openTimeEpochMs).atZone(ZoneOffset.UTC).toLocalDate() }
            .filterKeys { it < cutoff }.toSortedMap().map { (day, rows) ->
                val o = rows.sortedBy { it.openTimeEpochMs }
                TurtleDailyBar(day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), o.first().open,
                    o.maxOf { it.high }, o.minOf { it.low }, o.last().close,
                    o.fold(BigDecimal.ZERO) { a,c -> a+c.volume })
            }

    private fun report(symbol: String, strategy: StrategyMode, timeframe: Timeframe, trades: List<BacktestTrade>, maxDd: BigDecimal, note: String): BacktestReport {
        val wins = trades.filter { it.pnlPercent > BigDecimal.ZERO }
        val losses = trades.filter { it.pnlPercent < BigDecimal.ZERO }
        val winRate = if (trades.isEmpty()) BigDecimal.ZERO else BigDecimal(wins.size).multiply(BigDecimal("100")).divide(BigDecimal(trades.size),2,RoundingMode.HALF_UP)
        val gp = wins.fold(BigDecimal.ZERO){a,t->a+t.pnlPercent}; val gl = losses.fold(BigDecimal.ZERO){a,t->a+t.pnlPercent.abs()}
        val pf = if (gl > BigDecimal.ZERO) gp.divide(gl,4,RoundingMode.HALF_UP) else if (gp > BigDecimal.ZERO) BigDecimal("99") else BigDecimal.ZERO
        val netPct = trades.fold(BigDecimal.ZERO){a,t->a+t.pnlPercent}.setScale(4,RoundingMode.HALF_UP)
        return BacktestReport(symbol,strategy,timeframe,trades.size,winRate,pf,maxDd.setScale(4,RoundingMode.HALF_UP),netPct,false,
            "$note trades=${trades.size}, winRate=$winRate%, PF=$pf, maxDD=${maxDd.setScale(4,RoundingMode.HALF_UP)}%, summedNetTradePct=$netPct%. Promotion evidence is required separately.")
    }
}
