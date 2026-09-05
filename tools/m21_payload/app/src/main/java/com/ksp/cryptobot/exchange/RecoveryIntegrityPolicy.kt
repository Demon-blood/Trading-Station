package com.ksp.cryptobot.exchange

import java.math.BigDecimal

data class RecoveryIntegritySnapshot(
    val authoritativeReconciliationComplete: Boolean,
    val networkUsable: Boolean,
    val privateExecutionContinuous: Boolean,
    val recentRestTruth: Boolean,
    val durableSubmissionAmbiguities: Int,
    val ambiguousOrderMutations: Int,
    val databaseHealthy: Boolean,
    val wallClockSane: Boolean,
    val distributedAuthorityHeld: Boolean,
    val unprotectedPositions: Int
)

object RecoveryIntegrityPolicy {
    fun canSubmitNewBuy(snapshot: RecoveryIntegritySnapshot): Pair<Boolean, String> {
        if (!snapshot.authoritativeReconciliationComplete) {
            return false to "Authoritative recovery reconciliation is incomplete."
        }
        if (!snapshot.networkUsable) {
            return false to "Validated network is unavailable."
        }
        if (!snapshot.privateExecutionContinuous && !snapshot.recentRestTruth) {
            return false to "Neither continuous private execution truth nor recent REST truth is available."
        }
        if (snapshot.durableSubmissionAmbiguities > 0) {
            return false to "Unresolved durable AddOrder ambiguity exists."
        }
        if (snapshot.ambiguousOrderMutations > 0) {
            return false to "An amend/cancel mutation has ambiguous outcome and requires exchange reconciliation."
        }
        if (!snapshot.databaseHealthy) {
            return false to "Durable application database state is unavailable."
        }
        if (!snapshot.wallClockSane) {
            return false to "Wall-clock continuity is unsafe; age/deadline decisions require reconciliation."
        }
        if (!snapshot.distributedAuthorityHeld) {
            return false to "Distributed LIVE execution authority is not held."
        }
        if (snapshot.unprotectedPositions > 0) {
            return false to "At least one authoritative position is not confirmed protected."
        }
        return true to "Recovery integrity prerequisites are satisfied."
    }

    fun protectiveSellQuantity(
        authoritativeBaseQuantity: BigDecimal,
        requestedSellQuantity: BigDecimal
    ): BigDecimal {
        if (authoritativeBaseQuantity <= BigDecimal.ZERO ||
            requestedSellQuantity <= BigDecimal.ZERO
        ) return BigDecimal.ZERO
        return requestedSellQuantity.min(authoritativeBaseQuantity)
    }
}

enum class PrivateSequenceDisposition {
    INITIAL,
    ACCEPT_NEXT,
    DUPLICATE,
    STALE_OR_OUT_OF_ORDER,
    GAP
}

object PrivateExecutionSequencePolicy {
    fun classify(
        lastSequence: Long,
        incomingSequence: Long,
        messageType: String
    ): PrivateSequenceDisposition {
        if (incomingSequence <= 0L) return PrivateSequenceDisposition.INITIAL
        if (messageType.equals("snapshot", ignoreCase = true)) {
            return PrivateSequenceDisposition.INITIAL
        }
        if (lastSequence <= 0L) return PrivateSequenceDisposition.ACCEPT_NEXT
        if (incomingSequence == lastSequence) return PrivateSequenceDisposition.DUPLICATE
        if (incomingSequence < lastSequence) return PrivateSequenceDisposition.STALE_OR_OUT_OF_ORDER
        if (incomingSequence == lastSequence + 1L) return PrivateSequenceDisposition.ACCEPT_NEXT
        return PrivateSequenceDisposition.GAP
    }
}

class IdempotentExecutionIdFilter(
    private val maxEntries: Int = 2_000
) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun accept(executionId: String): Boolean {
        val id = executionId.trim()
        if (id.isBlank()) return true
        if (!seen.add(id)) return false
        while (seen.size > maxEntries.coerceAtLeast(100)) {
            val oldest = seen.firstOrNull() ?: break
            seen.remove(oldest)
        }
        return true
    }

    @Synchronized
    fun clear() = seen.clear()
}

object RecoveryClockPolicy {
    const val MAX_BACKWARD_JUMP_MS = 5_000L

    fun sane(previousWallEpochMs: Long, currentWallEpochMs: Long): Boolean {
        if (previousWallEpochMs <= 0L || currentWallEpochMs <= 0L) return true
        return currentWallEpochMs + MAX_BACKWARD_JUMP_MS >= previousWallEpochMs
    }
}
