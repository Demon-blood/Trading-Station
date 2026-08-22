package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.ExchangeProvider
import com.ksp.cryptobot.core.OrderResult
import com.ksp.cryptobot.core.OrderSide
import com.ksp.cryptobot.exchange.CryptoExchangeClient
import com.ksp.cryptobot.exchange.KrakenSpotClient
import com.ksp.cryptobot.portfolio.ReservationLedger
import java.math.BigDecimal

class DuplicateOrderIntentException(message: String) : IllegalStateException(message)

data class RoutedOrderResult(
    val result: OrderResult,
    val brokerMode: IntentBrokerMode,
    val validationDescription: String = "",
    val intentReplayPrevented: Boolean = false
)

/**
 * Single execution seam for normal entries, lifecycle exits, protective exits and recovery.
 * Strategy logic must create an OrderIntent before reaching this layer.
 */
object UnifiedOrderIntentRouter {
    suspend fun submit(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        intent: OrderIntent,
        quoteAsset: String = "EUR",
        reservationAmount: BigDecimal = BigDecimal.ZERO,
        maxSpendableBeforeLedger: BigDecimal = BigDecimal.ZERO
    ): RoutedOrderResult {
        val store = ExecutionStateRuntime.get()
        val mode = brokerMode(settings)
        val created = store.registerIntent(intent, mode)
        if (!created) {
            val existing = store.intent(intent.clientOrderId)
            throw DuplicateOrderIntentException(
                "DUPLICATE_ORDER_INTENT clientOrderId=${intent.clientOrderId} state=${existing?.state} exchangeOrderId=${existing?.exchangeOrderId}"
            )
        }

        val ledger = ReservationLedger(store)
        if (intent.side == OrderSide.BUY && reservationAmount > BigDecimal.ZERO) {
            ledger.reserve(intent.clientOrderId, quoteAsset, reservationAmount, maxSpendableBeforeLedger)
        }

        return try {
            var validation = ""
            if (settings.mode == BotMode.LIVE_AUTO && settings.exchangeProvider == ExchangeProvider.KRAKEN && exchange is KrakenSpotClient) {
                // Same OrderIntent, same serializer, validate=true first. Validation success is not a fill.
                validation = exchange.validateOrder(intent.toOrderRequest()).description
            }
            val result = when (mode) {
                IntentBrokerMode.PAPER -> PaperIntentBroker(exchange).submit(intent).orderResult
                    ?: error("PAPER broker did not return an OrderResult.")
                IntentBrokerMode.KRAKEN_LIVE -> KrakenLiveIntentBroker(exchange as KrakenSpotClient).submit(intent).orderResult
                    ?: error("Kraken LIVE broker did not return an OrderResult.")
                IntentBrokerMode.KRAKEN_VALIDATE -> error("Validation-only mode does not create an execution result.")
            }

            store.updateIntent(intent.clientOrderId, IntentState.SUBMITTED, result.exchangeOrderId)
            if (intent.side == OrderSide.BUY && reservationAmount > BigDecimal.ZERO) ledger.markSubmitted(intent.clientOrderId)

            if (result.executedQuantity > BigDecimal.ZERO) {
                val key = ExecutionKey.fill(
                    provider = settings.exchangeProvider.name,
                    mode = if (result.paper) "PAPER" else "LIVE",
                    sourceOrderId = result.exchangeOrderId.ifBlank { intent.clientOrderId },
                    cumulativeBefore = BigDecimal.ZERO,
                    fillQty = result.executedQuantity
                )
                val firstApplication = store.recordFill(
                    key, intent.clientOrderId, result.exchangeOrderId,
                    result.executedQuantity, result.averagePrice, result.fee
                )
                if (!firstApplication) {
                    throw DuplicateOrderIntentException("DUPLICATE_FILL executionKey=$key clientOrderId=${intent.clientOrderId}")
                }
                store.updateIntent(intent.clientOrderId, IntentState.FILLED, result.exchangeOrderId)
                if (intent.side == OrderSide.BUY) ledger.markFilled(intent.clientOrderId)
            }
            RoutedOrderResult(result, mode, validation)
        } catch (error: Throwable) {
            store.updateIntent(intent.clientOrderId, IntentState.REJECTED)
            if (intent.side == OrderSide.BUY) ledger.release(intent.clientOrderId)
            throw error
        }
    }

    suspend fun validateOnly(
        settings: BotSettings,
        client: KrakenSpotClient,
        intent: OrderIntent
    ): KrakenOrderValidationResult {
        require(settings.exchangeProvider == ExchangeProvider.KRAKEN) { "Kraken validation requires Kraken provider." }
        val store = ExecutionStateRuntime.get()
        val created = store.registerIntent(intent, IntentBrokerMode.KRAKEN_VALIDATE)
        if (!created) throw DuplicateOrderIntentException("DUPLICATE_VALIDATE_INTENT clientOrderId=${intent.clientOrderId}")
        return try {
            client.validateOrder(intent.toOrderRequest()).also {
                store.updateIntent(intent.clientOrderId, IntentState.CANCELLED) // validation is terminal and creates no live order
            }
        } catch (error: Throwable) {
            store.updateIntent(intent.clientOrderId, IntentState.REJECTED)
            throw error
        }
    }

    fun reconcileOpenOrders(openOrders: List<com.ksp.cryptobot.core.LiveOrderInfo>) {
        ExecutionStateRuntime.getOrNull()?.reconcile(openOrders)
    }

    private fun brokerMode(settings: BotSettings): IntentBrokerMode = when {
        settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER -> IntentBrokerMode.PAPER
        settings.mode == BotMode.LIVE_CONFIRM -> IntentBrokerMode.KRAKEN_VALIDATE
        else -> IntentBrokerMode.KRAKEN_LIVE
    }
}
