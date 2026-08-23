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
    fun professionalStrategiesEnabled(): Boolean = prefs.getBoolean(KEY_PROFESSIONAL_STRATEGIES, true)
    fun desktopParityIntelligenceEnabled(): Boolean = prefs.getBoolean(KEY_DESKTOP_PARITY_INTELLIGENCE, true)
    fun multiExchangeReferenceEnabled(): Boolean = prefs.getBoolean(KEY_MULTI_EXCHANGE_REFERENCE, true)
    fun btcMempoolEnabled(): Boolean = prefs.getBoolean(KEY_BTC_MEMPOOL, true)
    fun defillamaEnabled(): Boolean = prefs.getBoolean(KEY_DEFILLAMA, true)
    fun etherscanEnabled(): Boolean = prefs.getBoolean(KEY_ETHERSCAN, false)
    fun dropstabUnlocksEnabled(): Boolean = prefs.getBoolean(KEY_DROPSTAB_UNLOCKS, false)
    fun onchainCacheSeconds(): Int = prefs.getInt(KEY_ONCHAIN_CACHE_SECONDS, 300).coerceIn(30, 3600)
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

    // Research handoff truth engine (2026-08-17). These are product-policy controls, never creator-attributed rules.
    fun handoffEngineEnabled(): Boolean = prefs.getBoolean(KEY_HANDOFF_ENGINE, true)
    fun handoffAutoPaperExecutionEnabled(): Boolean = prefs.getBoolean(KEY_HANDOFF_AUTO_PAPER, true)
    fun handoffSourceTruthLiveEntriesEnabled(): Boolean = prefs.getBoolean(KEY_HANDOFF_LIVE_SOURCE, true)
    fun handoffProtectiveLiveActionsEnabled(): Boolean = prefs.getBoolean(KEY_HANDOFF_PROTECTIVE_LIVE, true)
    fun handoffFormalizedPaperExecutionEnabled(): Boolean = prefs.getBoolean(KEY_HANDOFF_FORMALIZED_PAPER, true)
    fun handoffRiskPerTradeFraction(): java.math.BigDecimal = prefs.getString(KEY_HANDOFF_RISK_FRACTION, "0.0035")?.toBigDecimalOrNull()?.coerceIn(java.math.BigDecimal("0.0005"), java.math.BigDecimal("0.0100")) ?: java.math.BigDecimal("0.0035")
    fun handoffCostSafetyMarginPct(): java.math.BigDecimal = prefs.getString(KEY_HANDOFF_COST_MARGIN_PCT, "0.25")?.toBigDecimalOrNull()?.coerceIn(java.math.BigDecimal("0.00"), java.math.BigDecimal("2.00")) ?: java.math.BigDecimal("0.25")
    fun handoffCorrelatedRiskCapFraction(): java.math.BigDecimal = prefs.getString(KEY_HANDOFF_CLUSTER_RISK, "0.0200")?.toBigDecimalOrNull()?.coerceIn(java.math.BigDecimal("0.0025"), java.math.BigDecimal("0.0500")) ?: java.math.BigDecimal("0.0200")
    fun handoffFreshnessWarnDays(): Int = prefs.getInt(KEY_HANDOFF_FRESHNESS_DAYS, 7).coerceIn(1, 90)

    fun whaleAlertApiKey(): String = secure.readEncryptedString("research_whale_alert_api_key").orEmpty()
    fun etherscanApiKey(): String = secure.readEncryptedString("research_etherscan_api_key").orEmpty()
    fun dropstabApiKey(): String = secure.readEncryptedString("research_dropstab_api_key").orEmpty()
    fun saveWhaleAlertApiKey(value: String) {
        if (value.isBlank()) secure.clearSecret("research_whale_alert_api_key")
        else secure.saveEncryptedString("research_whale_alert_api_key", value.trim())
    }
    fun saveEtherscanApiKey(value: String) {
        if (value.isBlank()) secure.clearSecret("research_etherscan_api_key")
        else secure.saveEncryptedString("research_etherscan_api_key", value.trim())
    }
    fun saveDropstabApiKey(value: String) {
        if (value.isBlank()) secure.clearSecret("research_dropstab_api_key")
        else secure.saveEncryptedString("research_dropstab_api_key", value.trim())
    }

    fun setEnabled(value: Boolean) = setBoolean(KEY_ENABLED, value)
    fun setAdvancedStrategiesEnabled(value: Boolean) = setBoolean(KEY_ADVANCED, value)
    fun setProfessionalStrategiesEnabled(value: Boolean) = setBoolean(KEY_PROFESSIONAL_STRATEGIES, value)
    fun setDesktopParityIntelligenceEnabled(value: Boolean) = setBoolean(KEY_DESKTOP_PARITY_INTELLIGENCE, value)
    fun setMultiExchangeReferenceEnabled(value: Boolean) = setBoolean(KEY_MULTI_EXCHANGE_REFERENCE, value)
    fun setBtcMempoolEnabled(value: Boolean) = setBoolean(KEY_BTC_MEMPOOL, value)
    fun setDefillamaEnabled(value: Boolean) = setBoolean(KEY_DEFILLAMA, value)
    fun setEtherscanEnabled(value: Boolean) = setBoolean(KEY_ETHERSCAN, value)
    fun setDropstabUnlocksEnabled(value: Boolean) = setBoolean(KEY_DROPSTAB_UNLOCKS, value)
    fun setOnchainCacheSeconds(value: Int) { prefs.edit().putInt(KEY_ONCHAIN_CACHE_SECONDS, value.coerceIn(30, 3600)).apply() }
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
    fun setHandoffEngineEnabled(value: Boolean) = setBoolean(KEY_HANDOFF_ENGINE, value)
    fun setHandoffAutoPaperExecutionEnabled(value: Boolean) = setBoolean(KEY_HANDOFF_AUTO_PAPER, value)
    fun setHandoffSourceTruthLiveEntriesEnabled(value: Boolean) = setBoolean(KEY_HANDOFF_LIVE_SOURCE, value)
    fun setHandoffProtectiveLiveActionsEnabled(value: Boolean) = setBoolean(KEY_HANDOFF_PROTECTIVE_LIVE, value)
    fun setHandoffFormalizedPaperExecutionEnabled(value: Boolean) = setBoolean(KEY_HANDOFF_FORMALIZED_PAPER, value)
    fun setHandoffRiskPerTradeFraction(value: java.math.BigDecimal) { prefs.edit().putString(KEY_HANDOFF_RISK_FRACTION, value.coerceIn(java.math.BigDecimal("0.0005"), java.math.BigDecimal("0.0100")).toPlainString()).apply() }
    fun setHandoffCostSafetyMarginPct(value: java.math.BigDecimal) { prefs.edit().putString(KEY_HANDOFF_COST_MARGIN_PCT, value.coerceIn(java.math.BigDecimal("0.00"), java.math.BigDecimal("2.00")).toPlainString()).apply() }
    fun setHandoffCorrelatedRiskCapFraction(value: java.math.BigDecimal) { prefs.edit().putString(KEY_HANDOFF_CLUSTER_RISK, value.coerceIn(java.math.BigDecimal("0.0025"), java.math.BigDecimal("0.0500")).toPlainString()).apply() }
    fun setHandoffFreshnessWarnDays(value: Int) { prefs.edit().putInt(KEY_HANDOFF_FRESHNESS_DAYS, value.coerceIn(1, 90)).apply() }

    fun setBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }

    fun publicSnapshot(): Map<String, Any> = linkedMapOf(
        "enabled" to enabled(),
        "advancedStrategiesEnabled" to advancedStrategiesEnabled(),
        "professionalStrategiesEnabled" to professionalStrategiesEnabled(),
        "desktopParityIntelligenceEnabled" to desktopParityIntelligenceEnabled(),
        "multiExchangeReferenceEnabled" to multiExchangeReferenceEnabled(),
        "btcMempoolEnabled" to btcMempoolEnabled(),
        "defillamaEnabled" to defillamaEnabled(),
        "etherscanEnabled" to etherscanEnabled(),
        "dropstabUnlocksEnabled" to dropstabUnlocksEnabled(),
        "onchainCacheSeconds" to onchainCacheSeconds(),
        "etherscanConfigured" to etherscanApiKey().isNotBlank(),
        "dropstabConfigured" to dropstabApiKey().isNotBlank(),
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
        "handoffEngineEnabled" to handoffEngineEnabled(),
        "handoffAutoPaperExecutionEnabled" to handoffAutoPaperExecutionEnabled(),
        "handoffSourceTruthLiveEntriesEnabled" to handoffSourceTruthLiveEntriesEnabled(),
        "handoffProtectiveLiveActionsEnabled" to handoffProtectiveLiveActionsEnabled(),
        "handoffFormalizedPaperExecutionEnabled" to handoffFormalizedPaperExecutionEnabled(),
        "handoffRiskPerTradeFraction" to handoffRiskPerTradeFraction().toPlainString(),
        "handoffCostSafetyMarginPct" to handoffCostSafetyMarginPct().toPlainString(),
        "handoffCorrelatedRiskCapFraction" to handoffCorrelatedRiskCapFraction().toPlainString(),
        "handoffFreshnessWarnDays" to handoffFreshnessWarnDays(),
        "whaleAlertConfigured" to whaleAlertApiKey().isNotBlank()
    )

    private fun java.math.BigDecimal.coerceIn(lo: java.math.BigDecimal, hi: java.math.BigDecimal): java.math.BigDecimal = when { this < lo -> lo; this > hi -> hi; else -> this }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ADVANCED = "advanced_strategies"
        private const val KEY_PROFESSIONAL_STRATEGIES = "professional_strategies"
        private const val KEY_DESKTOP_PARITY_INTELLIGENCE = "desktop_parity_intelligence"
        private const val KEY_MULTI_EXCHANGE_REFERENCE = "multi_exchange_reference"
        private const val KEY_BTC_MEMPOOL = "btc_mempool"
        private const val KEY_DEFILLAMA = "defillama"
        private const val KEY_ETHERSCAN = "etherscan"
        private const val KEY_DROPSTAB_UNLOCKS = "dropstab_unlocks"
        private const val KEY_ONCHAIN_CACHE_SECONDS = "onchain_cache_seconds"
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
        private const val KEY_HANDOFF_ENGINE = "handoff_truth_engine"
        private const val KEY_HANDOFF_AUTO_PAPER = "handoff_auto_paper"
        private const val KEY_HANDOFF_LIVE_SOURCE = "handoff_source_truth_live_entries"
        private const val KEY_HANDOFF_PROTECTIVE_LIVE = "handoff_protective_live_actions"
        private const val KEY_HANDOFF_FORMALIZED_PAPER = "handoff_formalized_paper"
        private const val KEY_HANDOFF_RISK_FRACTION = "handoff_risk_fraction"
        private const val KEY_HANDOFF_COST_MARGIN_PCT = "handoff_cost_margin_pct"
        private const val KEY_HANDOFF_CLUSTER_RISK = "handoff_cluster_risk_fraction"
        private const val KEY_HANDOFF_FRESHNESS_DAYS = "handoff_freshness_warn_days"
    }
}
