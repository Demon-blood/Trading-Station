package com.ksp.cryptobot.governance

import com.ksp.cryptobot.core.AiDecision
import com.ksp.cryptobot.core.Candle
import com.ksp.cryptobot.core.SignalAction
import java.math.BigDecimal
import java.math.RoundingMode

class CounterfactualLearningEngine {
    fun evaluate(decision: AiDecision, candles: List<Candle>): Pair<Int, String> {
        if (candles.size < 8) return 0 to "counterfactual skipped; not enough candles"
        val last = candles.last().close
        val fiveAgo = candles[candles.lastIndex - 5].close
        if (last <= BigDecimal.ZERO || fiveAgo <= BigDecimal.ZERO) return 0 to "counterfactual skipped; invalid candle prices"
        val delayedMove = last.subtract(fiveAgo).divide(fiveAgo, 10, RoundingMode.HALF_UP).multiply(BigDecimal("100"))
        val isBuy = decision.finalAction == SignalAction.BUY || decision.finalAction == SignalAction.SMALL_BUY
        return when {
            isBuy && delayedMove < BigDecimal("-0.35") -> -3 to "delayed entry would have been cheaper; delayed_move=${delayedMove.setScale(2, RoundingMode.HALF_UP)}%"
            isBuy && delayedMove > BigDecimal("0.35") -> 1 to "delayed entry would have been more expensive; delayed_move=${delayedMove.setScale(2, RoundingMode.HALF_UP)}%"
            else -> 0 to "counterfactual neutral; delayed_move=${delayedMove.setScale(2, RoundingMode.HALF_UP)}%"
        }
    }
}
