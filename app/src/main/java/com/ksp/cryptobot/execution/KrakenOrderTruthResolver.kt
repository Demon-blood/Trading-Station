
package com.ksp.cryptobot.execution

import com.ksp.cryptobot.exchange.KrakenDurableExecutionQuarantine
import com.ksp.cryptobot.exchange.KrakenPrivateExecutionRegistry
import com.ksp.cryptobot.exchange.KrakenSpotClient

data class KrakenDurableResolutionSummary(
    val resolvedOpen: Int,
    val resolvedClosed: Int,
    val resolvedNotFoundAfterGrace: Int,
    val unresolved: Int,
    val messages: List<String>
)

object KrakenOrderTruthResolver {
    const val NOT_FOUND_GRACE_MS = 10L * 60L * 1000L

    fun canClearAuthoritativeNotFound(startedAtEpochMs: Long, nowEpochMs: Long): Boolean =
        startedAtEpochMs > 0L && nowEpochMs - startedAtEpochMs >= NOT_FOUND_GRACE_MS

    suspend fun resolveDurable(
        exchange: KrakenSpotClient,
        nowEpochMs: Long = System.currentTimeMillis()
    ): KrakenDurableResolutionSummary {
        var open = 0
        var closed = 0
        var notFound = 0
        val messages = mutableListOf<String>()

        val unresolvedRows = KrakenDurableExecutionQuarantine.unresolved()
        for (row in unresolvedRows) {
            // resolveClientOrderId() is fail-closed: an OpenOrders or ClosedOrders API
            // failure throws instead of being interpreted as "not found".
            val truth = exchange.resolveClientOrderId(row.clientOrderId)
            when {
                truth.found && truth.open -> {
                    KrakenPrivateExecutionRegistry.clearSubmission(row.clientOrderId)
                    open++
                    messages += "Resolved ${row.clientOrderId} to OPEN Kraken order ${truth.exchangeOrderId} status=${truth.status} executed=${truth.executedQuantity}."
                }
                truth.found -> {
                    KrakenPrivateExecutionRegistry.clearSubmission(row.clientOrderId)
                    closed++
                    messages += "Resolved ${row.clientOrderId} to CLOSED Kraken order ${truth.exchangeOrderId} status=${truth.status} executed=${truth.executedQuantity}."
                }
                canClearAuthoritativeNotFound(row.startedAtEpochMs, nowEpochMs) -> {
                    KrakenPrivateExecutionRegistry.clearSubmission(row.clientOrderId)
                    notFound++
                    messages += "Cleared ${row.clientOrderId} only after authoritative OpenOrders+ClosedOrders returned not-found beyond the 10-minute consistency grace."
                }
                else -> {
                    messages += "Kept ${row.clientOrderId} quarantined: authoritative OpenOrders+ClosedOrders found no order yet, but the 10-minute consistency grace has not elapsed."
                }
            }
        }

        val remaining = KrakenDurableExecutionQuarantine.unresolved().size
        return KrakenDurableResolutionSummary(open, closed, notFound, remaining, messages)
    }
}
