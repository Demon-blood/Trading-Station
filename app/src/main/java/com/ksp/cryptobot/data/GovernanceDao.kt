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
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAiAttribution(row: AiValueAttributionEntity)

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

    @Query("SELECT * FROM ai_value_attribution WHERE fingerprint=:fingerprint LIMIT 1")
    suspend fun aiAttributionByFingerprint(fingerprint: String): AiValueAttributionEntity?

    @Query("SELECT * FROM ai_value_attribution WHERE status='OPEN' AND symbol=:symbol ORDER BY createdAtEpochMs ASC")
    suspend fun openAiAttributionForSymbol(symbol: String): List<AiValueAttributionEntity>

    @Query("SELECT COUNT(*) FROM ai_value_attribution WHERE status='OPEN'")
    suspend fun openAiAttributionCount(): Int

    @Query("SELECT * FROM ai_value_attribution WHERE status='RESOLVED' ORDER BY resolvedAtEpochMs DESC LIMIT :limit")
    suspend fun resolvedAiAttributions(limit: Int = 5000): List<AiValueAttributionEntity>

    @Query("SELECT * FROM ai_value_attribution ORDER BY createdAtEpochMs DESC LIMIT :limit")
    suspend fun recentAiAttributions(limit: Int = 200): List<AiValueAttributionEntity>

    @Query("SELECT * FROM production_intelligence_state ORDER BY updatedAtEpochMs ASC")
    suspend fun allProductionState(): List<ProductionIntelligenceStateEntity>

    @Query("DELETE FROM governance_events")
    suspend fun clearGovernanceEvents()

    @Query("DELETE FROM execution_quality_events")
    suspend fun clearExecutionQuality()

    @Query("DELETE FROM advanced_execution_events")
    suspend fun clearAdvancedExecution()

    @Query("DELETE FROM ai_value_attribution")
    suspend fun clearAiAttribution()

    @Query("DELETE FROM production_intelligence_state")
    suspend fun clearProductionState()

    @Query("DELETE FROM governance_events WHERE timestampEpochMs < :beforeEpochMs")
    suspend fun pruneGovernanceEvents(beforeEpochMs: Long): Int

    @Query("DELETE FROM execution_quality_events WHERE timestampEpochMs < :beforeEpochMs")
    suspend fun pruneExecutionQuality(beforeEpochMs: Long): Int

    @Query("DELETE FROM advanced_execution_events WHERE timestampEpochMs < :beforeEpochMs")
    suspend fun pruneAdvancedExecution(beforeEpochMs: Long): Int

    @Query("DELETE FROM ai_value_attribution WHERE status='RESOLVED' AND resolvedAtEpochMs < :beforeEpochMs")
    suspend fun pruneAiAttribution(beforeEpochMs: Long): Int

}
