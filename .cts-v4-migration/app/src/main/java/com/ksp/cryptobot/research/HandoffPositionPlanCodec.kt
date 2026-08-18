package com.ksp.cryptobot.research

import java.math.BigDecimal

/**
 * Persists source-specific entry protection in the existing PositionEntity.source TEXT field.
 * This avoids silently replacing a handoff stop/target with generic app percentages after restart.
 */
data class HandoffPositionPlan(
    val strategyId: String,
    val stopPrice: BigDecimal?,
    val remainingTargets: List<BigDecimal>,
    val fidelity: String,
    val liveTruthGate: String,
    val entryOrderId: String = ""
)

object HandoffPositionPlanCodec {
    private const val PREFIX = "HANDOFF_V1"

    fun encode(plan: HandoffPositionPlan): String = listOf(
        PREFIX,
        "strategy=${clean(plan.strategyId)}",
        "stop=${plan.stopPrice?.stripTrailingZeros()?.toPlainString().orEmpty()}",
        "targets=${plan.remainingTargets.filter { it > BigDecimal.ZERO }.joinToString(",") { it.stripTrailingZeros().toPlainString() }}",
        "fidelity=${clean(plan.fidelity)}",
        "truth=${clean(plan.liveTruthGate)}",
        "order=${clean(plan.entryOrderId)}"
    ).joinToString("|")

    fun decode(raw: String?): HandoffPositionPlan? {
        if (raw.isNullOrBlank() || !raw.startsWith("$PREFIX|")) return null
        val values = raw.split('|').drop(1).mapNotNull { token ->
            val idx = token.indexOf('=')
            if (idx <= 0) null else token.substring(0, idx) to token.substring(idx + 1)
        }.toMap()
        return HandoffPositionPlan(
            strategyId = values["strategy"].orEmpty(),
            stopPrice = values["stop"]?.toBigDecimalOrNull(),
            remainingTargets = values["targets"].orEmpty().split(',').mapNotNull { it.toBigDecimalOrNull() }.filter { it > BigDecimal.ZERO },
            fidelity = values["fidelity"].orEmpty(),
            liveTruthGate = values["truth"].orEmpty(),
            entryOrderId = values["order"].orEmpty()
        )
    }

    fun afterTarget(plan: HandoffPositionPlan, target: BigDecimal): HandoffPositionPlan =
        plan.copy(remainingTargets = plan.remainingTargets.filter { it > target })

    private fun clean(value: String): String = value.replace("|", "_").replace("=", "_").replace(",", "_")
}
