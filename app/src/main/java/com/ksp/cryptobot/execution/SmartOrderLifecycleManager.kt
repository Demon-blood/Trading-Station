package com.ksp.cryptobot.execution

import android.content.Context
import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.LiveOrderInfo
import com.ksp.cryptobot.core.OrderSide
import com.ksp.cryptobot.core.OrderType
import com.ksp.cryptobot.exchange.AtomicOrderAmendRequest
import com.ksp.cryptobot.exchange.CryptoExchangeClient
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.roundToLong

enum class SmartOrderLifecycleAction { HOLD, AMEND, CANCEL }

data class SmartOrderLifecycleEvent(
    val severity: String,
    val message: String
)

data class ExecutionCalibrationSnapshot(
    val samples: Int,
    val meanFillSeconds: Double,
    val meanSlippageBps: Double,
    val totalAmendments: Long,
    val totalCancels: Long
) {
    val amendmentsPerCompletedFill: Double
        get() = if (samples <= 0) 0.0 else totalAmendments.toDouble() / samples.toDouble()
}

object SmartOrderLifecyclePolicy {
    const val MAX_AUTOMATIC_AMENDS = 3
    const val HARD_CANCEL_MULTIPLIER = 4L

    fun effectiveStaleSeconds(
        configuredSeconds: Long,
        calibrationSamples: Int,
        meanFillSeconds: Double
    ): Long {
        val configured = configuredSeconds.coerceAtLeast(15L)
        if (calibrationSamples < 3 || !meanFillSeconds.isFinite() || meanFillSeconds <= 0.0) {
            return configured
        }
        val learned = (meanFillSeconds * 1.25).roundToLong()
        return learned.coerceIn(
            (configured / 2L).coerceAtLeast(15L),
            (configured * 2L).coerceAtLeast(30L)
        )
    }

    fun action(
        side: OrderSide,
        orderType: OrderType,
        remainingQuantity: BigDecimal,
        ageSeconds: Long,
        effectiveStaleSeconds: Long,
        amendmentsAlready: Int,
        targetPriceChangedByAtLeastOneTick: Boolean
    ): SmartOrderLifecycleAction {
        // M15 only reprices entry BUY LIMIT orders. Repricing an unknown-purpose SELL
        // could move an exit/protective objective and is deliberately deferred.
        if (side != OrderSide.BUY || orderType != OrderType.LIMIT || remainingQuantity <= BigDecimal.ZERO) {
            return SmartOrderLifecycleAction.HOLD
        }

        val stale = effectiveStaleSeconds.coerceAtLeast(15L)
        if (ageSeconds >= stale * HARD_CANCEL_MULTIPLIER) {
            return SmartOrderLifecycleAction.CANCEL
        }

        if (amendmentsAlready >= MAX_AUTOMATIC_AMENDS) {
            return SmartOrderLifecycleAction.HOLD
        }

        val nextAmendAge = stale * (amendmentsAlready + 1L)
        return if (ageSeconds >= nextAmendAge && targetPriceChangedByAtLeastOneTick) {
            SmartOrderLifecycleAction.AMEND
        } else {
            SmartOrderLifecycleAction.HOLD
        }
    }
}

object ExecutionCalibrationMath {
    fun nextMean(previousSamples: Int, previousMean: Double, observation: Double): Double {
        if (!observation.isFinite()) return previousMean
        if (previousSamples <= 0 || !previousMean.isFinite()) return observation
        return ((previousMean * previousSamples.toDouble()) + observation) /
            (previousSamples + 1).toDouble()
    }

    /**
     * Positive bps means adverse execution versus the originally observed working price.
     * Negative means price improvement.
     */
    fun slippageBps(side: OrderSide, plannedPrice: BigDecimal, actualPrice: BigDecimal): Double {
        if (plannedPrice <= BigDecimal.ZERO || actualPrice <= BigDecimal.ZERO) return 0.0
        val adverse = when (side) {
            OrderSide.BUY -> actualPrice.subtract(plannedPrice)
            OrderSide.SELL -> plannedPrice.subtract(actualPrice)
        }
        return adverse
            .divide(plannedPrice, 12, RoundingMode.HALF_UP)
            .multiply(BigDecimal("10000"))
            .toDouble()
    }
}

/**
 * M15 bounded open-order lifecycle with M16 L2 microstructure overlay:
 *
 *   HOLD -> ATOMIC AMEND (price only, post-only) -> hard-timeout CANCEL
 *
 * Kraken's atomic amend keeps the Kraken/client order identifiers stable. The automatic
 * manager deliberately never changes quantity: Kraken order_qty is total quantity and a
 * bad partial-fill calculation could otherwise cancel or over-size the remainder.
 *
 * Per-symbol fill time, slippage, amendment count and cancellations are persisted in
 * SharedPreferences. They only tune the stale/requote timing inside a strict 0.5x..2x
 * configured bound; they never override risk, authority, DMS or net-EV gates.
 */
class SmartOrderLifecycleManager(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("cts_m15_execution_calibration", Context.MODE_PRIVATE)
    private val microstructure = MarketMicrostructureEngine()

    suspend fun manage(
        settings: BotSettings,
        exchange: CryptoExchangeClient,
        openOrders: List<LiveOrderInfo>
    ): List<SmartOrderLifecycleEvent> {
        if (settings.mode == BotMode.PAPER) return emptyList()

        val events = mutableListOf<SmartOrderLifecycleEvent>()
        harvestClosedOrders(exchange, events)
        if (!settings.smartLimitRequote) return events

        val nowMs = System.currentTimeMillis()
        val nowSec = nowMs / 1000L

        for (order in openOrders.take(24)) {
            if (order.side != OrderSide.BUY || order.orderType != OrderType.LIMIT) {
                continue
            }
            rememberOpenOrder(order, nowMs)

            val calibration = calibration(order.symbol)
            val effectiveStale = SmartOrderLifecyclePolicy.effectiveStaleSeconds(
                configuredSeconds = settings.staleOrderTimeoutSeconds,
                calibrationSamples = calibration.samples,
                meanFillSeconds = calibration.meanFillSeconds
            )
            val age = (nowSec - order.openedAtEpochSeconds).coerceAtLeast(0L)
            val amendments = orderAmendments(order)

            val tickerResult = runCatching { exchange.getTicker(order.symbol) }
            if (tickerResult.isFailure) {
                val error = tickerResult.exceptionOrNull()
                events += SmartOrderLifecycleEvent(
                    "WARN",
                    "Unable to evaluate ${order.symbol} working BUY for atomic amend: ${error?.message}. Existing order left untouched."
                )
                continue
            }
            val ticker = tickerResult.getOrThrow()
            val symbolInfo = runCatching { exchange.validateSymbol(order.symbol) }.getOrNull()
            val tick = symbolInfo?.tickSize
                ?.takeIf { it > BigDecimal.ZERO }
                ?: BigDecimal.ONE.movePointLeft((symbolInfo?.priceDecimals ?: 8).coerceIn(0, 12))

            val book = runCatching { exchange.getOrderBook(order.symbol, 25) }.getOrNull()
            val micro = book?.let {
                microstructure.evaluate(
                    orderBook = it,
                    side = OrderSide.BUY,
                    requestedQuote = order.remainingQuantity.multiply(
                        ticker.ask.takeIf { price -> price > BigDecimal.ZERO } ?: order.price
                    ),
                    workingPrice = order.price,
                    tickSize = tick,
                    fillHorizonSeconds = effectiveStale,
                    calibrationSamples = calibration.samples,
                    calibratedMeanFillSeconds = calibration.meanFillSeconds
                )
            }

            // M16 only improves a stale BUY when L2 says the current passive price has
            // weak fill odds AND stepping inward is not strongly adverse. L2 is an
            // aggregated heuristic, never represented as exact Kraken queue position.
            val target = micro?.makerTargetPrice
                ?.takeIf { it > BigDecimal.ZERO && it < ticker.ask }
                ?: ticker.bid
            val priceImprovement = target.subtract(order.price)
            val microAllowsReprice = micro?.let {
                it.valid &&
                    it.makerFillProbability < 0.60 &&
                    it.adverseSelectionRisk < 0.65
            } ?: false
            val changedByTick =
                target > BigDecimal.ZERO &&
                    priceImprovement >= tick &&
                    microAllowsReprice

            val action = SmartOrderLifecyclePolicy.action(
                side = order.side,
                orderType = order.orderType,
                remainingQuantity = order.remainingQuantity,
                ageSeconds = age,
                effectiveStaleSeconds = effectiveStale,
                amendmentsAlready = amendments,
                targetPriceChangedByAtLeastOneTick = changedByTick
            )

            when (action) {
                SmartOrderLifecycleAction.HOLD -> Unit

                SmartOrderLifecycleAction.AMEND -> {
                    val amendResult = runCatching {
                        exchange.amendOrder(
                            AtomicOrderAmendRequest(
                                exchangeOrderId = order.exchangeOrderId,
                                clientOrderId = order.clientOrderId,
                                symbol = order.symbol,
                                side = order.side,
                                orderType = order.orderType,
                                newLimitPrice = target,
                                postOnly = true,
                                deadline = Instant.now().plusSeconds(5)
                            )
                        )
                    }
                    if (amendResult.isFailure) {
                        val error = amendResult.exceptionOrNull()
                        events += SmartOrderLifecycleEvent(
                            "WARN",
                            "Atomic amend failed for ${order.symbol}/${order.exchangeOrderId}: ${error?.message}. Order remains authoritative on exchange; no replacement order was submitted."
                        )
                        continue
                    }
                    val result = amendResult.getOrThrow()

                    if (result.supported && result.amended) {
                        incrementOrderAmendments(order)
                        incrementCalibrationCounter(order.symbol, "amends")
                        val microText = micro?.let {
                            "fillHeuristic=${"%.3f".format(it.makerFillProbability)}, adverse=${"%.3f".format(it.adverseSelectionRisk)}, spread=${"%.2f".format(it.spreadBps)}bps, imbalance=${"%.3f".format(it.bookImbalance)}, pressure=${"%.2f".format(it.microPricePressureBps)}bps"
                        } ?: "microstructure=unavailable"
                        events += SmartOrderLifecycleEvent(
                            "LIVE",
                            "Atomic amend ${result.amendId}: ${order.symbol} BUY kept ids txid=${order.exchangeOrderId}, cl_ord_id=${order.clientOrderId}; price ${order.price.stripTrailingZeros().toPlainString()} -> ${target.stripTrailingZeros().toPlainString()}, postOnly=true, age=${age}s, adaptiveStale=${effectiveStale}s, predictedFill=${predictedFillSeconds(settings, calibration)}s, $microText."
                        )
                    } else {
                        events += SmartOrderLifecycleEvent(
                            "WARN",
                            "Atomic amend unavailable/rejected for ${order.symbol}: ${result.reason}. Existing order left untouched."
                        )
                    }
                }

                SmartOrderLifecycleAction.CANCEL -> {
                    val cancelled = runCatching { exchange.cancelOrder(order.exchangeOrderId) }.getOrDefault(false)
                    if (cancelled) {
                        incrementCalibrationCounter(order.symbol, "cancels")
                        events += SmartOrderLifecycleEvent(
                            "LIVE",
                            "Hard-timeout cancel ${order.symbol} BUY ${order.exchangeOrderId} after ${age}s and $amendments atomic amend(s). No automatic replacement was submitted because the original signal is now stale."
                        )
                    } else {
                        events += SmartOrderLifecycleEvent(
                            "WARN",
                            "Hard-timeout cancel failed for ${order.symbol}/${order.exchangeOrderId}; authoritative exchange state will be checked again next cycle."
                        )
                    }
                }
            }
        }

        return events
    }

    fun calibration(symbol: String): ExecutionCalibrationSnapshot {
        val key = symbolKey(symbol)
        return ExecutionCalibrationSnapshot(
            samples = prefs.getInt("${key}_samples", 0),
            meanFillSeconds = prefs.getString("${key}_mean_fill_sec", "0")?.toDoubleOrNull() ?: 0.0,
            meanSlippageBps = prefs.getString("${key}_mean_slippage_bps", "0")?.toDoubleOrNull() ?: 0.0,
            totalAmendments = prefs.getLong("${key}_amends", 0L),
            totalCancels = prefs.getLong("${key}_cancels", 0L)
        )
    }

    private suspend fun harvestClosedOrders(
        exchange: CryptoExchangeClient,
        events: MutableList<SmartOrderLifecycleEvent>
    ) {
        val active = activeOrderKeys().toMutableSet()
        if (active.isEmpty()) return

        val closed = runCatching { exchange.getClosedOrders(100) }.getOrNull() ?: return
        if (closed.isEmpty()) return

        for (stateKey in active.toList()) {
            val exchangeOrderId = prefs.getString("${stateKey}_exchange_id", "").orEmpty()
            val clientOrderId = prefs.getString("${stateKey}_client_id", "").orEmpty()
            val match = closed.firstOrNull {
                (exchangeOrderId.isNotBlank() && it.exchangeOrderId == exchangeOrderId) ||
                    (clientOrderId.isNotBlank() && it.clientOrderId == clientOrderId)
            } ?: continue

            val firstSeenMs = prefs.getLong("${stateKey}_first_seen_ms", 0L)
            val plannedPrice = prefs.getString("${stateKey}_planned_price", "0")
                ?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val side = runCatching {
                OrderSide.valueOf(prefs.getString("${stateKey}_side", OrderSide.BUY.name).orEmpty())
            }.getOrDefault(OrderSide.BUY)
            val symbol = prefs.getString("${stateKey}_symbol", match.symbol).orEmpty().ifBlank { match.symbol }

            val fillRatio = if (match.quantity > BigDecimal.ZERO) {
                match.executedQuantity.divide(match.quantity, 8, RoundingMode.HALF_UP)
            } else BigDecimal.ZERO

            if (firstSeenMs > 0L && match.executedQuantity > BigDecimal.ZERO && fillRatio >= BigDecimal("0.98")) {
                val closedMs = match.closedAtEpochSeconds * 1000L
                val fillSeconds = ((closedMs - firstSeenMs).coerceAtLeast(0L) / 1000.0)
                val slippage = ExecutionCalibrationMath.slippageBps(side, plannedPrice, match.price)
                val before = calibration(symbol)
                val afterSamples = before.samples + 1
                val afterMeanFill = ExecutionCalibrationMath.nextMean(
                    before.samples,
                    before.meanFillSeconds,
                    fillSeconds
                )
                val afterMeanSlippage = ExecutionCalibrationMath.nextMean(
                    before.samples,
                    before.meanSlippageBps,
                    slippage
                )
                val key = symbolKey(symbol)
                prefs.edit()
                    .putInt("${key}_samples", afterSamples)
                    .putString("${key}_mean_fill_sec", afterMeanFill.toString())
                    .putString("${key}_mean_slippage_bps", afterMeanSlippage.toString())
                    .apply()

                events += SmartOrderLifecycleEvent(
                    "INFO",
                    "Execution calibration ${symbol}: actualFill=${"%.1f".format(fillSeconds)}s, slippage=${"%.2f".format(slippage)}bps, samples=$afterSamples, learnedMeanFill=${"%.1f".format(afterMeanFill)}s, learnedMeanSlippage=${"%.2f".format(afterMeanSlippage)}bps."
                )
            }

            removeOrderState(stateKey)
            active.remove(stateKey)
        }

        saveActiveOrderKeys(active)
    }

    private fun rememberOpenOrder(order: LiveOrderInfo, nowMs: Long) {
        val stateKey = stateKey(order)
        val active = activeOrderKeys().toMutableSet()
        if (stateKey !in active) {
            val observedOpenMs = if (order.openedAtEpochSeconds > 0L) {
                order.openedAtEpochSeconds * 1000L
            } else nowMs
            prefs.edit()
                .putLong("${stateKey}_first_seen_ms", observedOpenMs)
                .putString("${stateKey}_planned_price", order.price.toPlainString())
                .putString("${stateKey}_side", order.side.name)
                .putString("${stateKey}_symbol", order.symbol)
                .putString("${stateKey}_exchange_id", order.exchangeOrderId)
                .putString("${stateKey}_client_id", order.clientOrderId)
                .putInt("${stateKey}_amends", 0)
                .apply()
            active += stateKey
            saveActiveOrderKeys(active)
        }
    }

    private fun orderAmendments(order: LiveOrderInfo): Int =
        prefs.getInt("${stateKey(order)}_amends", 0)

    private fun incrementOrderAmendments(order: LiveOrderInfo) {
        val key = "${stateKey(order)}_amends"
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    private fun incrementCalibrationCounter(symbol: String, kind: String) {
        val key = "${symbolKey(symbol)}_$kind"
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + 1L).apply()
    }

    private fun predictedFillSeconds(
        settings: BotSettings,
        calibration: ExecutionCalibrationSnapshot
    ): Long =
        if (calibration.samples >= 3 && calibration.meanFillSeconds > 0.0) {
            calibration.meanFillSeconds.roundToLong().coerceAtLeast(1L)
        } else settings.staleOrderTimeoutSeconds.coerceAtLeast(15L)

    private fun stateKey(order: LiveOrderInfo): String {
        val identity = order.clientOrderId.ifBlank { order.exchangeOrderId }
        return "order_" + identity.replace(Regex("[^A-Za-z0-9]"), "_").take(96)
    }

    private fun symbolKey(symbol: String): String =
        "symbol_" + symbol.uppercase().replace(Regex("[^A-Z0-9]"), "_").take(40)

    private fun activeOrderKeys(): Set<String> =
        prefs.getString("active_order_keys", "")
            .orEmpty()
            .split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(128)
            .toSet()

    private fun saveActiveOrderKeys(keys: Set<String>) {
        prefs.edit().putString("active_order_keys", keys.take(128).joinToString("|")).apply()
    }

    private fun removeOrderState(stateKey: String) {
        prefs.edit()
            .remove("${stateKey}_first_seen_ms")
            .remove("${stateKey}_planned_price")
            .remove("${stateKey}_side")
            .remove("${stateKey}_symbol")
            .remove("${stateKey}_exchange_id")
            .remove("${stateKey}_client_id")
            .remove("${stateKey}_amends")
            .apply()
    }
}
