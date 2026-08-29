package com.ksp.cryptobot.execution

class ExchangeTruthUnavailableException(
    val truthSource: String,
    cause: Throwable
) : IllegalStateException(
    "Authoritative exchange truth unavailable for $truthSource: ${cause.message ?: cause.javaClass.simpleName}",
    cause
)

/**
 * M11 fail-closed boundary between transport/API uncertainty and authoritative state.
 *
 * An exception is not an empty exchange snapshot. Callers must either receive a real
 * provider result (which may legitimately be empty) or fail before mutating local
 * positions / granting LIVE entry authority.
 */
object ExecutionTruthGate {
    fun <T> requireAuthoritative(
        source: String,
        result: Result<T>
    ): T = result.getOrElse { error ->
        throw ExchangeTruthUnavailableException(source, error)
    }
}
