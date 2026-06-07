package com.ksp.cryptobot.strategy

import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.MarketTicker
import java.math.BigDecimal
import java.math.RoundingMode

class RiskEngine {
    fun spreadPercent(ticker: MarketTicker): BigDecimal {
        if (ticker.ask <= BigDecimal.ZERO || ticker.bid <= BigDecimal.ZERO) return BigDecimal("999")
        return ticker.ask.subtract(ticker.bid)
            .divide(ticker.ask, 8, RoundingMode.HALF_UP)
            .multiply(BigDecimal("100"))
    }

    fun isTradable(ticker: MarketTicker, settings: BotSettings): Pair<Boolean, String> {
        val spread = spreadPercent(ticker)
        if (spread > settings.maxSpreadPercent) {
            return false to "Spread too wide: $spread% > ${settings.maxSpreadPercent}%"
        }
        if (ticker.volume24h < settings.minVolume24hEur) {
            return false to "24h volume too low: ${ticker.volume24h}"
        }
        if (settings.tradeOnlyBtcEth && ticker.symbol !in setOf("BTCEUR", "ETHEUR", "BTCUSDT", "ETHUSDT")) {
            return false to "Restricted mode allows BTC/ETH only"
        }
        return true to "Risk checks passed"
    }

    fun riskPercent(ticker: MarketTicker): BigDecimal {
        val movement = ticker.priceChangePercent24h.abs()
        val spread = spreadPercent(ticker)
        return movement.add(spread.multiply(BigDecimal("2"))).min(BigDecimal("100"))
    }
}
