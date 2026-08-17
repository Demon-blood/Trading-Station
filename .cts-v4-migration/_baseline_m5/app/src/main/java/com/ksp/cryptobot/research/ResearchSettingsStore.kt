package com.ksp.cryptobot.research

import android.content.Context
import com.ksp.cryptobot.security.SecureSettingsStore

/** Stage-5 settings are intentionally separate from BotSettings to avoid a destructive
 * serialization/config migration. Stage 6 exposes these controls in the unified UI. */
class ResearchSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("cts_research_v4", Context.MODE_PRIVATE)
    private val secure = SecureSettingsStore(context)

    fun enabled(): Boolean = prefs.getBoolean("enabled", true)
    fun advancedStrategiesEnabled(): Boolean = prefs.getBoolean("advanced_strategies", true)
    fun walkForwardEnabled(): Boolean = prefs.getBoolean("walk_forward", true)
    fun monteCarloEnabled(): Boolean = prefs.getBoolean("monte_carlo", true)
    fun sequenceModelEnabled(): Boolean = prefs.getBoolean("sequence_model", true)
    fun rlSandboxEnabled(): Boolean = prefs.getBoolean("rl_sandbox", true)
    fun futuresContextEnabled(): Boolean = prefs.getBoolean("futures_context", true)
    fun labeledWalletEnabled(): Boolean = prefs.getBoolean("labeled_wallet", true)
    fun researchPromotionInPaper(): Boolean = prefs.getBoolean("paper_research_promotion", true)
    fun researchPromotionInLive(): Boolean = prefs.getBoolean("live_research_promotion", false)
    fun maxPositiveAdjustment(): Int = prefs.getInt("max_positive_adjustment", 6).coerceIn(0, 10)
    fun maxNegativeAdjustment(): Int = prefs.getInt("max_negative_adjustment", 8).coerceIn(0, 15)
    fun monteCarloSimulations(): Int = prefs.getInt("monte_carlo_simulations", 500).coerceIn(100, 5000)
    fun minimumOutcomeSamples(): Int = prefs.getInt("minimum_outcome_samples", 10).coerceIn(5, 100)
    fun whaleAlertMinUsd(): Long = prefs.getLong("whale_alert_min_usd", 500_000L).coerceAtLeast(100_000L)
    fun whaleAlertExchangeRiskUsd(): Long = prefs.getLong("whale_alert_exchange_risk_usd", 1_000_000L).coerceAtLeast(250_000L)
    fun whaleAlertExchangeOutflowBullUsd(): Long = prefs.getLong("whale_alert_outflow_bull_usd", 1_000_000L).coerceAtLeast(250_000L)

    fun whaleAlertApiKey(): String = secure.readEncryptedString("research_whale_alert_api_key").orEmpty()
    fun saveWhaleAlertApiKey(value: String) {
        if (value.isBlank()) secure.clearSecret("research_whale_alert_api_key")
        else secure.saveEncryptedString("research_whale_alert_api_key", value.trim())
    }

    fun setBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
}
