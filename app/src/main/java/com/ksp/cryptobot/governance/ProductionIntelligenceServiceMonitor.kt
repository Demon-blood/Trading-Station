package com.ksp.cryptobot.governance

import com.ksp.cryptobot.data.GovernanceDao
import com.ksp.cryptobot.data.GovernanceEventEntity
import com.ksp.cryptobot.data.ProductionIntelligenceStateEntity

class ProductionIntelligenceServiceMonitor(private val dao: GovernanceDao) {
    suspend fun onServiceStart() {
        val now = System.currentTimeMillis()
        val previous = dao.stateValue(KEY_HEARTBEAT)?.toLongOrNull() ?: 0L
        if (previous > 0L && now - previous > 180_000L) {
            dao.insertEvent(GovernanceEventEntity(
                eventType = "crash_recovery_event", severity = "WARN",
                reason = "Previous service heartbeat was stale by ${(now - previous) / 1000L}s; Android foreground service resumed and local state will be revalidated."
            ))
        }
        heartbeat()
    }

    suspend fun heartbeat() {
        dao.putState(ProductionIntelligenceStateEntity(KEY_HEARTBEAT, System.currentTimeMillis().toString()))
    }

    suspend fun recordLoopError(message: String) {
        dao.insertEvent(GovernanceEventEntity(
            eventType = "watchdog_error", severity = "HIGH", blocked = false,
            reason = message.take(3000)
        ))
        heartbeat()
    }

    suspend fun watchdogStatus(): String {
        val last = dao.stateValue(KEY_HEARTBEAT)?.toLongOrNull() ?: 0L
        if (last <= 0L) return "watchdog warm-up"
        val age = System.currentTimeMillis() - last
        return if (age > 180_000L) "watchdog heartbeat stale ${age / 1000L}s" else "watchdog healthy"
    }

    companion object { private const val KEY_HEARTBEAT = "service_heartbeat_epoch_ms" }
}
