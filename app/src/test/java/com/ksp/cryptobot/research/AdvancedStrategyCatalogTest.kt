package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.Timeframe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class AdvancedStrategyCatalogTest {
    private fun candles(tf: Timeframe, count: Int = 240): List<Candle> = (0 until count).map { i ->
        val trend = 100.0 + i * 0.08
        val wave = kotlin.math.sin(i / 7.0) * 0.7
        val close = trend + wave
        val open = close - kotlin.math.sin(i / 5.0) * 0.25
        Candle(
            symbol = "BTCEUR",
            timeframe = tf,
            openTimeEpochMs = i * 60_000L,
            open = BigDecimal.valueOf(open),
            high = BigDecimal.valueOf(maxOf(open, close) + 0.7),
            low = BigDecimal.valueOf(minOf(open, close) - 0.7),
            close = BigDecimal.valueOf(close),
            volume = BigDecimal.valueOf(1_000.0 + (i % 17) * 43.0)
        )
    }

    @Test
    fun fullDesktopAndProfessionalCatalogIsExecutable() {
        val m15 = candles(Timeframe.M15)
        val h1 = candles(Timeframe.H1)
        val h4 = candles(Timeframe.H4)
        val ticker = MarketTicker(
            symbol = "BTCEUR",
            lastPrice = m15.last().close,
            bid = m15.last().close.subtract(BigDecimal("0.05")),
            ask = m15.last().close.add(BigDecimal("0.05")),
            volume24h = BigDecimal("25000000"),
            priceChangePercent24h = BigDecimal("2.0")
        )
        val regimeEngine = AdvancedRegimeEngine()
        val engine = AdvancedStrategyVoteEngine(regimeEngine)
        val votes = engine.evaluate(
            BotSettings(), ticker,
            mapOf(Timeframe.M15 to m15, Timeframe.H1 to h1, Timeframe.H4 to h4),
            emptyList(), regimeEngine.detect(m15), professionalEnabled = true, upstreamNewsScore = 0
        )
        val names = votes.map { it.name }.toSet()
        val desktop = setOf(
            "VOLATILITY_BREAKOUT","PULLBACK_CONTINUATION","MEAN_REVERSION","VWAP_RECLAIM",
            "LIQUIDITY_SWEEP_REVERSAL","BOLLINGER_SQUEEZE_BREAKOUT","EMA_TREND_RIDER",
            "RSI_DIVERGENCE_REVERSAL","SUPERTREND","SUPPORT_RESISTANCE_BOUNCE",
            "DONCHIAN_CHANNEL_BREAKOUT","KELTNER_CHANNEL_BREAKOUT","MACD_TREND_CROSS",
            "STOCHASTIC_OVERSOLD_REVERSAL","CCI_MEAN_REVERSION","ADX_TREND_PULLBACK",
            "PARABOLIC_SAR_FLIP","ICHIMOKU_CLOUD_BREAKOUT","ROLLING_RANGE_EXPANSION",
            "VWAP_DEVIATION_REVERSION","NEWS_MOMENTUM_CONFIRMATION","SPREAD_LIQUIDITY_SCALP",
            "MULTI_TIMEFRAME_CONFIRMATION","EXIT_OPTIMIZER"
        )
        val professional = setOf(
            "PRO_TREND_MTF_BREAKOUT","PRO_TREND_PULLBACK_ATR","PRO_RANGE_BOLLINGER_VWAP",
            "PRO_VOLUME_CONFIRMED_BREAKOUT","PRO_SUPERTREND_CONFIRMED","PRO_PSAR_TREND_FLIP",
            "PRO_ICHIMOKU_MTF","PRO_ANCHORED_VWAP_RECLAIM","PRO_COMPRESSION_EXPANSION",
            "PRO_POSITION_TREND_FOLLOWING","PRO_DMI_ADX_TREND_PULLBACK","PRO_LONG_HORIZON_50_200",
            "PRO_TIME_SERIES_MOMENTUM","PRO_DUAL_DONCHIAN_BREAKOUT","PRO_VWAP_TREND_RETEST",
            "PRO_MACD_MTF_CONFIRMATION","PRO_BREAKOUT_RETEST","PRO_OBV_ACCUMULATION",
            "PRO_PRICE_STRUCTURE_TREND","PRO_ZSCORE_RANGE_REVERSION"
        )
        val filters = setOf("FILTER_EXECUTION_QUALITY","FILTER_MTF_ALIGNMENT","FILTER_VOLUME_CONFIRMATION")
        assertTrue("desktop catalog missing: ${desktop - names}", names.containsAll(desktop))
        assertTrue("professional catalog missing: ${professional - names}", names.containsAll(professional))
        assertTrue("professional filters missing: ${filters - names}", names.containsAll(filters))
        assertEquals(24, desktop.size)
        assertEquals(20, professional.size)
        assertEquals(3, filters.size)
        assertTrue(votes.none { it.reason.contains("proxy", ignoreCase = true) })
    }
}
