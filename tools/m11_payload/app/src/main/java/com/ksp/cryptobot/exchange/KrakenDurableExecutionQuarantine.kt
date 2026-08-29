package com.ksp.cryptobot.exchange

import android.content.Context
import android.content.SharedPreferences
import com.ksp.cryptobot.core.OrderSide
import java.nio.charset.StandardCharsets
import java.util.Base64

data class KrakenDurableSubmission(
    val clientOrderId: String,
    val symbol: String,
    val side: OrderSide,
    val startedAtEpochMs: Long,
    val status: String,
    val reason: String
)

/**
 * Pure codec so persistence behavior is unit-testable outside Android.
 */
object KrakenDurableSubmissionCodec {
    private fun enc(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun dec(value: String): String =
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)

    fun encode(rows: Collection<KrakenDurableSubmission>): String =
        rows.sortedBy { it.clientOrderId }.joinToString("\n") { row ->
            listOf(
                enc(row.clientOrderId),
                enc(row.symbol),
                row.side.name,
                row.startedAtEpochMs.toString(),
                enc(row.status),
                enc(row.reason)
            ).joinToString("|")
        }

    fun decode(raw: String): List<KrakenDurableSubmission> =
        raw.lineSequence().mapNotNull { line ->
            val p = line.split("|")
            if (p.size != 6) return@mapNotNull null
            runCatching {
                KrakenDurableSubmission(
                    clientOrderId = dec(p[0]),
                    symbol = dec(p[1]),
                    side = OrderSide.valueOf(p[2]),
                    startedAtEpochMs = p[3].toLong(),
                    status = dec(p[4]),
                    reason = dec(p[5])
                )
            }.getOrNull()
        }.toList()
}

/**
 * Durable unresolved AddOrder boundary.
 *
 * PENDING is persisted BEFORE the HTTP AddOrder call. If the process dies before a
 * definitive acknowledgement/rejection, the next process treats that row as
 * ambiguous and blocks new BUY entries until exchange execution truth resolves it.
 */
object KrakenDurableExecutionQuarantine {
    private const val PREFS = "cts_kraken_execution_quarantine"
    private const val KEY_ROWS = "unresolved_add_orders"

    private val lock = Any()
    private var prefs: SharedPreferences? = null
    private val rows = linkedMapOf<String, KrakenDurableSubmission>()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs == null) {
                prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            }
            restoreLocked()
        }
    }

    fun unresolved(): List<KrakenDurableSubmission> = synchronized(lock) {
        rows.values.toList()
    }

    fun markPending(
        clientOrderId: String,
        symbol: String,
        side: OrderSide,
        startedAtEpochMs: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            rows[clientOrderId] = KrakenDurableSubmission(
                clientOrderId = clientOrderId,
                symbol = symbol,
                side = side,
                startedAtEpochMs = startedAtEpochMs,
                status = "PENDING",
                reason = "Persisted before Kraken AddOrder transport boundary."
            )
            persistLocked()
        }
    }

    fun markAmbiguous(clientOrderId: String, reason: String) {
        synchronized(lock) {
            val current = rows[clientOrderId] ?: return
            rows[clientOrderId] = current.copy(
                status = "AMBIGUOUS",
                reason = reason.take(500)
            )
            persistLocked()
        }
    }

    fun clear(clientOrderId: String) {
        synchronized(lock) {
            if (rows.remove(clientOrderId) != null) persistLocked()
        }
    }

    private fun restoreLocked() {
        rows.clear()
        val raw = prefs?.getString(KEY_ROWS, "").orEmpty()
        KrakenDurableSubmissionCodec.decode(raw).forEach { row ->
            rows[row.clientOrderId] = row
        }
    }

    private fun persistLocked() {
        // commit(), not apply(): the unresolved intent must reach durable storage before
        // the network AddOrder call can cross the process-crash boundary.
        prefs?.edit()
            ?.putString(KEY_ROWS, KrakenDurableSubmissionCodec.encode(rows.values))
            ?.commit()
    }
}
