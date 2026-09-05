package com.ksp.cryptobot.observability

import java.math.BigDecimal
import java.util.UUID

/** Bounded in-process M23 decision lineage. Durable trading truth remains in Kraken/DB. */
data class DecisionLineageRecord(
    val timestampEpochMs: Long,
    val correlationId: String,
    val stage: String,
    val symbol: String,
    val strategy: String,
    val mode: String,
    val action: String,
    val confidencePercent: Int?,
    val orderType: String,
    val clientOrderId: String,
    val exchangeOrderId: String,
    val value: String,
    val blocked: Boolean,
    val reason: String
)

data class M23EconomicsSnapshot(
    val symbol: String,
    val m5ExpectedNetEvRate: String = "UNKNOWN",
    val m20AdjustedExpectedNetEvRate: String = "UNKNOWN",
    val updatedAtEpochMs: Long = 0L,
    val reason: String = ""
)

object M23DecisionLineageRuntime {
    private const val MAX_RECORDS = 400
    private val lock = Any()
    private val records = ArrayDeque<DecisionLineageRecord>()
    private val activeCorrelationBySymbol = linkedMapOf<String, String>()
    private val correlationByClientOrderId = linkedMapOf<String, String>()
    private val economics = linkedMapOf<String, M23EconomicsSnapshot>()

    fun recordCandidate(
        symbol: String,
        strategy: String,
        mode: String,
        action: String,
        confidencePercent: Int?,
        marketPrice: BigDecimal
    ): String = synchronized(lock) {
        val key = normalize(symbol)
        val correlation = activeCorrelationBySymbol[key] ?: newCorrelation(key)
        appendLocked(
            DecisionLineageRecord(
                timestampEpochMs = System.currentTimeMillis(),
                correlationId = correlation,
                stage = "CANDIDATE_DECISION",
                symbol = key,
                strategy = strategy,
                mode = mode,
                action = action,
                confidencePercent = confidencePercent,
                orderType = "",
                clientOrderId = "",
                exchangeOrderId = "",
                value = marketPrice.stripTrailingZeros().toPlainString(),
                blocked = false,
                reason = "Candidate decision captured before order construction."
            )
        )
        correlation
    }

    fun recordAdvancedExecution(
        eventType: String,
        symbol: String,
        strategy: String,
        mode: String,
        requested: BigDecimal,
        final: BigDecimal,
        metric: BigDecimal,
        orderType: String,
        category: String,
        blocked: Boolean,
        reason: String
    ) {
        synchronized(lock) {
            val key = normalize(symbol)
            if (key.isBlank()) return
            val correlation = if (eventType == "capital_protection") {
                newCorrelation(key)
            } else {
                activeCorrelationBySymbol[key] ?: newCorrelation(key)
            }
            val stage = when (eventType) {
                "research_execution_cap" -> "STRATEGY_GOVERNANCE"
                "capital_protection" -> "RISK"
                "portfolio_correlation", "portfolio_allocation" -> "PORTFOLIO_ALLOCATION"
                "liquidity_sizing" -> "MICROSTRUCTURE_SIZING"
                "cloud_ai_cap" -> "OPTIONAL_AI"
                "entry_economics" -> "M5_ECONOMICS"
                "net_profit_optimizer" -> "M20_ECONOMICS"
                "order_type" -> "ORDER_TYPE"
                "entry_plan" -> "FINAL_ENTRY_PLAN"
                else -> eventType.uppercase()
            }
            appendLocked(
                DecisionLineageRecord(
                    timestampEpochMs = System.currentTimeMillis(),
                    correlationId = correlation,
                    stage = stage,
                    symbol = key,
                    strategy = strategy,
                    mode = mode,
                    action = category,
                    confidencePercent = null,
                    orderType = orderType,
                    clientOrderId = "",
                    exchangeOrderId = "",
                    value = "requested=${requested.stripTrailingZeros().toPlainString()},final=${final.stripTrailingZeros().toPlainString()},metric=${metric.stripTrailingZeros().toPlainString()}",
                    blocked = blocked,
                    reason = M23Redaction.sanitizeText(reason)
                )
            )
            if (eventType == "entry_economics" || eventType == "net_profit_optimizer") {
                val previous = economics[key] ?: M23EconomicsSnapshot(key)
                economics[key] = if (eventType == "entry_economics") {
                    previous.copy(
                        m5ExpectedNetEvRate = metric.stripTrailingZeros().toPlainString(),
                        updatedAtEpochMs = System.currentTimeMillis(),
                        reason = M23Redaction.sanitizeText(reason)
                    )
                } else {
                    previous.copy(
                        m20AdjustedExpectedNetEvRate = metric.stripTrailingZeros().toPlainString(),
                        updatedAtEpochMs = System.currentTimeMillis(),
                        reason = M23Redaction.sanitizeText(reason)
                    )
                }
            }
        }
    }

    fun recordOrderSubmission(
        symbol: String,
        strategy: String,
        mode: String,
        action: String,
        orderType: String,
        clientOrderId: String
    ) {
        synchronized(lock) {
            val key = normalize(symbol)
            val correlation = activeCorrelationBySymbol[key] ?: newCorrelation(key)
            if (clientOrderId.isNotBlank()) correlationByClientOrderId[clientOrderId] = correlation
            appendLocked(
                DecisionLineageRecord(
                    timestampEpochMs = System.currentTimeMillis(),
                    correlationId = correlation,
                    stage = "ORDER_SUBMISSION",
                    symbol = key,
                    strategy = strategy,
                    mode = mode,
                    action = action,
                    confidencePercent = null,
                    orderType = orderType,
                    clientOrderId = clientOrderId,
                    exchangeOrderId = "",
                    value = "",
                    blocked = false,
                    reason = "Order passed deterministic pre-submission gates."
                )
            )
        }
    }

    fun recordOrderResult(
        symbol: String,
        clientOrderId: String,
        exchangeOrderId: String,
        side: String,
        fillConfirmed: Boolean,
        realizedPnlQuote: BigDecimal
    ) {
        synchronized(lock) {
            val key = normalize(symbol)
            val correlation = correlationByClientOrderId[clientOrderId]
                ?: activeCorrelationBySymbol[key]
                ?: newCorrelation(key)
            appendLocked(
                DecisionLineageRecord(
                    timestampEpochMs = System.currentTimeMillis(),
                    correlationId = correlation,
                    stage = if (fillConfirmed) "FILL" else "ORDER_ACCEPTED_UNFILLED",
                    symbol = key,
                    strategy = "",
                    mode = "",
                    action = side,
                    confidencePercent = null,
                    orderType = "",
                    clientOrderId = clientOrderId,
                    exchangeOrderId = exchangeOrderId,
                    value = "realizedPnlQuote=${realizedPnlQuote.stripTrailingZeros().toPlainString()}",
                    blocked = false,
                    reason = if (fillConfirmed) "Exchange fill evidence linked to candidate/order correlation." else "Order accepted; fill remains unconfirmed."
                )
            )
            if (fillConfirmed) {
                correlationByClientOrderId.remove(clientOrderId)
                activeCorrelationBySymbol.remove(key)
            }
        }
    }

    fun recent(limit: Int = 100): List<DecisionLineageRecord> = synchronized(lock) {
        records.takeLast(limit.coerceIn(1, MAX_RECORDS)).reversed()
    }

    fun latestForSymbol(symbol: String): DecisionLineageRecord? = synchronized(lock) {
        val key = normalize(symbol)
        records.lastOrNull { it.symbol == key }
    }

    fun economics(): List<M23EconomicsSnapshot> = synchronized(lock) { economics.values.toList() }

    fun clearForTests() = synchronized(lock) {
        records.clear()
        activeCorrelationBySymbol.clear()
        correlationByClientOrderId.clear()
        economics.clear()
    }

    private fun newCorrelation(symbol: String): String {
        val id = "m23-${UUID.randomUUID()}"
        activeCorrelationBySymbol[symbol] = id
        return id
    }

    private fun appendLocked(record: DecisionLineageRecord) {
        records.addLast(record)
        while (records.size > MAX_RECORDS) records.removeFirst()
    }

    private fun normalize(symbol: String): String = symbol
        .uppercase()
        .replace("/", "")
        .replace("-", "")
        .replace("_", "")
}
