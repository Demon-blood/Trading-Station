package com.ksp.cryptobot.strategy

import com.ksp.cryptobot.core.*
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

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
        fun enabled(mode: StrategyMode) = settings.strategyMode == StrategyMode.AUTO || settings.strategyMode == mode

        if (enabled(StrategyMode.SCALPING)) candidates += scalpingCandidate(ticker.symbol, candlesByTimeframe, settings, regime)
        if (enabled(StrategyMode.TREND)) candidates += trendCandidate(ticker.symbol, candlesByTimeframe, regime)
        if (enabled(StrategyMode.BREAKOUT)) candidates += breakoutCandidate(ticker.symbol, candlesByTimeframe, regime)
        if (enabled(StrategyMode.REVERSAL)) candidates += reversalCandidate(ticker.symbol, candlesByTimeframe, regime)
        if (enabled(StrategyMode.NEWS_MOMENTUM)) candidates += newsMomentumCandidate(ticker.symbol, ticker, regime)

        if (enabled(StrategyMode.MEAN_REVERSION_RSI_BOLLINGER)) candidates += meanReversionCandidate(ticker.symbol, candlesByTimeframe, regime)
        if (enabled(StrategyMode.VWAP_PULLBACK)) candidates += vwapPullbackCandidate(ticker.symbol, candlesByTimeframe, regime)
        if (enabled(StrategyMode.DONCHIAN_BREAKOUT)) candidates += donchianBreakoutCandidate(ticker.symbol, candlesByTimeframe, regime)
        if (enabled(StrategyMode.RANGE_GRID)) candidates += rangeGridCandidate(ticker.symbol, candlesByTimeframe, regime)
        if (enabled(StrategyMode.MARKET_MAKING_IMBALANCE)) candidates += marketMakingImbalanceCandidate(ticker, candlesByTimeframe, regime)
        if (enabled(StrategyMode.FUNDING_NEWS_RISK_OFF)) candidates += fundingNewsRiskOffCandidate(ticker, candlesByTimeframe, regime)
        if (enabled(StrategyMode.PAIRS_RELATIVE_STRENGTH)) candidates += pairsRelativeStrengthCandidate(ticker, candlesByTimeframe, regime)
        if (enabled(StrategyMode.DCA_CRASH_PROTECTION)) candidates += dcaCrashProtectionCandidate(ticker, candlesByTimeframe, regime)
        if (enabled(StrategyMode.MOMENTUM_SPIKE_CONTINUATION)) candidates += momentumSpikeContinuationCandidate(ticker, candlesByTimeframe, regime)
        if (enabled(StrategyMode.VOLUME_ANOMALY_WHALE_MOVE)) candidates += volumeAnomalyWhaleMoveCandidate(ticker, candlesByTimeframe, regime)

        return candidates.maxByOrNull { it.score } ?: StrategyCandidate(StrategyMode.AUTO, 0, SignalAction.WAIT, "No strategy candidate available.", BigDecimal.ZERO, BigDecimal.ZERO)
    }

    private fun baseCandles(candles: Map<Timeframe, List<Candle>>): List<Candle> =
        candles[Timeframe.M15].orEmpty().ifEmpty { candles[Timeframe.M5].orEmpty() }.ifEmpty { candles[Timeframe.H1].orEmpty() }

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
        val atrPct = atrPercent(h1)
        return StrategyCandidate(StrategyMode.TREND, score, actionFromScore(score, 72), "Trend mode: ${regime.explanation}", atrPct.multiply(BigDecimal("2.2")), atrPct.multiply(BigDecimal("1.2")))
    }

    private fun breakoutCandidate(symbol: String, candles: Map<Timeframe, List<Candle>>, regime: RegimeAnalysis): StrategyCandidate {
        val m15 = candles[Timeframe.M15].orEmpty()
        if (m15.size < 35) return StrategyCandidate(StrategyMode.BREAKOUT, 0, SignalAction.WAIT, "Breakout: not enough candles.", BigDecimal.ZERO, BigDecimal.ZERO)
        val recent = m15.takeLast(20)
        val resistance = recent.dropLast(1).maxOf { it.high }
        val last = recent.last()
        val volAvg = avgVolume(recent.dropLast(1))
        val priceBreak = last.close > resistance
        val volBreak = last.volume > volAvg.multiply(BigDecimal("1.25"))
        val base = 45 + (if (priceBreak) 22 else 0) + (if (volBreak) 18 else 0)
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
        return StrategyCandidate(StrategyMode.NEWS_MOMENTUM, score, actionFromScore(score, 72), "News momentum: positive 24h momentum=$positiveMomentum; news layer is applied after this technical candidate.", BigDecimal("1.2"), BigDecimal("0.8"))
    }

    private fun meanReversionCandidate(symbol: String, candles: Map<Timeframe, List<Candle>>, regime: RegimeAnalysis): StrategyCandidate {
        val c = baseCandles(candles)
        if (c.size < 40) return StrategyCandidate(StrategyMode.MEAN_REVERSION_RSI_BOLLINGER, 0, SignalAction.WAIT, "Mean reversion: not enough candles.", BigDecimal.ZERO, BigDecimal.ZERO)
        val closes = c.map { it.close }
        val last = closes.last()
        val basis = TechnicalIndicators.sma(closes, 20)
        val dev = TechnicalIndicators.standardDeviation(closes, 20)
        val lower = basis.subtract(dev.multiply(BigDecimal("2")))
        val rsi = TechnicalIndicators.rsi(closes, 14)
        val oversold = last <= lower || rsi < BigDecimal("32")
        val score = (42 + (if (oversold) 30 else 0) + (if (regime.regime == MarketRegime.SIDEWAYS || regime.regime == MarketRegime.LOW_VOLATILITY) 12 else -8)).coerceIn(0, 100)
        return StrategyCandidate(StrategyMode.MEAN_REVERSION_RSI_BOLLINGER, score, actionFromScore(score, 72), "Mean reversion RSI/Bollinger: RSI=$rsi, belowLower=$oversold, regime=${regime.regime}.", BigDecimal("0.9"), BigDecimal("0.7"))
    }

    private fun vwapPullbackCandidate(symbol: String, candles: Map<Timeframe, List<Candle>>, regime: RegimeAnalysis): StrategyCandidate {
        val c = baseCandles(candles)
        if (c.size < 35) return StrategyCandidate(StrategyMode.VWAP_PULLBACK, 0, SignalAction.WAIT, "VWAP pullback: not enough candles.", BigDecimal.ZERO, BigDecimal.ZERO)
        val closes = c.map { it.close }
        val last = closes.last()
        val vwap = TechnicalIndicators.vwap(c, 30)
        val ema = TechnicalIndicators.ema(closes, 21)
        val pullback = last <= vwap.multiply(BigDecimal("1.002")) && last >= vwap.multiply(BigDecimal("0.990"))
        val upBias = last >= ema || regime.regime == MarketRegime.TRENDING_UP
        val score = (40 + (if (pullback) 22 else 0) + (if (upBias) 20 else -10)).coerceIn(0, 100)
        return StrategyCandidate(StrategyMode.VWAP_PULLBACK, score, actionFromScore(score, 72), "VWAP pullback: nearVWAP=$pullback, upBias=$upBias, vwap≈$vwap.", BigDecimal("1.0"), BigDecimal("0.65"))
    }

    private fun donchianBreakoutCandidate(symbol: String, candles: Map<Timeframe, List<Candle>>, regime: RegimeAnalysis): StrategyCandidate {
        val c = baseCandles(candles)
        if (c.size < 60) return StrategyCandidate(StrategyMode.DONCHIAN_BREAKOUT, 0, SignalAction.WAIT, "Donchian breakout: not enough candles.", BigDecimal.ZERO, BigDecimal.ZERO)
        val prior = c.dropLast(1)
        val channelHigh = TechnicalIndicators.highestHigh(prior, 40)
        val last = c.last()
        val breakout = last.close > channelHigh
        val volOk = last.volume > avgVolume(c.takeLast(40).dropLast(1)).multiply(BigDecimal("1.20"))
        val score = (43 + (if (breakout) 28 else 0) + (if (volOk) 18 else 0) + (if (regime.regime == MarketRegime.HIGH_VOLATILITY) -8 else 0)).coerceIn(0, 100)
        return StrategyCandidate(StrategyMode.DONCHIAN_BREAKOUT, score, actionFromScore(score, 76), "Donchian breakout: channelHigh=$channelHigh, breakout=$breakout, volumeConfirm=$volOk.", BigDecimal("1.8"), BigDecimal("0.95"))
    }

    private fun rangeGridCandidate(symbol: String, candles: Map<Timeframe, List<Candle>>, regime: RegimeAnalysis): StrategyCandidate {
        val c = baseCandles(candles)
        if (c.size < 45) return StrategyCandidate(StrategyMode.RANGE_GRID, 0, SignalAction.WAIT, "Range grid: not enough candles.", BigDecimal.ZERO, BigDecimal.ZERO)
        val last = c.last().close
        val high = TechnicalIndicators.highestHigh(c, 40)
        val low = TechnicalIndicators.lowestLow(c, 40)
        val widthPct = if (last > BigDecimal.ZERO) high.subtract(low).divide(last, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal.ZERO
        val inRange = regime.regime == MarketRegime.SIDEWAYS || regime.regime == MarketRegime.LOW_VOLATILITY
        val nearLow = last <= low.add(high.subtract(low).multiply(BigDecimal("0.35")))
        val score = (35 + (if (inRange) 22 else -10) + (if (nearLow) 20 else 0) + (if (widthPct >= BigDecimal("0.8") && widthPct <= BigDecimal("6.0")) 10 else 0)).coerceIn(0, 100)
        return StrategyCandidate(StrategyMode.RANGE_GRID, score, actionFromScore(score, 70), "Range grid: inRange=$inRange, nearRangeLow=$nearLow, width≈${widthPct.setScale(2, RoundingMode.HALF_UP)}%.", BigDecimal("0.8"), BigDecimal("0.55"))
    }

    private fun marketMakingImbalanceCandidate(ticker: MarketTicker, candles: Map<Timeframe, List<Candle>>, regime: RegimeAnalysis): StrategyCandidate {
        val spreadPct = if (ticker.lastPrice > BigDecimal.ZERO) ticker.ask.subtract(ticker.bid).abs().divide(ticker.lastPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal.ONE
        val liquid = ticker.volume24h > BigDecimal("500000")
        val stable = regime.regime == MarketRegime.SIDEWAYS || regime.regime == MarketRegime.LOW_VOLATILITY
        val score = (45 + (if (spreadPct <= BigDecimal("0.25")) 20 else -10) + (if (liquid) 15 else -8) + (if (stable) 15 else -8)).coerceIn(0, 100)
        return StrategyCandidate(StrategyMode.MARKET_MAKING_IMBALANCE, score, actionFromScore(score, 72), "Market-making imbalance proxy: spread≈${spreadPct.setScale(3, RoundingMode.HALF_UP)}%, liquid=$liquid, stableRegime=$stable.", BigDecimal("0.45"), BigDecimal("0.35"))
    }

    private fun fundingNewsRiskOffCandidate(ticker: MarketTicker, candles: Map<Timeframe, List<Candle>>, regime: RegimeAnalysis): StrategyCandidate {
        val riskOff = regime.regime == MarketRegime.RISK_OFF || regime.regime == MarketRegime.TRENDING_DOWN || ticker.priceChangePercent24h < BigDecimal("-3.0")
        val score = if (riskOff) 25 else 58
        val action = if (riskOff) SignalAction.SELL else actionFromScore(score, 72)
        return StrategyCandidate(StrategyMode.FUNDING_NEWS_RISK_OFF, score, action, "Funding/news-event risk-off: riskOff=$riskOff, 24h=${ticker.priceChangePercent24h}%, regime=${regime.regime}.", BigDecimal("0.4"), BigDecimal("0.35"))
    }

    private fun pairsRelativeStrengthCandidate(ticker: MarketTicker, candles: Map<Timeframe, List<Candle>>, regime: RegimeAnalysis): StrategyCandidate {
        val c = baseCandles(candles)
        if (c.size < 25) return StrategyCandidate(StrategyMode.PAIRS_RELATIVE_STRENGTH, 0, SignalAction.WAIT, "Pairs/relative strength: not enough candles.", BigDecimal.ZERO, BigDecimal.ZERO)
        val closes = c.map { it.close }
        val rel = TechnicalIndicators.percentChange(closes.takeLast(24).first(), closes.last())
        val strong = rel > BigDecimal("0.75") && regime.regime != MarketRegime.TRENDING_DOWN
        val score = (48 + (if (strong) 26 else 0) + (if (ticker.volume24h > BigDecimal("1000000")) 10 else 0)).coerceIn(0, 100)
        return StrategyCandidate(StrategyMode.PAIRS_RELATIVE_STRENGTH, score, actionFromScore(score, 72), "Relative-strength rotation: 24-candle relative move≈${rel.setScale(2, RoundingMode.HALF_UP)}%, strong=$strong.", BigDecimal("1.25"), BigDecimal("0.8"))
    }

    private fun dcaCrashProtectionCandidate(ticker: MarketTicker, candles: Map<Timeframe, List<Candle>>, regime: RegimeAnalysis): StrategyCandidate {
        val crash = ticker.priceChangePercent24h < BigDecimal("-8.0") || regime.regime == MarketRegime.HIGH_VOLATILITY
        val dip = ticker.priceChangePercent24h < BigDecimal("-2.0") && !crash
        val score = when {
            crash -> 20
            dip -> 64
            else -> 48
        }
        return StrategyCandidate(StrategyMode.DCA_CRASH_PROTECTION, score, actionFromScore(score, 70), "DCA crash-protection: dip=$dip, crashBlock=$crash, 24h=${ticker.priceChangePercent24h}%.", BigDecimal("0.85"), BigDecimal("0.75"))
    }

    private fun momentumSpikeContinuationCandidate(ticker: MarketTicker, candles: Map<Timeframe, List<Candle>>, regime: RegimeAnalysis): StrategyCandidate {
        val c = baseCandles(candles)
        if (c.size < 30) return StrategyCandidate(StrategyMode.MOMENTUM_SPIKE_CONTINUATION, 0, SignalAction.WAIT, "Momentum spike: not enough candles.", BigDecimal.ZERO, BigDecimal.ZERO)
        val closes = c.map { it.close }
        val shortMove = TechnicalIndicators.percentChange(closes.takeLast(6).first(), closes.last())
        val volumeSpike = c.last().volume > avgVolume(c.takeLast(30).dropLast(1)).multiply(BigDecimal("1.7"))
        val continuation = shortMove > BigDecimal("1.0") && volumeSpike && regime.regime != MarketRegime.RISK_OFF
        val score = (42 + (if (continuation) 36 else 0) + (if (ticker.priceChangePercent24h > BigDecimal.ZERO) 8 else 0)).coerceIn(0, 100)
        return StrategyCandidate(StrategyMode.MOMENTUM_SPIKE_CONTINUATION, score, actionFromScore(score, 78), "Momentum spike continuation: shortMove≈${shortMove.setScale(2, RoundingMode.HALF_UP)}%, volumeSpike=$volumeSpike.", BigDecimal("1.4"), BigDecimal("0.9"))
    }

    private fun volumeAnomalyWhaleMoveCandidate(ticker: MarketTicker, candles: Map<Timeframe, List<Candle>>, regime: RegimeAnalysis): StrategyCandidate {
        val c = baseCandles(candles)
        if (c.size < 35) return StrategyCandidate(StrategyMode.VOLUME_ANOMALY_WHALE_MOVE, 0, SignalAction.WAIT, "Volume anomaly: not enough candles.", BigDecimal.ZERO, BigDecimal.ZERO)
        val last = c.last()
        val avgVol = avgVolume(c.takeLast(34).dropLast(1))
        val anomaly = last.volume > avgVol.multiply(BigDecimal("2.2"))
        val green = last.close > last.open
        val score = (40 + (if (anomaly) 28 else 0) + (if (green) 16 else -8) + (if (ticker.volume24h > BigDecimal("1000000")) 8 else 0)).coerceIn(0, 100)
        return StrategyCandidate(StrategyMode.VOLUME_ANOMALY_WHALE_MOVE, score, actionFromScore(score, 76), "Volume anomaly/whale-move: anomaly=$anomaly, greenCandle=$green, lastVol=${last.volume}.", BigDecimal("1.3"), BigDecimal("0.85"))
    }

    private fun avgVolume(candles: List<Candle>): BigDecimal =
        if (candles.isEmpty()) BigDecimal.ZERO else candles.fold(BigDecimal.ZERO) { acc, c -> acc.add(c.volume) }.divide(BigDecimal(candles.size), 8, RoundingMode.HALF_UP)

    private fun atrPercent(candles: List<Candle>): BigDecimal {
        if (candles.size < 20) return BigDecimal("1.0")
        val close = candles.last().close
        val atr = TechnicalIndicators.atr(candles, 14)
        return if (close > BigDecimal.ZERO) atr.divide(close, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100")) else BigDecimal("1.0")
    }


    private fun actionFromScore(score: Int, buyThreshold: Int): SignalAction = when {
        score >= buyThreshold + 10 -> SignalAction.BUY
        score >= buyThreshold -> SignalAction.SMALL_BUY
        score >= 58 -> SignalAction.WATCH
        score >= 45 -> SignalAction.WAIT
        else -> SignalAction.AVOID
    }
}
