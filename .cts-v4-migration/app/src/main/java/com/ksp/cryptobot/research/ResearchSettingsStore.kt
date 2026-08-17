package com.ksp.cryptobot.research

import android.content.Context
import com.ksp.cryptobot.security.SecureSettingsStore

/** Stage-5/6 research settings remain separate from BotSettings so v4 upgrades do not
 * require destructive serialization changes. All setters are bounded and persisted. */
class ResearchSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("cts_research_v4", Context.MODE_PRIVATE)
    private val secure = SecureSettingsStore(context)

    fun enabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)
    fun advancedStrategiesEnabled(): Boolean = prefs.getBoolean(KEY_ADVANCED, true)
    fun walkForwardEnabled(): Boolean = prefs.getBoolean(KEY_WALK_FORWARD, true)
    fun monteCarloEnabled(): Boolean = prefs.getBoolean(KEY_MONTE_CARLO, true)
    fun sequenceModelEnabled(): Boolean = prefs.getBoolean(KEY_SEQUENCE, true)
    fun rlSandboxEnabled(): Boolean = prefs.getBoolean(KEY_RL, true)
    fun futuresContextEnabled(): Boolean = prefs.getBoolean(KEY_FUTURES, true)
    fun labeledWalletEnabled(): Boolean = prefs.getBoolean(KEY_WALLET, true)
    fun researchPromotionInPaper(): Boolean = prefs.getBoolean(KEY_PROMOTE_PAPER, true)
    fun researchPromotionInLive(): Boolean = prefs.getBoolean(KEY_PROMOTE_LIVE, false)
    fun maxPositiveAdjustment(): Int = prefs.getInt(KEY_MAX_POSITIVE, 6).coerceIn(0, 10)
    fun maxNegativeAdjustment(): Int = prefs.getInt(KEY_MAX_NEGATIVE, 8).coerceIn(0, 15)
    fun monteCarloSimulations(): Int = prefs.getInt(KEY_MC_SIMULATIONS, 500).coerceIn(100, 5000)
    fun minimumOutcomeSamples(): Int = prefs.getInt(KEY_MIN_SAMPLES, 10).coerceIn(5, 100)
    fun whaleAlertMinUsd(): Long = prefs.getLong(KEY_WHALE_MIN_USD, 500_000L).coerceAtLeast(100_000L)
    fun whaleAlertExchangeRiskUsd(): Long = prefs.getLong(KEY_WHALE_RISK_USD, 1_000_000L).coerceAtLeast(250_000L)
    fun whaleAlertExchangeOutflowBullUsd(): Long = prefs.getLong(KEY_WHALE_OUTFLOW_USD, 1_000_000L).coerceAtLeast(250_000L)

    fun whaleAlertApiKey(): String = secure.readEncryptedString("research_whale_alert_api_key").orEmpty()
    fun saveWhaleAlertApiKey(value: String) {
        if (value.isBlank()) secure.clearSecret("research_whale_alert_api_key")
        else secure.saveEncryptedString("research_whale_alert_api_key", value.trim())
    }

    fun setEnabled(value: Boolean) = setBoolean(KEY_ENABLED, value)
    fun setAdvancedStrategiesEnabled(value: Boolean) = setBoolean(KEY_ADVANCED, value)
    fun setWalkForwardEnabled(value: Boolean) = setBoolean(KEY_WALK_FORWARD, value)
    fun setMonteCarloEnabled(value: Boolean) = setBoolean(KEY_MONTE_CARLO, value)
    fun setSequenceModelEnabled(value: Boolean) = setBoolean(KEY_SEQUENCE, value)
    fun setRlSandboxEnabled(value: Boolean) = setBoolean(KEY_RL, value)
    fun setFuturesContextEnabled(value: Boolean) = setBoolean(KEY_FUTURES, value)
    fun setLabeledWalletEnabled(value: Boolean) = setBoolean(KEY_WALLET, value)
    fun setResearchPromotionInPaper(value: Boolean) = setBoolean(KEY_PROMOTE_PAPER, value)
    fun setResearchPromotionInLive(value: Boolean) = setBoolean(KEY_PROMOTE_LIVE, value)
    fun setMaxPositiveAdjustment(value: Int) { prefs.edit().putInt(KEY_MAX_POSITIVE, value.coerceIn(0, 10)).apply() }
    fun setMaxNegativeAdjustment(value: Int) { prefs.edit().putInt(KEY_MAX_NEGATIVE, value.coerceIn(0, 15)).apply() }
    fun setMonteCarloSimulations(value: Int) { prefs.edit().putInt(KEY_MC_SIMULATIONS, value.coerceIn(100, 5000)).apply() }
    fun setMinimumOutcomeSamples(value: Int) { prefs.edit().putInt(KEY_MIN_SAMPLES, value.coerceIn(5, 100)).apply() }
    fun setWhaleAlertMinUsd(value: Long) { prefs.edit().putLong(KEY_WHALE_MIN_USD, value.coerceAtLeast(100_000L)).apply() }
    fun setWhaleAlertExchangeRiskUsd(value: Long) { prefs.edit().putLong(KEY_WHALE_RISK_USD, value.coerceAtLeast(250_000L)).apply() }
    fun setWhaleAlertExchangeOutflowBullUsd(value: Long) { prefs.edit().putLong(KEY_WHALE_OUTFLOW_USD, value.coerceAtLeast(250_000L)).apply() }

    fun setBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }

    fun publicSnapshot(): Map<String, Any> = linkedMapOf(
        "enabled" to enabled(),
        "advancedStrategiesEnabled" to advancedStrategiesEnabled(),
        "walkForwardEnabled" to walkForwardEnabled(),
        "monteCarloEnabled" to monteCarloEnabled(),
        "sequenceModelEnabled" to sequenceModelEnabled(),
        "rlSandboxEnabled" to rlSandboxEnabled(),
        "futuresContextEnabled" to futuresContextEnabled(),
        "labeledWalletEnabled" to labeledWalletEnabled(),
        "researchPromotionInPaper" to researchPromotionInPaper(),
        "researchPromotionInLive" to researchPromotionInLive(),
        "maxPositiveAdjustment" to maxPositiveAdjustment(),
        "maxNegativeAdjustment" to maxNegativeAdjustment(),
        "monteCarloSimulations" to monteCarloSimulations(),
        "minimumOutcomeSamples" to minimumOutcomeSamples(),
        "whaleAlertMinUsd" to whaleAlertMinUsd(),
        "whaleAlertExchangeRiskUsd" to whaleAlertExchangeRiskUsd(),
        "whaleAlertExchangeOutflowBullUsd" to whaleAlertExchangeOutflowBullUsd(),
        "whaleAlertConfigured" to whaleAlertApiKey().isNotBlank()
    )

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ADVANCED = "advanced_strategies"
        private const val KEY_WALK_FORWARD = "walk_forward"
        private const val KEY_MONTE_CARLO = "monte_carlo"
        private const val KEY_SEQUENCE = "sequence_model"
        private const val KEY_RL = "rl_sandbox"
        private const val KEY_FUTURES = "futures_context"
        private const val KEY_WALLET = "labeled_wallet"
        private const val KEY_PROMOTE_PAPER = "paper_research_promotion"
        private const val KEY_PROMOTE_LIVE = "live_research_promotion"
        private const val KEY_MAX_POSITIVE = "max_positive_adjustment"
        private const val KEY_MAX_NEGATIVE = "max_negative_adjustment"
        private const val KEY_MC_SIMULATIONS = "monte_carlo_simulations"
        private const val KEY_MIN_SAMPLES = "minimum_outcome_samples"
        private const val KEY_WHALE_MIN_USD = "whale_alert_min_usd"
        private const val KEY_WHALE_RISK_USD = "whale_alert_exchange_risk_usd"
        private const val KEY_WHALE_OUTFLOW_USD = "whale_alert_outflow_bull_usd"
    }
}
