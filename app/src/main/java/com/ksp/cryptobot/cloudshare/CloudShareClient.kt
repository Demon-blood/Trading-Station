package com.ksp.cryptobot.cloudshare

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

class CloudShareHttpException(
    val statusCode: Int,
    message: String,
    val responseBody: String = ""
) : RuntimeException(message)

data class CloudShareUploadResponse(
    val acceptedEventIds: List<String>,
    val duplicateEventIds: List<String>,
    val rejected: List<Pair<String, String>>
)

class CloudShareClient(
    apiUrl: String,
    private val credentials: CloudShareCredentials? = null,
    private val adminToken: String = "",
    timeoutSeconds: Long = 30
) {
    private val baseUrl = apiUrl.trim().trimEnd('/')
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val moshi = Moshi.Builder().build()
    private val mapType = Types.newParameterizedType(
        Map::class.java,
        String::class.java,
        Any::class.java
    )
    @Suppress("UNCHECKED_CAST")
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)
    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .build()

    init {
        require(baseUrl.startsWith("https://")) { "CloudShare requires an HTTPS Worker URL." }
    }

    suspend fun health(): Map<String, Any?> = requestMap("GET", "/v1/health", authenticated = false)

    suspend fun register(inviteCode: String, contributorId: String): CloudShareCredentials {
        val response = requestMap(
            method = "POST",
            path = "/v1/register",
            authenticated = false,
            extraHeaders = mapOf("X-CloudShare-Invite" to inviteCode.trim()),
            body = mapOf(
                "contributor_id" to contributorId,
                "client_name" to "Crypto TradeStation Android"
            )
        )
        val clientId = response.string("client_id")
        val token = response.string("client_token")
        require(clientId.isNotBlank() && token.isNotBlank()) {
            "CloudShare registration response did not contain credentials."
        }
        return CloudShareCredentials(clientId, token, contributorId)
    }

    suspend fun clientStatus(): Map<String, Any?> = requestMap("GET", "/v1/client/status")
    suspend fun intelligenceStatus(): Map<String, Any?> = requestMap("GET", "/v1/intelligence/status")

    suspend fun uploadEvents(contributorId: String, events: List<CloudShareEvent>): CloudShareUploadResponse {
        require(events.isNotEmpty()) { "Cannot upload an empty CloudShare batch." }
        require(events.size <= CloudShareProtocol.MAX_EVENTS_PER_BATCH) { "CloudShare batch exceeds 250 events." }
        val body = linkedMapOf<String, Any?>(
            "protocol_version" to CloudShareProtocol.PROTOCOL_VERSION,
            "contributor_id" to contributorId,
            "batch_id" to CloudShareProtocol.batchId(events.map { it.eventId }, contributorId),
            "sent_at" to CloudShareProtocol.nowIso(),
            "events" to events.map { it.asWireMap() }
        )
        val response = requestMap("POST", "/v1/events/batch", body = body)
        val accepted = response.stringList("accepted_event_ids")
        val duplicates = response.stringList("duplicate_event_ids")
        val rejected = (response["rejected"] as? List<*>)
            .orEmpty()
            .mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                val id = map["event_id"]?.toString().orEmpty()
                val error = map["error"]?.toString().orEmpty()
                id to error
            }
        return CloudShareUploadResponse(accepted, duplicates, rejected)
    }

    suspend fun downloadIntelligence(cursor: String = "", limit: Int = 5000): CloudShareDownloadPage {
        val bounded = limit.coerceIn(1, 10_000)
        val suffix = buildString {
            append("?limit=$bounded")
            if (cursor.isNotBlank()) append("&cursor=${java.net.URLEncoder.encode(cursor, "UTF-8")}")
        }
        val response = requestMap("GET", "/v1/intelligence/events$suffix")
        val events = (response["events"] as? List<*>)
            .orEmpty()
            .mapNotNull { raw ->
                val map = raw as? Map<*, *> ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                val payload = (map["payload"] as? Map<String, Any?>).orEmpty()
                CloudShareDownloadedEvent(
                    eventId = map["event_id"]?.toString().orEmpty(),
                    aggregateKey = map["aggregate_key"]?.toString().orEmpty(),
                    contributorId = map["contributor_id"]?.toString().orEmpty(),
                    sourceTable = map["source_table"]?.toString().orEmpty(),
                    eventTimestamp = map["event_timestamp"]?.toString().orEmpty(),
                    receivedAt = map["received_at"]?.toString().orEmpty(),
                    payload = payload
                )
            }
        return CloudShareDownloadPage(
            events = events,
            nextCursor = response.string("next_cursor"),
            hasMore = response.boolean("has_more")
        )
    }

    suspend fun uploadBootstrap(file: java.io.File, contributorId: String): Map<String, Any?> = withContext(Dispatchers.IO) {
        require(file.exists() && file.isFile) { "Bootstrap archive does not exist." }
        require(file.length() in 1..50_000_000) { "Bootstrap archive must be between 1 byte and 50 MB." }
        val creds = credentials ?: error("CloudShare client is not registered.")
        val bytes = file.readBytes()
        val request = Request.Builder()
            .url(baseUrl + "/v1/bootstrap")
            .header("Accept", "application/json")
            .header("User-Agent", "CryptoTradeStation-Android-CloudShare/${CloudShareProtocol.PROTOCOL_VERSION}")
            .header("X-CTS-Protocol", CloudShareProtocol.PROTOCOL_VERSION)
            .header("X-CTS-Client-ID", creds.clientId)
            .header("Authorization", "Bearer ${creds.clientToken}")
            .header("X-CTS-Contributor-ID", contributorId)
            .header("X-CTS-File-Name", file.name)
            .header("X-CTS-SHA256", CloudShareProtocol.sha256Bytes(bytes))
            .post(file.asRequestBody("application/zip".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { mapAdapter.fromJson(text).orEmpty() }.getOrDefault(emptyMap())
            if (!response.isSuccessful) {
                val serverError = parsed["error"]?.toString().orEmpty().ifBlank { response.message }
                throw CloudShareHttpException(response.code, "CloudShare HTTP ${response.code}: $serverError", text.take(1200))
            }
            parsed
        }
    }

    suspend fun acquireEngineLease(
        accountKey: String,
        engineId: String,
        platform: String,
        ttlSeconds: Int
    ): Map<String, Any?> = requestMap(
        "POST",
        "/v1/engine-lease/acquire",
        body = mapOf(
            "account_key" to accountKey,
            "engine_id" to engineId,
            "platform" to platform,
            "ttl_seconds" to ttlSeconds.coerceIn(30, 300)
        )
    )

    suspend fun heartbeatEngineLease(
        accountKey: String,
        engineId: String,
        platform: String,
        fenceToken: Long,
        ttlSeconds: Int
    ): Map<String, Any?> = requestMap(
        "POST",
        "/v1/engine-lease/heartbeat",
        body = mapOf(
            "account_key" to accountKey,
            "engine_id" to engineId,
            "platform" to platform,
            "fence_token" to fenceToken,
            "ttl_seconds" to ttlSeconds.coerceIn(30, 300)
        )
    )

    suspend fun releaseEngineLease(
        accountKey: String,
        engineId: String,
        fenceToken: Long
    ): Map<String, Any?> = requestMap(
        "POST",
        "/v1/engine-lease/release",
        body = mapOf(
            "account_key" to accountKey,
            "engine_id" to engineId,
            "fence_token" to fenceToken
        )
    )

    suspend fun transferEngineLease(
        accountKey: String,
        engineId: String,
        fenceToken: Long,
        targetClientId: String,
        targetEngineId: String,
        targetPlatform: String,
        ttlSeconds: Int
    ): Map<String, Any?> = requestMap(
        "POST",
        "/v1/engine-lease/transfer",
        body = mapOf(
            "account_key" to accountKey,
            "engine_id" to engineId,
            "fence_token" to fenceToken,
            "target_client_id" to targetClientId,
            "target_engine_id" to targetEngineId,
            "target_platform" to targetPlatform,
            "ttl_seconds" to ttlSeconds.coerceIn(30, 300)
        )
    )

    suspend fun engineLeaseStatus(
        accountKey: String,
        engineId: String,
        fenceToken: Long
    ): Map<String, Any?> = requestMap(
        "POST",
        "/v1/engine-lease/status",
        body = mapOf(
            "account_key" to accountKey,
            "engine_id" to engineId,
            "fence_token" to fenceToken
        )
    )

    suspend fun adminPing(): Map<String, Any?> = requestMap("GET", "/v1/admin/ping", admin = true)

    suspend fun adminCreateInvite(label: String, maxUses: Int = 1, expiresInHours: Int = 168): Map<String, Any?> =
        requestMap(
            "POST",
            "/v1/admin/invites",
            admin = true,
            body = mapOf(
                "label" to label,
                "max_uses" to maxUses.coerceIn(1, 100),
                "expires_in_hours" to expiresInHours.coerceIn(1, 8760)
            )
        )

    suspend fun adminInvites(): Map<String, Any?> = requestMap("GET", "/v1/admin/invites", admin = true)
    suspend fun adminClients(): Map<String, Any?> = requestMap("GET", "/v1/admin/clients", admin = true)

    suspend fun adminRevokeInvite(inviteId: String): Map<String, Any?> =
        requestMap("POST", "/v1/admin/invites/$inviteId/revoke", admin = true, body = emptyMap())

    suspend fun adminClientAction(clientId: String, action: String, reason: String = ""): Map<String, Any?> {
        val normalized = action.lowercase()
        require(normalized in setOf("disable", "enable", "rotate")) { "Unsupported admin action: $action" }
        return requestMap(
            "POST",
            "/v1/admin/clients/$clientId/$normalized",
            admin = true,
            body = if (normalized == "disable") mapOf("reason" to reason) else emptyMap()
        )
    }

    private suspend fun requestMap(
        method: String,
        path: String,
        authenticated: Boolean = true,
        admin: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap(),
        body: Map<String, Any?>? = null
    ): Map<String, Any?> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) error("CloudShare API URL is not configured.")
        val builder = Request.Builder()
            .url(baseUrl + path)
            .header("Accept", "application/json")
            .header("User-Agent", "CryptoTradeStation-Android-CloudShare/${CloudShareProtocol.PROTOCOL_VERSION}")
            .header("X-CTS-Protocol", CloudShareProtocol.PROTOCOL_VERSION)
            .header("X-Request-ID", UUID.randomUUID().toString())

        if (admin) {
            require(adminToken.isNotBlank()) { "CloudShare owner/admin token is not configured." }
            builder.header("X-CloudShare-Admin", adminToken)
        } else if (authenticated) {
            val creds = credentials ?: error("CloudShare client is not registered.")
            builder.header("X-CTS-Client-ID", creds.clientId)
            builder.header("Authorization", "Bearer ${creds.clientToken}")
        }
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }

        val requestBody = body?.let { mapAdapter.toJson(it).toRequestBody(jsonMediaType) }
        when (method.uppercase()) {
            "GET" -> builder.get()
            "POST" -> builder.post(requestBody ?: "{}".toRequestBody(jsonMediaType))
            else -> error("Unsupported HTTP method: $method")
        }

        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { mapAdapter.fromJson(text).orEmpty() }.getOrDefault(emptyMap())
            if (!response.isSuccessful) {
                val serverError = parsed["error"]?.toString().orEmpty().ifBlank { response.message }
                throw CloudShareHttpException(response.code, "CloudShare HTTP ${response.code}: $serverError", text.take(1200))
            }
            parsed
        }
    }

    private fun Map<String, Any?>.string(key: String): String = this[key]?.toString().orEmpty()
    private fun Map<String, Any?>.boolean(key: String): Boolean = when (val value = this[key]) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> value?.toString()?.equals("true", ignoreCase = true) == true
    }
    private fun Map<String, Any?>.stringList(key: String): List<String> =
        (this[key] as? List<*>)?.map { it.toString() }.orEmpty()
}
