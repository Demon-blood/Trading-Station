package com.ksp.cryptobot.strategy.provenance

import com.ksp.cryptobot.core.StrategyMode

enum class ProvenanceType { SOURCE_EXACT, SOURCE_FRAMEWORK, CTS_REFERENCE, RESEARCH_ONLY }
enum class SourceType { EDUCATOR, TRADER, PROP_FIRM, ACADEMIC, INDICATOR_AUTHOR, EXCHANGE, HISTORICAL_RULESET, CTS }
enum class PerformanceVerification { NONE, SELF_REPORTED, PARTIAL_PUBLIC_EVIDENCE, AUDITED }
enum class StrategyLifecycle {
    RESEARCH_ONLY,
    IMPLEMENTED,
    UNIT_TESTED,
    HISTORICAL_BACKTEST,
    WALK_FORWARD,
    PAPER,
    SHADOW_LIVE,
    ELIGIBLE_FOR_LIVE,
    LIVE,
    DEGRADED
}

data class StrategyDefinition(
    val strategyId: String,
    val name: String,
    val version: String,
    val ruleProfileId: String,
    val provenanceType: ProvenanceType,
    val sourceType: SourceType,
    val sourceIds: List<String>,
    val performanceVerification: PerformanceVerification = PerformanceVerification.NONE,
    val supportedMarkets: List<String> = listOf("KRAKEN_SPOT"),
    val supportedTimeframes: List<String> = emptyList(),
    val directionMode: String = "LONG_FLAT",
    val requiredFeatures: List<String> = emptyList(),
    val entryRules: List<String> = emptyList(),
    val invalidationRule: String = "",
    val exitRules: List<String> = emptyList(),
    val sizingPolicy: String = "",
    val regimeRules: List<String> = emptyList(),
    val differencesFromSource: List<String> = emptyList(),
    val lifecycle: StrategyLifecycle = StrategyLifecycle.RESEARCH_ONLY,
    val enabledForBacktest: Boolean = false,
    val enabledForPaper: Boolean = false,
    val enabledForLive: Boolean = false
)

data class SignalProvenance(
    val strategyId: String,
    val strategyVersion: String,
    val ruleProfileId: String,
    val provenanceType: ProvenanceType,
    val sourceIds: List<String>,
    val symbol: String,
    val timeframe: String,
    val marketRegime: String,
    val signalTimestampEpochMs: Long,
    val featureSnapshot: Map<String, String>,
    val entryRuleResults: Map<String, Boolean>,
    val invalidation: String,
    val targetPlan: String,
    val riskBudget: String,
    val estimatedFees: String,
    val estimatedSlippage: String,
    val expectedNetR: String
)

object StrategyProvenanceRegistry {
    val turtleOriginal = StrategyDefinition(
        strategyId = "TURTLE_ORIGINAL_RESEARCH",
        name = "Original Turtle Trend Following",
        version = "1.0.0",
        ruleProfileId = "TURTLE_ORIGINAL_SYSTEMS_1_2",
        provenanceType = ProvenanceType.SOURCE_EXACT,
        sourceType = SourceType.HISTORICAL_RULESET,
        sourceIds = listOf("SRC-TURTLE"),
        supportedTimeframes = listOf("D1"),
        directionMode = "LONG_SHORT_RESEARCH",
        requiredFeatures = listOf("20D_HIGH_LOW", "55D_HIGH_LOW", "10D_HIGH_LOW", "20D_WILDER_N"),
        entryRules = listOf(
            "System1 long: price exceeds previous 20-day high",
            "System1 short: price breaks previous 20-day low",
            "System1 skip signal if previous breakout would have been a winner",
            "System1 failsafe: 55-day breakout",
            "System2: take every qualifying 55-day breakout"
        ),
        invalidationRule = "Historical initial stop around 2N",
        exitRules = listOf(
            "System1 long exit: 10-day low",
            "System1 short exit: 10-day high",
            "System2 long exit: 20-day low",
            "System2 short exit: 20-day high"
        ),
        sizingPolicy = "Historical Turtle sizing is research-only in CTS; it is not applied to small Kraken spot accounts.",
        lifecycle = StrategyLifecycle.RESEARCH_ONLY,
        enabledForBacktest = true,
        enabledForPaper = false,
        enabledForLive = false
    )

    val turtleSpotSafe = StrategyDefinition(
        strategyId = "CTS_TURTLE_SPOT_SAFE",
        name = "CTS Turtle Spot Safe",
        version = "1.0.0",
        ruleProfileId = "CTS_TURTLE_SPOT_SAFE_SYSTEM2_V1",
        provenanceType = ProvenanceType.CTS_REFERENCE,
        sourceType = SourceType.CTS,
        sourceIds = listOf("SRC-TURTLE", "SRC-CRYPTOCRED"),
        supportedTimeframes = listOf("D1_AGGREGATED_FROM_COMMITTED_H4"),
        directionMode = "LONG_FLAT",
        requiredFeatures = listOf("55D_HIGH", "20D_LOW", "20D_WILDER_N", "LIQUIDITY", "COST_AWARE_RISK"),
        entryRules = listOf(
            "CTS adaptation uses the source System-2 long breakout: live price exceeds the previous 55 completed daily highs",
            "Pair must pass the existing CTS liquidity/spread/exchange gates",
            "No pyramiding in v1"
        ),
        invalidationRule = "Initial technical stop = entry - 2N; global CTS risk engine may reduce size or reject.",
        exitRules = listOf("Long trend exit when price breaks the previous 20 completed daily lows", "Hard protective stop remains active."),
        sizingPolicy = "CTS global cost-aware risk engine, default research risk 0.5% equity, no leverage.",
        regimeRules = listOf("Spot long/flat only", "No new entry when correlated BTC-beta cluster risk is already occupied."),
        differencesFromSource = listOf(
            "long/flat spot only",
            "CTS default 0.5% risk budget",
            "pyramiding disabled",
            "Kraken fee/minimum/liquidity gates",
            "data-driven correlated BTC-beta cluster guard",
            "historical futures portfolio sizing is not reused"
        ),
        lifecycle = StrategyLifecycle.UNIT_TESTED,
        enabledForBacktest = true,
        enabledForPaper = false,
        enabledForLive = false
    )

    val kakFramework = StrategyDefinition(
        strategyId = "KAK_MARKET_STRUCTURE_FRAMEWORK",
        name = "Koroush 7/30/100 Market Structure Framework",
        version = "1.0.0",
        ruleProfileId = "KAK_FRAMEWORK_PUBLIC",
        provenanceType = ProvenanceType.SOURCE_FRAMEWORK,
        sourceType = SourceType.EDUCATOR,
        sourceIds = listOf("SRC-KAK"),
        supportedTimeframes = listOf("MULTI"),
        directionMode = "FRAMEWORK_ONLY",
        requiredFeatures = listOf("MA7", "MA30", "MA100", "SWING_STRUCTURE"),
        entryRules = listOf("Source leaves exact entry/exit mechanics discretionary."),
        lifecycle = StrategyLifecycle.RESEARCH_ONLY,
        enabledForBacktest = false,
        enabledForPaper = false,
        enabledForLive = false
    )

    val kakReference = StrategyDefinition(
        strategyId = "CTS_KAK_CLOSE_BREAK_RETEST_V1",
        name = "CTS KAK Structure Close-Break Retest Reference",
        version = "1.0.0",
        ruleProfileId = "CTS_KAK_CLOSE_BREAK_RETEST_V1",
        provenanceType = ProvenanceType.CTS_REFERENCE,
        sourceType = SourceType.CTS,
        sourceIds = listOf("SRC-KAK"),
        directionMode = "LONG_FLAT",
        differencesFromSource = listOf("Exact close/retest/threshold rules are CTS-defined and are not attributed to Koroush."),
        lifecycle = StrategyLifecycle.RESEARCH_ONLY,
        enabledForBacktest = true,
        enabledForPaper = false,
        enabledForLive = false
    )

    private val explicit = listOf(turtleOriginal, turtleSpotSafe, kakFramework, kakReference)
        .associateBy { it.strategyId }

    fun byId(id: String): StrategyDefinition? = explicit[id]

    fun forMode(mode: StrategyMode): StrategyDefinition {
        if (mode.name == "CTS_TURTLE_SPOT_SAFE") return turtleSpotSafe
        return StrategyDefinition(
            strategyId = "CTS_LEGACY_${mode.name}",
            name = "CTS Existing ${mode.name.replace('_', ' ')}",
            version = "legacy-v4",
            ruleProfileId = "CTS_LEGACY_${mode.name}",
            provenanceType = ProvenanceType.CTS_REFERENCE,
            sourceType = SourceType.CTS,
            sourceIds = emptyList(),
            performanceVerification = PerformanceVerification.NONE,
            directionMode = "CTS_EXISTING",
            lifecycle = StrategyLifecycle.IMPLEMENTED,
            enabledForBacktest = true,
            enabledForPaper = true,
            enabledForLive = false
        )
    }

    fun all(): List<StrategyDefinition> = explicit.values.sortedBy { it.strategyId }

    fun assertTruthContract() {
        require(turtleOriginal.provenanceType == ProvenanceType.SOURCE_EXACT)
        require(!turtleOriginal.enabledForLive)
        require(turtleSpotSafe.provenanceType == ProvenanceType.CTS_REFERENCE)
        require(turtleSpotSafe.differencesFromSource.isNotEmpty())
        require(!turtleSpotSafe.enabledForPaper && !turtleSpotSafe.enabledForLive) {
            "CTS_TURTLE_SPOT_SAFE must pass historical/walk-forward gates before PAPER and later shadow/live promotion."
        }
        require(kakFramework.provenanceType == ProvenanceType.SOURCE_FRAMEWORK)
        require(!kakFramework.enabledForPaper && !kakFramework.enabledForLive)
    }
}
