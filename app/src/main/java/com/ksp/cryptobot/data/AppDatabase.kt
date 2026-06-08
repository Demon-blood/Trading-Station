package com.ksp.cryptobot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [TradeEntity::class, SignalEntity::class, AiDecisionEntity::class, TaxLotEntity::class, PositionEntity::class, TaxReportEntity::class, LearningFeatureSnapshotEntity::class, LearnedSymbolProfileEntity::class, LearnedStrategyProfileEntity::class, SelfLearningAuditEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "ksp_crypto_bot.db"
            )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}
