#!/usr/bin/env python3
from __future__ import annotations

import shutil
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()


def read(path: str) -> str:
    p = ROOT / path
    if not p.exists():
        raise SystemExit(f'M24.1 apply failed: missing {path}')
    return p.read_text(encoding='utf-8')


def write(path: str, text: str) -> None:
    p = ROOT / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding='utf-8', newline='\n')


def replace_once(path: str, old: str, new: str, label: str) -> None:
    text = read(path)
    if new in text:
        print(f'SKIP | {label} already applied')
        return
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'M24.1 apply failed: {label} anchor count={count} in {path}')
    write(path, text.replace(old, new, 1))
    print(f'PATCH | {label}')


# 1) Preserve observational sample counts while keeping outcome semantics strict.
INDEXER = 'app/src/main/java/com/ksp/cryptobot/cloudshare/CollectiveIntelligenceIndexer.kt'
replace_once(
    INDEXER,
    '''        if (source == "shared_learning_daily") {
            val positive = payload.int("positive_pnl_count").coerceAtLeast(0)
            val negative = payload.int("negative_pnl_count").coerceAtLeast(0)
            val zero = payload.int("zero_pnl_count").coerceAtLeast(0)
            val outcomeLike = eventType.contains("outcome") || eventType == "trade" || eventType.endsWith("_trade")
            if (outcomeLike) {
                samples = positive + negative + zero
                if (samples <= 0) samples = payload.int("sample_count").coerceAtLeast(0)
                if (samples > 0) {
                    isOutcome = true
                    wins = positive
                    losses = negative
                    edgeSum = payload.double("pnl_sum")
                }
            }
        } else if (source == "shared_trade_daily") {
            val side = payload.string("side").uppercase()
            samples = payload.int("sample_count").coerceAtLeast(0)
            if (side in setOf("SELL", "EXIT", "CLOSE") && samples > 0) {
                isOutcome = true
                wins = payload.int("wins").coerceAtLeast(0)
                losses = payload.int("losses").coerceAtLeast(0)
                edgeSum = when {
                    payload.containsKey("edge_sum") -> payload.double("edge_sum")
                    else -> payload.double("avg_net_return_pct") * samples.toDouble()
                }
            }
        }
''',
    '''        if (source == "shared_learning_daily") {
            // Decisions/signals are observational evidence even when they do not yet have
            // realized PnL. Preserve sample_count so data readiness can progress without
            // pretending these rows are resolved trade outcomes.
            samples = payload.int("sample_count").coerceAtLeast(0)
            val positive = payload.int("positive_pnl_count").coerceAtLeast(0)
            val negative = payload.int("negative_pnl_count").coerceAtLeast(0)
            val zero = payload.int("zero_pnl_count").coerceAtLeast(0)
            val outcomeLike = eventType.contains("outcome") || eventType == "trade" || eventType.endsWith("_trade")
            if (outcomeLike) {
                val resolvedSamples = positive + negative + zero
                if (resolvedSamples > 0) samples = resolvedSamples
                if (samples > 0) {
                    isOutcome = true
                    wins = positive
                    losses = negative
                    edgeSum = payload.double("pnl_sum")
                }
            }
        } else if (source == "shared_signal_daily") {
            // Signal aggregates prove that fresh collective data is flowing. They are not
            // outcomes and therefore must never influence win rate / edge adjustments.
            samples = payload.int("sample_count").coerceAtLeast(0)
        } else if (source == "shared_trade_daily") {
            val side = payload.string("side").uppercase()
            samples = payload.int("sample_count").coerceAtLeast(0)
            if (side in setOf("SELL", "EXIT", "CLOSE") && samples > 0) {
                isOutcome = true
                wins = payload.int("wins").coerceAtLeast(0)
                losses = payload.int("losses").coerceAtLeast(0)
                edgeSum = when {
                    payload.containsKey("edge_sum") -> payload.double("edge_sum")
                    else -> payload.double("avg_net_return_pct") * samples.toDouble()
                }
            }
        }
''',
    'index observational CloudShare evidence without fabricating outcomes'
)

# 2) Split data readiness from outcome-learning readiness while preserving scoring safety.
MODELS = 'app/src/main/java/com/ksp/cryptobot/cloudshare/CollectiveLearningModels.kt'
replace_once(
    MODELS,
    'import java.util.Locale\n',
    'import java.util.Locale\nimport java.time.Instant\n',
    'add timestamp freshness support'
)
replace_once(
    MODELS,
    '''    val edgeSum: Double,
    val eventTimestamp: String
)
''',
    '''    val edgeSum: Double,
    val eventTimestamp: String,
    // Default true preserves historical constructor/source compatibility. New index rows
    // explicitly carry the database isOutcome flag.
    val isOutcome: Boolean = true
)
''',
    'carry outcome identity into the in-memory collective cache'
)
replace_once(
    MODELS,
    'reason = "CloudShare evidence neutral: $samples/$requiredSamples matching outcome samples; local strategy remains active.",',
    'reason = "CloudShare outcome learning collecting: $samples/$requiredSamples matching resolved outcomes; data/sync readiness is tracked separately and the local strategy remains active.",',
    'replace misleading warmup-style neutral reason'
)
replace_once(
    MODELS,
    '''data class CollectiveCacheSnapshot(
    val enabled: Boolean,
    val rowCount: Int,
    val totalSamples: Int,
    val contributors: Int,
    val newestEventTimestamp: String,
    val minSamples: Int,
    val maxAdjustment: Int,
    val weight: Double
)
''',
    '''data class CollectiveCacheSnapshot(
    val enabled: Boolean,
    // Backward-compatible outcome-only fields.
    val rowCount: Int,
    val totalSamples: Int,
    val contributors: Int,
    val newestEventTimestamp: String,
    val minSamples: Int,
    val maxAdjustment: Int,
    val weight: Double,
    // M24.1 truthful readiness fields. Observations prove data flow; only outcomes can
    // affect collective edge / win-rate scoring.
    val indexedRows: Int = 0,
    val indexedSamples: Int = 0,
    val indexedContributors: Int = 0,
    val observationRows: Int = 0,
    val observationSamples: Int = 0,
    val outcomeRows: Int = 0,
    val outcomeSamples: Int = 0,
    val dataReady: Boolean = false,
    val dataState: String = "DISABLED",
    val dataRequiredSamples: Int = 0,
    val outcomeState: String = "COLLECTING_OUTCOMES",
    val newestDataTimestamp: String = "",
    val newestOutcomeTimestamp: String = ""
)
''',
    'extend collective diagnostics without changing old outcome fields'
)
replace_once(
    MODELS,
    '''        val required = minSamples.coerceAtLeast(1)

        val tiers = listOf(
''',
    '''        val required = minSamples.coerceAtLeast(1)
        // Safety invariant: observational signals/decisions can establish DATA_READY but
        // can never contribute to profit/edge/win-rate adjustments.
        val outcomeRows = rows.filter { it.isOutcome }

        val tiers = listOf(
''',
    'enforce outcome-only collective scoring'
)
replace_once(
    MODELS,
    '            val matches = rows.filter(tier.matches)\n',
    '            val matches = outcomeRows.filter(tier.matches)\n',
    'score only resolved outcome rows'
)
replace_once(
    MODELS,
    '''    @Volatile private var maxAdjustment: Int = 6
    @Volatile private var weight: Double = 1.0
''',
    '''    @Volatile private var maxAdjustment: Int = 6
    @Volatile private var weight: Double = 1.0

    private const val DATA_READINESS_MAX_REQUIRED_SAMPLES = 10
    private const val DATA_FRESHNESS_MS = 24L * 60L * 60L * 1000L
    private const val CLOCK_SKEW_TOLERANCE_MS = 5L * 60L * 1000L
    private val READINESS_SOURCES = setOf("shared_signal_daily", "shared_learning_daily", "shared_trade_daily")
''',
    'define bounded data-readiness requirements and freshness'
)
replace_once(
    MODELS,
    '''    fun snapshot(): CollectiveCacheSnapshot {
        val local = rows
        return CollectiveCacheSnapshot(
            enabled = enabled,
            rowCount = local.size,
            totalSamples = local.sumOf { it.sampleCount.coerceAtLeast(0) },
            contributors = local.map { it.contributorId }.filter { it.isNotBlank() }.distinct().size,
            newestEventTimestamp = local.maxOfOrNull { it.eventTimestamp }.orEmpty(),
            minSamples = minSamples,
            maxAdjustment = maxAdjustment,
            weight = weight
        )
    }
}
''',
    '''    fun snapshot(): CollectiveCacheSnapshot {
        val local = rows
        val indexed = local.filter { row ->
            row.sampleCount > 0 && row.sourceTable in READINESS_SOURCES
        }
        val outcomes = indexed.filter { it.isOutcome }
        val observations = indexed.filterNot { it.isOutcome }
        val indexedSamples = indexed.sumOf { it.sampleCount.coerceAtLeast(0) }
        val observationSamples = observations.sumOf { it.sampleCount.coerceAtLeast(0) }
        val outcomeSamples = outcomes.sumOf { it.sampleCount.coerceAtLeast(0) }
        val newestDataTimestamp = indexed.maxOfOrNull { it.eventTimestamp }.orEmpty()
        val newestOutcomeTimestamp = outcomes.maxOfOrNull { it.eventTimestamp }.orEmpty()
        val newestDataEpochMs = runCatching { Instant.parse(newestDataTimestamp).toEpochMilli() }.getOrDefault(0L)
        val ageMs = if (newestDataEpochMs > 0L) System.currentTimeMillis() - newestDataEpochMs else Long.MAX_VALUE
        val freshData = newestDataEpochMs > 0L && ageMs in -CLOCK_SKEW_TOLERANCE_MS..DATA_FRESHNESS_MS
        val dataRequiredSamples = minOf(minSamples, DATA_READINESS_MAX_REQUIRED_SAMPLES).coerceAtLeast(1)
        val dataReady = enabled && indexedSamples >= dataRequiredSamples && freshData
        val dataState = when {
            !enabled -> "DISABLED"
            indexedSamples <= 0 -> "NO_DATA"
            !freshData -> "STALE_DATA"
            indexedSamples < dataRequiredSamples -> "COLLECTING_DATA"
            else -> "READY"
        }
        val outcomeState = when {
            !enabled -> "DISABLED"
            outcomeSamples >= minSamples -> "OUTCOME_THRESHOLD_REACHED"
            else -> "COLLECTING_OUTCOMES"
        }
        return CollectiveCacheSnapshot(
            enabled = enabled,
            // Preserve old snapshot semantics for any existing callers: these remain
            // resolved-outcome-only values.
            rowCount = outcomes.size,
            totalSamples = outcomeSamples,
            contributors = outcomes.map { it.contributorId }.filter { it.isNotBlank() }.distinct().size,
            newestEventTimestamp = newestOutcomeTimestamp,
            minSamples = minSamples,
            maxAdjustment = maxAdjustment,
            weight = weight,
            indexedRows = indexed.size,
            indexedSamples = indexedSamples,
            indexedContributors = indexed.map { it.contributorId }.filter { it.isNotBlank() }.distinct().size,
            observationRows = observations.size,
            observationSamples = observationSamples,
            outcomeRows = outcomes.size,
            outcomeSamples = outcomeSamples,
            dataReady = dataReady,
            dataState = dataState,
            dataRequiredSamples = dataRequiredSamples,
            outcomeState = outcomeState,
            newestDataTimestamp = newestDataTimestamp,
            newestOutcomeTimestamp = newestOutcomeTimestamp
        )
    }
}
''',
    'report truthful data readiness separately from outcome-learning readiness'
)

# 3) Rebuild existing downloaded intelligence with the corrected M24.1 indexer and load all evidence.
SYNC = 'app/src/main/java/com/ksp/cryptobot/cloudshare/CloudShareSyncEngine.kt'
replace_once(
    SYNC,
    '        val indexed = dao.collectiveOutcomeRows(25_000)\n',
    '        val indexed = dao.collectiveIndexForBootstrap(25_000)\n',
    'load observations and outcomes into the in-memory readiness cache'
)
replace_once(
    SYNC,
    '''                edgeSum = row.edgeSum,
                eventTimestamp = row.eventTimestamp
            )
''',
    '''                edgeSum = row.edgeSum,
                eventTimestamp = row.eventTimestamp,
                isOutcome = row.isOutcome
            )
''',
    'propagate database outcome identity into cache rows'
)
replace_once(
    SYNC,
    '        return rows.size\n    }\n\n    suspend fun resetBackfill()',
    '        return rows.count { it.isOutcome }\n    }\n\n    suspend fun resetBackfill()',
    'preserve sync result outcome-row semantics'
)
replace_once(
    SYNC,
    '        if (dao.stateValue(KEY_REINDEX_V8) == "1") return\n',
    '        if (dao.stateValue(KEY_REINDEX_M24_1) == "1") return\n',
    'force one-time corrected reindex on upgraded installations'
)
replace_once(
    SYNC,
    '        dao.putState(CloudShareStateEntity(KEY_REINDEX_V8, "1"))\n    }\n',
    '''        // Keep the historical marker and add a new marker so previously completed V8
        // installs still rerun exactly once with the corrected observational indexer.
        dao.putState(CloudShareStateEntity(KEY_REINDEX_V8, "1"))
        dao.putState(CloudShareStateEntity(KEY_REINDEX_M24_1, "1"))
    }
''',
    'record completion of the corrected M24.1 reindex'
)
replace_once(
    SYNC,
    '        private const val KEY_REINDEX_V8 = "collective_index_rebuilt_v8"\n',
    '        private const val KEY_REINDEX_V8 = "collective_index_rebuilt_v8"\n        private const val KEY_REINDEX_M24_1 = "collective_index_rebuilt_m24_1"\n',
    'add new reindex generation key'
)

# 4) Expose the split readiness state in diagnostics.
DIAG = 'app/src/main/java/com/ksp/cryptobot/cloudshare/CloudShareDiagnostics.kt'
replace_once(
    DIAG,
    '''    val contributors: Int,
    val newestCollectiveTimestamp: String,
    val backfill: Map<String, String>,
    val recentAudit: List<String>
)
''',
    '''    val contributors: Int,
    val newestCollectiveTimestamp: String,
    val backfill: Map<String, String>,
    val recentAudit: List<String>,
    val indexedEvidenceRows: Int = 0,
    val indexedEvidenceSamples: Int = 0,
    val indexedEvidenceContributors: Int = 0,
    val observationRows: Int = 0,
    val observationSamples: Int = 0,
    val outcomeSamples: Int = 0,
    val dataReady: Boolean = false,
    val dataState: String = "DISABLED",
    val dataRequiredSamples: Int = 0,
    val outcomeState: String = "COLLECTING_OUTCOMES",
    val newestDataTimestamp: String = "",
    val newestOutcomeTimestamp: String = ""
)
''',
    'extend CloudShare diagnostics with split readiness counters'
)
replace_once(
    DIAG,
    '''            contributors = cache.contributors,
            newestCollectiveTimestamp = cache.newestEventTimestamp,
            backfill = backfill.status(),
            recentAudit = dao.recentAudit(20).map { "${it.status} ${it.operation}: ${it.detail}" }
        )
''',
    '''            contributors = cache.contributors,
            newestCollectiveTimestamp = cache.newestEventTimestamp,
            backfill = backfill.status(),
            recentAudit = dao.recentAudit(20).map { "${it.status} ${it.operation}: ${it.detail}" },
            indexedEvidenceRows = cache.indexedRows,
            indexedEvidenceSamples = cache.indexedSamples,
            indexedEvidenceContributors = cache.indexedContributors,
            observationRows = cache.observationRows,
            observationSamples = cache.observationSamples,
            outcomeSamples = cache.outcomeSamples,
            dataReady = cache.dataReady,
            dataState = cache.dataState,
            dataRequiredSamples = cache.dataRequiredSamples,
            outcomeState = cache.outcomeState,
            newestDataTimestamp = cache.newestDataTimestamp,
            newestOutcomeTimestamp = cache.newestOutcomeTimestamp
        )
''',
    'populate truthful readiness diagnostics'
)

# 5) Make decision explanations distinguish data readiness from outcome-learning readiness.
AI = 'app/src/main/java/com/ksp/cryptobot/intelligence/AiDecisionEngine.kt'
replace_once(
    AI,
    '''        val collective = CloudShareCollectiveCache.score(
            symbol = recommendation.symbol,
            strategy = settings.strategyMode.name,
            regime = "",
            timeframe = ""
        )
        val finalScore = (technicalScore + newsScore + memoryScore + collective.adjustment).coerceIn(0, 100)
''',
    '''        val collective = CloudShareCollectiveCache.score(
            symbol = recommendation.symbol,
            strategy = settings.strategyMode.name,
            regime = "",
            timeframe = ""
        )
        val collectiveSnapshot = CloudShareCollectiveCache.snapshot()
        val finalScore = (technicalScore + newsScore + memoryScore + collective.adjustment).coerceIn(0, 100)
''',
    'capture CloudShare split-readiness snapshot once per decision'
)
replace_once(
    AI,
    '''            if (CloudShareCollectiveCache.snapshot().enabled) append(collective.reason).append(' ')
            append("Base reason: ${recommendation.reason}")
''',
    '''            if (collectiveSnapshot.enabled) {
                append(collective.reason).append(' ')
                append("CloudShare data=${collectiveSnapshot.dataState}, indexed=${collectiveSnapshot.indexedSamples}, observations=${collectiveSnapshot.observationSamples}, resolvedOutcomes=${collectiveSnapshot.outcomeSamples}. ")
            }
            append("Base reason: ${recommendation.reason}")
''',
    'explain data state and resolved outcomes separately'
)

# 6) Add a human-readable readiness card to CloudShare repair/test UI.
UI = 'app/src/main/java/com/ksp/cryptobot/ui/CloudShareScreen.kt'
replace_once(
    UI,
    '    var adminOutput by remember { mutableStateOf("") }\n',
    '    var adminOutput by remember { mutableStateOf("") }\n    var diagnosticsSnapshot by remember { mutableStateOf<CloudShareDiagnosticsSnapshot?>(null) }\n',
    'store CloudShare readiness diagnostics in UI state'
)
replace_once(
    UI,
    '''            CloudShareFlow.REPAIR -> {
                Text("Repair & Test CloudShare", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                SetupStateCard(store)
                Button(
''',
    '''            CloudShareFlow.REPAIR -> {
                Text("Repair & Test CloudShare", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                SetupStateCard(store)
                OutlinedButton(
                    onClick = {
                        runAction {
                            diagnosticsSnapshot = engine.diagnostics()
                            val snapshot = diagnosticsSnapshot!!
                            status = "Data=${snapshot.dataState}; indexed=${snapshot.indexedEvidenceSamples}; resolved outcomes=${snapshot.outcomeSamples}/${store.collectiveMinSamples}."
                        }
                    },
                    enabled = !busy && store.enabled,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Inspect Learning Readiness") }
                diagnosticsSnapshot?.let { CloudShareReadinessCard(it, store.collectiveMinSamples) }
                Button(
''',
    'add explicit learning-readiness inspection to repair screen'
)
replace_once(
    UI,
    '''                            val result = engine.syncIfDue(force = true)
                            status = "Sync: uploaded=${result.uploaded}, downloaded=${result.downloaded}, backfill=${result.backfilled}" +
                                if (result.error.isBlank()) "" else ", error=${result.error}"
''',
    '''                            val result = engine.syncIfDue(force = true)
                            if (result.error.isBlank()) diagnosticsSnapshot = engine.diagnostics()
                            status = "Sync: uploaded=${result.uploaded}, downloaded=${result.downloaded}, backfill=${result.backfilled}" +
                                if (result.error.isBlank()) "" else ", error=${result.error}"
''',
    'refresh readiness counters after forced sync'
)
replace_once(
    UI,
    '''@Composable
private fun WizardProgress(title: String, step: Int, total: Int) {
''',
    '''@Composable
private fun CloudShareReadinessCard(snapshot: CloudShareDiagnosticsSnapshot, requiredOutcomes: Int) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("Learning Readiness", fontWeight = FontWeight.Bold)
            Text("Data readiness: ${snapshot.dataState}", style = MaterialTheme.typography.bodySmall)
            Text("Indexed evidence: ${snapshot.indexedEvidenceSamples} samples / ${snapshot.indexedEvidenceRows} rows", style = MaterialTheme.typography.bodySmall)
            Text("Observational samples: ${snapshot.observationSamples}", style = MaterialTheme.typography.bodySmall)
            Text("Resolved outcome samples: ${snapshot.outcomeSamples}/$requiredOutcomes", style = MaterialTheme.typography.bodySmall)
            Text("Outcome learning: ${snapshot.outcomeState}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Signals and decisions can make data READY, but they never count as profit/win-rate outcomes. Collective score adjustment stays neutral until enough matching resolved outcomes exist.",
                style = MaterialTheme.typography.labelSmall
            )
            if (snapshot.newestDataTimestamp.isNotBlank()) {
                Text("Newest data: ${snapshot.newestDataTimestamp}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun WizardProgress(title: String, step: Int, total: Int) {
''',
    'render truthful readiness counters'
)

# 7) Copy payload files.
payload_root = ROOT / 'tools' / 'm24_1_payload'
for rel in [
    'app/src/test/java/com/ksp/cryptobot/cloudshare/M241WarmupEvidenceTest.kt',
    'app/src/main/assets/cloudshare_setup/m24_1_warmup_evidence_semantics.md',
]:
    src = payload_root / rel
    if not src.exists():
        raise SystemExit(f'M24.1 apply failed: missing payload {src}')
    dst = ROOT / rel
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)
    print(f'WRITE | {rel}')

print('PASS | M24.1 warmup/evidence starvation hotfix applied.')
