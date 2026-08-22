package com.ksp.cryptobot.strategy.provenance

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

class SignalProvenanceStore(context: Context) {
    private val db = Helper(context.applicationContext)

    fun record(signal: SignalProvenance) {
        val values = ContentValues().apply {
            put("strategyId", signal.strategyId)
            put("strategyVersion", signal.strategyVersion)
            put("ruleProfileId", signal.ruleProfileId)
            put("provenanceType", signal.provenanceType.name)
            put("sourceIdsJson", org.json.JSONArray(signal.sourceIds).toString())
            put("symbol", signal.symbol)
            put("timeframe", signal.timeframe)
            put("marketRegime", signal.marketRegime)
            put("signalTimestampEpochMs", signal.signalTimestampEpochMs)
            put("featureSnapshotJson", JSONObject(signal.featureSnapshot).toString())
            put("entryRuleResultsJson", JSONObject(signal.entryRuleResults).toString())
            put("invalidation", signal.invalidation)
            put("targetPlan", signal.targetPlan)
            put("riskBudget", signal.riskBudget)
            put("estimatedFees", signal.estimatedFees)
            put("estimatedSlippage", signal.estimatedSlippage)
            put("expectedNetR", signal.expectedNetR)
            put("createdAtEpochMs", System.currentTimeMillis())
        }
        db.writableDatabase.insert("signal_provenance", null, values)
        prune()
    }

    fun recent(limit: Int = 200): List<String> {
        val out = mutableListOf<String>()
        db.readableDatabase.query(
            "signal_provenance",
            arrayOf("strategyId","strategyVersion","ruleProfileId","provenanceType","symbol","timeframe","marketRegime","signalTimestampEpochMs","invalidation","targetPlan","riskBudget","estimatedFees","estimatedSlippage","expectedNetR"),
            null,null,null,null,"id DESC",limit.coerceIn(1,10000).toString()
        ).use { c ->
            while(c.moveToNext()) out += "strategy=${c.getString(0)}@${c.getString(1)}|profile=${c.getString(2)}|provenance=${c.getString(3)}|symbol=${c.getString(4)}|tf=${c.getString(5)}|regime=${c.getString(6)}|time=${c.getLong(7)}|invalidation=${c.getString(8)}|target=${c.getString(9)}|risk=${c.getString(10)}|fees=${c.getString(11)}|slippage=${c.getString(12)}|netR=${c.getString(13)}"
        }
        return out
    }

    private fun prune() {
        db.writableDatabase.execSQL("DELETE FROM signal_provenance WHERE id NOT IN (SELECT id FROM signal_provenance ORDER BY id DESC LIMIT 50000)")
    }

    private class Helper(context: Context): SQLiteOpenHelper(context,"cts_signal_provenance.db",null,1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE signal_provenance(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    strategyId TEXT NOT NULL,
                    strategyVersion TEXT NOT NULL,
                    ruleProfileId TEXT NOT NULL,
                    provenanceType TEXT NOT NULL,
                    sourceIdsJson TEXT NOT NULL,
                    symbol TEXT NOT NULL,
                    timeframe TEXT NOT NULL,
                    marketRegime TEXT NOT NULL,
                    signalTimestampEpochMs INTEGER NOT NULL,
                    featureSnapshotJson TEXT NOT NULL,
                    entryRuleResultsJson TEXT NOT NULL,
                    invalidation TEXT NOT NULL,
                    targetPlan TEXT NOT NULL,
                    riskBudget TEXT NOT NULL,
                    estimatedFees TEXT NOT NULL,
                    estimatedSlippage TEXT NOT NULL,
                    expectedNetR TEXT NOT NULL,
                    createdAtEpochMs INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX idx_signal_provenance_strategy_time ON signal_provenance(strategyId,signalTimestampEpochMs)")
            db.execSQL("CREATE INDEX idx_signal_provenance_symbol_time ON signal_provenance(symbol,signalTimestampEpochMs)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion:Int, newVersion:Int)=Unit
    }
}
