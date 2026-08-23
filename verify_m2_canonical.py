#!/usr/bin/env python3
from __future__ import annotations
import re
import sys
from pathlib import Path

if len(sys.argv) != 2:
    raise SystemExit('Usage: python verify_m2_canonical.py <Trading-Station>')
repo = Path(sys.argv[1]).resolve()
workflow_path = repo / '.github/workflows/android-v4-build.yml'
gradle_path = repo / 'app/build.gradle.kts'
root = repo / 'app/src/main/java/com/ksp/cryptobot'
if not workflow_path.exists() or not gradle_path.exists():
    raise SystemExit('FAIL | Trading-Station files not found.')
workflow = workflow_path.read_text(encoding='utf-8')
gradle = gradle_path.read_text(encoding='utf-8')
files = [
    'PreviewReplicaUi.kt',
    'release/V4ReleaseInfo.kt',
    'execution/ExchangeMinimumOrderPolicy.kt',
    'execution/ProtectiveStopManager.kt',
    'research/ResearchHandoffEngine.kt',
    'news/NewsProviderHealth.kt',
    'ui/CloudShareScreen.kt',
]
checks = {
    'versionName 4.0.7': 'versionName = "4.0.7"' in gradle,
    'versionCode 112': 'versionCode = 112' in gradle,
    'JUnit committed': 'testImplementation("junit:junit:4.13.2")' in gradle,
    'no CI migration apply calls': 'python3 .cts-v4-migration/apply_' not in workflow,
    'no CI identity rewriting': 'Set update build identity' not in workflow,
    'canonical CI validation': 'Validate canonical v4 source freeze' in workflow,
    'dynamic APK name': 'CryptoTradeStation-v${{ env.CTS_VERSION_NAME }}' in workflow,
    'dynamic APK versionName verification': "versionName='$CTS_VERSION_NAME'" in workflow,
    'dynamic APK versionCode verification': "versionCode='$CTS_VERSION_CODE'" in workflow,
    'Room destructive fallback absent': (
        (root / 'data/AppDatabase.kt').exists() and
        re.search(r'(?m)^\s*\.fallbackToDestructiveMigration\s*\(', (root / 'data/AppDatabase.kt').read_text(encoding='utf-8')) is None
    ),
}
for rel in files:
    checks[f'materialized {rel}'] = (root / rel).exists()
failed = []
for name, ok in checks.items():
    print(('PASS' if ok else 'FAIL') + ' | ' + name)
    if not ok:
        failed.append(name)
if failed:
    raise SystemExit('FAIL | ' + ', '.join(failed))
print('\nPASS | v4.0.7 is a committed-source canonical build.')
