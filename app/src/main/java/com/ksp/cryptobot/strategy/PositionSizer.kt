package com.ksp.cryptobot.strategy

import com.ksp.cryptobot.core.*
import java.math.BigDecimal
import java.math.RoundingMode

class PositionSizer {
    fun size(settings: BotSettings, score: Int, regime: MarketRegime, memoryAdjustment: Int, news: NewsEventScore): BigDecimal {
        val confidenceMultiplier = when {
            score >= 88 -> BigDecimal("1.00")
            score >= 80 -> BigDecimal("0.80")
            score >= 72 -> BigDecimal("0.55")
            else -> BigDecimal("0.25")
        }
        val regimeMultiplier = when (regime) {
            MarketRegime.TRENDING_UP -> BigDecimal("1.00")
            MarketRegime.LOW_VOLATILITY -> BigDecimal("0.85")
            MarketRegime.SIDEWAYS -> BigDecimal("0.55")
            MarketRegime.HIGH_VOLATILITY -> BigDecimal("0.30")
            MarketRegime.NEWS_DRIVEN -> BigDecimal("0.45")
            MarketRegime.RISK_OFF, MarketRegime.TRENDING_DOWN -> BigDecimal("0.15")
        }
        val memoryMultiplier = when {
            memoryAdjustment >= 6 -> BigDecimal("1.10")
            memoryAdjustment <= -8 -> BigDecimal("0.45")
            else -> BigDecimal.ONE
        }
        val newsMultiplier = when {
            news.blocksTrading -> BigDecimal.ZERO
            news.sentimentScore > 10 -> BigDecimal("1.08")
            news.sentimentScore < -10 -> BigDecimal("0.60")
            else -> BigDecimal.ONE
        }
        return settings.maxPositionEur
            .multiply(confidenceMultiplier)
            .multiply(regimeMultiplier)
            .multiply(memoryMultiplier)
            .multiply(newsMultiplier)
            .setScale(2, RoundingMode.DOWN)
    }
}
