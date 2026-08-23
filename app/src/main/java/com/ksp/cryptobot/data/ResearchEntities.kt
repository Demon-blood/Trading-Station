package com.ksp.cryptobot.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "research_events",
    indices = [
        Index(value = ["eventType", "timestampEpochMs"]),
        Index(value = ["symbol", "timestampEpochMs"]),
        Index(value = ["strategy", "regime", "timestampEpochMs"])
    ]
)
data class ResearchEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMs: Long = System.currentTimeMillis(),
    val eventType: String,
    val symbol: String = "",
    val strategy: String = "",
    val regime: String = "",
    val mode: String = "",
    val variant: String = "",
    val adjustment: Int = 0,
    val confidence: Double = 0.0,
    val score: Double = 0.0,
    val sampleCount: Int = 0,
    val trainWindow: String = "",
    val testWindow: String = "",
    val provider: String = "",
    val status: String = "INFO",
    val reason: String = "",
    val payloadJson: String = "{}"
)

@Entity(tableName = "research_strategy_profiles")
data class ResearchStrategyProfileEntity(
    @PrimaryKey val strategyKey: String,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val sampleSize: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val totalPnlEur: Double = 0.0,
    val winRatePercent: Double = 0.0,
    val profitFactor: Double = 0.0,
    val walkForwardScore: Double = 0.0,
    val monteCarloScore: Double = 0.0,
    val mutationScore: Double = 0.0,
    val lifecycleState: String = "OBSERVE",
    val reason: String = ""
)

@Entity(tableName = "research_state")
data class ResearchStateEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
