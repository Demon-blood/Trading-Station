package com.ksp.cryptobot.cloudshare

import android.content.Context
import com.ksp.cryptobot.data.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.min

class CloudShareSyncEngine(context: Context) {
    private val appContext = context.applicationContext
    private val database = AppDatabase.get(appContext)
    private val dao = database.cloudShareDao()
    private val settings = CloudShareSettingsStore(appContext)
    private val collector = CloudShareEvidenceCollector(dao)
    private val aggregateCollector = CloudShareAggregateCollector(dao)
    private val governanceAggregateCollector = CloudShareGovernanceAggregateCollector(dao, database.governanceDao())
    private val researchAggregateCollector = CloudShareResearchAggregateCollector(dao, database.researchDao())
    private val backfill = CloudShareBackfillEngine(dao, collector)
    private val bootstrap = CloudShareBootstrapExporter(appContext)
    private val diagnostics = CloudShareDiagnostics(appContext)
    private val mutex = Mutex()
    private val moshi = Moshi.Builder().build()
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(mapType)

    init {
        if (!settings.enabled || !settings.collectiveLearningEnabled) CloudShareCollectiveCache.disable()
    }

    suspend fun health(): Map<String, Any?> = CloudShareClient(settings.apiUrl, credentials = settings.credentials()).health()

    suspend fun register(inviteCode: String): CloudShareCredentials = mutex.withLock {
        require(settings.apiUrl.isNotBlank()) { "CloudShare Worker URL is not configured." }
        val contributorId = settings.contributorId()
        val client = CloudShareClient(settings.apiUrl)
        val credentials = client.register(inviteCode, contributorId)
        settings.saveCredentials(credentials.clientId, credentials.clientToken)
        settings.enabled = true
        dao.putState(CloudShareStateEntity(KEY_DOWNLOAD_CURSOR, ""))
        backfill.reset()
        dao.insertAudit(CloudShareSyncAuditEntity(operation = "register", status = "OK", detail = "Registered Android contributor $contributorId"))
        credentials
    }

    suspend fun syncIfDue(force: Boolean = false): CloudShareSyncResult = mutex.withLock {
        if (!settings.enabled) {
            CloudShareCollectiveCache.disable()
            return@withLock CloudShareSyncResult()
        }
        if (settings.apiUrl.isBlank()) return@withLock CloudShareSyncResult(error = "CloudShare Worker URL is not configured")
        val creds = settings.credentials() ?: return@withLock CloudShareSyncResult(error = "CloudShare client is not registered")
        val now = System.currentTimeMillis()
        val lastSync = dao.stateValue(KEY_LAST_SYNC_MS)?.toLongOrNull() ?: 0L
        val dueMs = settings.syncIntervalMinutes * 60_000L
        if (!force && now - lastSync < dueMs) return@withLock CloudShareSyncResult()

        val client = CloudShareClient(settings.apiUrl, credentials = creds)
        try {
            reindexExistingIntelligenceIfNeeded()
            val recentQueued = collector.collectRecent()
            val fullAggregateSnapshotDone = dao.stateValue(KEY_FULL_AGGREGATE_SNAPSHOT) == "1"
            val aggregatesQueued = if (settings.emitSharedAggregates) {
                val window = if (fullAggregateSnapshotDone) 7 else 3650
                val queued = aggregateCollector.collectRecent(days = window)
                val governanceQueued = governanceAggregateCollector.collectRecent(days = window)
                val researchQueued = researchAggregateCollector.collectRecent(days = window)
                if (!fullAggregateSnapshotDone) dao.putState(CloudShareStateEntity(KEY_FULL_AGGREGATE_SNAPSHOT, "1"))
                queued + governanceQueued + researchQueued
            } else 0
            val backfillStep = if (settings.backfillEnabled) backfill.runStep(settings.backfillRowsPerSync)
                               else CloudShareBackfillEngine.BackfillStep(0, "disabled", false)
            val upload = uploadPending(client, creds)
            val download = downloadCollective(client)
            val collectiveRows = refreshCollectiveCache()
            dao.putState(CloudShareStateEntity(KEY_LAST_SYNC_MS, now.toString()))
            val result = CloudShareSyncResult(
                uploaded = upload.first,
                duplicates = upload.second,
                rejected = upload.third,
                downloaded = download,
                recentQueued = recentQueued,
                aggregatesQueued = aggregatesQueued,
                backfilled = backfillStep.queued,
                collectiveOutcomeRows = collectiveRows
            )
            dao.insertAudit(CloudShareSyncAuditEntity(
                operation = "sync",
                status = "OK",
                detail = "recent=$recentQueued aggregates=$aggregatesQueued backfill=${backfillStep.queued}/${backfillStep.stage} uploaded=${result.uploaded} duplicates=${result.duplicates} rejected=${result.rejected} downloaded=${result.downloaded} collective=$collectiveRows"
            ))
            result
        } catch (error: Exception) {
            dao.insertAudit(CloudShareSyncAuditEntity(operation = "sync", status = "ERROR", detail = (error.message ?: error.javaClass.simpleName).take(1200)))
            CloudShareSyncResult(error = error.message ?: error.javaClass.simpleName)
        }
    }

    suspend fun refreshCollectiveCache(): Int {
        val indexed = dao.collectiveIndexForBootstrap(25_000)
        val rows = indexed.map { row ->
            CollectiveOutcomeRow(
                eventId = row.eventId,
                contributorId = row.contributorId,
                sourceTable = row.sourceTable,
                aggregateKey = row.aggregateKey,
                symbol = row.symbol,
                strategy = row.strategy,
                regime = row.regime,
                timeframe = row.timeframe,
                eventType = row.eventType,
                sampleCount = row.sampleCount,
                wins = row.wins,
                losses = row.losses,
                edgeSum = row.edgeSum,
                eventTimestamp = row.eventTimestamp,
                isOutcome = row.isOutcome
            )
        }
        CloudShareCollectiveCache.install(
            outcomeRows = rows,
            enabled = settings.enabled && settings.collectiveLearningEnabled,
            minSamples = settings.collectiveMinSamples,
            maxAdjustment = settings.collectiveMaxAdjustment,
            weight = settings.collectiveWeight
        )
        return rows.count { it.isOutcome }
    }

    suspend fun resetBackfill() = backfill.reset()
    suspend fun backfillStatus(): Map<String, String> = backfill.status()
    suspend fun diagnostics(): CloudShareDiagnosticsSnapshot {
        refreshCollectiveCache()
        return diagnostics.snapshot()
    }

    suspend fun createAndUploadBootstrap(): Map<String, Any?> = mutex.withLock {
        val creds = settings.credentials() ?: error("CloudShare client is not registered.")
        val archive = bootstrap.createArchive()
        try {
            val response = CloudShareClient(settings.apiUrl, credentials = creds).uploadBootstrap(archive, creds.contributorId)
            dao.insertAudit(CloudShareSyncAuditEntity(operation = "bootstrap", status = "OK", detail = "Uploaded ${archive.name} (${archive.length()} bytes)"))
            response
        } finally {
            archive.delete()
        }
    }

    suspend fun adminPing(): Map<String, Any?> =
        CloudShareClient(settings.apiUrl, credentials = settings.credentials(), adminToken = settings.adminToken()).adminPing()

    suspend fun adminCreateInvite(label: String, maxUses: Int = 1, expiresHours: Int = 168): Map<String, Any?> =
        CloudShareClient(settings.apiUrl, credentials = settings.credentials(), adminToken = settings.adminToken())
            .adminCreateInvite(label, maxUses, expiresHours)

    suspend fun adminInvites(): Map<String, Any?> =
        CloudShareClient(settings.apiUrl, credentials = settings.credentials(), adminToken = settings.adminToken()).adminInvites()

    suspend fun adminClients(): Map<String, Any?> =
        CloudShareClient(settings.apiUrl, credentials = settings.credentials(), adminToken = settings.adminToken()).adminClients()

    suspend fun adminRevokeInvite(inviteId: String): Map<String, Any?> =
        CloudShareClient(settings.apiUrl, credentials = settings.credentials(), adminToken = settings.adminToken()).adminRevokeInvite(inviteId)

    suspend fun adminClientAction(clientId: String, action: String, reason: String = ""): Map<String, Any?> =
        CloudShareClient(settings.apiUrl, credentials = settings.credentials(), adminToken = settings.adminToken())
            .adminClientAction(clientId, action, reason)


    private suspend fun reindexExistingIntelligenceIfNeeded() {
        if (dao.stateValue(KEY_REINDEX_M24_1) == "1") return
        val existing = dao.intelligenceForReindex(25_000)
        if (existing.isNotEmpty()) {
            val indexes = mutableListOf<CloudShareCollectiveIndexEntity>()
            for (row in existing) {
                val payload = runCatching { mapAdapter.fromJson(row.payloadJson).orEmpty() }.getOrDefault(emptyMap())
                if (!row.sourceTable.startsWith("shared_")) continue
                val aggregateKey = row.aggregateKey.ifBlank { CloudShareProtocol.sharedAggregateKey(row.sourceTable, payload) }
                val event = CloudShareDownloadedEvent(
                    eventId = row.eventId, aggregateKey = aggregateKey, contributorId = row.contributorId,
                    sourceTable = row.sourceTable, eventTimestamp = row.eventTimestamp, receivedAt = row.receivedAt, payload = payload
                )
                dao.deleteOlderCollectiveAggregate(row.contributorId, row.sourceTable, aggregateKey, row.eventId)
                indexes += CollectiveIntelligenceIndexer.toIndex(event)
            }
            if (indexes.isNotEmpty()) dao.upsertCollectiveIndex(indexes)
        }
        // Keep the historical marker and add a new marker so previously completed V8
        // installs still rerun exactly once with the corrected observational indexer.
        dao.putState(CloudShareStateEntity(KEY_REINDEX_V8, "1"))
        dao.putState(CloudShareStateEntity(KEY_REINDEX_M24_1, "1"))
    }

    private suspend fun uploadPending(client: CloudShareClient, creds: CloudShareCredentials): Triple<Int, Int, Int> {
        var uploaded = 0
        var duplicates = 0
        var rejected = 0
        repeat(MAX_UPLOAD_BATCHES_PER_SYNC) {
            val rows = dao.pending(limit = CloudShareProtocol.MAX_EVENTS_PER_BATCH)
            if (rows.isEmpty()) return Triple(uploaded, duplicates, rejected)
            val ids = rows.map { it.eventId }
            dao.markUploading(ids)
            try {
                val events = rows.map { row ->
                    val payload = mapAdapter.fromJson(row.payloadJson).orEmpty()
                    CloudShareEvent(
                        eventId = row.eventId,
                        sourceTable = row.sourceTable,
                        eventTimestamp = row.eventTimestamp,
                        schemaVersion = row.schemaVersion,
                        payload = payload,
                        payloadSha256 = row.payloadSha256
                    )
                }
                val result = client.uploadEvents(creds.contributorId, events)
                val acceptedOrDuplicate = (result.acceptedEventIds + result.duplicateEventIds).distinct()
                if (acceptedOrDuplicate.isNotEmpty()) dao.markUploaded(acceptedOrDuplicate)
                for ((eventId, reason) in result.rejected) {
                    if (eventId.isNotBlank()) dao.markRejected(listOf(eventId), reason.ifBlank { "Rejected by CloudShare Worker" })
                }
                val accounted = (acceptedOrDuplicate + result.rejected.map { it.first }).filter { it.isNotBlank() }.toSet()
                val unaccounted = ids.filterNot { it in accounted }
                if (unaccounted.isNotEmpty()) dao.markRetry(unaccounted, System.currentTimeMillis() + 60_000L, "Worker did not account for event in batch response")
                uploaded += result.acceptedEventIds.size
                duplicates += result.duplicateEventIds.size
                rejected += result.rejected.size
            } catch (error: Exception) {
                val attempts = rows.maxOfOrNull { it.attempts + 1 } ?: 1
                val backoffMinutes = min(60, 1 shl min(6, attempts.coerceAtLeast(1) - 1))
                dao.markRetry(ids, System.currentTimeMillis() + backoffMinutes * 60_000L, (error.message ?: "CloudShare upload failed").take(1200))
                throw error
            }
        }
        return Triple(uploaded, duplicates, rejected)
    }

    private suspend fun downloadCollective(client: CloudShareClient): Int {
        var cursor = dao.stateValue(KEY_DOWNLOAD_CURSOR).orEmpty()
        var downloaded = 0
        repeat(MAX_DOWNLOAD_PAGES_PER_SYNC) {
            val page = client.downloadIntelligence(cursor = cursor, limit = 5000)
            if (page.events.isNotEmpty()) {
                val entities = mutableListOf<CloudShareIntelligenceEntity>()
                val indexes = mutableListOf<CloudShareCollectiveIndexEntity>()
                for (event in page.events) {
                    val aggregateKey = event.aggregateKey.ifBlank { CloudShareProtocol.sharedAggregateKey(event.sourceTable, event.payload) }
                    dao.deleteOlderIntelligenceAggregate(event.contributorId, event.sourceTable, aggregateKey, event.eventId)
                    dao.deleteOlderCollectiveAggregate(event.contributorId, event.sourceTable, aggregateKey, event.eventId)
                    val normalized = event.copy(aggregateKey = aggregateKey)
                    entities += CloudShareIntelligenceEntity(
                        eventId = normalized.eventId,
                        aggregateKey = normalized.aggregateKey,
                        contributorId = normalized.contributorId,
                        sourceTable = normalized.sourceTable,
                        eventTimestamp = normalized.eventTimestamp,
                        receivedAt = normalized.receivedAt,
                        payloadJson = mapAdapter.toJson(normalized.payload)
                    )
                    indexes += CollectiveIntelligenceIndexer.toIndex(normalized)
                }
                dao.upsertIntelligence(entities)
                dao.upsertCollectiveIndex(indexes)
                downloaded += page.events.size
            }
            cursor = page.nextCursor
            dao.putState(CloudShareStateEntity(KEY_DOWNLOAD_CURSOR, cursor))
            if (!page.hasMore) return downloaded
        }
        return downloaded
    }

    companion object {
        private const val KEY_LAST_SYNC_MS = "last_sync_ms"
        private const val KEY_DOWNLOAD_CURSOR = "download_cursor"
        private const val KEY_REINDEX_V8 = "collective_index_rebuilt_v8"
        private const val KEY_REINDEX_M24_1 = "collective_index_rebuilt_m24_1"
        private const val KEY_FULL_AGGREGATE_SNAPSHOT = "full_aggregate_snapshot_v8"
        private const val MAX_UPLOAD_BATCHES_PER_SYNC = 4
        private const val MAX_DOWNLOAD_PAGES_PER_SYNC = 4
    }
}
