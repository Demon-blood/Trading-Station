# Desktop v1.0.50 -> Android v4 migration roadmap

The migration is complete: **6 / 6 stages**.

## Stage 1 / M1 — CloudShare + data foundation — COMPLETE
- Worker client/auth/admin, deterministic protocol, outbox/retry/download cursor
- Android Keystore credentials and foreground sync
- non-destructive Room migration 6 -> 7

## Stage 2 / M2 — Collective learning — COMPLETE
- shared aggregates/backfill/index/cache and desktop-parity scoring
- AI + strategy-vote integration, bootstrap + diagnostics
- Room migration 7 -> 8

## Stage 3 / M3 — Governance + production safety — COMPLETE
- anomaly firewall, safe mode, kill switch and risk budgets
- counterfactual/execution-quality learning, Why-Not-Trade and watchdog/crash evidence
- PAPER execution guard correction
- Room migration 8 -> 9

## Stage 4 / M4 — Advanced execution + portfolio/risk — COMPLETE
- production multiplier applied to actual BUY sizing
- capital protection, portfolio allocation, liquidity sizing and fee-efficiency gate
- order-type optimization, reconciliation, lifecycle exit optimization
- realistic paper fills/fees/slippage/depth
- Room migration 9 -> 10

## Stage 5 / M5 — Research + strategy/AI expansion — COMPLETE
- 23 desktop-derived research strategy votes
- advanced regime classification
- walk-forward + Monte Carlo validation
- meta-model, cross-symbol, mutation/hypothesis and parameter suggestions
- sequence model + RL sandbox + order-book replay research
- Kraken Futures, optional labeled-wallet and cross-market read-only context
- desktop-compatible research/on-chain CloudShare aggregates
- Room migration 10 -> 11

## Stage 6 / M6 — Unified UI + hardening + release — COMPLETE
- V4 Systems navigation and consolidated migrated-system UI
- complete CloudShare + research settings surfaces
- v4 final verification integrated into System Test
- supplemental v4 backup/restore and redacted diagnostics bundle
- Android-native operational data compaction/storage audit
- APK signer/install-lineage checks
- release-signing guard
- final version `4.0.0 / 105`, Room remains v11

## Platform-specific desktop features

Windows-only mechanics are intentionally replaced rather than embedded:

- Tkinter UI -> Jetpack Compose;
- DPAPI -> Android Keystore;
- PowerShell/BAT management -> native Android UI/client operations;
- Windows data paths -> Android app storage / Storage Access Framework;
- PyInstaller updater/build tooling -> signed APK install/update lineage;
- Windows database maintenance scripts -> Android Room/SQLite maintenance manager.
