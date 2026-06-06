package com.ksp.cryptobot.order

import com.ksp.cryptobot.core.*
import java.math.BigDecimal
import java.math.RoundingMode

class SmartOrderManager {
    fun planEntry(ticker: MarketTicker, decision: AutomationDecision): ManagedOrderPlan {
        val side = if (decision.finalAction == SignalAction.SELL) OrderSide.SELL else OrderSide.BUY
        val price = if (side == OrderSide.BUY) ticker.bid.add(ticker.ask).divide(BigDecimal("2"), 8, RoundingMode.HALF_UP) else ticker.bid
        val quantity = if (price > BigDecimal.ZERO) decision.positionSizeEur.divide(price, 6, RoundingMode.DOWN) else BigDecimal.ZERO
        val request = OrderRequest(
            symbol = ticker.symbol,
            side = side,
            quantity = quantity,
            limitPrice = price,
            clientOrderId = "ksp-smart-${ticker.symbol.lowercase()}-${System.currentTimeMillis()}"
        )
        return ManagedOrderPlan(
            entry = request,
            takeProfitOnePercent = decision.takeProfitPercent.divide(BigDecimal("2"), 2, RoundingMode.HALF_UP),
            takeProfitTwoPercent = decision.takeProfitPercent,
            stopLossPercent = decision.stopLossPercent,
            trailingStopPercent = decision.trailingStopPercent,
            cancelAfterSeconds = 90,
            partialTakeProfitPercent = BigDecimal("50.0"),
            explanation = "Smart limit entry at mid/spread-aware price. Partial TP enabled, stale order cancellation after 90s, break-even/trailing stop managed by bot loop."
        )
    }
}
