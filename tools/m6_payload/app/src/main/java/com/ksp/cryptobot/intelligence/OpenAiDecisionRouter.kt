package com.ksp.cryptobot.intelligence

import android.content.Context
import com.ksp.cryptobot.core.AiDecision
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.MarketTicker
import com.ksp.cryptobot.core.NewsArticle
import com.ksp.cryptobot.core.SignalAction
import com.ksp.cryptobot.data.TradeEntity
import com.ksp.cryptobot.settings.AppSettingsStore
import com.ksp.cryptobot.settings.CloudAiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

enum class CloudAiVerdict {
    APPROVE,
    REJECT,
    ABSTAIN,
    ESCALATE,
    SKIPPED,
    UNAVAILABLE
}

data class CloudAiCallUsage(
    val model: String,
    val inputTokens: Int,
    val cachedInputTokens: Int,
    val cacheWriteTokens: Int,
    val outputTokens: Int,
    val costUsd: BigDecimal,
    val costQuote: BigDecimal
)

data class CloudAiReview(
    val fingerprint: String,
    val symbol: String,
    val verdict: CloudAiVerdict,
    val confidence: BigDecimal,
    val strategy: String,
    val regime: String,
    val riskMultiplier: BigDecimal,
    val reason: String,
    val invalidationConditions: List<String>,
    val modelPath: String,
    val totalCostUsd: BigDecimal,
    /**
     * M5 quote-currency reserve. M6 conservatively uses USD cost 1:1 as EUR quote
     * until the dedicated FX utility lands, so AI cost is never understated.
     */
    val totalCostQuote: BigDecimal,
    val lunaUsage: CloudAiCallUsage? = null,
    val solUsage: CloudAiCallUsage? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

data class CloudAiRoutingResult(
    val decision: AiDecision,
    val review: CloudAiReview
)

object CloudAiRuntime {
    private const val TTL_MS = 2 * 60_000L
    private val reviews = ConcurrentHashMap<String, CloudAiReview>()

    private fun key(symbol: String): String =
        symbol.uppercase().replace("/", "").replace("-", "").replace("_", "")

    fun fingerprint(decision: AiDecision): String =
        "${key(decision.symbol)}|${decision.finalScore}|${decision.createdAt.toEpochMilli()}"

    fun publish(review: CloudAiReview) {
        reviews[key(review.symbol)] = review
    }

    fun snapshotFor(decision: AiDecision, nowEpochMs: Long = System.currentTimeMillis()): CloudAiReview? {
        val review = reviews[key(decision.symbol)] ?: return null
        if (review.fingerprint != fingerprint(decision)) return null
        if (nowEpochMs - review.createdAtEpochMs > TTL_MS) {
            reviews.remove(key(decision.symbol))
            return null
        }
        return review
    }

    fun all(): List<CloudAiReview> =
        reviews.values.sortedByDescending { it.createdAtEpochMs }

    fun clear() = reviews.clear()
}

object OpenAiModelEconomics {
    const val LUNA_MODEL = "gpt-5.6-luna"
    const val SOL_MODEL = "gpt-5.6-sol"

    // Current standard short-context prices per 1M text tokens at M6 implementation.
    private val LUNA_INPUT = BigDecimal("0.20")
    private val LUNA_CACHED = BigDecimal("0.02")
    private val LUNA_CACHE_WRITE = BigDecimal("0.25")
    private val LUNA_OUTPUT = BigDecimal("1.20")

    private val SOL_INPUT = BigDecimal("4.00")
    private val SOL_CACHED = BigDecimal("0.40")
    private val SOL_CACHE_WRITE = BigDecimal("5.00")
    private val SOL_OUTPUT = BigDecimal("20.00")

    // Conservative budget reservation before sending a request.
    val LUNA_MAX_CALL_RESERVE_USD: BigDecimal = BigDecimal("0.005")
    val SOL_MAX_CALL_RESERVE_USD: BigDecimal = BigDecimal("0.050")

    fun costUsd(
        model: String,
        inputTokens: Int,
        cachedInputTokens: Int,
        cacheWriteTokens: Int,
        outputTokens: Int
    ): BigDecimal {
        val input = inputTokens.coerceAtLeast(0)
        val cached = cachedInputTokens.coerceIn(0, input)
        val writes = cacheWriteTokens.coerceIn(0, (input - cached).coerceAtLeast(0))
        val ordinary = (input - cached - writes).coerceAtLeast(0)

        val prices = if (model == SOL_MODEL) {
            listOf(SOL_INPUT, SOL_CACHED, SOL_CACHE_WRITE, SOL_OUTPUT)
        } else {
            listOf(LUNA_INPUT, LUNA_CACHED, LUNA_CACHE_WRITE, LUNA_OUTPUT)
        }

        val million = BigDecimal("1000000")
        return BigDecimal(ordinary).multiply(prices[0]).divide(million, 12, RoundingMode.HALF_UP)
            .add(BigDecimal(cached).multiply(prices[1]).divide(million, 12, RoundingMode.HALF_UP))
            .add(BigDecimal(writes).multiply(prices[2]).divide(million, 12, RoundingMode.HALF_UP))
            .add(BigDecimal(outputTokens.coerceAtLeast(0)).multiply(prices[3]).divide(million, 12, RoundingMode.HALF_UP))
    }
}

data class CloudAiBudgetSnapshot(
    val month: String,
    val spentUsd: BigDecimal,
    val monthlyBudgetUsd: BigDecimal,
    val remainingUsd: BigDecimal,
    val lunaCalls: Int,
    val solCalls: Int,
    val solCallsToday: Int
)

class CloudAiBudgetLedger(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(
        "cts_cloud_ai_budget",
        Context.MODE_PRIVATE
    )
    private val lock = Any()

    fun snapshot(config: CloudAiConfig): CloudAiBudgetSnapshot = synchronized(lock) {
        val month = YearMonth.now(ZoneOffset.UTC).toString()
        val day = LocalDate.now(ZoneOffset.UTC).toString()
        val spent = prefs.getString("spent_$month", "0")
            ?.toBigDecimalOrNull()
            ?.max(BigDecimal.ZERO)
            ?: BigDecimal.ZERO
        val budget = config.monthlyBudgetUsd.max(BigDecimal.ZERO)
        CloudAiBudgetSnapshot(
            month = month,
            spentUsd = spent,
            monthlyBudgetUsd = budget,
            remainingUsd = budget.subtract(spent).max(BigDecimal.ZERO),
            lunaCalls = prefs.getInt("luna_calls_$month", 0),
            solCalls = prefs.getInt("sol_calls_$month", 0),
            solCallsToday = prefs.getInt("sol_calls_$day", 0)
        )
    }

    fun canReserve(config: CloudAiConfig, model: String, reserveUsd: BigDecimal): Pair<Boolean, String> =
        synchronized(lock) {
            val snap = snapshot(config)
            if (config.monthlyBudgetUsd <= BigDecimal.ZERO) {
                return@synchronized false to "Cloud AI monthly budget is zero."
            }
            if (snap.spentUsd.add(reserveUsd) > config.monthlyBudgetUsd) {
                return@synchronized false to "Cloud AI monthly budget would be exceeded. spent=${snap.spentUsd}, reserve=$reserveUsd, budget=${config.monthlyBudgetUsd}."
            }
            if (model == OpenAiModelEconomics.SOL_MODEL) {
                if (!config.solEnabled) {
                    return@synchronized false to "GPT-5.6 Sol escalation is disabled."
                }
                if (snap.solCallsToday >= config.maxSolCallsPerDay.coerceAtLeast(0)) {
                    return@synchronized false to "GPT-5.6 Sol daily call cap reached (${snap.solCallsToday}/${config.maxSolCallsPerDay})."
                }
            }
            true to "Budget available."
        }

    fun record(usage: CloudAiCallUsage) = synchronized(lock) {
        val month = YearMonth.now(ZoneOffset.UTC).toString()
        val day = LocalDate.now(ZoneOffset.UTC).toString()
        val current = prefs.getString("spent_$month", "0")?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val editor = prefs.edit()
            .putString("spent_$month", current.add(usage.costUsd).toPlainString())

        if (usage.model == OpenAiModelEconomics.SOL_MODEL) {
            editor.putInt("sol_calls_$month", prefs.getInt("sol_calls_$month", 0) + 1)
            editor.putInt("sol_calls_$day", prefs.getInt("sol_calls_$day", 0) + 1)
        } else {
            editor.putInt("luna_calls_$month", prefs.getInt("luna_calls_$month", 0) + 1)
        }
        editor.apply()
    }
}

data class OpenAiValidatorPayload(
    val verdict: CloudAiVerdict,
    val confidence: BigDecimal,
    val strategy: String,
    val regime: String,
    val riskMultiplier: BigDecimal,
    val reason: String,
    val invalidationConditions: List<String>
)

data class OpenAiValidatorCall(
    val payload: OpenAiValidatorPayload,
    val usage: CloudAiCallUsage
)

object CloudAiDecisionPolicy {
    fun shouldValidateWithLuna(decision: AiDecision, maxPositionQuote: BigDecimal): Boolean {
        if (!decision.allowedToTrade) return false
        if (decision.finalAction != SignalAction.BUY && decision.finalAction != SignalAction.SMALL_BUY) return false

        // Clear, high-confidence, low-news, small-size candidates remain on the €0 path.
        val clearlyDeterministic =
            decision.finalScore >= 86 &&
                decision.confidencePercent >= 82 &&
                abs(decision.newsScore) < 8 &&
                maxPositionQuote < BigDecimal("20")
        return !clearlyDeterministic
    }

    fun shouldEscalateToSol(
        payload: OpenAiValidatorPayload,
        maxPositionQuote: BigDecimal
    ): Boolean {
        if (payload.verdict == CloudAiVerdict.ESCALATE) return true
        if (payload.confidence < BigDecimal("0.60")) return true
        return maxPositionQuote >= BigDecimal("20") &&
            payload.verdict == CloudAiVerdict.ABSTAIN
    }

    fun applyReview(base: AiDecision, review: CloudAiReview): AiDecision {
        // Never create or strengthen a trade. Cloud review is veto/reduce only.
        if (!base.allowedToTrade ||
            (base.finalAction != SignalAction.BUY && base.finalAction != SignalAction.SMALL_BUY)
        ) return base

        return when (review.verdict) {
            CloudAiVerdict.REJECT -> base.copy(
                finalAction = SignalAction.WAIT,
                allowedToTrade = false,
                explanation = base.explanation +
                    " | Cloud AI REJECT (${review.modelPath}, cost≈${review.totalCostQuote.setScale(6, RoundingMode.HALF_UP)} quote): ${review.reason}"
            )
            else -> base.copy(
                explanation = base.explanation +
                    " | Cloud AI ${review.verdict} (${review.modelPath}, risk×${review.riskMultiplier.setScale(2, RoundingMode.HALF_UP)}, cost≈${review.totalCostQuote.setScale(6, RoundingMode.HALF_UP)} quote): ${review.reason}"
            )
        }
    }
}

class OpenAiDecisionRouter(
    context: Context,
    private val settingsStore: AppSettingsStore
) {
    private val budget = CloudAiBudgetLedger(context)
    private val client = OpenAiResponsesValidatorClient()

    suspend fun reviewIfUseful(
        decision: AiDecision,
        ticker: MarketTicker,
        settings: BotSettings,
        strategy: String,
        regime: String,
        news: List<NewsArticle>,
        recentTrades: List<TradeEntity>
    ): CloudAiRoutingResult {
        val fingerprint = CloudAiRuntime.fingerprint(decision)
        val config = settingsStore.cloudAiConfig()

        if (!CloudAiDecisionPolicy.shouldValidateWithLuna(decision, settings.maxPositionEur)) {
            return publishSkipped(
                decision, fingerprint, strategy, regime,
                "Deterministic/local path sufficient or candidate is not an approved BUY."
            )
        }

        if (!config.enabled) {
            return publishSkipped(
                decision, fingerprint, strategy, regime,
                "Selective cloud AI validation is disabled; deterministic €0 path used."
            )
        }

        val apiKey = settingsStore.openAiApiKey()
        if (apiKey.isNullOrBlank()) {
            return publishSkipped(
                decision, fingerprint, strategy, regime,
                "Selective cloud AI is enabled but no OpenAI API key is configured."
            )
        }

        val lunaBudget = budget.canReserve(
            config,
            OpenAiModelEconomics.LUNA_MODEL,
            OpenAiModelEconomics.LUNA_MAX_CALL_RESERVE_USD
        )
        if (!lunaBudget.first) {
            return publishSkipped(
                decision, fingerprint, strategy, regime,
                "Luna skipped by cost budget: ${lunaBudget.second}"
            )
        }

        val prompt = buildValidationPrompt(
            decision = decision,
            ticker = ticker,
            strategy = strategy,
            regime = regime,
            news = news,
            recentTrades = recentTrades,
            priorReview = null
        )

        val luna = runCatching {
            client.validate(
                apiKey = apiKey,
                model = OpenAiModelEconomics.LUNA_MODEL,
                prompt = prompt
            )
        }.getOrElse { error ->
            val review = CloudAiReview(
                fingerprint = fingerprint,
                symbol = decision.symbol,
                verdict = CloudAiVerdict.UNAVAILABLE,
                confidence = BigDecimal.ZERO,
                strategy = strategy,
                regime = regime,
                riskMultiplier = BigDecimal.ONE,
                reason = "Luna unavailable: ${safeError(error)}. Deterministic decision preserved.",
                invalidationConditions = emptyList(),
                modelPath = "DETERMINISTIC→LUNA_UNAVAILABLE",
                totalCostUsd = BigDecimal.ZERO,
                totalCostQuote = BigDecimal.ZERO
            )
            CloudAiRuntime.publish(review)
            return CloudAiRoutingResult(CloudAiDecisionPolicy.applyReview(decision, review), review)
        }
        budget.record(luna.usage)

        var finalPayload = luna.payload
        var finalPath = "LUNA"
        var solUsage: CloudAiCallUsage? = null

        if (CloudAiDecisionPolicy.shouldEscalateToSol(luna.payload, settings.maxPositionEur)) {
            val solBudget = budget.canReserve(
                config,
                OpenAiModelEconomics.SOL_MODEL,
                OpenAiModelEconomics.SOL_MAX_CALL_RESERVE_USD
            )
            if (solBudget.first) {
                val solPrompt = buildValidationPrompt(
                    decision = decision,
                    ticker = ticker,
                    strategy = strategy,
                    regime = regime,
                    news = news,
                    recentTrades = recentTrades,
                    priorReview = luna.payload
                )
                val sol = runCatching {
                    client.validate(
                        apiKey = apiKey,
                        model = OpenAiModelEconomics.SOL_MODEL,
                        prompt = solPrompt
                    )
                }.getOrNull()
                if (sol != null) {
                    budget.record(sol.usage)
                    finalPayload = sol.payload
                    solUsage = sol.usage
                    finalPath = "LUNA→SOL"
                } else {
                    finalPath = "LUNA→SOL_UNAVAILABLE"
                }
            } else {
                finalPath = "LUNA→SOL_SKIPPED"
            }
        }

        val totalCostUsd = luna.usage.costUsd.add(solUsage?.costUsd ?: BigDecimal.ZERO)
        val review = CloudAiReview(
            fingerprint = fingerprint,
            symbol = decision.symbol,
            verdict = finalPayload.verdict,
            confidence = finalPayload.confidence.coerceIn(BigDecimal.ZERO, BigDecimal.ONE),
            strategy = finalPayload.strategy.ifBlank { strategy },
            regime = finalPayload.regime.ifBlank { regime },
            riskMultiplier = finalPayload.riskMultiplier.coerceIn(BigDecimal.ZERO, BigDecimal.ONE),
            reason = finalPayload.reason.take(500),
            invalidationConditions = finalPayload.invalidationConditions.take(8).map { it.take(220) },
            modelPath = finalPath,
            totalCostUsd = totalCostUsd,
            // Conservative until a dedicated USD/EUR FX utility is connected.
            totalCostQuote = totalCostUsd,
            lunaUsage = luna.usage,
            solUsage = solUsage
        )
        CloudAiRuntime.publish(review)
        return CloudAiRoutingResult(
            decision = CloudAiDecisionPolicy.applyReview(decision, review),
            review = review
        )
    }

    fun budgetSnapshot(): CloudAiBudgetSnapshot =
        budget.snapshot(settingsStore.cloudAiConfig())

    private fun publishSkipped(
        decision: AiDecision,
        fingerprint: String,
        strategy: String,
        regime: String,
        reason: String
    ): CloudAiRoutingResult {
        val review = CloudAiReview(
            fingerprint = fingerprint,
            symbol = decision.symbol,
            verdict = CloudAiVerdict.SKIPPED,
            confidence = BigDecimal.ONE,
            strategy = strategy,
            regime = regime,
            riskMultiplier = BigDecimal.ONE,
            reason = reason,
            invalidationConditions = emptyList(),
            modelPath = "DETERMINISTIC",
            totalCostUsd = BigDecimal.ZERO,
            totalCostQuote = BigDecimal.ZERO
        )
        CloudAiRuntime.publish(review)
        return CloudAiRoutingResult(decision, review)
    }

    private fun buildValidationPrompt(
        decision: AiDecision,
        ticker: MarketTicker,
        strategy: String,
        regime: String,
        news: List<NewsArticle>,
        recentTrades: List<TradeEntity>,
        priorReview: OpenAiValidatorPayload?
    ): String {
        val recentOutcome = recentTrades.take(12).joinToString("; ") { row ->
            "${row.symbol}:${row.realizedPnlEur}"
        }.ifBlank { "none" }

        val newsText = news.take(5).joinToString("\n") { row ->
            "- ${row.source.take(40)}: ${row.title.take(180)}"
        }.ifBlank { "- none" }

        val prior = priorReview?.let {
            """
Prior Luna review:
verdict=${it.verdict}
confidence=${it.confidence}
risk_multiplier=${it.riskMultiplier}
reason=${it.reason.take(300)}
""".trimIndent()
        }.orEmpty()

        return """
Validate this already-deterministic crypto SPOT BUY candidate. You are a skeptical validator, not the trading strategy.
You may veto or reduce risk, but you must never recommend leverage, increase position size, invent missing market facts, or override deterministic risk controls.
APPROVE means "no additional cloud veto"; it does not create a trade.
REJECT when supplied evidence contains a material contradiction, clear event/news risk, or a reason the deterministic BUY should not proceed.
ABSTAIN when evidence is insufficient but there is no specific reason to veto.
ESCALATE only when the evidence is genuinely ambiguous enough to justify a much more expensive frontier review.
risk_multiplier must be 0..1 and may only reduce the already-approved size.

Candidate:
symbol=${decision.symbol}
action=${decision.finalAction}
score=${decision.finalScore}
deterministic_confidence=${decision.confidencePercent}
technical_score=${decision.technicalScore}
news_score=${decision.newsScore}
memory_score=${decision.memoryScore}
strategy=${strategy.take(80)}
regime=${regime.take(80)}
last=${ticker.lastPrice}
bid=${ticker.bid}
ask=${ticker.ask}
change24h_percent=${ticker.priceChangePercent24h}
volume24h_quote=${ticker.volume24h}
deterministic_reason=${decision.explanation.take(800)}

Recent realized trade outcomes (quote P/L, not calibrated probabilities):
$recentOutcome

Fresh news context:
$newsText

$prior
""".trimIndent()
    }

    private fun safeError(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName)
            .replace(Regex("sk-[A-Za-z0-9_-]+"), "[redacted]")
            .take(220)

    private fun BigDecimal.coerceIn(lo: BigDecimal, hi: BigDecimal): BigDecimal = when {
        this < lo -> lo
        this > hi -> hi
        else -> this
    }
}

private class OpenAiResponsesValidatorClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun validate(
        apiKey: String,
        model: String,
        prompt: String
    ): OpenAiValidatorCall = withContext(Dispatchers.IO) {
        val schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("decision", JSONObject()
                    .put("type", "string")
                    .put("enum", JSONArray(listOf("APPROVE", "REJECT", "ABSTAIN", "ESCALATE"))))
                .put("confidence", JSONObject()
                    .put("type", "number")
                    .put("minimum", 0)
                    .put("maximum", 1))
                .put("strategy", JSONObject().put("type", "string"))
                .put("regime", JSONObject().put("type", "string"))
                .put("risk_multiplier", JSONObject()
                    .put("type", "number")
                    .put("minimum", 0)
                    .put("maximum", 1))
                .put("reason", JSONObject().put("type", "string"))
                .put("invalidation_conditions", JSONObject()
                    .put("type", "array")
                    .put("items", JSONObject().put("type", "string"))
                    .put("maxItems", 8)))
            .put("required", JSONArray(listOf(
                "decision",
                "confidence",
                "strategy",
                "regime",
                "risk_multiplier",
                "reason",
                "invalidation_conditions"
            )))
            .put("additionalProperties", false)

        val requestJson = JSONObject()
            .put("model", model)
            .put("store", false)
            .put("reasoning", JSONObject().put("effort", "low"))
            .put("max_output_tokens", 500)
            .put("instructions",
                "Return only the strict structured validator result. Treat supplied market data as the entire observable world; do not infer fresh prices or news beyond it.")
            .put("input", prompt)
            .put("text", JSONObject()
                .put("format", JSONObject()
                    .put("type", "json_schema")
                    .put("name", "crypto_trade_validator")
                    .put("strict", true)
                    .put("schema", schema)))

        val body = requestJson.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        http.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val compact = runCatching {
                    JSONObject(responseBody)
                        .optJSONObject("error")
                        ?.optString("message")
                        ?.take(220)
                }.getOrNull().orEmpty()
                error("OpenAI $model HTTP ${response.code}${if (compact.isBlank()) "" else ": $compact"}")
            }

            val root = JSONObject(responseBody)
            val outputText = extractOutputText(root)
            if (outputText.isBlank()) error("OpenAI $model returned no structured output text.")

            val parsed = JSONObject(outputText)
            val verdict = runCatching {
                CloudAiVerdict.valueOf(parsed.getString("decision").uppercase())
            }.getOrDefault(CloudAiVerdict.ABSTAIN)

            val payload = OpenAiValidatorPayload(
                verdict = verdict,
                confidence = parsed.opt("confidence")?.toString()?.toBigDecimalOrNull()
                    ?: BigDecimal.ZERO,
                strategy = parsed.optString("strategy", ""),
                regime = parsed.optString("regime", ""),
                riskMultiplier = parsed.opt("risk_multiplier")?.toString()?.toBigDecimalOrNull()
                    ?: BigDecimal.ONE,
                reason = parsed.optString("reason", "").take(500),
                invalidationConditions = parsed.optJSONArray("invalidation_conditions")
                    ?.let { array ->
                        (0 until array.length()).mapNotNull { index ->
                            array.optString(index, "").takeIf { it.isNotBlank() }
                        }
                    }.orEmpty()
            )

            val usageJson = root.optJSONObject("usage")
            val inputTokens = usageJson?.optInt("input_tokens", 0) ?: 0
            val outputTokens = usageJson?.optInt("output_tokens", 0) ?: 0
            val inputDetails = usageJson?.optJSONObject("input_tokens_details")
            val cachedTokens = inputDetails?.optInt("cached_tokens", 0) ?: 0
            val cacheWriteTokens = inputDetails?.optInt("cache_write_tokens", 0) ?: 0
            val costUsd = OpenAiModelEconomics.costUsd(
                model = model,
                inputTokens = inputTokens,
                cachedInputTokens = cachedTokens,
                cacheWriteTokens = cacheWriteTokens,
                outputTokens = outputTokens
            )

            OpenAiValidatorCall(
                payload = payload,
                usage = CloudAiCallUsage(
                    model = model,
                    inputTokens = inputTokens,
                    cachedInputTokens = cachedTokens,
                    cacheWriteTokens = cacheWriteTokens,
                    outputTokens = outputTokens,
                    costUsd = costUsd,
                    costQuote = costUsd
                )
            )
        }
    }

    private fun extractOutputText(root: JSONObject): String {
        val output = root.optJSONArray("output") ?: return ""
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") {
                    val text = part.optString("text", "")
                    if (text.isNotBlank()) return text
                }
            }
        }
        return ""
    }
}
