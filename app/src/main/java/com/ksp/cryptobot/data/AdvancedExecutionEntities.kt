package com.ksp.cryptobot.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "advanced_execution_events",
    indices = [
        Index(value = ["eventType", "timestampEpochMs"], name = "idx_advanced_execution_type_time"),
        Index(value = ["symbol", "timestampEpochMs"], name = "idx_advanced_execution_symbol_time")
    ]
)
data class AdvancedExecutionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val eventType: String,
    val symbol: String = "",
    val strategy: String = "AUTO",
    val mode: String = "",
    val side: String = "",
    val severity: String = "INFO",
    val requestedQuote: Double = 0.0,
    val finalQuote: Double = 0.0,
    val multiplier: Double = 1.0,
    val recommendedOrderType: String = "",
    val reasonCategory: String = "other",
    val requestedSizeBand: String = "unknown",
    val exitMethod: String = "",
    val qualityTier: String = "unknown",
    val blocked: Boolean = false,
    val reason: String,
    val payloadJson: String = "{}"
)
