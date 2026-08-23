#!/usr/bin/env python3
from __future__ import annotations

import datetime as dt
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

MUTATION_SCRIPTS = [
    '.cts-v4-migration/apply_diagnostics_integration_fix.py',
    '.cts-v4-migration/apply_milestone6.py',
    '.cts-v4-migration/apply_full_integration_cleanup.py',
    '.cts-v4-migration/apply_exchange_minimum_order_fix.py',
    '.cts-v4-migration/apply_exact_preview_ui.py',
    '.cts-v4-migration/apply_system_diagnostics_ui.py',
    '.cts-v4-migration/apply_gdelt_rate_limit_fix.py',
    '.cts-v4-migration/apply_cloudshare_setup_wizard.py',
    '.cts-v4-migration/apply_cloudshare_guided_assistant.py',
]

REMOVE_WORKFLOW_STEPS = [
    'Apply diagnostics source patch',
    'Apply cumulative v4 migration',
    'Apply full integration and UX cleanup',
    'Apply Kraken exchange minimum-order sizing fix',
    'Preflight exact-preview migration',
    'Apply approved preview-exact UI redesign',
    'Apply System diagnostics and export UI',
    'Apply GDELT request pacing and cache',
    'Apply guided CloudShare setup wizard',
    'Apply true step-by-step CloudShare assistant',
    'Validate diagnostics patch markers',
    'Set update build identity',
    'Add JUnit 4 test dependency when missing',
    'Validate migrated update source',
]


def fail(message: str) -> None:
    raise RuntimeError(message)


def run(cmd: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, cwd=str(cwd), text=True, capture_output=True, check=False)


def git_dirty_paths(repo: Path) -> list[str]:
    result = run(['git', 'status', '--porcelain'], repo)
    if result.returncode != 0:
        return []
    paths: list[str] = []
    for line in result.stdout.splitlines():
        if len(line) < 4:
            continue
        raw = line[3:]
        if ' -> ' in raw:
            raw = raw.split(' -> ', 1)[1]
        paths.append(raw.replace('\\', '/'))
    return paths


def create_backup(repo: Path, temp_root: Path, output_zip: Path) -> Path:
    snapshot = temp_root / 'snapshot'
    snapshot.mkdir(parents=True, exist_ok=True)
    for rel in ('app', '.cts-v4-migration'):
        src = repo / rel
        if not src.exists():
            fail(f'Required path missing: {src}')
        shutil.copytree(src, snapshot / rel)
    workflow = repo / '.github/workflows/android-v4-build.yml'
    target = snapshot / '.github/workflows/android-v4-build.yml'
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(workflow, target)
    with zipfile.ZipFile(output_zip, 'w', zipfile.ZIP_DEFLATED) as zf:
        for path in snapshot.rglob('*'):
            if path.is_file():
                zf.write(path, path.relative_to(snapshot))
    return snapshot


def restore_path(snapshot: Path, repo: Path, rel: str) -> None:
    source = snapshot / rel
    target = repo / rel
    if target.exists():
        if target.is_dir():
            shutil.rmtree(target)
        else:
            target.unlink()
    target.parent.mkdir(parents=True, exist_ok=True)
    if source.is_dir():
        shutil.copytree(source, target)
    else:
        shutil.copy2(source, target)


def restore_all(snapshot: Path, repo: Path) -> None:
    restore_path(snapshot, repo, 'app')
    restore_path(snapshot, repo, '.cts-v4-migration')
    restore_path(snapshot, repo, '.github/workflows/android-v4-build.yml')


def parse_identity(workflow_text: str) -> tuple[str, int]:
    name_match = re.search(r'(?m)^\s*CTS_VERSION_NAME:\s*([0-9]+\.[0-9]+\.[0-9]+)\s*$', workflow_text)
    code_match = re.search(r'(?m)^\s*CTS_VERSION_CODE:\s*(\d+)\s*$', workflow_text)
    if not name_match or not code_match:
        fail('Could not read CTS_VERSION_NAME / CTS_VERSION_CODE from canonical workflow.')
    return name_match.group(1), int(code_match.group(1))


def run_materialization(repo: Path, log_path: Path) -> None:
    with log_path.open('w', encoding='utf-8') as log:
        for rel in MUTATION_SCRIPTS:
            script = repo / rel
            if not script.exists():
                fail(f'Required current-CI transformation script is missing: {rel}')
            compile_result = run([sys.executable, '-m', 'py_compile', str(script)], repo)
            log.write(f'\n=== PY_COMPILE {rel} ===\n{compile_result.stdout}{compile_result.stderr}')
            if compile_result.returncode != 0:
                fail(f'Python syntax validation failed for {rel}. See {log_path}')
            result = run([sys.executable, str(script), str(repo)], repo)
            log.write(f'\n=== APPLY {rel} ===\n{result.stdout}{result.stderr}')
            log.flush()
            if result.returncode != 0:
                fail(f'Materialization failed in {rel}. See {log_path}')


def patch_source_identity(repo: Path, version_name: str, version_code: int) -> None:
    gradle = repo / 'app/build.gradle.kts'
    text = gradle.read_text(encoding='utf-8')
    text, c1 = re.subn(r'versionCode\s*=\s*\d+', f'versionCode = {version_code}', text, count=1)
    text, c2 = re.subn(r'versionName\s*=\s*"[^"]+"', f'versionName = "{version_name}"', text, count=1)
    if c1 != 1 or c2 != 1:
        fail(f'Could not freeze Gradle identity. code={c1}, name={c2}')
    if 'testImplementation("junit:junit:4.13.2")' not in text:
        marker = 'dependencies {\n'
        if marker not in text:
            fail('dependencies block missing in app/build.gradle.kts')
        text = text.replace(marker, marker + '    testImplementation("junit:junit:4.13.2")\n', 1)
    gradle.write_text(text, encoding='utf-8')

    release_info = repo / 'app/src/main/java/com/ksp/cryptobot/release/V4ReleaseInfo.kt'
    if not release_info.exists():
        fail('V4ReleaseInfo.kt was not materialized.')
    text = release_info.read_text(encoding='utf-8')
    text, c1 = re.subn(r'const val VERSION_NAME\s*=\s*"[^"]+"', f'const val VERSION_NAME = "{version_name}"', text, count=1)
    text, c2 = re.subn(r'const val VERSION_CODE\s*=\s*\d+', f'const val VERSION_CODE = {version_code}', text, count=1)
    if c1 != 1 or c2 != 1:
        fail('Could not freeze V4ReleaseInfo identity.')
    release_info.write_text(text, encoding='utf-8')

    main = repo / 'app/src/main/java/com/ksp/cryptobot/MainActivity.kt'
    if main.exists():
        text = main.read_text(encoding='utf-8')
        text = re.sub(r'v4\.0\.\d+ CTS', f'v{version_name} CTS', text)
        main.write_text(text, encoding='utf-8')


def remove_named_step(text: str, name: str) -> str:
    marker = f'      - name: {name}\n'
    start = text.find(marker)
    if start < 0:
        return text
    candidates = [
        text.find('\n      - name: ', start + len(marker)),
        text.find('\n      - uses: ', start + len(marker)),
    ]
    candidates = [x for x in candidates if x >= 0]
    if not candidates:
        return text[:start].rstrip() + '\n'
    end = min(candidates)
    return text[:start] + text[end + 1:]


def canonical_validation_step() -> str:
    return r'''      - name: Validate canonical v4 source freeze
        shell: bash
        run: |
          set -euo pipefail
          python3 - <<'PYCANON'
          from pathlib import Path
          import os
          import re

          expected_name = os.environ["CTS_VERSION_NAME"]
          expected_code = int(os.environ["CTS_VERSION_CODE"])
          root = Path("app/src/main/java/com/ksp/cryptobot")
          gradle = Path("app/build.gradle.kts").read_text(encoding="utf-8")
          db = (root / "data/AppDatabase.kt").read_text(encoding="utf-8")
          release = (root / "release/V4ReleaseInfo.kt").read_text(encoding="utf-8")

          def contains(path, marker):
              p = root / path
              return p.exists() and marker in p.read_text(encoding="utf-8")

          checks = {
              "applicationId": 'applicationId = "com.ksp.cryptobot"' in gradle,
              "canonical versionName": f'versionName = "{expected_name}"' in gradle,
              "canonical versionCode": f"versionCode = {expected_code}" in gradle,
              "release identity": f'VERSION_NAME = "{expected_name}"' in release and f"VERSION_CODE = {expected_code}" in release,
              "JUnit dependency committed": 'testImplementation("junit:junit:4.13.2")' in gradle,
              "Room schema 11": "version = 11" in db,
              "non-destructive Room": re.search(r"(?m)^\s*\.fallbackToDestructiveMigration\s*\(", db) is None,
              "research handoff": (root / "research/ResearchHandoffEngine.kt").exists(),
              "protective stops": (root / "execution/ProtectiveStopManager.kt").exists(),
              "exchange minimum policy": (root / "execution/ExchangeMinimumOrderPolicy.kt").exists(),
              "diagnostic safe-mode marker": contains("governance/RiskBudgetAndSafeMode.kt", "causativeErrorTypes"),
              "production governor marker": contains("governance/ProductionIntelligenceEngine.kt", "entryOnlyGovernorsBlock"),
              "execution guard marker": contains("execution/ExecutionGuard.kt", "productionSafeModeBlocks"),
              "news health registry": (root / "news/NewsProviderHealth.kt").exists(),
              "completed learning outcomes": contains("learning/TrueSelfLearningEngine.kt", "completedOutcomeTradesForLearning"),
              "end-to-end verifier": contains("release/V4SystemVerifier.kt", "End-to-end wiring evidence"),
          }
          for name, ok in checks.items():
              print(("PASS" if ok else "FAIL") + " | " + name)
          failed = [name for name, ok in checks.items() if not ok]
          if failed:
              raise SystemExit("Canonical source freeze validation failed: " + ", ".join(failed))
          workflow = Path(".github/workflows/android-v4-build.yml").read_text(encoding="utf-8")
          if "python3 .cts-v4-migration/apply_" in workflow:
              raise SystemExit("Canonical workflow still mutates source through migration scripts.")
          print("PASS | canonical workflow contains no migration apply calls")
          PYCANON

'''


def rewrite_workflow(repo: Path, version_name: str, version_code: int) -> None:
    workflow = repo / '.github/workflows/android-v4-build.yml'
    text = workflow.read_text(encoding='utf-8')
    for name in REMOVE_WORKFLOW_STEPS:
        text = remove_named_step(text, name)
    text = text.replace('name: Crypto TradeStation v4 Canonical Update APK', 'name: Crypto TradeStation Canonical Android APK', 1)
    text = text.replace('name: Build canonical CTS v4 update APK', 'name: Build committed canonical Android source', 1)
    marker = '      - name: Prepare release signing\n'
    if marker not in text:
        fail('Prepare release signing step not found while rewriting workflow.')
    text = text.replace(marker, canonical_validation_step() + marker, 1)

    text = text.replace(
        '''          echo "$PACKAGE_LINE" | grep -q "versionCode='111'" || { echo "Wrong versionCode in built APK" >&2; exit 1; }\n          echo "$PACKAGE_LINE" | grep -q "versionName='4.0.6'" || { echo "Wrong versionName in built APK" >&2; exit 1; }\n''',
        '''          echo "$PACKAGE_LINE" | grep -q "versionCode='$CTS_VERSION_CODE'" || { echo "Wrong versionCode in built APK" >&2; exit 1; }\n          echo "$PACKAGE_LINE" | grep -q "versionName='$CTS_VERSION_NAME'" || { echo "Wrong versionName in built APK" >&2; exit 1; }\n'''
    )
    text = text.replace(
        '          name: CryptoTradeStation-v4.0.6-${{ env.CTS_BUILD_TYPE }}-update-apk\n',
        '          name: CryptoTradeStation-v${{ env.CTS_VERSION_NAME }}-${{ env.CTS_BUILD_TYPE }}-update-apk\n'
    )
    old_collect = '          cp migration.log diagnostics-fix.log integration-cleanup.log exchange-minimum-order-fix.log exact-preview-ui.log system-diagnostics-ui.log preview-visual-contracts.log integration-contracts.log compile-debug.log unit-tests.log apk-build.log ci-failure/ 2>/dev/null || true\n'
    new_collect = '          cp preview-visual-contracts.log integration-contracts.log compile-debug.log unit-tests.log apk-build.log ci-failure/ 2>/dev/null || true\n'
    text = text.replace(old_collect, new_collect)

    if 'python3 .cts-v4-migration/apply_' in text:
        matches = sorted(set(re.findall(r'python3\s+(\.cts-v4-migration/apply_[^\s"]+)', text)))
        fail('Workflow still contains source mutation calls: ' + ', '.join(matches))
    declared_name, declared_code = parse_identity(text)
    if declared_name != version_name or declared_code != version_code:
        fail('Workflow identity changed unexpectedly during canonicalization.')
    workflow.write_text(text, encoding='utf-8')


def cleanup_generated_backups(repo: Path, root_entries_before: set[str]) -> None:
    for child in repo.iterdir():
        if child.name in root_entries_before:
            continue
        if child.is_dir() and child.name.startswith('.') and 'backup' in child.name.lower():
            shutil.rmtree(child, ignore_errors=True)


def verify(repo: Path, version_name: str, version_code: int) -> list[str]:
    errors: list[str] = []
    gradle = (repo / 'app/build.gradle.kts').read_text(encoding='utf-8')
    workflow = (repo / '.github/workflows/android-v4-build.yml').read_text(encoding='utf-8')
    root = repo / 'app/src/main/java/com/ksp/cryptobot'

    def require(ok: bool, label: str) -> None:
        print(('PASS' if ok else 'FAIL') + ' | ' + label)
        if not ok:
            errors.append(label)

    require(f'versionName = "{version_name}"' in gradle, 'Gradle v4.0.7 identity')
    require(f'versionCode = {version_code}' in gradle, 'Gradle versionCode 112')
    require('testImplementation("junit:junit:4.13.2")' in gradle, 'JUnit dependency committed')
    require((root / 'release/V4ReleaseInfo.kt').exists(), 'V4ReleaseInfo materialized')
    require((root / 'execution/ExchangeMinimumOrderPolicy.kt').exists(), 'exchange minimum policy materialized')
    require((root / 'PreviewReplicaUi.kt').exists(), 'approved preview UI materialized')
    require((root / 'ui/CloudShareScreen.kt').exists(), 'CloudShare UI materialized')
    require((root / 'research/ResearchHandoffEngine.kt').exists(), 'research handoff materialized')
    require((root / 'execution/ProtectiveStopManager.kt').exists(), 'protective stop manager materialized')
    require('python3 .cts-v4-migration/apply_' not in workflow, 'workflow no longer mutates source')
    require('Set update build identity' not in workflow, 'workflow no longer rewrites version identity')
    require('Validate canonical v4 source freeze' in workflow, 'canonical source validation installed')
    require("versionCode='$CTS_VERSION_CODE'" in workflow, 'APK versionCode check uses env')
    require("versionName='$CTS_VERSION_NAME'" in workflow, 'APK versionName check uses env')
    require('CryptoTradeStation-v${{ env.CTS_VERSION_NAME }}' in workflow, 'artifact name uses canonical version')
    return errors


def main() -> None:
    if len(sys.argv) != 2:
        print('Usage: python canonicalize_v407.py <path-to-Trading-Station>', file=sys.stderr)
        raise SystemExit(2)
    repo = Path(sys.argv[1]).resolve()
    workflow_path = repo / '.github/workflows/android-v4-build.yml'
    if not workflow_path.exists() or not (repo / 'app').exists() or not (repo / '.cts-v4-migration').exists():
        print('Target does not look like Demon-blood/Trading-Station.', file=sys.stderr)
        raise SystemExit(2)

    version_name, version_code = parse_identity(workflow_path.read_text(encoding='utf-8'))
    if (version_name, version_code) != ('4.0.7', 112):
        print(f'Refusing unexpected identity {version_name}/{version_code}; expected 4.0.7/112.', file=sys.stderr)
        raise SystemExit(2)

    dirty = git_dirty_paths(repo)
    dangerous = [p for p in dirty if p.startswith('app/') or p.startswith('.cts-v4-migration/')]
    if dangerous:
        print('Refusing to overwrite uncommitted app/migration changes:', file=sys.stderr)
        for p in dangerous:
            print('  ' + p, file=sys.stderr)
        raise SystemExit(3)

    timestamp = dt.datetime.now().strftime('%Y%m%d_%H%M%S')
    backup_zip = repo.parent / f'{repo.name}_before_M2_canonicalize_{timestamp}.zip'
    log_path = repo.parent / f'{repo.name}_M2_materialization_{timestamp}.log'
    root_entries_before = {p.name for p in repo.iterdir()}
    temp_root = Path(tempfile.mkdtemp(prefix='cts_m2_'))
    snapshot: Path | None = None
    try:
        snapshot = create_backup(repo, temp_root, backup_zip)
        print(f'Backup created: {backup_zip}')
        print('Materializing current canonical CI source...')
        run_materialization(repo, log_path)
        patch_source_identity(repo, version_name, version_code)
        restore_path(snapshot, repo, '.cts-v4-migration')
        rewrite_workflow(repo, version_name, version_code)
        cleanup_generated_backups(repo, root_entries_before)
        errors = verify(repo, version_name, version_code)
        if errors:
            fail('Verification failed: ' + ', '.join(errors))
        print('\nPASS | Milestone 2 canonical source materialization complete.')
        print(f'Backup: {backup_zip}')
        print(f'Log:    {log_path}')
        print('Trading behavior intentionally unchanged.')
        print('\nReview with:\n  git status --short\n  git diff --stat')
        print('\nThen commit/push and let GitHub Actions compile/test the committed source.')
    except Exception as exc:
        print(f'ERROR | {exc}', file=sys.stderr)
        if snapshot is not None:
            print('Restoring app/, migration payload and workflow...', file=sys.stderr)
            restore_all(snapshot, repo)
            cleanup_generated_backups(repo, root_entries_before)
            print('Restore complete.', file=sys.stderr)
        print(f'Backup ZIP retained at: {backup_zip}', file=sys.stderr)
        print(f'Materialization log: {log_path}', file=sys.stderr)
        raise SystemExit(1)
    finally:
        shutil.rmtree(temp_root, ignore_errors=True)


if __name__ == '__main__':
    main()
