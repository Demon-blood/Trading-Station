package com.ksp.cryptobot.exchange

import com.ksp.cryptobot.core.OrderSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableQuarantineCodecM21Test {
    @Test fun pendingIntentSurvivesProcessBoundaryCodec() {
        val source = KrakenDurableSubmission(
            clientOrderId = "cts-test-1",
            symbol = "BTCEUR",
            side = OrderSide.BUY,
            startedAtEpochMs = 123456789L,
            status = "PENDING",
            reason = "persisted before transport"
        )
        val restored = KrakenDurableSubmissionCodec.decode(
            KrakenDurableSubmissionCodec.encode(listOf(source))
        )
        assertEquals(1, restored.size)
        assertEquals(source, restored.single())
    }

    @Test fun ambiguousReasonRoundTripsWithoutDelimiterCorruption() {
        val source = KrakenDurableSubmission(
            clientOrderId = "cts|ambiguous",
            symbol = "ETH/EUR",
            side = OrderSide.BUY,
            startedAtEpochMs = 987654321L,
            status = "AMBIGUOUS",
            reason = "timeout | response unknown\nnext process must reconcile"
        )
        val restored = KrakenDurableSubmissionCodec.decode(
            KrakenDurableSubmissionCodec.encode(listOf(source))
        ).single()
        assertTrue(restored.reason.contains("next process"))
        assertEquals(source, restored)
    }
}
