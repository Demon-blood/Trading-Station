package com.ksp.cryptobot.governance

import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.data.GovernanceEventEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SafeModeControllerTest {
    private val controller = SafeModeController()
    private val normalAnomaly = AnomalyAssessment(true, "INFO", "normal")

    @Test
    fun derivedHighSeverityGovernanceEventsDoNotSelfLatchSafeMode() {
        val now = 1_800_000_000_000L
        val derived = (1..12).flatMap { index ->
            listOf(
                GovernanceEventEntity(timestampEpochMs = now - index * 1_000L, eventType = "production_ai_evaluation", severity = "HIGH", blocked = true, reason = "derived"),
                GovernanceEventEntity(timestampEpochMs = now - index * 1_000L, eventType = "safe_mode_event", severity = "HIGH", blocked = true, reason = "derived")
            )
        }
        val result = controller.evaluate(BotSettings(mode = BotMode.LIVE_AUTO), derived, 0.0, normalAnomaly, now)
        assertEquals("NORMAL", result.level)
        assertFalse(result.blockLiveEntries)
    }

    @Test
    fun repeatedCausativeErrorsStillEscalateWhenAutoSafeModeEnabled() {
        val now = 1_800_000_000_000L
        val errors = (1..6).map { index ->
            GovernanceEventEntity(timestampEpochMs = now - index * 1_000L, eventType = "order_error", severity = "HIGH", blocked = true, reason = "submit failed")
        }
        val result = controller.evaluate(BotSettings(mode = BotMode.LIVE_AUTO, enableAutoSafeMode = true), errors, 0.0, normalAnomaly, now)
        assertEquals("PAPER_ONLY", result.level)
        assertTrue(result.blockLiveEntries)
    }

    @Test
    fun oldCausativeErrorsAgeOutInsteadOfLatchingForever() {
        val now = 1_800_000_000_000L
        val errors = (1..10).map { index ->
            GovernanceEventEntity(timestampEpochMs = now - 2L * 60L * 60L * 1_000L - index, eventType = "order_error", severity = "HIGH", blocked = true, reason = "old")
        }
        val result = controller.evaluate(BotSettings(mode = BotMode.LIVE_AUTO, enableAutoSafeMode = true), errors, 0.0, normalAnomaly, now)
        assertEquals("NORMAL", result.level)
    }
}
