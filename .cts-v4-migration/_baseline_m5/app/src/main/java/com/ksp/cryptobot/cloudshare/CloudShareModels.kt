package com.ksp.cryptobot.cloudshare

data class CloudShareCredentials(
    val clientId: String,
    val clientToken: String,
    val contributorId: String
)

data class CloudShareEvent(
    val eventId: String,
    val sourceTable: String,
    val eventTimestamp: String,
    val schemaVersion: Int = CloudShareProtocol.SCHEMA_VERSION,
    val payload: Map<String, Any?>,
    val payloadSha256: String
) {
    fun asWireMap(): Map<String, Any?> = linkedMapOf(
        "event_id" to eventId,
        "source_table" to sourceTable,
        "event_timestamp" to eventTimestamp,
        "schema_version" to schemaVersion,
        "payload" to payload,
        "payload_sha256" to payloadSha256
    )

    companion object {
        fun create(sourceTable: String, eventTimestamp: String, payload: Map<String, Any?>): CloudShareEvent {
            require(sourceTable.matches(Regex("^[a-z0-9_]{1,80}$"))) { "Invalid source_table: $sourceTable" }
            val sanitized = @Suppress("UNCHECKED_CAST")
                (CloudShareProtocol.sanitize(payload) as Map<String, Any?>)
            val payloadHash = CloudShareProtocol.sha256(CloudShareProtocol.canonicalJson(sanitized))
            return CloudShareEvent(
                eventId = CloudShareProtocol.eventId(sourceTable, eventTimestamp, sanitized),
                sourceTable = sourceTable,
                eventTimestamp = eventTimestamp,
                payload = sanitized,
                payloadSha256 = payloadHash
            )
        }
    }
}

data class CloudShareDownloadedEvent(
    val eventId: String,
    val aggregateKey: String,
    val contributorId: String,
    val sourceTable: String,
    val eventTimestamp: String,
    val receivedAt: String,
    val payload: Map<String, Any?>
)

data class CloudShareDownloadPage(
    val events: List<CloudShareDownloadedEvent>,
    val nextCursor: String,
    val hasMore: Boolean
)

data class CloudShareSyncResult(
    val uploaded: Int = 0,
    val duplicates: Int = 0,
    val rejected: Int = 0,
    val downloaded: Int = 0,
    val recentQueued: Int = 0,
    val aggregatesQueued: Int = 0,
    val backfilled: Int = 0,
    val collectiveOutcomeRows: Int = 0,
    val error: String = ""
) {
    val ok: Boolean get() = error.isBlank()
}
