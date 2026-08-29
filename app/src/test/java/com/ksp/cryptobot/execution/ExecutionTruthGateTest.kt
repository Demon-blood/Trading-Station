package com.ksp.cryptobot.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExecutionTruthGateTest {
    @Test
    fun legitimateEmptySnapshotRemainsAuthoritative() {
        val rows = ExecutionTruthGate.requireAuthoritative(
            "open orders",
            Result.success(emptyList<String>())
        )
        assertEquals(0, rows.size)
    }

    @Test
    fun apiFailureCannotBecomeEmptyAuthoritativeSnapshot() {
        assertThrows(ExchangeTruthUnavailableException::class.java) {
            ExecutionTruthGate.requireAuthoritative<List<String>>(
                "open orders",
                Result.failure(IllegalStateException("HTTP 503"))
            )
        }
    }
}
