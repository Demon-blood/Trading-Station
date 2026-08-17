package com.ksp.cryptobot.cloudshare

import android.content.Context
import com.ksp.cryptobot.data.AppDatabase
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Creates a secret-free Android shared-intelligence bootstrap archive for R2 retention. */
class CloudShareBootstrapExporter(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(appContext).cloudShareDao()
    private val moshi = Moshi.Builder().build()
    private val mapType = Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    private val anyListType = Types.newParameterizedType(List::class.java, mapType)
    private val listAdapter = moshi.adapter<List<Map<String, Any?>>>(anyListType)

    suspend fun createArchive(): File {
        val dir = File(appContext.cacheDir, "cloudshare_bootstrap").apply { mkdirs() }
        val file = File(dir, "cts_android_shared_intelligence_${System.currentTimeMillis()}.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            val collective = dao.collectiveIndexForBootstrap(25_000).map { row ->
                linkedMapOf<String, Any?>(
                    "event_id" to row.eventId, "contributor_id" to row.contributorId,
                    "source_table" to row.sourceTable, "aggregate_key" to row.aggregateKey,
                    "symbol" to row.symbol, "strategy" to row.strategy, "regime" to row.regime,
                    "timeframe" to row.timeframe, "event_type" to row.eventType,
                    "is_outcome" to row.isOutcome, "sample_count" to row.sampleCount,
                    "wins" to row.wins, "losses" to row.losses, "edge_sum" to row.edgeSum,
                    "event_timestamp" to row.eventTimestamp
                )
            }
            val audit = dao.recentAudit(1000).map { row ->
                linkedMapOf<String, Any?>(
                    "timestamp_epoch_ms" to row.timestampEpochMs,
                    "operation" to row.operation, "status" to row.status, "detail" to row.detail
                )
            }
            val inventory = dao.sourceInventory().map { row ->
                linkedMapOf<String, Any?>(
                    "source_table" to row.sourceTable, "row_count" to row.rowCount,
                    "first_timestamp_epoch_ms" to row.firstTimestampEpochMs,
                    "last_timestamp_epoch_ms" to row.lastTimestampEpochMs
                )
            }
            val cache = CloudShareCollectiveCache.snapshot()
            val manifest = listOf(linkedMapOf<String, Any?>(
                "format" to "cts-android-cloudshare-bootstrap-v1",
                "protocol_version" to CloudShareProtocol.PROTOCOL_VERSION,
                "created_at" to CloudShareProtocol.nowIso(),
                "collective_rows" to collective.size,
                "collective_samples" to cache.totalSamples,
                "contributors" to cache.contributors,
                "contains_secrets" to false
            ))
            writeJson(zip, "manifest.json", manifest)
            writeJson(zip, "collective_index.json", collective)
            writeJson(zip, "sync_audit.json", audit)
            writeJson(zip, "source_inventory.json", inventory)
        }
        require(file.length() in 1..50_000_000) { "CloudShare bootstrap archive must be between 1 byte and 50 MB." }
        return file
    }

    private fun writeJson(zip: ZipOutputStream, name: String, rows: List<Map<String, Any?>>) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(listAdapter.toJson(rows).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
