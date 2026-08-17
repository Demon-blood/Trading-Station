# Crypto TradeStation Android v4 — Milestone 5 (Research + Strategy/AI Expansion)

This is the **cumulative** Android v4 source overlay for `Demon-blood/Trading-Station`. It contains M1 + M2 + M3 + M4 + M5. The installer can upgrade an existing validated M4 tree directly and automatically applies the bundled validated M4 baseline first when starting from an older supported source tree.

## Apply on Windows

```powershell
python .\apply_milestone5.py C:\path\to\Trading-Station
cd C:\path\to\Trading-Station
.\gradlew clean :app:assembleDebug
```

Use `apply_milestone5.py`. `_baseline_m4` is an internal cumulative-baseline helper and should not be run manually for an M5 installation.

## Target build

- versionName: `4.0.0-m5`
- versionCode: `104`
- Room database: version `11`
- explicit migrations: `6 -> 7 -> 8 -> 9 -> 10 -> 11`
- no `fallbackToDestructiveMigration()`

## M5 adds

- 23 desktop-derived research strategy votes;
- advanced market-regime classification and regime/strategy compatibility;
- rolling walk-forward validation;
- deterministic Monte Carlo robustness simulation;
- meta-model outcome scoring;
- cross-symbol BTC/ETH broad-market confirmation;
- bounded strategy mutation and autonomous hypothesis generation;
- conservative parameter-optimizer suggestions;
- persistent tiny sequence-model research scoring;
- persistent reinforcement-learning sandbox;
- order-book replay research;
- Kraken Futures public context (read-only; no futures trading);
- optional labeled-wallet/Whale Alert intelligence, with the API key stored through Android Keystore;
- read-only cross-market reference confirmation; Kraken remains the execution venue;
- persistent research event/profile/state tables;
- exact desktop-compatible CloudShare research aggregates:
  - `shared_research_daily`
  - `shared_strategy_variant_daily`
  - `shared_walk_forward_daily`
  - `shared_onchain_daily`

## Safety invariants

1. Research runs **before** M3 production governance, so anomaly/safe-mode/kill-switch/risk-budget gates still see the research-adjusted decision.
2. M4 remains the sole final capital/execution planner and its post-balance ceiling is unchanged.
3. M5 research score adjustments are bounded (defaults `-8..+6`).
4. Research-originated PAPER entries may be enabled; research-originated LIVE entries are **disabled by default**.
5. An upstream `SELL` is preserved: research cannot change the SELL action, reduce its confidence, or turn an allowed exit into a blocked exit.
6. Futures/on-chain/cross-market modules are context-only and cannot route trades away from Kraken.

See `docs/MILESTONE_5.md`, `docs/PORT_ROADMAP.md`, and `VALIDATION_REPORT_M5.json`.
