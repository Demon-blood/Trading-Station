package com.ksp.cryptobot.execution

import com.ksp.cryptobot.core.OrderBookLevel
import com.ksp.cryptobot.core.OrderBookSnapshot
import com.ksp.cryptobot.core.OrderSide
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

data class MarketMicrostructureSnapshot(
    val symbol: String,
    val valid: Boolean,
    val bestBid: BigDecimal = BigDecimal.ZERO,
    val bestAsk: BigDecimal = BigDecimal.ZERO,
    val midpoint: BigDecimal = BigDecimal.ZERO,
    val spreadBps: Double = 0.0,
    /** Quote-notional imbalance in [-1,+1]. Positive means more visible bid depth. */
    val bookImbalance: Double = 0.0,
    val microPrice: BigDecimal = BigDecimal.ZERO,
    /** Positive means microprice is above midpoint; negative means below. */
    val microPricePressureBps: Double = 0.0,
    val top5BidDepthQuote: BigDecimal = BigDecimal.ZERO,
    val top5AskDepthQuote: BigDecimal = BigDecimal.ZERO,
    val top10BidDepthQuote: BigDecimal = BigDecimal.ZERO,
    val top10AskDepthQuote: BigDecimal = BigDecimal.ZERO,
    /** Market-order impact relative to same-side touch, in basis points. */
    val marketImpactBps: Double = 0.0,
    val marketImpactComplete: Boolean = false,
    /**
     * Heuristic probability in [0,1] of a passive order filling inside the supplied
     * horizon. This is NOT exchange queue position and is not represented as such.
     */
    val makerFillProbability: Double = 0.0,
    /** Heuristic [0,1] risk that a passive fill occurs into unfavorable book pressure. */
    val adverseSelectionRisk: Double = 0.0,
    /** Passive target; it never intentionally crosses the opposite touch. */
    val makerTargetPrice: BigDecimal = BigDecimal.ZERO,
    val reason: String = ""
)

object MarketMicrostructureRuntime {
    private val snapshots = ConcurrentHashMap<String, MarketMicrostructureSnapshot>()

    fun publish(snapshot: MarketMicrostructureSnapshot) {
        snapshots[snapshot.symbol.uppercase()] = snapshot
    }

    fun snapshot(symbol: String): MarketMicrostructureSnapshot? =
        snapshots[symbol.uppercase()]

    fun all(): List<MarketMicrostructureSnapshot> = snapshots.values.toList()
}

class MarketMicrostructureEngine {
    fun evaluate(
        orderBook: OrderBookSnapshot,
        side: OrderSide,
        requestedQuote: BigDecimal,
        workingPrice: BigDecimal? = null,
        tickSize: BigDecimal = BigDecimal.ZERO,
        fillHorizonSeconds: Long = 90L,
        calibrationSamples: Int = 0,
        calibratedMeanFillSeconds: Double = 0.0
    ): MarketMicrostructureSnapshot {
        val bids = sanitizeBids(orderBook.bids)
        val asks = sanitizeAsks(orderBook.asks)
        if (bids.isEmpty() || asks.isEmpty()) {
            return invalid(orderBook.symbol, "order book has no positive bid/ask levels")
        }

        val bestBid = bids.first().price
        val bestAsk = asks.first().price
        if (bestBid <= BigDecimal.ZERO || bestAsk <= BigDecimal.ZERO || bestBid >= bestAsk) {
            return invalid(
                orderBook.symbol,
                "crossed/invalid L2 book: bestBid=$bestBid bestAsk=$bestAsk"
            )
        }

        val midpoint = bestBid.add(bestAsk).divide(BigDecimal("2"), 16, RoundingMode.HALF_UP)
        val spreadBps = bestAsk.subtract(bestBid)
            .divide(midpoint, 16, RoundingMode.HALF_UP)
            .multiply(BigDecimal("10000"))
            .toDouble()

        val top5Bid = quoteDepth(bids, 5)
        val top5Ask = quoteDepth(asks, 5)
        val top10Bid = quoteDepth(bids, 10)
        val top10Ask = quoteDepth(asks, 10)
        val top5Total = top5Bid.add(top5Ask)
        val imbalance = if (top5Total > BigDecimal.ZERO) {
            top5Bid.subtract(top5Ask)
                .divide(top5Total, 16, RoundingMode.HALF_UP)
                .toDouble()
                .coerceIn(-1.0, 1.0)
        } else 0.0

        // Standard top-of-book microprice proxy using opposite-side size weighting.
        val bidQty = bids.first().quantity
        val askQty = asks.first().quantity
        val topQty = bidQty.add(askQty)
        val microPrice = if (topQty > BigDecimal.ZERO) {
            bestAsk.multiply(bidQty)
                .add(bestBid.multiply(askQty))
                .divide(topQty, 16, RoundingMode.HALF_UP)
        } else midpoint

        val pressureBps = microPrice.subtract(midpoint)
            .divide(midpoint, 16, RoundingMode.HALF_UP)
            .multiply(BigDecimal("10000"))
            .toDouble()

        val impact = marketImpact(
            levels = if (side == OrderSide.BUY) asks else bids,
            side = side,
            requestedQuote = requestedQuote,
            touch = if (side == OrderSide.BUY) bestAsk else bestBid
        )

        val fillProbability = makerFillProbability(
            side = side,
            workingPrice = workingPrice,
            bestBid = bestBid,
            bestAsk = bestAsk,
            midpoint = midpoint,
            spreadBps = spreadBps,
            imbalance = imbalance,
            fillHorizonSeconds = fillHorizonSeconds,
            calibrationSamples = calibrationSamples,
            calibratedMeanFillSeconds = calibratedMeanFillSeconds
        )
        val adverseSelection = adverseSelectionRisk(
            side = side,
            pressureBps = pressureBps,
            spreadBps = spreadBps,
            imbalance = imbalance
        )
        val makerTarget = makerTarget(
            side = side,
            bestBid = bestBid,
            bestAsk = bestAsk,
            tickSize = tickSize,
            fillProbability = fillProbability,
            adverseSelectionRisk = adverseSelection
        )

        val result = MarketMicrostructureSnapshot(
            symbol = orderBook.symbol,
            valid = true,
            bestBid = bestBid,
            bestAsk = bestAsk,
            midpoint = midpoint,
            spreadBps = spreadBps,
            bookImbalance = imbalance,
            microPrice = microPrice,
            microPricePressureBps = pressureBps,
            top5BidDepthQuote = top5Bid,
            top5AskDepthQuote = top5Ask,
            top10BidDepthQuote = top10Bid,
            top10AskDepthQuote = top10Ask,
            marketImpactBps = impact.first,
            marketImpactComplete = impact.second,
            makerFillProbability = fillProbability,
            adverseSelectionRisk = adverseSelection,
            makerTargetPrice = makerTarget,
            reason = buildString {
                append("L2 microstructure: spread=")
                append("%.2f".format(spreadBps))
                append("bps, imbalance=")
                append("%.3f".format(imbalance))
                append(", microPressure=")
                append("%.2f".format(pressureBps))
                append("bps, impact=")
                append(if (impact.second) "%.2f".format(impact.first) else "INCOMPLETE")
                append("bps, makerFillHeuristic=")
                append("%.3f".format(fillProbability))
                append(", adverseSelection=")
                append("%.3f".format(adverseSelection))
                append(". L2 is aggregated depth, not exact queue position.")
            }
        )
        MarketMicrostructureRuntime.publish(result)
        return result
    }

    private fun sanitizeBids(levels: List<OrderBookLevel>): List<OrderBookLevel> =
        levels.asSequence()
            .filter { it.price > BigDecimal.ZERO && it.quantity > BigDecimal.ZERO }
            .sortedByDescending { it.price }
            .toList()

    private fun sanitizeAsks(levels: List<OrderBookLevel>): List<OrderBookLevel> =
        levels.asSequence()
            .filter { it.price > BigDecimal.ZERO && it.quantity > BigDecimal.ZERO }
            .sortedBy { it.price }
            .toList()

    private fun quoteDepth(levels: List<OrderBookLevel>, depth: Int): BigDecimal =
        levels.take(depth).fold(BigDecimal.ZERO) { acc, level ->
            acc.add(level.price.multiply(level.quantity))
        }

    private fun marketImpact(
        levels: List<OrderBookLevel>,
        side: OrderSide,
        requestedQuote: BigDecimal,
        touch: BigDecimal
    ): Pair<Double, Boolean> {
        if (requestedQuote <= BigDecimal.ZERO || touch <= BigDecimal.ZERO) return 0.0 to true

        var remaining = requestedQuote
        var spentQuote = BigDecimal.ZERO
        var acquiredBase = BigDecimal.ZERO

        for (level in levels) {
            if (remaining <= BigDecimal.ZERO) break
            val capacityQuote = level.price.multiply(level.quantity)
            if (capacityQuote <= BigDecimal.ZERO) continue
            val usedQuote = if (remaining < capacityQuote) remaining else capacityQuote
            val base = usedQuote.divide(level.price, 16, RoundingMode.HALF_UP)
            spentQuote = spentQuote.add(usedQuote)
            acquiredBase = acquiredBase.add(base)
            remaining = remaining.subtract(usedQuote)
        }

        if (acquiredBase <= BigDecimal.ZERO) return 99_999.0 to false
        val vwap = spentQuote.divide(acquiredBase, 16, RoundingMode.HALF_UP)
        val rawImpact = when (side) {
            OrderSide.BUY -> vwap.subtract(touch)
            OrderSide.SELL -> touch.subtract(vwap)
        }
        val impactBps = rawImpact
            .max(BigDecimal.ZERO)
            .divide(touch, 16, RoundingMode.HALF_UP)
            .multiply(BigDecimal("10000"))
            .toDouble()
        val complete = remaining <= BigDecimal("0.00000001")
        return (if (complete) impactBps else max(impactBps, 99_999.0)) to complete
    }

    private fun makerFillProbability(
        side: OrderSide,
        workingPrice: BigDecimal?,
        bestBid: BigDecimal,
        bestAsk: BigDecimal,
        midpoint: BigDecimal,
        spreadBps: Double,
        imbalance: Double,
        fillHorizonSeconds: Long,
        calibrationSamples: Int,
        calibratedMeanFillSeconds: Double
    ): Double {
        val touch = if (side == OrderSide.BUY) bestBid else bestAsk
        val price = workingPrice?.takeIf { it > BigDecimal.ZERO } ?: touch
        val distanceBps = price.subtract(touch).abs()
            .divide(midpoint, 16, RoundingMode.HALF_UP)
            .multiply(BigDecimal("10000"))
            .toDouble()

        val placementScale = max(spreadBps * 2.0, 5.0)
        val placementScore = (1.0 - distanceBps / placementScale).coerceIn(0.05, 1.0)

        // L2 depth is only a proxy for likely opposing liquidity; it is not order flow.
        val opposingPressure = when (side) {
            OrderSide.BUY -> ((1.0 - imbalance) / 2.0)
            OrderSide.SELL -> ((1.0 + imbalance) / 2.0)
        }.coerceIn(0.0, 1.0)

        val calibrationScore = if (
            calibrationSamples >= 3 &&
            calibratedMeanFillSeconds.isFinite() &&
            calibratedMeanFillSeconds > 0.0
        ) {
            val horizon = fillHorizonSeconds.coerceAtLeast(1L).toDouble()
            (horizon / (horizon + calibratedMeanFillSeconds)).coerceIn(0.10, 0.90)
        } else 0.50

        val spreadScore = (1.0 - spreadBps / 100.0).coerceIn(0.20, 1.0)

        val governanceOffset =
            com.ksp.cryptobot.governance.LearningGovernanceRuntime
                .snapshot()
                .bounds
                .fillProbabilityOffset
                .coerceIn(-0.08, 0.0)

        return (
            0.10 +
                0.35 * placementScore +
                0.25 * opposingPressure +
                0.20 * calibrationScore +
                0.10 * spreadScore +
                governanceOffset
            ).coerceIn(0.02, 0.98)
    }

    private fun adverseSelectionRisk(
        side: OrderSide,
        pressureBps: Double,
        spreadBps: Double,
        imbalance: Double
    ): Double {
        val adversePressure = when (side) {
            OrderSide.BUY -> max(0.0, -pressureBps)
            OrderSide.SELL -> max(0.0, pressureBps)
        }
        val directional = (
            adversePressure / max(spreadBps / 2.0, 1.0)
            ).coerceIn(0.0, 1.0)
        val adverseImbalance = when (side) {
            OrderSide.BUY -> max(0.0, -imbalance)
            OrderSide.SELL -> max(0.0, imbalance)
        }.coerceIn(0.0, 1.0)
        return (0.65 * directional + 0.35 * adverseImbalance).coerceIn(0.0, 1.0)
    }

    private fun makerTarget(
        side: OrderSide,
        bestBid: BigDecimal,
        bestAsk: BigDecimal,
        tickSize: BigDecimal,
        fillProbability: Double,
        adverseSelectionRisk: Double
    ): BigDecimal {
        if (tickSize <= BigDecimal.ZERO) {
            return if (side == OrderSide.BUY) bestBid else bestAsk
        }
        val spread = bestAsk.subtract(bestBid)
        if (spread <= tickSize.multiply(BigDecimal("2"))) {
            return if (side == OrderSide.BUY) bestBid else bestAsk
        }

        val shouldImproveInside =
            fillProbability < 0.55 && adverseSelectionRisk < 0.60

        if (!shouldImproveInside) {
            return if (side == OrderSide.BUY) bestBid else bestAsk
        }

        return when (side) {
            OrderSide.BUY -> bestBid.add(tickSize).min(bestAsk.subtract(tickSize))
            OrderSide.SELL -> bestAsk.subtract(tickSize).max(bestBid.add(tickSize))
        }
    }

    private fun invalid(symbol: String, reason: String): MarketMicrostructureSnapshot {
        val result = MarketMicrostructureSnapshot(
            symbol = symbol,
            valid = false,
            marketImpactBps = 99_999.0,
            marketImpactComplete = false,
            adverseSelectionRisk = 1.0,
            reason = reason
        )
        MarketMicrostructureRuntime.publish(result)
        return result
    }
}
