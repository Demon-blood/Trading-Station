# Crypto TradeStation Android v4 — Research Handoff Truth Automation

This is a **cumulative GitHub upload patch** for `Demon-blood/Trading-Station`.
It contains the complete Android v4 M1→M6 migration payload plus the desktop-parity, professional-strategy, and 2026-08-17/18 research-handoff truth implementation.

## Upload

Extract this ZIP over the root of the GitHub repository, preserving the hidden directory:

```text
.cts-v4-migration/
```

Commit the overwritten/new files to `main`. The existing workflow:

```text
.github/workflows/android-v4-build.yml
```

already runs `python3 .cts-v4-migration/apply_milestone6.py "$GITHUB_WORKSPACE"`, so the next push automatically applies this payload and builds with GitHub Actions.

The ZIP intentionally does **not** replace the workflow file. The current repository workflow already contains the corrected non-destructive-Room validator and GitHub SDK/Gradle build steps.

## Automatic runtime path

For every scanned symbol the Android app now runs the layers in this order:

```text
Kraken/public market data + data-integrity checks
    ↓
Desktop-parity strategy family
    ↓
Professional/practitioner strategy variants
    ↓
31-record research-handoff strategy laboratory
    ↓
Cost / liquidity / fee-tier gate
    ↓
Source-specific risk sizing + exchange minimums
    ↓
Empirical walk-forward / Monte-Carlo promotion gate
    ↓
M3 governance / safe mode / anomaly / kill switch
    ↓
M4 capital ceiling / portfolio / liquidity sizing
    ↓
Source-faithful order type + post-only/trigger semantics
    ↓
Kraken spot execution or truthful PAPER execution
    ↓
Source stop / target lifecycle + realized-outcome learning
```

No downstream layer may increase an order above the already approved balance/reserve/risk ceiling.

## Truth behavior

All 31 handoff records are evaluated automatically. The research package itself currently marks **zero positive strategies as `live_truth_gate=PASS`**. This patch deliberately preserves that fact rather than inventing missing creator rules.

Therefore:

- positive source strategies automatically stage/execute in PAPER when their setup, trigger/staging, cost and risk gates pass;
- positive LIVE handoff entries require both a future source-truth PASS update and strategy-specific empirical promotion;
- negative filters and protective risk reductions can act automatically because they reduce exposure;
- `krown_vmp_exact` and `cowen_price_risk_exact` remain `BLOCKED_SOURCE_UNKNOWN` and are never replaced with fake proxy formulas;
- formalized/concept-inspired rules are labelled as such and never presented as creator-exact.

## Execution truth added in this release

- account/pair maker/taker fee lookup from Kraken `TradeVolume`, with conservative fallback;
- Kraken `ordermin`, `costmin`, amount precision and `tick_size` enforcement;
- post-only LIMIT maps to maker-only behavior rather than silently becoming taker;
- source resting LIMIT/STOP orders remain pending until genuinely executable in PAPER;
- PAPER wallet is unchanged while a resting/conditional order is pending;
- deferred PAPER fills enter trade history and realized-P&L learning;
- accepted-but-unfilled LIVE orders are never recorded as fills;
- sourced LIVE BUYs carry their technical stop into Kraken as a conditional stop-loss close where supported;
- after a confirmed sourced entry, stop coverage is verified; missing protection attempts a standalone stop;
- if protection still cannot be verified, the fail-safe attempts an emergency MARKET flatten and records `UNPROTECTED_POSITION` state;
- source-managed lifecycle exits cancel an existing exchange stop before selling, refuse an unsafe competing sell if cancellation is not verified, and restore protection after partial/unfilled/failed exits;
- source-specific stop/target metadata survives app restarts.

## Small-account risk truth

Risk quantity is bounded by the handoff loss model:

```text
loss_per_unit = (entry - stop)
                + entry_fee
                + stop_exit_fee
                + entry_slippage
                + stop_exit_slippage
qty_risk = risk_budget / loss_per_unit
qty = min(qty_risk, qty_cash)
```

Quantity is rounded **down** to Kraken precision. If exchange minimum quantity or minimum cost would require more modeled loss than the risk budget allows, the trade is skipped. The app never increases risk just to satisfy an exchange minimum.

## Included research source pack

The APK migration embeds all 15 files from the supplied handoff under:

```text
app/src/main/assets/research_handoff/
```

The runtime catalog verifies the asset set and expects exactly 31 strategy records. Provenance aliases are explicit and audited.

## Build status

Local static/pure-Kotlin validation is green; see `VALIDATION_REPORT_HANDOFF_TRUTH.json` and `RESEARCH_HANDOFF_TRUTH_AUDIT.md`.

A real Android SDK/KSP/Compose APK build still must reach green in **GitHub Actions**. Do not treat the APK as validated until the `Crypto TradeStation v4 Build` workflow succeeds and uploads its artifact.
