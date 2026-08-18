package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.Candle
import java.math.BigDecimal
import java.math.RoundingMode

/** Desktop v1.0.50 regime parity plus compatibility mapping for professional variants. */
class AdvancedRegimeEngine {
    fun detect(candles: List<Candle>): AdvancedRegimeProfile {
        if (candles.size < 60) return AdvancedRegimeProfile(
            "UNKNOWN", "UNKNOWN", "UNKNOWN", "NEUTRAL", 0.0, emptySet(), emptySet(),
            "Not enough candles for advanced regime detection (${candles.size}/60)."
        )
        val closes = StrategyMath.closes(candles)
        val e20 = StrategyMath.ema(closes, 20)
        val e50 = StrategyMath.ema(closes, 50)
        val mom12 = StrategyMath.momentumPct(closes, 12)
        val atr = StrategyMath.atrPct(candles, 14)
        val vol = StrategyMath.volatilityPct(closes, 20)
        val rsi = StrategyMath.rsi(closes, 14)

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
            allowed += setOf(
                "VOLATILITY_BREAKOUT","PULLBACK_CONTINUATION","EMA_TREND_RIDER","SUPERTREND",
                "BOLLINGER_SQUEEZE_BREAKOUT","DONCHIAN_CHANNEL_BREAKOUT","KELTNER_CHANNEL_BREAKOUT",
                "MACD_TREND_CROSS","ADX_TREND_PULLBACK","PARABOLIC_SAR_FLIP","ICHIMOKU_CLOUD_BREAKOUT",
                "ROLLING_RANGE_EXPANSION","PRO_TREND_MTF_BREAKOUT","PRO_TREND_PULLBACK_ATR",
                "PRO_VOLUME_CONFIRMED_BREAKOUT","PRO_SUPERTREND_CONFIRMED","PRO_PSAR_TREND_FLIP",
                "PRO_ICHIMOKU_MTF","PRO_COMPRESSION_EXPANSION","PRO_POSITION_TREND_FOLLOWING",
                "PRO_DMI_ADX_TREND_PULLBACK","PRO_LONG_HORIZON_50_200","PRO_TIME_SERIES_MOMENTUM",
                "PRO_DUAL_DONCHIAN_BREAKOUT","PRO_VWAP_TREND_RETEST","PRO_MACD_MTF_CONFIRMATION", "PRO_BREAKOUT_RETEST", "PRO_OBV_ACCUMULATION", "PRO_PRICE_STRUCTURE_TREND"
            )
            blocked += setOf("MEAN_REVERSION","PRO_RANGE_BOLLINGER_VWAP", "PRO_ZSCORE_RANGE_REVERSION")
        }
        if (regime in setOf("RANGING", "LOW_VOL_RANGE")) {
            allowed += setOf(
                "MEAN_REVERSION","SUPPORT_RESISTANCE_BOUNCE","VWAP_RECLAIM","RSI_DIVERGENCE_REVERSAL",
                "STOCHASTIC_OVERSOLD_REVERSAL","CCI_MEAN_REVERSION","VWAP_DEVIATION_REVERSION",
                "PRO_RANGE_BOLLINGER_VWAP", "PRO_ZSCORE_RANGE_REVERSION","PRO_ANCHORED_VWAP_RECLAIM"
            )
            if (regime == "LOW_VOL_RANGE") allowed += setOf("BOLLINGER_SQUEEZE_BREAKOUT","ROLLING_RANGE_EXPANSION","PRO_COMPRESSION_EXPANSION")
            blocked += setOf("VOLATILITY_BREAKOUT","PRO_TREND_MTF_BREAKOUT","PRO_DMI_ADX_TREND_PULLBACK","PRO_DUAL_DONCHIAN_BREAKOUT")
        }
        if (regime.startsWith("RISK_OFF")) {
            allowed += setOf(
                "LIQUIDITY_SWEEP_REVERSAL","RSI_DIVERGENCE_REVERSAL","SUPPORT_RESISTANCE_BOUNCE",
                "STOCHASTIC_OVERSOLD_REVERSAL","CCI_MEAN_REVERSION","VWAP_DEVIATION_REVERSION",
                "PRO_RANGE_BOLLINGER_VWAP", "PRO_ZSCORE_RANGE_REVERSION","PRO_ANCHORED_VWAP_RECLAIM"
            )
            blocked += setOf("PULLBACK_CONTINUATION","EMA_TREND_RIDER","PRO_TREND_PULLBACK_ATR","PRO_POSITION_TREND_FOLLOWING","PRO_DMI_ADX_TREND_PULLBACK","PRO_LONG_HORIZON_50_200","PRO_TIME_SERIES_MOMENTUM")
        }
        val reason = "regime=$regime; trend=$trend; volatility=$volatility; risk=$risk; EMA20/50=${f(e20)}/${f(e50)}; mom12=${f(mom12)}%; ATR=${f(atr)}%; RSI=${f(rsi)}"
        val score = when (trend) { "BULL_TREND" -> 1.0; "BEAR_TREND" -> -0.75; else -> 0.0 }
        return AdvancedRegimeProfile(regime, trend, volatility, risk, score, allowed, blocked, reason)
    }

    fun weight(profile: AdvancedRegimeProfile, strategy: String): Pair<Double, String> {
        if (strategy.startsWith("FILTER_") || strategy == "EXIT_OPTIMIZER" || strategy in setOf("NEWS_MOMENTUM_CONFIRMATION","SPREAD_LIQUIDITY_SCALP","MULTI_TIMEFRAME_CONFIRMATION")) {
            return 1.0 to "confirmation/filter layer; regimeWeight=1.00"
        }
        val base = when {
            strategy in profile.blockedFamilies -> 0.70
            profile.allowedFamilies.isNotEmpty() && strategy !in profile.allowedFamilies && !strategy.startsWith("PUMP_") -> 0.85
            else -> 1.05
        }
        val label = if (base < .8) "soft-blocked" else if (base < 1.0) "non-preferred" else "preferred"
        return base to "$label in ${profile.regime}; regimeWeight=${"%.2f".format(base)}"
    }

    private fun f(v: Double)=BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}
