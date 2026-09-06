package com.ksp.cryptobot.execution

enum class M24AuthorityPlatform(val wireName: String) {
    ANDROID("ANDROID"),
    WINDOWS("WINDOWS");

    companion object {
        fun parse(value: String): M24AuthorityPlatform? =
            entries.firstOrNull { it.wireName.equals(value.trim(), ignoreCase = true) }
    }
}

data class M24AuthorityTransferTarget(
    val clientId: String,
    val engineId: String,
    val platform: M24AuthorityPlatform
) {
    init {
        require(clientId.isNotBlank()) { "target clientId is required" }
        require(engineId.isNotBlank()) { "target engineId is required" }
    }
}

/**
 * Pure M24 cross-platform authority policy.
 *
 * The CloudShare/D1 lease remains the single source of distributed ownership truth.
 * Android and Windows are protocol peers: neither platform receives a special right to
 * trade. A BUY is allowed only when the local lease is live AND the Worker has just
 * confirmed the same engine, platform and fencing token immediately before AddOrder.
 */
object M24CrossPlatformAuthorityPolicy {
    const val PROTOCOL_REVISION = 24
    const val LEASE_SCHEMA_VERSION = 2
    const val RESPONSE_SAFETY_MARGIN_MS = 1_500L

    fun supportedPlatform(value: String): Boolean = M24AuthorityPlatform.parse(value) != null

    /**
     * Server lease_remaining_ms is measured when the Worker handles the request.
     * Subtract the full local request RTT plus a fixed margin so delayed responses never
     * extend the local deadline beyond the server's lease deadline.
     */
    fun conservativeRemainingMs(serverRemainingMs: Long, roundTripMs: Long): Long =
        (serverRemainingMs - roundTripMs.coerceAtLeast(0L) - RESPONSE_SAFETY_MARGIN_MS)
            .coerceAtLeast(0L)

    fun remoteSubmissionValid(
        remoteReachable: Boolean,
        owned: Boolean,
        holderMatches: Boolean,
        holderPlatform: String,
        expectedPlatform: M24AuthorityPlatform,
        schemaVersion: Int,
        expectedFence: Long,
        responseFence: Long,
        conservativeRemainingMs: Long
    ): Boolean =
        remoteReachable &&
            owned &&
            holderMatches &&
            M24AuthorityPlatform.parse(holderPlatform) == expectedPlatform &&
            schemaVersion == LEASE_SCHEMA_VERSION &&
            expectedFence > 0L &&
            responseFence == expectedFence &&
            conservativeRemainingMs > 0L

    fun transferAccepted(
        transferred: Boolean,
        oldFence: Long,
        newFence: Long,
        targetEngineId: String,
        responseHolderEngineId: String,
        targetPlatform: M24AuthorityPlatform,
        responseHolderPlatform: String,
        conservativeRemainingMs: Long
    ): Boolean =
        transferred &&
            oldFence > 0L &&
            newFence > oldFence &&
            targetEngineId.isNotBlank() &&
            responseHolderEngineId == targetEngineId &&
            M24AuthorityPlatform.parse(responseHolderPlatform) == targetPlatform &&
            conservativeRemainingMs > 0L

    fun staleFenceRejected(staleFence: Long, currentFence: Long): Boolean =
        staleFence <= 0L || currentFence <= 0L || staleFence != currentFence
}
