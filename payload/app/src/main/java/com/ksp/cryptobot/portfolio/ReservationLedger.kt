package com.ksp.cryptobot.portfolio

import com.ksp.cryptobot.execution.ExecutionStateStore
import com.ksp.cryptobot.execution.ReservationRow
import com.ksp.cryptobot.execution.ReservationState
import java.math.BigDecimal

/**
 * One persistent CTS reservation ledger. Exchange holds are still accounted separately.
 */
class ReservationLedger(private val store: ExecutionStateStore) {
    fun reserve(
        clientOrderId: String,
        asset: String,
        amount: BigDecimal,
        maxSpendableBeforeLedger: BigDecimal
    ): ReservationRow = store.reserve(clientOrderId, asset, amount, maxSpendableBeforeLedger)

    fun markSubmitted(clientOrderId: String) = store.markReservation(clientOrderId, ReservationState.SUBMITTED)
    fun markFilled(clientOrderId: String) = store.markReservation(clientOrderId, ReservationState.FILLED)
    fun cancel(clientOrderId: String) = store.release(clientOrderId, ReservationState.CANCELLED)
    fun expire(clientOrderId: String) = store.release(clientOrderId, ReservationState.EXPIRED)
    fun release(clientOrderId: String) = store.release(clientOrderId, ReservationState.RELEASED)
    fun active(asset: String): BigDecimal = store.activeReserved(asset)
}
