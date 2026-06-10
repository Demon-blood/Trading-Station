package com.ksp.cryptobot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Insert suspend fun insertTrade(trade: TradeEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreTrade(trade: TradeEntity)
    @Insert suspend fun insertSignal(signal: SignalEntity)
    @Insert suspend fun insertAiDecision(decision: AiDecisionEntity)
    @Insert suspend fun insertTaxLot(lot: TaxLotEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertPosition(position: PositionEntity)
    @Insert suspend fun insertTaxReportRow(row: TaxReportEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun restoreTaxReportRow(row: TaxReportEntity)
    @Insert suspend fun insertLearningFeatureSnapshot(snapshot: LearningFeatureSnapshotEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertLearnedSymbolProfile(profile: LearnedSymbolProfileEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertLearnedStrategyProfile(profile: LearnedStrategyProfileEntity)
    @Insert suspend fun insertSelfLearningAudit(row: SelfLearningAuditEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertLearnedHoldProfile(profile: LearnedHoldProfileEntity)

    @Query("SELECT * FROM trades ORDER BY timestampEpochMs DESC LIMIT 100")
    fun recentTrades(): Flow<List<TradeEntity>>

    @Query("SELECT * FROM trades ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recentTradesSnapshot(limit: Int = 100): List<TradeEntity>

    @Query("SELECT * FROM trades WHERE timestampEpochMs BETWEEN :startMs AND :endMs ORDER BY timestampEpochMs DESC")
    suspend fun tradesBetween(startMs: Long, endMs: Long): List<TradeEntity>

    @Query("SELECT * FROM trades WHERE symbol = :symbol ORDER BY timestampEpochMs DESC LIMIT 1")
    suspend fun lastTradeForSymbol(symbol: String): TradeEntity?

    @Query("SELECT COUNT(*) FROM trades WHERE timestampEpochMs BETWEEN :startMs AND :endMs")
    suspend fun tradeCountBetween(startMs: Long, endMs: Long): Int

    @Query("SELECT * FROM signals ORDER BY timestampEpochMs DESC LIMIT 100")
    fun recentSignals(): Flow<List<SignalEntity>>

    @Query("SELECT * FROM ai_decisions ORDER BY timestampEpochMs DESC LIMIT 100")
    fun recentAiDecisions(): Flow<List<AiDecisionEntity>>

    @Query("SELECT COALESCE(SUM(CAST(realizedGainEur AS REAL)), 0) FROM tax_lots WHERE closedAtEpochMs BETWEEN :yearStartMs AND :yearEndMs")
    suspend fun realizedGainForYear(yearStartMs: Long, yearEndMs: Long): Double

    @Query("SELECT * FROM positions WHERE status = 'OPEN' ORDER BY updatedAtEpochMs DESC")
    suspend fun openPositionsSnapshot(): List<PositionEntity>

    @Query("SELECT * FROM positions WHERE symbol = :symbol LIMIT 1")
    suspend fun positionForSymbol(symbol: String): PositionEntity?

    @Query("UPDATE positions SET status = :status, updatedAtEpochMs = :updatedAt WHERE symbol = :symbol")
    suspend fun updatePositionStatus(symbol: String, status: String, updatedAt: Long)

    @Query("SELECT * FROM tax_report_rows ORDER BY timestampEpochMs DESC")
    suspend fun taxReportRowsSnapshot(): List<TaxReportEntity>

    @Query("SELECT * FROM trades ORDER BY timestampEpochMs DESC")
    suspend fun allTradesSnapshot(): List<TradeEntity>

    @Query("DELETE FROM trades")
    suspend fun clearTradesForRestore()

    @Query("DELETE FROM positions")
    suspend fun clearPositionsForRestore()

    @Query("DELETE FROM tax_report_rows")
    suspend fun clearTaxReportsForRestore()

    @Query("SELECT * FROM learned_symbol_profiles WHERE symbol = :symbol LIMIT 1")
    suspend fun learnedSymbolProfile(symbol: String): LearnedSymbolProfileEntity?

    @Query("SELECT * FROM learned_symbol_profiles ORDER BY updatedAtEpochMs DESC")
    suspend fun learnedSymbolProfilesSnapshot(): List<LearnedSymbolProfileEntity>

    @Query("SELECT * FROM learned_strategy_profiles ORDER BY updatedAtEpochMs DESC")
    suspend fun learnedStrategyProfilesSnapshot(): List<LearnedStrategyProfileEntity>

    @Query("SELECT * FROM learned_hold_profiles WHERE symbol = :symbol LIMIT 1")
    suspend fun learnedHoldProfile(symbol: String): LearnedHoldProfileEntity?

    @Query("SELECT * FROM learned_hold_profiles ORDER BY updatedAtEpochMs DESC")
    suspend fun learnedHoldProfilesSnapshot(): List<LearnedHoldProfileEntity>

    @Query("SELECT * FROM learning_feature_snapshots ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun learningFeatureSnapshots(limit: Int = 200): List<LearningFeatureSnapshotEntity>

    @Query("SELECT * FROM self_learning_audit ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun selfLearningAudit(limit: Int = 100): List<SelfLearningAuditEntity>
}
