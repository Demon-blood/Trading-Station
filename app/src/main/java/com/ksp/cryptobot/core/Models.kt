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
    val coinGeckoVsKrakenDeviationBlockPercent: BigDecimal = BigDecimal("1.75")
) {
    fun symbols(): List<String> = symbolsCsv.split(',').map { it.trim().uppercase() }.filter { it.isNotBlank() }
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
