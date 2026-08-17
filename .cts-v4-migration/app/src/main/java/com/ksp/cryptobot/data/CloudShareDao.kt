package com.ksp.cryptobot.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CloudShareDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(event: CloudShareOutboxEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putState(state: CloudShareStateEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIntelligence(events: List<CloudShareIntelligenceEntity>)

    @Insert
    suspend fun insertAudit(row: CloudShareSyncAuditEntity)

    @Query("SELECT value FROM cloudshare_state WHERE `key`=:key LIMIT 1")
    suspend fun stateValue(key: String): String?

    @Query("SELECT * FROM cloudshare_outbox WHERE state NOT IN ('UPLOADED','REJECTED') AND nextAttemptAtEpochMs<=:now ORDER BY createdAtEpochMs,eventId LIMIT :limit")
    suspend fun pending(now: Long = System.currentTimeMillis(), limit: Int = 250): List<CloudShareOutboxEntity>

    @Query("UPDATE cloudshare_outbox SET state='UPLOADING', attempts=attempts+1, lastError='' WHERE eventId IN (:ids)")
    suspend fun markUploading(ids: List<String>)

    @Query("UPDATE cloudshare_outbox SET state='UPLOADED', uploadedAtEpochMs=:uploadedAt, lastError='', payloadJson='{}' WHERE eventId IN (:ids)")
    suspend fun markUploaded(ids: List<String>, uploadedAt: Long = System.currentTimeMillis())

    @Query("UPDATE cloudshare_outbox SET state='PENDING', nextAttemptAtEpochMs=:retryAt, lastError=:error WHERE eventId IN (:ids)")
    suspend fun markRetry(ids: List<String>, retryAt: Long, error: String)

    @Query("UPDATE cloudshare_outbox SET state='REJECTED', lastError=:error WHERE eventId IN (:ids)")
    suspend fun markRejected(ids: List<String>, error: String)

    @Query("SELECT COUNT(*) FROM cloudshare_outbox WHERE state!='UPLOADED'")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM cloudshare_intelligence_events")
    suspend fun intelligenceCount(): Int

    @Query("SELECT * FROM cloudshare_intelligence_events WHERE sourceTable=:source ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun intelligenceForSource(source: String, limit: Int = 500): List<CloudShareIntelligenceEntity>

    @Query("SELECT * FROM cloudshare_intelligence_events ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun intelligenceForReindex(limit: Int = 25000): List<CloudShareIntelligenceEntity>

    @Query("SELECT * FROM cloudshare_sync_audit ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recentAudit(limit: Int = 100): List<CloudShareSyncAuditEntity>
    @Query("SELECT * FROM trades ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recentTradesForCloudShare(limit: Int = 250): List<TradeEntity>

    @Query("SELECT * FROM signals ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recentSignalsForCloudShare(limit: Int = 250): List<SignalEntity>

    @Query("SELECT * FROM ai_decisions ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recentAiDecisionsForCloudShare(limit: Int = 250): List<AiDecisionEntity>

    @Query("SELECT * FROM news_articles ORDER BY fetchedAtEpochMs DESC LIMIT :limit")
    suspend fun recentNewsForCloudShare(limit: Int = 250): List<NewsArticleEntity>

    @Query("SELECT * FROM learning_feature_snapshots ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recentLearningSnapshotsForCloudShare(limit: Int = 250): List<LearningFeatureSnapshotEntity>

    @Query("SELECT * FROM self_learning_audit ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recentLearningAuditForCloudShare(limit: Int = 250): List<SelfLearningAuditEntity>

    @Query("SELECT * FROM learned_symbol_profiles ORDER BY updatedAtEpochMs DESC LIMIT :limit")
    suspend fun learnedSymbolsForCloudShare(limit: Int = 250): List<LearnedSymbolProfileEntity>

    @Query("SELECT * FROM learned_strategy_profiles ORDER BY updatedAtEpochMs DESC LIMIT :limit")
    suspend fun learnedStrategiesForCloudShare(limit: Int = 250): List<LearnedStrategyProfileEntity>

    @Query("SELECT * FROM learned_hold_profiles ORDER BY updatedAtEpochMs DESC LIMIT :limit")
    suspend fun learnedHoldsForCloudShare(limit: Int = 250): List<LearnedHoldProfileEntity>

    @Query("SELECT * FROM governance_events ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recentGovernanceForCloudShare(limit: Int = 250): List<GovernanceEventEntity>

    @Query("SELECT * FROM execution_quality_events ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun recentExecutionQualityForCloudShare(limit: Int = 250): List<ExecutionQualityEntity>



    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCollectiveIndex(rows: List<CloudShareCollectiveIndexEntity>)

    @Query("DELETE FROM cloudshare_intelligence_events WHERE contributorId=:contributorId AND sourceTable=:sourceTable AND aggregateKey=:aggregateKey AND eventId!=:keepEventId")
    suspend fun deleteOlderIntelligenceAggregate(contributorId: String, sourceTable: String, aggregateKey: String, keepEventId: String)

    @Query("DELETE FROM cloudshare_collective_index WHERE contributorId=:contributorId AND sourceTable=:sourceTable AND aggregateKey=:aggregateKey AND eventId!=:keepEventId")
    suspend fun deleteOlderCollectiveAggregate(contributorId: String, sourceTable: String, aggregateKey: String, keepEventId: String)

    @Query("SELECT * FROM cloudshare_collective_index WHERE isOutcome=1 ORDER BY eventTimestamp DESC LIMIT :limit")
    suspend fun collectiveOutcomeRows(limit: Int = 25000): List<CloudShareCollectiveIndexEntity>

    @Query("SELECT * FROM cloudshare_collective_index ORDER BY eventTimestamp DESC LIMIT :limit")
    suspend fun collectiveIndexForBootstrap(limit: Int = 25000): List<CloudShareCollectiveIndexEntity>

    @Query("SELECT COUNT(*) FROM cloudshare_collective_index WHERE isOutcome=1")
    suspend fun collectiveOutcomeCount(): Int

    @Query("SELECT * FROM trades WHERE id>:afterId ORDER BY id ASC LIMIT :limit")
    suspend fun tradeBackfillAfter(afterId: Long, limit: Int): List<TradeEntity>

    @Query("SELECT * FROM signals WHERE id>:afterId ORDER BY id ASC LIMIT :limit")
    suspend fun signalBackfillAfter(afterId: Long, limit: Int): List<SignalEntity>

    @Query("SELECT * FROM ai_decisions WHERE id>:afterId ORDER BY id ASC LIMIT :limit")
    suspend fun aiDecisionBackfillAfter(afterId: Long, limit: Int): List<AiDecisionEntity>

    @Query("SELECT * FROM learning_feature_snapshots WHERE id>:afterId ORDER BY id ASC LIMIT :limit")
    suspend fun learningSnapshotBackfillAfter(afterId: Long, limit: Int): List<LearningFeatureSnapshotEntity>

    @Query("""
        SELECT strftime('%Y-%m-%d', timestampEpochMs / 1000, 'unixepoch') AS day,
               UPPER(symbol) AS symbol,
               UPPER(side) AS side,
               CASE WHEN paper THEN 'PAPER' ELSE 'LIVE' END AS mode,
               COUNT(*) AS sampleCount,
               SUM(CASE WHEN CAST(realizedPnlEur AS REAL) > 0 THEN 1 ELSE 0 END) AS wins,
               SUM(CASE WHEN CAST(realizedPnlEur AS REAL) < 0 THEN 1 ELSE 0 END) AS losses,
               SUM(CASE WHEN CAST(realizedPnlEur AS REAL) = 0 THEN 1 ELSE 0 END) AS zeroPnl,
               SUM(CAST(realizedPnlEur AS REAL)) AS pnlSum,
               SUM(CASE WHEN ABS(CAST(quantity AS REAL) * CAST(priceEur AS REAL)) > 0.00000001
                        THEN (CAST(realizedPnlEur AS REAL) / ABS(CAST(quantity AS REAL) * CAST(priceEur AS REAL))) * 100.0
                        ELSE 0.0 END) AS returnPctSum,
               MIN(timestampEpochMs) AS firstTimestampEpochMs,
               MAX(timestampEpochMs) AS lastTimestampEpochMs
        FROM trades
        WHERE timestampEpochMs>=:sinceMs
        GROUP BY day, symbol, side, mode
        ORDER BY day ASC, symbol ASC, side ASC
    """)
    suspend fun tradeDailyAggregates(sinceMs: Long): List<CloudShareTradeDailyProjection>

    @Query("""
        SELECT strftime('%Y-%m-%d', timestampEpochMs / 1000, 'unixepoch') AS day,
               UPPER(symbol) AS symbol,
               UPPER(action) AS action,
               COUNT(*) AS sampleCount,
               SUM(score) AS scoreSum,
               MIN(timestampEpochMs) AS firstTimestampEpochMs,
               MAX(timestampEpochMs) AS lastTimestampEpochMs
        FROM signals
        WHERE timestampEpochMs>=:sinceMs
        GROUP BY day, symbol, action
        ORDER BY day ASC, symbol ASC, action ASC
    """)
    suspend fun signalDailyAggregates(sinceMs: Long): List<CloudShareSignalDailyProjection>

    @Query("""
        SELECT strftime('%Y-%m-%d', timestampEpochMs / 1000, 'unixepoch') AS day,
               UPPER(strategyMode) AS strategy,
               UPPER(symbol) AS symbol,
               UPPER(mode) AS mode,
               UPPER(action) AS action,
               COUNT(*) AS sampleCount,
               SUM(finalScore) AS scoreSum,
               SUM(CASE WHEN traded THEN 1 ELSE 0 END) AS tradedCount,
               MIN(timestampEpochMs) AS firstTimestampEpochMs,
               MAX(timestampEpochMs) AS lastTimestampEpochMs
        FROM learning_feature_snapshots
        WHERE timestampEpochMs>=:sinceMs
        GROUP BY day, strategy, symbol, mode, action
        ORDER BY day ASC, symbol ASC, strategy ASC
    """)
    suspend fun learningDailyAggregates(sinceMs: Long): List<CloudShareLearningDailyProjection>

    @Query("""
        SELECT 'trades' AS sourceTable, COUNT(*) AS rowCount,
               COALESCE(MIN(timestampEpochMs),0) AS firstTimestampEpochMs,
               COALESCE(MAX(timestampEpochMs),0) AS lastTimestampEpochMs FROM trades
        UNION ALL
        SELECT 'signals', COUNT(*), COALESCE(MIN(timestampEpochMs),0), COALESCE(MAX(timestampEpochMs),0) FROM signals
        UNION ALL
        SELECT 'ai_decisions', COUNT(*), COALESCE(MIN(timestampEpochMs),0), COALESCE(MAX(timestampEpochMs),0) FROM ai_decisions
        UNION ALL
        SELECT 'learning_feature_snapshots', COUNT(*), COALESCE(MIN(timestampEpochMs),0), COALESCE(MAX(timestampEpochMs),0) FROM learning_feature_snapshots
        UNION ALL
        SELECT 'news_articles', COUNT(*), COALESCE(MIN(fetchedAtEpochMs),0), COALESCE(MAX(fetchedAtEpochMs),0) FROM news_articles
    """)
    suspend fun sourceInventory(): List<CloudShareSourceInventoryProjection>

    @Query("DELETE FROM cloudshare_outbox WHERE state='UPLOADED' AND uploadedAtEpochMs > 0 AND uploadedAtEpochMs < :beforeEpochMs")
    suspend fun pruneUploadedOutbox(beforeEpochMs: Long): Int

    @Query("DELETE FROM cloudshare_sync_audit WHERE timestampEpochMs < :beforeEpochMs")
    suspend fun pruneSyncAudit(beforeEpochMs: Long): Int

}
