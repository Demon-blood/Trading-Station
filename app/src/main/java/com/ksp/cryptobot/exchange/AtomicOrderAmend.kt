package com.ksp.cryptobot.exchange

import com.ksp.cryptobot.core.OrderSide
import com.ksp.cryptobot.core.OrderType
import java.math.BigDecimal
import java.time.Instant

data class AtomicOrderAmendRequest(
    val exchangeOrderId: String = "",
    val clientOrderId: String = "",
    val symbol: String,
    val side: OrderSide,
    val orderType: OrderType,
    /**
     * Kraken interprets order_qty as the NEW TOTAL order quantity, not remaining quantity.
     * M15's automatic repricer intentionally leaves this null and amends price only.
     */
    val newTotalQuantity: BigDecimal? = null,
    val newLimitPrice: BigDecimal? = null,
    val newTriggerPrice: BigDecimal? = null,
    val postOnly: Boolean? = null,
    val deadline: Instant? = null
)

data class AtomicOrderAmendResult(
    val supported: Boolean,
    val amended: Boolean,
    val amendId: String = "",
    val exchangeOrderId: String = "",
    val clientOrderId: String = "",
    val reason: String = ""
)
