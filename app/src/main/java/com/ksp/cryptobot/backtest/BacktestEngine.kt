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
            val emaFast = TechnicalIndicators.ema(closes, settings.emaFastPeriod)
            val emaSlow = TechnicalIndicators.ema(closes, settings.emaSlowPeriod)
            val atr = TechnicalIndicators.atr(window, settings.atrPeriod)
            val atrPct = if (close > BigDecimal.ZERO) atr.divide(close, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal("1.0")
            if (!inTrade && emaFast > emaSlow) {
                inTrade = true
                entry = close
                entryTime = window.last().openTimeEpochMs
            } else if (inTrade) {
                val change = close.subtract(entry).divide(entry, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
                val tp = atrPct.multiply(settings.takeProfitAtrMultiplier)
                val sl = atrPct.multiply(settings.stopLossAtrMultiplier).negate()
                if (change >= tp || change <= sl || emaFast < emaSlow) {
                    trades += BacktestTrade(symbol, strategy, entryTime, window.last().openTimeEpochMs, entry, close, OrderSide.BUY, change.setScale(2, RoundingMode.HALF_UP), if (change >= tp) "TP" else if (change <= sl) "SL" else "Trend exit")
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
        return BacktestReport(symbol, strategy, timeframe, trades.size, winRate, pf, maxDd.setScale(2, RoundingMode.HALF_UP), net, passed, "Trades=${trades.size}, winRate=$winRate%, PF=$pf, maxDD=${maxDd.setScale(2, RoundingMode.HALF_UP)}%, net=$net%.")
    }
}
