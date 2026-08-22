#!/usr/bin/env python3
"""Install CTS v4.0.7 stabilization + 2026-08-22 research-truth handoff into Trading-Station."""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path


def fail(msg: str) -> None:
    raise SystemExit(f"[CTS research truth installer] {msg}")


def main() -> None:
    pack = Path(__file__).resolve().parent
    repo = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd().resolve()
    workflow = repo / ".github/workflows/android-v4-build.yml"
    if not workflow.exists():
        fail(f"Not a Trading-Station checkout: missing {workflow}")

    # First install the already-reviewed v4.0.7 execution/repair/portfolio stabilization so
    # the strategy work never lands on the known 4.0.6 execution-integrity regression.
    stabilization_installer = pack / "stabilization/INSTALL_V4_0_7_STABILIZATION.py"
    result = subprocess.run([sys.executable, str(stabilization_installer), str(repo)], text=True)
    if result.returncode != 0:
        fail("v4.0.7 stabilization installer failed")

    source_patch = pack / ".cts-v4-migration/apply_research_handoff_2026_08_22.py"
    source_assets = pack / ".cts-v4-migration/research_handoff_2026_08_22"
    target_patch = repo / ".cts-v4-migration/apply_research_handoff_2026_08_22.py"
    target_assets = repo / ".cts-v4-migration/research_handoff_2026_08_22"
    target_patch.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source_patch, target_patch)
    if target_assets.exists():
        shutil.rmtree(target_assets)
    shutil.copytree(source_assets, target_assets)

    text = workflow.read_text(encoding="utf-8")
    marker = "      - name: Apply 2026-08-22 research truth handoff\n"
    if marker not in text:
        anchor = "      - name: Validate step-by-step CloudShare assistant contracts\n"
        idx = text.find(anchor)
        if idx < 0:
            fail("Canonical workflow CloudShare validation anchor not found")
        block = '''      - name: Apply 2026-08-22 research truth handoff
        shell: bash
        run: |
          set -euo pipefail
          python3 -m py_compile .cts-v4-migration/apply_research_handoff_2026_08_22.py
          python3 .cts-v4-migration/apply_research_handoff_2026_08_22.py "$GITHUB_WORKSPACE" | tee research-truth-2026-08-22.log

      - name: Validate 2026-08-22 strategy-truth contracts
        shell: bash
        run: |
          set -euo pipefail
          python3 - <<'PYTRUTH'
          from pathlib import Path
          import csv, json

          asset = Path('app/src/main/assets/research_handoff')
          catalog = json.loads((asset/'STRATEGY_CATALOG.json').read_text(encoding='utf-8'))
          manifest = json.loads((asset/'MANIFEST.json').read_text(encoding='utf-8'))
          registry = json.loads((asset/'SOURCE_REGISTRY.json').read_text(encoding='utf-8'))
          strategies = catalog['strategies']
          ids = {s['id'] for s in strategies}
          new_ids = {
              'chris_dunn_1234_crypto_breakout',
              'josh_olszewicz_crypto_ichimoku_20_60_120_30_component',
              'josh_olszewicz_alligator_fractal_public_core',
              'krown_lti_public_core_2026',
              'cowen_macro_regime_memo_2026',
              'pizzino_three_bar_confirmation_candidate_2026',
          }
          source_ids = {s['id'] for s in registry['sources']}
          aliases = {
              'tcg_equilibrium':'tcg_video_library',
              'tcg_inside_bar':'tcg_video_library',
              'tcg_correlations':'tcg_video_library',
              'loukas_current':'loukas_channel',
              'loukas_cycles_trader':'loukas_channel',
              'rastani_opening_gap':'rastani_site',
              'rastani_elliott':'rastani_channel',
          }
          refs = {r for s in strategies for r in s.get('source_refs', [])}
          unresolved = sorted(r for r in refs if r not in source_ids and aliases.get(r) not in source_ids)
          video_rows = sum(1 for _ in csv.DictReader((asset/'VIDEO_RESEARCH_INDEX.csv').open(encoding='utf-8')))

          engine = Path('app/src/main/java/com/ksp/cryptobot/research/ResearchHandoffEngine.kt').read_text(encoding='utf-8')
          detector = Path('app/src/main/java/com/ksp/cryptobot/research/ResearchHandoffStrategyEngine.kt').read_text(encoding='utf-8')
          models = Path('app/src/main/java/com/ksp/cryptobot/research/ResearchHandoffModels.kt').read_text(encoding='utf-8')
          verifier = Path('app/src/main/java/com/ksp/cryptobot/release/V4SystemVerifier.kt').read_text(encoding='utf-8')
          helper = Path('app/src/main/java/com/ksp/cryptobot/research/WeeklyResearchFormalizations.kt').read_text(encoding='utf-8')
          catalog_source = Path('app/src/main/java/com/ksp/cryptobot/research/ResearchHandoffCatalog.kt').read_text(encoding='utf-8')
          ui = Path('app/src/main/java/com/ksp/cryptobot/ui/V4ControlCenterScreen.kt').read_text(encoding='utf-8')

          checks = {
              'latest freeze': catalog.get('research_freeze') == '2026-08-22' and manifest.get('research_freeze') == '2026-08-22',
              '37 strategies': len(strategies) == 37 and manifest.get('strategy_count') == 37,
              'all weekly additions': new_ids <= ids,
              '55 video research rows': video_rows == 55,
              'all strategy source refs resolve': not unresolved,
              'engine evaluates all 37': 'check(rows.size == 37)' in engine and 'handoff evaluated=37' in engine,
              'fidelity labels preserved': 'fidelityLabel' in models and 'displayFidelityLabel' in models,
              'scalar and array source rules preserved': 'stringsValue(o: JSONObject' in catalog_source and 'entryTrigger = stringsValue(o, "entry_trigger")' in catalog_source,
              'mandatory source-faithfulness report': 'sourceFaithfulnessUnknowns' in models and 'sourceFaithfulnessReport' in models,
              'mandatory strategy context gate': 'BLOCK_CONTEXT_TRUTH' in engine and 'usageContextResolvedFromHandoff' in engine,
              'Dunn formalization is versioned': 'DUNN_1234_FORMALIZATION_VERSION' in helper and 'Numeric windows/thresholds' in helper,
              'Ichimoku is context-only': 'no single universal entry rule' in helper and 'olszewiczIchimoku' in detector,
              'Alligator exact parameters not invented': 'josh_olszewicz_alligator_fractal_public_core' in detector and 'sourceInsufficient' in detector,
              'Krown LTI private preset not cloned': 'PARTIAL_PUBLIC_CORE' in helper and 'Exact preset thresholds/N remain proprietary' in detector,
              'Cowen macro missing data not silently bullish': 'missing macro is reported UNKNOWN' in detector,
              'Pizzino secondary candidate hard blocked': 'pizzino_three_bar_confirmation_candidate_2026' in detector and 'Primary entry/invalidation/stop/sizing/management/exit rules remain unresolved' in detector,
              'system verifier proves catalog': 'Research handoff truth catalog' in verifier,
              'research UI shows current catalog': '2026-08-22 Research Handoff' in ui and 'All 37 handoff strategies/processes' in ui,
          }
          for name, ok in checks.items():
              print(('PASS' if ok else 'FAIL') + ' | ' + name)
          if unresolved:
              print('UNRESOLVED SOURCE REFS: ' + ', '.join(unresolved))
          failed = [name for name, ok in checks.items() if not ok]
          if failed:
              raise SystemExit('Strategy-truth contract failure: ' + ', '.join(failed))
          PYTRUTH

'''
        text = text[:idx] + block + text[idx:]

    # Keep the new log with failure artifacts when the existing failure bundle command is present.
    text = text.replace(
        "v4-0-7-stabilization.log preview-visual-contracts.log",
        "v4-0-7-stabilization.log research-truth-2026-08-22.log preview-visual-contracts.log"
    )
    workflow.write_text(text, encoding="utf-8")

    print(f"Installed research patch: {target_patch}")
    print(f"Installed authoritative handoff payload: {target_assets}")
    print(f"Updated canonical workflow: {workflow}")
    print("PASS | CTS v4.0.7 + 2026-08-22 Strategy Truth implementation installed")


if __name__ == "__main__":
    main()
