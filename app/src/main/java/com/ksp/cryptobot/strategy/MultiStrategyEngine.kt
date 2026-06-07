package com.ksp.cryptobot.strategy

import com.ksp.cryptobot.core.*
import java.math.BigDecimal

class MultiStrategyEngine(
    private val scalper: MultiTimeframeScalpingStrategy = MultiTimeframeScalpingStrategy()
) {
    fun chooseBest(
        ticker: MarketTicker,
        candlesByTimeframe: Map<Timeframe, List<Candle>>,
        settings: BotSettings,
        regime: RegimeAnalysis
    ): StrategyCandidate {
        val candidates = mutableListOf<StrategyCandidate>()
        if (settings.strategyMode == StrategyMode.AUTO || settings.strategyMode == StrategyMode.SCALPING) {
            candidates += scalpingCandidate(ticker.symbol, candlesByTimeframe, settings, regime)
        }
        if (settings.strategyMode == StrategyMode.AUTO || settings.strategyMode == StrategyMode.TREND) {
            candidates += trendCandidate(ticker.symbol, candlesByTimeframe, regime)
        }
        if (settings.strategyMode == StrategyMode.AUTO || settings.strategyMode == StrategyMode.BREAKOUT) {
            candidates += breakoutCandidate(ticker.symbol, candlesByTimeframe, regime)
        }
        if (settings.strategyMode == StrategyMode.AUTO || settings.strategyMode == StrategyMode.REVERSAL) {
            candidates += reversalCandidate(ticker.symbol, candlesByTimeframe, regime)
        }
        if (settings.strategyMode == StrategyMode.AUTO || settings.strategyMode == StrategyMode.NEWS_MOMENTUM) {
            candidates += newsMomentumCandidate(ticker.symbol, ticker, regime)
        }
        return candidates.maxByOrNull { it.score } ?: StrategyCandidate(StrategyMode.AUTO, 0, SignalAction.WAIT, "No strategy candidate available.", BigDecimal.ZERO, BigDecimal.ZERO)
    }

    private fun scalpingCandidate(symbol: String, candles: Map<Timeframe, List<Candle>>, settings: BotSettings, regime: RegimeAnalysis): StrategyCandidate {
        val signal = scalper.evaluate(symbol, candles, settings)
        val penalty = if (regime.regime == MarketRegime.HIGH_VOLATILITY) 10 else 0
        val score = (signal.strategyScore - penalty).coerceIn(0, 100)
        return StrategyCandidate(StrategyMode.SCALPING, score, actionFromScore(score, settings.minStrategyScoreToBuy), "Scalping: ${signal.explanation}; regime penalty=$penalty.", signal.suggestedTakeProfitPercent, signal.suggestedStopLossPercent)
    }

    private fun trendCandidate(symbol: String, candles: Map<Timeframe, List<Candle>>, regime: RegimeAnalysis): StrategyCandidate {
        val h1 = candles[Timeframe.H1].orEmpty()
        val score = when (regime.regime) {
            MarketRegime.TRENDING_UP -> 74 + (regime.confidencePercent - 60) / 2
            MarketRegime.LOW_VOLATILITY -> 56
            MarketRegime.HIGH_VOLATILITY -> 42
            MarketRegime.TRENDING_DOWN -> 22
            else -> 50
        }.coerceIn(0, 100)
        val atr = if (h1.size > 20) TechnicalIndicators.atr(h1, 14) else BigDecimal.ZERO
        val close = h1.lastOrNull()?.close ?: BigDecimal.ONE
        val atrPct = if (close > BigDecimal.ZERO) atr.divide(close, 8, java.math.RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal("1.0")
        return StrategyCandidate(StrategyMode.TREND, score, actionFromScore(score, 72), "Trend mode selected from ${regime.explanation}", atrPct.multiply(BigDecimal("2.2")), atrPct.multiply(BigDecimal("1.2")))
    }

    private fun breakoutCandidate(symbol: String, candles: Map<Timeframe, List<Candle>>, regime: RegimeAnalysis): StrategyCandidate {
        val m15 = candles[Timeframe.M15].orEmpty()
        if (m15.size < 35) return StrategyCandidate(StrategyMode.BREAKOUT, 0, SignalAction.WAIT, "Breakout: not enough candles.", BigDecimal.ZERO, BigDecimal.ZERO)
        val recent = m15.takeLast(20)
        val resistance = recent.dropLast(1).maxOf { it.high }
        val last = recent.last()
        val volAvg = recent.dropLast(1).map { it.volume }.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(recent.size - 1), 8, java.math.RoundingMode.HALF_UP)
        val priceBreak = last.close > resistance
        val volBreak = last.volume > volAvg.multiply(BigDecimal("1.25"))
        val base = 45 + if (priceBreak) 22 else 0 + if (volBreak) 18 else 0
        val penalty = if (regime.regime == MarketRegime.RISK_OFF) 20 else 0
        val score = (base - penalty).coerceIn(0, 100)
        return StrategyCandidate(StrategyMode.BREAKOUT, score, actionFromScore(score, 76), "Breakout: priceBreak=$priceBreak, volumeBreak=$volBreak, regime=${regime.regime}.", BigDecimal("1.6"), BigDecimal("0.9"))
    }

    private fun reversalCandidate(symbol: String, candles: Map<Timeframe, List<Candle>>, regime: RegimeAnalysis): StrategyCandidate {
        val m15 = candles[Timeframe.M15].orEmpty()
        if (m15.size < 35) return StrategyCandidate(StrategyMode.REVERSAL, 0, SignalAction.WAIT, "Reversal: not enough candles.", BigDecimal.ZERO, BigDecimal.ZERO)
        val closes = m15.map { it.close }
        val change = TechnicalIndicators.percentChange(closes.takeLast(12).first(), closes.last())
        val oversold = change < BigDecimal("-2.5") && regime.regime != MarketRegime.TRENDING_DOWN
        val score = if (oversold) 70 else 45
        return StrategyCandidate(StrategyMode.REVERSAL, score, actionFromScore(score, 72), "Reversal: 12-candle change=$change%, oversold=$oversold.", BigDecimal("1.2"), BigDecimal("0.8"))
    }

    private fun newsMomentumCandidate(symbol: String, ticker: MarketTicker, regime: RegimeAnalysis): StrategyCandidate {
        val positiveMomentum = ticker.priceChangePercent24h > BigDecimal("1.0") && regime.regime != MarketRegime.HIGH_VOLATILITY
        val score = if (positiveMomentum) 68 else 48
        return StrategyCandidate(StrategyMode.NEWS_MOMENTUM, score, actionFromScore(score, 72), "News momentum shell: waits for NewsIntelligenceEngine confirmation before execution.", BigDecimal("1.2"), BigDecimal("0.8"))
    }

    private fun actionFromScore(score: Int, buyThreshold: Int): SignalAction = when {
        score >= buyThreshold + 10 -> SignalAction.BUY
        score >= buyThreshold -> SignalAction.SMALL_BUY
        score >= 58 -> SignalAction.WATCH
        score >= 45 -> SignalAction.WAIT
        else -> SignalAction.AVOID
    }
}
