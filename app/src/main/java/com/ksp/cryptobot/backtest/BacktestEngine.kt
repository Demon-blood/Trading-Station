package com.ksp.cryptobot.backtest

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.strategy.TechnicalIndicators
import java.math.BigDecimal
import java.math.RoundingMode

class BacktestEngine {
    fun run(symbol: String, timeframe: Timeframe, strategy: StrategyMode, candles: List<Candle>, settings: BotSettings): BacktestReport {
        if (candles.size < 80) {
            return BacktestReport(symbol, strategy, timeframe, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, "Not enough candles for meaningful backtest.")
        }
        val trades = mutableListOf<BacktestTrade>()
        var inTrade = false
        var entry = BigDecimal.ZERO
        var entryTime = 0L
        var peakEquity = BigDecimal("1000.00")
        var equity = BigDecimal("1000.00")
        var maxDd = BigDecimal.ZERO

        for (i in 60 until candles.size) {
            val window = candles.subList(0, i + 1)
            val close = window.last().close
            val closes = window.map { it.close }
            val atr = TechnicalIndicators.atr(window, settings.atrPeriod)
            val atrPct = if (close > BigDecimal.ZERO) atr.divide(close, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal("1.0")

            if (!inTrade && shouldEnter(strategy, window, settings)) {
                inTrade = true
                entry = close
                entryTime = window.last().openTimeEpochMs
            } else if (inTrade) {
                val change = close.subtract(entry).divide(entry, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
                val tp = strategyTakeProfit(strategy, atrPct, settings)
                val sl = strategyStopLoss(strategy, atrPct, settings).negate()
                if (change >= tp || change <= sl || shouldExit(strategy, window, entry, settings)) {
                    trades += BacktestTrade(
                        symbol,
                        strategy,
                        entryTime,
                        window.last().openTimeEpochMs,
                        entry,
                        close,
                        OrderSide.BUY,
                        change.setScale(2, RoundingMode.HALF_UP),
                        if (change >= tp) "TP" else if (change <= sl) "SL" else "Strategy exit"
                    )
                    equity = equity.multiply(BigDecimal.ONE.add(change.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP))).setScale(2, RoundingMode.HALF_UP)
                    if (equity > peakEquity) peakEquity = equity
                    val dd = peakEquity.subtract(equity).divide(peakEquity, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
                    if (dd > maxDd) maxDd = dd
                    inTrade = false
                }
            }
        }

        val wins = trades.filter { it.pnlPercent > BigDecimal.ZERO }
        val losses = trades.filter { it.pnlPercent < BigDecimal.ZERO }
        val winRate = if (trades.isNotEmpty()) BigDecimal(wins.size).multiply(BigDecimal("100")).divide(BigDecimal(trades.size), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val grossProfit = wins.fold(BigDecimal.ZERO) { a, t -> a + t.pnlPercent }
        val grossLoss = losses.fold(BigDecimal.ZERO) { a, t -> a + t.pnlPercent.abs() }
        val pf = if (grossLoss > BigDecimal.ZERO) grossProfit.divide(grossLoss, 2, RoundingMode.HALF_UP) else if (grossProfit > BigDecimal.ZERO) BigDecimal("9.99") else BigDecimal.ZERO
        val net = equity.subtract(BigDecimal("1000.00")).divide(BigDecimal("1000.00"), 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")).setScale(2, RoundingMode.HALF_UP)
        val passed = trades.size >= settings.requiredPaperTrades && winRate >= BigDecimal(settings.requiredPaperWinRatePercent) && pf >= settings.requiredProfitFactor && maxDd <= settings.maxDrawdownPercent
        return BacktestReport(symbol, strategy, timeframe, trades.size, winRate, pf, maxDd.setScale(2, RoundingMode.HALF_UP), net, passed, "Strategy=${strategy.name}, trades=${trades.size}, winRate=$winRate%, PF=$pf, maxDD=${maxDd.setScale(2, RoundingMode.HALF_UP)}%, net=$net%.")
    }

    private fun shouldEnter(strategy: StrategyMode, candles: List<Candle>, settings: BotSettings): Boolean {
        val closes = candles.map { it.close }
        val last = candles.last()
        val emaFast = TechnicalIndicators.ema(closes, settings.emaFastPeriod)
        val emaSlow = TechnicalIndicators.ema(closes, settings.emaSlowPeriod)
        val rsi = TechnicalIndicators.rsi(closes, 14)
        val vwap = TechnicalIndicators.vwap(candles, 30)
        val high20 = TechnicalIndicators.highestHigh(candles.dropLast(1), 20)
        val high40 = TechnicalIndicators.highestHigh(candles.dropLast(1), 40)
        val low40 = TechnicalIndicators.lowestLow(candles, 40)
        val avgVol = avgVolume(candles.takeLast(30).dropLast(1))
        val shortMove = TechnicalIndicators.percentChange(closes.takeLast(6).first(), closes.last())
        val dayMove = TechnicalIndicators.percentChange(closes.takeLast(24).first(), closes.last())
        val basis = TechnicalIndicators.sma(closes, 20)
        val lower = basis.subtract(TechnicalIndicators.standardDeviation(closes, 20).multiply(BigDecimal("2")))

        return when (strategy) {
            StrategyMode.AUTO, StrategyMode.SCALPING, StrategyMode.TREND -> emaFast > emaSlow
            StrategyMode.BREAKOUT -> last.close > high20 && last.volume > avgVol.multiply(BigDecimal("1.20"))
            StrategyMode.REVERSAL -> dayMove < BigDecimal("-2.0") && rsi < BigDecimal("38")
            StrategyMode.NEWS_MOMENTUM -> dayMove > BigDecimal("0.8") && emaFast > emaSlow
            StrategyMode.MEAN_REVERSION_RSI_BOLLINGER -> last.close <= lower || rsi < BigDecimal("32")
            StrategyMode.VWAP_PULLBACK -> last.close <= vwap.multiply(BigDecimal("1.002")) && last.close >= vwap.multiply(BigDecimal("0.990")) && emaFast >= emaSlow
            StrategyMode.DONCHIAN_BREAKOUT -> last.close > high40 && last.volume > avgVol.multiply(BigDecimal("1.20"))
            StrategyMode.RANGE_GRID -> last.close <= low40.add(TechnicalIndicators.highestHigh(candles, 40).subtract(low40).multiply(BigDecimal("0.35")))
            StrategyMode.MARKET_MAKING_IMBALANCE -> emaFast >= emaSlow && avgVol > BigDecimal.ZERO
            StrategyMode.FUNDING_NEWS_RISK_OFF -> dayMove > BigDecimal("-2.0") && emaFast >= emaSlow
            StrategyMode.PAIRS_RELATIVE_STRENGTH -> dayMove > BigDecimal("0.75")
            StrategyMode.DCA_CRASH_PROTECTION -> dayMove < BigDecimal("-2.0") && dayMove > BigDecimal("-8.0")
            StrategyMode.MOMENTUM_SPIKE_CONTINUATION -> shortMove > BigDecimal("1.0") && last.volume > avgVol.multiply(BigDecimal("1.6"))
            StrategyMode.VOLUME_ANOMALY_WHALE_MOVE -> last.volume > avgVol.multiply(BigDecimal("2.0")) && last.close > last.open
        }
    }

    private fun shouldExit(strategy: StrategyMode, candles: List<Candle>, entry: BigDecimal, settings: BotSettings): Boolean {
        val closes = candles.map { it.close }
        val last = closes.last()
        val emaFast = TechnicalIndicators.ema(closes, settings.emaFastPeriod)
        val emaSlow = TechnicalIndicators.ema(closes, settings.emaSlowPeriod)
        val rsi = TechnicalIndicators.rsi(closes, 14)
        val change = if (entry > BigDecimal.ZERO) last.subtract(entry).divide(entry, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal.ZERO
        return when (strategy) {
            StrategyMode.AUTO, StrategyMode.SCALPING, StrategyMode.TREND, StrategyMode.NEWS_MOMENTUM -> emaFast < emaSlow
            StrategyMode.MEAN_REVERSION_RSI_BOLLINGER, StrategyMode.REVERSAL -> rsi > BigDecimal("55") || change > BigDecimal("1.0")
            StrategyMode.RANGE_GRID, StrategyMode.MARKET_MAKING_IMBALANCE -> change > BigDecimal("0.55")
            StrategyMode.FUNDING_NEWS_RISK_OFF -> change < BigDecimal("-1.5") || emaFast < emaSlow
            StrategyMode.DCA_CRASH_PROTECTION -> change > BigDecimal("0.8") || change < BigDecimal("-1.2")
            else -> change > BigDecimal("1.2") || emaFast < emaSlow
        }
    }

    private fun strategyTakeProfit(strategy: StrategyMode, atrPct: BigDecimal, settings: BotSettings): BigDecimal = when (strategy) {
        StrategyMode.RANGE_GRID, StrategyMode.MARKET_MAKING_IMBALANCE -> BigDecimal("0.55")
        StrategyMode.MOMENTUM_SPIKE_CONTINUATION, StrategyMode.DONCHIAN_BREAKOUT -> atrPct.multiply(BigDecimal("1.8"))
        StrategyMode.DCA_CRASH_PROTECTION -> BigDecimal("0.85")
        else -> atrPct.multiply(settings.takeProfitAtrMultiplier)
    }.max(BigDecimal("0.25"))

    private fun strategyStopLoss(strategy: StrategyMode, atrPct: BigDecimal, settings: BotSettings): BigDecimal = when (strategy) {
        StrategyMode.RANGE_GRID, StrategyMode.MARKET_MAKING_IMBALANCE -> BigDecimal("0.40")
        StrategyMode.DCA_CRASH_PROTECTION -> BigDecimal("0.75")
        StrategyMode.MOMENTUM_SPIKE_CONTINUATION -> BigDecimal("0.90")
        else -> atrPct.multiply(settings.stopLossAtrMultiplier)
    }.max(BigDecimal("0.25"))

    private fun avgVolume(candles: List<Candle>): BigDecimal =
        if (candles.isEmpty()) BigDecimal.ZERO else candles.fold(BigDecimal.ZERO) { acc, c -> acc.add(c.volume) }.divide(BigDecimal(candles.size), 8, RoundingMode.HALF_UP)
}
