package com.ksp.cryptobot.maintenance

import android.content.Context
import com.ksp.cryptobot.data.AppDatabase

data class RetentionResult(
    val ran: Boolean,
    val deletedRowsApprox: Long,
    val checkpoint: String,
    val details: List<String>
)

object DatabaseMaintenance {
    private const val PREFS = "cts_database_maintenance_v1"
    private const val LAST_RUN = "last_run"
    private const val INTERVAL_MS = 24L * 60L * 60L * 1000L

    /**
     * Permanent accounting ledgers are intentionally excluded.
     * High-frequency telemetry is bounded by row count so a mobile DB cannot grow without limit.
     */
    private val caps = linkedMapOf(
        "signals" to 50_000,
        "ai_decisions" to 50_000,
        "news_articles" to 20_000,
        "learning_feature_snapshots" to 50_000,
        "self_learning_audit" to 20_000,
        "governance_events" to 10_000,
        "execution_quality_events" to 20_000,
        "advanced_execution_events" to 20_000,
        "research_events" to 10_000,
        "cloudshare_sync_audit" to 20_000
    )

    fun maybeRun(context: Context, tradingActive: Boolean, force: Boolean = false): RetentionResult {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val last = prefs.getLong(LAST_RUN, 0L)
        if (!force && now - last < INTERVAL_MS) {
            return RetentionResult(false, 0, "SKIPPED", listOf("nextMaintenanceInMs=${INTERVAL_MS - (now-last)}"))
        }
        val db = AppDatabase.get(context).openHelper.writableDatabase
        val details = mutableListOf<String>()
        var deleted = 0L
        caps.forEach { (table, cap) ->
            if (!tableExists(db, table)) return@forEach
            val before = count(db, table)
            val id = idColumn(db, table)
            if (id != null && before > cap) {
                val excess = before - cap
                db.execSQL(
                    """DELETE FROM "$table" WHERE "$id" IN (
                        SELECT "$id" FROM "$table" ORDER BY "$id" ASC LIMIT $excess
                    )""".trimIndent()
                )
                val after = count(db, table)
                deleted += (before - after).coerceAtLeast(0)
                details += "$table before=$before cap=$cap after=$after deleted=${before-after}"
            } else {
                details += "$table rows=$before cap=$cap"
            }
        }
        val checkpoint = runCatching {
            db.query(if (tradingActive) "PRAGMA wal_checkpoint(PASSIVE)" else "PRAGMA wal_checkpoint(TRUNCATE)")
                .use { c ->
                    if (c.moveToFirst()) "busy=${c.getInt(0)},log=${c.getInt(1)},checkpointed=${c.getInt(2)}"
                    else "no result"
                }
        }.getOrElse { "checkpoint failed: ${it.message}" }
        prefs.edit().putLong(LAST_RUN, now).apply()
        return RetentionResult(true, deleted, checkpoint, details)
    }

    fun compactWhenIdle(context: Context): List<String> {
        val db = AppDatabase.get(context).openHelper.writableDatabase
        val lines = mutableListOf<String>()
        lines += runCatching {
            db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { c ->
                if (c.moveToFirst()) "checkpoint busy=${c.getInt(0)} log=${c.getInt(1)} checkpointed=${c.getInt(2)}"
                else "checkpoint no result"
            }
        }.getOrElse { "checkpoint failed: ${it.message}" }
        lines += runCatching { db.execSQL("VACUUM"); "VACUUM complete" }
            .getOrElse { "VACUUM failed: ${it.message}" }
        return lines
    }

    fun storageDiagnostics(context: Context): List<String> {
        val db = AppDatabase.get(context).openHelper.readableDatabase
        val lines = mutableListOf<String>()
        val pageSize = scalar(db, "PRAGMA page_size")
        val pageCount = scalar(db, "PRAGMA page_count")
        val freePages = scalar(db, "PRAGMA freelist_count")
        lines += "pageSize=$pageSize|pageCount=$pageCount|freePages=$freePages|dbBytes=${pageSize*pageCount}|reclaimableBytes=${pageSize*freePages}"
        val tables = mutableListOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { c ->
            while (c.moveToNext()) tables += c.getString(0)
        }
        tables.forEach { table ->
            lines += "table=$table|rows=${count(db, table)}|retention=${retentionClass(table)}"
        }
        return lines
    }

    private fun retentionClass(table: String): String = when (table) {
        "trades","tax_lots","tax_report_rows" -> "PERMANENT_LEDGER"
        "positions","learned_symbol_profiles","learned_strategy_profiles","learned_hold_profiles",
        "production_intelligence_state","research_strategy_profiles","research_state" -> "STATE"
        in caps -> "BOUNDED_TELEMETRY"
        else -> "REVIEW"
    }

    private fun tableExists(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

    private fun idColumn(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): String? {
        db.query("""PRAGMA table_info("$table")""").use { c ->
            val nameIdx = c.getColumnIndex("name")
            val pkIdx = c.getColumnIndex("pk")
            while (c.moveToNext()) {
                val name = if (nameIdx >= 0) c.getString(nameIdx) else ""
                val pk = if (pkIdx >= 0) c.getInt(pkIdx) else 0
                if (name == "id" && pk >= 0) return "id"
            }
        }
        return null
    }

    private fun count(db: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Long =
        db.query("SELECT COUNT(*) FROM \"$table\"").use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun scalar(db: androidx.sqlite.db.SupportSQLiteDatabase, sql: String): Long =
        db.query(sql).use { if (it.moveToFirst()) it.getLong(0) else 0L }
}
