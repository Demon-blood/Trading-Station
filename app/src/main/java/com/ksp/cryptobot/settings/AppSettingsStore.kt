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
            tradeOnlyBtcEth = prefs.getBoolean("trade_only_btc_eth", false),
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
            manualExecutionMode = prefs.getBoolean("manual_execution_mode", false),
            enableMarketOrders = prefs.getBoolean("enable_market_orders", false),
            maxMarketOrderEur = prefs.getString("max_market_order_eur", "25.00")!!.toBigDecimalOrNull() ?: BigDecimal("25.00"),
            marketOrderSlippageWarningPercent = prefs.getString("market_order_slippage_warning_percent", "0.75")!!.toBigDecimalOrNull() ?: BigDecimal("0.75"),
            liveLifecycleManagerEnabled = prefs.getBoolean("live_lifecycle_manager_enabled", true),
            autoExitManagerEnabled = prefs.getBoolean("auto_exit_manager_enabled", true),
            autoTakeProfitEnabled = prefs.getBoolean("auto_take_profit_enabled", true),
            autoStopLossEnabled = prefs.getBoolean("auto_stop_loss_enabled", true),
            profitMaximizerEnabled = prefs.getBoolean("profit_maximizer_enabled", true),
            forceSellOnBearishSignal = prefs.getBoolean("force_sell_on_bearish_signal", true),
            takeProfitPercent = prefs.getString("take_profit_percent", "2.0")!!.toBigDecimalOrNull() ?: BigDecimal("2.0"),
            stopLossPercent = prefs.getString("stop_loss_percent", "1.2")!!.toBigDecimalOrNull() ?: BigDecimal("1.2"),
            trailingActivationPercent = prefs.getString("trailing_activation_percent", "1.0")!!.toBigDecimalOrNull() ?: BigDecimal("1.0"),
            trailingDistancePercent = prefs.getString("trailing_distance_percent", "0.8")!!.toBigDecimalOrNull() ?: BigDecimal("0.8"),
            partialExitPercent = prefs.getString("partial_exit_percent", "50.0")!!.toBigDecimalOrNull() ?: BigDecimal("50.0"),
            emergencySellAllOnRiskOff = prefs.getBoolean("emergency_sell_all_on_risk_off", false),
            syncKrakenHistory = prefs.getBoolean("sync_kraken_history", true),
            exportTaxReportEnabled = prefs.getBoolean("export_tax_report_enabled", true),
            useCoinGeckoIntelligence = prefs.getBoolean("use_coingecko_intelligence", false),
            coinGeckoVsKrakenDeviationBlockPercent = prefs.getString("coingecko_deviation_block_percent", "1.75")!!.toBigDecimalOrNull() ?: BigDecimal("1.75"),
            enableKrakenWebSocketFeed = prefs.getBoolean("enable_kraken_websocket_feed", true),
            smartProfitLockEnabled = prefs.getBoolean("smart_profit_lock_enabled", true),
            smartProfitLockActivationPercent = prefs.getString("smart_profit_lock_activation_percent", "1.0")!!.toBigDecimalOrNull() ?: BigDecimal("1.0"),
            smartProfitLockTrailingDistancePercent = prefs.getString("smart_profit_lock_trailing_distance_percent", "0.75")!!.toBigDecimalOrNull() ?: BigDecimal("0.75"),
            smartProfitLockPartialTakeProfitPercent = prefs.getString("smart_profit_lock_partial_take_profit_percent", "2.0")!!.toBigDecimalOrNull() ?: BigDecimal("2.0"),
            smartProfitLockPartialExitPercent = prefs.getString("smart_profit_lock_partial_exit_percent", "30.0")!!.toBigDecimalOrNull() ?: BigDecimal("30.0"),
            autoCompoundingEnabled = prefs.getBoolean("auto_compounding_enabled", true),
            autoCompoundingMaxIncreasePercent = prefs.getString("auto_compounding_max_increase_percent", "10.0")!!.toBigDecimalOrNull() ?: BigDecimal("10.0"),
            enableNetProfitFilter = prefs.getBoolean("enable_net_profit_filter", true),
            saveWhyTradedExplanations = prefs.getBoolean("save_why_traded_explanations", true),
            strategyOptimizerEnabled = prefs.getBoolean("strategy_optimizer_enabled", true),
            portfolioBalancerEnabled = prefs.getBoolean("portfolio_balancer_enabled", true),
            minimumEurReservePercent = prefs.getString("minimum_eur_reserve_percent", "15.0")!!.toBigDecimalOrNull() ?: BigDecimal("15.0"),
            maxSingleAssetAllocationPercent = prefs.getString("max_single_asset_allocation_percent", "45.0")!!.toBigDecimalOrNull() ?: BigDecimal("45.0"),
            watchdogEnabled = prefs.getBoolean("watchdog_enabled", true),
            pauseBelowBatteryPercent = prefs.getInt("pause_below_battery_percent", 15),
            telegramRemoteControlEnabled = prefs.getBoolean("telegram_remote_control_enabled", false),
            discordRemoteControlEnabled = prefs.getBoolean("discord_remote_control_enabled", false),
            localMlScoringEnabled = prefs.getBoolean("local_ml_scoring_enabled", true),
            dryRunMirrorModeEnabled = prefs.getBoolean("dry_run_mirror_mode_enabled", true),
            bearishAutoSellScore = prefs.getInt("bearish_auto_sell_score", 45),
            autonomousStrategyPerSymbolEnabled = prefs.getBoolean("autonomous_strategy_per_symbol_enabled", true),
            selfOptimizationEnabled = prefs.getBoolean("self_optimization_enabled", true),
            autoDisableBadSymbolsEnabled = prefs.getBoolean("auto_disable_bad_symbols_enabled", false),
            shadowPaperComparisonEnabled = prefs.getBoolean("shadow_paper_comparison_enabled", true),
            tradeReplayEnabled = prefs.getBoolean("trade_replay_enabled", true),
            remoteCommandParserEnabled = prefs.getBoolean("remote_command_parser_enabled", true),
            belgianTaxExportEnabled = prefs.getBoolean("belgian_tax_export_enabled", true),
            portfolioReserveManagerV12Enabled = prefs.getBoolean("portfolio_reserve_manager_v12_enabled", true),
            crashRecoveryWatchdogV12Enabled = prefs.getBoolean("crash_recovery_watchdog_v12_enabled", true),
            badSymbolDisableHours = prefs.getInt("bad_symbol_disable_hours", 48),
            minSymbolWinRatePercent = prefs.getInt("min_symbol_win_rate_percent", 40),
            minSymbolProfitFactor = prefs.getString("min_symbol_profit_factor", "0.90")!!.toBigDecimalOrNull() ?: BigDecimal("0.90"),
            optimizerLookbackTrades = prefs.getInt("optimizer_lookback_trades", 30),
            autoSymbolDiscoveryEnabled = prefs.getBoolean("auto_symbol_discovery_enabled", true),
            autoSymbolQuoteAsset = prefs.getString("auto_symbol_quote_asset", "ALL") ?: "ALL",
            autoSymbolCandidateLimit = prefs.getInt("auto_symbol_candidate_limit", 250),
            autoSymbolActiveLimit = prefs.getInt("auto_symbol_active_limit", 20),
            autoSymbolMaxSpreadPercent = prefs.getString("auto_symbol_max_spread_percent", "1.00")!!.toBigDecimalOrNull() ?: BigDecimal("1.00"),
            autoSymbolMinVolume24hEur = prefs.getString("auto_symbol_min_volume_24h_eur", "50000")!!.toBigDecimalOrNull() ?: BigDecimal("50000"),
            autoSymbolRefreshMinutes = prefs.getInt("auto_symbol_refresh_minutes", 240),
            autoTradeMultipleSymbolsPerScan = prefs.getBoolean("auto_trade_multiple_symbols_per_scan", true),
            maxSymbolsTradedPerScan = prefs.getInt("max_symbols_traded_per_scan", 6),
            allowedQuoteAssetsCsv = prefs.getString("allowed_quote_assets_csv", "EUR,USD,USDT,USDC") ?: "EUR,USD,USDT,USDC",
            maxNewTradesPerScan = prefs.getInt("max_new_trades_per_scan", 2),
            maxTradesPerHour = prefs.getInt("max_trades_per_hour", 3),
            maxSimultaneousLivePositions = prefs.getInt("max_simultaneous_live_positions", 3),
            cooldownAfterBuyMinutes = prefs.getInt("cooldown_after_buy_minutes", 15),
            cooldownAfterSellMinutes = prefs.getInt("cooldown_after_sell_minutes", 30),
            cooldownAfterLossMinutes = prefs.getInt("cooldown_after_loss_minutes", 120),
            cooldownAfterOrderFailureMinutes = prefs.getInt("cooldown_after_order_failure_minutes", 60),
            minimumQuoteReserveAmount = prefs.getString("minimum_quote_reserve_amount", "10.00")!!.toBigDecimalOrNull() ?: BigDecimal("10.00"),
            minimumQuoteReservePercent = prefs.getString("minimum_quote_reserve_percent", "20.0")!!.toBigDecimalOrNull() ?: BigDecimal("20.0"),
            liquidityBlacklistEnabled = prefs.getBoolean("liquidity_blacklist_enabled", true),
            marketOrderHighLiquidityOnly = prefs.getBoolean("market_order_high_liquidity_only", true),
            fallbackToLimitWhenMarketBlocked = prefs.getBoolean("fallback_to_limit_when_market_blocked", true),
            liveVerificationPanelEnabled = prefs.getBoolean("live_verification_panel_enabled", true),
            nonEurQuoteBuyEnabled = prefs.getBoolean("non_eur_quote_buy_enabled", false),
            maxNonEurQuoteSpendPercent = prefs.getString("max_non_eur_quote_spend_percent", "5.0")!!.toBigDecimalOrNull() ?: BigDecimal("5.0"),
            trueSelfLearningEnabled = prefs.getBoolean("true_self_learning_enabled", true),
            selfLearningMinSamples = prefs.getInt("self_learning_min_samples", 10),
            selfLearningLookbackTrades = prefs.getInt("self_learning_lookback_trades", 500),
            selfLearningMaxScoreBoost = prefs.getInt("self_learning_max_score_boost", 10),
            selfLearningMaxScorePenalty = prefs.getInt("self_learning_max_score_penalty", 15),
            selfLearningPositionSizingEnabled = prefs.getBoolean("self_learning_position_sizing_enabled", true),
            selfLearningAutoDisableEnabled = prefs.getBoolean("self_learning_auto_disable_enabled", true),
            selfLearningPaperAndLiveSeparated = prefs.getBoolean("self_learning_paper_and_live_separated", true),
            selfLearningExplainEveryDecision = prefs.getBoolean("self_learning_explain_every_decision", true),
            adaptiveStrategyLearningEnabled = prefs.getBoolean("adaptive_strategy_learning_enabled", true),
            adaptiveStrategyMinSamples = prefs.getInt("adaptive_strategy_min_samples", 8),
            adaptiveStrategySwitchConfidencePercent = prefs.getInt("adaptive_strategy_switch_confidence_percent", 55),
            adaptiveStrategyMaxScoreBoost = prefs.getInt("adaptive_strategy_max_score_boost", 12),
            adaptiveStrategyMaxScorePenalty = prefs.getInt("adaptive_strategy_max_score_penalty", 16),
            adaptiveStrategyPreferSymbolProfile = prefs.getBoolean("adaptive_strategy_prefer_symbol_profile", true),
            adaptiveStrategyAllowLiveLearning = prefs.getBoolean("adaptive_strategy_allow_live_learning", true),
            adaptiveStrategyAllowPaperLearning = prefs.getBoolean("adaptive_strategy_allow_paper_learning", true),
            taxExportYear = prefs.getInt("tax_export_year", 2026)
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
            .putBoolean("enable_market_orders", settings.enableMarketOrders)
            .putString("max_market_order_eur", settings.maxMarketOrderEur.toPlainString())
            .putString("market_order_slippage_warning_percent", settings.marketOrderSlippageWarningPercent.toPlainString())
            .putBoolean("live_lifecycle_manager_enabled", settings.liveLifecycleManagerEnabled)
            .putBoolean("auto_exit_manager_enabled", settings.autoExitManagerEnabled)
            .putBoolean("auto_take_profit_enabled", settings.autoTakeProfitEnabled)
            .putBoolean("auto_stop_loss_enabled", settings.autoStopLossEnabled)
            .putBoolean("profit_maximizer_enabled", settings.profitMaximizerEnabled)
            .putBoolean("force_sell_on_bearish_signal", settings.forceSellOnBearishSignal)
            .putString("take_profit_percent", settings.takeProfitPercent.toPlainString())
            .putString("stop_loss_percent", settings.stopLossPercent.toPlainString())
            .putString("trailing_activation_percent", settings.trailingActivationPercent.toPlainString())
            .putString("trailing_distance_percent", settings.trailingDistancePercent.toPlainString())
            .putString("partial_exit_percent", settings.partialExitPercent.toPlainString())
            .putBoolean("emergency_sell_all_on_risk_off", settings.emergencySellAllOnRiskOff)
            .putBoolean("sync_kraken_history", settings.syncKrakenHistory)
            .putBoolean("export_tax_report_enabled", settings.exportTaxReportEnabled)
            .putBoolean("use_coingecko_intelligence", settings.useCoinGeckoIntelligence)
            .putString("coingecko_deviation_block_percent", settings.coinGeckoVsKrakenDeviationBlockPercent.toPlainString())
            .putBoolean("enable_kraken_websocket_feed", settings.enableKrakenWebSocketFeed)
            .putBoolean("smart_profit_lock_enabled", settings.smartProfitLockEnabled)
            .putString("smart_profit_lock_activation_percent", settings.smartProfitLockActivationPercent.toPlainString())
            .putString("smart_profit_lock_trailing_distance_percent", settings.smartProfitLockTrailingDistancePercent.toPlainString())
            .putString("smart_profit_lock_partial_take_profit_percent", settings.smartProfitLockPartialTakeProfitPercent.toPlainString())
            .putString("smart_profit_lock_partial_exit_percent", settings.smartProfitLockPartialExitPercent.toPlainString())
            .putBoolean("auto_compounding_enabled", settings.autoCompoundingEnabled)
            .putString("auto_compounding_max_increase_percent", settings.autoCompoundingMaxIncreasePercent.toPlainString())
            .putBoolean("enable_net_profit_filter", settings.enableNetProfitFilter)
            .putBoolean("save_why_traded_explanations", settings.saveWhyTradedExplanations)
            .putBoolean("strategy_optimizer_enabled", settings.strategyOptimizerEnabled)
            .putBoolean("portfolio_balancer_enabled", settings.portfolioBalancerEnabled)
            .putString("minimum_eur_reserve_percent", settings.minimumEurReservePercent.toPlainString())
            .putString("max_single_asset_allocation_percent", settings.maxSingleAssetAllocationPercent.toPlainString())
            .putBoolean("watchdog_enabled", settings.watchdogEnabled)
            .putInt("pause_below_battery_percent", settings.pauseBelowBatteryPercent)
            .putBoolean("telegram_remote_control_enabled", settings.telegramRemoteControlEnabled)
            .putBoolean("discord_remote_control_enabled", settings.discordRemoteControlEnabled)
            .putBoolean("local_ml_scoring_enabled", settings.localMlScoringEnabled)
            .putBoolean("dry_run_mirror_mode_enabled", settings.dryRunMirrorModeEnabled)
            .putInt("bearish_auto_sell_score", settings.bearishAutoSellScore)
            .putBoolean("autonomous_strategy_per_symbol_enabled", settings.autonomousStrategyPerSymbolEnabled)
            .putBoolean("self_optimization_enabled", settings.selfOptimizationEnabled)
            .putBoolean("auto_disable_bad_symbols_enabled", settings.autoDisableBadSymbolsEnabled)
            .putBoolean("shadow_paper_comparison_enabled", settings.shadowPaperComparisonEnabled)
            .putBoolean("trade_replay_enabled", settings.tradeReplayEnabled)
            .putBoolean("remote_command_parser_enabled", settings.remoteCommandParserEnabled)
            .putBoolean("belgian_tax_export_enabled", settings.belgianTaxExportEnabled)
            .putBoolean("portfolio_reserve_manager_v12_enabled", settings.portfolioReserveManagerV12Enabled)
            .putBoolean("crash_recovery_watchdog_v12_enabled", settings.crashRecoveryWatchdogV12Enabled)
            .putInt("bad_symbol_disable_hours", settings.badSymbolDisableHours)
            .putInt("min_symbol_win_rate_percent", settings.minSymbolWinRatePercent)
            .putString("min_symbol_profit_factor", settings.minSymbolProfitFactor.toPlainString())
            .putInt("optimizer_lookback_trades", settings.optimizerLookbackTrades)
            .putBoolean("auto_symbol_discovery_enabled", settings.autoSymbolDiscoveryEnabled)
            .putString("auto_symbol_quote_asset", settings.autoSymbolQuoteAsset)
            .putInt("auto_symbol_candidate_limit", settings.autoSymbolCandidateLimit)
            .putInt("auto_symbol_active_limit", settings.autoSymbolActiveLimit)
            .putString("auto_symbol_max_spread_percent", settings.autoSymbolMaxSpreadPercent.toPlainString())
            .putString("auto_symbol_min_volume_24h_eur", settings.autoSymbolMinVolume24hEur.toPlainString())
            .putInt("auto_symbol_refresh_minutes", settings.autoSymbolRefreshMinutes)
            .putBoolean("auto_trade_multiple_symbols_per_scan", settings.autoTradeMultipleSymbolsPerScan)
            .putInt("max_symbols_traded_per_scan", settings.maxSymbolsTradedPerScan)
            .putString("allowed_quote_assets_csv", settings.allowedQuoteAssetsCsv)
            .putInt("max_new_trades_per_scan", settings.maxNewTradesPerScan)
            .putInt("max_trades_per_hour", settings.maxTradesPerHour)
            .putInt("max_simultaneous_live_positions", settings.maxSimultaneousLivePositions)
            .putInt("cooldown_after_buy_minutes", settings.cooldownAfterBuyMinutes)
            .putInt("cooldown_after_sell_minutes", settings.cooldownAfterSellMinutes)
            .putInt("cooldown_after_loss_minutes", settings.cooldownAfterLossMinutes)
            .putInt("cooldown_after_order_failure_minutes", settings.cooldownAfterOrderFailureMinutes)
            .putString("minimum_quote_reserve_amount", settings.minimumQuoteReserveAmount.toPlainString())
            .putString("minimum_quote_reserve_percent", settings.minimumQuoteReservePercent.toPlainString())
            .putBoolean("liquidity_blacklist_enabled", settings.liquidityBlacklistEnabled)
            .putBoolean("market_order_high_liquidity_only", settings.marketOrderHighLiquidityOnly)
            .putBoolean("fallback_to_limit_when_market_blocked", settings.fallbackToLimitWhenMarketBlocked)
            .putBoolean("live_verification_panel_enabled", settings.liveVerificationPanelEnabled)
            .putBoolean("non_eur_quote_buy_enabled", settings.nonEurQuoteBuyEnabled)
            .putString("max_non_eur_quote_spend_percent", settings.maxNonEurQuoteSpendPercent.toPlainString())
            .putBoolean("true_self_learning_enabled", settings.trueSelfLearningEnabled)
            .putInt("self_learning_min_samples", settings.selfLearningMinSamples)
            .putInt("self_learning_lookback_trades", settings.selfLearningLookbackTrades)
            .putInt("self_learning_max_score_boost", settings.selfLearningMaxScoreBoost)
            .putInt("self_learning_max_score_penalty", settings.selfLearningMaxScorePenalty)
            .putBoolean("self_learning_position_sizing_enabled", settings.selfLearningPositionSizingEnabled)
            .putBoolean("self_learning_auto_disable_enabled", settings.selfLearningAutoDisableEnabled)
            .putBoolean("self_learning_paper_and_live_separated", settings.selfLearningPaperAndLiveSeparated)
            .putBoolean("self_learning_explain_every_decision", settings.selfLearningExplainEveryDecision)
            .putBoolean("adaptive_strategy_learning_enabled", settings.adaptiveStrategyLearningEnabled)
            .putInt("adaptive_strategy_min_samples", settings.adaptiveStrategyMinSamples)
            .putInt("adaptive_strategy_switch_confidence_percent", settings.adaptiveStrategySwitchConfidencePercent)
            .putInt("adaptive_strategy_max_score_boost", settings.adaptiveStrategyMaxScoreBoost)
            .putInt("adaptive_strategy_max_score_penalty", settings.adaptiveStrategyMaxScorePenalty)
            .putBoolean("adaptive_strategy_prefer_symbol_profile", settings.adaptiveStrategyPreferSymbolProfile)
            .putBoolean("adaptive_strategy_allow_live_learning", settings.adaptiveStrategyAllowLiveLearning)
            .putBoolean("adaptive_strategy_allow_paper_learning", settings.adaptiveStrategyAllowPaperLearning)
            .putInt("tax_export_year", settings.taxExportYear)
            .commit()
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
