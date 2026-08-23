package com.ksp.cryptobot.cloudshare

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CloudShareProvisioningStep(
    val name: String,
    val status: String,
    val detail: String
)

data class CloudShareProvisioningResult(
    val success: Boolean,
    val workerUrl: String = "",
    val d1DatabaseId: String = "",
    val r2BucketName: String = "",
    val workerName: String = "",
    val firstInviteCode: String = "",
    val steps: List<CloudShareProvisioningStep> = emptyList(),
    val error: String = ""
)

class CloudShareProvisioner(context: Context) {
    private val appContext = context.applicationContext
    private val store = CloudShareSettingsStore(appContext)
    private val engine = CloudShareSyncEngine(appContext)
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun provision(
        accountId: String,
        apiToken: String,
        workerName: String,
        d1Name: String,
        r2BucketName: String,
        euResidency: Boolean = true,
        backfillEnabled: Boolean = true,
        syncIntervalMinutes: Int = 5,
        onProgress: (CloudShareProvisioningStep) -> Unit = {}
    ): CloudShareProvisioningResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<CloudShareProvisioningStep>()
        fun progress(name: String, status: String, detail: String) {
            val row = CloudShareProvisioningStep(name, status, detail)
            steps += row
            onProgress(row)
        }

        val cleanAccount = accountId.trim()
        val cleanToken = apiToken.trim()
        val cleanWorker = safeResourceName(workerName, "cts-cloudshare")
        val cleanD1 = safeResourceName(d1Name, "cts-cloudshare-db")
        val cleanR2 = safeResourceName(r2BucketName, "cts-cloudshare-backups")

        require(cleanAccount.matches(Regex("^[a-fA-F0-9]{32}$"))) {
            "Cloudflare Account ID does not look valid."
        }
        require(cleanToken.length >= 20) {
            "Cloudflare API token is missing or too short."
        }

        val adminToken = randomSecret()
        val firstInvite = randomSecret()

        try {
            progress("Cloudflare authorization", "RUNNING", "Checking the scoped API token.")
            api(cleanToken, "GET", "/accounts/$cleanAccount/d1/database?per_page=1")
            progress("Cloudflare authorization", "PASS", "Token can access D1 for this account.")

            progress("D1 database", "RUNNING", "Finding or creating $cleanD1.")
            val databaseId = ensureD1(cleanAccount, cleanToken, cleanD1, euResidency)
            progress("D1 database", "PASS", "D1 ready: $cleanD1.")

            progress("D1 schema", "RUNNING", "Creating CloudShare tables and the first invitation.")
            initializeSchema(cleanAccount, cleanToken, databaseId, firstInvite)
            progress("D1 schema", "PASS", "Schema and initial invitation ready.")

            progress("R2 storage", "RUNNING", "Finding or creating $cleanR2.")
            ensureR2(cleanAccount, cleanToken, cleanR2, euResidency)
            progress("R2 storage", "PASS", "R2 bucket ready: $cleanR2.")

            progress("Worker deployment", "RUNNING", "Deploying CloudShare Worker and D1/R2 bindings.")
            deployWorker(cleanAccount, cleanToken, cleanWorker, databaseId, cleanR2, adminToken)
            progress("Worker deployment", "PASS", "Worker uploaded with DB/BACKUPS bindings.")

            progress("Workers.dev route", "RUNNING", "Enabling Worker HTTPS endpoint.")
            enableWorkerSubdomain(cleanAccount, cleanToken, cleanWorker)
            val subdomain = ensureAccountSubdomain(cleanAccount, cleanToken)
            val workerUrl = "https://$cleanWorker.$subdomain.workers.dev"
            progress("Workers.dev route", "PASS", workerUrl)

            progress("Worker health", "RUNNING", "Testing /v1/health before saving CloudShare credentials.")
            val health = CloudShareClient(workerUrl).health()
            require(health["ok"]?.toString()?.equals("true", true) == true) {
                "CloudShare health endpoint did not report ok=true."
            }
            progress("Worker health", "PASS", "Worker/D1/R2 health check passed.")

            progress("Owner setup", "RUNNING", "Saving CloudShare owner token in encrypted Android storage.")
            store.apiUrl = workerUrl
            store.saveAdminToken(adminToken)
            progress("Owner setup", "PASS", "Owner token stored encrypted. Cloudflare token was not stored.")

            progress("Register this device", "RUNNING", "Registering this phone with the generated invitation.")
            val credentials = engine.register(firstInvite)
            progress("Register this device", "PASS", "Registered client ${credentials.clientId.take(12)}…")

            progress("Client verification", "RUNNING", "Checking client and intelligence endpoints.")
            val authenticated = CloudShareClient(workerUrl, credentials = credentials)
            authenticated.clientStatus()
            authenticated.intelligenceStatus()
            progress("Client verification", "PASS", "Client authentication and intelligence API passed.")

            progress("Initial sync", "RUNNING", "Uploading the first local evidence batch and downloading collective intelligence.")
            store.backfillEnabled = backfillEnabled
            store.syncIntervalMinutes = syncIntervalMinutes.coerceIn(1, 1440)
            store.enabled = true
            val initialSync = engine.syncIfDue(force = true)
            require(initialSync.error.isBlank()) { "Initial CloudShare sync failed: ${initialSync.error}" }
            progress(
                "Initial sync",
                "PASS",
                "uploaded=${initialSync.uploaded}, duplicates=${initialSync.duplicates}, backfill=${initialSync.backfilled}, downloaded=${initialSync.downloaded}"
            )

            progress("CloudShare", "PASS", "CloudShare enabled. Local trading remains independent.")

            CloudShareProvisioningResult(
                success = true,
                workerUrl = workerUrl,
                d1DatabaseId = databaseId,
                r2BucketName = cleanR2,
                workerName = cleanWorker,
                firstInviteCode = firstInvite,
                steps = steps
            )
        } catch (error: Exception) {
            progress("Provisioning", "FAIL", error.message ?: error.javaClass.simpleName)
            CloudShareProvisioningResult(
                success = false,
                steps = steps,
                error = error.message ?: error.javaClass.simpleName
            )
        }
    }

    /** Verify the one-time user API token before asking for any infrastructure details. */
    suspend fun verifyProvisioningToken(apiToken: String): CloudShareProvisioningStep = withContext(Dispatchers.IO) {
        val token = apiToken.trim()
        require(token.length >= 20) { "Paste the Cloudflare API token first." }
        val response = api(token, "GET", "/user/tokens/verify")
        val result = response.optJSONObject("result") ?: JSONObject()
        val status = result.optString("status")
        require(status.equals("active", ignoreCase = true)) {
            "Cloudflare token is not active (status=${status.ifBlank { "unknown" }})."
        }
        CloudShareProvisioningStep(
            "Cloudflare API token",
            "PASS",
            "Token is active. It remains only in this setup screen and is never saved by Crypto TradeStation."
        )
    }

    /**
     * Validate the Account ID and each permission CloudShare provisioning needs.
     * This is deliberately performed before the user presses Create CloudShare so
     * missing permissions are reported as a guided setup problem, not a deployment error.
     */
    suspend fun verifyProvisioningAccess(
        accountId: String,
        apiToken: String
    ): List<CloudShareProvisioningStep> = withContext(Dispatchers.IO) {
        val account = accountId.trim()
        val token = apiToken.trim()
        require(account.matches(Regex("^[a-fA-F0-9]{32}$"))) {
            "Cloudflare Account ID must be the 32-character ID copied from the Cloudflare dashboard."
        }
        require(token.length >= 20) { "Cloudflare API token is missing." }

        val out = mutableListOf<CloudShareProvisioningStep>()
        suspend fun check(name: String, detail: String, path: String) {
            try {
                api(token, "GET", path)
                out += CloudShareProvisioningStep(name, "PASS", detail)
            } catch (error: Exception) {
                out += CloudShareProvisioningStep(
                    name,
                    "FAIL",
                    (error.message ?: error.javaClass.simpleName).take(500)
                )
            }
        }

        check(
            "D1 Write access",
            "The token can access D1 for this account.",
            "/accounts/$account/d1/database?per_page=1"
        )
        check(
            "R2 Storage Write access",
            "The token can access R2 for this account.",
            "/accounts/$account/r2/buckets?per_page=1"
        )
        check(
            "Workers Scripts Write access",
            "The token can access Workers scripts for this account.",
            "/accounts/$account/workers/scripts"
        )
        out
    }

    suspend fun verifyExisting(workerUrl: String): List<CloudShareProvisioningStep> = withContext(Dispatchers.IO) {
        val rows = mutableListOf<CloudShareProvisioningStep>()
        val url = workerUrl.trim().trimEnd('/')
        try {
            val health = CloudShareClient(url).health()
            rows += CloudShareProvisioningStep("Worker health", "PASS", health.toString())
        } catch (error: Exception) {
            rows += CloudShareProvisioningStep("Worker health", "FAIL", error.message ?: error.javaClass.simpleName)
            return@withContext rows
        }

        val credentials = store.credentials()
        if (credentials == null) {
            rows += CloudShareProvisioningStep("Client registration", "WARN", "This device has no CloudShare credentials.")
            return@withContext rows
        }

        try {
            val client = CloudShareClient(url, credentials = credentials)
            rows += CloudShareProvisioningStep("Client status", "PASS", client.clientStatus().toString())
            rows += CloudShareProvisioningStep("Intelligence status", "PASS", client.intelligenceStatus().toString())
        } catch (error: Exception) {
            rows += CloudShareProvisioningStep("Authenticated API", "FAIL", error.message ?: error.javaClass.simpleName)
        }
        rows
    }

    private suspend fun ensureD1(accountId: String, token: String, name: String, eu: Boolean): String {
        val encoded = URLEncoder.encode(name, "UTF-8")
        val list = api(token, "GET", "/accounts/$accountId/d1/database?name=$encoded&per_page=100")
        val array = list.optJSONArray("result") ?: JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("name") == name) {
                return item.optString("uuid").ifBlank { item.optString("id") }
            }
        }

        val body = JSONObject().put("name", name)
        if (eu) body.put("jurisdiction", "eu")
        val created = api(token, "POST", "/accounts/$accountId/d1/database", body)
            .getJSONObject("result")
        return created.optString("uuid").ifBlank { created.getString("id") }
    }

    private suspend fun initializeSchema(accountId: String, token: String, databaseId: String, inviteCode: String) {
        val schema = appContext.assets.open("cloudshare_setup/schema.sql").bufferedReader().use { it.readText() }
        val statements = schema.split(';').map { it.trim() }.filter { it.isNotBlank() }
        for (statement in statements) d1Query(accountId, token, databaseId, statement, emptyList())

        val inviteHash = CloudShareProtocol.sha256(inviteCode)
        val now = CloudShareProtocol.nowIso()
        val expires = java.time.Instant.now().plusSeconds(7L * 24L * 3600L).toString()
        d1Query(
            accountId, token, databaseId,
            """INSERT OR REPLACE INTO invites
               (id, code_hash, label, max_uses, uses, expires_at, revoked, created_at)
               VALUES (?, ?, ?, 1, 0, ?, 0, ?)""".trimIndent(),
            listOf(UUID.randomUUID().toString(), inviteHash, "Initial Android setup", expires, now)
        )
    }

    private suspend fun d1Query(
        accountId: String,
        token: String,
        databaseId: String,
        sql: String,
        params: List<Any?>
    ): JSONObject {
        val payload = JSONObject().put("sql", sql).put("params", JSONArray(params))
        return api(token, "POST", "/accounts/$accountId/d1/database/$databaseId/query", payload)
    }

    private suspend fun ensureR2(accountId: String, token: String, bucket: String, eu: Boolean) {
        val encoded = URLEncoder.encode(bucket, "UTF-8")
        val existing = runCatching { api(token, "GET", "/accounts/$accountId/r2/buckets/$encoded") }.getOrNull()
        if (existing?.optBoolean("success", false) == true) return

        val headers = if (eu) mapOf("cf-r2-jurisdiction" to "eu") else emptyMap()
        val body = JSONObject().put("name", bucket).put("storageClass", "Standard")
        api(token, "POST", "/accounts/$accountId/r2/buckets", body, headers)
    }

    private suspend fun deployWorker(
        accountId: String,
        token: String,
        workerName: String,
        databaseId: String,
        bucket: String,
        adminToken: String
    ) {
        val script = appContext.assets.open("cloudshare_setup/cloudshare-worker.js").bufferedReader().use { it.readText() }
        val metadata = JSONObject()
            .put("main_module", "worker.js")
            .put("compatibility_date", "2026-08-21")
            .put("bindings", JSONArray()
                .put(JSONObject().put("type", "d1").put("name", "DB").put("id", databaseId))
                .put(JSONObject().put("type", "r2_bucket").put("name", "BACKUPS").put("bucket_name", bucket))
                .put(JSONObject().put("type", "secret_text").put("name", "ADMIN_TOKEN").put("text", adminToken))
            )

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("metadata", null, metadata.toString().toRequestBody("application/json".toMediaType()))
            .addFormDataPart(
                "worker.js", "worker.js",
                script.toRequestBody("application/javascript+module".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$API_BASE/accounts/$accountId/workers/scripts/$workerName")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .put(multipart)
            .build()
        executeCloudflare(request)
    }

    private suspend fun enableWorkerSubdomain(accountId: String, token: String, workerName: String) {
        api(
            token, "POST",
            "/accounts/$accountId/workers/scripts/$workerName/subdomain",
            JSONObject().put("enabled", true).put("previews_enabled", false)
        )
    }

    private suspend fun ensureAccountSubdomain(accountId: String, token: String): String {
        val existing = runCatching {
            api(token, "GET", "/accounts/$accountId/workers/subdomain")
                .optJSONObject("result")?.optString("subdomain").orEmpty()
        }.getOrDefault("")
        if (existing.isNotBlank()) return existing

        val suggested = safeResourceName("cts-${accountId.take(6)}-${randomSecret().take(6)}", "cts-cloudshare").take(40)
        val created = api(
            token, "PUT",
            "/accounts/$accountId/workers/subdomain",
            JSONObject().put("subdomain", suggested)
        )
        return created.getJSONObject("result").getString("subdomain")
    }

    private suspend fun api(
        token: String,
        method: String,
        path: String,
        body: JSONObject? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(API_BASE + path)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
        extraHeaders.forEach { (key, value) -> builder.header(key, value) }

        val requestBody = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(requestBody ?: "{}".toRequestBody("application/json".toMediaType()))
            "PUT" -> builder.put(requestBody ?: "{}".toRequestBody("application/json".toMediaType()))
            else -> error("Unsupported Cloudflare method $method")
        }
        executeCloudflare(builder.build())
    }

    private fun executeCloudflare(request: Request): JSONObject {
        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val parsed = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("raw", raw.take(1200)) }
            if (!response.isSuccessful || !parsed.optBoolean("success", response.isSuccessful)) {
                val errors = parsed.optJSONArray("errors")?.toString().orEmpty()
                val detail = errors.ifBlank { parsed.optString("raw").ifBlank { response.message } }
                error("Cloudflare HTTP ${response.code}: ${detail.take(900)}")
            }
            return parsed
        }
    }

    companion object {
        private const val API_BASE = "https://api.cloudflare.com/client/v4"

        fun safeResourceName(raw: String, fallback: String): String {
            val clean = raw.lowercase()
                .replace(Regex("[^a-z0-9-]+"), "-")
                .replace(Regex("-+"), "-")
                .trim('-')
            return clean.ifBlank { fallback }.take(63).trimEnd('-')
        }

        private fun randomSecret(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
