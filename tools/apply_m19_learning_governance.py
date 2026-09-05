#!/usr/bin/env python3
from pathlib import Path
import os, sys

PAYLOAD_FILES = [
    "app/src/main/java/com/ksp/cryptobot/governance/LearningGovernanceEngine.kt",
    "app/src/test/java/com/ksp/cryptobot/governance/LearningMonotonicPolicyM19Test.kt",
    "app/src/test/java/com/ksp/cryptobot/governance/LearningGovernanceDriftM19Test.kt",
    "app/src/test/java/com/ksp/cryptobot/governance/LearningGovernanceStatisticsM19Test.kt",
]

def fail(msg):
    raise SystemExit("ERROR | " + msg)

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

def replace_function(text, signature, replacement, label):
    start = text.find(signature)
    if start < 0:
        fail(f"{label}: function signature not found")
    next_private = text.find("\n    private fun ", start + len(signature))
    if next_private < 0:
        class_end = text.rfind("\n}")
        if class_end < 0 or class_end <= start:
            fail(f"{label}: unable to locate function end")
        end = class_end
    else:
        end = next_private
    return text[:start] + replacement.rstrip() + "\n" + text[end:]

def main():
    print("INFO | M19 applier revision v1")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")

    truth = repo / "app/src/main/java/com/ksp/cryptobot/strategy/StrategyTruthRegistry.kt"
    if not truth.exists():
        fail("M18 prerequisite missing from source tree: StrategyTruthRegistry.kt")
    truth_text = truth.read_text(encoding="utf-8")
    if "M18 strategy truth registry" not in truth_text or "fun autoSelectable()" not in truth_text:
        fail("M18 prerequisite is not the validated truth-gated implementation")

    dirty = os.popen(f'cd "{repo}" && git status --porcelain -- app').read().strip()
    if dirty:
        fail("Refusing to patch dirty app tree:\n" + dirty)

    payload = Path(__file__).resolve().parent / "m19_payload"
    for rel in PAYLOAD_FILES:
        src = payload / rel
        dst = repo / rel
        if not src.exists():
            fail(f"M19 payload missing: {rel}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(src.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
        copied = dst.read_bytes()
        if copied.endswith(b"\\n"):
            fail(f"M19 payload copy produced literal backslash-n EOF: {rel}")
        if not copied.endswith(b"\n"):
            fail(f"M19 payload copy missing real newline EOF: {rel}")
        print("WRITE |", rel)

    p = repo / "app/src/main/java/com/ksp/cryptobot/learning/TrueSelfLearningEngine.kt"
    t = p.read_text(encoding="utf-8")

    t = replace_once(
        t,
        '''import com.ksp.cryptobot.data.AppDao
''',
        '''import com.ksp.cryptobot.data.AppDao
import com.ksp.cryptobot.data.GovernanceDao
import com.ksp.cryptobot.data.ProductionIntelligenceStateEntity
import com.ksp.cryptobot.governance.LearningGovernanceEngine
import com.ksp.cryptobot.governance.LearningGovernanceRuntime
import com.ksp.cryptobot.governance.LearningMonotonicPolicy
import com.ksp.cryptobot.strategy.StrategyTruthRegistry
''',
        "M19 self-learning imports"
    )

    t = replace_once(
        t,
        '''class TrueSelfLearningEngine {
''',
        '''class TrueSelfLearningEngine(
    private val governanceDao: GovernanceDao? = null
) {
    private val learningGovernance = LearningGovernanceEngine()
''',
        "M19 self-learning governance constructor"
    )

    t = replace_once(
        t,
        '''        val completedOutcomes = completedOutcomeTradesForLearning(allTrades, settings)
        val now = System.currentTimeMillis()
        val symbolProfiles = completedOutcomes.groupBy { it.symbol.uppercase() }.map { (symbol, rows) ->
            buildSymbolProfile(symbol, rows, settings, now).also { profile ->
                dao.upsertLearnedSymbolProfile(profile)
                dao.insertSelfLearningAudit(SelfLearningAuditEntity(timestampEpochMs = now, eventType = "PROFILE_UPDATE", symbol = symbol, message = profile.explanation))
            }
        }

        val strategyProfiles = completedOutcomes.groupBy { strategyKeyFromTrade(it) }.map { (strategy, rows) ->
            buildStrategyProfile(strategy, rows, settings, now).also { profile -> dao.upsertLearnedStrategyProfile(profile) }
        }

        val holdProfiles = learningHistory.groupBy { it.symbol.uppercase() }.map { (symbol, rows) ->
            buildHoldProfile(symbol, rows, settings, now).also { profile ->
                dao.upsertLearnedHoldProfile(profile)
                dao.insertSelfLearningAudit(SelfLearningAuditEntity(timestampEpochMs = now, eventType = "HOLD_PROFILE_UPDATE", symbol = symbol, message = profile.explanation))
            }
        }
''',
        '''        val completedOutcomes = completedOutcomeTradesForLearning(allTrades, settings)
        val now = System.currentTimeMillis()

        val featureSnapshots = dao.learningFeatureSnapshots(400)
        val executionRows = governanceDao?.let { governance ->
            runCatching { governance.recentExecutionQuality(250) }.getOrDefault(emptyList())
        } ?: emptyList()
        val modelRows = governanceDao?.let { governance ->
            runCatching { governance.resolvedAiAttributions(1000) }.getOrDefault(emptyList())
        } ?: emptyList()

        val governance = learningGovernance.assess(
            mode = settings.mode,
            snapshots = featureSnapshots,
            completedOutcomes = completedOutcomes,
            executionRows = executionRows,
            aiRows = modelRows
        )
        LearningGovernanceRuntime.install(governance)
        dao.insertSelfLearningAudit(
            SelfLearningAuditEntity(
                timestampEpochMs = now,
                eventType = "M19_GOVERNANCE",
                symbol = "*",
                message = governance.reason
            )
        )
        governanceDao?.let { governanceStore ->
            runCatching {
                governanceStore.putState(
                    ProductionIntelligenceStateEntity(
                        key = "m19_learning_governance",
                        value = governance.compactState(),
                        updatedAtEpochMs = now
                    )
                )
            }
        }

        val symbolProfiles = completedOutcomes.groupBy { it.symbol.uppercase() }.map { (symbol, rows) ->
            val raw = buildSymbolProfile(symbol, rows, settings, now)
            val preferredMode = runCatching {
                StrategyMode.valueOf(raw.preferredStrategy)
            }.getOrDefault(StrategyMode.AUTO)
            val safePreferred =
                if (preferredMode == StrategyMode.AUTO ||
                    StrategyTruthRegistry.spec(preferredMode)?.liveSelectable == true
                ) raw.preferredStrategy else StrategyMode.AUTO.name
            raw.copy(
                scoreAdjustment = governance.clampScoreAdjustment(raw.scoreAdjustment),
                positionMultiplier = governance
                    .clampPositionMultiplier(
                        raw.positionMultiplier.toBigDecimalOrNull() ?: BigDecimal.ONE,
                        settings.mode
                    )
                    .stripTrailingZeros()
                    .toPlainString(),
                preferredStrategy = safePreferred,
                explanation = raw.explanation + " | " + governance.reason
            ).also { profile ->
                dao.upsertLearnedSymbolProfile(profile)
                dao.insertSelfLearningAudit(
                    SelfLearningAuditEntity(
                        timestampEpochMs = now,
                        eventType = "PROFILE_UPDATE",
                        symbol = symbol,
                        message = profile.explanation
                    )
                )
            }
        }

        val strategyProfiles = completedOutcomes.groupBy { strategyKeyFromTrade(it) }.map { (strategy, rows) ->
            val raw = buildStrategyProfile(strategy, rows, settings, now)
            val strategyMode = runCatching { StrategyMode.valueOf(strategy) }.getOrNull()
            val truthSelectable =
                strategyMode != null &&
                    strategyMode != StrategyMode.AUTO &&
                    StrategyTruthRegistry.spec(strategyMode)?.liveSelectable == true
            raw.copy(
                scoreAdjustment = if (truthSelectable) {
                    governance.clampScoreAdjustment(raw.scoreAdjustment)
                } else {
                    raw.scoreAdjustment.coerceAtMost(0)
                },
                positionMultiplier = governance
                    .clampPositionMultiplier(
                        raw.positionMultiplier.toBigDecimalOrNull() ?: BigDecimal.ONE,
                        settings.mode
                    )
                    .stripTrailingZeros()
                    .toPlainString(),
                explanation = raw.explanation +
                    " | M19 truthSelectable=$truthSelectable; stage=${governance.stage}."
            ).also { profile ->
                dao.upsertLearnedStrategyProfile(profile)
            }
        }

        val holdProfiles = learningHistory.groupBy { it.symbol.uppercase() }.map { (symbol, rows) ->
            val raw = buildHoldProfile(symbol, rows, settings, now)
            raw.copy(
                shouldDeferTakeProfit =
                    raw.shouldDeferTakeProfit && governance.positiveLearningEnabled,
                shouldDeferTrailingExit =
                    raw.shouldDeferTrailingExit && governance.positiveLearningEnabled,
                explanation = raw.explanation +
                    " | M19 stage=${governance.stage}; positiveLearning=${governance.positiveLearningEnabled}."
            ).also { profile ->
                dao.upsertLearnedHoldProfile(profile)
                dao.insertSelfLearningAudit(
                    SelfLearningAuditEntity(
                        timestampEpochMs = now,
                        eventType = "HOLD_PROFILE_UPDATE",
                        symbol = symbol,
                        message = profile.explanation
                    )
                )
            }
        }
''',
        "M19 refresh governance and bounded profile persistence"
    )

    t = replace_once(
        t,
        '''        val summary = "Learning refreshed: symbols=${symbolProfiles.size}, strategies=${strategyProfiles.size}, holdProfiles=${holdProfiles.size}, completedOutcomes=${completedOutcomes.size}, historyRows=${learningHistory.size}, liveOutcomes=$liveCount, paperOutcomes=$paperCount, mode=$separation. Min sample=${settings.selfLearningMinSamples}."
''',
        '''        val summary = "Learning refreshed: symbols=${symbolProfiles.size}, strategies=${strategyProfiles.size}, holdProfiles=${holdProfiles.size}, completedOutcomes=${completedOutcomes.size}, historyRows=${learningHistory.size}, liveOutcomes=$liveCount, paperOutcomes=$paperCount, mode=$separation. Min sample=${settings.selfLearningMinSamples}. M19 stage=${governance.stage}, positiveLearning=${governance.positiveLearningEnabled}, rollback=${governance.rollbackRequired}."
''',
        "M19 learning summary governance state"
    )

    t = replace_once(
        t,
        '''                profile,
                profile.positionMultiplier.toBigDecimalOrNull() ?: BigDecimal.ONE,
                explanation
''',
        '''                profile,
                LearningGovernanceRuntime.snapshot().clampPositionMultiplier(
                    profile.positionMultiplier.toBigDecimalOrNull() ?: BigDecimal.ONE,
                    settings.mode
                ),
                explanation
''',
        "M19 disabled profile size clamp"
    )

    t = replace_once(
        t,
        '''        val rawBoost = profile.scoreAdjustment.coerceIn(-settings.selfLearningMaxScorePenalty, settings.selfLearningMaxScoreBoost)
        val learnedScore = (decision.finalScore + rawBoost).coerceIn(0, 100)
        val action = learnedAction(decision.finalAction, learnedScore, settings)
        val spreadPenalty = spreadPenalty(ticker, settings)
        val finalScore = (learnedScore - spreadPenalty).coerceIn(0, 100)
        val finalAction = learnedAction(action, finalScore, settings)
        val allowed = decision.allowedToTrade && (finalAction == SignalAction.BUY || finalAction == SignalAction.SMALL_BUY || finalAction == SignalAction.SELL)
        val multiplier = (profile.positionMultiplier.toBigDecimalOrNull() ?: BigDecimal.ONE).coerceIn(BigDecimal("0.10"), BigDecimal("1.50"))
        val explanation = "Self-learning applied: samples=${profile.sampleSize}, win=${profile.winRatePercent}%, pf=${profile.profitFactor}, scoreAdj=$rawBoost, spreadPenalty=$spreadPenalty, size×${multiplier.stripTrailingZeros().toPlainString()}, preferred=${profile.preferredStrategy}."
''',
        '''        val governance = LearningGovernanceRuntime.snapshot()
        val rawProfileAdjustment = profile.scoreAdjustment
            .coerceIn(-settings.selfLearningMaxScorePenalty, settings.selfLearningMaxScoreBoost)
        val rawBoost = if (rawProfileAdjustment > 0) {
            rawProfileAdjustment.coerceAtMost(governance.bounds.scoreBoostCeiling)
        } else rawProfileAdjustment
        val learnedScore = (decision.finalScore + rawBoost).coerceIn(0, 100)
        val action = learnedAction(decision.finalAction, learnedScore, settings)
        val spreadPenalty = spreadPenalty(ticker, settings)
        val finalScore = (learnedScore - spreadPenalty).coerceIn(0, 100)
        val finalAction = learnedAction(action, finalScore, settings)
        val allowed = decision.allowedToTrade &&
            (finalAction == SignalAction.BUY ||
                finalAction == SignalAction.SMALL_BUY ||
                finalAction == SignalAction.SELL)
        val multiplier = governance.clampPositionMultiplier(
            profile.positionMultiplier.toBigDecimalOrNull() ?: BigDecimal.ONE,
            settings.mode
        )
        val explanation = "M19 governed self-learning: stage=${governance.stage}, samples=${profile.sampleSize}, win=${profile.winRatePercent}%, pf=${profile.profitFactor}, rawScoreAdj=$rawProfileAdjustment, appliedScoreAdj=$rawBoost, spreadPenalty=$spreadPenalty, size×${multiplier.stripTrailingZeros().toPlainString()}, preferred=${profile.preferredStrategy}, positiveLearning=${governance.positiveLearningEnabled}."
''',
        "M19 adjustDecision monotonic bounds"
    )

    t = replace_once(
        t,
        '''            if (preferred != StrategyMode.AUTO) {
                val adj = symbolProfile.scoreAdjustment.coerceIn(-settings.adaptiveStrategyMaxScorePenalty, settings.adaptiveStrategyMaxScoreBoost)
                val mult = symbolProfile.positionMultiplier.toBigDecimalOrNull() ?: BigDecimal.ONE
''',
        '''            val truthSelectable =
                preferred != StrategyMode.AUTO &&
                    StrategyTruthRegistry.spec(preferred)?.liveSelectable == true
            if (truthSelectable) {
                val governance = LearningGovernanceRuntime.snapshot()
                val rawAdj = symbolProfile.scoreAdjustment
                    .coerceIn(-settings.adaptiveStrategyMaxScorePenalty, settings.adaptiveStrategyMaxScoreBoost)
                val adj = if (rawAdj > 0) rawAdj.coerceAtMost(governance.bounds.scoreBoostCeiling) else rawAdj
                val mult = governance.clampPositionMultiplier(
                    symbolProfile.positionMultiplier.toBigDecimalOrNull() ?: BigDecimal.ONE,
                    settings.mode
                )
''',
        "M19 symbol adaptive truth gate"
    )

    t = replace_once(
        t,
        '''                    scoreAdjustment = adj,
                    positionMultiplier = mult.coerceIn(BigDecimal("0.25"), BigDecimal("1.50")),
                    explanation = "Adaptive strategy for $normalized: symbol profile prefers $preferred after ${symbolProfile.sampleSize} samples; scoreAdj=$adj, size×$mult. ${symbolProfile.explanation}"
''',
        '''                    scoreAdjustment = adj,
                    positionMultiplier = mult,
                    explanation = "Adaptive strategy for $normalized: symbol profile prefers $preferred after ${symbolProfile.sampleSize} samples; scoreAdj=$adj, size×$mult. ${symbolProfile.explanation}"
''',
        "M19 symbol adaptive multiplier clamp"
    )

    t = replace_once(
        t,
        '''        val bestStrategy = strategyProfiles
            .filter { it.sampleSize >= settings.adaptiveStrategyMinSamples }
            .maxWithOrNull(compareBy<LearnedStrategyProfileEntity> { it.scoreAdjustment }.thenBy { it.profitFactor.toBigDecimalOrNull() ?: BigDecimal.ZERO })
''',
        '''        val bestStrategy = strategyProfiles
            .filter { it.sampleSize >= settings.adaptiveStrategyMinSamples }
            .filter { profile ->
                val mode = runCatching { StrategyMode.valueOf(profile.strategyKey) }.getOrNull()
                mode != null &&
                    mode != StrategyMode.AUTO &&
                    StrategyTruthRegistry.spec(mode)?.liveSelectable == true
            }
            .maxWithOrNull(
                compareBy<LearnedStrategyProfileEntity> { it.scoreAdjustment }
                    .thenBy { it.profitFactor.toBigDecimalOrNull() ?: BigDecimal.ZERO }
            )
''',
        "M19 global adaptive truth gate"
    )

    t = replace_once(
        t,
        '''            if (mode != StrategyMode.AUTO) {
                val mult = bestStrategy.positionMultiplier.toBigDecimalOrNull() ?: BigDecimal.ONE
                val conf = bestStrategy.winRatePercent.toBigDecimalOrNull()?.toInt() ?: settings.adaptiveStrategySwitchConfidencePercent
                return AdaptiveStrategyResult(
                    selectedStrategy = if (fallback == StrategyMode.AUTO) mode else fallback,
                    source = "GLOBAL_STRATEGY_PROFILE",
                    confidencePercent = conf.coerceIn(0, 100),
                    scoreAdjustment = bestStrategy.scoreAdjustment.coerceIn(-settings.adaptiveStrategyMaxScorePenalty, settings.adaptiveStrategyMaxScoreBoost),
                    positionMultiplier = mult.coerceIn(BigDecimal("0.25"), BigDecimal("1.50")),
''',
        '''            if (mode != StrategyMode.AUTO &&
                StrategyTruthRegistry.spec(mode)?.liveSelectable == true
            ) {
                val governance = LearningGovernanceRuntime.snapshot()
                val mult = governance.clampPositionMultiplier(
                    bestStrategy.positionMultiplier.toBigDecimalOrNull() ?: BigDecimal.ONE,
                    settings.mode
                )
                val conf = bestStrategy.winRatePercent.toBigDecimalOrNull()?.toInt()
                    ?: settings.adaptiveStrategySwitchConfidencePercent
                val rawAdj = bestStrategy.scoreAdjustment
                    .coerceIn(-settings.adaptiveStrategyMaxScorePenalty, settings.adaptiveStrategyMaxScoreBoost)
                val appliedAdj = if (rawAdj > 0) {
                    rawAdj.coerceAtMost(governance.bounds.scoreBoostCeiling)
                } else rawAdj
                return AdaptiveStrategyResult(
                    selectedStrategy = if (fallback == StrategyMode.AUTO) mode else fallback,
                    source = "GLOBAL_STRATEGY_PROFILE",
                    confidencePercent = conf.coerceIn(0, 100),
                    scoreAdjustment = appliedAdj,
                    positionMultiplier = mult,
''',
        "M19 global adaptive governed bounds"
    )

    t = replace_once(
        t,
        '''        val combinedAdj = (strategyResult.scoreAdjustment + strategyAdj)
            .coerceIn(-settings.adaptiveStrategyMaxScorePenalty, settings.adaptiveStrategyMaxScoreBoost)
        val newScore = (automation.finalScore + combinedAdj).coerceIn(0, 100)
        val learnedMultiplier = strategyResult.positionMultiplier.multiply(strategyProfile?.positionMultiplier?.toBigDecimalOrNull() ?: BigDecimal.ONE)
            .coerceIn(BigDecimal("0.25"), BigDecimal("1.60"))
        val adjustedSize = automation.positionSizeEur.multiply(learnedMultiplier).setScale(2, RoundingMode.DOWN)
        val action = learnedAction(automation.finalAction, newScore, settings)
''',
        '''        val governance = LearningGovernanceRuntime.snapshot()
        val rawCombinedAdj = (strategyResult.scoreAdjustment + strategyAdj)
            .coerceIn(-settings.adaptiveStrategyMaxScorePenalty, settings.adaptiveStrategyMaxScoreBoost)
        val combinedAdj = if (rawCombinedAdj > 0) {
            rawCombinedAdj.coerceAtMost(governance.bounds.scoreBoostCeiling)
        } else rawCombinedAdj
        val newScore = (automation.finalScore + combinedAdj).coerceIn(0, 100)
        val rawMultiplier = strategyResult.positionMultiplier
            .multiply(strategyProfile?.positionMultiplier?.toBigDecimalOrNull() ?: BigDecimal.ONE)
        val learnedMultiplier = governance.clampPositionMultiplier(rawMultiplier, settings.mode)
        val learnedSize = automation.positionSizeEur
            .multiply(learnedMultiplier)
            .setScale(2, RoundingMode.DOWN)
        val adjustedSize = if (settings.mode == BotMode.PAPER) {
            learnedSize
        } else {
            learnedSize.min(automation.positionSizeEur)
        }
        val action = learnedAction(automation.finalAction, newScore, settings)
''',
        "M19 adaptive automation bounded size/score"
    )

    t = replace_once(
        t,
        '''            explanation = automation.explanation + " Adaptive multi-strategy learning: selected=${automation.selectedStrategy}, source=${strategyResult.source}, scoreAdj=$combinedAdj, size×$learnedMultiplier. ${strategyResult.explanation}"
''',
        '''            explanation = automation.explanation + " M19 governed adaptive learning: stage=${governance.stage}, selected=${automation.selectedStrategy}, source=${strategyResult.source}, rawScoreAdj=$rawCombinedAdj, appliedScoreAdj=$combinedAdj, size×$learnedMultiplier. LIVE cannot exceed deterministic pre-learning size. ${strategyResult.explanation}"
''',
        "M19 adaptive automation explanation"
    )

    learned_signature = "    private fun learnedAction(current: SignalAction, score: Int, settings: BotSettings): SignalAction {"
    learned_replacement = '''    private fun learnedAction(
        current: SignalAction,
        score: Int,
        settings: BotSettings
    ): SignalAction =
        LearningMonotonicPolicy.action(
            current = current,
            score = score,
            minBuyScore = settings.minStrategyScoreToBuy
        )'''
    t = replace_function(
        t,
        learned_signature,
        learned_replacement,
        "M19 learnedAction monotonic authority"
    )

    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/core/BotController.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''    private val selfLearningEngine = TrueSelfLearningEngine()
''',
        '''    private val selfLearningEngine = TrueSelfLearningEngine(
        AppDatabase.get(appContext).governanceDao()
    )
''',
        "M19 BotController governed self-learning construction"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/execution/MarketMicrostructureEngine.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''        return (
            0.10 +
                0.35 * placementScore +
                0.25 * opposingPressure +
                0.20 * calibrationScore +
                0.10 * spreadScore
            ).coerceIn(0.02, 0.98)
''',
        '''        val governanceOffset =
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
''',
        "M19 governed fill probability offset"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/execution/SmartOrderLifecycleManager.kt"
    t = p.read_text(encoding="utf-8")
    t = replace_once(
        t,
        '''        val learned = (meanFillSeconds * 1.25).roundToLong()
''',
        '''        val governanceMultiplier =
            com.ksp.cryptobot.governance.LearningGovernanceRuntime
                .snapshot()
                .bounds
                .staleTimingMultiplier
                .coerceIn(0.75, 1.0)
        val learned = (meanFillSeconds * 1.25 * governanceMultiplier).roundToLong()
''',
        "M19 governed fill timing bound"
    )

    t = replace_once(
        t,
        '''            val microAllowsReprice = micro?.let {
                it.valid &&
                    it.makerFillProbability < 0.60 &&
                    it.adverseSelectionRisk < 0.65
            } ?: false
''',
        '''            val governedFillThreshold =
                com.ksp.cryptobot.governance.LearningGovernanceRuntime
                    .snapshot()
                    .bounds
                    .amendFillProbabilityThreshold
                    .coerceIn(0.45, 0.60)
            val microAllowsReprice = micro?.let {
                it.valid &&
                    it.makerFillProbability < governedFillThreshold &&
                    it.adverseSelectionRisk < 0.65
            } ?: false
''',
        "M19 governed amend fill threshold"
    )
    p.write_text(t, encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    changed = set(os.popen(f'cd "{repo}" && git diff --name-only -- app').read().splitlines())
    untracked = set(os.popen(f'cd "{repo}" && git ls-files --others --exclude-standard -- app').read().splitlines())
    actual = changed | untracked
    allowed = set(PAYLOAD_FILES) | {
        "app/src/main/java/com/ksp/cryptobot/learning/TrueSelfLearningEngine.kt",
        "app/src/main/java/com/ksp/cryptobot/core/BotController.kt",
        "app/src/main/java/com/ksp/cryptobot/execution/MarketMicrostructureEngine.kt",
        "app/src/main/java/com/ksp/cryptobot/execution/SmartOrderLifecycleManager.kt",
    }
    if actual - allowed:
        fail("Unexpected M19 app changes: " + ",".join(sorted(actual - allowed)))
    if allowed - actual:
        fail("Expected M19 changes missing: " + ",".join(sorted(allowed - actual)))

    print("PASS | M19 controlled app diff.")

if __name__ == "__main__":
    main()
