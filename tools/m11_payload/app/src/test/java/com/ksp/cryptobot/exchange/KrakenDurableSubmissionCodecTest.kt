package com.ksp.cryptobot.exchange

import com.ksp.cryptobot.core.OrderSide
import org.junit.Assert.assertEquals
import org.junit.Test

class KrakenDurableSubmissionCodecTest {
    @Test
    fun unresolvedSubmissionRoundTripsAcrossProcessBoundary() {
        val original = listOf(
            KrakenDurableSubmission(
                clientOrderId = "cts-abc123",
                symbol = "BTCEUR",
                side = OrderSide.BUY,
                startedAtEpochMs = 123456789L,
                status = "PENDING",
                reason = "before AddOrder | transport"
            )
        )
        assertEquals(original, KrakenDurableSubmissionCodec.decode(KrakenDurableSubmissionCodec.encode(original)))
    }

    @Test
    fun malformedPersistenceRowsAreIgnoredFailClosedByRegistryRestore() {
        val decoded = KrakenDurableSubmissionCodec.decode("not|a|valid|row")
        assertEquals(emptyList<KrakenDurableSubmission>(), decoded)
    }
}
