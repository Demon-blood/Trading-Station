package com.ksp.cryptobot.cloudshare

import com.ksp.cryptobot.data.CloudShareDao
import com.ksp.cryptobot.data.CloudShareStateEntity

class CloudShareBackfillEngine(
    private val dao: CloudShareDao,
    private val collector: CloudShareEvidenceCollector
) {
    data class BackfillStep(val queued: Int, val stage: String, val complete: Boolean)

    suspend fun runStep(maxRows: Int = 500): BackfillStep {
        var remaining = maxRows.coerceIn(1, 5000)
        var totalQueued = 0
        var lastStage = "complete"
        for (stage in STAGES) {
            if (remaining <= 0) break
            if (dao.stateValue(doneKey(stage)) == "1") continue
            lastStage = stage
            val cursor = dao.stateValue(cursorKey(stage))?.toLongOrNull() ?: 0L
            val limit = remaining.coerceAtMost(250)
            when (stage) {
                "trades" -> {
                    val rows = dao.tradeBackfillAfter(cursor, limit)
                    for (row in rows) totalQueued += collector.queueTrade(row)
                    advance(stage, rows.lastOrNull()?.id ?: cursor, rows.size, limit)
                    remaining -= rows.size
                }
                "signals" -> {
                    val rows = dao.signalBackfillAfter(cursor, limit)
                    for (row in rows) totalQueued += collector.queueSignal(row)
                    advance(stage, rows.lastOrNull()?.id ?: cursor, rows.size, limit)
                    remaining -= rows.size
                }
                "ai_decisions" -> {
                    val rows = dao.aiDecisionBackfillAfter(cursor, limit)
                    for (row in rows) totalQueued += collector.queueAiDecision(row)
                    advance(stage, rows.lastOrNull()?.id ?: cursor, rows.size, limit)
                    remaining -= rows.size
                }
                "learning_feature_snapshots" -> {
                    val rows = dao.learningSnapshotBackfillAfter(cursor, limit)
                    for (row in rows) totalQueued += collector.queueLearningSnapshot(row)
                    advance(stage, rows.lastOrNull()?.id ?: cursor, rows.size, limit)
                    remaining -= rows.size
                }
            }
        }
        val complete = STAGES.all { dao.stateValue(doneKey(it)) == "1" }
        return BackfillStep(totalQueued, if (complete) "complete" else lastStage, complete)
    }

    suspend fun reset() {
        for (stage in STAGES) {
            dao.putState(CloudShareStateEntity(cursorKey(stage), "0"))
            dao.putState(CloudShareStateEntity(doneKey(stage), "0"))
        }
    }

    suspend fun status(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        for (stage in STAGES) {
            result[stage] = if (dao.stateValue(doneKey(stage)) == "1") {
                "complete"
            } else {
                "cursor=${dao.stateValue(cursorKey(stage)).orEmpty().ifBlank { "0" }}"
            }
        }
        return result
    }

    private suspend fun advance(stage: String, cursor: Long, rows: Int, limit: Int) {
        dao.putState(CloudShareStateEntity(cursorKey(stage), cursor.toString()))
        if (rows < limit) dao.putState(CloudShareStateEntity(doneKey(stage), "1"))
    }

    private fun cursorKey(stage: String) = "backfill:$stage:cursor"
    private fun doneKey(stage: String) = "backfill:$stage:done"

    companion object {
        private val STAGES = listOf("trades", "signals", "ai_decisions", "learning_feature_snapshots")
    }
}
