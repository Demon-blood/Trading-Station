package com.ksp.cryptobot.strategy

import com.ksp.cryptobot.core.MarketRegime
import com.ksp.cryptobot.core.StrategyMode
import com.ksp.cryptobot.core.Timeframe

enum class StrategyTruthAvailability {
    IMPLEMENTED,
    DATA_REQUIRED,
    ARCHITECTURE_REQUIRED,
    MULTITIMEFRAME_ONLY
}

data class StrategyTruthSpec(
    val mode: StrategyMode,
    val canonicalName: String,
    val availability: StrategyTruthAvailability,
    val liveSelectable: Boolean,
    val singleTimeframeBacktestable: Boolean,
    val primaryTimeframe: Timeframe?,
    val requiredInputs: List<String>,
    val entryDefinition: String,
    val confirmationDefinition: String,
    val invalidationDefinition: String,
    val exitDefinition: String,
    val suitableRegimes: Set<MarketRegime>,
    val unsuitableRegimes: Set<MarketRegime>,
    val executionNotes: String
)

/**
 * M18 strategy truth registry.
 *
 * A StrategyMode is allowed to compete in AUTO only if the app currently has the
 * data AND execution architecture required by the strategy name. A proxy that merely
 * resembles the strategy is not enough.
 */
object StrategyTruthRegistry {
    private val specs: Map<StrategyMode, StrategyTruthSpec> = listOf(
        StrategyTruthSpec(
            mode = StrategyMode.SCALPING,
            canonicalName = "Multi-timeframe EMA/OBV momentum scalper",
            availability = StrategyTruthAvailability.MULTITIMEFRAME_ONLY,
            liveSelectable = true,
            singleTimeframeBacktestable = false,
            primaryTimeframe = Timeframe.M15,
            requiredInputs = listOf("M5 candles", "M15 candles", "H1 candles", "EMA", "OBV", "ATR", "short-term momentum"),
            entryDefinition = "At least the configured number of M5/M15/H1 frames must agree bullish; EMA fast > EMA slow, OBV direction and short momentum contribute to the score.",
            confirmationDefinition = "Multi-timeframe trend agreement is mandatory; ATR is used to reject excessive volatility and size the technical stop/target.",
            invalidationDefinition = "Insufficient timeframe history, loss of required trend agreement, or extreme ATR risk.",
            exitDefinition = "Protective stop and target are ATR-derived; lifecycle risk controls remain authoritative.",
            suitableRegimes = setOf(MarketRegime.TRENDING_UP, MarketRegime.LOW_VOLATILITY, MarketRegime.SIDEWAYS),
            unsuitableRegimes = setOf(MarketRegime.TRENDING_DOWN, MarketRegime.RISK_OFF, MarketRegime.HIGH_VOLATILITY),
            executionNotes = "This is a defined custom scalper, not a claim that all scalping uses these rules. Single-timeframe backtests are truth-blocked."
        ),
        StrategyTruthSpec(
            StrategyMode.TREND,
            "EMA 21/55 trend continuation",
            StrategyTruthAvailability.IMPLEMENTED,
            true, true, Timeframe.H1,
            listOf("H1 OHLCV", "EMA21", "EMA55", "ATR14"),
            "Long only when close > EMA21 > EMA55 and short-horizon EMA/price slope is positive.",
            "Trend regime must not be bearish/risk-off; price must remain above the fast EMA.",
            "Close below EMA21 or EMA21 below EMA55.",
            "Exit on trend invalidation or deterministic protective stop/target.",
            setOf(MarketRegime.TRENDING_UP),
            setOf(MarketRegime.TRENDING_DOWN, MarketRegime.RISK_OFF),
            "Trend following accepts many small losses; M5 net-EV and M17 portfolio gates remain final."
        ),
        StrategyTruthSpec(
            StrategyMode.BREAKOUT,
            "20-bar resistance breakout with volume confirmation",
            StrategyTruthAvailability.IMPLEMENTED,
            true, true, Timeframe.M15,
            listOf("M15 OHLCV", "20-bar prior high", "20-bar mean volume", "ATR14"),
            "Close above the highest high of the previous 20 completed bars.",
            "Breakout bar volume >= 1.25x prior mean volume and close must finish in the upper half of its candle.",
            "Close returns below the prior breakout level or bearish/risk-off regime.",
            "Exit on failed breakout, ATR stop, or target.",
            setOf(MarketRegime.TRENDING_UP, MarketRegime.HIGH_VOLATILITY, MarketRegime.LOW_VOLATILITY),
            setOf(MarketRegime.RISK_OFF, MarketRegime.TRENDING_DOWN),
            "No intrabar look-ahead: channel excludes the current bar."
        ),
        StrategyTruthSpec(
            StrategyMode.REVERSAL,
            "Oversold bullish reversal confirmation",
            StrategyTruthAvailability.IMPLEMENTED,
            true, true, Timeframe.M15,
            listOf("M15 OHLCV", "RSI14", "Bollinger 20/2", "ATR14"),
            "Previous bar is oversold (RSI <=30) and at/below the lower Bollinger band.",
            "Current bar must be bullish and reclaim back inside the lower band; falling-trend regimes are rejected.",
            "A new closing low or renewed close below the lower band.",
            "Target mean reversion toward SMA20; protective stop remains below recent risk/ATR.",
            setOf(MarketRegime.SIDEWAYS, MarketRegime.LOW_VOLATILITY),
            setOf(MarketRegime.TRENDING_DOWN, MarketRegime.RISK_OFF, MarketRegime.HIGH_VOLATILITY),
            "An oversold reading alone is not an entry because RSI can remain oversold during strong trends."
        ),
        StrategyTruthSpec(
            StrategyMode.NEWS_MOMENTUM,
            "News-confirmed momentum",
            StrategyTruthAvailability.DATA_REQUIRED,
            false, false, null,
            listOf("timestamped news/event feed", "event sentiment/severity", "price response", "liquidity"),
            "Requires a fresh positive/negative catalyst and post-event price/volume confirmation.",
            "Event recency, source quality, severity and market response must all be available.",
            "Catalyst invalidated, stale, contradicted, or market response reverses.",
            "Event-decay/time stop, momentum invalidation, protective risk controls.",
            emptySet(),
            emptySet(),
            "The current MultiStrategyEngine receives no news-event object. 24h price change is not a news strategy and is therefore blocked."
        ),
        StrategyTruthSpec(
            StrategyMode.MEAN_REVERSION_RSI_BOLLINGER,
            "RSI + Bollinger mean reversion",
            StrategyTruthAvailability.IMPLEMENTED,
            true, true, Timeframe.M15,
            listOf("M15 OHLCV", "RSI14", "SMA20", "20-period standard deviation"),
            "Previous close below/touching lower 20/2 Bollinger band with RSI <=30.",
            "Current close re-enters the band and RSI turns upward; range-compatible regime required.",
            "Close makes a fresh lower-band breakdown or regime becomes directional bearish.",
            "Primary target is SMA20/band basis; stop remains volatility/risk based.",
            setOf(MarketRegime.SIDEWAYS, MarketRegime.LOW_VOLATILITY),
            setOf(MarketRegime.TRENDING_DOWN, MarketRegime.RISK_OFF),
            "Bollinger bands are confirmation/context, not an automatic buy merely because price touches a band."
        ),
        StrategyTruthSpec(
            StrategyMode.VWAP_PULLBACK,
            "Trend VWAP pullback and reclaim",
            StrategyTruthAvailability.IMPLEMENTED,
            true, true, Timeframe.M15,
            listOf("M15 OHLCV", "VWAP30", "EMA21", "EMA55", "volume"),
            "EMA21 > EMA55 trend; previous price trades at/below VWAP and current bar reclaims VWAP.",
            "Current low must test the VWAP zone and the close must finish above VWAP without a bearish regime.",
            "Close loses VWAP and trend EMA structure.",
            "Exit on VWAP/trend invalidation or protective target/stop.",
            setOf(MarketRegime.TRENDING_UP),
            setOf(MarketRegime.TRENDING_DOWN, MarketRegime.RISK_OFF),
            "VWAP is volume-weighted; zero-volume windows are invalid."
        ),
        StrategyTruthSpec(
            StrategyMode.DONCHIAN_BREAKOUT,
            "Turtle-style Donchian 20/10 breakout",
            StrategyTruthAvailability.IMPLEMENTED,
            true, true, Timeframe.H1,
            listOf("H1 OHLCV", "20-bar prior high", "10-bar prior low", "ATR14"),
            "Long when close exceeds the prior 20-bar Donchian high.",
            "Current bar is excluded from channel construction; bearish/risk-off regimes block entry.",
            "Close below prior 10-bar Donchian low.",
            "10-bar channel exit plus deterministic stop/target.",
            setOf(MarketRegime.TRENDING_UP, MarketRegime.HIGH_VOLATILITY),
            setOf(MarketRegime.TRENDING_DOWN, MarketRegime.RISK_OFF),
            "This implementation explicitly uses a 20-bar entry / 10-bar exit variant."
        ),
        StrategyTruthSpec(
            StrategyMode.RANGE_GRID,
            "Multi-level range grid",
            StrategyTruthAvailability.ARCHITECTURE_REQUIRED,
            false, false, null,
            listOf("validated range", "multiple resting bid levels", "multiple resting ask levels", "inventory/grid state", "re-centering policy"),
            "A real grid stages multiple orders across a defined range; a single near-low BUY is not a grid.",
            "Range stability, spacing, fee coverage and inventory constraints.",
            "Range breakout or inventory/risk limit breach.",
            "Repeated grid fills/offsetting exits and full range invalidation.",
            setOf(MarketRegime.SIDEWAYS, MarketRegime.LOW_VOLATILITY),
            setOf(MarketRegime.TRENDING_UP, MarketRegime.TRENDING_DOWN, MarketRegime.HIGH_VOLATILITY, MarketRegime.RISK_OFF),
            "Current single-entry StrategyCandidate architecture cannot truthfully execute a grid."
        ),
        StrategyTruthSpec(
            StrategyMode.MARKET_MAKING_IMBALANCE,
            "Order-book-imbalance market making",
            StrategyTruthAvailability.ARCHITECTURE_REQUIRED,
            false, false, null,
            listOf("fresh L2/L3 book", "two-sided quoting", "inventory state", "queue/fill model", "adverse-selection model"),
            "Simultaneously quote passive bid and offer around fair value/microprice with inventory skew.",
            "Quotes require fresh order-book evidence, spread economics and bounded adverse-selection risk.",
            "Stale book, one-sided toxic flow, inventory limit, spread collapse, or authority loss.",
            "Continuously amend/cancel both sides and flatten inventory under risk policy.",
            setOf(MarketRegime.SIDEWAYS, MarketRegime.LOW_VOLATILITY),
            setOf(MarketRegime.HIGH_VOLATILITY, MarketRegime.RISK_OFF),
            "M16 has L2 analytics, but the strategy engine is still one-sided. Market making requires a two-sided quote/inventory engine."
        ),
        StrategyTruthSpec(
            StrategyMode.FUNDING_NEWS_RISK_OFF,
            "Funding/news risk-off overlay",
            StrategyTruthAvailability.DATA_REQUIRED,
            false, false, null,
            listOf("funding rate/history", "derivatives positioning", "timestamped news/event severity", "spot response"),
            "Risk-off state requires actual funding/news/positioning evidence, not 24h spot return alone.",
            "Multiple independent risk signals or a defined high-severity event.",
            "Risk inputs normalize/expire.",
            "Reduce/exit exposure according to risk overlay; this is not a stand-alone alpha BUY strategy.",
            setOf(MarketRegime.RISK_OFF),
            emptySet(),
            "No funding-rate feed is supplied to MultiStrategyEngine today, so this mode is truth-blocked."
        ),
        StrategyTruthSpec(
            StrategyMode.PAIRS_RELATIVE_STRENGTH,
            "Cross-asset relative-strength rotation",
            StrategyTruthAvailability.DATA_REQUIRED,
            false, false, null,
            listOf("candidate return series", "benchmark/peer return series", "aligned timestamps", "spread/fee economics"),
            "Candidate strength must be measured relative to another asset or benchmark over the same horizon.",
            "Relative outperformance must persist and liquidity/cost gates must pass.",
            "Relative-strength spread mean-reverts or leadership reverses.",
            "Rotate/reduce when relative spread signal reverses.",
            emptySet(),
            emptySet(),
            "A symbol's own 24-candle return is absolute momentum, not pairs relative strength."
        ),
        StrategyTruthSpec(
            StrategyMode.DCA_CRASH_PROTECTION,
            "Stateful tranche DCA with crash guard",
            StrategyTruthAvailability.ARCHITECTURE_REQUIRED,
            false, false, null,
            listOf("scheduled/tranche state", "prior tranche prices", "capital budget", "crash/volatility guard", "maximum deployment"),
            "Deploy predefined tranches according to a durable schedule/price plan, not a one-off dip BUY.",
            "Each tranche must fit total capital/risk budget and crash guard.",
            "Crash guard, maximum deployment, or strategy suspension.",
            "DCA is position-building; exits follow the strategy's separate portfolio plan.",
            emptySet(),
            setOf(MarketRegime.RISK_OFF, MarketRegime.HIGH_VOLATILITY),
            "Current StrategyCandidate has no durable tranche schedule, so a dip score cannot be called DCA."
        ),
        StrategyTruthSpec(
            StrategyMode.MOMENTUM_SPIKE_CONTINUATION,
            "Volume-confirmed impulse continuation",
            StrategyTruthAvailability.IMPLEMENTED,
            true, true, Timeframe.M15,
            listOf("M15 OHLCV", "ATR14", "30-bar mean volume"),
            "Prior bar must be a genuine bullish impulse with large range/body and >=1.5x baseline volume.",
            "Current bar must hold above the impulse midpoint and close above the impulse high with non-collapsing volume.",
            "Close below impulse midpoint or risk-off regime.",
            "Momentum invalidation, ATR stop, or target.",
            setOf(MarketRegime.TRENDING_UP, MarketRegime.HIGH_VOLATILITY),
            setOf(MarketRegime.RISK_OFF, MarketRegime.TRENDING_DOWN),
            "Requires a separate impulse and continuation bar; one green high-volume candle alone is not continuation."
        ),
        StrategyTruthSpec(
            StrategyMode.VOLUME_ANOMALY_WHALE_MOVE,
            "Volume anomaly with whale attribution",
            StrategyTruthAvailability.DATA_REQUIRED,
            false, false, null,
            listOf("volume anomaly", "trade/order-flow identity or size distribution", "L2/L3 changes and/or on-chain/exchange-flow evidence"),
            "A volume anomaly may be detected from candles, but a 'whale move' requires evidence about unusually large flow/participants.",
            "Large-flow evidence must agree with direction and persist beyond one candle.",
            "Flow disappears/reverses or anomaly is explained by broad market activity.",
            "Exit on flow reversal/decay plus risk controls.",
            emptySet(),
            emptySet(),
            "Candle volume alone cannot truthfully attribute activity to whales, so this named strategy is blocked."
        )
    ).associateBy { it.mode }

    fun spec(mode: StrategyMode): StrategyTruthSpec? =
        if (mode == StrategyMode.AUTO) null else specs[mode]

    fun all(): List<StrategyTruthSpec> = specs.values.sortedBy { it.mode.name }

    fun autoSelectable(): List<StrategyMode> =
        specs.values.filter { it.liveSelectable }.map { it.mode }

    fun truthBlockedReason(mode: StrategyMode): String {
        val spec = spec(mode) ?: return "AUTO is a selector, not a stand-alone strategy."
        return "TRUTH_BLOCKED ${mode.name}: ${spec.availability}. Required=${spec.requiredInputs.joinToString(", ")}. ${spec.executionNotes}"
    }
}
