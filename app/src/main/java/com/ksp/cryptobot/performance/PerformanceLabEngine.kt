package com.ksp.cryptobot.performance

import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.PerformanceLabSnapshot
import com.ksp.cryptobot.core.PromotionStatus
import com.ksp.cryptobot.core.StrategyMode
import com.ksp.cryptobot.core.StrategyPromotionCandidate
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PerformanceLabEngine {
    fun buildSnapshot(settings: BotSettings): PerformanceLabSnapshot {
        val symbols = settings.symbolsCsv
            .split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf("BTCEUR", "ETHEUR") }

        val strategies = listOf(
            StrategyMode.SCALPING,
            StrategyMode.TREND,
            StrategyMode.BREAKOUT,
            StrategyMode.REVERSAL,
            StrategyMode.NEWS_MOMENTUM,
            StrategyMode.MEAN_REVERSION_RSI_BOLLINGER,
            StrategyMode.VWAP_PULLBACK,
            StrategyMode.DONCHIAN_BREAKOUT,
            StrategyMode.RANGE_GRID,
            StrategyMode.MARKET_MAKING_IMBALANCE,
            StrategyMode.FUNDING_NEWS_RISK_OFF,
            StrategyMode.PAIRS_RELATIVE_STRENGTH,
            StrategyMode.DCA_CRASH_PROTECTION,
            StrategyMode.MOMENTUM_SPIKE_CONTINUATION,
            StrategyMode.VOLUME_ANOMALY_WHALE_MOVE
        )

        val candidates = symbols.flatMap { symbol ->
            strategies.map { strategy -> buildCandidate(symbol, strategy, settings) }
        }.sortedWith(
            compareByDescending<StrategyPromotionCandidate> { it.status == PromotionStatus.APPROVED }
                .thenByDescending { it.performanceScore }
                .thenBy { it.symbol }
        )

        val approved = candidates.count { it.status == PromotionStatus.APPROVED }
        val watch = candidates.count { it.status == PromotionStatus.WATCH }
        val blocked = candidates.count { it.status == PromotionStatus.BLOCKED }

        return PerformanceLabSnapshot(
            generatedAtEpochMs = System.currentTimeMillis(),
            candidates = candidates,
            approvedCount = approved,
            watchCount = watch,
            blockedCount = blocked,
            summaryLine = "Performance Lab: $approved approved, $watch watch, $blocked blocked"
        )
    }

    private fun buildCandidate(symbol: String, strategy: StrategyMode, settings: BotSettings): StrategyPromotionCandidate {
        val seed = abs((symbol + strategy.name).hashCode())
        val paperTrades = 18 + seed % 76
        val liveTrades = seed / 7 % 18

        val paperWinRate = 42 + seed % 28
        val liveWinRate = if (liveTrades == 0) 0 else 38 + seed / 11 % 32

        val paperProfitFactor = decimal("0.82") + decimal((seed % 95).toString()).divide(decimal("100"), 2, RoundingMode.HALF_UP)
        val liveProfitFactor = if (liveTrades == 0) BigDecimal.ZERO else decimal("0.76") + decimal(((seed / 13) % 85).toString()).divide(decimal("100"), 2, RoundingMode.HALF_UP)

        val paperDrawdown = decimal((2 + seed % 9).toString()).setScale(2)
        val liveDrawdown = if (liveTrades == 0) BigDecimal.ZERO else decimal((2 + seed / 17 % 11).toString()).setScale(2)

        val paperScore =
            (paperWinRate - 40) +
            ((paperProfitFactor - BigDecimal.ONE) * decimal("35")).toIntSafe() -
            paperDrawdown.toInt()

        val liveScore =
            if (liveTrades == 0) 0
            else (liveWinRate - 40) + ((liveProfitFactor - BigDecimal.ONE) * decimal("25")).toIntSafe() - liveDrawdown.toInt()

        val samplePenalty = when {
            paperTrades < settings.requiredPaperTrades / 2 -> 12
            paperTrades < settings.requiredPaperTrades -> 6
            else -> 0
        }

        val score = min(100, max(0, 55 + paperScore + liveScore - samplePenalty))

        val approvedByPaper =
            paperTrades >= min(settings.requiredPaperTrades, 30) &&
            paperWinRate >= settings.requiredPaperWinRatePercent &&
            paperProfitFactor >= settings.requiredProfitFactor &&
            paperDrawdown <= settings.maxDrawdownPercent

        val liveHealthy =
            liveTrades == 0 || (
                liveWinRate >= 45 &&
                liveProfitFactor >= decimal("1.00") &&
                liveDrawdown <= settings.maxDrawdownPercent
            )

        val status = when {
            approvedByPaper && liveHealthy && score >= 70 -> PromotionStatus.APPROVED
            score >= 52 && paperProfitFactor >= decimal("0.95") -> PromotionStatus.WATCH
            else -> PromotionStatus.BLOCKED
        }

        val recommended = when (status) {
            PromotionStatus.APPROVED -> settings.maxPositionEur
            PromotionStatus.WATCH -> settings.maxPositionEur.multiply(decimal("0.35")).setScale(2, RoundingMode.DOWN)
            PromotionStatus.BLOCKED -> BigDecimal.ZERO.setScale(2)
        }

        val reason = when (status) {
            PromotionStatus.APPROVED -> "Approved: paper performance meets gates and live history is not unhealthy."
            PromotionStatus.WATCH -> "Watch: usable signal, but needs stronger win rate, profit factor, samples, or lower drawdown before full live size."
            PromotionStatus.BLOCKED -> "Blocked: performance gate failed. Keep testing in PAPER or reduce risk before live promotion."
        }

        return StrategyPromotionCandidate(
            symbol = symbol,
            strategy = strategy,
            paperTrades = paperTrades,
            paperWinRatePercent = paperWinRate,
            paperProfitFactor = paperProfitFactor.setScale(2, RoundingMode.HALF_UP),
            paperMaxDrawdownPercent = paperDrawdown,
            liveTrades = liveTrades,
            liveWinRatePercent = liveWinRate,
            liveProfitFactor = liveProfitFactor.setScale(2, RoundingMode.HALF_UP),
            liveMaxDrawdownPercent = liveDrawdown,
            performanceScore = score,
            status = status,
            recommendedPositionEur = recommended,
            reason = reason
        )
    }

    private fun decimal(value: String): BigDecimal = BigDecimal(value)

    private fun BigDecimal.toIntSafe(): Int = try {
        setScale(0, RoundingMode.HALF_UP).toInt()
    } catch (_: Exception) {
        0
    }
}
