package com.ksp.cryptobot.market

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.Timeframe
import java.math.BigDecimal

data class CandleStoreStats(
    val symbol: String,
    val timeframe: String,
    val rows: Int,
    val oldestEpochMs: Long,
    val newestEpochMs: Long,
    val lastCommittedEpochMs: Long
)

object CandleCommitPolicy {
    /**
     * Kraken Spot REST documents the last OHLC row as current/uncommitted.
     * We therefore never persist the final row returned by a REST fetch as committed.
     */
    fun committedRows(restRows: List<Candle>): List<Candle> =
        if (restRows.size <= 1) emptyList() else restRows.dropLast(1)
}

class PersistentCandleRepository(context: Context) {
    private val db = Helper(context.applicationContext)

    fun upsertKrakenRest(symbol: String, timeframe: Timeframe, rows: List<Candle>): Int {
        val committed = CandleCommitPolicy.committedRows(rows)
        if (committed.isEmpty()) return 0
        val writable = db.writableDatabase
        var count = 0
        writable.beginTransaction()
        try {
            committed.forEach { candle ->
                val values = ContentValues().apply {
                    put("symbol", normalize(symbol))
                    put("timeframe", timeframe.name)
                    put("openTimeEpochMs", candle.openTimeEpochMs)
                    put("open", candle.open.toPlainString())
                    put("high", candle.high.toPlainString())
                    put("low", candle.low.toPlainString())
                    put("close", candle.close.toPlainString())
                    put("volume", candle.volume.toPlainString())
                    put("committed", 1)
                    put("updatedAtEpochMs", System.currentTimeMillis())
                }
                writable.insertWithOnConflict("candles", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                count++
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        prune(normalize(symbol), timeframe)
        return count
    }

    fun load(symbol: String, timeframe: Timeframe, limit: Int): List<Candle> {
        val out = mutableListOf<Candle>()
        db.readableDatabase.query(
            "candles",
            arrayOf("openTimeEpochMs", "open", "high", "low", "close", "volume"),
            "symbol=? AND timeframe=? AND committed=1",
            arrayOf(normalize(symbol), timeframe.name),
            null, null,
            "openTimeEpochMs DESC",
            limit.coerceIn(1, 100_000).toString()
        ).use { c ->
            while (c.moveToNext()) {
                out += Candle(
                    symbol = normalize(symbol),
                    timeframe = timeframe,
                    openTimeEpochMs = c.getLong(0),
                    open = c.getString(1).toBigDecimal(),
                    high = c.getString(2).toBigDecimal(),
                    low = c.getString(3).toBigDecimal(),
                    close = c.getString(4).toBigDecimal(),
                    volume = c.getString(5).toBigDecimal()
                )
            }
        }
        return out.sortedBy { it.openTimeEpochMs }
    }

    fun mergeHistory(symbol: String, timeframe: Timeframe, freshRestRows: List<Candle>, limit: Int): List<Candle> {
        upsertKrakenRest(symbol, timeframe, freshRestRows)
        return load(symbol, timeframe, limit)
    }

    fun stats(symbol: String, timeframe: Timeframe): CandleStoreStats {
        val s = normalize(symbol)
        val sql = """
            SELECT COUNT(*), COALESCE(MIN(openTimeEpochMs),0), COALESCE(MAX(openTimeEpochMs),0)
            FROM candles WHERE symbol=? AND timeframe=? AND committed=1
        """.trimIndent()
        db.readableDatabase.rawQuery(sql, arrayOf(s, timeframe.name)).use { c ->
            if (c.moveToFirst()) {
                return CandleStoreStats(s, timeframe.name, c.getInt(0), c.getLong(1), c.getLong(2), c.getLong(2))
            }
        }
        return CandleStoreStats(s, timeframe.name, 0, 0L, 0L, 0L)
    }

    fun freshnessAgeMs(symbol: String, timeframe: Timeframe, nowMs: Long = System.currentTimeMillis()): Long {
        val newest = stats(symbol, timeframe).newestEpochMs
        return if (newest <= 0L) Long.MAX_VALUE else (nowMs - newest).coerceAtLeast(0L)
    }

    private fun prune(symbol: String, timeframe: Timeframe) {
        val cap = when (timeframe) {
            Timeframe.M1 -> 20_000
            Timeframe.M5 -> 30_000
            Timeframe.M15 -> 40_000
            Timeframe.H1 -> 30_000
            Timeframe.H4 -> 20_000
        }
        db.writableDatabase.execSQL(
            """
            DELETE FROM candles
            WHERE symbol=? AND timeframe=? AND openTimeEpochMs NOT IN (
                SELECT openTimeEpochMs FROM candles
                WHERE symbol=? AND timeframe=? AND committed=1
                ORDER BY openTimeEpochMs DESC LIMIT ?
            )
            """.trimIndent(),
            arrayOf(symbol, timeframe.name, symbol, timeframe.name, cap)
        )
    }

    private fun normalize(symbol: String) = symbol.uppercase().replace("/", "").replace("-", "")

    private class Helper(context: Context) : SQLiteOpenHelper(context, "cts_market_history.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE candles(
                    symbol TEXT NOT NULL,
                    timeframe TEXT NOT NULL,
                    openTimeEpochMs INTEGER NOT NULL,
                    open TEXT NOT NULL,
                    high TEXT NOT NULL,
                    low TEXT NOT NULL,
                    close TEXT NOT NULL,
                    volume TEXT NOT NULL,
                    committed INTEGER NOT NULL,
                    updatedAtEpochMs INTEGER NOT NULL,
                    PRIMARY KEY(symbol,timeframe,openTimeEpochMs)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_candles_symbol_tf_time ON candles(symbol,timeframe,openTimeEpochMs)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}
