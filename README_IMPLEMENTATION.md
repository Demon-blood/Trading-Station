# Crypto TradeStation v4.0.7 — 2026-08-22 Research Truth Implementation

This package installs the latest supplied crypto-trading research handoff into the canonical `Demon-blood/Trading-Station` v4 build path **and** keeps the v4.0.7 execution-integrity stabilization in the same release.

## What this implementation does

- Replaces the old 2026-08-17 / 31-entry handoff assets with the authoritative 2026-08-22 package: **37 strategies, 10 traders, 55 indexed videos**.
- Updates both `.cts-v4-migration/app/...` and effective `app/...` trees so a later migration run cannot silently restore the old catalog.
- Preserves scalar **and** array rule fields from `STRATEGY_CATALOG.json`; the weekly additions use both forms.
- Adds explicit fidelity-label handling and a source-faithfulness report for entry, invalidation, stop, sizing, management, exit, usage context, no-trade conditions, and version/source.
- Adds a mandatory strategy-context/source-truth gate **before cost/risk/execution**. Missing context remains unknown; it is not inherited from older research or guessed.
- Updates the handoff engine invariant from 31 to 37 strategies.
- Implements the six 2026-08-22 additions conservatively:
  - Chris Dunn 1234 Crypto Breakout: versioned D1 **app formalization for PAPER research**, preserving trend → lower-volume pullback → consolidation/retest → high-volume breakout. Numeric windows/thresholds, structural stop and 2R research target are explicitly app-owned formalizations. The supplied live truth gate still blocks LIVE.
  - Josh Olszewicz 20/60/120/30 Ichimoku: context/toolkit calculations only; no fabricated universal entry.
  - Josh Olszewicz Alligator + Fractal: source-insufficient hard block; exact original parameters are not invented.
  - Krown LTI public core: independent partial context only; proprietary preset thresholds/weights/N are not cloned.
  - Cowen macro regime memo: research context only; missing macro inputs remain UNKNOWN and contribute zero bullish adjustment.
  - Pizzino three-bar candidate: hard blocked because only secondary evidence is present and primary entry/invalidation/stop/sizing/management/exit remain unresolved.
- Adds Kotlin unit-test fixtures for the new deterministic formalizations.
- Adds System Verification + Research UI catalog auditing.
- Includes the previously prepared v4.0.7 stabilization: duplicate/phantom PAPER fill protection, exposure-cap enforcement, PAPER reconciliation, P/L truth, operational-error classification and database-table diagnostics.

## Important truth-preserving behavior

The latest 2026-08-22 catalog does **not** carry the mandatory structured real-life `usage_context` / no-trade context for the older 31 definitions. This installer does **not** silently copy those details from an older package. Their detectors remain available for research/audit, but automatic influence/execution fails closed with `BLOCK_CONTEXT_TRUTH` until a future authoritative handoff supplies the missing context. This is intentional and follows the attached Strategy Truth Standard.

The current handoff has structured source context + no-trade evidence for 5/37 definitions. That does **not** mean those five are live-enabled: fidelity, source truth, detector actionability, cost, risk, empirical evidence and the explicit per-strategy `live_truth_gate` still apply.

## Install

From Windows PowerShell after extracting this package:

```powershell
powershell -ExecutionPolicy Bypass -File .\INSTALL_RESEARCH_TRUTH_2026_08_22.ps1 "C:\path\to\Trading-Station"
```

or:

```powershell
python .\INSTALL_RESEARCH_TRUTH_2026_08_22.py "C:\path\to\Trading-Station"
```

Commit/push the resulting repository. The canonical `android-v4-build.yml` workflow will then:
1. apply the cumulative v4 generation path,
2. apply v4.0.7 stabilization,
3. apply the 2026-08-22 research-truth patch,
4. run static truth contracts and unit tests,
5. build the APK.

## Validation performed while packaging

- Python syntax compilation for installer and research patcher.
- 37 unique strategy IDs; six weekly IDs present.
- 55 video-index rows.
- All strategy `source_refs` resolve directly or through the seven explicit legacy aliases already defined by the app.
- Research patcher applied successfully to a synthetic fixture matching current repository anchors.
- Research patcher applied a second time successfully (idempotency test).
- Master installer applied twice without duplicating workflow steps (idempotency test).
- `WeeklyResearchFormalizations.kt` compiled with local `kotlinc`.
- Formalization behavioral self-test passed for Dunn volume breakout, Ichimoku context-only behavior, and Krown partial-public-core behavior.
- Patched source-faithfulness model compiled with local `kotlinc` fixture.

## What is not claimed

The connected GitHub integration does not have write permission to `Demon-blood/Trading-Station`, so this session could not push a branch or run repository GitHub Actions. Local transformation/compilation checks are not a substitute for the full Android/Gradle CI run. The installer adds CI contracts so the actual repository build will fail if the generated effective source does not contain the required truth/safety behavior.
