package com.ksp.cryptobot.research

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class ResearchHandoffCatalog(private val context: Context) {
    companion object {
        const val ASSET_ROOT = "research_handoff"
        const val CATALOG = "$ASSET_ROOT/STRATEGY_CATALOG.json"
        const val SOURCE_REGISTRY = "$ASSET_ROOT/SOURCE_REGISTRY.json"
        const val RESEARCH_FREEZE = "2026-08-17"
        val EXPECTED_ASSETS = listOf(
            "BELGIUM_KRAKEN_CONSTRAINTS.md", "EVIDENCE_MATRIX.md", "HANDOFF_PROMPT.md",
            "IMPLEMENTATION_SPEC.md", "MANIFEST.json", "README.md", "RESEARCH_SOURCES.md",
            "SOURCE_REGISTRY.json", "STRATEGY_CATALOG.json", "STRATEGY_TRUTH_STANDARD.md",
            "TRADER_DUE_DILIGENCE.md", "UNVERIFIED_AND_PROPRIETARY.md", "VIDEO_EXTRACTION_NOTES.md",
            "VIDEO_RESEARCH_INDEX.csv", "WEEKLY_RESEARCH_RUNBOOK.md"
        )
        // The handoff catalog uses seven semantic source aliases that point to broader
        // primary-host/channel entries in SOURCE_REGISTRY.json. These mappings are explicit,
        // auditable and never manufacture a new source or trading rule.
        val SOURCE_REF_ALIASES = mapOf(
            "tcg_equilibrium" to "tcg_video_library",
            "tcg_inside_bar" to "tcg_video_library",
            "tcg_correlations" to "tcg_video_library",
            "loukas_current" to "loukas_channel",
            "loukas_cycles_trader" to "loukas_channel",
            "rastani_opening_gap" to "rastani_site",
            "rastani_elliott" to "rastani_channel"
        )
    }

    @Volatile private var cached: List<HandoffStrategyDefinition>? = null

    fun strategies(): List<HandoffStrategyDefinition> = cached ?: synchronized(this) {
        cached ?: parseCatalog(readText(CATALOG)).also { parsed ->
            require(parsed.size == 31) { "Research handoff catalog expected 31 strategies, found ${parsed.size}." }
            cached = parsed
        }
    }

    fun definition(id: String): HandoffStrategyDefinition? = strategies().firstOrNull { it.id == id }

    fun verifyIntegrityOrThrow() {
        val audit = assetAudit()
        val missingAssets = EXPECTED_ASSETS.filter { audit[it]?.startsWith("MISSING") != false }
        require(missingAssets.isEmpty()) { "Research handoff is incomplete; missing assets: ${missingAssets.joinToString()}" }
        require(audit["missing_source_refs"] == "none") { "Research handoff has unresolved source references: ${audit["missing_source_refs"]}" }
        val manifest = JSONObject(readText("$ASSET_ROOT/MANIFEST.json"))
        require(manifest.optInt("strategy_count", -1) == 31) { "Manifest strategy_count must be 31." }
        require(manifest.optInt("video_index_count", -1) == 46) { "Manifest video_index_count must be 46." }
        require(strings(manifest.optJSONArray("files")).toSet() == EXPECTED_ASSETS.toSet()) { "Manifest file set does not exactly match the research handoff assets." }
        val csv = readText("$ASSET_ROOT/VIDEO_RESEARCH_INDEX.csv")
        val videoRows = csv.lineSequence().filter { it.isNotBlank() }.count() - 1
        require(videoRows == 46) { "Video research index expected 46 rows, found $videoRows." }
        val truth = readText("$ASSET_ROOT/STRATEGY_TRUTH_STANDARD.md").lowercase()
        require("proprietary" in truth && "source" in truth && "fidelity" in truth) { "Strategy truth standard is not the expected truth-policy document." }
        val belgium = readText("$ASSET_ROOT/BELGIUM_KRAKEN_CONSTRAINTS.md").lowercase()
        require("kraken" in belgium && "belgium" in belgium) { "Belgium/Kraken constraints asset failed integrity semantics." }
        val implementation = readText("$ASSET_ROOT/IMPLEMENTATION_SPEC.md").lowercase()
        require("tradecandidate" in implementation && "risk" in implementation && "execution" in implementation) { "Implementation specification failed integrity semantics." }
    }

    fun assetAudit(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        EXPECTED_ASSETS.forEach { name ->
            val path = "$ASSET_ROOT/$name"
            val bytes = runCatching { context.assets.open(path).use { it.readBytes() } }.getOrNull()
            out[name] = if (bytes == null) "MISSING" else "OK sha256=${sha256(bytes)} bytes=${bytes.size}"
        }
        val sourceAudit = runCatching {
            val root = JSONObject(readText(SOURCE_REGISTRY))
            val rows = root.optJSONArray("sources") ?: JSONArray()
            val ids = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank) }.toSet()
            val refs = strategies().flatMap { it.sourceRefs }.toSet()
            val missingRefs = refs.filterNot { ref ->
                ref in ids || SOURCE_REF_ALIASES[ref]?.let { it in ids } == true
            }.sorted()
            Triple(rows.length(), ids.size, missingRefs)
        }.getOrElse { Triple(-1, -1, listOf("SOURCE_REGISTRY_PARSE_ERROR:${it.message}")) }
        val manifestAudit = runCatching {
            val manifest = JSONObject(readText("$ASSET_ROOT/MANIFEST.json"))
            val files = strings(manifest.optJSONArray("files")).toSet()
            val missing = EXPECTED_ASSETS.filterNot { it in files }
            val extra = files.filterNot { it in EXPECTED_ASSETS }.sorted()
            "expected=${EXPECTED_ASSETS.size}; manifest=${files.size}; missing=${missing.joinToString(",").ifBlank { "none" }}; extra=${extra.joinToString(",").ifBlank { "none" }}; strategy_count=${manifest.optInt("strategy_count",-1)}; video_index_count=${manifest.optInt("video_index_count",-1)}; truth_policy=${manifest.optString("truth_policy")}"
        }.getOrElse { "MANIFEST_PARSE_ERROR:${it.message}" }
        out["source_registry_count"] = sourceAudit.first.toString()
        out["source_registry_unique_ids"] = sourceAudit.second.toString()
        out["missing_source_refs"] = sourceAudit.third.joinToString(",").ifBlank { "none" }
        out["source_ref_aliases"] = SOURCE_REF_ALIASES.entries.joinToString(",") { "${it.key}->${it.value}" }
        out["manifest_audit"] = manifestAudit
        out["strategy_count"] = strategies().size.toString()
        out["research_freeze"] = RESEARCH_FREEZE
        out["truth_gate_pass_count"] = strategies().count { it.positiveLiveTruthSatisfied }.toString()
        out["proprietary_blocked_count"] = strategies().count { it.proprietaryUnknown || it.fidelity.equals("X",true) }.toString()
        return out
    }

    private fun readText(path: String): String = context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun parseCatalog(text: String): List<HandoffStrategyDefinition> {
        val root = JSONObject(text)
        val rows = root.getJSONArray("strategies")
        return (0 until rows.length()).map { idx -> parseStrategy(rows.getJSONObject(idx)) }
    }

    private fun parseStrategy(o: JSONObject): HandoffStrategyDefinition {
        val usage = o.optJSONObject("usage_context") ?: JSONObject()
        val faithful = o.optJSONObject("source_faithfulness_required_fields") ?: JSONObject()
        return HandoffStrategyDefinition(
            id = o.optString("id"),
            trader = o.optString("trader"),
            name = o.optString("name"),
            researchFreeze = o.optString("research_freeze", RESEARCH_FREEZE),
            fidelity = o.optString("fidelity", "X"),
            provenance = strings(o.optJSONArray("provenance")),
            purpose = o.optString("purpose"),
            timeframes = strings(o.optJSONArray("timeframes")),
            setupConditions = strings(o.optJSONArray("setup_conditions")),
            entryTrigger = strings(o.optJSONArray("entry_trigger")),
            invalidation = strings(o.optJSONArray("invalidation")),
            stopLogic = strings(o.optJSONArray("stop_logic")),
            positionSizing = strings(o.optJSONArray("position_sizing")),
            tradeManagement = strings(o.optJSONArray("trade_management")),
            targetExit = strings(o.optJSONArray("target_exit")),
            requiredData = strings(o.optJSONArray("required_data")),
            belgiumKrakenSpotPolicy = o.optString("belgium_kraken_spot_policy"),
            empiricalStatus = o.optString("empirical_status"),
            discretionaryElements = o.optString("discretionary_elements"),
            proprietaryUnknown = o.optBoolean("proprietary_unknown", false),
            sourceRefs = strings(o.optJSONArray("source_refs")),
            mustNotClaim = o.optString("must_not_claim"),
            liveTruthGate = o.optString("live_truth_gate", "BLOCK_UNVERIFIED"),
            usageContext = HandoffUsageContext(
                bestConditions = strings(usage.optJSONArray("best_conditions")),
                requiredConditions = strings(usage.optJSONArray("required_conditions")),
                acceptableConditions = strings(usage.optJSONArray("acceptable_conditions")),
                avoidConditions = strings(usage.optJSONArray("avoid_conditions")),
                invalidConditions = strings(usage.optJSONArray("invalid_conditions")),
                marketRegime = strings(usage.optJSONArray("market_regime")),
                volatilityRegime = strings(usage.optJSONArray("volatility_regime")),
                liquidityRequirements = strings(usage.optJSONArray("liquidity_requirements")),
                directionPolicy = usage.optString("direction_policy"),
                expectedHoldingPeriod = usage.optString("expected_holding_period", "UNKNOWN_UNLESS_SOURCE_VERIFIED"),
                sourceEvidence = strings(usage.optJSONArray("source_evidence"))
            ),
            usageContextSourceVerified = faithful.optBoolean("usage_context", false),
            noTradeConditionsSourceVerified = faithful.optBoolean("no_trade_conditions", false)
        )
    }

    private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
