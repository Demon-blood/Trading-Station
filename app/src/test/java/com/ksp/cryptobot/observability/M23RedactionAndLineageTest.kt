package com.ksp.cryptobot.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

class M23RedactionAndLineageTest {
    @Before
    fun reset() {
        M23DecisionLineageRuntime.clearForTests()
    }

    @Test
    fun diagnosticsTextRedactsSecretAssignments() {
        val input = "api_key=ABC123 secret:XYZ token=qwerty pin=9999 webhook=https://secret"
        val clean = M23Redaction.sanitizeText(input)
        assertFalse(clean.contains("ABC123"))
        assertFalse(clean.contains("XYZ"))
        assertFalse(clean.contains("qwerty"))
        assertFalse(clean.contains("9999"))
        assertFalse(clean.contains("https://secret"))
        assertTrue(clean.contains("<redacted>"))
    }

    @Test
    fun sanitizedIdentityIsFingerprintOnly() {
        val raw = "engine-private-identity"
        val fingerprint = M23Redaction.fingerprint(raw)
        assertNotEquals(raw, fingerprint)
        assertEquals(16, fingerprint.length)
    }

    @Test
    fun candidateOrderAndFillShareCorrelationId() {
        val candidate = M23DecisionLineageRuntime.recordCandidate(
            symbol = "BTCEUR",
            strategy = "AUTO",
            mode = "LIVE_AUTO",
            action = "BUY",
            confidencePercent = 81,
            marketPrice = BigDecimal("50000")
        )
        M23DecisionLineageRuntime.recordOrderSubmission(
            symbol = "BTCEUR",
            strategy = "AUTO",
            mode = "LIVE_AUTO",
            action = "BUY",
            orderType = "LIMIT",
            clientOrderId = "client-1"
        )
        M23DecisionLineageRuntime.recordOrderResult(
            symbol = "BTCEUR",
            clientOrderId = "client-1",
            exchangeOrderId = "order-1",
            side = "BUY",
            fillConfirmed = true,
            realizedPnlQuote = BigDecimal.ZERO
        )

        val rows = M23DecisionLineageRuntime.recent(20)
        assertTrue(rows.any { it.stage == "CANDIDATE_DECISION" && it.correlationId == candidate })
        assertTrue(rows.any { it.stage == "ORDER_SUBMISSION" && it.correlationId == candidate })
        assertTrue(rows.any { it.stage == "FILL" && it.correlationId == candidate })
    }

    @Test
    fun m5AndM20EconomicsRemainDistinct() {
        M23DecisionLineageRuntime.recordAdvancedExecution(
            "entry_economics", "BTCEUR", "AUTO", "LIVE_AUTO",
            BigDecimal("20"), BigDecimal("15"), BigDecimal("0.012"), "LIMIT", "positive_net_ev", false, "m5"
        )
        M23DecisionLineageRuntime.recordAdvancedExecution(
            "net_profit_optimizer", "BTCEUR", "AUTO", "LIVE_AUTO",
            BigDecimal("20"), BigDecimal("15"), BigDecimal("0.008"), "LIMIT", "positive_adjusted_net_ev", false, "m20"
        )
        val economics = M23DecisionLineageRuntime.economics().single()
        assertEquals("0.012", economics.m5ExpectedNetEvRate)
        assertEquals("0.008", economics.m20AdjustedExpectedNetEvRate)
    }
}
