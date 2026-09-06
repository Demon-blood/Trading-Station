package com.ksp.cryptobot.cloudshare

import android.content.Context
import com.ksp.cryptobot.data.AppDatabase

data class CloudShareDiagnosticsSnapshot(
    val enabled: Boolean,
    val registered: Boolean,
    val pendingOutbox: Int,
    val downloadedIntelligence: Int,
    val collectiveOutcomeRows: Int,
    val collectiveSamples: Int,
    val contributors: Int,
    val newestCollectiveTimestamp: String,
    val backfill: Map<String, String>,
    val recentAudit: List<String>,
    val indexedEvidenceRows: Int = 0,
    val indexedEvidenceSamples: Int = 0,
    val indexedEvidenceContributors: Int = 0,
    val observationRows: Int = 0,
    val observationSamples: Int = 0,
    val outcomeSamples: Int = 0,
    val dataReady: Boolean = false,
    val dataState: String = "DISABLED",
    val dataRequiredSamples: Int = 0,
    val outcomeState: String = "COLLECTING_OUTCOMES",
    val newestDataTimestamp: String = "",
    val newestOutcomeTimestamp: String = ""
)

class CloudShareDiagnostics(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(appContext).cloudShareDao()
    private val settings = CloudShareSettingsStore(appContext)
    private val backfill = CloudShareBackfillEngine(dao, CloudShareEvidenceCollector(dao))

    suspend fun snapshot(): CloudShareDiagnosticsSnapshot {
        val cache = CloudShareCollectiveCache.snapshot()
        return CloudShareDiagnosticsSnapshot(
            enabled = settings.enabled,
            registered = settings.credentials() != null,
            pendingOutbox = dao.pendingCount(),
            downloadedIntelligence = dao.intelligenceCount(),
            collectiveOutcomeRows = dao.collectiveOutcomeCount(),
            collectiveSamples = cache.totalSamples,
            contributors = cache.contributors,
            newestCollectiveTimestamp = cache.newestEventTimestamp,
            backfill = backfill.status(),
            recentAudit = dao.recentAudit(20).map { "${it.status} ${it.operation}: ${it.detail}" },
            indexedEvidenceRows = cache.indexedRows,
            indexedEvidenceSamples = cache.indexedSamples,
            indexedEvidenceContributors = cache.indexedContributors,
            observationRows = cache.observationRows,
            observationSamples = cache.observationSamples,
            outcomeSamples = cache.outcomeSamples,
            dataReady = cache.dataReady,
            dataState = cache.dataState,
            dataRequiredSamples = cache.dataRequiredSamples,
            outcomeState = cache.outcomeState,
            newestDataTimestamp = cache.newestDataTimestamp,
            newestOutcomeTimestamp = cache.newestOutcomeTimestamp
        )
    }
}
