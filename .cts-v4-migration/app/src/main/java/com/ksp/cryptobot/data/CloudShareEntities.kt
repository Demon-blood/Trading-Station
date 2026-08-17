package com.ksp.cryptobot.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cloudshare_outbox",
    indices = [Index(value = ["state", "nextAttemptAtEpochMs", "createdAtEpochMs"], name = "idx_cloudshare_outbox_state_retry")]
)
data class CloudShareOutboxEntity(
    @PrimaryKey val eventId: String,
    val sourceTable: String,
    val eventTimestamp: String,
    val schemaVersion: Int,
    val payloadJson: String,
    val payloadSha256: String,
    val state: String = "PENDING",
    val attempts: Int = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val nextAttemptAtEpochMs: Long = 0,
    val uploadedAtEpochMs: Long = 0,
    val lastError: String = ""
)

@Entity(tableName = "cloudshare_state")
data class CloudShareStateEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "cloudshare_intelligence_events",
    indices = [
        Index(value = ["sourceTable", "receivedAt"], name = "idx_cloudshare_intelligence_source_received"),
        Index(value = ["contributorId", "receivedAt"], name = "idx_cloudshare_intelligence_contributor")
    ]
)
data class CloudShareIntelligenceEntity(
    @PrimaryKey val eventId: String,
    val aggregateKey: String,
    val contributorId: String,
    val sourceTable: String,
    val eventTimestamp: String,
    val receivedAt: String,
    val payloadJson: String
)

@Entity(
    tableName = "cloudshare_sync_audit",
    indices = [Index(value = ["timestampEpochMs"], name = "idx_cloudshare_sync_audit_time")]
)
data class CloudShareSyncAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val operation: String,
    val status: String,
    val detail: String
)

@Entity(
    tableName = "cloudshare_collective_index",
    indices = [
        Index(value = ["isOutcome", "symbol", "strategy", "regime", "timeframe"], name = "idx_cloudshare_collective_exact"),
        Index(value = ["isOutcome", "strategy", "regime"], name = "idx_cloudshare_collective_strategy_regime"),
        Index(value = ["isOutcome", "symbol", "regime"], name = "idx_cloudshare_collective_symbol_regime"),
        Index(value = ["contributorId", "sourceTable", "aggregateKey"], name = "idx_cloudshare_collective_contributor_aggregate")
    ]
)
data class CloudShareCollectiveIndexEntity(
    @PrimaryKey val eventId: String,
    val contributorId: String,
    val sourceTable: String,
    val aggregateKey: String,
    val symbol: String,
    val strategy: String,
    val regime: String,
    val timeframe: String,
    val eventType: String,
    val isOutcome: Boolean,
    val sampleCount: Int,
    val wins: Int,
    val losses: Int,
    val edgeSum: Double,
    val eventTimestamp: String
)
