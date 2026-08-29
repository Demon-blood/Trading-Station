package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.data.ResearchDao
import com.ksp.cryptobot.data.ResearchEventEntity
import com.ksp.cryptobot.data.ResearchStateEntity
import com.ksp.cryptobot.data.ResearchStrategyProfileEntity
import com.ksp.cryptobot.data.TradeEntity
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sqrt

enum class StrategyGovernanceAction {
    HOLD_CHALLENGER,
    KEEP_CHAMPION,
    PROMOTE_INITIAL_CHAMPION,
    PROMOTE_CHALLENGER
}

data class StrategyOutcome(
    val timestampEpochMs: Long,
    val conservativeNetPnlQuote: BigDecimal,
    val normalizedNetReturn: BigDecimal,
    val paper: Boolean
)

data class StrategyOosStats(
    val exactSamples: Int,
    val paperSamples: Int,
    val liveSamples: Int,
    val trainSamples: Int,
    val testSamples: Int,
    val evidenceSpanMs: Long,
    val oosNetPnlQuote: BigDecimal,
    val oosMeanReturn: BigDecimal,
    val oosProfitFactor: BigDecimal,
    val oosMaxDrawdownRate: BigDecimal,
    val lower95Return: BigDecimal,
    val upper95Return: BigDecimal
)

data class StrategyDifferenceStats(
    val meanDifference: BigDecimal,
    val lower95Difference: BigDecimal,
    val upper95Difference: BigDecimal
)

data class StrategyGovernanceDecision(
    val action: StrategyGovernanceAction,
    val challengerStrategy: String,
    val championBefore: String?,
    val championAfter: String?,
    val productionAuthorized: Boolean,
    val challenger: StrategyOosStats,
    val champion: StrategyOosStats?,
    val difference: StrategyDifferenceStats?,
    val regimeCount: Int,
    val walkForwardScore: Double,
    val monteCarloScore: Double,
    val reason: String
)

/**
 * M9 champion/challenger production governance.
 *
 * PAPER remains the proving ground. LIVE research promotion is authorized only for
 * the current champion or a challenger that passes every promotion gate.
 *
 * This engine never creates a BUY, never raises position size, and never weakens
 * M4 execution-state, M5 net-EV, M6 AI, or portfolio/risk controls.
 */
class StrategyChampionChallengerEngine(private val dao: ResearchDao) {
    companion object {
        const val MIN_EXACT_OUTCOMES = 30
        const val MIN_PAPER_OUTCOMES = 20
        const val MIN_OOS_OUTCOMES = 10
        const val MIN_REGIMES = 2
        const val MIN_EVIDENCE_SPAN_MS = 7L * 24L * 60L * 60L * 1000L
        const val PROMOTION_COOLDOWN_MS = 7L * 24L * 60L * 60L * 1000L

        private val MIN_OOS_NET_PNL = BigDecimal("0.25")
        private val MIN_OOS_MEAN_RETURN = BigDecimal("0.0005")
        private val MIN_PROFIT_FACTOR = BigDecimal("1.20")
        private val MIN_SUPERIORITY_EFFECT = BigDecimal("0.0005")
        private val RETURN_CLIP = BigDecimal("0.25")

        fun exactOutcomes(strategy: String, symbol: String, trades: List<TradeEntity>): List<StrategyOutcome> {
            val strategyUpper = strategy.trim().uppercase()
            if (strategyUpper.isBlank() || strategyUpper == "NONE") return emptyList()

            return trades.asSequence()
                .filter { it.symbol.equals(symbol, true) }
                .filter { it.side.equals("SELL", true) }
                .filter { strategyTagged(it.aiReason, strategyUpper) }
                .mapNotNull { trade ->
                    val realized = trade.realizedPnlEur.toBigDecimalOrNull() ?: return@mapNotNull null
                    if (realized.compareTo(BigDecimal.ZERO) == 0) return@mapNotNull null
                    val fee = trade.feeEur.toBigDecimalOrNull()?.abs() ?: BigDecimal.ZERO
                    val qty = trade.quantity.toBigDecimalOrNull()?.abs() ?: BigDecimal.ZERO
                    val price = trade.priceEur.toBigDecimalOrNull()?.abs() ?: BigDecimal.ZERO
                    val notional = qty.multiply(price)
                    if (notional <= BigDecimal.ZERO) return@mapNotNull null

                    // Closing realized P/L already reserves the observed exit fee when locally
                    // calculated. One additional observed-fee amount is deducted conservatively
                    // as a proxy for entry-side fee not represented on the closing TradeEntity.
                    val conservativeNet = realized.subtract(fee)
                    val rate = conservativeNet.divide(notional, 16, RoundingMode.HALF_UP)
                        .coerceIn(RETURN_CLIP.negate(), RETURN_CLIP)
                    StrategyOutcome(trade.timestampEpochMs, conservativeNet, rate, trade.paper)
                }
                .sortedBy { it.timestampEpochMs }
                .toList()
                .takeLast(300)
        }

        fun statistics(outcomes: List<StrategyOutcome>): StrategyOosStats {
            if (outcomes.isEmpty()) return emptyStats()
            val rows = outcomes.sortedBy { it.timestampEpochMs }
            val split = if (rows.size > MIN_OOS_OUTCOMES) {
                ((rows.size * 2) / 3).coerceIn(1, rows.size - MIN_OOS_OUTCOMES)
            } else 0
            val test = if (split > 0) rows.drop(split) else emptyList()
            val returns = test.map { it.normalizedNetReturn.toDouble() }
            val n = returns.size
            val mean = if (n > 0) returns.average() else 0.0
            val variance = if (n > 1) {
                returns.sumOf { (it - mean) * (it - mean) } / (n - 1).toDouble()
            } else 0.0
            val sd = sqrt(variance.coerceAtLeast(0.0))
            val se = if (n > 0) sd / sqrt(n.toDouble()) else 0.0
            val margin = critical95(n) * se

            val wins = test.filter { it.conservativeNetPnlQuote > BigDecimal.ZERO }
                .fold(BigDecimal.ZERO) { a, b -> a.add(b.conservativeNetPnlQuote) }
            val losses = test.filter { it.conservativeNetPnlQuote < BigDecimal.ZERO }
                .fold(BigDecimal.ZERO) { a, b -> a.add(b.conservativeNetPnlQuote.abs()) }
            val pf = when {
                losses > BigDecimal.ZERO -> wins.divide(losses, 8, RoundingMode.HALF_UP)
                wins > BigDecimal.ZERO -> BigDecimal("99")
                else -> BigDecimal.ZERO
            }

            var equity = BigDecimal.ZERO
            var peak = BigDecimal.ZERO
            var maxDd = BigDecimal.ZERO
            test.forEach {
                equity = equity.add(it.normalizedNetReturn)
                if (equity > peak) peak = equity
                val dd = peak.subtract(equity)
                if (dd > maxDd) maxDd = dd
            }

            val span = if (rows.size > 1) {
                (rows.last().timestampEpochMs - rows.first().timestampEpochMs).coerceAtLeast(0L)
            } else 0L

            return StrategyOosStats(
                exactSamples = rows.size,
                paperSamples = rows.count { it.paper },
                liveSamples = rows.count { !it.paper },
                trainSamples = split,
                testSamples = n,
                evidenceSpanMs = span,
                oosNetPnlQuote = test.fold(BigDecimal.ZERO) { a, b -> a.add(b.conservativeNetPnlQuote) },
                oosMeanReturn = BigDecimal.valueOf(mean),
                oosProfitFactor = pf,
                oosMaxDrawdownRate = maxDd,
                lower95Return = BigDecimal.valueOf(mean - margin),
                upper95Return = BigDecimal.valueOf(mean + margin)
            )
        }

        fun difference(challengerOutcomes: List<StrategyOutcome>, championOutcomes: List<StrategyOutcome>): StrategyDifferenceStats? {
            val c = oosReturns(challengerOutcomes)
            val p = oosReturns(championOutcomes)
            if (c.size < MIN_OOS_OUTCOMES || p.size < MIN_OOS_OUTCOMES) return null
            val cm = c.average()
            val pm = p.average()
            val se = sqrt(sampleVariance(c, cm) / c.size + sampleVariance(p, pm) / p.size)
            val diff = cm - pm
            val margin = 2.10 * se
            return StrategyDifferenceStats(
                BigDecimal.valueOf(diff),
                BigDecimal.valueOf(diff - margin),
                BigDecimal.valueOf(diff + margin)
            )
        }

        fun decide(
            championStrategy: String?,
            challengerStrategy: String,
            challengerOutcomes: List<StrategyOutcome>,
            championOutcomes: List<StrategyOutcome>,
            regimeCount: Int,
            walkForward: WalkForwardAssessment,
            monteCarlo: MonteCarloAssessment,
            championProfile: ResearchStrategyProfileEntity?,
            maxDrawdownPercent: BigDecimal,
            nowEpochMs: Long,
            lastPromotionAtEpochMs: Long
        ): StrategyGovernanceDecision {
            val c = statistics(challengerOutcomes)
            val p = championStrategy?.let { statistics(championOutcomes) }
            val diff = if (championStrategy != null && !championStrategy.equals(challengerStrategy, true)) {
                difference(challengerOutcomes, championOutcomes)
            } else null

            if (championStrategy != null && championStrategy.equals(challengerStrategy, true)) {
                return StrategyGovernanceDecision(
                    StrategyGovernanceAction.KEEP_CHAMPION, challengerStrategy,
                    championStrategy, championStrategy, true, c, p, null, regimeCount,
                    walkForward.score, monteCarlo.score,
                    "Current champion retained. M9 never auto-demotes from one evaluation; replacements must prove superiority."
                )
            }

            val hardDd = maxDrawdownPercent.divide(BigDecimal("100"), 8, RoundingMode.HALF_UP)
                .coerceIn(BigDecimal("0.005"), BigDecimal("0.50"))

            val standaloneQualified =
                c.exactSamples >= MIN_EXACT_OUTCOMES &&
                c.paperSamples >= MIN_PAPER_OUTCOMES &&
                c.testSamples >= MIN_OOS_OUTCOMES &&
                c.evidenceSpanMs >= MIN_EVIDENCE_SPAN_MS &&
                regimeCount >= MIN_REGIMES &&
                c.oosNetPnlQuote >= MIN_OOS_NET_PNL &&
                c.oosMeanReturn >= MIN_OOS_MEAN_RETURN &&
                c.lower95Return > BigDecimal.ZERO &&
                c.oosProfitFactor >= MIN_PROFIT_FACTOR &&
                c.oosMaxDrawdownRate <= hardDd &&
                walkForward.ready && walkForward.status == "PASS" && walkForward.score >= 60.0 &&
                walkForward.windows > 0 && walkForward.profitableWindows * 2 >= walkForward.windows &&
                monteCarlo.ready && monteCarlo.score >= 60.0 && monteCarlo.probabilityPositive >= 0.65

            if (!standaloneQualified) {
                return hold(championStrategy, challengerStrategy, c, p, diff, regimeCount, walkForward, monteCarlo,
                    qualificationReason(c, regimeCount, walkForward, monteCarlo, hardDd))
            }

            if (lastPromotionAtEpochMs > 0L && nowEpochMs - lastPromotionAtEpochMs < PROMOTION_COOLDOWN_MS) {
                return hold(championStrategy, challengerStrategy, c, p, diff, regimeCount, walkForward, monteCarlo,
                    "Challenger qualifies independently, but the seven-day champion promotion cooldown is active.")
            }

            if (championStrategy.isNullOrBlank()) {
                return StrategyGovernanceDecision(
                    StrategyGovernanceAction.PROMOTE_INITIAL_CHAMPION, challengerStrategy,
                    null, challengerStrategy, true, c, null, null, regimeCount,
                    walkForward.score, monteCarlo.score,
                    "Initial champion qualified from exact strategy-tagged paper evidence, untouched chronological OOS holdout, multi-regime coverage, walk-forward and Monte Carlo."
                )
            }

            if (p == null || p.exactSamples < MIN_EXACT_OUTCOMES || p.testSamples < MIN_OOS_OUTCOMES) {
                return hold(championStrategy, challengerStrategy, c, p, diff, regimeCount, walkForward, monteCarlo,
                    "Existing champion lacks enough retained exact OOS data for a statistically fair replacement; no promotion.")
            }

            val championDdAllowance = p.oosMaxDrawdownRate.multiply(BigDecimal("1.10"))
                .max(BigDecimal("0.01")).min(hardDd)
            val noValidationRegression =
                walkForward.score + 5.0 >= (championProfile?.walkForwardScore ?: 0.0) &&
                monteCarlo.score + 5.0 >= (championProfile?.monteCarloScore ?: 0.0)

            val superior =
                diff != null &&
                diff.meanDifference >= MIN_SUPERIORITY_EFFECT &&
                diff.lower95Difference > BigDecimal.ZERO &&
                c.oosProfitFactor >= p.oosProfitFactor &&
                c.oosMaxDrawdownRate <= championDdAllowance &&
                noValidationRegression

            return if (superior) {
                StrategyGovernanceDecision(
                    StrategyGovernanceAction.PROMOTE_CHALLENGER, challengerStrategy,
                    championStrategy, challengerStrategy, true, c, p, diff, regimeCount,
                    walkForward.score, monteCarlo.score,
                    "Challenger promoted: positive lower-95% return-difference bound, >=5 bps effect, and no PF/drawdown/validation regression."
                )
            } else {
                hold(championStrategy, challengerStrategy, c, p, diff, regimeCount, walkForward, monteCarlo,
                    "Challenger is viable but has not proven statistically credible superiority over the current champion.")
            }
        }

        private fun hold(
            champion: String?, challenger: String, c: StrategyOosStats, p: StrategyOosStats?,
            d: StrategyDifferenceStats?, regimes: Int, wf: WalkForwardAssessment, mc: MonteCarloAssessment,
            reason: String
        ) = StrategyGovernanceDecision(
            StrategyGovernanceAction.HOLD_CHALLENGER, challenger, champion, champion, false,
            c, p, d, regimes, wf.score, mc.score, reason
        )

        private fun strategyTagged(reason: String, strategyUpper: String): Boolean {
            val upper = reason.uppercase()
            return upper.contains("[$strategyUpper]") ||
                upper.contains("STRATEGY=$strategyUpper") ||
                upper.contains("RESEARCH STRATEGY=$strategyUpper")
        }

        private fun oosReturns(outcomes: List<StrategyOutcome>): List<Double> {
            if (outcomes.size <= MIN_OOS_OUTCOMES) return emptyList()
            val rows = outcomes.sortedBy { it.timestampEpochMs }
            val split = ((rows.size * 2) / 3).coerceIn(1, rows.size - MIN_OOS_OUTCOMES)
            return rows.drop(split).map { it.normalizedNetReturn.toDouble() }
        }

        private fun sampleVariance(rows: List<Double>, mean: Double): Double =
            if (rows.size > 1) rows.sumOf { (it - mean) * (it - mean) } / (rows.size - 1) else 0.0

        private fun critical95(n: Int): Double = when {
            n <= 10 -> 2.262
            n <= 15 -> 2.145
            n <= 20 -> 2.093
            n <= 30 -> 2.045
            n <= 60 -> 2.000
            else -> 1.980
        }

        private fun qualificationReason(
            s: StrategyOosStats, regimes: Int, wf: WalkForwardAssessment,
            mc: MonteCarloAssessment, maxDd: BigDecimal
        ): String =
            "M9 HOLD exact=${s.exactSamples}/$MIN_EXACT_OUTCOMES paper=${s.paperSamples}/$MIN_PAPER_OUTCOMES OOS=${s.testSamples}/$MIN_OOS_OUTCOMES span=${days(s.evidenceSpanMs)}/7d regimes=$regimes/$MIN_REGIMES net=${s.oosNetPnlQuote.s4()} mean=${s.oosMeanReturn.pct()} lower95=${s.lower95Return.pct()} PF=${s.oosProfitFactor.s3()} DD=${s.oosMaxDrawdownRate.pct()}/${maxDd.pct()} WF=${wf.status}/${"%.1f".format(wf.score)} MC=${"%.1f".format(mc.score)}/${"%.1f".format(mc.probabilityPositive * 100)}%."

        private fun emptyStats() = StrategyOosStats(
            0,0,0,0,0,0L, BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,
            BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO
        )

        private fun BigDecimal.coerceIn(lo: BigDecimal, hi: BigDecimal) = when {
            this < lo -> lo
            this > hi -> hi
            else -> this
        }
        private fun BigDecimal.s4() = setScale(4, RoundingMode.HALF_UP).toPlainString()
        private fun BigDecimal.s3() = setScale(3, RoundingMode.HALF_UP).toPlainString()
        private fun BigDecimal.pct() = multiply(BigDecimal("100")).setScale(3, RoundingMode.HALF_UP).toPlainString()+"%"
        private fun days(ms: Long) = BigDecimal.valueOf(ms).divide(
            BigDecimal.valueOf(24L*60L*60L*1000L),1,RoundingMode.DOWN
        ).toPlainString()
    }

    suspend fun evaluateAndMaybePromote(
        settings: BotSettings,
        challengerStrategy: String,
        symbol: String,
        trades: List<TradeEntity>,
        walkForward: WalkForwardAssessment,
        monteCarlo: MonteCarloAssessment,
        nowEpochMs: Long = System.currentTimeMillis()
    ): StrategyGovernanceDecision {
        val s = normalize(symbol)
        val championKey = "m9_champion:$s"
        val promotedAtKey = "m9_champion_promoted_at:$s"
        val champion = dao.state(championKey)?.value?.takeIf { it.isNotBlank() }
        val lastPromotion = dao.state(promotedAtKey)?.value?.toLongOrNull() ?: 0L

        val challengerOutcomes = exactOutcomes(challengerStrategy, s, trades)
        val championOutcomes = champion?.let { exactOutcomes(it, s, trades) }.orEmpty()

        val regimes = dao.recentEventsForSymbol(s, 1500).asSequence()
            .filter { it.strategy.equals(challengerStrategy, true) }
            .filter { it.eventType in setOf("handoff_catalog_evaluation", "research_evaluation") }
            .map { it.regime.trim() }
            .filter { it.isNotBlank() && !it.equals("UNKNOWN", true) }
            .toSet()

        val championProfile = champion?.let { dao.profile("$it|$s") }
        val decision = decide(
            champion, challengerStrategy, challengerOutcomes, championOutcomes,
            regimes.size, walkForward, monteCarlo, championProfile,
            settings.maxDrawdownPercent, nowEpochMs, lastPromotion
        )

        if (decision.action == StrategyGovernanceAction.PROMOTE_INITIAL_CHAMPION ||
            decision.action == StrategyGovernanceAction.PROMOTE_CHALLENGER
        ) {
            dao.putState(ResearchStateEntity(championKey, challengerStrategy, nowEpochMs))
            dao.putState(ResearchStateEntity(promotedAtKey, nowEpochMs.toString(), nowEpochMs))
            dao.putState(ResearchStateEntity("m9_champion_reason:$s", decision.reason.take(2000), nowEpochMs))
            dao.insertEvent(ResearchEventEntity(
                timestampEpochMs = nowEpochMs,
                eventType = "m9_strategy_promotion",
                symbol = s,
                strategy = challengerStrategy,
                regime = regimes.sorted().joinToString(","),
                mode = settings.mode.name,
                variant = decision.action.name,
                confidence = decision.difference?.lower95Difference?.toDouble()
                    ?: decision.challenger.lower95Return.toDouble(),
                score = walkForward.score,
                sampleCount = decision.challenger.exactSamples,
                status = "PROMOTED",
                reason = decision.reason
            ))
        }
        return decision
    }

    suspend fun currentChampion(symbol: String): String? =
        dao.state("m9_champion:${normalize(symbol)}")?.value?.takeIf { it.isNotBlank() }

    suspend fun recentPromotions(limit: Int = 100): List<ResearchEventEntity> =
        dao.recentEventsByType("m9_strategy_promotion", limit.coerceIn(1, 500))

    private fun normalize(symbol: String) =
        symbol.uppercase().replace("/","").replace("-","").replace("_","")
}
