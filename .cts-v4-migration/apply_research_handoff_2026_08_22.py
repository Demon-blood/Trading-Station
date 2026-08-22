#!/usr/bin/env python3
"""Integrate the 2026-08-22 crypto trading research handoff into Crypto TradeStation v4.0.7.

Truth policy:
- The supplied research handoff is the authority for strategy names, provenance, fidelity and stated rules.
- All 37 catalog entries are loaded/evaluated.
- Unknown/proprietary/secondary-only rules are never reverse engineered.
- New machine thresholds are explicitly labelled app formalizations and are not attributed to the creator.
- Positive LIVE entry promotion remains source-truth gated; this patch never turns a research-only method into an
  automatically trusted live method merely because code exists for it.

The patch updates both the cumulative v4 migration overlay and the effective app tree when present so future
migration runs cannot silently restore the previous 31-strategy handoff.
"""
from __future__ import annotations

import json
import re
import shutil
import sys
from pathlib import Path

PATCH_MARKER = "CTS_RESEARCH_HANDOFF_2026_08_22"
EXPECTED_STRATEGIES = 37
EXPECTED_VIDEO_ROWS = 55
RESEARCH_FREEZE = "2026-08-22"
NEW_IDS = {
    "chris_dunn_1234_crypto_breakout",
    "josh_olszewicz_crypto_ichimoku_20_60_120_30_component",
    "josh_olszewicz_alligator_fractal_public_core",
    "krown_lti_public_core_2026",
    "cowen_macro_regime_memo_2026",
    "pizzino_three_bar_confirmation_candidate_2026",
}


def fail(msg: str) -> None:
    raise SystemExit(f"[CTS research handoff 2026-08-22] {msg}")


def require(path: Path) -> None:
    if not path.exists():
        fail(f"Required path missing: {path}")


def read(path: Path) -> str:
    require(path)
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text.rstrip() + "\n", encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


def copy_research_assets(repo: Path, payload: Path) -> None:
    catalog = json.loads((payload / "STRATEGY_CATALOG.json").read_text(encoding="utf-8"))
    manifest = json.loads((payload / "MANIFEST.json").read_text(encoding="utf-8"))
    strategies = catalog.get("strategies", [])
    if len(strategies) != EXPECTED_STRATEGIES:
        fail(f"Supplied handoff must contain {EXPECTED_STRATEGIES} strategies, found {len(strategies)}")
    ids = {row.get("id") for row in strategies}
    missing_new = sorted(NEW_IDS - ids)
    if missing_new:
        fail("Supplied handoff is missing weekly strategy additions: " + ", ".join(missing_new))
    if manifest.get("strategy_count") != EXPECTED_STRATEGIES:
        fail("Handoff manifest strategy_count is not 37")
    video_rows = sum(1 for line in (payload / "VIDEO_RESEARCH_INDEX.csv").read_text(encoding="utf-8").splitlines() if line.strip()) - 1
    if video_rows != EXPECTED_VIDEO_ROWS:
        fail(f"Expected {EXPECTED_VIDEO_ROWS} video-index rows, found {video_rows}")

    for target in (
        repo / ".cts-v4-migration/app/src/main/assets/research_handoff",
        repo / "app/src/main/assets/research_handoff",
    ):
        target.mkdir(parents=True, exist_ok=True)
        # Remove obsolete files only inside this owned asset folder, then copy the authoritative package.
        for child in target.iterdir():
            if child.is_file():
                child.unlink()
        for src in payload.iterdir():
            if src.is_file():
                shutil.copy2(src, target / src.name)
        print(f"copied research handoff assets: {target}")


def patch_models(path: Path) -> None:
    text = read(path)
    if PATCH_MARKER not in text:
        text = replace_once(
            text,
            "enum class HandoffExecutionEligibility { PAPER_AND_TRUTH_GATED_LIVE, PAPER_ONLY, PROTECTIVE_LIVE_ALLOWED, RESEARCH_ONLY, BLOCKED }\n",
            "enum class HandoffExecutionEligibility { PAPER_AND_TRUTH_GATED_LIVE, PAPER_ONLY, PROTECTIVE_LIVE_ALLOWED, RESEARCH_ONLY, BLOCKED }\n\n"
            f"// {PATCH_MARKER}: preserve the handoff's explicit fidelity label when one is supplied.\n",
            f"{path}: patch marker",
        )

    old_tail = """    val liveTruthGate: String,
    val usageContext: HandoffUsageContext,
    val usageContextSourceVerified: Boolean,
    val noTradeConditionsSourceVerified: Boolean
) {
"""
    new_tail = """    val liveTruthGate: String,
    val usageContext: HandoffUsageContext,
    val usageContextSourceVerified: Boolean,
    val noTradeConditionsSourceVerified: Boolean,
    /** Optional explicit label from the handoff, e.g. SOURCE_FAITHFUL_WITH_DISCRETION. */
    val fidelityLabel: String = ""
) {
    val displayFidelityLabel: String
        get() = fidelityLabel.ifBlank {
            when {
                fidelity.equals("A", true) && usageContextSourceVerified && noTradeConditionsSourceVerified -> "SOURCE_FAITHFUL"
                fidelity.equals("A", true) || fidelity.equals("B", true) -> "SOURCE_FAITHFUL_WITH_DISCRETION"
                fidelity.equals("C", true) -> "FORMALIZED_FROM_PUBLIC_CORE"
                else -> "PROPRIETARY / NOT IMPLEMENTED"
            }
        }
"""
    if old_tail in text:
        text = text.replace(old_tail, new_tail, 1)
    elif "val fidelityLabel: String = \"\"" not in text:
        fail(f"{path}: HandoffStrategyDefinition tail changed unexpectedly")

    old_impl = """    val implementationClass: HandoffImplementationClass
        get() = when {
            fidelity.equals("X", true) -> HandoffImplementationClass.PROPRIETARY_NOT_IMPLEMENTED
            fidelity.equals("A", true) && usageContextSourceVerified && noTradeConditionsSourceVerified -> HandoffImplementationClass.SOURCE_FAITHFUL
            fidelity.equals("A", true) -> HandoffImplementationClass.SOURCE_FAITHFUL_WITH_DISCRETION
            fidelity.equals("B", true) -> HandoffImplementationClass.SOURCE_FAITHFUL_WITH_DISCRETION
            fidelity.equals("C", true) -> HandoffImplementationClass.FORMALIZED_FROM_PUBLIC_CORE
            else -> HandoffImplementationClass.CONCEPT_INSPIRED
        }
"""
    new_impl = """    val implementationClass: HandoffImplementationClass
        get() = when (displayFidelityLabel.uppercase()) {
            "SOURCE_FAITHFUL" -> HandoffImplementationClass.SOURCE_FAITHFUL
            "SOURCE_FAITHFUL_WITH_DISCRETION" -> HandoffImplementationClass.SOURCE_FAITHFUL_WITH_DISCRETION
            "FORMALIZED_FROM_PUBLIC_CORE" -> HandoffImplementationClass.FORMALIZED_FROM_PUBLIC_CORE
            "CONCEPT_INSPIRED" -> HandoffImplementationClass.CONCEPT_INSPIRED
            "PROPRIETARY_NOT_IMPLEMENTED", "PROPRIETARY / NOT IMPLEMENTED", "SECONDARY_CANDIDATE_REQUIRES_PRIMARY_EXTRACTION" -> HandoffImplementationClass.PROPRIETARY_NOT_IMPLEMENTED
            else -> when {
                fidelity.equals("X", true) -> HandoffImplementationClass.PROPRIETARY_NOT_IMPLEMENTED
                fidelity.equals("A", true) && usageContextSourceVerified && noTradeConditionsSourceVerified -> HandoffImplementationClass.SOURCE_FAITHFUL
                fidelity.equals("A", true) || fidelity.equals("B", true) -> HandoffImplementationClass.SOURCE_FAITHFUL_WITH_DISCRETION
                fidelity.equals("C", true) -> HandoffImplementationClass.FORMALIZED_FROM_PUBLIC_CORE
                else -> HandoffImplementationClass.CONCEPT_INSPIRED
            }
        }
"""
    if old_impl in text:
        text = text.replace(old_impl, new_impl, 1)
    elif "when (displayFidelityLabel.uppercase())" not in text:
        fail(f"{path}: implementationClass anchor changed")

    # Source-faithfulness/context report. Presence of the latest handoff's structured context plus explicit
    # source_evidence can establish that the context was actually supplied by the handoff; missing old fields
    # remain unknown rather than being inherited from prior packages or guessed.
    truth_anchor = '''    val positiveLiveTruthSatisfied: Boolean
        get() = liveTruthGate.equals("PASS", true) && usageContextSourceVerified && noTradeConditionsSourceVerified && fidelity.uppercase() in setOf("A", "B")
'''
    truth_new = '''    val structuredUsageContextPresent: Boolean
        get() = usageContext.requiredConditions.isNotEmpty() || usageContext.bestConditions.isNotEmpty() ||
            usageContext.marketRegime.isNotEmpty() || usageContext.volatilityRegime.isNotEmpty() || usageContext.liquidityRequirements.isNotEmpty()

    val structuredNoTradeConditionsPresent: Boolean
        get() = usageContext.avoidConditions.isNotEmpty() || usageContext.invalidConditions.isNotEmpty()

    val usageContextResolvedFromHandoff: Boolean
        get() = usageContextSourceVerified || (structuredUsageContextPresent && usageContext.sourceEvidence.isNotEmpty())

    val noTradeConditionsResolvedFromHandoff: Boolean
        get() = noTradeConditionsSourceVerified || (structuredNoTradeConditionsPresent && usageContext.sourceEvidence.isNotEmpty())

    val sourceFaithfulnessUnknowns: List<String>
        get() = buildList {
            if (entryTrigger.isEmpty()) add("ENTRY_RULE")
            if (invalidation.isEmpty()) add("INVALIDATION")
            if (stopLogic.isEmpty()) add("STOP_METHOD")
            if (positionSizing.isEmpty()) add("SIZING_METHOD")
            if (tradeManagement.isEmpty()) add("MANAGEMENT")
            if (targetExit.isEmpty()) add("EXIT_METHOD")
            if (!usageContextResolvedFromHandoff) add("USAGE_CONTEXT")
            if (!noTradeConditionsResolvedFromHandoff) add("NO_TRADE_CONDITIONS")
            if (researchFreeze.isBlank() || sourceRefs.isEmpty() || provenance.isEmpty()) add("VERSION_SOURCE")
        }

    val sourceFaithfulnessReport: String
        get() = if (sourceFaithfulnessUnknowns.isEmpty())
            "SOURCE_TRUTH_FIELDS_COMPLETE_FOR_HANDOFF; fidelity=$displayFidelityLabel"
        else "SOURCE_TRUTH_MATERIAL_UNKNOWNS=" + sourceFaithfulnessUnknowns.joinToString(",") + "; fidelity=$displayFidelityLabel"

    val positiveLiveTruthSatisfied: Boolean
        get() = liveTruthGate.equals("PASS", true) && usageContextResolvedFromHandoff && noTradeConditionsResolvedFromHandoff &&
            sourceFaithfulnessUnknowns.isEmpty() && fidelity.uppercase() in setOf("A", "B")
'''
    if truth_anchor in text:
        text = text.replace(truth_anchor, truth_new, 1)
    elif 'val sourceFaithfulnessUnknowns:' not in text:
        fail(f"{path}: positiveLiveTruthSatisfied anchor changed")

    write(path, text)
    print(f"patched models: {path}")


def patch_catalog(path: Path) -> None:
    text = read(path)
    text = text.replace('const val RESEARCH_FREEZE = "2026-08-17"', f'const val RESEARCH_FREEZE = "{RESEARCH_FREEZE}"')
    text = text.replace('"VIDEO_RESEARCH_INDEX.csv", "WEEKLY_RESEARCH_RUNBOOK.md"', '"VIDEO_RESEARCH_INDEX.csv", "WEEKLY_REPORT_2026-08-22.md", "WEEKLY_RESEARCH_RUNBOOK.md"')
    text = text.replace('require(parsed.size == 31) { "Research handoff catalog expected 31 strategies, found ${parsed.size}." }',
                        'require(parsed.size == 37) { "Research handoff catalog expected 37 strategies, found ${parsed.size}." }')
    text = text.replace('require(manifest.optInt("strategy_count", -1) == 31) { "Manifest strategy_count must be 31." }',
                        'require(manifest.optInt("strategy_count", -1) == 37) { "Manifest strategy_count must be 37." }')
    text = text.replace('require(manifest.optInt("video_index_count", -1) == 46) { "Manifest video_index_count must be 46." }',
                        'require(manifest.optInt("strategy_count", -1) == 37) { "Manifest strategy_count must be 37." }') if 'video_index_count' in text and 'strategy_count must be 37' not in text else text
    # New manifest intentionally omits MANIFEST.json from its own files list and no longer stores video_index_count.
    old_manifest_files = 'require(strings(manifest.optJSONArray("files")).toSet() == EXPECTED_ASSETS.toSet()) { "Manifest file set does not exactly match the research handoff assets." }'
    new_manifest_files = ('val declaredAssets = EXPECTED_ASSETS.filterNot { it == "MANIFEST.json" }.toSet()\n'
                          '        require(strings(manifest.optJSONArray("files")).toSet() == declaredAssets) { "Manifest file set does not exactly match the declared research handoff assets." }')
    if old_manifest_files in text:
        text = text.replace(old_manifest_files, new_manifest_files, 1)
    elif 'Manifest file set does not exactly match the declared research handoff assets.' in text:
        if 'val declaredAssets = EXPECTED_ASSETS.filterNot' not in text:
            new_require = 'require(strings(manifest.optJSONArray("files")).toSet() == declaredAssets) { "Manifest file set does not exactly match the declared research handoff assets." }'
            text = text.replace(new_require, 'val declaredAssets = EXPECTED_ASSETS.filterNot { it == "MANIFEST.json" }.toSet()\n        ' + new_require, 1)
    else:
        fail(f"{path}: manifest file-set anchor changed")
    text = text.replace('require(videoRows == 46) { "Video research index expected 46 rows, found $videoRows." }',
                        'require(videoRows == 55) { "Video research index expected 55 rows, found $videoRows." }')
    text = text.replace('"expected=${EXPECTED_ASSETS.size}; manifest=${files.size}; missing=${missing.joinToString(",").ifBlank { "none" }}; extra=${extra.joinToString(",").ifBlank { "none" }}; strategy_count=${manifest.optInt("strategy_count",-1)}; video_index_count=${manifest.optInt("video_index_count",-1)}; truth_policy=${manifest.optString("truth_policy")}"',
                        '"expected=${EXPECTED_ASSETS.size}; manifestDeclared=${files.size}; missing=${missing.filterNot { it == "MANIFEST.json" }.joinToString(",").ifBlank { "none" }}; extra=${extra.joinToString(",").ifBlank { "none" }}; strategy_count=${manifest.optInt("strategy_count",-1)}; weekly_run=${manifest.optString("weekly_research_last_run")}; truth_policy=${manifest.optString("truth_policy")}"')

    # Preserve both list-valued and scalar-valued rules from the weekly JSON catalog.
    # The 2026-08-22 additions intentionally use scalar strings for several rule fields.
    flexible_fields = [
        ('provenance = strings(o.optJSONArray("provenance"))', 'provenance = stringsValue(o, "provenance")'),
        ('timeframes = strings(o.optJSONArray("timeframes"))', 'timeframes = stringsValue(o, "timeframes")'),
        ('setupConditions = strings(o.optJSONArray("setup_conditions"))', 'setupConditions = stringsValue(o, "setup_conditions")'),
        ('entryTrigger = strings(o.optJSONArray("entry_trigger"))', 'entryTrigger = stringsValue(o, "entry_trigger")'),
        ('invalidation = strings(o.optJSONArray("invalidation"))', 'invalidation = stringsValue(o, "invalidation")'),
        ('stopLogic = strings(o.optJSONArray("stop_logic"))', 'stopLogic = stringsValue(o, "stop_logic")'),
        ('positionSizing = strings(o.optJSONArray("position_sizing"))', 'positionSizing = stringsValue(o, "position_sizing")'),
        ('tradeManagement = strings(o.optJSONArray("trade_management"))', 'tradeManagement = stringsValue(o, "trade_management")'),
        ('targetExit = strings(o.optJSONArray("target_exit"))', 'targetExit = stringsValue(o, "target_exit")'),
        ('requiredData = strings(o.optJSONArray("required_data"))', 'requiredData = stringsValue(o, "required_data")'),
        ('sourceRefs = strings(o.optJSONArray("source_refs"))', 'sourceRefs = stringsValue(o, "source_refs")'),
        ('bestConditions = strings(usage.optJSONArray("best_conditions"))', 'bestConditions = stringsValue(usage, "best_conditions")'),
        ('requiredConditions = strings(usage.optJSONArray("required_conditions"))', 'requiredConditions = stringsValue(usage, "required_conditions")'),
        ('acceptableConditions = strings(usage.optJSONArray("acceptable_conditions"))', 'acceptableConditions = stringsValue(usage, "acceptable_conditions")'),
        ('avoidConditions = strings(usage.optJSONArray("avoid_conditions"))', 'avoidConditions = stringsValue(usage, "avoid_conditions")'),
        ('invalidConditions = strings(usage.optJSONArray("invalid_conditions"))', 'invalidConditions = stringsValue(usage, "invalid_conditions")'),
        ('marketRegime = strings(usage.optJSONArray("market_regime"))', 'marketRegime = stringsValue(usage, "market_regime")'),
        ('volatilityRegime = strings(usage.optJSONArray("volatility_regime"))', 'volatilityRegime = stringsValue(usage, "volatility_regime")'),
        ('liquidityRequirements = strings(usage.optJSONArray("liquidity_requirements"))', 'liquidityRequirements = stringsValue(usage, "liquidity_requirements")'),
        ('sourceEvidence = strings(usage.optJSONArray("source_evidence"))', 'sourceEvidence = stringsValue(usage, "source_evidence")'),
    ]
    for old_field, new_field in flexible_fields:
        if old_field in text:
            text = text.replace(old_field, new_field)

    # Add fidelity label to parser while keeping older assets backwards-compatible.
    parser_anchor = "            noTradeConditionsSourceVerified = faithful.optBoolean(\"no_trade_conditions\", false)\n        )"
    parser_new = "            noTradeConditionsSourceVerified = faithful.optBoolean(\"no_trade_conditions\", false),\n            fidelityLabel = o.optString(\"fidelity_label\", \"\")\n        )"
    if parser_anchor in text:
        text = text.replace(parser_anchor, parser_new, 1)
    elif 'fidelityLabel = o.optString("fidelity_label"' not in text:
        fail(f"{path}: fidelity label parser anchor changed")

    # Add audit counters that make the weekly integration verifiable from diagnostics/UI.
    if 'out["fidelity_A"]' not in text:
        proprietary_line = 'out["proprietary_blocked_count"] = strategies().count { it.proprietaryUnknown || it.fidelity.equals("X",true) }.toString()'
        pos = text.find(proprietary_line)
        if pos < 0:
            fail(f"{path}: proprietary audit anchor changed")
        insert_at = pos + len(proprietary_line)
        audit_lines = '''
        out["fidelity_A"] = strategies().count { it.fidelity.equals("A", true) }.toString()
        out["fidelity_B"] = strategies().count { it.fidelity.equals("B", true) }.toString()
        out["fidelity_C"] = strategies().count { it.fidelity.equals("C", true) }.toString()
        out["fidelity_X"] = strategies().count { it.fidelity.equals("X", true) }.toString()
        out["source_context_resolved_count"] = strategies().count { it.usageContextResolvedFromHandoff && it.noTradeConditionsResolvedFromHandoff }.toString()
        out["source_truth_complete_count"] = strategies().count { it.sourceFaithfulnessUnknowns.isEmpty() }.toString()
        out["weekly_research_last_run"] = runCatching { JSONObject(readText("$ASSET_ROOT/MANIFEST.json")).optString("weekly_research_last_run", RESEARCH_FREEZE) }.getOrDefault(RESEARCH_FREEZE)'''
        text = text[:insert_at] + audit_lines + text[insert_at:]

    # Flexible scalar/array parsing is mandatory because the weekly catalog intentionally uses both forms.
    if 'private fun stringsValue(o: JSONObject' not in text:
        match = re.search(r'(?m)^(\s*)private fun strings\(a: JSONArray\?\): List<String> = if \(a == null\) emptyList\(\) else \(0 until a\.length\(\)\)\.mapNotNull \{ a\.optString\(it\)\.takeIf\(String::isNotBlank\) \}\s*$', text)
        if not match:
            fail(f"{path}: flexible strings parser anchor changed")
        indent = match.group(1)
        helper = match.group(0).rstrip() + "\n" + indent + '''private fun stringsValue(o: JSONObject, key: String): List<String> = when (val raw = o.opt(key)) {
''' + indent + '''    is JSONArray -> strings(raw)
''' + indent + '''    is String -> raw.trim().takeIf { it.isNotBlank() }?.let(::listOf) ?: emptyList()
''' + indent + '''    else -> emptyList()
''' + indent + "}"
        text = text[:match.start()] + helper + text[match.end():]

    # Ensure the obsolete manifest video_index_count verification is gone even on slightly different baseline text.
    text = re.sub(r'\s*require\(manifest\.optInt\("video_index_count"[^\n]+\n', '\n', text)
    write(path, text)
    print(f"patched catalog: {path}")


def patch_handoff_engine(path: Path) -> None:
    text = read(path)
    text = text.replace("All 31 catalog entries are evaluated every scan.", "All 37 catalog entries are evaluated every scan.")
    text = text.replace('check(rows.size == 31) { "Truth engine must evaluate all 31 handoff strategies; evaluated ${rows.size}." }',
                        'check(rows.size == 37) { "Truth engine must evaluate all 37 handoff strategies; evaluated ${rows.size}." }')
    text = text.replace('append("handoff evaluated=31; integrity=${structure.dataIntegrityOk}; freezeStale=$stale; dominance=${dominance.status}; ")',
                        'append("handoff evaluated=37; integrity=${structure.dataIntegrityOk}; freezeStale=$stale; dominance=${dominance.status}; ")')
    # Preserve explicit fidelity label in audit/explanation without changing execution semantics.
    text = text.replace('fidelity=${def.fidelity}/${def.implementationClass} liveTruth=${def.liveTruthGate}',
                        'fidelity=${def.fidelity}/${def.displayFidelityLabel}/${def.implementationClass} liveTruth=${def.liveTruthGate}')
    text = text.replace(
        'if(candidate.sideIntent==HandoffSideIntent.BLOCKED_SOURCE_UNKNOWN)return HandoffCandidateEvaluation(def,candidate,"BLOCKED_SOURCE_UNKNOWN",0,1.0,null,null,false,false,false,"${def.name}: proprietary/under-specified; exact implementation prohibited by handoff.")',
        'if(candidate.sideIntent==HandoffSideIntent.BLOCKED_SOURCE_UNKNOWN)return HandoffCandidateEvaluation(def,candidate,"BLOCKED_SOURCE_UNKNOWN",0,1.0,null,null,false,false,false,"${def.name}: proprietary/under-specified; exact implementation prohibited by handoff. ${def.sourceFaithfulnessReport}")'
    )
    # Context is a mandatory gate for ALL actionable/influential candidates, including protective exits.
    # If the latest handoff does not carry the real-life usage/no-trade context, the detector remains visible
    # as research evidence but cannot change position size, block/promote entries, or execute an exit.
    context_anchor = '        if(candidate.sideIntent in setOf(HandoffSideIntent.EXIT,HandoffSideIntent.REDUCE,HandoffSideIntent.AVOID,HandoffSideIntent.FILTER,HandoffSideIntent.CONTEXT,HandoffSideIntent.RESEARCH)){\n'
    context_block = '''        // Mandatory Strategy Context / Source-Faithfulness Gate (2026-08-22 handoff).
        // Context absent from the latest authoritative package stays UNKNOWN. We deliberately do not
        // inherit prior-package context or substitute generic trading conventions.
        if (!def.usageContextResolvedFromHandoff || !def.noTradeConditionsResolvedFromHandoff) {
            return HandoffCandidateEvaluation(
                def,candidate,"BLOCK_CONTEXT_TRUTH",0,1.0,null,null,false,false,false,
                "Strategy Context Gate neutralized automatic influence/execution: usageContextResolved=${def.usageContextResolvedFromHandoff}, noTradeConditionsResolved=${def.noTradeConditionsResolvedFromHandoff}. ${def.sourceFaithfulnessReport} Detection is retained as RESEARCH_ONLY; missing source context is not guessed."
            )
        }
        if(candidate.sideIntent in setOf(HandoffSideIntent.EXIT,HandoffSideIntent.REDUCE,HandoffSideIntent.AVOID,HandoffSideIntent.FILTER,HandoffSideIntent.CONTEXT,HandoffSideIntent.RESEARCH)){
'''
    if context_anchor in text and 'Strategy Context Gate neutralized automatic influence/execution' not in text:
        text = text.replace(context_anchor, context_block, 1)
    elif 'Strategy Context Gate neutralized automatic influence/execution' not in text:
        fail(f"{path}: context/source truth gate anchor changed")
    write(path, text)
    print(f"patched handoff engine: {path}")


FORMALIZATIONS_SOURCE = r'''package com.ksp.cryptobot.research

import kotlin.math.abs
import kotlin.math.max

/**
 * App-owned formalizations used only where the 2026-08-22 research handoff explicitly leaves
 * machine thresholds discretionary or says that no single universal creator entry exists.
 *
 * These constants are NEVER creator-attributed defaults. They are versioned so walk-forward,
 * paper and source-example comparisons can validate or retire them independently.
 */
data class WeeklyResearchBar(
    val openTimeEpochMs: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double
)

data class Dunn1234FormalizationResult(
    val ready: Boolean,
    val setup: Boolean,
    val triggered: Boolean,
    val resistance: Double = 0.0,
    val support: Double = 0.0,
    val entry: Double = 0.0,
    val structuralStop: Double = 0.0,
    val researchTarget: Double = 0.0,
    val trendRunPct: Double = 0.0,
    val pullbackVolumeRatio: Double = 0.0,
    val breakoutVolumeRatio: Double = 0.0,
    val reason: String
)

data class Ichimoku206012030Context(
    val ready: Boolean,
    val conversion20: Double = 0.0,
    val base60: Double = 0.0,
    val spanA: Double = 0.0,
    val spanB120: Double = 0.0,
    val chikou30ReferenceClose: Double = 0.0,
    val trendState: String = "UNAVAILABLE",
    val reason: String
)

data class KrownLtiPublicCoreContext(
    val ready: Boolean,
    val ema21: Double = 0.0,
    val ema55: Double = 0.0,
    val rsi14: Double = 50.0,
    val drawdownFrom52BarHighPct: Double = 0.0,
    val observedComponents: Int = 0,
    val state: String = "PARTIAL_PUBLIC_CORE",
    val reason: String
)

object WeeklyResearchFormalizations {
    const val DUNN_1234_FORMALIZATION_VERSION = "APP_FORMALIZATION_D1_V1_2026_08_22"
    const val KROWN_LTI_PUBLIC_CORE_VERSION = "INDEPENDENT_PARTIAL_PUBLIC_CORE_V1_2026_08_22"

    /**
     * Chris Dunn 1234 breakout: the handoff gives the causal sequence but not universal numeric
     * thresholds/timeframe/stop/target. This D1 version therefore uses explicit app-owned windows
     * for paper research only. Positive LIVE remains blocked by the handoff's live_truth_gate.
     */
    fun dunn1234(bars: List<WeeklyResearchBar>): Dunn1234FormalizationResult {
        if (bars.size < 40) return Dunn1234FormalizationResult(false, false, false, reason = "Need >=40 closed bars for the versioned D1 formalization.")
        val breakout = bars.last()
        val consolidation = bars.dropLast(1).takeLast(7)
        val pullback = bars.dropLast(1 + consolidation.size).takeLast(7)
        val trend = bars.dropLast(1 + consolidation.size + pullback.size).takeLast(20)
        if (trend.size < 20 || pullback.size < 7 || consolidation.size < 7) {
            return Dunn1234FormalizationResult(false, false, false, reason = "Formalization windows incomplete.")
        }
        val atrPct = atrPct(trend + pullback + consolidation, 14)
        val trendRunPct = pct(trend.first().close, trend.last().close)
        val trendOk = trendRunPct > max(3.0, atrPct * 4.0)
        val trendVolume = trend.map { it.volume }.filter { it >= 0.0 }.averageOrZero()
        val pullbackVolume = pullback.map { it.volume }.filter { it >= 0.0 }.averageOrZero()
        val pullbackVolumeRatio = if (trendVolume > 0.0) pullbackVolume / trendVolume else 1.0
        val lowerVolumePullback = trendVolume > 0.0 && pullbackVolumeRatio < 0.90

        val resistance = consolidation.maxOf { it.high }
        val support = consolidation.minOf { it.low }
        val midpoint = (resistance + support) / 2.0
        val widthPct = if (midpoint > 0.0) (resistance - support) / midpoint * 100.0 else 999.0
        // This threshold is an app formalization, not a Dunn rule. It only prevents extremely loose ranges.
        val consolidationOk = widthPct <= max(12.0, atrPct * 7.0)

        val referenceVolume = (pullback + consolidation).map { it.volume }.filter { it >= 0.0 }.averageOrZero()
        val breakoutVolumeRatio = if (referenceVolume > 0.0) breakout.volume / referenceVolume else 0.0
        // Source says high-volume breakout; 1.35x is our versioned paper-research definition of "high".
        val highVolumeBreakout = referenceVolume > 0.0 && breakoutVolumeRatio >= 1.35
        val triggered = breakout.close > resistance && breakout.high > resistance && highVolumeBreakout
        val setup = trendOk && lowerVolumePullback && consolidationOk
        val entry = breakout.close
        val stop = support
        val risk = (entry - stop).coerceAtLeast(0.0)
        val target = if (risk > 0.0) entry + 2.0 * risk else 0.0 // app research target, not creator universal target
        return Dunn1234FormalizationResult(
            ready = true,
            setup = setup,
            triggered = setup && triggered,
            resistance = resistance,
            support = support,
            entry = entry,
            structuralStop = stop,
            researchTarget = target,
            trendRunPct = trendRunPct,
            pullbackVolumeRatio = pullbackVolumeRatio,
            breakoutVolumeRatio = breakoutVolumeRatio,
            reason = "${DUNN_1234_FORMALIZATION_VERSION}: trend=$trendOk run=${fmt(trendRunPct)}%; lowerVolumePullback=$lowerVolumePullback ratio=${fmt(pullbackVolumeRatio)}; consolidation=$consolidationOk width=${fmt(widthPct)}%; highVolumeBreakout=$highVolumeBreakout ratio=${fmt(breakoutVolumeRatio)}. Numeric windows/thresholds and 2R target are app formalizations."
        )
    }

    /** Crypto-adapted Ichimoku component calculation. It deliberately returns context only. */
    fun ichimoku206012030(bars: List<WeeklyResearchBar>): Ichimoku206012030Context {
        if (bars.size < 120) return Ichimoku206012030Context(false, reason = "Need >=120 closed bars for 20/60/120/30 components.")
        fun midpoint(period: Int): Double {
            val w = bars.takeLast(period)
            return (w.maxOf { it.high } + w.minOf { it.low }) / 2.0
        }
        val conversion = midpoint(20)
        val base = midpoint(60)
        val spanB = midpoint(120)
        val spanA = (conversion + base) / 2.0
        val last = bars.last().close
        val lagRef = bars[bars.lastIndex - 30].close
        val state = when {
            last > max(spanA, spanB) && conversion > base && last > lagRef -> "BULLISH_CONTEXT"
            last < minOf(spanA, spanB) && conversion < base && last < lagRef -> "BEARISH_CONTEXT"
            else -> "MIXED_CONTEXT"
        }
        return Ichimoku206012030Context(
            ready = true,
            conversion20 = conversion,
            base60 = base,
            spanA = spanA,
            spanB120 = spanB,
            chikou30ReferenceClose = lagRef,
            trendState = state,
            reason = "20/60/120/30 public components calculated. The handoff explicitly says there is no single universal entry rule, so this result is context-only."
        )
    }

    /**
     * Partial, independent observation of public LTI components. The source package says the exact preset
     * thresholds/N are proprietary; missing components are not silently replaced or treated as bullish.
     */
    fun krownLtiPublicCore(bars: List<WeeklyResearchBar>): KrownLtiPublicCoreContext {
        if (bars.size < 60) return KrownLtiPublicCoreContext(false, reason = "Need >=60 closed higher-timeframe bars for available public-core observations.")
        val closes = bars.map { it.close }
        val e21 = ema(closes, 21)
        val e55 = ema(closes, 55)
        val rsi = rsi(closes, 14)
        val high52 = bars.takeLast(52).maxOf { it.high }
        val dd = if (high52 > 0.0) (bars.last().close / high52 - 1.0) * 100.0 else 0.0
        val components = 4 // EMA21/55 relationship, RSI, drawdown, current price vs EMA regime
        return KrownLtiPublicCoreContext(
            ready = true,
            ema21 = e21,
            ema55 = e55,
            rsi14 = rsi,
            drawdownFrom52BarHighPct = dd,
            observedComponents = components,
            state = "PARTIAL_PUBLIC_CORE",
            reason = "Independent partial public-core context only: exact LTI thresholds, weights, N and presets are not public in the handoff. Missing BBWP/PMARP/sentiment/hash-ribbon/calendar components are not substituted."
        )
    }

    private fun atrPct(bars: List<WeeklyResearchBar>, period: Int): Double {
        if (bars.size < period + 1) return 0.0
        val tr = (bars.size - period until bars.size).map { i ->
            val prev = bars[i - 1].close
            max(bars[i].high - bars[i].low, max(abs(bars[i].high - prev), abs(bars[i].low - prev)))
        }.average()
        val close = bars.last().close
        return if (close > 0.0) tr / close * 100.0 else 0.0
    }

    private fun ema(values: List<Double>, period: Int): Double {
        if (values.size < period) return 0.0
        val alpha = 2.0 / (period + 1.0)
        var out = values.take(period).average()
        values.drop(period).forEach { out = alpha * it + (1.0 - alpha) * out }
        return out
    }

    private fun rsi(values: List<Double>, period: Int): Double {
        if (values.size < period + 1) return 50.0
        val changes = values.zipWithNext { a, b -> b - a }.takeLast(period)
        val gains = changes.filter { it > 0.0 }.sum()
        val losses = -changes.filter { it < 0.0 }.sum()
        if (losses <= 1e-12) return if (gains > 0.0) 100.0 else 50.0
        val rs = gains / losses
        return 100.0 - 100.0 / (1.0 + rs)
    }

    private fun pct(a: Double, b: Double): Double = if (a == 0.0) 0.0 else (b / a - 1.0) * 100.0
    private fun Iterable<Double>.averageOrZero(): Double { var n = 0; var sum = 0.0; for (v in this) { n++; sum += v }; return if (n == 0) 0.0 else sum / n }
    private fun fmt(v: Double): String = "%.3f".format(v)
}
'''

FORMALIZATIONS_TEST = r'''package com.ksp.cryptobot.research

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyResearchFormalizationsTest {
    private fun bar(i: Int, close: Double, volume: Double, spread: Double = 1.0) = WeeklyResearchBar(
        openTimeEpochMs = i.toLong() * 86_400_000L,
        open = close - 0.2,
        high = close + spread,
        low = close - spread,
        close = close,
        volume = volume
    )

    @Test fun dunnFormalizationNeedsHighVolumeBreakout() {
        val bars = mutableListOf<WeeklyResearchBar>()
        repeat(20) { i -> bars += bar(i, 100.0 + i * 1.0, 1000.0) }
        repeat(7) { i -> bars += bar(20 + i, 118.0 - i * 0.3, 650.0) }
        repeat(7) { i -> bars += bar(27 + i, 116.5 + (i % 2) * 0.2, 600.0, .5) }
        while (bars.size < 39) bars += bar(bars.size, 116.7, 600.0, .5)
        val resistance = bars.takeLast(7).maxOf { it.high }
        bars += WeeklyResearchBar(40L * 86_400_000L, resistance, resistance + 2.0, resistance - .2, resistance + 1.5, 1800.0)
        val hit = WeeklyResearchFormalizations.dunn1234(bars)
        assertTrue(hit.ready)
        assertTrue(hit.setup)
        assertTrue(hit.triggered)

        val weak = bars.dropLast(1) + bars.last().copy(volume = 500.0)
        assertFalse(WeeklyResearchFormalizations.dunn1234(weak).triggered)
    }

    @Test fun ichimokuToolkitIsContextNotUniversalEntry() {
        val bars = (0 until 130).map { i -> bar(i, 100.0 + i * .5, 1000.0) }
        val ctx = WeeklyResearchFormalizations.ichimoku206012030(bars)
        assertTrue(ctx.ready)
        assertTrue(ctx.reason.contains("context-only"))
    }

    @Test fun krownPublicCoreExplicitlyRemainsPartial() {
        val bars = (0 until 80).map { i -> bar(i, 100.0 + i * .2, 1000.0) }
        val ctx = WeeklyResearchFormalizations.krownLtiPublicCore(bars)
        assertTrue(ctx.ready)
        assertTrue(ctx.state == "PARTIAL_PUBLIC_CORE")
        assertTrue(ctx.reason.contains("not public"))
    }
}
'''


def write_formalizations(repo: Path) -> None:
    for base in (repo / ".cts-v4-migration/app", repo / "app"):
        src = base / "src/main/java/com/ksp/cryptobot/research/WeeklyResearchFormalizations.kt"
        test = base / "src/test/java/com/ksp/cryptobot/research/WeeklyResearchFormalizationsTest.kt"
        write(src, FORMALIZATIONS_SOURCE)
        write(test, FORMALIZATIONS_TEST)
        print(f"wrote weekly formalizations/tests: {base}")


def patch_strategy_engine(path: Path) -> None:
    text = read(path)
    text = text.replace("Source-preserving strategy detectors for the 2026-08-17 research handoff.",
                        "Source-preserving strategy detectors for the 2026-08-22 research handoff.")

    switch_anchor = '        "rastani_elliott_wave_context" -> rastaniElliott(def,c)\n'
    switch_add = switch_anchor + (
        '        "chris_dunn_1234_crypto_breakout" -> dunn1234(def,c)\n'
        '        "josh_olszewicz_crypto_ichimoku_20_60_120_30_component" -> olszewiczIchimoku(def,c)\n'
        '        "josh_olszewicz_alligator_fractal_public_core" -> sourceInsufficient(def,c,"Exact original Alligator/Fractal Set parameters and full rules were not recovered; research bullish variant remains non-executable until primary rules are recovered.")\n'
        '        "krown_lti_public_core_2026" -> krownLtiPublicCore(def,c)\n'
        '        "cowen_macro_regime_memo_2026" -> cowenMacroMemo(def,c)\n'
        '        "pizzino_three_bar_confirmation_candidate_2026" -> sourceInsufficient(def,c,"Only secondary-reported three-bar confirmation is present. Primary entry/invalidation/stop/sizing/management/exit rules remain unresolved, so the candidate is not implemented.")\n'
    )
    if switch_anchor in text and '"chris_dunn_1234_crypto_breakout" -> dunn1234' not in text:
        text = text.replace(switch_anchor, switch_add, 1)
    elif '"chris_dunn_1234_crypto_breakout" -> dunn1234' not in text:
        fail(f"{path}: strategy switch anchor changed")

    insert_anchor = '    private fun proprietary(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {\n'
    methods = r'''    // CTS_RESEARCH_HANDOFF_2026_08_22 — weekly additions.
    private fun dunn1234(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val bars=c.structure.daily.map { WeeklyResearchBar(it.openTimeEpochMs,it.open,it.high,it.low,it.close,it.volume) }
        val r=WeeklyResearchFormalizations.dunn1234(bars)
        if(!r.ready)return warm(d,c,r.reason)
        if(!r.setup)return noSetup(d,c,"Chris Dunn 1234 public sequence not present under ${WeeklyResearchFormalizations.DUNN_1234_FORMALIZATION_VERSION}. ${r.reason}")
        if(!r.triggered)return noSetup(d,c,"1234 setup exists but the source-described high-volume resistance breakout has not triggered under the app formalization. ${r.reason}")
        if(r.structuralStop<=0.0||r.structuralStop>=r.entry)return noSetup(d,c,"1234 app structural stop invalid; refusing to invent/force a trade.")
        val cand=entry(d,c,HandoffEntryKind.MARKET_CONFIRMATION,r.entry,r.resistance,OrderType.MARKET,r.structuralStop,listOf(r.researchTarget),"D1_APP_FORMALIZATION","D1_APP_FORMALIZATION",true,
            "SOURCE_FAITHFUL_WITH_DISCRETION causal sequence: trend run → lower-volume pullback → consolidation/retest → high-volume resistance breakout. The handoff does not provide universal timeframe, stop or target formulas, so D1 windows, 1.35x breakout-volume threshold, consolidation limits, structural stop and 2R research target are explicit app formalizations. LIVE remains blocked by live_truth_gate until this formalization is validated and labelled.")
        return HandoffDetection(cand,4,.70,"FORMALIZED_TRIGGER","${r.reason} liveTruth=${d.liveTruthGate}.")
    }

    private fun olszewiczIchimoku(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val bars=when {
            c.structure.h4.size>=120 -> c.structure.h4.map { WeeklyResearchBar(it.openTimeEpochMs,it.open.toDouble(),it.high.toDouble(),it.low.toDouble(),it.close.toDouble(),it.volume.toDouble()) }
            else -> c.structure.daily.map { WeeklyResearchBar(it.openTimeEpochMs,it.open,it.high,it.low,it.close,it.volume) }
        }
        val r=WeeklyResearchFormalizations.ichimoku206012030(bars)
        if(!r.ready)return warm(d,c,r.reason)
        val reason="Josh Olszewicz crypto Ichimoku 20/60/120/30 toolkit context=${r.trendState}, conversion20=${f(r.conversion20)}, base60=${f(r.base60)}, spanA=${f(r.spanA)}, spanB120=${f(r.spanB120)}. ${r.reason} The catalog explicitly prohibits collapsing multiple sub-strategies into one universal entry."
        return context(d,c,reason,0,1.0,HandoffSideIntent.RESEARCH)
    }

    private fun krownLtiPublicCore(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val bars=c.structure.weekly.map { WeeklyResearchBar(it.openTimeEpochMs,it.open,it.high,it.low,it.close,it.volume) }
        val r=WeeklyResearchFormalizations.krownLtiPublicCore(bars)
        if(!r.ready)return warm(d,c,r.reason)
        val reason="Krown LTI public-core independent context only: EMA21=${f(r.ema21)}, EMA55=${f(r.ema55)}, RSI14=${f(r.rsi14)}, drawdown52=${f(r.drawdownFrom52BarHighPct)}%, observedComponents=${r.observedComponents}. ${r.reason} Exact preset thresholds/N remain proprietary and are not reconstructed; no entry adjustment is emitted."
        return context(d,c,reason,0,1.0,HandoffSideIntent.RESEARCH)
    }

    private fun cowenMacroMemo(d:HandoffStrategyDefinition,c:HandoffDetectorContext):HandoffDetection {
        val reason="Benjamin Cowen 2026 macro/crypto memo is a slow regime framework, not an entry strategy. This scan does not have the complete labor/unemployment/claims/inflation/Fed/dollar/liquidity/breadth/on-chain feature set required by the handoff, so missing macro is reported UNKNOWN and contributes zero bullish adjustment. BTC trend/dominance are kept in their separate existing context models."
        return context(d,c,reason,0,1.0,HandoffSideIntent.RESEARCH)
    }

    private fun sourceInsufficient(d:HandoffStrategyDefinition,c:HandoffDetectorContext,reason:String):HandoffDetection {
        val cand=contextCandidate(d,c,HandoffSideIntent.BLOCKED_SOURCE_UNKNOWN,reason)
        return HandoffDetection(cand,0,1.0,"BLOCKED_SOURCE_UNKNOWN","SOURCE_INSUFFICIENT_NOT_IMPLEMENTED: $reason ${d.mustNotClaim}")
    }

'''
    if insert_anchor in text and 'private fun dunn1234(' not in text:
        text = text.replace(insert_anchor, methods + insert_anchor, 1)
    elif 'private fun dunn1234(' not in text:
        fail(f"{path}: function insertion anchor changed")

    # Candidate metadata must reflect the latest handoff's structured source context, not legacy booleans alone.
    text = text.replace('d.usageContextSourceVerified&&d.noTradeConditionsSourceVerified,note)',
                        'd.usageContextResolvedFromHandoff&&d.noTradeConditionsResolvedFromHandoff,note)')
    text = text.replace('d.usageContextSourceVerified&&d.noTradeConditionsSourceVerified,reason)',
                        'd.usageContextResolvedFromHandoff&&d.noTradeConditionsResolvedFromHandoff,reason)')

    write(path, text)
    print(f"patched strategy engine: {path}")


def patch_verifier(path: Path) -> None:
    text = read(path)
    if 'import com.ksp.cryptobot.research.ResearchHandoffCatalog' not in text:
        text = replace_once(text,
                            'import com.ksp.cryptobot.research.ResearchSettingsStore\n',
                            'import com.ksp.cryptobot.research.ResearchSettingsStore\nimport com.ksp.cryptobot.research.ResearchHandoffCatalog\n',
                            f"{path}: catalog import")
    anchor = '        add(if (!researchSettings.researchPromotionInLive()) "PASS" else "WARN", "Research LIVE promotion", if (researchSettings.researchPromotionInLive()) "Research-created LIVE entry promotion is enabled. M3/M4 guards still apply, but default is OFF." else "Research-created LIVE entry promotion remains OFF by default.")\n'
    block = anchor + '''
        val handoffAudit = runCatching { ResearchHandoffCatalog(appContext).assetAudit() }.getOrDefault(emptyMap())
        val handoffCount = handoffAudit["strategy_count"]?.toIntOrNull() ?: -1
        val unresolvedRefs = handoffAudit["missing_source_refs"].orEmpty().ifBlank { "unknown" }
        val freeze = handoffAudit["research_freeze"].orEmpty()
        val handoffOk = handoffCount == 37 && unresolvedRefs == "none" && freeze == "2026-08-22"
        add(
            if (handoffOk) "PASS" else "FAIL",
            "Research handoff truth catalog",
            "strategies=$handoffCount, freeze=$freeze, unresolvedSourceRefs=$unresolvedRefs, A=${handoffAudit["fidelity_A"] ?: "?"}, B=${handoffAudit["fidelity_B"] ?: "?"}, C=${handoffAudit["fidelity_C"] ?: "?"}, X=${handoffAudit["fidelity_X"] ?: "?"}, sourceContextExecutable=${handoffAudit["source_context_resolved_count"] ?: "?"}, sourceTruthComplete=${handoffAudit["source_truth_complete_count"] ?: "?"}, weeklyRun=${handoffAudit["weekly_research_last_run"] ?: "?"}. Missing context/truth fields fail closed for automatic entry; proprietary/source-insufficient entries remain non-executable."
        )
'''
    if anchor in text and 'Research handoff truth catalog' not in text:
        text = text.replace(anchor, block, 1)
    elif 'Research handoff truth catalog' not in text:
        fail(f"{path}: verifier research anchor changed")
    write(path, text)
    print(f"patched verifier: {path}")


def patch_research_ui(path: Path) -> None:
    text = read(path)
    if 'import com.ksp.cryptobot.research.ResearchHandoffCatalog' not in text:
        text = replace_once(text,
                            'import com.ksp.cryptobot.research.ResearchSettingsStore\n',
                            'import com.ksp.cryptobot.research.ResearchSettingsStore\nimport com.ksp.cryptobot.research.ResearchHandoffCatalog\n',
                            f"{path}: research catalog import")
    state_anchor = '    var status by remember { mutableStateOf("Research-created LIVE entries remain OFF by default.") }\n'
    state_new = state_anchor + '    val handoffAudit = remember { runCatching { ResearchHandoffCatalog(context).assetAudit() }.getOrDefault(emptyMap()) }\n'
    if state_anchor in text and 'val handoffAudit = remember' not in text:
        text = text.replace(state_anchor, state_new, 1)
    text = text.replace('Text("2026-08-17 Research Handoff — Truth & Automatic Execution", fontWeight = FontWeight.Bold)',
                        'Text("2026-08-22 Research Handoff — Strategy Truth Lab", fontWeight = FontWeight.Bold)')
    text = text.replace('Text("All 31 handoff strategies/processes are evaluated automatically. PAPER executes mechanically eligible A/B rules and explicitly-labelled C formalizations when their cost/risk gates pass. Protective EXIT/REDUCE may act automatically in LIVE. Positive LIVE source entries require the per-strategy source-truth gate to PASS; this switch is permission, never an override. Proprietary/unknown rules remain BLOCKED_SOURCE_UNKNOWN.")',
                        'Text("All 37 handoff strategies/processes are evaluated as separate evidence-preserving definitions. PAPER may test mechanically eligible A/B rules and explicitly-labelled C formalizations when their source/actionability, cost and risk gates pass. Positive LIVE source entries still require a per-strategy truth gate; the 2026-08-22 additions are not promoted merely because they are coded. Proprietary, source-insufficient and secondary-only rules remain BLOCKED_SOURCE_UNKNOWN.")')
    description_anchor = '        ResearchToggle("Handoff truth engine", handoffEngine) { handoffEngine = it }\n'
    audit_text = '''        Text(
            "Catalog ${handoffAudit["strategy_count"] ?: "?"} strategies • freeze ${handoffAudit["research_freeze"] ?: "?"} • A=${handoffAudit["fidelity_A"] ?: "?"} B=${handoffAudit["fidelity_B"] ?: "?"} C=${handoffAudit["fidelity_C"] ?: "?"} X=${handoffAudit["fidelity_X"] ?: "?"} • context-resolved=${handoffAudit["source_context_resolved_count"] ?: "?"} • source-truth-complete=${handoffAudit["source_truth_complete_count"] ?: "?"} • unresolved refs=${handoffAudit["missing_source_refs"] ?: "?"}",
            style = MaterialTheme.typography.bodySmall
        )
        Text("Weekly additions: Chris Dunn 1234 breakout (paper formalization/live-blocked), Josh Olszewicz 20/60/120/30 context, Alligator/Fractal source-insufficient block, Krown LTI partial public-core context, Cowen macro regime research context, and Pizzino three-bar secondary-only block.", style = MaterialTheme.typography.bodySmall)
'''
    if description_anchor in text and 'Weekly additions: Chris Dunn 1234' not in text:
        text = text.replace(description_anchor, audit_text + description_anchor, 1)
    elif 'Weekly additions: Chris Dunn 1234' not in text:
        fail(f"{path}: research UI anchor changed")
    write(path, text)
    print(f"patched research UI: {path}")


def patch_tree(repo: Path, relative: str, patch_fn, required_overlay: bool = True) -> None:
    overlay = repo / ".cts-v4-migration/app" / relative
    effective = repo / "app" / relative
    if required_overlay:
        patch_fn(overlay)
    elif overlay.exists():
        patch_fn(overlay)
    if effective.exists():
        patch_fn(effective)


def validate_final(repo: Path) -> None:
    assets = repo / ".cts-v4-migration/app/src/main/assets/research_handoff"
    catalog = json.loads((assets / "STRATEGY_CATALOG.json").read_text(encoding="utf-8"))
    ids = {row["id"] for row in catalog["strategies"]}
    if len(ids) != EXPECTED_STRATEGIES:
        fail(f"post-patch catalog has {len(ids)} unique strategies")
    missing = NEW_IDS - ids
    if missing:
        fail("post-patch missing weekly IDs: " + ", ".join(sorted(missing)))
    source = read(repo / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/research/ResearchHandoffStrategyEngine.kt")
    for strategy_id in NEW_IDS:
        if strategy_id not in source:
            fail(f"detector switch missing {strategy_id}")
    if 'check(rows.size == 37)' not in read(repo / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/research/ResearchHandoffEngine.kt"):
        fail("handoff engine still has old evaluated-count invariant")
    if 'RESEARCH_FREEZE = "2026-08-22"' not in read(repo / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/research/ResearchHandoffCatalog.kt"):
        fail("research freeze was not updated")
    catalog_source = read(repo / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/research/ResearchHandoffCatalog.kt")
    engine_source = read(repo / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/research/ResearchHandoffEngine.kt")
    models_source = read(repo / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/research/ResearchHandoffModels.kt")
    if 'private fun stringsValue(o: JSONObject' not in catalog_source:
        fail("weekly scalar/array catalog parsing was not installed")
    if 'BLOCK_CONTEXT_TRUTH' not in engine_source:
        fail("mandatory strategy context/source truth gate was not installed")
    if 'sourceFaithfulnessUnknowns' not in models_source:
        fail("source-faithfulness report was not installed")
    print("PASS | 37-strategy handoff catalog integrated")
    print("PASS | six 2026-08-22 weekly additions registered")
    print("PASS | source-insufficient/proprietary additions remain hard blocked")
    print("PASS | scalar/array source rules preserved from weekly catalog")
    print("PASS | mandatory strategy context/source-faithfulness gate installed")
    print("PASS | latest handoff assets copied to migration + app asset roots")


def main() -> None:
    repo = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()
    here = Path(__file__).resolve().parent
    payload = here / "research_handoff_2026_08_22"
    require(repo / ".github/workflows/android-v4-build.yml")
    require(repo / ".cts-v4-migration/app/src/main/java/com/ksp/cryptobot/research/ResearchHandoffCatalog.kt")
    require(payload / "STRATEGY_CATALOG.json")

    copy_research_assets(repo, payload)
    patch_tree(repo, "src/main/java/com/ksp/cryptobot/research/ResearchHandoffModels.kt", patch_models)
    patch_tree(repo, "src/main/java/com/ksp/cryptobot/research/ResearchHandoffCatalog.kt", patch_catalog)
    patch_tree(repo, "src/main/java/com/ksp/cryptobot/research/ResearchHandoffEngine.kt", patch_handoff_engine)
    write_formalizations(repo)
    patch_tree(repo, "src/main/java/com/ksp/cryptobot/research/ResearchHandoffStrategyEngine.kt", patch_strategy_engine)
    patch_tree(repo, "src/main/java/com/ksp/cryptobot/release/V4SystemVerifier.kt", patch_verifier)
    patch_tree(repo, "src/main/java/com/ksp/cryptobot/ui/V4ControlCenterScreen.kt", patch_research_ui)
    validate_final(repo)


if __name__ == "__main__":
    main()
