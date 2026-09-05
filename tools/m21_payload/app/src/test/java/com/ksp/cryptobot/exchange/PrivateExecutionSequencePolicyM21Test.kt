package com.ksp.cryptobot.exchange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateExecutionSequencePolicyM21Test {
    @Test fun consecutiveUpdateIsAccepted() {
        assertEquals(
            PrivateSequenceDisposition.ACCEPT_NEXT,
            PrivateExecutionSequencePolicy.classify(100L, 101L, "update")
        )
    }

    @Test fun duplicateSequenceIsIdentifiedWithoutReapplying() {
        assertEquals(
            PrivateSequenceDisposition.DUPLICATE,
            PrivateExecutionSequencePolicy.classify(100L, 100L, "update")
        )
    }

    @Test fun outOfOrderSequenceInvalidatesContinuity() {
        assertEquals(
            PrivateSequenceDisposition.STALE_OR_OUT_OF_ORDER,
            PrivateExecutionSequencePolicy.classify(100L, 99L, "update")
        )
    }

    @Test fun forwardGapInvalidatesContinuity() {
        assertEquals(
            PrivateSequenceDisposition.GAP,
            PrivateExecutionSequencePolicy.classify(100L, 105L, "update")
        )
    }

    @Test fun execIdFilterIsIdempotent() {
        val filter = IdempotentExecutionIdFilter()
        assertTrue(filter.accept("EXEC-1"))
        assertFalse(filter.accept("EXEC-1"))
        assertTrue(filter.accept("EXEC-2"))
    }
}
