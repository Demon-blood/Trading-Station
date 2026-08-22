package com.ksp.cryptobot.release

import com.ksp.cryptobot.governance.EntryExitSafetyPolicy
import com.ksp.cryptobot.governance.KillSeverity
import com.ksp.cryptobot.core.SignalAction
import com.ksp.cryptobot.strategy.provenance.ProvenanceType
import com.ksp.cryptobot.strategy.provenance.StrategyProvenanceRegistry

data class CompletionGateResult(
    val passed: Boolean,
    val checks: List<String>
)

object CompletionReleaseGate {
    fun staticRuntimeContracts(): CompletionGateResult {
        val checks = mutableListOf<String>()
        fun check(name: String, ok: Boolean) {
            checks += "${if (ok) "PASS" else "FAIL"} | $name"
        }

        StrategyProvenanceRegistry.assertTruthContract()
        check("Turtle historical/source separation",
            StrategyProvenanceRegistry.turtleOriginal.provenanceType == ProvenanceType.SOURCE_EXACT &&
                StrategyProvenanceRegistry.turtleSpotSafe.provenanceType == ProvenanceType.CTS_REFERENCE)
        check("Turtle LIVE promotion disabled by default", !StrategyProvenanceRegistry.turtleSpotSafe.enabledForLive)

        val highBuy = EntryExitSafetyPolicy.evaluate(SignalAction.BUY, KillSeverity.HIGH, false)
        val highSell = EntryExitSafetyPolicy.evaluate(SignalAction.SELL, KillSeverity.HIGH, true)
        check("HIGH kill blocks new exposure", highBuy.blockNewExposure)
        check("Protective SELL survives HIGH kill", highSell.allowProtectiveExit && !highSell.blockNewExposure)

        val passed = checks.none { it.startsWith("FAIL") }
        return CompletionGateResult(passed, checks)
    }
}
