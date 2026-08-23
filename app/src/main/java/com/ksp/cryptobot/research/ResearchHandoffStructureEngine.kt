package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.Timeframe
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class HandoffBar(
    val openTimeEpochMs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

data class HandoffPivot(val index: Int, val price: Double, val high: Boolean)

data class HandoffMarketStructure(
    val m15: List<Candle>,
    val h1: List<Candle>,
    val h4: List<Candle>,
    val daily: List<HandoffBar>,
    val weekly: List<HandoffBar>,
    val h1Pivots: List<HandoffPivot>,
    val h4Pivots: List<HandoffPivot>,
    val trendH1: String,
    val trendH4: String,
    val atrH1: Double,
    val atrH4: Double,
    val adxH1: Double,
    val adxH4: Double,
    val previousDayHigh: Double?,
    val previousDayLow: Double?,
    val currentDayOpen: Double?,
    val nearestResistanceH1: Double?,
    val nearestSupportH1: Double?,
    val relativeVolumeH1: Double,
    val spreadPct: Double,
    val dataIntegrityOk: Boolean,
    val integrityReason: String
)

class ResearchHandoffStructureEngine {
    fun build(
        ticker: MarketTicker,
        candlesByTimeframe: Map<Timeframe, List<Candle>>,
        nowEpochMs: Long = System.currentTimeMillis()
    ): HandoffMarketStructure {
        val m15 = closedUnique(candlesByTimeframe[Timeframe.M15].orEmpty(), Timeframe.M15, nowEpochMs)
        val h1 = closedUnique(candlesByTimeframe[Timeframe.H1].orEmpty(), Timeframe.H1, nowEpochMs)
        val h4 = closedUnique(candlesByTimeframe[Timeframe.H4].orEmpty(), Timeframe.H4, nowEpochMs)
        val source = if (h1.isNotEmpty()) h1 else if (h4.isNotEmpty()) h4 else m15
        val daily = aggregateUtc(source, weekly = false)
        val weekly = aggregateUtc(if (h4.isNotEmpty()) h4 else source, weekly = true)
        val h1P = pivots(h1, 2)
        val h4P = pivots(h4, 2)
        val last = ticker.lastPrice.toDouble()
        val resistance = h1P.filter { it.high && it.price > last }.minByOrNull { it.price }?.price
        val support = h1P.filter { !it.high && it.price < last }.maxByOrNull { it.price }?.price
        val todayKey = utcDayKey(nowEpochMs)
        val completeDaily = daily.filter { utcDayKey(it.openTimeEpochMs) != todayKey }
        val previous = completeDaily.lastOrNull()
        val currentOpen = daily.lastOrNull()?.takeIf { utcDayKey(it.openTimeEpochMs) == todayKey }?.open
        val integrity = dataIntegrity(candlesByTimeframe)
        return HandoffMarketStructure(
            m15 = m15,
            h1 = h1,
            h4 = h4,
            daily = completeDaily,
            weekly = weekly.filter { weekKey(it.openTimeEpochMs) != weekKey(nowEpochMs) },
            h1Pivots = h1P,
            h4Pivots = h4P,
            trendH1 = structureTrend(h1P, h1),
            trendH4 = structureTrend(h4P, h4),
            atrH1 = StrategyMath.atrAbs(h1, 14),
            atrH4 = StrategyMath.atrAbs(h4, 14),
            adxH1 = StrategyMath.wilderDmi(h1, 14).adx,
            adxH4 = StrategyMath.wilderDmi(h4, 14).adx,
            previousDayHigh = previous?.high,
            previousDayLow = previous?.low,
            currentDayOpen = currentOpen,
            nearestResistanceH1 = resistance,
            nearestSupportH1 = support,
            relativeVolumeH1 = StrategyMath.volumeRatio(h1, 4, 30),
            spreadPct = StrategyMath.spreadPct(last, ticker.bid.toDouble(), ticker.ask.toDouble()),
            dataIntegrityOk = integrity.first,
            integrityReason = integrity.second
        )
    }

    fun closedUnique(candles: List<Candle>, tf: Timeframe, nowEpochMs: Long): List<Candle> {
        val interval = intervalMs(tf)
        return candles
            .filter { it.openTimeEpochMs > 0 && it.openTimeEpochMs + interval <= nowEpochMs }
            .sortedBy { it.openTimeEpochMs }
            .distinctBy { it.openTimeEpochMs }
    }

    fun pivots(candles: List<Candle>, span: Int = 2): List<HandoffPivot> {
        if (candles.size < span * 2 + 1) return emptyList()
        val out = mutableListOf<HandoffPivot>()
        for (i in span until candles.size - span) {
            val c = candles[i]
            val hi = c.high.toDouble(); val lo = c.low.toDouble()
            val around = (i - span..i + span).filter { it != i }
            if (around.all { candles[it].high.toDouble() <= hi }) out += HandoffPivot(i, hi, true)
            if (around.all { candles[it].low.toDouble() >= lo }) out += HandoffPivot(i, lo, false)
        }
        return out
    }

    fun recentHorizontalRange(bars: List<HandoffBar>, minBars: Int = 40, maxBars: Int = 98): Triple<Double, Double, Double>? {
        if (bars.size < minBars) return null
        val window = bars.takeLast(min(maxBars, bars.size))
        val high = window.maxOf { it.high }; val low = window.minOf { it.low }
        val mid = (high + low) / 2.0
        val widthPct = if (mid > 0.0) (high - low) / mid * 100.0 else 999.0
        return Triple(low, high, widthPct)
    }

    fun equilibrium(candles: List<Candle>, legs: Int = 4): Boolean {
        if (candles.size < legs + 2) return false
        val w = candles.takeLast(legs + 1)
        val lowerHighs = w.zipWithNext().all { (a,b) -> b.high.toDouble() <= a.high.toDouble() }
        val higherLows = w.zipWithNext().all { (a,b) -> b.low.toDouble() >= a.low.toDouble() }
        return lowerHighs && higherLows
    }

    fun insideBar(candles: List<Candle>, equalityAllowed: Boolean = true): Boolean {
        if (candles.size < 2) return false
        val mother = candles[candles.lastIndex - 1]; val child = candles.last()
        return if (equalityAllowed) child.high <= mother.high && child.low >= mother.low else child.high < mother.high && child.low > mother.low
    }

    fun breakoutRetest(candles: List<Candle>, lookback: Int = 48, toleranceAtr: Double = .35): Pair<Double, Int>? {
        if (candles.size < lookback + 8) return null
        val atr = StrategyMath.atrAbs(candles, 14).takeIf { it > 0 } ?: return null
        val start = candles.size - lookback - 8
        for (breakIndex in (start + lookback) until candles.lastIndex) {
            val prior = candles.subList(max(0, breakIndex - lookback), breakIndex)
            val level = prior.maxOf { it.high.toDouble() }
            val b = candles[breakIndex]
            if (b.close.toDouble() > level && b.high.toDouble() > level) {
                val after = candles.subList(breakIndex + 1, candles.size)
                val firstTouch = after.indexOfFirst { it.low.toDouble() <= level + atr * toleranceAtr && it.close.toDouble() >= level - atr * .10 }
                if (firstTouch >= 0 && breakIndex + 1 + firstTouch == candles.lastIndex) return level to breakIndex
            }
        }
        return null
    }

    fun roundedRetest(candles: List<Candle>, lookback: Int = 48, minBarsAway: Int = 6): Pair<Double, Int>? {
        val hit = breakoutRetest(candles, lookback, .45) ?: return null
        val level = hit.first; val breakIndex = hit.second
        val atr = StrategyMath.atrAbs(candles.take(breakIndex + 1), 14).takeIf { it > 0 } ?: return null
        val away = candles.subList(breakIndex + 1, candles.lastIndex)
        if (away.size < minBarsAway) return null
        val separated = away.any { it.high.toDouble() >= level + atr }
        return if (separated) hit else null
    }

    fun patternFailure(candles: List<Candle>, lookback: Int = 30): Pair<String, Double>? {
        if (candles.size < lookback + 3) return null
        val prior = candles.dropLast(2).takeLast(lookback)
        val resistance = prior.maxOf { it.high.toDouble() }; val support = prior.minOf { it.low.toDouble() }
        val prev = candles[candles.lastIndex - 1]; val last = candles.last()
        if (prev.high.toDouble() > resistance && last.close.toDouble() < resistance) return "BULL_BREAK_FAILED" to resistance
        if (prev.low.toDouble() < support && last.close.toDouble() > support) return "BEAR_BREAK_FAILED" to support
        return null
    }

    fun firstTroubleArea(structure: HandoffMarketStructure, entry: Double): Double? =
        structure.h1Pivots.filter { it.high && it.price > entry }.minByOrNull { it.price }?.price

    fun pdhPdlReclaim(structure: HandoffMarketStructure): Pair<String, Double>? {
        val c = structure.m15.lastOrNull() ?: return null
        val pdl = structure.previousDayLow
        val pdh = structure.previousDayHigh
        if (pdl != null && c.low.toDouble() < pdl && c.close.toDouble() > pdl) return "PDL_RECLAIM" to pdl
        if (pdh != null && c.high.toDouble() > pdh && c.close.toDouble() < pdh) return "PDH_REJECTION" to pdh
        return null
    }

    fun sixtyDayCycleContext(daily: List<HandoffBar>): String {
        if (daily.size < 80) return "INSUFFICIENT_80_DAILY_BARS"
        val lows = mutableListOf<Int>()
        for (i in 4 until daily.size - 4) {
            val lo = daily[i].low
            if ((i - 4..i + 4).all { daily[it].low >= lo }) lows += i
        }
        if (lows.size < 2) return "NO_STABLE_CYCLE_LOW_SEQUENCE"
        val spacing = lows.zipWithNext().map { it.second - it.first }.takeLast(4)
        val avg = spacing.average()
        val since = daily.lastIndex - lows.last()
        return "FORMALIZED_LOW_SPACING avg=${"%.1f".format(avg)}d sinceLastLow=${since}d"
    }

    private fun aggregateUtc(candles: List<Candle>, weekly: Boolean): List<HandoffBar> {
        if (candles.isEmpty()) return emptyList()
        return candles.groupBy { if (weekly) weekKey(it.openTimeEpochMs) else utcDayKey(it.openTimeEpochMs) }
            .toSortedMap()
            .values.mapNotNull { rows ->
                val sorted = rows.sortedBy { it.openTimeEpochMs }
                if (sorted.isEmpty()) null else HandoffBar(
                    openTimeEpochMs = sorted.first().openTimeEpochMs,
                    open = sorted.first().open.toDouble(),
                    high = sorted.maxOf { it.high.toDouble() },
                    low = sorted.minOf { it.low.toDouble() },
                    close = sorted.last().close.toDouble(),
                    volume = sorted.sumOf { it.volume.toDouble() }
                )
            }
    }

    private fun structureTrend(pivots: List<HandoffPivot>, candles: List<Candle>): String {
        val highs = pivots.filter { it.high }.takeLast(2); val lows = pivots.filter { !it.high }.takeLast(2)
        if (highs.size == 2 && lows.size == 2) {
            if (highs[1].price > highs[0].price && lows[1].price > lows[0].price) return "HH_HL"
            if (highs[1].price < highs[0].price && lows[1].price < lows[0].price) return "LH_LL"
        }
        val closes = StrategyMath.closes(candles)
        if (closes.size >= 50) {
            val e20 = StrategyMath.ema(closes, 20); val e50 = StrategyMath.ema(closes, 50)
            if (e20 > e50 && closes.last() > e20) return "BULLISH"
            if (e20 < e50 && closes.last() < e20) return "BEARISH"
        }
        return "MIXED"
    }

    private fun dataIntegrity(input: Map<Timeframe, List<Candle>>): Pair<Boolean, String> {
        val issues = mutableListOf<String>()
        for ((tf, rows) in input) {
            if (rows.any { it.openTimeEpochMs <= 0 }) issues += "$tf invalid timestamp"
            if (rows.groupingBy { it.openTimeEpochMs }.eachCount().any { it.value > 1 }) issues += "$tf duplicate timestamps"
            if (rows.zipWithNext().any { (a,b) -> b.openTimeEpochMs < a.openTimeEpochMs }) issues += "$tf unsorted input"
            if (rows.any { c ->
                    c.open <= BigDecimal.ZERO || c.high <= BigDecimal.ZERO || c.low <= BigDecimal.ZERO || c.close <= BigDecimal.ZERO ||
                    c.volume < BigDecimal.ZERO || c.high < c.low || c.high < c.open.max(c.close) || c.low > c.open.min(c.close)
                }) issues += "$tf invalid OHLCV geometry"
            val interval = intervalMs(tf)
            val sorted = rows.filter { it.openTimeEpochMs > 0 }.sortedBy { it.openTimeEpochMs }.distinctBy { it.openTimeEpochMs }
            val maxGap = sorted.zipWithNext().maxOfOrNull { (a,b) -> b.openTimeEpochMs-a.openTimeEpochMs } ?: interval
            if (maxGap > interval*3L) issues += "$tf market-data gap exceeds 3 bars (${maxGap/interval} intervals); strategy execution blocked until continuity is restored"
            val misaligned = sorted.any { c ->
                val remainder = c.openTimeEpochMs % interval
                remainder > 1_000L && interval-remainder > 1_000L
            }
            if (misaligned) issues += "$tf candle timestamps are not UTC interval-aligned"
        }
        return if (issues.isEmpty()) true to "UTC alignment/OHLCV/duplicate/gap checks passed; unfinished bars excluded and gap policy <=3 intervals." else false to issues.joinToString("; ")
    }

    private fun intervalMs(tf: Timeframe): Long = when (tf) {
        Timeframe.M1 -> 60_000L
        Timeframe.M5 -> 300_000L
        Timeframe.M15 -> 900_000L
        Timeframe.H1 -> 3_600_000L
        Timeframe.H4 -> 14_400_000L
    }

    private fun utcDayKey(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().toString()
    private fun weekKey(ms: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
        val wf = WeekFields.of(Locale.ROOT)
        return "${d.get(wf.weekBasedYear())}-${d.get(wf.weekOfWeekBasedYear()).toString().padStart(2,'0')}"
    }
}
