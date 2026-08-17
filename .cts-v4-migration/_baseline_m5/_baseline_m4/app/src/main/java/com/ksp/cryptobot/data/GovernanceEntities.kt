package com.ksp.cryptobot.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "governance_events",
    indices = [
        Index(value = ["eventType", "timestampEpochMs"], name = "idx_governance_type_time"),
        Index(value = ["symbol", "timestampEpochMs"], name = "idx_governance_symbol_time"),
        Index(value = ["blocked", "timestampEpochMs"], name = "idx_governance_blocked_time")
    ]
)
data class GovernanceEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val eventType: String,
    val symbol: String = "",
    val strategy: String = "",
    val mode: String = "",
    val severity: String = "INFO",
    val scoreAdjustment: Int = 0,
    val blocked: Boolean = false,
    val sizeMultiplier: Double = 1.0,
    val reason: String,
    val payloadJson: String = "{}"
)

@Entity(
    tableName = "execution_quality_events",
    indices = [
        Index(value = ["symbol", "side", "mode", "timestampEpochMs"], name = "idx_execution_quality_lookup")
    ]
)
data class ExecutionQualityEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val symbol: String,
    val side: String,
    val mode: String,
    val orderType: String,
    val expectedPrice: Double,
    val actualPrice: Double,
    val slippagePct: Double,
    val notionalQuote: Double,
    val clientOrderId: String = "",
    val exchangeOrderId: String = ""
)

@Entity(tableName = "production_intelligence_state")
data class ProductionIntelligenceStateEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
