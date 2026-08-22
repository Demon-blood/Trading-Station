package com.ksp.cryptobot.execution

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.ksp.cryptobot.core.LiveOrderInfo
import java.math.BigDecimal
import java.security.MessageDigest

enum class IntentState { CREATED, RESERVED, SUBMITTED, PARTIALLY_FILLED, FILLED, CANCELLED, EXPIRED, REJECTED }
enum class ReservationState { RESERVED, SUBMITTED, RELEASED, FILLED, CANCELLED, EXPIRED }

data class ReservationRow(
    val reservationId: String,
    val clientOrderId: String,
    val asset: String,
    val amount: BigDecimal,
    val state: ReservationState,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

data class IntentLedgerRow(
    val clientOrderId: String,
    val semanticHash: String,
    val strategyId: String,
    val brokerMode: String,
    val state: IntentState,
    val exchangeOrderId: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

object ExecutionKey {
    fun fill(provider: String, mode: String, sourceOrderId: String, cumulativeBefore: BigDecimal, fillQty: BigDecimal): String =
        listOf(provider, mode, sourceOrderId, cumulativeBefore.norm(), cumulativeBefore.add(fillQty).norm()).joinToString(":")

    private fun BigDecimal.norm() = stripTrailingZeros().toPlainString()
}

class ExecutionStateStore(context: Context) {
    private val db = Helper(context.applicationContext)

    fun semanticHash(fields: Map<String, String>): String {
        val canonical = fields.toSortedMap().entries.joinToString("\n") { "${it.key}=${it.value}" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * Returns false when the same clientOrderId was already registered.
     * Same semantic hash means idempotent replay; a different hash is a hard collision.
     */
    fun registerIntent(intent: OrderIntent, brokerMode: IntentBrokerMode): Boolean {
        val hash = semanticHash(intent.semanticFields())
        val now = System.currentTimeMillis()
        val existing = intent(intent.clientOrderId)
        if (existing != null) {
            require(existing.semanticHash == hash) {
                "ORDER_INTENT_ID_COLLISION clientOrderId=${intent.clientOrderId} existingHash=${existing.semanticHash} newHash=$hash"
            }
            return false
        }
        val v = ContentValues().apply {
            put("clientOrderId", intent.clientOrderId)
            put("semanticHash", hash)
            put("strategyId", intent.strategyId)
            put("brokerMode", brokerMode.name)
            put("state", IntentState.CREATED.name)
            put("exchangeOrderId", "")
            put("createdAtEpochMs", now)
            put("updatedAtEpochMs", now)
        }
        db.writableDatabase.insertOrThrow("order_intents", null, v)
        return true
    }

    fun intent(clientOrderId: String): IntentLedgerRow? {
        db.readableDatabase.query(
            "order_intents",
            arrayOf("clientOrderId","semanticHash","strategyId","brokerMode","state","exchangeOrderId","createdAtEpochMs","updatedAtEpochMs"),
            "clientOrderId=?", arrayOf(clientOrderId), null, null, null, "1"
        ).use { c ->
            if (!c.moveToFirst()) return null
            return IntentLedgerRow(
                c.getString(0), c.getString(1), c.getString(2), c.getString(3),
                IntentState.valueOf(c.getString(4)), c.getString(5), c.getLong(6), c.getLong(7)
            )
        }
    }

    fun updateIntent(clientOrderId: String, state: IntentState, exchangeOrderId: String = "") {
        val v = ContentValues().apply {
            put("state", state.name)
            if (exchangeOrderId.isNotBlank()) put("exchangeOrderId", exchangeOrderId)
            put("updatedAtEpochMs", System.currentTimeMillis())
        }
        db.writableDatabase.update("order_intents", v, "clientOrderId=?", arrayOf(clientOrderId))
    }

    fun reserve(clientOrderId: String, asset: String, amount: BigDecimal, maxSpendable: BigDecimal): ReservationRow {
        require(amount > BigDecimal.ZERO) { "Reservation amount must be >0." }
        val active = activeReserved(asset)
        val remaining = maxSpendable.subtract(active).max(BigDecimal.ZERO)
        require(amount <= remaining) {
            "REJECTED_INSUFFICIENT_SPENDABLE_AFTER_LEDGER asset=$asset maxSpendable=$maxSpendable ledgerReserved=$active remaining=$remaining requestedReservation=$amount"
        }
        val id = "res-$clientOrderId"
        val now = System.currentTimeMillis()
        val existing = reservationForOrder(clientOrderId)
        if (existing != null && existing.state in setOf(ReservationState.RESERVED, ReservationState.SUBMITTED)) return existing
        val v = ContentValues().apply {
            put("reservationId", id)
            put("clientOrderId", clientOrderId)
            put("asset", asset.uppercase())
            put("amount", amount.toPlainString())
            put("state", ReservationState.RESERVED.name)
            put("createdAtEpochMs", now)
            put("updatedAtEpochMs", now)
        }
        db.writableDatabase.insertWithOnConflict("reservations", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        updateIntent(clientOrderId, IntentState.RESERVED)
        return ReservationRow(id, clientOrderId, asset.uppercase(), amount, ReservationState.RESERVED, now, now)
    }

    fun markReservation(clientOrderId: String, state: ReservationState) {
        val v = ContentValues().apply {
            put("state", state.name)
            put("updatedAtEpochMs", System.currentTimeMillis())
        }
        db.writableDatabase.update("reservations", v, "clientOrderId=?", arrayOf(clientOrderId))
    }

    fun release(clientOrderId: String, state: ReservationState = ReservationState.RELEASED) {
        markReservation(clientOrderId, state)
    }

    fun activeReserved(asset: String): BigDecimal {
        var total = BigDecimal.ZERO
        db.readableDatabase.query(
            "reservations", arrayOf("amount"),
            "asset=? AND state IN (?,?)",
            arrayOf(asset.uppercase(), ReservationState.RESERVED.name, ReservationState.SUBMITTED.name),
            null,null,null
        ).use { c -> while (c.moveToNext()) total += c.getString(0).toBigDecimalOrNull() ?: BigDecimal.ZERO }
        return total
    }

    fun reservationForOrder(clientOrderId: String): ReservationRow? {
        db.readableDatabase.query(
            "reservations",
            arrayOf("reservationId","clientOrderId","asset","amount","state","createdAtEpochMs","updatedAtEpochMs"),
            "clientOrderId=?", arrayOf(clientOrderId), null,null,null,"1"
        ).use { c ->
            if (!c.moveToFirst()) return null
            return ReservationRow(c.getString(0),c.getString(1),c.getString(2),c.getString(3).toBigDecimal(),
                ReservationState.valueOf(c.getString(4)),c.getLong(5),c.getLong(6))
        }
    }

    /**
     * Open-order reconciliation makes reservation recovery crash-safe.
     * A submitted reservation with no matching live/open order is released.
     */
    fun reconcile(openOrders: List<LiveOrderInfo>) {
        val openIds = openOrders.map { it.exchangeOrderId }.filter { it.isNotBlank() }.toSet()
        val submitted = mutableListOf<Pair<String,String>>()
        db.readableDatabase.query(
            "order_intents", arrayOf("clientOrderId","exchangeOrderId"),
            "state IN (?,?)",
            arrayOf(IntentState.SUBMITTED.name, IntentState.PARTIALLY_FILLED.name),
            null,null,null
        ).use { c -> while(c.moveToNext()) submitted += c.getString(0) to c.getString(1) }
        submitted.forEach { (clientId, exchangeId) ->
            if (exchangeId.isNotBlank() && exchangeId !in openIds) {
                release(clientId)
            }
        }
    }

    /**
     * Returns false if this exact fill progress was already applied.
     */
    fun recordFill(executionKey: String, clientOrderId: String, exchangeOrderId: String, quantity: BigDecimal, price: BigDecimal, fee: BigDecimal): Boolean {
        val now = System.currentTimeMillis()
        val v = ContentValues().apply {
            put("executionKey", executionKey)
            put("clientOrderId", clientOrderId)
            put("exchangeOrderId", exchangeOrderId)
            put("quantity", quantity.toPlainString())
            put("price", price.toPlainString())
            put("fee", fee.toPlainString())
            put("createdAtEpochMs", now)
        }
        val id = db.writableDatabase.insertWithOnConflict("fills", null, v, SQLiteDatabase.CONFLICT_IGNORE)
        return id != -1L
    }

    fun compactIfIdle() {
        db.writableDatabase.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
        db.writableDatabase.execSQL("VACUUM")
    }

    fun diagnostics(): List<String> = listOf(
        "executionStateDb=${db.databaseName}",
        "activeEURReserved=${activeReserved("EUR")}",
        "intentRows=${count("order_intents")}",
        "reservationRows=${count("reservations")}",
        "fillRows=${count("fills")}"
    )

    private fun count(table: String): Long =
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c -> if(c.moveToFirst()) c.getLong(0) else 0L }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "cts_execution_state.db", null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.enableWriteAheadLogging()
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE order_intents(
                    clientOrderId TEXT PRIMARY KEY NOT NULL,
                    semanticHash TEXT NOT NULL,
                    strategyId TEXT NOT NULL,
                    brokerMode TEXT NOT NULL,
                    state TEXT NOT NULL,
                    exchangeOrderId TEXT NOT NULL,
                    createdAtEpochMs INTEGER NOT NULL,
                    updatedAtEpochMs INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE reservations(
                    reservationId TEXT PRIMARY KEY NOT NULL,
                    clientOrderId TEXT UNIQUE NOT NULL,
                    asset TEXT NOT NULL,
                    amount TEXT NOT NULL,
                    state TEXT NOT NULL,
                    createdAtEpochMs INTEGER NOT NULL,
                    updatedAtEpochMs INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX idx_reservation_asset_state ON reservations(asset,state)")
            db.execSQL("""
                CREATE TABLE fills(
                    executionKey TEXT PRIMARY KEY NOT NULL,
                    clientOrderId TEXT NOT NULL,
                    exchangeOrderId TEXT NOT NULL,
                    quantity TEXT NOT NULL,
                    price TEXT NOT NULL,
                    fee TEXT NOT NULL,
                    createdAtEpochMs INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX idx_fills_client_order ON fills(clientOrderId,createdAtEpochMs)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
}

object ExecutionStateRuntime {
    @Volatile private var store: ExecutionStateStore? = null
    fun install(context: Context): ExecutionStateStore = synchronized(this) {
        store ?: ExecutionStateStore(context.applicationContext).also { store = it }
    }
    fun get(): ExecutionStateStore = store ?: error("ExecutionStateRuntime not installed.")
    fun getOrNull(): ExecutionStateStore? = store
}
