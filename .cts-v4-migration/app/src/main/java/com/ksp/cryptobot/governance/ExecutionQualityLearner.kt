package com.ksp.cryptobot.governance

import com.ksp.cryptobot.data.ExecutionQualityEntity

class ExecutionQualityLearner {
    fun assess(rows: List<ExecutionQualityEntity>): ExecutionQualityAssessment {
        if (rows.size < 5) return ExecutionQualityAssessment(rows.size, 0.0, 0.0, 0, "execution-quality warm-up")
        val avg = rows.map { it.slippagePct }.average()
        val worst = rows.maxOfOrNull { it.slippagePct } ?: 0.0
        // Positive slippage is adverse for the normalized metric stored by Android.
        return when {
            avg > 0.25 -> ExecutionQualityAssessment(rows.size, avg, worst, -5, "historical execution slippage poor avg=%.3f%%".format(avg))
            avg < 0.05 -> ExecutionQualityAssessment(rows.size, avg, worst, 2, "historical execution quality acceptable avg=%.3f%%".format(avg))
            else -> ExecutionQualityAssessment(rows.size, avg, worst, 0, "historical execution quality neutral avg=%.3f%%".format(avg))
        }
    }
}
