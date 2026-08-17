package com.ksp.cryptobot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GovernanceDao {
    @Insert suspend fun insertEvent(event: GovernanceEventEntity): Long
    @Insert suspend fun insertExecutionQuality(event: ExecutionQualityEntity): Long
    @Insert suspend fun insertAdvancedExecution(event: AdvancedExecutionEventEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putState(state: ProductionIntelligenceStateEntity)

    @Query("SELECT value FROM production_intelligence_state WHERE `key`=:key LIMIT 1")
    suspend fun stateValue(key: String): String?

    @Query("SELECT * FROM governance_events ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recentEvents(limit: Int = 200): List<GovernanceEventEntity>

    @Query("SELECT * FROM governance_events WHERE symbol=:symbol ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recentEventsForSymbol(symbol: String, limit: Int = 100): List<GovernanceEventEntity>

    @Query("SELECT COUNT(*) FROM governance_events WHERE timestampEpochMs>=:sinceMs AND eventType IN ('watchdog_error','order_error','anomaly_event')")
    suspend fun recentOperationalErrorCount(sinceMs: Long): Int

    @Query("SELECT * FROM execution_quality_events WHERE symbol=:symbol AND side=:side AND mode=:mode ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun executionQuality(symbol: String, side: String, mode: String, limit: Int = 100): List<ExecutionQualityEntity>

    @Query("SELECT * FROM execution_quality_events ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recentExecutionQuality(limit: Int = 250): List<ExecutionQualityEntity>

    @Query("SELECT * FROM governance_events WHERE eventType='why_not_trade' ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun whyNotTrade(limit: Int = 200): List<GovernanceEventEntity>

    @Query("SELECT * FROM advanced_execution_events ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recentAdvancedExecution(limit: Int = 500): List<AdvancedExecutionEventEntity>

    @Query("SELECT * FROM advanced_execution_events WHERE eventType=:eventType ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun advancedExecutionByType(eventType: String, limit: Int = 500): List<AdvancedExecutionEventEntity>
}
