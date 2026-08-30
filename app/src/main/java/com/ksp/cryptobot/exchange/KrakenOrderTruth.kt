
package com.ksp.cryptobot.exchange

import com.ksp.cryptobot.core.OrderSide
import com.ksp.cryptobot.core.OrderType
import java.math.BigDecimal

data class KrakenClientOrderResolution(
    val found: Boolean,
    val open: Boolean,
    val exchangeOrderId: String = "",
    val clientOrderId: String = "",
    val symbol: String = "",
    val side: OrderSide = OrderSide.BUY,
    val orderType: OrderType = OrderType.LIMIT,
    val status: String = "",
    val quantity: BigDecimal = BigDecimal.ZERO,
    val executedQuantity: BigDecimal = BigDecimal.ZERO,
    val averageFillPrice: BigDecimal = BigDecimal.ZERO,
    val fee: BigDecimal = BigDecimal.ZERO
)

data class KrakenAccountAuthorityIdentity(
    val accountKey: String,
    val source: String
)

data class KrakenDeadManSwitchStatus(
    val timeoutSeconds: Int,
    val currentTime: String,
    val triggerTime: String,
    val enabled: Boolean
)
