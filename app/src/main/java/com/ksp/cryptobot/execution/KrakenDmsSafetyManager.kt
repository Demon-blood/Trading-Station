package com.ksp.cryptobot.execution

import android.content.Context
import android.os.SystemClock
import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.ExchangeProvider
import com.ksp.cryptobot.exchange.KrakenSpotClient
import com.ksp.cryptobot.settings.AppSettingsStore

data class KrakenDmsSafetySnapshot(
    val safeForNewEntries: Boolean,
    val state: String,
    val timeoutSeconds: Int = 0,
    val currentTime: String = "",
    val triggerTime: String = "",
    val lastConfirmedElapsedMs: Long = 0L,
    val reason: String = ""
)

object KrakenDmsSafetyPolicy {
    const val CONFIRMATION_MAX_AGE_MS = 45_000L

    fun entryAllowed(
        mode: BotMode,
        state: String,
        confirmationAgeMs: Long
    ): Boolean =
        mode == BotMode.PAPER ||
            (state == "DISARMED" && confirmationAgeMs in 0..CONFIRMATION_MAX_AGE_MS)
}

object KrakenDmsSafetyRuntime {
    @Volatile
    private var snapshot = KrakenDmsSafetySnapshot(
        safeForNewEntries = false,
        state = "UNINITIALIZED",
        reason = "Kraken DMS state has not been confirmed."
    )

    fun snapshot(): KrakenDmsSafetySnapshot = snapshot

    fun publish(value: KrakenDmsSafetySnapshot) {
        snapshot = value
    }

    fun canSubmitNewEntry(mode: BotMode): Pair<Boolean, String> {
        if (mode == BotMode.PAPER) return true to "PAPER has no Kraken DMS requirement."
        val s = snapshot
        val age = if (s.lastConfirmedElapsedMs <= 0L) Long.MAX_VALUE
        else (SystemClock.elapsedRealtime() - s.lastConfirmedElapsedMs).coerceAtLeast(0L)
        val allowed = s.safeForNewEntries &&
            KrakenDmsSafetyPolicy.entryAllowed(mode, s.state, age)
        return if (allowed) {
            true to "Kraken DMS is confirmed DISARMED; confirmationAgeMs=$age."
        } else {
            false to "Kraken DMS safety is ${s.state}; confirmationAgeMs=$age; ${s.reason}"
        }
    }
}

/**
 * M14 deliberately keeps Kraken CancelAllOrdersAfter disabled while the app uses
 * exchange-resting protective SELL stops. Kraken DMS is account-wide: if it fires,
 * it cancels all client orders, which can include those protective orders.
 *
 * The manager therefore reasserts timeout=0 and publishes a short-lived confirmation.
 * If that confirmation cannot be refreshed, new BUYs fail closed. Protective SELLs are
 * intentionally not gated by DMS state.
 */
class KrakenDmsSafetyManager(context: Context) {
    private val settingsStore = AppSettingsStore(context.applicationContext)

    suspend fun ensureDisarmed(settings: BotSettings, reason: String): KrakenDmsSafetySnapshot {
        if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) {
            return publish(
                KrakenDmsSafetySnapshot(
                    safeForNewEntries = true,
                    state = "NOT_APPLICABLE",
                    lastConfirmedElapsedMs = SystemClock.elapsedRealtime(),
                    reason = "PAPER does not submit Kraken client orders."
                )
            )
        }
        if (settings.exchangeProvider != ExchangeProvider.KRAKEN) {
            return publish(
                KrakenDmsSafetySnapshot(
                    safeForNewEntries = false,
                    state = "UNSUPPORTED_PROVIDER",
                    reason = "M14 DMS safety is implemented for Kraken LIVE execution only."
                )
            )
        }

        val key = settingsStore.exchangeApiKey(ExchangeProvider.KRAKEN).orEmpty()
        val secret = settingsStore.exchangeSecretKey(ExchangeProvider.KRAKEN).orEmpty()
        if (key.isBlank() || secret.isBlank()) {
            return publish(
                KrakenDmsSafetySnapshot(
                    safeForNewEntries = false,
                    state = "CREDENTIALS_MISSING",
                    reason = "Kraken credentials are required to confirm DMS is disabled."
                )
            )
        }

        return runCatching {
            val status = KrakenSpotClient(key, secret).setDeadMansSwitch(0)
            require(!status.enabled && status.timeoutSeconds == 0) {
                "Kraken did not confirm CancelAllOrdersAfter timeout=0."
            }
            KrakenDmsSafetySnapshot(
                safeForNewEntries = true,
                state = "DISARMED",
                timeoutSeconds = 0,
                currentTime = status.currentTime,
                triggerTime = status.triggerTime,
                lastConfirmedElapsedMs = SystemClock.elapsedRealtime(),
                reason = "Account-wide Kraken DMS confirmed disabled for protective-stop safety. context=$reason"
            )
        }.getOrElse { error ->
            KrakenDmsSafetySnapshot(
                safeForNewEntries = false,
                state = "UNKNOWN",
                reason = "Unable to confirm Kraken DMS is disabled: ${error.message ?: error.javaClass.simpleName}. context=$reason"
            )
        }.let(::publish)
    }

    fun stop() {
        publish(
            KrakenDmsSafetySnapshot(
                safeForNewEntries = false,
                state = "STOPPED",
                reason = "Trading host stopped; DMS confirmation is no longer current."
            )
        )
    }

    private fun publish(snapshot: KrakenDmsSafetySnapshot): KrakenDmsSafetySnapshot {
        KrakenDmsSafetyRuntime.publish(snapshot)
        return snapshot
    }
}
