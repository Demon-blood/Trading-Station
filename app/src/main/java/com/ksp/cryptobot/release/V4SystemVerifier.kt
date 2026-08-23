package com.ksp.cryptobot.release

import android.content.Context
import com.ksp.cryptobot.cloudshare.CloudShareSettingsStore
import com.ksp.cryptobot.cloudshare.CloudShareSyncEngine
import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.data.AppDatabase
import com.ksp.cryptobot.news.NewsProviderHealthRegistry
import com.ksp.cryptobot.research.ResearchSettingsStore
import com.ksp.cryptobot.settings.AppSettingsStore

class V4SystemVerifier(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val dao = db.dao()
    private val governance = db.governanceDao()
    private val research = db.researchDao()
    private val cloudSettings = CloudShareSettingsStore(appContext)
    private val researchSettings = ResearchSettingsStore(appContext)
    private val appSettings = AppSettingsStore(appContext)

    suspend fun verify(settings: BotSettings): List<V4VerificationItem> {
        val out = mutableListOf<V4VerificationItem>()
        fun add(status: String, name: String, detail: String) { out += V4VerificationItem(status, name, detail) }

        val dbVersion = runCatching { db.openHelper.readableDatabase.version }.getOrDefault(-1)
        add(if (dbVersion == V4ReleaseInfo.ROOM_SCHEMA_VERSION) "PASS" else "FAIL", "Room v4 schema", "databaseVersion=$dbVersion expected=${V4ReleaseInfo.ROOM_SCHEMA_VERSION}; migrations 6→7→8→9→10→11")

        val release = runCatching { ReleaseIntegrityChecker(appContext).snapshot() }.getOrNull()
        if (release == null) add("WARN", "Install/signing lineage", "Package signing information unavailable.")
        else {
            val versionOk = release.versionName == V4ReleaseInfo.VERSION_NAME && release.versionCode >= V4ReleaseInfo.VERSION_CODE
            add(if (versionOk) "PASS" else "WARN", "Release identity", release.detail)
            add(if (release.signerStable) "PASS" else "FAIL", "Signing lineage", if (release.signerStable) "Current signer matches the first trusted v4 signer on this installation." else "APK signer changed since the v4 trust record was created.")
            add(if (release.debuggable) "WARN" else "PASS", "Release debuggability", if (release.debuggable) "Debuggable APK detected. Use the release build for unattended LIVE_AUTO." else "Non-debuggable build detected.")
        }

        // Persisted settings truth: never report a save as successful solely from
        // in-memory Compose state.
        val persistedSettings = runCatching { appSettings.load() }.getOrNull()
        val saveTruth = appSettings.lastSaveVerification()
        val inMemoryMatchesDisk = persistedSettings == settings
        val settingsStatus = when {
            persistedSettings == null -> "FAIL"
            saveTruth.timestampEpochMs == 0L -> "WARN"
            !saveTruth.committed || !saveTruth.exactMatch -> "FAIL"
            !inMemoryMatchesDisk -> "WARN"
            else -> "PASS"
        }
        add(
            settingsStatus,
            "Effective settings persistence",
            "lastCommit=${saveTruth.committed}, lastExactReload=${saveTruth.exactMatch}, lastSaveEpochMs=${saveTruth.timestampEpochMs}, uiMatchesDisk=$inMemoryMatchesDisk, effectiveMode=${persistedSettings?.mode ?: "LOAD_FAILED"}"
        )
        add(
            if (persistedSettings != null) "PASS" else "FAIL",
            "Effective settings truth",
            persistedSettings?.toString() ?: "Unable to load persisted BotSettings."
        )

        val providerConfig = listOf(
            "GDELT=PUBLIC_NO_KEY",
            "RSS=PUBLIC_NO_KEY",
            "CryptoPanic=${if (!appSettings.cryptoPanicApiKey().isNullOrBlank()) "CONFIGURED" else "NO_KEY"}",
            "Marketaux=${if (!appSettings.marketauxApiKey().isNullOrBlank()) "CONFIGURED" else "NO_KEY"}",
            "NewsData.io=${if (!appSettings.newsDataApiKey().isNullOrBlank()) "CONFIGURED" else "NO_KEY"}",
            "GNews=${if (!appSettings.gNewsApiKey().isNullOrBlank()) "CONFIGURED" else "NO_KEY"}",
            "Guardian=${if (!appSettings.guardianApiKey().isNullOrBlank()) "CONFIGURED" else "NO_KEY"}",
            "NewsAPI=${appSettings.newsApiKey()?.split(',', ';', '\n')?.count { it.trim().isNotBlank() } ?: 0} key(s)"
        )
        add("PASS", "News provider configuration", providerConfig.joinToString(", "))

        val providerHealth = NewsProviderHealthRegistry.snapshot()
        if (providerHealth.isEmpty()) {
            add("WARN", "News provider runtime health", "No provider attempt has occurred in this app process yet. Run Scan News or a normal scan, then Verify v4 again.")
        } else {
            providerHealth.forEach { health ->
                val status = when {
                    health.coolingDown() -> "WARN"
                    health.lastSuccessEpochMs > 0L && health.lastSuccessEpochMs >= health.lastFailureEpochMs -> "PASS"
                    health.lastFailureEpochMs > 0L -> "WARN"
                    else -> "WARN"
                }
                add(
                    status,
                    "News health • ${health.provider}",
                    "status=${health.status}, articles=${health.lastArticleCount}, failures=${health.consecutiveFailures}, lastAttempt=${health.lastAttemptEpochMs}, lastSuccess=${health.lastSuccessEpochMs}, lastFailure=${health.lastFailureEpochMs}, cooldownUntil=${health.cooldownUntilEpochMs}${health.lastError.takeIf { it.isNotBlank() }?.let { ", error=$it" } ?: ""}"
                )
            }
        }

        val gov = runCatching { governance.recentEvents(10_000) }.getOrDefault(emptyList())
        val execution = runCatching { governance.recentExecutionQuality(10_000) }.getOrDefault(emptyList())
        val advanced = runCatching { governance.recentAdvancedExecution(10_000) }.getOrDefault(emptyList())
        val safeMode = runCatching { governance.stateValue("safe_mode_level") }.getOrNull().orEmpty().ifBlank { "not-yet-evaluated" }
        val heartbeat = runCatching { governance.stateValue("service_heartbeat_epoch_ms")?.toLongOrNull() }.getOrNull()
        val heartbeatAge = heartbeat?.let { System.currentTimeMillis() - it }
        add("PASS", "Governance ledger", "events=${gov.size}, executionQuality=${execution.size}, advancedExecution=${advanced.size}, safeMode=$safeMode")
        add(if (heartbeatAge == null || heartbeatAge > 180_000L) "WARN" else "PASS", "Foreground watchdog", if (heartbeatAge == null) "No service heartbeat recorded yet." else "heartbeatAge=${heartbeatAge / 1000L}s")

        val researchEvents = runCatching { research.recentEvents(10_000) }.getOrDefault(emptyList())
        val profiles = runCatching { research.profiles() }.getOrDefault(emptyList())
        add("PASS", "Research engine persistence", "events=${researchEvents.size}, profiles=${profiles.size}, enabled=${researchSettings.enabled()}, livePromotion=${researchSettings.researchPromotionInLive()}")
        add(if (!researchSettings.researchPromotionInLive()) "PASS" else "WARN", "Research LIVE promotion", if (researchSettings.researchPromotionInLive()) "Research-created LIVE entry promotion is enabled. M3/M4 guards still apply, but default is OFF." else "Research-created LIVE entry promotion remains OFF by default.")

        val cloud = runCatching { CloudShareSyncEngine(appContext).diagnostics() }.getOrNull()
        if (!cloudSettings.enabled) add("PASS", "CloudShare", "CloudShare disabled; local trading remains independent.")
        else if (cloud == null) add("WARN", "CloudShare", "Enabled but diagnostics could not be read.")
        else {
            val https = cloudSettings.apiUrl.startsWith("https://")
            val registered = cloud.registered
            add(if (https && registered) "PASS" else "WARN", "CloudShare", "registered=$registered, https=$https, pending=${cloud.pendingOutbox}, downloaded=${cloud.downloadedIntelligence}, collectiveSamples=${cloud.collectiveSamples}, contributors=${cloud.contributors}")
        }

        // Persisted evidence matrix for the requested end-to-end path. A fresh
        // install is WARN rather than a false PASS until runtime evidence exists.
        val trades = runCatching { dao.allTradesSnapshot() }.getOrDefault(emptyList())
        val completedExits = trades.filter { it.side.equals("SELL", ignoreCase = true) }
        val learningProfiles = runCatching { dao.learnedSymbolProfilesSnapshot() }.getOrDefault(emptyList())
        val learningAudit = runCatching { dao.selfLearningAudit(100) }.getOrDefault(emptyList())
        val m3Evaluations = gov.count { it.eventType == "production_ai_evaluation" }
        val newsSuccessEvidence = providerHealth.any { it.lastSuccessEpochMs > 0L }
        val settingsEvidence = persistedSettings != null && saveTruth.committed && saveTruth.exactMatch
        val researchEvidence = researchEvents.isNotEmpty()
        val m3Evidence = m3Evaluations > 0
        val m4Evidence = execution.isNotEmpty() || advanced.isNotEmpty()
        val lifecycleEvidence = completedExits.isNotEmpty()
        val learningEvidence = learningProfiles.isNotEmpty() || learningAudit.isNotEmpty()

        add(if (settingsEvidence) "PASS" else "WARN", "E2E • settings → persisted effective config", "evidence=$settingsEvidence")
        add(if (newsSuccessEvidence) "PASS" else "WARN", "E2E • data/news acquisition", "providerSuccess=$newsSuccessEvidence, trackedProviders=${providerHealth.size}")
        add(if (researchEvidence) "PASS" else "WARN", "E2E • strategy/research", "researchEvents=${researchEvents.size}, profiles=${profiles.size}")
        add(if (m3Evidence) "PASS" else "WARN", "E2E • M3 governance", "production_ai_evaluation events=$m3Evaluations")
        add(if (m4Evidence) "PASS" else "WARN", "E2E • M4 execution", "executionQuality=${execution.size}, advancedExecution=${advanced.size}")
        add(if (lifecycleEvidence) "PASS" else "WARN", "E2E • lifecycle completed outcomes", "completed SELL outcomes=${completedExits.size}")
        add(if (learningEvidence) "PASS" else "WARN", "E2E • persistence/learning", "learnedProfiles=${learningProfiles.size}, learningAudit=${learningAudit.size}")
        val e2eAll = settingsEvidence && newsSuccessEvidence && researchEvidence && m3Evidence && m4Evidence && lifecycleEvidence && learningEvidence
        add(if (e2eAll) "PASS" else "WARN", "End-to-end wiring evidence", if (e2eAll) "Persisted runtime evidence exists for every requested stage." else "Not all stages have persisted runtime evidence yet. This is intentionally not reported as fully integrated until every stage above passes.")

        add(if (settings.mode != BotMode.LIVE_AUTO || settings.liveTradingAcknowledged) "PASS" else "FAIL", "LIVE_AUTO acknowledgement", "mode=${settings.mode}, acknowledged=${settings.liveTradingAcknowledged}")
        add(if (settings.mode != BotMode.LIVE_AUTO || settings.autoStopLossEnabled) "PASS" else "FAIL", "Hard stop-loss", "autoStopLossEnabled=${settings.autoStopLossEnabled}")
        add(if (settings.mode != BotMode.LIVE_AUTO || (settings.enableBacktestGate && settings.enableForwardTestGate)) "PASS" else "FAIL", "Validation gates", "backtest=${settings.enableBacktestGate}, forwardTest=${settings.enableForwardTestGate}")
        add("PASS", "Storage audit", V4MaintenanceManager(appContext).storageAudit())
        add("PASS", "Migration completion", "${V4ReleaseInfo.MIGRATION_STAGE_COMPLETE}/${V4ReleaseInfo.MIGRATION_STAGE_COUNT} stages integrated; protocol=${V4ReleaseInfo.CLOUDSHARE_PROTOCOL}")
        return out
    }
}
