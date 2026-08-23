package com.ksp.cryptobot.exchange

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KrakenExecutionIdentityTest {
    @Test
    fun freeTextClientIdIsPreservedWhenKrakenCompatible() {
        assertEquals("ksp-1787500000000", KrakenClientOrderId.normalize("ksp-1787500000000"))
    }

    @Test
    fun longArbitraryClientIdBecomesStableKrakenFreeText() {
        val raw = "this-is-a-client-order-id-that-is-longer-than-kraken-free-text"
        val a = KrakenClientOrderId.normalize(raw)
        val b = KrakenClientOrderId.normalize(raw)
        assertEquals(a, b)
        assertTrue(a.length <= 18)
        assertTrue(a.startsWith("cts-"))
        assertTrue(a.all { it.code in 33..126 })
    }

    @Test
    fun validLongUuidIsPreserved() {
        val uuid = "6d1b345e-2821-40e2-ad83-4ecb18a06876"
        assertEquals(uuid, KrakenClientOrderId.normalize(uuid))
    }

    @Test
    fun nonceIsStrictlyIncreasingEvenWithinSameMillisecond() {
        var previous = KrakenNonceSequencer.nextLong()
        repeat(1000) {
            val current = KrakenNonceSequencer.nextLong()
            assertTrue(current > previous)
            previous = current
        }
    }
}
