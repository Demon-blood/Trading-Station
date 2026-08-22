package com.ksp.cryptobot.release

import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionReleaseGateTest {
    @Test fun hardStaticContractsPass() {
        val result=CompletionReleaseGate.staticRuntimeContracts()
        assertTrue(result.checks.joinToString("\n"), result.passed)
    }
}
