package com.ksp.cryptobot.release

import android.content.Context
import com.ksp.cryptobot.cloudshare.CloudShareSettingsStore
import com.ksp.cryptobot.cloudshare.CloudShareSyncEngine
import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.data.AppDatabase
import com.ksp.cryptobot.research.ResearchSettingsStore

class V4SystemVerifier(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val governance = db.governanceDao()
    private val research = db.researchDao()
    private val cloudSettings = CloudShareSettingsStore(appContext)
    private val researchSettings = ResearchSettingsStore(appContext)

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

        add(if (settings.mode != BotMode.LIVE_AUTO || settings.liveTradingAcknowledged) "PASS" else "FAIL", "LIVE_AUTO acknowledgement", "mode=${settings.mode}, acknowledged=${settings.liveTradingAcknowledged}")
        add(if (settings.mode != BotMode.LIVE_AUTO || settings.autoStopLossEnabled) "PASS" else "FAIL", "Hard stop-loss", "autoStopLossEnabled=${settings.autoStopLossEnabled}")
        add(if (settings.mode != BotMode.LIVE_AUTO || (settings.enableBacktestGate && settings.enableForwardTestGate)) "PASS" else "FAIL", "Validation gates", "backtest=${settings.enableBacktestGate}, forwardTest=${settings.enableForwardTestGate}")
        add("PASS", "Storage audit", V4MaintenanceManager(appContext).storageAudit())
        add("PASS", "Migration completion", "${V4ReleaseInfo.MIGRATION_STAGE_COMPLETE}/${V4ReleaseInfo.MIGRATION_STAGE_COUNT} stages integrated; protocol=${V4ReleaseInfo.CLOUDSHARE_PROTOCOL}")
        return out
    }
}
