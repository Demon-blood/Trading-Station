package com.ksp.cryptobot.strategy.turtle

import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.SignalAction
import com.ksp.cryptobot.core.StrategyCandidate
import com.ksp.cryptobot.core.StrategyMode
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneOffset

enum class TurtleSystem { SYSTEM_1, SYSTEM_2 }
enum class TurtleSignalType { ENTRY_LONG, EXIT_LONG, HOLD, INSUFFICIENT_DATA, BLOCKED }

data class TurtleDailyBar(
    val dayEpochMs: Long,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal
)

data class TurtleEvaluation(
    val type: TurtleSignalType,
    val system: TurtleSystem,
    val entryBreakoutPrice: BigDecimal,
    val trendExitPrice: BigDecimal,
    val n: BigDecimal,
    val initialStopPrice: BigDecimal,
    val ruleProfileId: String,
    val reason: String
) {
    val action: SignalAction
        get() = when (type) {
            TurtleSignalType.ENTRY_LONG -> SignalAction.BUY
            TurtleSignalType.EXIT_LONG -> SignalAction.SELL
            TurtleSignalType.BLOCKED -> SignalAction.AVOID
            else -> SignalAction.WAIT
        }
}

object TurtleDailyAggregation {
    /**
     * Kraken REST marks its final OHLC row as current/uncommitted.
     * The caller may pass that row; this function first drops it, then refuses to form
     * a "daily" bar from the final partial UTC day.
     */
    fun completedDailyFromH4(h4: List<Candle>): List<TurtleDailyBar> {
        if (h4.size < 2) return emptyList()
        val committedH4 = h4.dropLast(1)
        if (committedH4.isEmpty()) return emptyList()
        val finalDay = Instant.ofEpochMilli(committedH4.maxOf { it.openTimeEpochMs })
            .atZone(ZoneOffset.UTC).toLocalDate()
        return committedH4
            .groupBy { Instant.ofEpochMilli(it.openTimeEpochMs).atZone(ZoneOffset.UTC).toLocalDate() }
            .filterKeys { it < finalDay }
            .toSortedMap()
            .map { (day, rows) ->
                val ordered = rows.sortedBy { it.openTimeEpochMs }
                TurtleDailyBar(
                    day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                    ordered.first().open,
                    ordered.maxOf { it.high },
                    ordered.minOf { it.low },
                    ordered.last().close,
                    ordered.fold(BigDecimal.ZERO) { a, c -> a + c.volume }
                )
            }
    }
}

class TurtleSpotSafeStrategy(
    private val defaultSystem: TurtleSystem = TurtleSystem.SYSTEM_2
) {
    fun evaluate(
        ticker: MarketTicker,
        h4Candles: List<Candle>,
        hasOpenPosition: Boolean,
        liquidityAllowed: Boolean,
        previousSystem1BreakoutWouldWin: Boolean? = null,
        system: TurtleSystem = defaultSystem
    ): TurtleEvaluation {
        val daily = TurtleDailyAggregation.completedDailyFromH4(h4Candles)
        val need = if (system == TurtleSystem.SYSTEM_2) 56 else 56 // System-1 still needs 55D failsafe context.
        if (daily.size < need) {
            return empty(system, TurtleSignalType.INSUFFICIENT_DATA, "Need at least $need completed daily bars; have ${daily.size}.")
        }
        if (!liquidityAllowed && !hasOpenPosition) {
            return empty(system, TurtleSignalType.BLOCKED, "CTS liquidity gate failed; new Turtle entry blocked.")
        }

        val n = wilderN(daily, 20)
        val current = ticker.lastPrice
        val high55 = daily.takeLast(55).maxOf { it.high }
        val low20 = daily.takeLast(20).minOf { it.low }
        val high20 = daily.takeLast(20).maxOf { it.high }
        val low10 = daily.takeLast(10).minOf { it.low }

        val entry = when (system) {
            TurtleSystem.SYSTEM_2 -> high55
            TurtleSystem.SYSTEM_1 -> if (previousSystem1BreakoutWouldWin == true) high55 else high20
        }
        val exit = when (system) {
            TurtleSystem.SYSTEM_2 -> low20
            TurtleSystem.SYSTEM_1 -> low10
        }
        val stop = if (n > BigDecimal.ZERO) current.subtract(n.multiply(BigDecimal("2"))).max(BigDecimal.ZERO) else BigDecimal.ZERO

        if (hasOpenPosition && current < exit) {
            return TurtleEvaluation(
                TurtleSignalType.EXIT_LONG, system, entry, exit, n, stop,
                profile(system),
                "Source trend exit satisfied: current=$current < prior completed-day exit channel=$exit."
            )
        }
        if (!hasOpenPosition && current > entry) {
            if (system == TurtleSystem.SYSTEM_1 && previousSystem1BreakoutWouldWin == null) {
                return TurtleEvaluation(
                    TurtleSignalType.BLOCKED, system, entry, exit, n, stop,
                    profile(system),
                    "System-1 previous-breakout winner filter state is unknown; exact automatic System-1 entry is blocked rather than guessed."
                )
            }
            return TurtleEvaluation(
                TurtleSignalType.ENTRY_LONG, system, entry, exit, n, stop,
                profile(system),
                "Turtle breakout satisfied against completed daily history. CTS adaptation remains long/flat, no leverage, no pyramiding."
            )
        }
        return TurtleEvaluation(
            TurtleSignalType.HOLD, system, entry, exit, n, stop,
            profile(system),
            "No Turtle entry/exit trigger. current=$current entry=$entry exit=$exit N=$n."
        )
    }

    fun candidate(
        ticker: MarketTicker,
        h4Candles: List<Candle>,
        hasOpenPosition: Boolean,
        liquidityAllowed: Boolean
    ): Pair<StrategyCandidate, TurtleEvaluation> {
        val e = evaluate(ticker, h4Candles, hasOpenPosition, liquidityAllowed, system = TurtleSystem.SYSTEM_2)
        val stopPct = if (ticker.lastPrice > BigDecimal.ZERO && e.initialStopPrice > BigDecimal.ZERO) {
            ticker.lastPrice.subtract(e.initialStopPrice)
                .divide(ticker.lastPrice, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal("100")).max(BigDecimal("0.10"))
        } else BigDecimal("1.00")
        // Binary rule-satisfaction score, not a calibrated probability.
        val score = when (e.type) {
            TurtleSignalType.ENTRY_LONG -> 100
            TurtleSignalType.EXIT_LONG -> 0
            TurtleSignalType.BLOCKED -> 0
            TurtleSignalType.HOLD -> 50
            TurtleSignalType.INSUFFICIENT_DATA -> 0
        }
        val action = e.action
        return StrategyCandidate(
            mode = StrategyMode.valueOf("CTS_TURTLE_SPOT_SAFE"),
            score = score,
            action = action,
            reason = "CTS_TURTLE_SPOT_SAFE ${e.ruleProfileId}: ${e.reason} Signal score is rule satisfaction, not win probability.",
            // Turtle has a channel exit, not a fixed profit target. Large value prevents generic TP from being the primary exit.
            takeProfitPercent = BigDecimal("1000"),
            stopLossPercent = stopPct
        ) to e
    }

    private fun profile(system: TurtleSystem) =
        if (system == TurtleSystem.SYSTEM_2) "CTS_TURTLE_SPOT_SAFE_SYSTEM2_V1" else "TURTLE_ORIGINAL_SYSTEM1_RESEARCH"

    private fun empty(system: TurtleSystem, type: TurtleSignalType, reason: String) =
        TurtleEvaluation(type, system, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, profile(system), reason)

    fun wilderN(daily: List<TurtleDailyBar>, period: Int = 20): BigDecimal {
        if (daily.size < period + 1) return BigDecimal.ZERO
        val trs = mutableListOf<BigDecimal>()
        for (i in 1 until daily.size) {
            val c = daily[i]
            val prev = daily[i - 1]
            val tr = maxOf(
                c.high.subtract(c.low),
                c.high.subtract(prev.close).abs(),
                prev.close.subtract(c.low).abs()
            )
            trs += tr
        }
        if (trs.size < period) return BigDecimal.ZERO
        var n = trs.take(period).fold(BigDecimal.ZERO, BigDecimal::add)
            .divide(BigDecimal(period), 12, RoundingMode.HALF_UP)
        for (tr in trs.drop(period)) {
            n = n.multiply(BigDecimal(period - 1)).add(tr)
                .divide(BigDecimal(period), 12, RoundingMode.HALF_UP)
        }
        return n
    }
}
