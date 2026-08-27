package com.ksp.cryptobot.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ai_value_attribution",
    indices = [
        Index(value = ["status", "createdAtEpochMs"], name = "idx_ai_value_status_created"),
        Index(value = ["symbol", "createdAtEpochMs"], name = "idx_ai_value_symbol_created"),
        Index(value = ["modelPath", "resolvedAtEpochMs"], name = "idx_ai_value_model_resolved")
    ]
)
data class AiValueAttributionEntity(
    @PrimaryKey val fingerprint: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val resolvedAtEpochMs: Long = 0L,
    val symbol: String,
    val strategy: String,
    val regime: String,
    val modelPath: String,
    val deterministicAction: String,
    val deterministicNotionalQuote: String,
    val lunaVerdict: String,
    val lunaRiskMultiplier: String,
    val finalVerdict: String,
    val finalRiskMultiplier: String,
    val entryPrice: String,
    val targetPrice: String,
    val stopPrice: String,
    val horizonMinutes: Int,
    val estimatedRoundTripCostRate: String,
    val lunaCostQuote: String,
    val solCostQuote: String,
    val totalAiCostQuote: String,
    val status: String = "OPEN",
    val resolution: String = "",
    val exitPrice: String = "0",
    val deterministicNetPnlQuote: String = "0",
    val lunaNetPnlQuote: String = "0",
    val finalNetPnlQuote: String = "0",
    val lunaValueAddedQuote: String = "0",
    val solIncrementalValueQuote: String = "0",
    val aiValueAddedQuote: String = "0",
    val avoidedLossQuote: String = "0",
    val missedProfitQuote: String = "0",
    val aiGeneratedProfitQuote: String = "0"
)
