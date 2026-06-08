package com.ksp.cryptobot.core

import java.math.BigDecimal
import java.time.Instant

enum class BotMode { PAPER, LIVE_CONFIRM, LIVE_AUTO }
enum class ExchangeProvider { PAPER, BINANCE_READ_ONLY, KRAKEN, COINBASE_ADVANCED, BITVAVO, MANUAL }
enum class SignalAction { STRONG_AVOID, AVOID, WAIT, WATCH, SMALL_BUY, BUY, SELL }
enum class OrderSide { BUY, SELL }
enum class OrderType { LIMIT, MARKET, STOP_LOSS, TAKE_PROFIT }
enum class DecisionSource { TECHNICAL, NEWS, TRADE_MEMORY, COMBINED_AI }
enum class Timeframe(val binanceInterval: String) { M1("1m"), M5("5m"), M15("15m"), H1("1h"), H4("4h") }
enum class StrategyMode { AUTO, SCALPING, TREND, BREAKOUT, REVERSAL, NEWS_MOMENTUM }
enum class MarketRegime { TRENDING_UP, TRENDING_DOWN, SIDEWAYS, HIGH_VOLATILITY, LOW_VOLATILITY, NEWS_DRIVEN, RISK_OFF }
enum class AutomationLockReason { NONE, PAPER_TEST_REQUIRED, DAILY_LOSS, WEEKLY_LOSS, DRAWDOWN, API_UNSAFE, BATTERY_UNSAFE, NETWORK_UNSTABLE, TAX_GUARD, MANUAL_LOCK }
enum class OrderManagementMode { SIMPLE_LIMIT, SMART_LIMIT, SMART_LIMIT_WITH_TRAILING, SPLIT_TAKE_PROFIT }

data class MarketTicker(
    val symbol: String,
    val lastPrice: BigDecimal,
    val bid: BigDecimal,
    val ask: BigDecimal,
    val volume24h: BigDecimal,
    val priceChangePercent24h: BigDecimal,
    val timestamp: Instant = Instant.now()
)

data class BotSettings(
    val mode: BotMode = BotMode.PAPER,
    val maxPositionEur: BigDecimal = BigDecimal("25.00"),
    val maxDailyLossEur: BigDecimal = BigDecimal("15.00"),
    val maxTradesPerDay: Int = 4,
    val maxSpreadPercent: BigDecimal = BigDecimal("0.35"),
    val minVolume24hEur: BigDecimal = BigDecimal("1000000"),
    val scanIntervalSeconds: Long = 60,
    val taxOptimization: Boolean = true,
    val tradeOnlyBtcEth: Boolean = false,
    val liveTradingAcknowledged: Boolean = false,
    val useNewsAi: Boolean = true,
    val useTradeMemoryAi: Boolean = true,
    val symbolsCsv: String = "BTCEUR,ETHEUR",
    val recoveredScalpingStrategyEnabled: Boolean = true,
    val emaFastPeriod: Int = 9,
    val emaSlowPeriod: Int = 21,
    val obvLookback: Int = 20,
    val atrPeriod: Int = 14,
    val minStrategyScoreToBuy: Int = 72,
    val minTrendAgreement: Int = 2,
    val takeProfitAtrMultiplier: BigDecimal = BigDecimal("1.4"),
    val stopLossAtrMultiplier: BigDecimal = BigDecimal("1.0"),
    val strategyMode: StrategyMode = StrategyMode.AUTO,
    val autoSelectStrategy: Boolean = true,
    val enableBacktestGate: Boolean = true,
    val enableForwardTestGate: Boolean = true,
    val requiredPaperTrades: Int = 50,
    val requiredPaperWinRatePercent: Int = 55,
    val requiredProfitFactor: BigDecimal = BigDecimal("1.20"),
    val maxDrawdownPercent: BigDecimal = BigDecimal("10.0"),
    val maxWeeklyLossEur: BigDecimal = BigDecimal("45.00"),
    val maxOpenPositions: Int = 3,
    val maxCoinExposurePercent: BigDecimal = BigDecimal("45.0"),
    val lossCooldownMinutes: Int = 240,
    val winStreakCooldownMinutes: Int = 0,
    val enableTrailingStop: Boolean = true,
    val trailingStopAtrMultiplier: BigDecimal = BigDecimal("0.8"),
    val enableBreakEvenStop: Boolean = true,
    val enablePartialTakeProfit: Boolean = true,
    val partialTakeProfitPercent: BigDecimal = BigDecimal("50.0"),
    val staleOrderTimeoutSeconds: Long = 90,
    val smartLimitRequote: Boolean = true,
    val orderManagementMode: OrderManagementMode = OrderManagementMode.SPLIT_TAKE_PROFIT,
    val enableNewsSeverityFilter: Boolean = true,
    val highSeverityNewsBlockHours: Int = 12,
    val enableAutoSafeMode: Boolean = true,
    val exchangeProvider: ExchangeProvider = ExchangeProvider.PAPER,
    val manualExecutionMode: Boolean = false,
    /**
     * When true, live execution can use Kraken market orders. This is disabled by default
     * because market orders execute immediately and can suffer slippage in fast markets.
     */
    val enableMarketOrders: Boolean = false,
    val maxMarketOrderEur: BigDecimal = BigDecimal("25.00"),
    val marketOrderSlippageWarningPercent: BigDecimal = BigDecimal("0.75"),
    val liveLifecycleManagerEnabled: Boolean = true,
    val autoExitManagerEnabled: Boolean = true,
    val autoTakeProfitEnabled: Boolean = true,
    val autoStopLossEnabled: Boolean = true,
    val profitMaximizerEnabled: Boolean = true,
    val forceSellOnBearishSignal: Boolean = true,
    val takeProfitPercent: BigDecimal = BigDecimal("2.0"),
    val stopLossPercent: BigDecimal = BigDecimal("1.2"),
    val trailingActivationPercent: BigDecimal = BigDecimal("1.0"),
    val trailingDistancePercent: BigDecimal = BigDecimal("0.8"),
    val partialExitPercent: BigDecimal = BigDecimal("50.0"),
    val emergencySellAllOnRiskOff: Boolean = false,
    val syncKrakenHistory: Boolean = true,
    val exportTaxReportEnabled: Boolean = true,
    val useCoinGeckoIntelligence: Boolean = false,
    val coinGeckoVsKrakenDeviationBlockPercent: BigDecimal = BigDecimal("1.75"),

    // v1.1 full pro automation modules. Defaults are conservative and explainable.
    val enableKrakenWebSocketFeed: Boolean = true,
    val smartProfitLockEnabled: Boolean = true,
    val smartProfitLockActivationPercent: BigDecimal = BigDecimal("1.0"),
    val smartProfitLockTrailingDistancePercent: BigDecimal = BigDecimal("0.75"),
    val smartProfitLockPartialTakeProfitPercent: BigDecimal = BigDecimal("2.0"),
    val smartProfitLockPartialExitPercent: BigDecimal = BigDecimal("30.0"),
    val autoCompoundingEnabled: Boolean = true,
    val autoCompoundingMaxIncreasePercent: BigDecimal = BigDecimal("10.0"),
    val enableNetProfitFilter: Boolean = true,
    val saveWhyTradedExplanations: Boolean = true,
    val strategyOptimizerEnabled: Boolean = true,
    val portfolioBalancerEnabled: Boolean = true,
    val minimumEurReservePercent: BigDecimal = BigDecimal("15.0"),
    val maxSingleAssetAllocationPercent: BigDecimal = BigDecimal("45.0"),
    val watchdogEnabled: Boolean = true,
    val pauseBelowBatteryPercent: Int = 15,
    val telegramRemoteControlEnabled: Boolean = false,
    val discordRemoteControlEnabled: Boolean = false,
    val localMlScoringEnabled: Boolean = true,
    val dryRunMirrorModeEnabled: Boolean = true,
    val bearishAutoSellScore: Int = 45,

    // v1.3 live automation settings. These make the bot more self-directing while
    // keeping all live orders behind the existing exchange, balance and risk guards.
    val autonomousStrategyPerSymbolEnabled: Boolean = true,
    val selfOptimizationEnabled: Boolean = true,
    val autoDisableBadSymbolsEnabled: Boolean = false,
    val shadowPaperComparisonEnabled: Boolean = true,
    val tradeReplayEnabled: Boolean = true,
    val remoteCommandParserEnabled: Boolean = true,
    val belgianTaxExportEnabled: Boolean = true,
    val portfolioReserveManagerV12Enabled: Boolean = true,
    val crashRecoveryWatchdogV12Enabled: Boolean = true,
    val badSymbolDisableHours: Int = 48,
    val minSymbolWinRatePercent: Int = 40,
    val minSymbolProfitFactor: BigDecimal = BigDecimal("0.90"),
    val optimizerLookbackTrades: Int = 30,

    // v1.7 auto symbol discovery. When enabled, Kraken markets are discovered across
    // every available quote asset, validated, scored, ranked and automatically used
    // for rotation. Use ALL to scan every Kraken spot pair instead of EUR-only.
    val autoSymbolDiscoveryEnabled: Boolean = true,
    val autoSymbolQuoteAsset: String = "ALL",
    val autoSymbolCandidateLimit: Int = 250,
    val autoSymbolActiveLimit: Int = 20,
    val autoSymbolMaxSpreadPercent: BigDecimal = BigDecimal("1.00"),
    val autoSymbolMinVolume24hEur: BigDecimal = BigDecimal("50000"),
    val autoSymbolRefreshMinutes: Int = 240,
    val autoTradeMultipleSymbolsPerScan: Boolean = true,
    val maxSymbolsTradedPerScan: Int = 6,

    // v1.6.2 rotation safety controls. These keep full-universe scanning from
    // opening too many positions or spending the wrong quote asset.
    // EUR is the default primary cash/quote balance for Belgian Kraken users.
    // ALL still works as a wildcard, but EUR-only is safer unless you also hold USD/USDT/BTC/etc.
    val allowedQuoteAssetsCsv: String = "EUR",
    val maxNewTradesPerScan: Int = 2,
    val maxTradesPerHour: Int = 3,
    val maxSimultaneousLivePositions: Int = 3,
    val cooldownAfterBuyMinutes: Int = 15,
    val cooldownAfterSellMinutes: Int = 30,
    val cooldownAfterLossMinutes: Int = 120,
    val cooldownAfterOrderFailureMinutes: Int = 60,
    val minimumQuoteReserveAmount: BigDecimal = BigDecimal("10.00"),
    val minimumQuoteReservePercent: BigDecimal = BigDecimal("20.0"),
    val liquidityBlacklistEnabled: Boolean = true,
    val marketOrderHighLiquidityOnly: Boolean = true,
    val fallbackToLimitWhenMarketBlocked: Boolean = true,
    val liveVerificationPanelEnabled: Boolean = true,

    val nonEurQuoteBuyEnabled: Boolean = false,
    val maxNonEurQuoteSpendPercent: BigDecimal = BigDecimal("5.0"),

    // v1.7 true self-learning. These settings make learning persistent, bounded and explainable.
    val trueSelfLearningEnabled: Boolean = true,
    val selfLearningMinSamples: Int = 10,
    val selfLearningLookbackTrades: Int = 500,
    val selfLearningMaxScoreBoost: Int = 10,
    val selfLearningMaxScorePenalty: Int = 15,
    val selfLearningPositionSizingEnabled: Boolean = true,
    val selfLearningAutoDisableEnabled: Boolean = true,
    val selfLearningPaperAndLiveSeparated: Boolean = true,
    val selfLearningExplainEveryDecision: Boolean = true,

    // v1.7.1 adaptive multi-strategy learning. When enabled, the bot can learn
    // which strategy performs best per symbol and market context, then use that
    // strategy automatically instead of always relying on the static global mode.
    val adaptiveStrategyLearningEnabled: Boolean = true,
    val adaptiveStrategyMinSamples: Int = 8,
    val adaptiveStrategySwitchConfidencePercent: Int = 55,
    val adaptiveStrategyMaxScoreBoost: Int = 12,
    val adaptiveStrategyMaxScorePenalty: Int = 16,
    val adaptiveStrategyPreferSymbolProfile: Boolean = true,
    val adaptiveStrategyAllowLiveLearning: Boolean = true,
    val adaptiveStrategyAllowPaperLearning: Boolean = true,

    // v1.7.8 learned hold/profit continuation. This lets the bot learn when a
    // symbol tends to keep running after normal TP/trailing conditions. It can
    // defer profit-taking, but it never overrides hard stop-loss protection.
    val learnedHoldForProfitEnabled: Boolean = true,
    val learnedHoldMinSamples: Int = 8,
    val learnedHoldConfidenceThresholdPercent: Int = 60,
    val learnedHoldMinProfitPercent: BigDecimal = BigDecimal("0.80"),
    val learnedHoldMaxExtraHoldMinutes: Int = 180,
    val learnedHoldAllowTakeProfitDeferral: Boolean = true,
    val learnedHoldAllowTrailingDeferral: Boolean = true,
    val learnedHoldAllowBearishOverride: Boolean = false,

    // v1.7.9 spike/profit-cycle timing. This analyzes historical candle spikes
    // for the held symbol and can defer profit-taking when a move still looks
    // early, or sell/lock profit when the run looks exhausted. It never claims
    // to know the perfect top and never overrides hard stop-loss protection.
    val spikeProfitTimingEnabled: Boolean = true,
    val spikeTimingLookbackCandles: Int = 240,
    val spikeTimingPatternHorizonCandles: Int = 36,
    val spikeTimingPullbackWindowCandles: Int = 12,
    val spikeTimingMinPatternSamples: Int = 3,
    val spikeTimingHistoricalSpikeThresholdPercent: BigDecimal = BigDecimal("3.00"),
    val spikeTimingMinProfitPercent: BigDecimal = BigDecimal("0.80"),
    val spikeTimingHoldUntilProgressPercent: BigDecimal = BigDecimal("70.00"),
    val spikeTimingExhaustionProgressPercent: BigDecimal = BigDecimal("90.00"),
    val spikeTimingHoldConfidenceThresholdPercent: Int = 65,
    val spikeTimingSellConfidenceThresholdPercent: Int = 70,
    val spikeTimingTrailingFlexMultiplier: BigDecimal = BigDecimal("1.15"),
    val spikeTimingMinDynamicTrailPercent: BigDecimal = BigDecimal("0.60"),
    val spikeTimingMaxDynamicTrailPercent: BigDecimal = BigDecimal("4.00"),

    val taxExportYear: Int = 2026
) {
    fun symbols(): List<String> = symbolsCsv.split(',').map { it.trim().uppercase() }.filter { it.isNotBlank() }

    fun allowedQuoteAssets(): Set<String> = allowedQuoteAssetsCsv
        .split(',')
        .map { it.trim().uppercase() }
        .filter { it.isNotBlank() }
        .toSet()

    fun allQuoteAssetsAllowed(): Boolean = allowedQuoteAssets()
        .any { it == "ALL" || it == "*" || it == "ANY" }

    fun isQuoteAssetAllowed(quoteAsset: String): Boolean {
        val normalized = quoteAsset.trim().uppercase()
        if (normalized.isBlank()) return false
        val allowed = allowedQuoteAssets()
        return allowed.any { it == "ALL" || it == "*" || it == "ANY" } || normalized in allowed
    }
}

data class Recommendation(
    val symbol: String,
    val action: SignalAction,
    val score: Int,
    val riskPercent: BigDecimal,
    val taxWarning: String,
    val reason: String,
    val createdAt: Instant = Instant.now()
)

data class NewsArticle(
    val title: String,
    val description: String,
    val source: String,
    val url: String,
    val publishedAt: Instant? = null
)

data class AiDecision(
    val symbol: String,
    val finalAction: SignalAction,
    val finalScore: Int,
    val confidencePercent: Int,
    val technicalScore: Int,
    val newsScore: Int,
    val memoryScore: Int,
    val allowedToTrade: Boolean,
    val explanation: String,
    val source: DecisionSource = DecisionSource.COMBINED_AI,
    val createdAt: Instant = Instant.now()
)

data class OrderRequest(
    val symbol: String,
    val side: OrderSide,
    val quantity: BigDecimal,
    val limitPrice: BigDecimal? = null,
    val orderType: OrderType = OrderType.LIMIT,
    val clientOrderId: String = "ksp-${System.currentTimeMillis()}",
    val reduceOnly: Boolean = false,
    val purpose: String = "ENTRY"
)

data class OrderResult(
    val exchangeOrderId: String,
    val symbol: String,
    val side: OrderSide,
    val executedQuantity: BigDecimal,
    val averagePrice: BigDecimal,
    val fee: BigDecimal,
    val paper: Boolean,
    val timestamp: Instant = Instant.now()
)


data class Candle(
    val symbol: String,
    val timeframe: Timeframe,
    val openTimeEpochMs: Long,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal
)

data class StrategySignal(
    val symbol: String,
    val strategyScore: Int,
    val trendAgreement: Int,
    val emaScore: Int,
    val obvScore: Int,
    val atrRiskScore: Int,
    val momentumScore: Int,
    val suggestedTakeProfitPercent: BigDecimal,
    val suggestedStopLossPercent: BigDecimal,
    val action: SignalAction,
    val explanation: String
)


data class RegimeAnalysis(
    val symbol: String,
    val regime: MarketRegime,
    val confidencePercent: Int,
    val volatilityPercent: BigDecimal,
    val trendStrengthPercent: BigDecimal,
    val explanation: String
)

data class StrategyCandidate(
    val mode: StrategyMode,
    val score: Int,
    val action: SignalAction,
    val reason: String,
    val takeProfitPercent: BigDecimal,
    val stopLossPercent: BigDecimal
)

data class AutomationDecision(
    val symbol: String,
    val selectedStrategy: StrategyMode,
    val marketRegime: MarketRegime,
    val finalAction: SignalAction,
    val finalScore: Int,
    val positionSizeEur: BigDecimal,
    val takeProfitPercent: BigDecimal,
    val stopLossPercent: BigDecimal,
    val trailingStopPercent: BigDecimal,
    val allowed: Boolean,
    val lockReason: AutomationLockReason,
    val explanation: String
)

data class BacktestTrade(
    val symbol: String,
    val strategy: StrategyMode,
    val entryEpochMs: Long,
    val exitEpochMs: Long,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal,
    val side: OrderSide,
    val pnlPercent: BigDecimal,
    val exitReason: String
)

data class BacktestReport(
    val symbol: String,
    val strategy: StrategyMode,
    val timeframe: Timeframe,
    val trades: Int,
    val winRatePercent: BigDecimal,
    val profitFactor: BigDecimal,
    val maxDrawdownPercent: BigDecimal,
    val netReturnPercent: BigDecimal,
    val passedLiveGate: Boolean,
    val summary: String
)

data class NewsEventScore(
    val symbol: String,
    val sentimentScore: Int,
    val severityScore: Int,
    val sourceConfidence: Int,
    val duplicateAdjustedCount: Int,
    val blocksTrading: Boolean,
    val reason: String
)

data class AdvancedRiskState(
    val allowed: Boolean,
    val lockReason: AutomationLockReason,
    val dailyLossEur: BigDecimal,
    val weeklyLossEur: BigDecimal,
    val drawdownPercent: BigDecimal,
    val consecutiveLosses: Int,
    val openPositions: Int,
    val reason: String
)

data class ManagedOrderPlan(
    val entry: OrderRequest,
    val takeProfitOnePercent: BigDecimal,
    val takeProfitTwoPercent: BigDecimal,
    val stopLossPercent: BigDecimal,
    val trailingStopPercent: BigDecimal,
    val cancelAfterSeconds: Long,
    val partialTakeProfitPercent: BigDecimal,
    val explanation: String
)


data class BalanceInfo(
    val asset: String,
    val total: BigDecimal,
    val free: BigDecimal,
    val holdTrade: BigDecimal = BigDecimal.ZERO,
    val eurValue: BigDecimal = BigDecimal.ZERO
)



data class AutonomousSymbolAssessment(
    val symbol: String,
    val allowed: Boolean,
    val selectedStrategy: StrategyMode,
    val winRatePercent: BigDecimal,
    val profitFactor: BigDecimal,
    val disableReason: String,
    val optimizerHint: String
)

data class TradeReplaySnapshot(
    val symbol: String,
    val action: SignalAction,
    val score: Int,
    val reason: String,
    val mirrorExitComparison: String,
    val createdAt: Instant = Instant.now()
)

data class RemoteCommandResult(
    val accepted: Boolean,
    val command: String,
    val message: String
)

data class TaxExportSummary(
    val year: Int,
    val rowCount: Int,
    val realizedGainEur: BigDecimal,
    val csv: String
)

data class PortfolioSnapshot(
    val provider: ExchangeProvider,
    val totalValueEur: BigDecimal,
    val freeEur: BigDecimal,
    val assets: List<BalanceInfo>,
    val refreshedAt: Instant = Instant.now(),
    val warning: String = ""
)


data class ExchangeSymbolInfo(
    val requestedSymbol: String,
    val normalizedSymbol: String,
    val exchangePair: String,
    val altName: String,
    val baseAsset: String,
    val quoteAsset: String,
    val minOrderSize: BigDecimal,
    val priceDecimals: Int,
    val quantityDecimals: Int,
    val tradable: Boolean,
    val reason: String = ""
)

data class SymbolDiscoveryCandidate(
    val symbol: String,
    val exchangePair: String,
    val baseAsset: String,
    val quoteAsset: String,
    val tradable: Boolean,
    val minOrderSize: BigDecimal,
    val lastPrice: BigDecimal = BigDecimal.ZERO,
    val bid: BigDecimal = BigDecimal.ZERO,
    val ask: BigDecimal = BigDecimal.ZERO,
    val spreadPercent: BigDecimal = BigDecimal.ZERO,
    val volume24hEur: BigDecimal = BigDecimal.ZERO,
    val change24hPercent: BigDecimal = BigDecimal.ZERO,
    val score: Int = 0,
    val enabledForRotation: Boolean = false,
    val reason: String = ""
)

data class LiveOrderInfo(
    val exchangeOrderId: String,
    val symbol: String,
    val side: OrderSide,
    val orderType: OrderType,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val executedQuantity: BigDecimal,
    val remainingQuantity: BigDecimal,
    val status: String,
    val openedAtEpochSeconds: Long,
    val description: String = ""
)


data class ClosedOrderInfo(
    val exchangeOrderId: String,
    val symbol: String,
    val side: OrderSide,
    val orderType: OrderType,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val executedQuantity: BigDecimal,
    val fee: BigDecimal,
    val closedAtEpochSeconds: Long,
    val status: String,
    val description: String = ""
)

data class PositionInfo(
    val symbol: String,
    val baseAsset: String,
    val quantity: BigDecimal,
    val freeQuantity: BigDecimal,
    val entryPrice: BigDecimal,
    val currentPrice: BigDecimal,
    val highestPrice: BigDecimal,
    val unrealizedPnlEur: BigDecimal,
    val unrealizedPnlPercent: BigDecimal,
    val stopPrice: BigDecimal,
    val takeProfitPrice: BigDecimal,
    val trailingStopPrice: BigDecimal,
    val managed: Boolean,
    val reason: String
)

data class PerformanceSummary(
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val winRatePercent: BigDecimal,
    val realizedPnlEur: BigDecimal,
    val estimatedFeesEur: BigDecimal,
    val profitFactor: BigDecimal,
    val bestSymbol: String,
    val worstSymbol: String
)

data class LifecycleSnapshot(
    val positions: List<PositionInfo>,
    val openOrders: List<LiveOrderInfo>,
    val performance: PerformanceSummary,
    val messages: List<String>
)

data class TaxReportRow(
    val timestampEpochMs: Long,
    val symbol: String,
    val side: OrderSide,
    val quantity: BigDecimal,
    val priceEur: BigDecimal,
    val feeEur: BigDecimal,
    val realizedGainEur: BigDecimal,
    val note: String
)
