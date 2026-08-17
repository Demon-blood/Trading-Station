package com.ksp.cryptobot.governance

import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.MarketTicker
import java.math.BigDecimal
import java.math.RoundingMode

class AnomalyFirewall {
    fun evaluate(settings: BotSettings, ticker: MarketTicker, candles: List<Candle>): AnomalyAssessment {
        if (ticker.lastPrice <= BigDecimal.ZERO || ticker.bid <= BigDecimal.ZERO || ticker.ask <= BigDecimal.ZERO) {
            return AnomalyAssessment(false, "CRITICAL", "Ticker contains zero/negative bid, ask, or last price.")
        }
        if (ticker.ask < ticker.bid) {
            return AnomalyAssessment(false, "CRITICAL", "Ticker is crossed: ask is below bid.")
        }
        val spread = ticker.ask.subtract(ticker.bid)
            .divide(ticker.lastPrice, 10, RoundingMode.HALF_UP)
            .multiply(BigDecimal("100"))
        val anomalySpread = settings.maxSpreadPercent.multiply(BigDecimal("1.50"))
        if (spread > anomalySpread) {
            return AnomalyAssessment(false, "HIGH", "Spread spike ${spread.setScale(3, RoundingMode.HALF_UP)}% exceeds anomaly limit ${anomalySpread.setScale(3, RoundingMode.HALF_UP)}%.")
        }
        if (candles.size < 60) {
            return AnomalyAssessment(false, "MEDIUM", "Insufficient candles for safe automated decision: ${candles.size}.")
        }
        val recent = candles.takeLast(30).map { it.close }.filter { it > BigDecimal.ZERO }
        if (recent.size >= 20) {
            val baseline = recent.dropLast(1).sorted()[recent.dropLast(1).size / 2]
            val last = recent.last()
            if (baseline > BigDecimal.ZERO) {
                val deviation = last.subtract(baseline).abs()
                    .divide(baseline, 10, RoundingMode.HALF_UP)
                    .multiply(BigDecimal("100"))
                if (deviation > BigDecimal("8.0")) {
                    return AnomalyAssessment(false, "HIGH", "Suspicious candle displacement: last close deviates ${deviation.setScale(2, RoundingMode.HALF_UP)}% from recent median.")
                }
            }
        }
        val recentVolumes = candles.takeLast(20).map { it.volume }
        if (recentVolumes.isNotEmpty() && recentVolumes.takeLast(5).fold(BigDecimal.ZERO, BigDecimal::add) <= BigDecimal.ZERO) {
            return AnomalyAssessment(false, "HIGH", "Recent candles have zero volume; feed may be frozen or symbol illiquid.")
        }
        return AnomalyAssessment(true, "OK", "Market data anomaly checks passed.")
    }
}
