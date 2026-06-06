package com.ksp.cryptobot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Insert suspend fun insertTrade(trade: TradeEntity)
    @Insert suspend fun insertSignal(signal: SignalEntity)
    @Insert suspend fun insertAiDecision(decision: AiDecisionEntity)
    @Insert suspend fun insertTaxLot(lot: TaxLotEntity)

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
}
