package com.ksp.cryptobot.learning

import com.ksp.cryptobot.core.AiDecision
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.SignalAction
import com.ksp.cryptobot.core.StrategyMode
import com.ksp.cryptobot.data.AppDao
import com.ksp.cryptobot.data.LearnedStrategyProfileEntity
import com.ksp.cryptobot.data.LearnedSymbolProfileEntity
import com.ksp.cryptobot.data.LearningFeatureSnapshotEntity
import com.ksp.cryptobot.data.SelfLearningAuditEntity
import com.ksp.cryptobot.data.TradeEntity
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.max
import kotlin.math.min

/**
 * v1.7.1 true self-learning/adaptive multi-strategy layer.
 *
 * This is intentionally explainable and bounded. It learns from real and paper trade outcomes,
 * updates persisted symbol/strategy profiles, then uses those profiles to adjust confidence,
 * minimum-score pressure and sizing hints. It never bypasses risk, balance, quote, exchange or
 * legal restrictions; it only changes scoring and recommendations inside configured limits.
 */
class TrueSelfLearningEngine {
    data class LearningSummary(
        val enabled: Boolean,
        val symbolProfiles: List<LearnedSymbolProfileEntity>,
        val strategyProfiles: List<LearnedStrategyProfileEntity>,
        val audit: List<SelfLearningAuditEntity>,
        val summaryLine: String
    )

    data class DecisionLearningResult(
        val decision: AiDecision,
        val profile: LearnedSymbolProfileEntity?,
        val positionMultiplier: BigDecimal,
        val explanation: String
    )

    data class AdaptiveStrategyResult(
        val selectedStrategy: StrategyMode,
        val source: String,
        val confidencePercent: Int,
        val scoreAdjustment: Int,
        val positionMultiplier: BigDecimal,
        val explanation: String
    )

    data class AdaptiveAutomationResult(
        val decision: com.ksp.cryptobot.core.AutomationDecision,
        val strategyResult: AdaptiveStrategyResult,
        val explanation: String
    )

    suspend fun refreshFromTradeHistory(dao: AppDao, settings: BotSettings): LearningSummary {
        if (!settings.trueSelfLearningEnabled) {
            return LearningSummary(false, emptyList(), emptyList(), emptyList(), "True self-learning disabled.")
        }

        val trades = dao.allTradesSnapshot().take(settings.selfLearningLookbackTrades.coerceAtLeast(20))
        val now = System.currentTimeMillis()
        val symbolProfiles = trades.groupBy { it.symbol.uppercase() }.map { (symbol, rows) ->
            buildSymbolProfile(symbol, rows, settings, now).also { profile ->
                dao.upsertLearnedSymbolProfile(profile)
                dao.insertSelfLearningAudit(SelfLearningAuditEntity(timestampEpochMs = now, eventType = "PROFILE_UPDATE", symbol = symbol, message = profile.explanation))
            }
        }

        val strategyProfiles = trades.groupBy { strategyKeyFromTrade(it) }.map { (strategy, rows) ->
            buildStrategyProfile(strategy, rows, settings, now).also { profile -> dao.upsertLearnedStrategyProfile(profile) }
        }

        val audit = dao.selfLearningAudit(40)
        val liveCount = trades.count { !it.paper }
        val paperCount = trades.count { it.paper }
        val summary = "Learning refreshed: symbols=${symbolProfiles.size}, strategies=${strategyProfiles.size}, trades=${trades.size}, live=$liveCount, paper=$paperCount. Min sample=${settings.selfLearningMinSamples}."
        return LearningSummary(true, symbolProfiles.sortedByDescending { it.updatedAtEpochMs }, strategyProfiles, audit, summary)
    }

    suspend fun adjustDecision(dao: AppDao, decision: AiDecision, ticker: MarketTicker, settings: BotSettings): DecisionLearningResult {
        if (!settings.trueSelfLearningEnabled) return DecisionLearningResult(decision, null, BigDecimal.ONE, "True self-learning disabled.")
        val profile = dao.learnedSymbolProfile(decision.symbol.uppercase())
        if (profile == null || profile.sampleSize < settings.selfLearningMinSamples) {
            val explanation = "Self-learning neutral: not enough samples for ${decision.symbol}. sample=${profile?.sampleSize ?: 0}/${settings.selfLearningMinSamples}."
            return DecisionLearningResult(decision.copy(explanation = decision.explanation + " | $explanation"), profile, BigDecimal.ONE, explanation)
        }

        val now = System.currentTimeMillis()
        if (profile.disabledUntilEpochMs > now && settings.selfLearningAutoDisableEnabled) {
            val explanation = "Self-learning blocked ${decision.symbol}: profile disabled until ${profile.disabledUntilEpochMs}. ${profile.explanation}"
            return DecisionLearningResult(
                decision.copy(finalAction = SignalAction.WAIT, allowedToTrade = false, explanation = decision.explanation + " | $explanation"),
                profile,
                profile.positionMultiplier.toBigDecimalOrNull() ?: BigDecimal.ONE,
                explanation
            )
        }

        val rawBoost = profile.scoreAdjustment.coerceIn(-settings.selfLearningMaxScorePenalty, settings.selfLearningMaxScoreBoost)
        val learnedScore = (decision.finalScore + rawBoost).coerceIn(0, 100)
        val action = learnedAction(decision.finalAction, learnedScore, settings)
        val spreadPenalty = spreadPenalty(ticker, settings)
        val finalScore = (learnedScore - spreadPenalty).coerceIn(0, 100)
        val finalAction = learnedAction(action, finalScore, settings)
        val allowed = decision.allowedToTrade && (finalAction == SignalAction.BUY || finalAction == SignalAction.SMALL_BUY || finalAction == SignalAction.SELL)
        val multiplier = (profile.positionMultiplier.toBigDecimalOrNull() ?: BigDecimal.ONE).coerceIn(BigDecimal("0.10"), BigDecimal("1.50"))
        val explanation = "Self-learning applied: samples=${profile.sampleSize}, win=${profile.winRatePercent}%, pf=${profile.profitFactor}, scoreAdj=$rawBoost, spreadPenalty=$spreadPenalty, size×${multiplier.stripTrailingZeros().toPlainString()}, preferred=${profile.preferredStrategy}."
        return DecisionLearningResult(
            decision.copy(
                finalAction = finalAction,
                finalScore = finalScore,
                confidencePercent = finalScore,
                allowedToTrade = allowed,
                explanation = decision.explanation + " | " + explanation
            ),
            profile,
            multiplier,
            explanation
        )
    }


    suspend fun selectAdaptiveStrategyMode(
        dao: AppDao,
        symbol: String,
        fallback: StrategyMode,
        settings: BotSettings
    ): AdaptiveStrategyResult {
        if (!settings.trueSelfLearningEnabled || !settings.adaptiveStrategyLearningEnabled) {
            return AdaptiveStrategyResult(fallback, "DISABLED", 0, 0, BigDecimal.ONE, "Adaptive strategy learning disabled; using $fallback.")
        }

        val normalized = symbol.uppercase()
        val symbolProfile = dao.learnedSymbolProfile(normalized)
        val strategyProfiles = dao.learnedStrategyProfilesSnapshot()
        val enoughSymbolSamples = (symbolProfile?.sampleSize ?: 0) >= settings.adaptiveStrategyMinSamples

        if (settings.adaptiveStrategyPreferSymbolProfile && enoughSymbolSamples && symbolProfile != null) {
            val preferred = runCatching { StrategyMode.valueOf(symbolProfile.preferredStrategy) }.getOrDefault(StrategyMode.AUTO)
            if (preferred != StrategyMode.AUTO) {
                val adj = symbolProfile.scoreAdjustment.coerceIn(-settings.adaptiveStrategyMaxScorePenalty, settings.adaptiveStrategyMaxScoreBoost)
                val mult = symbolProfile.positionMultiplier.toBigDecimalOrNull() ?: BigDecimal.ONE
                val conf = symbolProfile.confidence.toBigDecimalOrNull()?.toInt() ?: settings.adaptiveStrategySwitchConfidencePercent
                return AdaptiveStrategyResult(
                    selectedStrategy = if (fallback == StrategyMode.AUTO) preferred else fallback,
                    source = "SYMBOL_PROFILE",
                    confidencePercent = conf.coerceIn(0, 100),
                    scoreAdjustment = adj,
                    positionMultiplier = mult.coerceIn(BigDecimal("0.25"), BigDecimal("1.50")),
                    explanation = "Adaptive strategy for $normalized: symbol profile prefers $preferred after ${symbolProfile.sampleSize} samples; scoreAdj=$adj, size×$mult. ${symbolProfile.explanation}"
                )
            }
        }

        val bestStrategy = strategyProfiles
            .filter { it.sampleSize >= settings.adaptiveStrategyMinSamples }
            .maxWithOrNull(compareBy<LearnedStrategyProfileEntity> { it.scoreAdjustment }.thenBy { it.profitFactor.toBigDecimalOrNull() ?: BigDecimal.ZERO })

        if (bestStrategy != null && bestStrategy.scoreAdjustment > 0) {
            val mode = runCatching { StrategyMode.valueOf(bestStrategy.strategyKey) }.getOrDefault(StrategyMode.AUTO)
            if (mode != StrategyMode.AUTO) {
                val mult = bestStrategy.positionMultiplier.toBigDecimalOrNull() ?: BigDecimal.ONE
                val conf = bestStrategy.winRatePercent.toBigDecimalOrNull()?.toInt() ?: settings.adaptiveStrategySwitchConfidencePercent
                return AdaptiveStrategyResult(
                    selectedStrategy = if (fallback == StrategyMode.AUTO) mode else fallback,
                    source = "GLOBAL_STRATEGY_PROFILE",
                    confidencePercent = conf.coerceIn(0, 100),
                    scoreAdjustment = bestStrategy.scoreAdjustment.coerceIn(-settings.adaptiveStrategyMaxScorePenalty, settings.adaptiveStrategyMaxScoreBoost),
                    positionMultiplier = mult.coerceIn(BigDecimal("0.25"), BigDecimal("1.50")),
                    explanation = "Adaptive strategy fallback: global learned strategy $mode is strongest; samples=${bestStrategy.sampleSize}, win=${bestStrategy.winRatePercent}%, pf=${bestStrategy.profitFactor}, size×$mult."
                )
            }
        }

        val sample = symbolProfile?.sampleSize ?: 0
        return AdaptiveStrategyResult(
            selectedStrategy = fallback,
            source = "WARMUP",
            confidencePercent = if (settings.adaptiveStrategyMinSamples > 0) (sample * 100 / settings.adaptiveStrategyMinSamples).coerceIn(0, 99) else 0,
            scoreAdjustment = 0,
            positionMultiplier = BigDecimal.ONE,
            explanation = "Adaptive strategy warm-up for $normalized: $sample/${settings.adaptiveStrategyMinSamples} samples. Using $fallback until enough outcomes exist."
        )
    }

    suspend fun adaptAutomationDecision(
        dao: AppDao,
        automation: com.ksp.cryptobot.core.AutomationDecision,
        ticker: MarketTicker,
        settings: BotSettings,
        strategyResult: AdaptiveStrategyResult
    ): AdaptiveAutomationResult {
        if (!settings.trueSelfLearningEnabled || !settings.adaptiveStrategyLearningEnabled) {
            return AdaptiveAutomationResult(automation, strategyResult, "Adaptive automation disabled.")
        }
        val strategyProfile = dao.learnedStrategyProfilesSnapshot()
            .firstOrNull { it.strategyKey == automation.selectedStrategy.name }
        val strategyAdj = strategyProfile
            ?.takeIf { it.sampleSize >= settings.adaptiveStrategyMinSamples }
            ?.scoreAdjustment
            ?: 0
        val combinedAdj = (strategyResult.scoreAdjustment + strategyAdj)
            .coerceIn(-settings.adaptiveStrategyMaxScorePenalty, settings.adaptiveStrategyMaxScoreBoost)
        val newScore = (automation.finalScore + combinedAdj).coerceIn(0, 100)
        val learnedMultiplier = strategyResult.positionMultiplier.multiply(strategyProfile?.positionMultiplier?.toBigDecimalOrNull() ?: BigDecimal.ONE)
            .coerceIn(BigDecimal("0.25"), BigDecimal("1.60"))
        val adjustedSize = automation.positionSizeEur.multiply(learnedMultiplier).setScale(2, RoundingMode.DOWN)
        val action = learnedAction(automation.finalAction, newScore, settings)
        val allowed = automation.allowed && (action == SignalAction.BUY || action == SignalAction.SMALL_BUY || action == SignalAction.SELL)
        val adjusted = automation.copy(
            finalAction = action,
            finalScore = newScore,
            positionSizeEur = adjustedSize,
            allowed = allowed,
            explanation = automation.explanation + " Adaptive multi-strategy learning: selected=${automation.selectedStrategy}, source=${strategyResult.source}, scoreAdj=$combinedAdj, size×$learnedMultiplier. ${strategyResult.explanation}"
        )
        return AdaptiveAutomationResult(
            decision = adjusted,
            strategyResult = strategyResult,
            explanation = "Adaptive multi-strategy result for ${ticker.symbol}: strategy=${adjusted.selectedStrategy}, score ${automation.finalScore}→${adjusted.finalScore}, action ${automation.finalAction}→${adjusted.finalAction}, size ${automation.positionSizeEur}→${adjusted.positionSizeEur}."
        )
    }

    suspend fun recordDecisionSnapshot(
        dao: AppDao,
        settings: BotSettings,
        ticker: MarketTicker,
        decision: AiDecision,
        traded: Boolean,
        strategyMode: StrategyMode = settings.strategyMode,
        orderSide: String = "",
        orderType: String = "",
        notionalQuote: BigDecimal = BigDecimal.ZERO
    ) {
        if (!settings.trueSelfLearningEnabled) return
        val spreadPercent = if (ticker.lastPrice > BigDecimal.ZERO) {
            ticker.ask.subtract(ticker.bid).divide(ticker.lastPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
        } else BigDecimal.ZERO
        dao.insertLearningFeatureSnapshot(
            LearningFeatureSnapshotEntity(
                timestampEpochMs = System.currentTimeMillis(),
                symbol = ticker.symbol.uppercase(),
                strategyMode = strategyMode.name,
                mode = settings.mode.name,
                action = decision.finalAction.name,
                finalScore = decision.finalScore,
                technicalScore = decision.technicalScore,
                newsScore = decision.newsScore,
                memoryScore = decision.memoryScore,
                spreadPercent = spreadPercent.setScale(4, RoundingMode.HALF_UP).toPlainString(),
                volume24h = ticker.volume24h.toPlainString(),
                priceChange24hPercent = ticker.priceChangePercent24h.toPlainString(),
                allowedToTrade = decision.allowedToTrade,
                traded = traded,
                orderSide = orderSide,
                orderType = orderType,
                notionalQuote = notionalQuote.toPlainString(),
                reason = decision.explanation.take(600)
            )
        )
    }

    suspend fun dashboard(dao: AppDao, settings: BotSettings): LearningSummary {
        val profiles = dao.learnedSymbolProfilesSnapshot()
        val strategies = dao.learnedStrategyProfilesSnapshot()
        val audit = dao.selfLearningAudit(30)
        val line = if (profiles.isEmpty()) {
            "No learned profiles yet. Run PAPER or LIVE trades until at least ${settings.selfLearningMinSamples} samples exist per symbol."
        } else {
            "Profiles=${profiles.size}. Best=${profiles.maxByOrNull { it.scoreAdjustment }?.symbol ?: "n/a"}, weakest=${profiles.minByOrNull { it.scoreAdjustment }?.symbol ?: "n/a"}."
        }
        return LearningSummary(settings.trueSelfLearningEnabled, profiles, strategies, audit, line)
    }

    private fun buildSymbolProfile(symbol: String, rows: List<TradeEntity>, settings: BotSettings, now: Long): LearnedSymbolProfileEntity {
        val pnls = rows.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }
        val wins = pnls.count { it > BigDecimal.ZERO }
        val losses = pnls.count { it < BigDecimal.ZERO }
        val net = pnls.fold(BigDecimal.ZERO, BigDecimal::add)
        val avg = if (pnls.isNotEmpty()) net.divide(BigDecimal(pnls.size), 6, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val winRate = if (rows.isNotEmpty()) BigDecimal(wins * 100).divide(BigDecimal(rows.size), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val grossWin = pnls.filter { it > BigDecimal.ZERO }.fold(BigDecimal.ZERO, BigDecimal::add)
        val grossLoss = pnls.filter { it < BigDecimal.ZERO }.fold(BigDecimal.ZERO) { acc, v -> acc.add(v.abs()) }
        val profitFactor = if (grossLoss > BigDecimal.ZERO) grossWin.divide(grossLoss, 2, RoundingMode.HALF_UP) else if (grossWin > BigDecimal.ZERO) BigDecimal("9.99") else BigDecimal.ONE
        val enough = rows.size >= settings.selfLearningMinSamples
        val scoreAdj = if (!enough) 0 else when {
            winRate >= BigDecimal("62") && profitFactor >= BigDecimal("1.60") -> settings.selfLearningMaxScoreBoost
            winRate >= BigDecimal("55") && profitFactor >= BigDecimal("1.25") -> min(settings.selfLearningMaxScoreBoost, 6)
            winRate < BigDecimal("38") || profitFactor < BigDecimal("0.75") -> -settings.selfLearningMaxScorePenalty
            winRate < BigDecimal("45") || profitFactor < BigDecimal("0.95") -> -min(settings.selfLearningMaxScorePenalty, 8)
            else -> 0
        }
        val positionMultiplier = if (!settings.selfLearningPositionSizingEnabled || !enough) BigDecimal.ONE else when {
            scoreAdj >= 8 -> BigDecimal("1.20")
            scoreAdj >= 4 -> BigDecimal("1.10")
            scoreAdj <= -10 -> BigDecimal("0.35")
            scoreAdj <= -5 -> BigDecimal("0.60")
            else -> BigDecimal.ONE
        }
        val disabledUntil = if (settings.selfLearningAutoDisableEnabled && enough && scoreAdj <= -settings.selfLearningMaxScorePenalty) {
            now + settings.badSymbolDisableHours.toLong() * 60L * 60L * 1000L
        } else 0L
        val preferred = when {
            !enough -> StrategyMode.AUTO
            winRate >= BigDecimal("58") && profitFactor >= BigDecimal("1.30") -> StrategyMode.TREND
            profitFactor < BigDecimal("0.95") -> StrategyMode.SCALPING
            symbol.startsWith("BTC") || symbol.startsWith("ETH") -> StrategyMode.AUTO
            else -> StrategyMode.BREAKOUT
        }
        val confidence = if (!enough) BigDecimal(rows.size * 100).divide(BigDecimal(settings.selfLearningMinSamples.coerceAtLeast(1)), 2, RoundingMode.HALF_UP).min(BigDecimal("99.00")) else BigDecimal("100.00")
        val explanation = if (!enough) {
            "Learning warm-up: $symbol has ${rows.size}/${settings.selfLearningMinSamples} samples. No strong adjustment applied."
        } else {
            "Learned $symbol: winRate=$winRate%, pf=$profitFactor, net=${net.setScale(4, RoundingMode.HALF_UP)}, scoreAdj=$scoreAdj, sizeMultiplier=$positionMultiplier."
        }
        return LearnedSymbolProfileEntity(
            symbol = symbol,
            updatedAtEpochMs = now,
            sampleSize = rows.size,
            wins = wins,
            losses = losses,
            winRatePercent = winRate.toPlainString(),
            profitFactor = profitFactor.toPlainString(),
            averagePnlEur = avg.toPlainString(),
            netPnlEur = net.toPlainString(),
            scoreAdjustment = scoreAdj,
            minScoreAdjustment = if (scoreAdj < 0) -scoreAdj else 0,
            positionMultiplier = positionMultiplier.toPlainString(),
            cooldownMultiplier = if (scoreAdj < 0) "1.50" else "1.00",
            preferredStrategy = preferred.name,
            disabledUntilEpochMs = disabledUntil,
            confidence = confidence.toPlainString(),
            explanation = explanation
        )
    }

    private fun buildStrategyProfile(strategy: String, rows: List<TradeEntity>, settings: BotSettings, now: Long): LearnedStrategyProfileEntity {
        val pnls = rows.mapNotNull { it.realizedPnlEur.toBigDecimalOrNull() }
        val wins = pnls.count { it > BigDecimal.ZERO }
        val losses = pnls.count { it < BigDecimal.ZERO }
        val grossWin = pnls.filter { it > BigDecimal.ZERO }.fold(BigDecimal.ZERO, BigDecimal::add)
        val grossLoss = pnls.filter { it < BigDecimal.ZERO }.fold(BigDecimal.ZERO) { acc, v -> acc.add(v.abs()) }
        val winRate = if (rows.isNotEmpty()) BigDecimal(wins * 100).divide(BigDecimal(rows.size), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO
        val pf = if (grossLoss > BigDecimal.ZERO) grossWin.divide(grossLoss, 2, RoundingMode.HALF_UP) else if (grossWin > BigDecimal.ZERO) BigDecimal("9.99") else BigDecimal.ONE
        val enough = rows.size >= settings.selfLearningMinSamples
        val adj = if (!enough) 0 else when {
            winRate >= BigDecimal("58") && pf >= BigDecimal("1.30") -> 5
            winRate < BigDecimal("42") || pf < BigDecimal("0.90") -> -6
            else -> 0
        }
        val mult = if (adj > 0) "1.10" else if (adj < 0) "0.70" else "1.00"
        return LearnedStrategyProfileEntity(strategy, now, rows.size, wins, losses, winRate.toPlainString(), pf.toPlainString(), adj, mult, "Strategy $strategy learned from ${rows.size} trades: win=$winRate%, pf=$pf, scoreAdj=$adj.")
    }

    private fun strategyKeyFromTrade(trade: TradeEntity): String {
        val reason = trade.aiReason.uppercase()
        return when {
            "SCALPING" in reason -> "SCALPING"
            "TREND" in reason -> "TREND"
            "BREAKOUT" in reason -> "BREAKOUT"
            "REVERSAL" in reason -> "REVERSAL"
            else -> "AUTO"
        }
    }

    private fun learnedAction(current: SignalAction, score: Int, settings: BotSettings): SignalAction {
        return when {
            current == SignalAction.SELL -> SignalAction.SELL
            score >= settings.minStrategyScoreToBuy -> SignalAction.BUY
            score >= settings.minStrategyScoreToBuy - 8 -> SignalAction.SMALL_BUY
            score >= 55 -> SignalAction.WATCH
            score >= 45 -> SignalAction.WAIT
            else -> SignalAction.AVOID
        }
    }

    private fun spreadPenalty(ticker: MarketTicker, settings: BotSettings): Int {
        if (ticker.lastPrice <= BigDecimal.ZERO) return 0
        val spread = ticker.ask.subtract(ticker.bid).divide(ticker.lastPrice, 8, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
        return when {
            spread > settings.autoSymbolMaxSpreadPercent.multiply(BigDecimal("2")) -> 8
            spread > settings.autoSymbolMaxSpreadPercent -> 4
            else -> 0
        }
    }

    private fun BigDecimal.coerceIn(minimum: BigDecimal, maximum: BigDecimal): BigDecimal = max(minimum).min(maximum)
}
