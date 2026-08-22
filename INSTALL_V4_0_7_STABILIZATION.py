#!/usr/bin/env python3
"""Install the CTS 4.0.7 stabilization patch into a Trading-Station checkout."""
from __future__ import annotations

import shutil
import sys
from pathlib import Path


def fail(msg: str) -> None:
    raise SystemExit(f"[CTS 4.0.7 installer] {msg}")


def main() -> None:
    pack = Path(__file__).resolve().parent
    repo = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()
    workflow = repo / ".github/workflows/android-v4-build.yml"
    if not workflow.exists():
        fail(f"Run from the Demon-blood/Trading-Station repository root (missing {workflow}).")

    source_patch = pack / ".cts-v4-migration/apply_v4_0_7_stabilization.py"
    target_patch = repo / ".cts-v4-migration/apply_v4_0_7_stabilization.py"
    target_patch.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source_patch, target_patch)

    text = workflow.read_text(encoding="utf-8")

    # Fix the release-identity regression: env already says 4.0.7/112 but a later
    # inline script and artifact verification forced the built APK back to 4.0.6/111.
    text = text.replace('version_name = "4.0.6"', 'version_name = "4.0.7"')
    text = text.replace('version_code = 111', 'version_code = 112')
    text = text.replace('"v4.0.0 CTS", "v4.0.6 CTS"', '"v4.0.0 CTS", "v4.0.7 CTS"')
    text = text.replace("'versionName 4.0.6': 'versionName = \"4.0.6\"' in gradle", "'versionName 4.0.7': 'versionName = \"4.0.7\"' in gradle")
    text = text.replace("'versionCode 111': 'versionCode = 111' in gradle", "'versionCode 112': 'versionCode = 112' in gradle")
    text = text.replace("'V4ReleaseInfo 4.0.6': 'VERSION_NAME = \"4.0.6\"' in release and 'VERSION_CODE = 111' in release", "'V4ReleaseInfo 4.0.7': 'VERSION_NAME = \"4.0.7\"' in release and 'VERSION_CODE = 112' in release")
    text = text.replace("versionCode='111'", "versionCode='112'")
    text = text.replace("versionName='4.0.6'", "versionName='4.0.7'")
    text = text.replace("CryptoTradeStation-v4.0.6-${{ env.CTS_BUILD_TYPE }}-update-apk", "CryptoTradeStation-v4.0.7-${{ env.CTS_BUILD_TYPE }}-update-apk")

    step_marker = "      - name: Apply v4.0.7 execution stabilization\n"
    if step_marker not in text:
        anchor = "      - name: Validate step-by-step CloudShare assistant contracts\n"
        idx = text.find(anchor)
        if idx < 0:
            fail("Could not find CloudShare validation anchor in android-v4-build.yml")
        block = '''      - name: Apply v4.0.7 execution stabilization
        shell: bash
        run: |
          set -euo pipefail
          python3 -m py_compile .cts-v4-migration/apply_v4_0_7_stabilization.py
          python3 .cts-v4-migration/apply_v4_0_7_stabilization.py "$GITHUB_WORKSPACE" | tee v4-0-7-stabilization.log

      - name: Validate v4.0.7 execution-integrity contracts
        shell: bash
        run: |
          set -euo pipefail
          python3 - <<'PY407'
          from pathlib import Path
          controller = Path('app/src/main/java/com/ksp/cryptobot/core/BotController.kt').read_text(encoding='utf-8')
          paper = Path('app/src/main/java/com/ksp/cryptobot/exchange/PaperExchangeClient.kt').read_text(encoding='utf-8')
          utility = Path('app/src/main/java/com/ksp/cryptobot/execution/PaperExecutionIntegrity.kt').read_text(encoding='utf-8')
          tests = Path('app/src/test/java/com/ksp/cryptobot/execution/PaperExecutionIntegrityTest.kt').read_text(encoding='utf-8')
          models = Path('app/src/main/java/com/ksp/cryptobot/core/Models.kt').read_text(encoding='utf-8')
          ui = Path('app/src/main/java/com/ksp/cryptobot/PreviewReplicaUi.kt').read_text(encoding='utf-8')
          checks = {
              'zero-fill is never journaled as requested quantity': 'accepted/resting LIMIT orders are not trades' in controller and '?: quantity' not in controller.split('val result = runCatching { exchange.placeOrder(request) }',1)[1].split('private fun estimateOrderBookSlippagePercent',1)[0],
              'total exposure includes open BUY orders': 'pendingBuyExposure' in controller and 'remainingPositionCapacity' in controller,
              'paper fill-time hard cap': 'maxAdditionalBuyQuantity' in paper and 'hardPositionCap' in paper,
              'process-wide paper mutex': 'globalOrderMutex' in paper and 'private val orderMutex' not in paper,
              'duplicate client order guard': 'Paper duplicate clientOrderId blocked' in paper,
              'deterministic deferred fill id': 'PaperExecutionIntegrity.deferredFillId' in paper,
              'warmup separated from headline': 'learningTelemetry' in controller,
              'integrity utility present': 'object PaperExecutionIntegrity' in utility,
              'regression tests present': 'deferredFillIdIsStableForReplayButChangesForNextPartialFill' in tests and 'fillTimeQuantityClampCannotCrossHardCap' in tests,
              'paper performance baseline is explicit': 'performanceBaselineEur' in models and 'PaperExchangeClient.STARTING_BALANCE_EUR' in controller,
              'portfolio shows all-time total return': 'All-Time P/L' in ui and 'allTimePnl' in ui and 'allTimePct' in ui,
              '24h metric is labeled realized': '24H Realized P/L' in ui,
          }
          for name, ok in checks.items():
              print(('PASS' if ok else 'FAIL') + ' | ' + name)
          failed = [name for name, ok in checks.items() if not ok]
          if failed:
              raise SystemExit('v4.0.7 execution-integrity contract failure: ' + ', '.join(failed))
          PY407

'''
        text = text[:idx] + block + text[idx:]

    extra_marker = "      - name: Validate v4.0.7 repair/health/storage contracts\n"
    if extra_marker not in text:
        anchor = "      - name: Validate step-by-step CloudShare assistant contracts\n"
        idx = text.find(anchor)
        if idx < 0:
            fail("Could not find CloudShare validation anchor for v4.0.7 repair contracts")
        extra = '''      - name: Validate v4.0.7 repair/health/storage contracts
        shell: bash
        run: |
          set -euo pipefail
          python3 - <<'PY407B'
          from pathlib import Path
          controller = Path('app/src/main/java/com/ksp/cryptobot/core/BotController.kt').read_text(encoding='utf-8')
          paper = Path('app/src/main/java/com/ksp/cryptobot/exchange/PaperExchangeClient.kt').read_text(encoding='utf-8')
          dao = Path('app/src/main/java/com/ksp/cryptobot/data/AppDao.kt').read_text(encoding='utf-8')
          prod = Path('app/src/main/java/com/ksp/cryptobot/governance/ProductionIntelligenceEngine.kt').read_text(encoding='utf-8')
          kill = Path('app/src/main/java/com/ksp/cryptobot/governance/KillSwitchEngine.kt').read_text(encoding='utf-8')
          classifier = Path('app/src/main/java/com/ksp/cryptobot/governance/OperationalErrorClassifier.kt').read_text(encoding='utf-8')
          classifier_test = Path('app/src/test/java/com/ksp/cryptobot/governance/OperationalErrorClassifierTest.kt').read_text(encoding='utf-8')
          checks = {
              'legacy paper repair is wired': 'repairLegacyDuplicateDeferredFillsIfNeeded' in paper and 'paper_repair_v407' in paper,
              'legacy paper repair can delete only PAPER rows': 'DELETE FROM trades WHERE id = :id AND paper = 1' in dao,
              'repair rebuild checkpoint is crash-resumable': 'pending_rebuild' in paper and 'Rebuilt PAPER wallet/cost basis' in paper,
              'runtime position invariant is tested': 'Position Exposure Invariant' in controller,
              'paper repair status is diagnostic': '[PAPER_REPAIR]' in controller,
              'table storage visibility is diagnostic': '[DATABASE_TABLES]' in controller and 'databaseTableStorageDiagnostics' in controller,
              'retention classes are visible': 'ROLLING_TELEMETRY_CANDIDATE' in controller and 'PERMANENT_LEDGER' in controller,
              'news quota noise classification exists': 'isProviderQuotaNoise' in classifier,
              'production uses weighted critical score': 'operationalHealth.weightedCriticalScore' in prod,
              'kill switch wording is classified': 'weighted critical error score' in kill,
              'classifier regression tests exist': 'newsQuotaErrorsDoNotTripExecutionKillScore' in classifier_test and 'realOrderFailureTripsHighThreshold' in classifier_test,
          }
          for name, ok in checks.items():
              print(('PASS' if ok else 'FAIL') + ' | ' + name)
          failed = [name for name, ok in checks.items() if not ok]
          if failed:
              raise SystemExit('v4.0.7 repair/health/storage contract failure: ' + ', '.join(failed))
          PY407B

'''
        text = text[:idx] + extra + text[idx:]

    # Make failure artifacts retain the stabilization log.
    text = text.replace(
        "cp migration.log diagnostics-fix.log integration-cleanup.log exchange-minimum-order-fix.log exact-preview-ui.log system-diagnostics-ui.log preview-visual-contracts.log integration-contracts.log compile-debug.log unit-tests.log apk-build.log ci-failure/",
        "cp migration.log diagnostics-fix.log integration-cleanup.log exchange-minimum-order-fix.log exact-preview-ui.log system-diagnostics-ui.log v4-0-7-stabilization.log preview-visual-contracts.log integration-contracts.log compile-debug.log unit-tests.log apk-build.log ci-failure/"
    )

    workflow.write_text(text, encoding="utf-8")
    print(f"Installed patch script: {target_patch}")
    print(f"Updated workflow: {workflow}")
    print("PASS | CTS v4.0.7 stabilization installer complete")


if __name__ == "__main__":
    main()
