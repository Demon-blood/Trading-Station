package com.ksp.cryptobot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ResearchDao {
    @Insert suspend fun insertEvent(event: ResearchEventEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertProfile(profile: ResearchStrategyProfileEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putState(state: ResearchStateEntity)

    @Query("SELECT * FROM research_events ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recentEvents(limit: Int = 1000): List<ResearchEventEntity>

    @Query("SELECT * FROM research_events WHERE eventType = :eventType ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recentEventsByType(eventType: String, limit: Int = 1000): List<ResearchEventEntity>

    @Query("SELECT * FROM research_events WHERE symbol = :symbol ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recentEventsForSymbol(symbol: String, limit: Int = 500): List<ResearchEventEntity>

    @Query("SELECT * FROM research_strategy_profiles ORDER BY updatedAtEpochMs DESC")
    suspend fun profiles(): List<ResearchStrategyProfileEntity>

    @Query("SELECT * FROM research_strategy_profiles WHERE strategyKey = :strategy LIMIT 1")
    suspend fun profile(strategy: String): ResearchStrategyProfileEntity?

    @Query("SELECT * FROM research_state WHERE `key` = :key LIMIT 1")
    suspend fun state(key: String): ResearchStateEntity?

    @Query("DELETE FROM research_events WHERE timestampEpochMs < :beforeEpochMs")
    suspend fun pruneEvents(beforeEpochMs: Long): Int
}
