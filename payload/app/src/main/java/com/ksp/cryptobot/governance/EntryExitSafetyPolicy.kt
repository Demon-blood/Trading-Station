package com.ksp.cryptobot.governance

import com.ksp.cryptobot.core.SignalAction

enum class KillSeverity { NONE, LOW, MEDIUM, HIGH, CRITICAL }

data class EntryExitSafetyDecision(
    val blockNewExposure: Boolean,
    val allowProtectiveExit: Boolean,
    val reason: String
)

object EntryExitSafetyPolicy {
    fun evaluate(action: SignalAction, killSeverity: KillSeverity, safeModeBlocksEntries: Boolean): EntryExitSafetyDecision {
        val entry = action == SignalAction.BUY || action == SignalAction.SMALL_BUY
        val protectiveExit = action == SignalAction.SELL
        val severe = killSeverity == KillSeverity.HIGH || killSeverity == KillSeverity.CRITICAL
        val blockEntry = entry && (severe || safeModeBlocksEntries)
        return EntryExitSafetyDecision(
            blockNewExposure = blockEntry,
            allowProtectiveExit = protectiveExit,
            reason = when {
                blockEntry -> "New exposure blocked by kill/safe-mode severity=$killSeverity."
                protectiveExit -> "Protective/reducing SELL remains permitted under kill/safe mode."
                else -> "No entry/exit safety block."
            }
        )
    }
}
