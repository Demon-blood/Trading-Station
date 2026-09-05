package com.ksp.cryptobot.observability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class M23DiagnosticContractTest {
    @Test
    fun sha256IsDeterministicAndContentSensitive() {
        val one = M23DiagnosticBundleExporter.sha256("health".toByteArray())
        val two = M23DiagnosticBundleExporter.sha256("health".toByteArray())
        val three = M23DiagnosticBundleExporter.sha256("health2".toByteArray())
        assertEquals(64, one.length)
        assertEquals(one, two)
        assertNotEquals(one, three)
    }
}
