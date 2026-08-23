package com.ksp.cryptobot.cloudshare

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudShareProtocolTest {
    @Test
    fun desktopV1050CompatibilityVector() {
        val payload = linkedMapOf<String, Any?>(
            "symbol" to "BTCEUR",
            "score" to 77,
            "api_token" to "super-secret-token-abcdefghijklmnopqrstuvwxyz",
            "path" to "C:\\Users\\Steven\\data\\db.sqlite",
            "nested" to linkedMapOf("z" to 2, "a" to 1),
            "allowed" to true
        )
        @Suppress("UNCHECKED_CAST")
        val sanitized = CloudShareProtocol.sanitize(payload) as Map<String, Any?>
        val canonical = CloudShareProtocol.canonicalJson(sanitized)
        assertEquals(
            "{\"allowed\":true,\"api_token\":\"[REDACTED]\",\"nested\":{\"a\":1,\"z\":2},\"path\":\"C:\\\\Users\\\\[LOCAL_USER]\\\\data\\\\db.sqlite\",\"score\":77,\"symbol\":\"BTCEUR\"}",
            canonical
        )
        assertEquals(
            "09e30052559f67568bac12d678172a3a1d679462ea24cfb799a6c674223acec0",
            CloudShareProtocol.sha256(canonical)
        )
        assertEquals(
            "83b371d7920cb1341ac79aec4702d401419e6626c061000e379490bbd2e09d86",
            CloudShareProtocol.eventId("signals", "2026-08-17T00:00:00+00:00", sanitized)
        )
        assertEquals(
            "81e454aea75c886e07598fdac1d4f9ec3b6f12d30d88ac50d3209e4ddf64ee3e",
            CloudShareProtocol.batchId(
                listOf(
                    "83b371d7920cb1341ac79aec4702d401419e6626c061000e379490bbd2e09d86",
                    "a".repeat(64)
                ),
                "12345678-1234-1234-1234-123456789012"
            )
        )
    }
}
