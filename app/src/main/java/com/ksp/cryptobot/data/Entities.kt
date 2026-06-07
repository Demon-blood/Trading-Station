package com.ksp.cryptobot.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "trades")
data class TradeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val side: String,
    val quantity: String,
    val priceEur: String,
    val feeEur: String,
    val paper: Boolean,
    val realizedPnlEur: String = "0.00",
    val aiScore: Int = 0,
    val aiReason: String = "",
    val clientOrderId: String = "",
    val exchangeOrderId: String = "",
    val timestampEpochMs: Long
)

@Entity(tableName = "signals")
data class SignalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val action: String,
    val score: Int,
    val riskPercent: String,
    val reason: String,
    val timestampEpochMs: Long
)

@Entity(tableName = "ai_decisions")
data class AiDecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val finalAction: String,
    val finalScore: Int,
    val confidencePercent: Int,
    val technicalScore: Int,
    val newsScore: Int,
    val memoryScore: Int,
    val allowedToTrade: Boolean,
    val explanation: String,
    val timestampEpochMs: Long
)

@Entity(tableName = "tax_lots")
data class TaxLotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val quantity: String,
    val costBasisEur: String,
    val openedAtEpochMs: Long,
    val closedAtEpochMs: Long? = null,
    val realizedGainEur: String = "0.00"
)


@Entity(tableName = "positions")
data class PositionEntity(
    @PrimaryKey val symbol: String,
    val baseAsset: String,
    val quantity: String,
    val entryPriceEur: String,
    val highestPriceEur: String,
    val stopPriceEur: String,
    val takeProfitPriceEur: String,
    val trailingStopPriceEur: String,
    val openedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val status: String = "OPEN",
    val source: String = "LIFECYCLE"
)

@Entity(tableName = "tax_report_rows")
data class TaxReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampEpochMs: Long,
    val symbol: String,
    val side: String,
    val quantity: String,
    val priceEur: String,
    val feeEur: String,
    val realizedGainEur: String,
    val note: String
)
