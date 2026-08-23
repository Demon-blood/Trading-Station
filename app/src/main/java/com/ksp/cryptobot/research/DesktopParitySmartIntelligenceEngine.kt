package com.ksp.cryptobot.research

import android.content.Context
import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.*
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.*

/**
 * Behavioral port of the advisory layers in desktop v1.0.50 advanced_ai.py and governance_ai.py.
 *
 * This class never submits an order. It only returns bounded evidence. M3 governance and M4 execution
 * remain authoritative, and its capital multiplier is capped at 1.0 when applied on Android so this
 * research layer can never increase a post-reserve/post-risk order ceiling.
 */
data class DesktopSmartAssessment(
    val adjustment: Int,
    val appliedSizeMultiplier: Double,
    val desktopCapitalMultiplier: Double,
    val blocked: Boolean,
    val mlProbability: Double,
    val modelVersion: String,
    val fakeBreakoutRisk: Double,
    val report: String
)

class DesktopParitySmartIntelligenceEngine(context: Context, private val researchDao: ResearchDao) {
    private val db = AppDatabase.get(context.applicationContext)
    private val appDao = db.dao()
    private val governanceDao = db.governanceDao()
    private val featureKeys = listOf(
        "score", "spread_pct", "volume_log", "news_score", "is_bull", "is_bear", "is_high_vol",
        "is_low_vol", "is_scalping", "is_trend", "is_breakout", "hour_sin", "hour_cos"
    )

    suspend fun evaluate(
        settings: BotSettings,
        decision: AiDecision,
        ticker: MarketTicker,
        candles: List<Candle>,
        orderBook: OrderBookSnapshot?,
        regime: AdvancedRegimeProfile,
        strategy: String,
        recentTrades: List<TradeEntity>,
        crossMarket: ContextAssessment
    ): DesktopSmartAssessment {
        trainOnlineModelFromNewOutcomes(recentTrades)
        val model = loadModel()
        val features = features(decision, ticker, regime, strategy)
        val probability = predict(model, features, decision.finalScore)
        val mlScore = ((probability - 0.50) * 24.0).toInt()
        val mlReason = if (model.samples < 25) "ML warm-up samples=${model.samples}; blended with signal score." else "ML probability from ${model.samples} trained samples."

        val ob = orderBookEvidence(orderBook, decision.finalAction)
        val vol = volatilityEvidence(candles)
        val liq = liquidityEvidence(ticker, ob)
        val dq = dataQualityEvidence(ticker, candles)
        val positions = runCatching { appDao.openPositionsSnapshot() }.getOrDefault(emptyList())
        val portfolio = portfolioEvidence(settings, ticker.symbol, positions)
        val mutation = mutationEvidence(strategy)

        val modelGov = modelGovernance(model, recentTrades)
        val anomaly = anomalyEvidence(ticker, candles, ob.fakeBreakoutRisk)
        val execution = executionQualityEvidence(ticker.symbol, settings.mode)
        val feedback = humanFeedbackEvidence(ticker.symbol)
        val research = autonomousResearchEvidence(ticker.symbol, strategy, regime.regime, recentTrades)

        val components = linkedMapOf(
            "ml" to Evidence(mlScore, mlReason),
            "order_book" to Evidence(ob.adjustment, ob.reason),
            "cross_market" to Evidence(crossMarket.adjustment, crossMarket.reason),
            "volatility" to Evidence(vol.adjustment, vol.reason),
            "liquidity" to Evidence(liq.adjustment, liq.reason),
            "data_quality" to Evidence(dq.adjustment, dq.reason),
            "portfolio" to Evidence(portfolio.adjustment, portfolio.reason),
            "mutation" to Evidence(mutation.adjustment, mutation.reason)
        )
        val ensemble = ensembleVote(components)
        val preCapital = modelGov.adjustment + anomaly.adjustment + execution.adjustment + feedback.adjustment + research.adjustment
        val desktopCapital = adaptiveCapitalMultiplier(
            baseAdjustment = ensemble.adjustment + preCapital,
            probabilityDailyLimitHit = desktopDailyLimitProbability(recentTrades, settings),
            decision = decision
        )
        // Desktop allowed up to 1.25x. Android deliberately never lets advisory research raise M4's ceiling.
        val appliedCapital = desktopCapital.coerceIn(0.15, 1.0)
        val governanceAdj = (modelGov.adjustment + anomaly.adjustment + execution.adjustment + feedback.adjustment + research.adjustment)
            .coerceIn(-35, 25)
        val finalAdj = (ensemble.adjustment + governanceAdj).coerceIn(-45, 35)
        val blocked = anomaly.blocked || dq.blocked
        val version = modelGov.version

        val report = buildString {
            append("Desktop parity smart AI: ml=${"%.3f".format(probability)} ($mlReason); ")
            append("orderBook=${ob.reason}; crossMarket=${crossMarket.reason}; volatility=${vol.reason}; liquidity=${liq.reason}; dataQuality=${dq.reason}; ")
            append("portfolio=${portfolio.reason}; mutation=${mutation.reason}; ensemble=${ensemble.reason}; ")
            append("model=$version ${modelGov.reason}; anomaly=${anomaly.reason}; execution=${execution.reason}; ")
            append("feedback=${feedback.reason}; research=${research.reason}; desktopCapital=${"%.2f".format(desktopCapital)}x; ")
            append("appliedCapital=${"%.2f".format(appliedCapital)}x; finalAdj=${if(finalAdj>=0) "+" else ""}$finalAdj; blocked=$blocked.")
        }
        runCatching {
            researchDao.insertEvent(
                ResearchEventEntity(
                    eventType = "desktop_smart_parity",
                    symbol = ticker.symbol,
                    strategy = strategy,
                    regime = regime.regime,
                    mode = settings.mode.name,
                    adjustment = finalAdj,
                    confidence = probability,
                    score = decision.finalScore.toDouble(),
                    sampleCount = model.samples,
                    status = if (blocked) "BLOCK" else "OK",
                    reason = report,
                    payloadJson = JSONObject().apply {
                        put("desktop_capital_multiplier", desktopCapital)
                        put("applied_capital_multiplier", appliedCapital)
                        put("fake_breakout_risk", ob.fakeBreakoutRisk)
                        put("model_version", version)
                    }.toString()
                )
            )
        }
        return DesktopSmartAssessment(finalAdj, appliedCapital, desktopCapital, blocked, probability, version, ob.fakeBreakoutRisk, report)
    }

    private data class OnlineModel(val weights: MutableMap<String, Double>, var bias: Double, var samples: Int)
    private data class Evidence(val adjustment: Int, val reason: String, val blocked: Boolean = false)
    private data class OrderBookEvidence(val adjustment: Int, val reason: String, val fakeBreakoutRisk: Double, val bidDepth: Double, val askDepth: Double)
    private data class ModelGovernance(val adjustment: Int, val reason: String, val version: String)
    private data class Anomaly(val adjustment: Int, val reason: String, val blocked: Boolean)

    private suspend fun loadModel(): OnlineModel {
        val row = researchDao.state(MODEL_KEY)?.value
        if (row.isNullOrBlank()) return OnlineModel(featureKeys.associateWith { 0.0 }.toMutableMap(), 0.0, 0)
        return runCatching {
            val json = JSONObject(row)
            val weightsObj = json.optJSONObject("weights") ?: JSONObject()
            val weights = featureKeys.associateWith { weightsObj.optDouble(it, 0.0) }.toMutableMap()
            OnlineModel(weights, json.optDouble("bias", 0.0), json.optInt("samples", 0))
        }.getOrElse { OnlineModel(featureKeys.associateWith { 0.0 }.toMutableMap(), 0.0, 0) }
    }

    private suspend fun saveModel(model: OnlineModel) {
        val weights = JSONObject(); model.weights.forEach { (k, v) -> weights.put(k, v) }
        val json = JSONObject().put("weights", weights).put("bias", model.bias).put("samples", model.samples)
        researchDao.putState(ResearchStateEntity(MODEL_KEY, json.toString()))
    }

    private fun features(decision: AiDecision, ticker: MarketTicker, regime: AdvancedRegimeProfile, strategy: String): Map<String, Double> {
        val hour = Instant.now().atZone(ZoneOffset.UTC).hour
        val volumeLog = log10(max(1.0, ticker.volume24h.toDouble())) / 8.0
        val spread = StrategyMath.spreadPct(ticker.lastPrice.toDouble(), ticker.bid.toDouble(), ticker.ask.toDouble())
        val r = regime.regime.uppercase()
        return linkedMapOf(
            "score" to (decision.finalScore / 100.0).coerceIn(0.0, 1.0),
            "spread_pct" to (spread / 2.0).coerceIn(0.0, 1.0),
            "volume_log" to volumeLog.coerceIn(0.0, 1.0),
            "news_score" to (decision.newsScore / 100.0).coerceIn(-1.0, 1.0),
            "is_bull" to if (r.contains("TRENDING") && regime.trend.contains("UP", true)) 1.0 else 0.0,
            "is_bear" to if (r.contains("RISK_OFF") || regime.trend.contains("DOWN", true)) 1.0 else 0.0,
            "is_high_vol" to if (regime.volatility.contains("HIGH", true)) 1.0 else 0.0,
            "is_low_vol" to if (regime.volatility.contains("LOW", true)) 1.0 else 0.0,
            "is_scalping" to if (strategy in setOf("VWAP_RECLAIM", "SUPPORT_RESISTANCE_BOUNCE", "SPREAD_LIQUIDITY_SCALP")) 1.0 else 0.0,
            "is_trend" to if (strategy in setOf("EMA_TREND_RIDER", "PULLBACK_CONTINUATION", "SUPERTREND", "PRO_POSITION_TREND_FOLLOWING")) 1.0 else 0.0,
            "is_breakout" to if (strategy.contains("BREAKOUT")) 1.0 else 0.0,
            "hour_sin" to sin(2.0 * Math.PI * hour / 24.0),
            "hour_cos" to cos(2.0 * Math.PI * hour / 24.0)
        )
    }

    private fun predict(model: OnlineModel, x: Map<String, Double>, baseScore: Int): Double {
        val z = (model.bias + x.entries.sumOf { (k, v) -> (model.weights[k] ?: 0.0) * v }).coerceIn(-12.0, 12.0)
        var p = 1.0 / (1.0 + exp(-z))
        if (model.samples < 25) p = 0.65 * (baseScore / 100.0) + 0.35 * p
        return p.coerceIn(if (model.samples < 25) 0.05 else 0.02, if (model.samples < 25) 0.95 else 0.98)
    }

    private suspend fun trainOnlineModelFromNewOutcomes(trades: List<TradeEntity>) {
        val model = loadModel()
        val lastId = researchDao.state(MODEL_CURSOR_KEY)?.value?.toLongOrNull() ?: 0L
        val snapshots = runCatching { appDao.learningFeatureSnapshots(500) }.getOrDefault(emptyList())
        val newRows = trades.filter { it.id > lastId && it.side.equals("SELL", true) && abs(it.realizedPnlEur.toDoubleOrNull() ?: 0.0) > 1e-9 }.sortedBy { it.id }
        var cursor = lastId
        for (trade in newRows) {
            val snap = snapshots.filter { it.symbol.equals(trade.symbol, true) && it.timestampEpochMs <= trade.timestampEpochMs }
                .minByOrNull { abs(trade.timestampEpochMs - it.timestampEpochMs) }
            val x = historicalFeatures(trade, snap)
            val base = snap?.finalScore ?: trade.aiScore.coerceIn(0, 100)
            val before = predict(model, x, base)
            val label = if ((trade.realizedPnlEur.toDoubleOrNull() ?: 0.0) > 0.0) 1.0 else 0.0
            val err = label - before
            for ((k, v) in x) model.weights[k] = ((model.weights[k] ?: 0.0) + 0.035 * err * v).coerceIn(-5.0, 5.0)
            model.bias = (model.bias + 0.035 * err).coerceIn(-5.0, 5.0)
            model.samples += 1
            cursor = max(cursor, trade.id)
            researchDao.insertEvent(ResearchEventEntity(eventType="desktop_ml_model_update", symbol=trade.symbol, adjustment=0, confidence=before, score=base.toDouble(), sampleCount=model.samples, reason="label=$label; prediction_before=${"%.4f".format(before)}; pnl=${trade.realizedPnlEur}"))
        }
        if (newRows.isNotEmpty()) {
            saveModel(model)
            researchDao.putState(ResearchStateEntity(MODEL_CURSOR_KEY, cursor.toString()))
        }
    }

    private fun historicalFeatures(trade: TradeEntity, snap: LearningFeatureSnapshotEntity?): Map<String, Double> {
        val hour = Instant.ofEpochMilli(trade.timestampEpochMs).atZone(ZoneOffset.UTC).hour
        val score = (snap?.finalScore ?: trade.aiScore).coerceIn(0, 100)
        val spread = snap?.spreadPercent?.toDoubleOrNull() ?: 0.0
        val volume = snap?.volume24h?.toDoubleOrNull() ?: 0.0
        val news = snap?.newsScore ?: 0
        val strategy = snap?.strategyMode.orEmpty().uppercase()
        return linkedMapOf(
            "score" to score / 100.0,
            "spread_pct" to (spread / 2.0).coerceIn(0.0, 1.0),
            "volume_log" to (log10(max(1.0, volume)) / 8.0).coerceIn(0.0, 1.0),
            "news_score" to (news / 100.0).coerceIn(-1.0, 1.0),
            "is_bull" to 0.0, "is_bear" to 0.0, "is_high_vol" to 0.0, "is_low_vol" to 0.0,
            "is_scalping" to if (strategy.contains("SCALP") || strategy.contains("VWAP")) 1.0 else 0.0,
            "is_trend" to if (strategy.contains("TREND") || strategy.contains("EMA")) 1.0 else 0.0,
            "is_breakout" to if (strategy.contains("BREAKOUT")) 1.0 else 0.0,
            "hour_sin" to sin(2.0 * Math.PI * hour / 24.0),
            "hour_cos" to cos(2.0 * Math.PI * hour / 24.0)
        )
    }

    /** Exact desktop advanced_ai.OrderBookIntelligence thresholds. */
    private fun orderBookEvidence(book: OrderBookSnapshot?, action: SignalAction): OrderBookEvidence {
        if (book == null) return OrderBookEvidence(0, "order book unavailable; no score impact", 0.0, 0.0, 0.0)
        if (book.bids.isEmpty() || book.asks.isEmpty()) return OrderBookEvidence(-4, "order book empty or malformed", 0.0, 0.0, 0.0)
        val bidDepth = book.bids.take(10).sumOf { it.price.toDouble() * it.quantity.toDouble() }
        val askDepth = book.asks.take(10).sumOf { it.price.toDouble() * it.quantity.toDouble() }
        val bestBid = book.bids.first().price.toDouble(); val bestAsk = book.asks.first().price.toDouble()
        val spread = if (bestBid > 0.0) (bestAsk - bestBid) / bestBid * 100.0 else 999.0
        val imbalance = (bidDepth - askDepth) / max(1.0, bidDepth + askDepth)
        val largestAsk = book.asks.take(15).maxOfOrNull { it.price.toDouble() * it.quantity.toDouble() } ?: 0.0
        val largestBid = book.bids.take(15).maxOfOrNull { it.price.toDouble() * it.quantity.toDouble() } ?: 0.0
        val fake = if (action in setOf(SignalAction.BUY, SignalAction.SMALL_BUY)) (largestAsk / max(1.0, bidDepth)).coerceIn(0.0, 1.0) else 0.0
        var score = 0; val reasons = mutableListOf<String>()
        if (imbalance > 0.18) { score += 5; reasons += "bid-side depth supports long signal" }
        else if (imbalance < -0.18) { score -= 5; reasons += "ask-side depth pressure" }
        if (spread > 0.35) { score -= 5; reasons += "order-book spread high ${"%.3f".format(spread)}%" }
        if (fake > 0.65) { score -= 6; reasons += "large nearby ask wall / fake-breakout risk" }
        else if (largestBid > largestAsk * 1.4) { score += 2; reasons += "nearby bid wall support" }
        return OrderBookEvidence(score.coerceIn(-12, 10), reasons.joinToString("; ").ifBlank { "neutral order book" }, fake, bidDepth, askDepth)
    }

    /** Exact desktop advanced_ai.VolatilityForecaster. */
    private fun volatilityEvidence(candles: List<Candle>): Evidence {
        if (candles.size < 40) return Evidence(0, "not enough candles for volatility forecast")
        val closes = StrategyMath.closes(candles)
        val returns = closes.zipWithNext().mapNotNull { (a, b) -> if (a > 0.0) abs((b - a) / a) * 100.0 else null }
        val short = returns.takeLast(12).average().takeIf { !it.isNaN() } ?: 0.0
        val long = (if (returns.size >= 48) returns.takeLast(48) else returns).average().takeIf { !it.isNaN() } ?: 0.0
        val acceleration = short / max(1e-9, long)
        val expected = short * acceleration.coerceIn(0.75, 2.0)
        return when {
            acceleration > 1.8 && expected > 0.6 -> Evidence(-6, "volatility expanding; expected next-window move ~${"%.2f".format(expected)}%")
            acceleration < 0.65 && short < 0.20 -> Evidence(-1, "volatility compressed; breakout risk requires confirmation")
            acceleration in 0.8..1.35 -> Evidence(2, "volatility stable enough for normal sizing")
            else -> Evidence(0, "volatility neutral expected=${"%.2f".format(expected)}%")
        }
    }

    /** Exact desktop advanced_ai.LiquidityRegimeModel thresholds. */
    private fun liquidityEvidence(ticker: MarketTicker, ob: OrderBookEvidence): Evidence {
        val spread = StrategyMath.spreadPct(ticker.lastPrice.toDouble(), ticker.bid.toDouble(), ticker.ask.toDouble())
        val depth = ob.bidDepth + ob.askDepth; val volume = ticker.volume24h.toDouble()
        var score = 0; val reasons = mutableListOf<String>()
        if (spread > 0.5) { score -= 8; reasons += "wide spread ${"%.3f".format(spread)}%" }
        else if (spread < 0.12) { score += 3; reasons += "tight spread" }
        if (volume < 100_000) { score -= 5; reasons += "low 24h quote volume" }
        else if (volume > 2_000_000) { score += 3; reasons += "strong 24h quote volume" }
        if (depth > 0.0 && depth < max(1000.0, volume * 0.0008)) { score -= 4; reasons += "thin visible depth" }
        return Evidence(score.coerceIn(-12, 8), reasons.joinToString("; ").ifBlank { "liquidity neutral" })
    }

    /** Desktop data-quality guard, using REST candles as healthy feed unless data itself indicates otherwise. */
    private fun dataQualityEvidence(ticker: MarketTicker, candles: List<Candle>): Evidence {
        val problems = mutableListOf<String>()
        if (candles.size < 30) problems += "insufficient candles"
        if (ticker.lastPrice <= java.math.BigDecimal.ZERO || ticker.bid <= java.math.BigDecimal.ZERO || ticker.ask <= java.math.BigDecimal.ZERO) problems += "invalid ticker price"
        if (candles.isNotEmpty()) {
            val lastClose = candles.last().close.toDouble(); val last = ticker.lastPrice.toDouble()
            if (lastClose > 0.0 && abs(last - lastClose) / lastClose * 100.0 > 4.0) problems += "ticker/OHLC disagreement"
            if (candles.takeLast(20).count { it.volume <= java.math.BigDecimal.ZERO } >= 5) problems += "many zero-volume candles"
        }
        return if (problems.isNotEmpty()) Evidence(-15, "data-quality guard: ${problems.joinToString()}", blocked = true) else Evidence(3, "data quality OK")
    }

    /** Desktop PortfolioOptimizer; M4 remains the actual capital allocator. */
    private fun portfolioEvidence(settings: BotSettings, symbol: String, positions: List<PositionEntity>): Evidence {
        if (positions.isEmpty()) return Evidence(2, "portfolio has room for new exposure")
        if (positions.size >= settings.maxOpenPositions) return Evidence(-10, "max open-position pressure")
        val altCount = positions.count { !it.symbol.startsWith("BTC", true) && !it.symbol.startsWith("ETH", true) }
        if (altCount >= 3 && !symbol.startsWith("BTC", true) && !symbol.startsWith("ETH", true)) return Evidence(-5, "altcoin correlation exposure already high")
        if (positions.any { it.symbol.equals(symbol, true) }) return Evidence(-8, "already exposed to this symbol")
        return Evidence(0, "portfolio exposure neutral")
    }

    private suspend fun mutationEvidence(strategy: String): Evidence {
        val events = researchDao.recentEventsByType("strategy_variant", 500)
        val mature = events.filter { it.strategy.equals(strategy, true) && it.variant.isNotBlank() }
            .groupBy { it.variant }.mapValues { (_, rows) -> rows.size to rows.sumOf { it.adjustment.toDouble() } }
            .filterValues { it.first >= 12 }
        if (mature.isEmpty()) return Evidence(0, "no mature strategy mutation winner yet")
        val best = mature.maxByOrNull { it.value.second } ?: return Evidence(0, "strategy mutation results neutral")
        return if (best.value.second > 0.0) Evidence(3, "mutation shadow winner ${best.key} samples=${best.value.first}") else Evidence(0, "strategy mutation results neutral")
    }

    /** Exact weighted ensemble from desktop advanced_ai.EnsembleDecisionEngine. */
    private fun ensembleVote(components: Map<String, Evidence>): Evidence {
        val weights = mapOf("ml" to 1.2, "order_book" to 1.0, "cross_market" to 0.8, "volatility" to 0.9, "liquidity" to 1.0, "data_quality" to 1.4, "portfolio" to 0.9, "mutation" to 0.5)
        val weighted = components.entries.sumOf { (k, v) -> v.adjustment * (weights[k] ?: 1.0) }
        val denom = max(1.0, components.keys.sumOf { weights[it] ?: 1.0 })
        val adj = (weighted / denom).roundToInt().coerceIn(-25, 18)
        val details = components.filterValues { it.adjustment != 0 }.entries.joinToString("; ") { (k, v) -> "$k:${if(v.adjustment>=0) "+" else ""}${v.adjustment} ${v.reason}" }
        return Evidence(adj, details.ifBlank { "ensemble neutral" })
    }

    /** Desktop model-version promotion/rollback scoring, persisted into research_state. */
    private suspend fun modelGovernance(model: OnlineModel, trades: List<TradeEntity>): ModelGovernance {
        val closed = trades.filter { it.side.equals("SELL", true) && abs(it.realizedPnlEur.toDoubleOrNull() ?: 0.0) > 1e-9 }.takeLast(200)
        if (closed.size < 20) return ModelGovernance(0, "model governance warm-up: ${closed.size}/20 outcome samples", activeModelVersion())
        val wins = closed.count { (it.realizedPnlEur.toDoubleOrNull() ?: 0.0) > 0.0 }
        val pnl = closed.sumOf { it.realizedPnlEur.toDoubleOrNull() ?: 0.0 }
        val wr = wins.toDouble() / closed.size
        val score = pnl + (wr - 0.5) * 100.0
        val state = runCatching { JSONObject(researchDao.state(MODEL_GOV_KEY)?.value ?: "{}") }.getOrDefault(JSONObject())
        var active = state.optString("active_version", "baseline")
        val versions = state.optJSONObject("versions") ?: JSONObject().also { state.put("versions", it) }
        if (!versions.has("baseline")) versions.put("baseline", JSONObject().put("score", 0.0).put("samples", 0).put("status", "active"))
        val current = versions.optJSONObject(active) ?: JSONObject().also { versions.put(active, it) }
        val prevScore = if (current.has("score")) current.optDouble("score", -1e9) else -1e9
        var adj = 0; var reason = "active model $active; validation score=${"%.2f".format(score)}, win=${"%.1f".format(wr*100)}%"
        if (closed.size >= 30 && score > prevScore + 1.5) {
            val newVersion = "model_${System.currentTimeMillis()}"
            val weights = JSONObject(); model.weights.forEach { (k,v) -> weights.put(k,v) }
            versions.put(newVersion, JSONObject().put("score", score).put("samples", closed.size).put("win_rate", wr).put("pnl", pnl).put("weights", weights).put("bias", model.bias).put("status", "active"))
            current.put("status", "archived"); state.put("rollback_version", active); active = newVersion; state.put("active_version", active)
            adj = 2; reason = "promoted stronger local model $active win=${"%.1f".format(wr*100)}% pnl=${"%.2f".format(pnl)}"
        } else if (closed.size >= 30 && score < prevScore - 8.0 && active != "baseline") {
            val rollback = state.optString("rollback_version", "baseline")
            current.put("status", "rolled_back"); versions.optJSONObject(rollback)?.put("status", "active"); active = rollback; state.put("active_version", active)
            adj = -4; reason = "rolled back weak model to $rollback"
        }
        researchDao.putState(ResearchStateEntity(MODEL_GOV_KEY, state.toString()))
        return ModelGovernance(adj, reason, active)
    }

    private suspend fun activeModelVersion(): String = runCatching { JSONObject(researchDao.state(MODEL_GOV_KEY)?.value ?: "{}").optString("active_version", "baseline") }.getOrDefault("baseline")

    /** Exact desktop anomaly thresholds. */
    private fun anomalyEvidence(ticker: MarketTicker, candles: List<Candle>, fakeBreakoutRisk: Double): Anomaly {
        val problems = mutableListOf<String>()
        val spread = StrategyMath.spreadPct(ticker.lastPrice.toDouble(), ticker.bid.toDouble(), ticker.ask.toDouble())
        if (spread > 1.25) problems += "spread explosion ${"%.2f".format(spread)}%"
        if (candles.size >= 3) {
            val prev = candles[candles.lastIndex - 1].close.toDouble(); val last = ticker.lastPrice.toDouble()
            if (prev > 0.0 && abs(last - prev) / prev * 100.0 > 8.0) problems += "abnormal price spike vs previous candle"
            val c = candles.last(); val range = (c.high.toDouble() - c.low.toDouble()) / max(1e-9, c.close.toDouble()) * 100.0
            if (range > 12.0) problems += "abnormal candle wick/range"
        }
        if (fakeBreakoutRisk > 0.85) problems += "very high fake-breakout risk"
        if (problems.isEmpty()) return Anomaly(2, "anomaly firewall clear", false)
        val hard = problems.any { it.contains("spread explosion") || it.contains("price spike") }
        return Anomaly(if (hard) -18 else -9, "anomaly firewall: ${problems.joinToString("; ")}", hard)
    }

    private suspend fun executionQualityEvidence(symbol: String, mode: BotMode): Evidence {
        val rows = runCatching { governanceDao.executionQuality(symbol, "BUY", if (mode == BotMode.PAPER) "PAPER" else "LIVE", 100) }.getOrDefault(emptyList())
        if (rows.size < 5) return Evidence(0, "execution-quality warm-up")
        // M3 stores signed slippage; keep desktop thresholds and use the persisted realized observations.
        val avg = rows.map { it.slippagePct }.average()
        return when {
            avg < -0.25 -> Evidence(-5, "historical execution slippage poor avg=${"%.3f".format(avg)}%")
            avg > -0.05 -> Evidence(2, "historical execution quality acceptable avg=${"%.3f".format(avg)}%")
            else -> Evidence(0, "historical execution quality neutral avg=${"%.3f".format(avg)}%")
        }
    }

    private suspend fun humanFeedbackEvidence(symbol: String): Evidence {
        val rows = researchDao.recentEventsByType("human_feedback", 500).filter { it.symbol.isBlank() || it.symbol.equals(symbol, true) }.take(50)
        if (rows.isEmpty()) return Evidence(0, "no human feedback for this symbol yet")
        val good = rows.count { it.reason.contains("good", true) || it.status.contains("good", true) }
        val bad = rows.count { it.reason.contains("bad", true) || it.reason.contains("risky", true) || it.status.contains("bad", true) }
        return Evidence((good - bad).coerceIn(-6, 6), "human feedback adjustment good=$good bad=$bad")
    }

    private suspend fun autonomousResearchEvidence(symbol: String, strategy: String, regime: String, trades: List<TradeEntity>): Evidence {
        val related = trades.filter { it.symbol.equals(symbol, true) && it.aiReason.contains(strategy, true) && abs(it.realizedPnlEur.toDoubleOrNull() ?: 0.0) > 1e-9 }.takeLast(30)
        if (related.size < 10) return Evidence(0, "research lab shadow evidence not mature")
        val pnl = related.sumOf { it.realizedPnlEur.toDoubleOrNull() ?: 0.0 }
        val wins = related.count { (it.realizedPnlEur.toDoubleOrNull() ?: 0.0) > 0.0 }
        val score = pnl + wins
        val variant = if (score < 0) "conservative" else "baseline_or_runner"
        researchDao.putState(ResearchStateEntity("desktop_research_lab:${strategy}:${regime}", JSONObject().put("samples", related.size).put("score", score).put("best_variant", variant).toString()))
        return when { score > 5 -> Evidence(2, "research lab supports $variant variant"); score < -5 -> Evidence(-3, "research lab recommends conservative variant"); else -> Evidence(0, "research lab shadow evidence neutral") }
    }

    /** Desktop Monte Carlo probability used only for its AdaptiveCapitalAllocator input. */
    private fun desktopDailyLimitProbability(trades: List<TradeEntity>, settings: BotSettings): Double {
        val pnls = trades.mapNotNull { it.realizedPnlEur.toDoubleOrNull() }.filter { abs(it) > 1e-9 }.takeLast(250)
        if (pnls.size < 20) return 0.0
        val rng = java.util.Random(1337L); val limit = max(1.0, settings.maxDailyLossEur.toDouble()); var hits = 0
        repeat(250) {
            var equity = 0.0; var worst = 0.0
            repeat(60) { equity += pnls[rng.nextInt(pnls.size)]; worst = min(worst, equity) }
            if (abs(worst) > limit) hits++
        }
        return hits / 250.0
    }

    /** Exact desktop AdaptiveCapitalAllocator; Android applies min(mult,1.0). */
    private fun adaptiveCapitalMultiplier(baseAdjustment: Int, probabilityDailyLimitHit: Double, decision: AiDecision): Double {
        var mult = 1.0
        if (decision.finalScore >= 85) mult += 0.15
        if (baseAdjustment > 8) mult += 0.15
        if (probabilityDailyLimitHit > 0.20) mult -= 0.35
        // Desktop used NEWS_HIGH_RISK risk flag. Android uses a strongly negative news score as the equivalent signal.
        if (decision.newsScore <= -50) mult -= 0.35
        return mult.coerceIn(0.15, 1.25)
    }

    companion object {
        private const val MODEL_KEY = "desktop_online_predictive_model_v1"
        private const val MODEL_CURSOR_KEY = "desktop_online_predictive_model_last_trade_id"
        private const val MODEL_GOV_KEY = "desktop_model_governance_v1"
    }
}
