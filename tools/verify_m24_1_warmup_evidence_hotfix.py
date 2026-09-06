#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
failures: list[str] = []


def text(path: str) -> str:
    p = ROOT / path
    if not p.exists():
        failures.append(f'missing {path}')
        return ''
    return p.read_text(encoding='utf-8')


def check(name: str, condition: bool, detail: str = '') -> None:
    if condition:
        print(f'PASS | {name}' + (f' | {detail}' if detail else ''))
    else:
        print(f'FAIL | {name}' + (f' | {detail}' if detail else ''))
        failures.append(name)


indexer = text('app/src/main/java/com/ksp/cryptobot/cloudshare/CollectiveIntelligenceIndexer.kt')
models = text('app/src/main/java/com/ksp/cryptobot/cloudshare/CollectiveLearningModels.kt')
sync = text('app/src/main/java/com/ksp/cryptobot/cloudshare/CloudShareSyncEngine.kt')
diag = text('app/src/main/java/com/ksp/cryptobot/cloudshare/CloudShareDiagnostics.kt')
ai = text('app/src/main/java/com/ksp/cryptobot/intelligence/AiDecisionEngine.kt')
ui = text('app/src/main/java/com/ksp/cryptobot/ui/CloudShareScreen.kt')
tests = text('app/src/test/java/com/ksp/cryptobot/cloudshare/M241WarmupEvidenceTest.kt')
doc = text('app/src/main/assets/cloudshare_setup/m24_1_warmup_evidence_semantics.md')
db = text('app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt')

check('Room schema remains version 12', bool(re.search(r'version\s*=\s*12\b', db)))

check('learning decisions retain sample_count', 'samples = payload.int("sample_count").coerceAtLeast(0)' in indexer)
check('signal aggregates retain sample_count', 'source == "shared_signal_daily"' in indexer and 'samples = payload.int("sample_count").coerceAtLeast(0)' in indexer)
check('observations do not become outcomes by default', 'var isOutcome = false' in indexer)
check('resolved trade exits remain outcome-only', 'side in setOf("SELL", "EXIT", "CLOSE")' in indexer and 'isOutcome = true' in indexer)

check('cache row carries outcome identity', 'val isOutcome: Boolean = true' in models)
check('collective scoring filters to outcomes', 'val outcomeRows = rows.filter { it.isOutcome }' in models and 'outcomeRows.filter(tier.matches)' in models)
check('neutral reason no longer conflates data warmup with outcomes', 'CloudShare outcome learning collecting:' in models and 'data/sync readiness is tracked separately' in models)
check('data readiness is freshness bounded', 'DATA_FRESHNESS_MS = 24L * 60L * 60L * 1000L' in models and 'STALE_DATA' in models)
check('data readiness threshold is bounded', 'DATA_READINESS_MAX_REQUIRED_SAMPLES = 10' in models and 'dataRequiredSamples' in models)
check('old snapshot fields remain outcome-only', 'rowCount = outcomes.size' in models and 'totalSamples = outcomeSamples' in models)
check('split readiness fields exposed', all(token in models for token in ['indexedSamples', 'observationSamples', 'outcomeSamples', 'dataReady', 'dataState', 'outcomeState']))

check('sync cache loads all indexed evidence', 'dao.collectiveIndexForBootstrap(25_000)' in sync)
check('sync propagates isOutcome', 'isOutcome = row.isOutcome' in sync)
check('sync result still returns outcome row count', 'return rows.count { it.isOutcome }' in sync)
check('upgrade uses new M24.1 reindex marker', 'KEY_REINDEX_M24_1' in sync and 'collective_index_rebuilt_m24_1' in sync)
check('historical V8 reindex marker remains preserved', 'KEY_REINDEX_V8' in sync and 'collective_index_rebuilt_v8' in sync)
check('new marker controls rerun', 'dao.stateValue(KEY_REINDEX_M24_1)' in sync)

check('diagnostics expose indexed observations and outcomes', all(token in diag for token in ['indexedEvidenceSamples', 'observationSamples', 'outcomeSamples', 'dataState', 'outcomeState']))
check('AI explanation reports split readiness', all(token in ai for token in ['collectiveSnapshot.dataState', 'collectiveSnapshot.indexedSamples', 'collectiveSnapshot.observationSamples', 'collectiveSnapshot.outcomeSamples']))
check('AI trading score still uses only collective adjustment', 'technicalScore + newsScore + memoryScore + collective.adjustment' in ai)
check('CloudShare repair UI exposes readiness inspector', 'Inspect Learning Readiness' in ui and 'Learning Readiness' in ui)
check('CloudShare UI shows resolved outcomes separately', 'Resolved outcome samples:' in ui and 'Data readiness:' in ui)

for test_name in [
    'decisionSamplesAreObservationalEvidenceNotOutcomes',
    'signalSamplesArePreservedWithoutBecomingOutcomes',
    'resolvedSellTradesRemainRealOutcomeEvidence',
    'observationalEvidenceNeverChangesCollectiveScore',
    'freshObservationsCanMakeDataReadyWhileOutcomeLearningStillCollects',
    'oldEvidenceCannotFalselySatisfyDataReadiness',
    'resolvedOutcomeThresholdStillControlsAdjustmentReadiness',
]:
    check(f'test present: {test_name}', f'fun {test_name}()' in tests)

check('semantics document forbids fabricated outcomes', 'does not fabricate outcomes' in doc)
check('semantics document keeps 25-default outcome threshold', '25 by default' in doc)
check('semantics document explains one-time reindex repair', 'reindexed once after upgrade' in doc)

# No M24 authority/execution surfaces should be touched by this hotfix payload.
expected = {
    'app/src/main/java/com/ksp/cryptobot/cloudshare/CollectiveIntelligenceIndexer.kt',
    'app/src/main/java/com/ksp/cryptobot/cloudshare/CollectiveLearningModels.kt',
    'app/src/main/java/com/ksp/cryptobot/cloudshare/CloudShareSyncEngine.kt',
    'app/src/main/java/com/ksp/cryptobot/cloudshare/CloudShareDiagnostics.kt',
    'app/src/main/java/com/ksp/cryptobot/intelligence/AiDecisionEngine.kt',
    'app/src/main/java/com/ksp/cryptobot/ui/CloudShareScreen.kt',
    'app/src/test/java/com/ksp/cryptobot/cloudshare/M241WarmupEvidenceTest.kt',
    'app/src/main/assets/cloudshare_setup/m24_1_warmup_evidence_semantics.md',
}
for path in sorted(expected):
    check(f'expected hotfix file exists: {path}', (ROOT / path).exists())

if failures:
    print(f'\nM24.1 verification FAILED ({len(failures)} checks).')
    sys.exit(1)
print('\nPASS | M24.1 warmup/evidence starvation hotfix verified.')
