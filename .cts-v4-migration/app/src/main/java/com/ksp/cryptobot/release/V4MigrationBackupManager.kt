package com.ksp.cryptobot.release

import android.content.Context
import android.net.Uri
import com.ksp.cryptobot.cloudshare.CloudShareSettingsStore
import com.ksp.cryptobot.cloudshare.CloudShareSyncEngine
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.data.*
import com.ksp.cryptobot.research.ResearchSettingsStore
import com.ksp.cryptobot.settings.AppSettingsStore
import com.ksp.cryptobot.status.BotStatusStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Stage-6 companion backup for data introduced by v4 migration stages 1-5.
 * Core trades/settings/credentials are already handled by BotController's existing full backup.
 * This companion intentionally excludes CloudShare client/admin tokens and research API keys.
 */
class V4MigrationBackupManager(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val governance = db.governanceDao()
    private val research = db.researchDao()
    private val cloud = CloudShareSettingsStore(appContext)
    private val researchSettings = ResearchSettingsStore(appContext)
    private val appSettings = AppSettingsStore(appContext)
    private val statusStore = BotStatusStore(appContext)

    suspend fun exportSupplementalBackupToFile(): String {
        val file = File(backupDirectory(), "cts_v4_supplemental_${System.currentTimeMillis()}.json")
        file.writeText(buildBackupJson().toString(2))
        return "V4 SUPPLEMENTAL BACKUP SAVED\nfile=${file.absolutePath}\nsizeBytes=${file.length()}\nsecretsIncluded=false\nroomSchema=${V4ReleaseInfo.ROOM_SCHEMA_VERSION}"
    }

    suspend fun restoreSupplementalBackup(input: String, replaceExisting: Boolean = false): String {
        val json = JSONObject(readInput(input))
        val format = json.optString("format")
        require(format == FORMAT) { "Unsupported v4 supplemental backup format: $format" }
        if (replaceExisting) {
            governance.clearGovernanceEvents()
            governance.clearExecutionQuality()
            governance.clearAdvancedExecution()
            governance.clearProductionState()
            research.clearEvents()
            research.clearProfiles()
            research.clearState()
        }

        var govCount = 0
        json.optJSONArray("governanceEvents")?.forEachObject { o ->
            governance.insertEvent(GovernanceEventEntity(
                timestampEpochMs = o.optLong("timestampEpochMs"), eventType = o.optString("eventType"),
                symbol = o.optString("symbol"), strategy = o.optString("strategy"), mode = o.optString("mode"),
                severity = o.optString("severity", "INFO"), scoreAdjustment = o.optInt("scoreAdjustment"),
                blocked = o.optBoolean("blocked"), sizeMultiplier = o.optDouble("sizeMultiplier", 1.0),
                reason = o.optString("reason"), payloadJson = o.optString("payloadJson", "{}")
            )); govCount++
        }
        var qualityCount = 0
        json.optJSONArray("executionQuality")?.forEachObject { o ->
            governance.insertExecutionQuality(ExecutionQualityEntity(
                timestampEpochMs = o.optLong("timestampEpochMs"), symbol = o.optString("symbol"), side = o.optString("side"),
                mode = o.optString("mode"), orderType = o.optString("orderType"), expectedPrice = o.optDouble("expectedPrice"),
                actualPrice = o.optDouble("actualPrice"), slippagePct = o.optDouble("slippagePct"),
                notionalQuote = o.optDouble("notionalQuote"), clientOrderId = o.optString("clientOrderId"),
                exchangeOrderId = o.optString("exchangeOrderId")
            )); qualityCount++
        }
        var advancedCount = 0
        json.optJSONArray("advancedExecution")?.forEachObject { o ->
            governance.insertAdvancedExecution(AdvancedExecutionEventEntity(
                timestampEpochMs = o.optLong("timestampEpochMs"), eventType = o.optString("eventType"), symbol = o.optString("symbol"),
                strategy = o.optString("strategy", "AUTO"), mode = o.optString("mode"), side = o.optString("side"),
                severity = o.optString("severity", "INFO"), requestedQuote = o.optDouble("requestedQuote"), finalQuote = o.optDouble("finalQuote"),
                multiplier = o.optDouble("multiplier", 1.0), recommendedOrderType = o.optString("recommendedOrderType"),
                reasonCategory = o.optString("reasonCategory", "other"), requestedSizeBand = o.optString("requestedSizeBand", "unknown"),
                exitMethod = o.optString("exitMethod"), qualityTier = o.optString("qualityTier", "unknown"),
                blocked = o.optBoolean("blocked"), reason = o.optString("reason"), payloadJson = o.optString("payloadJson", "{}")
            )); advancedCount++
        }
        json.optJSONArray("productionState")?.forEachObject { o ->
            governance.putState(ProductionIntelligenceStateEntity(o.optString("key"), o.optString("value"), o.optLong("updatedAtEpochMs")))
        }

        var researchCount = 0
        json.optJSONArray("researchEvents")?.forEachObject { o ->
            research.insertEvent(ResearchEventEntity(
                timestampEpochMs = o.optLong("timestampEpochMs"), eventType = o.optString("eventType"), symbol = o.optString("symbol"),
                strategy = o.optString("strategy"), regime = o.optString("regime"), mode = o.optString("mode"), variant = o.optString("variant"),
                adjustment = o.optInt("adjustment"), confidence = o.optDouble("confidence"), score = o.optDouble("score"), sampleCount = o.optInt("sampleCount"),
                trainWindow = o.optString("trainWindow"), testWindow = o.optString("testWindow"), provider = o.optString("provider"),
                status = o.optString("status", "INFO"), reason = o.optString("reason"), payloadJson = o.optString("payloadJson", "{}")
            )); researchCount++
        }
        var profileCount = 0
        json.optJSONArray("researchProfiles")?.forEachObject { o ->
            research.upsertProfile(ResearchStrategyProfileEntity(
                strategyKey = o.optString("strategyKey"), updatedAtEpochMs = o.optLong("updatedAtEpochMs"), sampleSize = o.optInt("sampleSize"),
                wins = o.optInt("wins"), losses = o.optInt("losses"), totalPnlEur = o.optDouble("totalPnlEur"),
                winRatePercent = o.optDouble("winRatePercent"), profitFactor = o.optDouble("profitFactor"),
                walkForwardScore = o.optDouble("walkForwardScore"), monteCarloScore = o.optDouble("monteCarloScore"),
                mutationScore = o.optDouble("mutationScore"), lifecycleState = o.optString("lifecycleState", "OBSERVE"), reason = o.optString("reason")
            )); profileCount++
        }
        json.optJSONArray("researchState")?.forEachObject { o ->
            research.putState(ResearchStateEntity(o.optString("key"), o.optString("value"), o.optLong("updatedAtEpochMs")))
        }

        restorePublicSettings(json)
        return "V4 SUPPLEMENTAL RESTORE COMPLETE\nreplaceExisting=$replaceExisting\ngovernance=$govCount\nexecutionQuality=$qualityCount\nadvancedExecution=$advancedCount\nresearchEvents=$researchCount\nresearchProfiles=$profileCount\nsecretCredentialsRestored=false"
    }

    suspend fun exportDiagnosticsBundleToFile(settings: BotSettings = appSettings.load()): String {
        val file = File(backupDirectory(), "cts_v4_diagnostics_${System.currentTimeMillis()}.zip")
        val verification = V4SystemVerifier(appContext).verify(settings)
        val cloudDiag = runCatching { CloudShareSyncEngine(appContext).diagnostics() }.getOrNull()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putText("release.json", JSONObject().apply {
                put("versionName", V4ReleaseInfo.VERSION_NAME); put("versionCode", V4ReleaseInfo.VERSION_CODE)
                put("roomSchema", V4ReleaseInfo.ROOM_SCHEMA_VERSION); put("cloudShareProtocol", V4ReleaseInfo.CLOUDSHARE_PROTOCOL)
                put("migrationStages", JSONArray(V4ReleaseInfo.stages)); put("generatedAtEpochMs", System.currentTimeMillis())
            }.toString(2))
            zip.putText("system_verification.txt", verification.joinToString("\n") { "${it.status} | ${it.name} | ${it.detail}" })
            zip.putText("settings_redacted.json", JSONObject().apply {
                put("mode", settings.mode.name); put("provider", settings.exchangeProvider.name); put("symbols", settings.symbolsCsv)
                put("maxPositionEur", settings.maxPositionEur.toPlainString()); put("maxDailyLossEur", settings.maxDailyLossEur.toPlainString())
                put("liveTradingAcknowledged", settings.liveTradingAcknowledged); put("cloudShareEnabled", cloud.enabled)
                put("cloudShareUrl", cloud.apiUrl); put("cloudShareRegistered", cloud.credentials() != null)
                put("research", JSONObject(researchSettings.publicSnapshot()))
                put("secrets", "REDACTED")
            }.toString(2))
            zip.putText("cloudshare_diagnostics.txt", cloudDiag?.toString() ?: "CloudShare diagnostics unavailable")
            zip.putText("governance_recent.json", JSONArray(governance.recentEvents(1000).map { it.toJson() }).toString(2))
            zip.putText("execution_quality_recent.json", JSONArray(governance.recentExecutionQuality(1000).map { it.toJson() }).toString(2))
            zip.putText("advanced_execution_recent.json", JSONArray(governance.recentAdvancedExecution(1000).map { it.toJson() }).toString(2))
            zip.putText("research_recent.json", JSONArray(research.recentEvents(1000).map { it.toJson() }).toString(2))
            zip.putText("status_recent.txt", statusStore.recentLines(500).joinToString("\n"))
        }
        return "V4 DIAGNOSTICS BUNDLE SAVED\nfile=${file.absolutePath}\nsizeBytes=${file.length()}\nsecretsIncluded=false"
    }

    private suspend fun buildBackupJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT); put("versionName", V4ReleaseInfo.VERSION_NAME); put("versionCode", V4ReleaseInfo.VERSION_CODE)
        put("roomSchema", V4ReleaseInfo.ROOM_SCHEMA_VERSION); put("generatedAtEpochMs", System.currentTimeMillis()); put("secretsIncluded", false)
        put("cloudSharePublic", JSONObject().apply {
            put("enabled", cloud.enabled); put("apiUrl", cloud.apiUrl); put("syncIntervalMinutes", cloud.syncIntervalMinutes)
            put("collectiveLearningEnabled", cloud.collectiveLearningEnabled); put("collectiveMinSamples", cloud.collectiveMinSamples)
            put("collectiveMaxAdjustment", cloud.collectiveMaxAdjustment); put("collectiveWeight", cloud.collectiveWeight)
            put("emitSharedAggregates", cloud.emitSharedAggregates); put("backfillEnabled", cloud.backfillEnabled); put("backfillRowsPerSync", cloud.backfillRowsPerSync)
            put("registeredOnSourceDevice", cloud.credentials() != null); put("credentials", "NOT_EXPORTED")
        })
        put("researchSettings", JSONObject(researchSettings.publicSnapshot()))
        put("governanceEvents", JSONArray(governance.recentEvents(MAX_ROWS).map { it.toJson() }))
        put("executionQuality", JSONArray(governance.recentExecutionQuality(MAX_ROWS).map { it.toJson() }))
        put("advancedExecution", JSONArray(governance.recentAdvancedExecution(MAX_ROWS).map { it.toJson() }))
        put("productionState", JSONArray(governance.allProductionState().map { JSONObject().put("key", it.key).put("value", it.value).put("updatedAtEpochMs", it.updatedAtEpochMs) }))
        put("researchEvents", JSONArray(research.recentEvents(MAX_ROWS).map { it.toJson() }))
        put("researchProfiles", JSONArray(research.profiles().map { it.toJson() }))
        put("researchState", JSONArray(research.allState().map { JSONObject().put("key", it.key).put("value", it.value).put("updatedAtEpochMs", it.updatedAtEpochMs) }))
    }

    private fun restorePublicSettings(root: JSONObject) {
        root.optJSONObject("cloudSharePublic")?.let { o ->
            cloud.enabled = o.optBoolean("enabled", cloud.enabled); cloud.apiUrl = o.optString("apiUrl", cloud.apiUrl)
            cloud.syncIntervalMinutes = o.optInt("syncIntervalMinutes", cloud.syncIntervalMinutes)
            cloud.collectiveLearningEnabled = o.optBoolean("collectiveLearningEnabled", cloud.collectiveLearningEnabled)
            cloud.collectiveMinSamples = o.optInt("collectiveMinSamples", cloud.collectiveMinSamples)
            cloud.collectiveMaxAdjustment = o.optInt("collectiveMaxAdjustment", cloud.collectiveMaxAdjustment)
            cloud.collectiveWeight = o.optDouble("collectiveWeight", cloud.collectiveWeight)
            cloud.emitSharedAggregates = o.optBoolean("emitSharedAggregates", cloud.emitSharedAggregates)
            cloud.backfillEnabled = o.optBoolean("backfillEnabled", cloud.backfillEnabled)
            cloud.backfillRowsPerSync = o.optInt("backfillRowsPerSync", cloud.backfillRowsPerSync)
        }
        root.optJSONObject("researchSettings")?.let { o ->
            researchSettings.setEnabled(o.optBoolean("enabled", researchSettings.enabled()))
            researchSettings.setAdvancedStrategiesEnabled(o.optBoolean("advancedStrategiesEnabled", researchSettings.advancedStrategiesEnabled()))
            researchSettings.setWalkForwardEnabled(o.optBoolean("walkForwardEnabled", researchSettings.walkForwardEnabled()))
            researchSettings.setMonteCarloEnabled(o.optBoolean("monteCarloEnabled", researchSettings.monteCarloEnabled()))
            researchSettings.setSequenceModelEnabled(o.optBoolean("sequenceModelEnabled", researchSettings.sequenceModelEnabled()))
            researchSettings.setRlSandboxEnabled(o.optBoolean("rlSandboxEnabled", researchSettings.rlSandboxEnabled()))
            researchSettings.setFuturesContextEnabled(o.optBoolean("futuresContextEnabled", researchSettings.futuresContextEnabled()))
            researchSettings.setLabeledWalletEnabled(o.optBoolean("labeledWalletEnabled", researchSettings.labeledWalletEnabled()))
            researchSettings.setResearchPromotionInPaper(o.optBoolean("researchPromotionInPaper", researchSettings.researchPromotionInPaper()))
            researchSettings.setResearchPromotionInLive(o.optBoolean("researchPromotionInLive", false))
            researchSettings.setMaxPositiveAdjustment(o.optInt("maxPositiveAdjustment", researchSettings.maxPositiveAdjustment()))
            researchSettings.setMaxNegativeAdjustment(o.optInt("maxNegativeAdjustment", researchSettings.maxNegativeAdjustment()))
            researchSettings.setMonteCarloSimulations(o.optInt("monteCarloSimulations", researchSettings.monteCarloSimulations()))
            researchSettings.setMinimumOutcomeSamples(o.optInt("minimumOutcomeSamples", researchSettings.minimumOutcomeSamples()))
        }
    }

    private fun backupDirectory(): File = File(appContext.getExternalFilesDir(null), "backups").apply { mkdirs() }
    private fun readInput(input: String): String {
        val trimmed = input.trim()
        if (trimmed.startsWith("{")) return trimmed
        if (trimmed.startsWith("content://")) {
            return appContext.contentResolver.openInputStream(Uri.parse(trimmed))?.bufferedReader()?.use { it.readText() }
                ?: error("Could not read selected v4 supplemental backup URI.")
        }
        val file = File(trimmed)
        require(file.exists() && file.isFile) { "Backup input is neither JSON nor a readable file/content URI." }
        return file.readText()
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name)); write(text.toByteArray(Charsets.UTF_8)); closeEntry()
    }

    private inline fun JSONArray.forEachObject(block: (JSONObject) -> Unit) {
        for (i in 0 until length()) optJSONObject(i)?.let(block)
    }

    private fun GovernanceEventEntity.toJson() = JSONObject().apply {
        put("timestampEpochMs", timestampEpochMs); put("eventType", eventType); put("symbol", symbol); put("strategy", strategy); put("mode", mode)
        put("severity", severity); put("scoreAdjustment", scoreAdjustment); put("blocked", blocked); put("sizeMultiplier", sizeMultiplier); put("reason", reason); put("payloadJson", payloadJson)
    }
    private fun ExecutionQualityEntity.toJson() = JSONObject().apply {
        put("timestampEpochMs", timestampEpochMs); put("symbol", symbol); put("side", side); put("mode", mode); put("orderType", orderType)
        put("expectedPrice", expectedPrice); put("actualPrice", actualPrice); put("slippagePct", slippagePct); put("notionalQuote", notionalQuote)
        put("clientOrderId", clientOrderId); put("exchangeOrderId", exchangeOrderId)
    }
    private fun AdvancedExecutionEventEntity.toJson() = JSONObject().apply {
        put("timestampEpochMs", timestampEpochMs); put("eventType", eventType); put("symbol", symbol); put("strategy", strategy); put("mode", mode); put("side", side)
        put("severity", severity); put("requestedQuote", requestedQuote); put("finalQuote", finalQuote); put("multiplier", multiplier)
        put("recommendedOrderType", recommendedOrderType); put("reasonCategory", reasonCategory); put("requestedSizeBand", requestedSizeBand); put("exitMethod", exitMethod)
        put("qualityTier", qualityTier); put("blocked", blocked); put("reason", reason); put("payloadJson", payloadJson)
    }
    private fun ResearchEventEntity.toJson() = JSONObject().apply {
        put("timestampEpochMs", timestampEpochMs); put("eventType", eventType); put("symbol", symbol); put("strategy", strategy); put("regime", regime); put("mode", mode)
        put("variant", variant); put("adjustment", adjustment); put("confidence", confidence); put("score", score); put("sampleCount", sampleCount)
        put("trainWindow", trainWindow); put("testWindow", testWindow); put("provider", provider); put("status", status); put("reason", reason); put("payloadJson", payloadJson)
    }
    private fun ResearchStrategyProfileEntity.toJson() = JSONObject().apply {
        put("strategyKey", strategyKey); put("updatedAtEpochMs", updatedAtEpochMs); put("sampleSize", sampleSize); put("wins", wins); put("losses", losses)
        put("totalPnlEur", totalPnlEur); put("winRatePercent", winRatePercent); put("profitFactor", profitFactor); put("walkForwardScore", walkForwardScore)
        put("monteCarloScore", monteCarloScore); put("mutationScore", mutationScore); put("lifecycleState", lifecycleState); put("reason", reason)
    }

    companion object {
        const val FORMAT = "cts-v4-supplemental-1"
        private const val MAX_ROWS = 50_000
    }
}
