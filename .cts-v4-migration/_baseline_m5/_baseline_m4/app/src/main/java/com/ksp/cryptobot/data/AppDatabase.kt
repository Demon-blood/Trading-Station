package com.ksp.cryptobot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v4 migration baseline.
 *
 * v3.2.5 used database version 6 with fallbackToDestructiveMigration().
 * v4 removes that destructive fallback and provides explicit migrations so
 * accumulated trades/self-learning data survive application upgrades.
 */
@Database(
    entities = [
        TradeEntity::class,
        SignalEntity::class,
        AiDecisionEntity::class,
        TaxLotEntity::class,
        PositionEntity::class,
        TaxReportEntity::class,
        LearningFeatureSnapshotEntity::class,
        LearnedSymbolProfileEntity::class,
        LearnedStrategyProfileEntity::class,
        SelfLearningAuditEntity::class,
        LearnedHoldProfileEntity::class,
        NewsArticleEntity::class,
        CloudShareOutboxEntity::class,
        CloudShareStateEntity::class,
        CloudShareIntelligenceEntity::class,
        CloudShareSyncAuditEntity::class,
        CloudShareCollectiveIndexEntity::class,
        GovernanceEventEntity::class,
        ExecutionQualityEntity::class,
        ProductionIntelligenceStateEntity::class,
        AdvancedExecutionEventEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao
    abstract fun cloudShareDao(): CloudShareDao
    abstract fun governanceDao(): GovernanceDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS cloudshare_outbox(
                        eventId TEXT NOT NULL PRIMARY KEY,
                        sourceTable TEXT NOT NULL,
                        eventTimestamp TEXT NOT NULL,
                        schemaVersion INTEGER NOT NULL,
                        payloadJson TEXT NOT NULL,
                        payloadSha256 TEXT NOT NULL,
                        state TEXT NOT NULL,
                        attempts INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        nextAttemptAtEpochMs INTEGER NOT NULL,
                        uploadedAtEpochMs INTEGER NOT NULL,
                        lastError TEXT NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_cloudshare_outbox_state_retry ON cloudshare_outbox(state,nextAttemptAtEpochMs,createdAtEpochMs)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS cloudshare_state(
                        `key` TEXT NOT NULL PRIMARY KEY,
                        value TEXT NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )""".trimIndent()
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS cloudshare_intelligence_events(
                        eventId TEXT NOT NULL PRIMARY KEY,
                        aggregateKey TEXT NOT NULL,
                        contributorId TEXT NOT NULL,
                        sourceTable TEXT NOT NULL,
                        eventTimestamp TEXT NOT NULL,
                        receivedAt TEXT NOT NULL,
                        payloadJson TEXT NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_cloudshare_intelligence_source_received ON cloudshare_intelligence_events(sourceTable,receivedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_cloudshare_intelligence_contributor ON cloudshare_intelligence_events(contributorId,receivedAt)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS cloudshare_sync_audit(
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestampEpochMs INTEGER NOT NULL,
                        operation TEXT NOT NULL,
                        status TEXT NOT NULL,
                        detail TEXT NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_cloudshare_sync_audit_time ON cloudshare_sync_audit(timestampEpochMs)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS cloudshare_collective_index(
                        eventId TEXT NOT NULL PRIMARY KEY,
                        contributorId TEXT NOT NULL,
                        sourceTable TEXT NOT NULL,
                        aggregateKey TEXT NOT NULL,
                        symbol TEXT NOT NULL,
                        strategy TEXT NOT NULL,
                        regime TEXT NOT NULL,
                        timeframe TEXT NOT NULL,
                        eventType TEXT NOT NULL,
                        isOutcome INTEGER NOT NULL,
                        sampleCount INTEGER NOT NULL,
                        wins INTEGER NOT NULL,
                        losses INTEGER NOT NULL,
                        edgeSum REAL NOT NULL,
                        eventTimestamp TEXT NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_cloudshare_collective_exact ON cloudshare_collective_index(isOutcome,symbol,strategy,regime,timeframe)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_cloudshare_collective_strategy_regime ON cloudshare_collective_index(isOutcome,strategy,regime)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_cloudshare_collective_symbol_regime ON cloudshare_collective_index(isOutcome,symbol,regime)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_cloudshare_collective_contributor_aggregate ON cloudshare_collective_index(contributorId,sourceTable,aggregateKey)")
            }
        }


        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS governance_events(
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestampEpochMs INTEGER NOT NULL,
                        eventType TEXT NOT NULL,
                        symbol TEXT NOT NULL,
                        strategy TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        scoreAdjustment INTEGER NOT NULL,
                        blocked INTEGER NOT NULL,
                        sizeMultiplier REAL NOT NULL,
                        reason TEXT NOT NULL,
                        payloadJson TEXT NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_governance_type_time ON governance_events(eventType,timestampEpochMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_governance_symbol_time ON governance_events(symbol,timestampEpochMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_governance_blocked_time ON governance_events(blocked,timestampEpochMs)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS execution_quality_events(
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestampEpochMs INTEGER NOT NULL,
                        symbol TEXT NOT NULL,
                        side TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        orderType TEXT NOT NULL,
                        expectedPrice REAL NOT NULL,
                        actualPrice REAL NOT NULL,
                        slippagePct REAL NOT NULL,
                        notionalQuote REAL NOT NULL,
                        clientOrderId TEXT NOT NULL,
                        exchangeOrderId TEXT NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_execution_quality_lookup ON execution_quality_events(symbol,side,mode,timestampEpochMs)")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS production_intelligence_state(
                        `key` TEXT NOT NULL PRIMARY KEY,
                        value TEXT NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )""".trimIndent()
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS advanced_execution_events(
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        timestampEpochMs INTEGER NOT NULL,
                        eventType TEXT NOT NULL,
                        symbol TEXT NOT NULL,
                        strategy TEXT NOT NULL,
                        mode TEXT NOT NULL,
                        side TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        requestedQuote REAL NOT NULL,
                        finalQuote REAL NOT NULL,
                        multiplier REAL NOT NULL,
                        recommendedOrderType TEXT NOT NULL,
                        reasonCategory TEXT NOT NULL,
                        requestedSizeBand TEXT NOT NULL,
                        exitMethod TEXT NOT NULL,
                        qualityTier TEXT NOT NULL,
                        blocked INTEGER NOT NULL,
                        reason TEXT NOT NULL,
                        payloadJson TEXT NOT NULL
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_advanced_execution_type_time ON advanced_execution_events(eventType,timestampEpochMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_advanced_execution_symbol_time ON advanced_execution_events(symbol,timestampEpochMs)")
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "ksp_crypto_bot.db"
            )
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .build()
                .also { INSTANCE = it }
        }
    }
}
