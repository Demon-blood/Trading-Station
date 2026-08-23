package com.ksp.cryptobot.release

import android.content.Context
import com.ksp.cryptobot.data.AppDatabase
import java.io.File

class V4MaintenanceManager(context: Context) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val governance = db.governanceDao()
    private val research = db.researchDao()
    private val cloud = db.cloudShareDao()

    data class Result(
        val governancePruned: Int,
        val executionQualityPruned: Int,
        val advancedExecutionPruned: Int,
        val researchPruned: Int,
        val uploadedOutboxPruned: Int,
        val syncAuditPruned: Int,
        val databaseBytesBefore: Long,
        val databaseBytesAfter: Long,
        val detail: String
    )

    suspend fun compact(retentionDays: Int = 365): Result {
        val days = retentionDays.coerceIn(30, 3650)
        val before = System.currentTimeMillis() - days * 86_400_000L
        val dbFile = appContext.getDatabasePath("ksp_crypto_bot.db")
        val bytesBefore = databaseFootprint(dbFile)
        val gov = governance.pruneGovernanceEvents(before)
        val quality = governance.pruneExecutionQuality(before)
        val advanced = governance.pruneAdvancedExecution(before)
        val researchRows = research.pruneEvents(before)
        val uploaded = cloud.pruneUploadedOutbox(before)
        val audit = cloud.pruneSyncAudit(before)
        val sql = db.openHelper.writableDatabase
        runCatching { sql.query("PRAGMA wal_checkpoint(TRUNCATE)").close() }
        runCatching { sql.execSQL("VACUUM") }
        val bytesAfter = databaseFootprint(dbFile)
        return Result(gov, quality, advanced, researchRows, uploaded, audit, bytesBefore, bytesAfter,
            "retention=${days}d; pruned governance=$gov quality=$quality advanced=$advanced research=$researchRows uploadedOutbox=$uploaded audit=$audit; db=${bytesBefore}→${bytesAfter} bytes")
    }

    fun storageAudit(): String {
        val dbFile = appContext.getDatabasePath("ksp_crypto_bot.db")
        val dbBytes = databaseFootprint(dbFile)
        val filesBytes = directorySize(appContext.filesDir)
        val externalBytes = appContext.getExternalFilesDir(null)?.let(::directorySize) ?: 0L
        return "databaseBytes=$dbBytes, internalFilesBytes=$filesBytes, externalAppFilesBytes=$externalBytes"
    }

    private fun databaseFootprint(db: File): Long = listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).sumOf { if (it.exists()) it.length() else 0L }
    private fun directorySize(root: File): Long = if (!root.exists()) 0L else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
