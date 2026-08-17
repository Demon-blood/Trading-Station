package com.ksp.cryptobot.data

data class CloudShareTradeDailyProjection(
    val day: String,
    val symbol: String,
    val side: String,
    val mode: String,
    val sampleCount: Int,
    val wins: Int,
    val losses: Int,
    val zeroPnl: Int,
    val pnlSum: Double,
    val returnPctSum: Double,
    val firstTimestampEpochMs: Long,
    val lastTimestampEpochMs: Long
)

data class CloudShareSignalDailyProjection(
    val day: String,
    val symbol: String,
    val action: String,
    val sampleCount: Int,
    val scoreSum: Double,
    val firstTimestampEpochMs: Long,
    val lastTimestampEpochMs: Long
)

data class CloudShareLearningDailyProjection(
    val day: String,
    val strategy: String,
    val symbol: String,
    val mode: String,
    val action: String,
    val sampleCount: Int,
    val scoreSum: Double,
    val tradedCount: Int,
    val firstTimestampEpochMs: Long,
    val lastTimestampEpochMs: Long
)

data class CloudShareSourceInventoryProjection(
    val sourceTable: String,
    val rowCount: Int,
    val firstTimestampEpochMs: Long,
    val lastTimestampEpochMs: Long
)
