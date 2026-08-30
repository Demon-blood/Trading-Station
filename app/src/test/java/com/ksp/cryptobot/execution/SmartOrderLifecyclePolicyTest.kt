package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.OrderSide
import com.ksp.cryptobot.core.OrderType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class SmartOrderLifecyclePolicyTest {
    @Test fun staleBuyLimitAmendsInsteadOfCancelReplace() {
        assertEquals(
            SmartOrderLifecycleAction.AMEND,
            SmartOrderLifecyclePolicy.action(
                side = OrderSide.BUY,
                orderType = OrderType.LIMIT,
                remainingQuantity = BigDecimal("0.5"),
                ageSeconds = 90,
                effectiveStaleSeconds = 90,
                amendmentsAlready = 0,
                targetPriceChangedByAtLeastOneTick = true
            )
        )
    }

    @Test fun hardDeadlineCancelsStaleSignalWithoutReplacement() {
        assertEquals(
            SmartOrderLifecycleAction.CANCEL,
            SmartOrderLifecyclePolicy.action(
                side = OrderSide.BUY,
                orderType = OrderType.LIMIT,
                remainingQuantity = BigDecimal("0.5"),
                ageSeconds = 360,
                effectiveStaleSeconds = 90,
                amendmentsAlready = 3,
                targetPriceChangedByAtLeastOneTick = true
            )
        )
    }

    @Test fun sellLimitIsNeverBlindlyRepriced() {
        assertEquals(
            SmartOrderLifecycleAction.HOLD,
            SmartOrderLifecyclePolicy.action(
                side = OrderSide.SELL,
                orderType = OrderType.LIMIT,
                remainingQuantity = BigDecimal("0.5"),
                ageSeconds = 1000,
                effectiveStaleSeconds = 90,
                amendmentsAlready = 0,
                targetPriceChangedByAtLeastOneTick = true
            )
        )
    }

    @Test fun learnedStaleTimingIsBounded() {
        assertEquals(45L, SmartOrderLifecyclePolicy.effectiveStaleSeconds(90, 10, 10.0))
        assertEquals(180L, SmartOrderLifecyclePolicy.effectiveStaleSeconds(90, 10, 1000.0))
        assertEquals(90L, SmartOrderLifecyclePolicy.effectiveStaleSeconds(90, 2, 20.0))
    }
}
