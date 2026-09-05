package com.ksp.cryptobot.observability

import android.content.Context
import android.os.Build
import com.ksp.cryptobot.exchange.KrakenPrivateExecutionRegistry
import com.ksp.cryptobot.settings.AppSettingsStore
import com.ksp.cryptobot.status.BotStatusStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class M23DiagnosticExportResult(
    val filePath: String,
    val archiveSha256: String,
    val manifestEntries: Int,
    val sizeBytes: Long
)

object M23DiagnosticBundleExporter {
    fun export(context: Context): M23DiagnosticExportResult {
        val appContext = context.applicationContext
        val generatedAt = System.currentTimeMillis()
        val health = M23HealthSnapshotBuilder.build(appContext)
        val settings = AppSettingsStore(appContext).load()

        val files = linkedMapOf<String, ByteArray>()
        files["health.json"] = (health.toJson().toString(2) + "\n").toByteArray(Charsets.UTF_8)
        files["decision-lineage.json"] = lineageJson().toByteArray(Charsets.UTF_8)
        files["remote-audit.json"] = remoteAuditJson().toByteArray(Charsets.UTF_8)
        files["execution-reports.json"] = executionReportsJson().toByteArray(Charsets.UTF_8)
        files["status.log"] = statusLog(appContext).toByteArray(Charsets.UTF_8)
        files["metadata.json"] = metadataJson(appContext, generatedAt).toByteArray(Charsets.UTF_8)
        files["settings-sanitized.json"] = JSONObject()
            .put("mode", settings.mode.name)
            .put("exchangeProvider", settings.exchangeProvider.name)
            .put("strategyMode", settings.strategyMode.name)
            .put("symbolsCsv", M23Redaction.sanitizeText(settings.symbolsCsv))
            .put("maxDailyLossEur", settings.maxDailyLossEur.toPlainString())
            .put("maxOpenPositions", settings.maxSimultaneousLivePositions)
            .put("remoteCommandCenterEnabled", settings.remoteCommandCenterEnabled)
            .put("remoteCommandRequirePin", settings.remoteCommandRequirePin)
            .put("note", "Secrets and credential material are intentionally excluded from M23 diagnostics.")
            .toString(2)
            .plus("\n")
            .toByteArray(Charsets.UTF_8)

        val manifest = JSONObject()
            .put("schema", "CTS_M23_DIAGNOSTICS_V1")
            .put("generatedAtEpochMs", generatedAt)
            .put("files", JSONArray().apply {
                files.forEach { (name, bytes) ->
                    put(
                        JSONObject()
                            .put("name", name)
                            .put("sizeBytes", bytes.size)
                            .put("sha256", sha256(bytes))
                    )
                }
            })
            .put("privacy", JSONObject()
                .put("rawKrakenApiKey", false)
                .put("rawKrakenSecret", false)
                .put("rawOpenAiApiKey", false)
                .put("rawTelegramToken", false)
                .put("rawDiscordSecret", false)
                .put("rawRemoteCommandPin", false)
                .put("rawSigningMaterial", false)
                .put("rawAndroidKeystoreMaterial", false)
            )
        files["manifest.json"] = (manifest.toString(2) + "\n").toByteArray(Charsets.UTF_8)

        val outputDir = File(appContext.getExternalFilesDir(null), "diagnostics")
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            error("Unable to create M23 diagnostics directory.")
        }
        val output = File(outputDir, "cts_m23_diagnostics_$generatedAt.zip")
        ZipOutputStream(FileOutputStream(output)).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }

        val archiveHash = sha256(output.readBytes())
        return M23DiagnosticExportResult(
            filePath = output.absolutePath,
            archiveSha256 = archiveHash,
            manifestEntries = files.size,
            sizeBytes = output.length()
        )
    }

    private fun lineageJson(): String = JSONArray().apply {
        M23DecisionLineageRuntime.recent(200).forEach { row ->
            put(JSONObject()
                .put("timestampEpochMs", row.timestampEpochMs)
                .put("correlationId", row.correlationId)
                .put("stage", row.stage)
                .put("symbol", row.symbol)
                .put("strategy", row.strategy)
                .put("mode", row.mode)
                .put("action", row.action)
                .put("confidencePercent", row.confidencePercent ?: JSONObject.NULL)
                .put("orderType", row.orderType)
                .put("clientOrderId", row.clientOrderId)
                .put("exchangeOrderId", row.exchangeOrderId)
                .put("value", M23Redaction.sanitizeText(row.value))
                .put("blocked", row.blocked)
                .put("reason", M23Redaction.sanitizeText(row.reason))
            )
        }
    }.toString(2) + "\n"

    private fun remoteAuditJson(): String = JSONArray().apply {
        M23RemoteOperationsRuntime.recentAudit(200).forEach { row ->
            put(JSONObject()
                .put("timestampEpochMs", row.timestampEpochMs)
                .put("command", row.command)
                .put("sourceId", row.sourceId)
                .put("accepted", row.accepted)
                .put("reason", M23Redaction.sanitizeText(row.reason))
                .put("result", M23Redaction.sanitizeText(row.result))
            )
        }
    }.toString(2) + "\n"

    private fun executionReportsJson(): String = JSONArray().apply {
        KrakenPrivateExecutionRegistry.recentExecutionReports(200).forEach { row ->
            put(JSONObject()
                .put("orderId", row.orderId)
                .put("clientOrderId", row.clientOrderId)
                .put("executionId", row.executionId)
                .put("symbol", row.symbol)
                .put("side", row.side.name)
                .put("execType", row.execType)
                .put("orderStatus", row.orderStatus)
                .put("orderQuantity", row.orderQuantity.toPlainString())
                .put("cumulativeQuantity", row.cumulativeQuantity.toPlainString())
                .put("lastQuantity", row.lastQuantity.toPlainString())
                .put("averagePrice", row.averagePrice.toPlainString())
                .put("lastPrice", row.lastPrice.toPlainString())
                .put("feeQuantity", row.feeQuantity.toPlainString())
                .put("sequence", row.sequence)
                .put("observedAtEpochMs", row.observedAtEpochMs)
            )
        }
    }.toString(2) + "\n"

    private fun statusLog(context: Context): String = BotStatusStore(context)
        .recentLines(80)
        .joinToString("\n") { M23Redaction.sanitizeText(it) }
        .plus("\n")

    private fun metadataJson(context: Context, generatedAt: Long): String {
        val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return JSONObject()
            .put("generatedAtEpochMs", generatedAt)
            .put("package", context.packageName)
            .put("versionName", packageInfo?.versionName ?: "UNKNOWN")
            .put("androidSdk", Build.VERSION.SDK_INT)
            .put("deviceClass", "ANDROID")
            .put("databaseIntegritySummary", "UNKNOWN")
            .put("note", "No Android ID, serial number, account identifier, IP address, credential, or signing material is collected.")
            .toString(2) + "\n"
    }

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
