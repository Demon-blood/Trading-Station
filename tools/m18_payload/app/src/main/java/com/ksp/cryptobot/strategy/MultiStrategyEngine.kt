package com.ksp.cryptobot.strategy

import com.ksp.cryptobot.cloudshare.CloudShareCollectiveCache
import com.ksp.cryptobot.core.*
import java.math.BigDecimal

/**
 * M18 truth-gated strategy selector.
 *
 * AUTO can only rank strategies whose required inputs/execution architecture are
 * actually present. Explicitly selecting an unsupported named mode returns WAIT
 * with TRUTH_BLOCKED instead of running a proxy under that name.
 */
class MultiStrategyEngine(
    private val scalper: MultiTimeframeScalpingStrategy = MultiTimeframeScalpingStrategy()
) {
    fun chooseBest(
        ticker: MarketTicker,
        candlesByTimeframe: Map<Timeframe, List<Candle>>,
        settings: BotSettings,
        regime: RegimeAnalysis
    ): StrategyCandidate {
        if (settings.strategyMode != StrategyMode.AUTO) {
            return evaluateMode(settings.strategyMode, ticker, candlesByTimeframe, settings, regime)
        }

        val candidates = StrategyTruthRegistry.autoSelectable()
            .map { mode -> evaluateMode(mode, ticker, candlesByTimeframe, settings, regime) }

        val selected = candidates.maxByOrNull { candidate ->
            val collectiveTieBreak = CloudShareCollectiveCache
                .score(ticker.symbol, candidate.mode.name, regime.regime.name, "15m")
                .adjustment
                .coerceIn(-2, 2)
            candidate.score + collectiveTieBreak
        } ?: return StrategyCandidate(
            StrategyMode.AUTO,
            0,
            SignalAction.WAIT,
            "M18 truth gate: no currently implemented strategy had enough valid data.",
            BigDecimal.ZERO,
            BigDecimal.ZERO
        )

        val collectiveHint = CloudShareCollectiveCache.score(
            ticker.symbol,
            selected.mode.name,
            regime.regime.name,
            "15m"
        )

        return if (
            CloudShareCollectiveCache.snapshot().enabled &&
            collectiveHint.ready
        ) {
            selected.copy(
                reason = selected.reason +
                    " | Collective tie-break hint only; it cannot bypass M18 truth/entry gates. " +
                    collectiveHint.reason
            )
        } else selected
    }

    private fun evaluateMode(
        mode: StrategyMode,
        ticker: MarketTicker,
        candlesByTimeframe: Map<Timeframe, List<Candle>>,
        settings: BotSettings,
        regime: RegimeAnalysis
    ): StrategyCandidate {
        val spec = StrategyTruthRegistry.spec(mode)
            ?: return StrategyCandidate(
                StrategyMode.AUTO, 0, SignalAction.WAIT,
                "AUTO is a selector, not a stand-alone strategy.",
                BigDecimal.ZERO, BigDecimal.ZERO
            )

        if (!spec.liveSelectable) {
            return StrategyCandidate(
                mode = mode,
                score = 0,
                action = SignalAction.WAIT,
                reason = StrategyTruthRegistry.truthBlockedReason(mode),
                takeProfitPercent = BigDecimal.ZERO,
                stopLossPercent = BigDecimal.ZERO
            )
        }

        if (mode == StrategyMode.SCALPING) {
            return scalpingCandidate(ticker.symbol, candlesByTimeframe, settings, regime, spec)
        }

        val candles = StrategyTruthRules.primaryCandles(mode, candlesByTimeframe)
        val rule = StrategyTruthRules.evaluate(mode, candles, settings)

        if (!rule.enoughData) {
            return StrategyCandidate(
                mode, 0, SignalAction.WAIT,
                "M18 ${spec.canonicalName}: ${rule.reason}",
                BigDecimal.ZERO, BigDecimal.ZERO
            )
        }

        val regimeBlocked = regime.regime in spec.unsuitableRegimes
        val regimePreferred =
            spec.suitableRegimes.isEmpty() || regime.regime in spec.suitableRegimes

        val score = (
            rule.score +
                (if (regimePreferred) 6 else -8) +
                (if (regimeBlocked) -30 else 0)
            ).coerceIn(0, 100)

        val actionable = rule.entry && !regimeBlocked && regimePreferred
        val action = when {
            actionable && score >= settings.minStrategyScoreToBuy + 10 -> SignalAction.BUY
            actionable && score >= settings.minStrategyScoreToBuy -> SignalAction.SMALL_BUY
            rule.entry && !actionable -> SignalAction.WAIT
            score >= 58 -> SignalAction.WATCH
            score >= 45 -> SignalAction.WAIT
            else -> SignalAction.AVOID
        }

        val reason = buildString {
            append("M18 TRUTH_OK ")
            append(spec.canonicalName)
            append(": ")
            append(rule.reason)
            append(" Regime=")
            append(regime.regime)
            append(", preferred=")
            append(regimePreferred)
            append(", blocked=")
            append(regimeBlocked)
            append(", entryDefinition=")
            append(spec.entryDefinition)
            append(" Confirmation=")
            append(spec.confirmationDefinition)
            append(" Invalidation=")
            append(spec.invalidationDefinition)
        }

        return StrategyCandidate(
            mode = mode,
            score = score,
            action = action,
            reason = reason,
            takeProfitPercent = rule.takeProfitPercent,
            stopLossPercent = rule.stopLossPercent
        )
    }

    private fun scalpingCandidate(
        symbol: String,
        candles: Map<Timeframe, List<Candle>>,
        settings: BotSettings,
        regime: RegimeAnalysis,
        spec: StrategyTruthSpec
    ): StrategyCandidate {
        val requiredFrames = listOf(Timeframe.M5, Timeframe.M15, Timeframe.H1)
        val missing = requiredFrames.filter {
            candles[it].orEmpty().size < settings.emaSlowPeriod + 5
        }
        if (missing.isNotEmpty()) {
            return StrategyCandidate(
                StrategyMode.SCALPING,
                0,
                SignalAction.WAIT,
                "M18 multi-timeframe scalper truth-blocked for this scan: insufficient ${missing.joinToString()} candle history.",
                BigDecimal.ZERO,
                BigDecimal.ZERO
            )
        }

        if (regime.regime in spec.unsuitableRegimes) {
            return StrategyCandidate(
                StrategyMode.SCALPING,
                20,
                SignalAction.WAIT,
                "M18 multi-timeframe scalper regime-blocked in ${regime.regime}. ${spec.invalidationDefinition}",
                BigDecimal.ZERO,
                BigDecimal.ZERO
            )
        }

        val signal = scalper.evaluate(symbol, candles, settings)
        val actionable = signal.action in setOf(SignalAction.BUY, SignalAction.SMALL_BUY)
        val action = if (actionable) signal.action else when {
            signal.strategyScore >= 58 -> SignalAction.WATCH
            signal.strategyScore >= 45 -> SignalAction.WAIT
            else -> SignalAction.AVOID
        }

        return StrategyCandidate(
            StrategyMode.SCALPING,
            signal.strategyScore,
            action,
            "M18 TRUTH_OK ${spec.canonicalName}: ${signal.explanation}. ${spec.entryDefinition} ${spec.confirmationDefinition}",
            signal.suggestedTakeProfitPercent,
            signal.suggestedStopLossPercent
        )
    }
}
