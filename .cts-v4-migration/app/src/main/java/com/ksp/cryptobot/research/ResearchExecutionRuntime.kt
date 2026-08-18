package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.OrderType
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

data class ResearchExecutionDirective(
    val symbol: String,
    val allowedEntry: Boolean,
    val sideIntent: HandoffSideIntent,
    val strategyId: String,
    val strategyName: String,
    val fidelity: String,
    val implementationClass: String,
    val liveTruthGate: String,
    val sizeMultiplier: Double,
    val maxNotionalQuote: BigDecimal?,
    val preferredOrderType: OrderType?,
    val preferredLimitOrTriggerPrice: BigDecimal?,
    val postOnlyPreferred: Boolean = false,
    val makerFeeRate: BigDecimal? = null,
    val takerFeeRate: BigDecimal? = null,
    val feeSource: String = "fallback",
    val stopPrice: BigDecimal?,
    val targets: List<BigDecimal>,
    val costGatePassed: Boolean,
    val riskGatePassed: Boolean,
    val reason: String,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val expiresAtEpochMs: Long = System.currentTimeMillis() + 5 * 60_000L
)

/**
 * Symbol-scoped bridge from research into M4 execution.
 * Invariant: directives may only block or REDUCE an existing M4-approved quote.
 */
object ResearchExecutionRuntime {
    private val directives = ConcurrentHashMap<String, ResearchExecutionDirective>()
    private fun key(symbol: String) = symbol.uppercase().replace("/", "").replace("-", "")

    fun publish(directive: ResearchExecutionDirective) {
        directives[key(directive.symbol)] = directive.copy(sizeMultiplier = directive.sizeMultiplier.coerceIn(0.0, 1.0))
    }

    fun snapshot(symbol: String, nowEpochMs: Long = System.currentTimeMillis()): ResearchExecutionDirective? {
        val k = key(symbol)
        val value = directives[k] ?: return null
        if (value.expiresAtEpochMs < nowEpochMs) {
            directives.remove(k)
            return null
        }
        return value
    }

    fun clear(symbol: String) { directives.remove(key(symbol)) }
    fun clearAll() { directives.clear() }
}
