package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.Candle
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sqrt

class AdvancedRegimeEngine {
    fun detect(candles: List<Candle>): AdvancedRegimeProfile {
        if (candles.size < 60) return AdvancedRegimeProfile(
            "UNKNOWN", "UNKNOWN", "UNKNOWN", "NEUTRAL", 0.0, emptySet(), emptySet(),
            "Not enough candles for advanced regime detection (${candles.size}/60)."
        )
        val closes = candles.map { it.close.toDouble() }
        val e20 = ema(closes, 20)
        val e50 = ema(closes, 50)
        val mom12 = pct(closes.getOrNull(closes.size - 12) ?: closes.first(), closes.last())
        val atr = atrPct(candles, 14)
        val vol = stdPct(closes.takeLast(20))
        val rsi = rsi(closes, 14)

        val trend = when {
            e20 > e50 && mom12 > 0.20 -> "BULL_TREND"
            e20 < e50 && mom12 < -0.20 -> "BEAR_TREND"
            else -> "RANGE"
        }
        val volatility = when {
            atr >= 1.50 || vol >= 1.25 -> "HIGH_VOLATILITY"
            atr <= 0.35 && vol <= 0.35 -> "LOW_VOLATILITY"
            else -> "NORMAL_VOLATILITY"
        }
        val risk = when {
            trend == "BEAR_TREND" && rsi < 45.0 -> "RISK_OFF"
            trend == "BULL_TREND" && rsi > 50.0 -> "RISK_ON"
            else -> "NEUTRAL"
        }
        val regime = when {
            trend == "BULL_TREND" && volatility == "HIGH_VOLATILITY" -> "TRENDING_HIGH_VOL"
            trend == "BULL_TREND" -> "TRENDING"
            trend == "RANGE" && volatility == "LOW_VOLATILITY" -> "LOW_VOL_RANGE"
            trend == "RANGE" -> "RANGING"
            volatility == "HIGH_VOLATILITY" -> "RISK_OFF_HIGH_VOL"
            else -> "RISK_OFF"
        }
        val allowed = linkedSetOf<String>()
        val blocked = linkedSetOf<String>()
        if (regime in setOf("TRENDING", "TRENDING_HIGH_VOL")) {
            allowed += setOf("VOLATILITY_BREAKOUT","PULLBACK_CONTINUATION","EMA_TREND_RIDER","SUPERTREND","BOLLINGER_SQUEEZE_BREAKOUT","DONCHIAN_CHANNEL_BREAKOUT","KELTNER_CHANNEL_BREAKOUT","MACD_TREND_CROSS","ADX_TREND_PULLBACK","PARABOLIC_SAR_FLIP","ICHIMOKU_CLOUD_BREAKOUT","ROLLING_RANGE_EXPANSION")
            blocked += "MEAN_REVERSION"
        }
        if (regime in setOf("RANGING", "LOW_VOL_RANGE")) {
            allowed += setOf("MEAN_REVERSION","SUPPORT_RESISTANCE_BOUNCE","VWAP_RECLAIM","RSI_DIVERGENCE_REVERSAL","STOCHASTIC_OVERSOLD_REVERSAL","CCI_MEAN_REVERSION","VWAP_DEVIATION_REVERSION")
            if (regime == "LOW_VOL_RANGE") allowed += setOf("BOLLINGER_SQUEEZE_BREAKOUT","ROLLING_RANGE_EXPANSION")
            blocked += "VOLATILITY_BREAKOUT"
        }
        if (regime.startsWith("RISK_OFF")) {
            allowed += setOf("LIQUIDITY_SWEEP_REVERSAL","RSI_DIVERGENCE_REVERSAL","SUPPORT_RESISTANCE_BOUNCE","STOCHASTIC_OVERSOLD_REVERSAL","CCI_MEAN_REVERSION","VWAP_DEVIATION_REVERSION")
            blocked += setOf("PULLBACK_CONTINUATION","EMA_TREND_RIDER")
        }
        val reason = "regime=$regime; trend=$trend; volatility=$volatility; risk=$risk; EMA20=${f(e20)}; EMA50=${f(e50)}; mom12=${f(mom12)}%; ATR=${f(atr)}%; RSI=${f(rsi)}"
        val score = when (trend) { "BULL_TREND" -> 1.0; "BEAR_TREND" -> -0.75; else -> 0.0 }
        return AdvancedRegimeProfile(regime, trend, volatility, risk, score, allowed, blocked, reason)
    }

    fun weight(profile: AdvancedRegimeProfile, strategy: String): Pair<Double, String> {
        val base = when {
            strategy in profile.blockedFamilies -> 0.70
            profile.allowedFamilies.isNotEmpty() && strategy !in profile.allowedFamilies && !strategy.startsWith("PUMP_") -> 0.85
            else -> 1.05
        }
        return base to "${if (base < 0.8) "soft-blocked" else if (base < 1.0) "non-preferred" else "preferred"} in ${profile.regime}; regimeWeight=${"%.2f".format(base)}"
    }

    private fun ema(values: List<Double>, period: Int): Double {
        if (values.isEmpty()) return 0.0
        val p = period.coerceAtLeast(1)
        val k = 2.0 / (p + 1.0)
        var out = values.take(p).average().takeIf { !it.isNaN() } ?: values.first()
        for (v in values.drop(p)) out = v * k + out * (1.0 - k)
        return out
    }
    private fun pct(a: Double, b: Double): Double = if (a == 0.0) 0.0 else (b - a) / a * 100.0
    private fun stdPct(values: List<Double>): Double {
        if (values.size < 3) return 0.0
        val returns = values.zipWithNext().mapNotNull { (a,b) -> if (a == 0.0) null else (b-a)/a*100.0 }
        if (returns.isEmpty()) return 0.0
        val m = returns.average(); return sqrt(returns.sumOf { (it-m)*(it-m) } / returns.size)
    }
    private fun atrPct(c: List<Candle>, period: Int): Double {
        if (c.size < period + 1) return 0.0
        var prev = c[c.size-period-1].close.toDouble(); val tr = mutableListOf<Double>()
        for (x in c.takeLast(period)) {
            val h=x.high.toDouble(); val l=x.low.toDouble(); val close=x.close.toDouble()
            val raw=maxOf(h-l, kotlin.math.abs(h-prev), kotlin.math.abs(l-prev))
            if (close>0) tr += raw/close*100.0
            prev=close
        }
        return tr.average()
    }
    private fun rsi(v: List<Double>, period: Int): Double {
        if (v.size < period + 1) return 50.0
        val d=v.takeLast(period+1).zipWithNext().map { it.second-it.first }
        val gains=d.filter { it>0 }.sum()/period; val losses=-d.filter { it<0 }.sum()/period
        if (losses<=1e-12) return 100.0
        return 100.0-(100.0/(1.0+gains/losses))
    }
    private fun f(v: Double)=BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString()
}
