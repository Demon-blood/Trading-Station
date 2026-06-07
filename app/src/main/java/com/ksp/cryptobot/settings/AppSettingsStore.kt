package com.ksp.cryptobot.settings

import android.content.Context
import com.ksp.cryptobot.core.BotMode
import com.ksp.cryptobot.core.BotSettings
import com.ksp.cryptobot.core.StrategyMode
import com.ksp.cryptobot.core.ExchangeProvider
import com.ksp.cryptobot.core.OrderManagementMode
import com.ksp.cryptobot.security.SecureSettingsStore
import java.math.BigDecimal

class AppSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("bot_settings", Context.MODE_PRIVATE)
    private val secure = SecureSettingsStore(context)

    fun load(): BotSettings {
        return BotSettings(
            mode = BotMode.valueOf(prefs.getString("mode", BotMode.PAPER.name) ?: BotMode.PAPER.name),
            maxPositionEur = prefs.getString("max_position_eur", "25.00")!!.toBigDecimalOrNull() ?: BigDecimal("25.00"),
            maxDailyLossEur = prefs.getString("max_daily_loss_eur", "15.00")!!.toBigDecimalOrNull() ?: BigDecimal("15.00"),
            maxTradesPerDay = prefs.getInt("max_trades_per_day", 4),
            maxSpreadPercent = prefs.getString("max_spread_percent", "0.35")!!.toBigDecimalOrNull() ?: BigDecimal("0.35"),
            minVolume24hEur = prefs.getString("min_volume_24h_eur", "1000000")!!.toBigDecimalOrNull() ?: BigDecimal("1000000"),
            scanIntervalSeconds = prefs.getLong("scan_interval_seconds", 60L),
            taxOptimization = prefs.getBoolean("tax_optimization", true),
            tradeOnlyBtcEth = prefs.getBoolean("trade_only_btc_eth", true),
            liveTradingAcknowledged = prefs.getBoolean("live_ack", false),
            useNewsAi = prefs.getBoolean("use_news_ai", true),
            useTradeMemoryAi = prefs.getBoolean("use_trade_memory_ai", true),
            symbolsCsv = prefs.getString("symbols_csv", "BTCEUR,ETHEUR") ?: "BTCEUR,ETHEUR",
            recoveredScalpingStrategyEnabled = prefs.getBoolean("recovered_scalping_strategy_enabled", true),
            emaFastPeriod = prefs.getInt("ema_fast_period", 9),
            emaSlowPeriod = prefs.getInt("ema_slow_period", 21),
            obvLookback = prefs.getInt("obv_lookback", 20),
            atrPeriod = prefs.getInt("atr_period", 14),
            minStrategyScoreToBuy = prefs.getInt("min_strategy_score_to_buy", 72),
            minTrendAgreement = prefs.getInt("min_trend_agreement", 2),
            takeProfitAtrMultiplier = prefs.getString("take_profit_atr_multiplier", "1.4")!!.toBigDecimalOrNull() ?: BigDecimal("1.4"),
            stopLossAtrMultiplier = prefs.getString("stop_loss_atr_multiplier", "1.0")!!.toBigDecimalOrNull() ?: BigDecimal("1.0"),
            strategyMode = StrategyMode.valueOf(prefs.getString("strategy_mode", StrategyMode.AUTO.name) ?: StrategyMode.AUTO.name),
            autoSelectStrategy = prefs.getBoolean("auto_select_strategy", true),
            enableBacktestGate = prefs.getBoolean("enable_backtest_gate", true),
            enableForwardTestGate = prefs.getBoolean("enable_forward_test_gate", true),
            requiredPaperTrades = prefs.getInt("required_paper_trades", 50),
            requiredPaperWinRatePercent = prefs.getInt("required_paper_win_rate_percent", 55),
            requiredProfitFactor = prefs.getString("required_profit_factor", "1.20")!!.toBigDecimalOrNull() ?: BigDecimal("1.20"),
            maxDrawdownPercent = prefs.getString("max_drawdown_percent", "10.0")!!.toBigDecimalOrNull() ?: BigDecimal("10.0"),
            maxWeeklyLossEur = prefs.getString("max_weekly_loss_eur", "45.00")!!.toBigDecimalOrNull() ?: BigDecimal("45.00"),
            maxOpenPositions = prefs.getInt("max_open_positions", 3),
            maxCoinExposurePercent = prefs.getString("max_coin_exposure_percent", "45.0")!!.toBigDecimalOrNull() ?: BigDecimal("45.0"),
            lossCooldownMinutes = prefs.getInt("loss_cooldown_minutes", 240),
            winStreakCooldownMinutes = prefs.getInt("win_streak_cooldown_minutes", 0),
            enableTrailingStop = prefs.getBoolean("enable_trailing_stop", true),
            trailingStopAtrMultiplier = prefs.getString("trailing_stop_atr_multiplier", "0.8")!!.toBigDecimalOrNull() ?: BigDecimal("0.8"),
            enableBreakEvenStop = prefs.getBoolean("enable_break_even_stop", true),
            enablePartialTakeProfit = prefs.getBoolean("enable_partial_take_profit", true),
            partialTakeProfitPercent = prefs.getString("partial_take_profit_percent", "50.0")!!.toBigDecimalOrNull() ?: BigDecimal("50.0"),
            staleOrderTimeoutSeconds = prefs.getLong("stale_order_timeout_seconds", 90L),
            smartLimitRequote = prefs.getBoolean("smart_limit_requote", true),
            orderManagementMode = OrderManagementMode.valueOf(prefs.getString("order_management_mode", OrderManagementMode.SPLIT_TAKE_PROFIT.name) ?: OrderManagementMode.SPLIT_TAKE_PROFIT.name),
            enableNewsSeverityFilter = prefs.getBoolean("enable_news_severity_filter", true),
            highSeverityNewsBlockHours = prefs.getInt("high_severity_news_block_hours", 12),
            enableAutoSafeMode = prefs.getBoolean("enable_auto_safe_mode", true),
            exchangeProvider = ExchangeProvider.valueOf(prefs.getString("exchange_provider", ExchangeProvider.PAPER.name) ?: ExchangeProvider.PAPER.name),
            manualExecutionMode = prefs.getBoolean("manual_execution_mode", false)
        )
    }

    fun save(settings: BotSettings) {
        prefs.edit()
            .putString("mode", settings.mode.name)
            .putString("max_position_eur", settings.maxPositionEur.toPlainString())
            .putString("max_daily_loss_eur", settings.maxDailyLossEur.toPlainString())
            .putInt("max_trades_per_day", settings.maxTradesPerDay)
            .putString("max_spread_percent", settings.maxSpreadPercent.toPlainString())
            .putString("min_volume_24h_eur", settings.minVolume24hEur.toPlainString())
            .putLong("scan_interval_seconds", settings.scanIntervalSeconds)
            .putBoolean("tax_optimization", settings.taxOptimization)
            .putBoolean("trade_only_btc_eth", settings.tradeOnlyBtcEth)
            .putBoolean("live_ack", settings.liveTradingAcknowledged)
            .putBoolean("use_news_ai", settings.useNewsAi)
            .putBoolean("use_trade_memory_ai", settings.useTradeMemoryAi)
            .putString("symbols_csv", settings.symbolsCsv)
            .putBoolean("recovered_scalping_strategy_enabled", settings.recoveredScalpingStrategyEnabled)
            .putInt("ema_fast_period", settings.emaFastPeriod)
            .putInt("ema_slow_period", settings.emaSlowPeriod)
            .putInt("obv_lookback", settings.obvLookback)
            .putInt("atr_period", settings.atrPeriod)
            .putInt("min_strategy_score_to_buy", settings.minStrategyScoreToBuy)
            .putInt("min_trend_agreement", settings.minTrendAgreement)
            .putString("take_profit_atr_multiplier", settings.takeProfitAtrMultiplier.toPlainString())
            .putString("stop_loss_atr_multiplier", settings.stopLossAtrMultiplier.toPlainString())
            .putString("strategy_mode", settings.strategyMode.name)
            .putBoolean("auto_select_strategy", settings.autoSelectStrategy)
            .putBoolean("enable_backtest_gate", settings.enableBacktestGate)
            .putBoolean("enable_forward_test_gate", settings.enableForwardTestGate)
            .putInt("required_paper_trades", settings.requiredPaperTrades)
            .putInt("required_paper_win_rate_percent", settings.requiredPaperWinRatePercent)
            .putString("required_profit_factor", settings.requiredProfitFactor.toPlainString())
            .putString("max_drawdown_percent", settings.maxDrawdownPercent.toPlainString())
            .putString("max_weekly_loss_eur", settings.maxWeeklyLossEur.toPlainString())
            .putInt("max_open_positions", settings.maxOpenPositions)
            .putString("max_coin_exposure_percent", settings.maxCoinExposurePercent.toPlainString())
            .putInt("loss_cooldown_minutes", settings.lossCooldownMinutes)
            .putInt("win_streak_cooldown_minutes", settings.winStreakCooldownMinutes)
            .putBoolean("enable_trailing_stop", settings.enableTrailingStop)
            .putString("trailing_stop_atr_multiplier", settings.trailingStopAtrMultiplier.toPlainString())
            .putBoolean("enable_break_even_stop", settings.enableBreakEvenStop)
            .putBoolean("enable_partial_take_profit", settings.enablePartialTakeProfit)
            .putString("partial_take_profit_percent", settings.partialTakeProfitPercent.toPlainString())
            .putLong("stale_order_timeout_seconds", settings.staleOrderTimeoutSeconds)
            .putBoolean("smart_limit_requote", settings.smartLimitRequote)
            .putString("order_management_mode", settings.orderManagementMode.name)
            .putBoolean("enable_news_severity_filter", settings.enableNewsSeverityFilter)
            .putInt("high_severity_news_block_hours", settings.highSeverityNewsBlockHours)
            .putBoolean("enable_auto_safe_mode", settings.enableAutoSafeMode)
            .putString("exchange_provider", settings.exchangeProvider.name)
            .putBoolean("manual_execution_mode", settings.manualExecutionMode)
            .apply()
    }

    fun saveBinanceKeys(apiKey: String, secretKey: String) {
        saveExchangeKeys(ExchangeProvider.BINANCE_READ_ONLY, apiKey, secretKey)
    }

    fun saveExchangeKeys(provider: ExchangeProvider, apiKey: String, secretKey: String) {
        secure.saveEncryptedString("${provider.name.lowercase()}_api_key", apiKey.trim())
        secure.saveEncryptedString("${provider.name.lowercase()}_secret_key", secretKey.trim())
    }

    fun exchangeApiKey(provider: ExchangeProvider): String? =
        secure.readEncryptedString("${provider.name.lowercase()}_api_key")?.takeIf { it.isNotBlank() }

    fun exchangeSecretKey(provider: ExchangeProvider): String? =
        secure.readEncryptedString("${provider.name.lowercase()}_secret_key")?.takeIf { it.isNotBlank() }

    fun binanceApiKey(): String? = exchangeApiKey(ExchangeProvider.BINANCE_READ_ONLY)
    fun binanceSecretKey(): String? = exchangeSecretKey(ExchangeProvider.BINANCE_READ_ONLY)

    fun saveNewsApiKey(apiKey: String) = secure.saveEncryptedString("news_api_key", apiKey.trim())
    fun newsApiKey(): String? = secure.readEncryptedString("news_api_key")?.takeIf { it.isNotBlank() }
}
