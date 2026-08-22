# Crypto TradeStation v4.0.7 — ALL IN ONE

Target repository:
- `Demon-blood/Trading-Station`
- branch: `main`
- canonical workflow: `.github/workflows/android-v4-build.yml`

This archive combines the entire current v4.0.7 stabilization/completion work into one package.

Included:
- CTS-READINESS-001 startup readiness state machine
- CTS-BALANCE-002 spendable-balance model
- CTS-ORDERINTENT-003 first-class OrderIntent / Kraken validate path
- strategy provenance/version registry
- `CTS_TURTLE_SPOT_SAFE`
- Koroush source-framework / CTS-reference separation
- persistent committed candle history
- persistent execution state / ReservationLedger
- unified OrderIntent router
- PAPER/LIVE/SHADOW/BACKTEST domain isolation
- HIGH/CRITICAL entry-block / protective-exit policy
- centralized news acquisition budgets/cache
- database retention/WAL maintenance
- external reference normalization and numeric sanity
- correlation/small-account/promotion guards
- cost-aware Turtle risk sizing
- handoff research/backtest adapters and tests
- hard release-contract checks
- canonical 4.0.7 / versionCode 112 workflow identity corrections

## Install

From PowerShell:

```powershell
powershell -ExecutionPolicy Bypass -File `
".\CTS_v4.0.7_ALL_IN_ONE_2026-08-22\INSTALL_ALL_IN_ONE.ps1" `
"C:\path\to\Trading-Station"
```

The installer:
1. backs up the canonical workflow,
2. installs both migration scripts into `.cts-v4-migration`,
3. installs the completion `payload` beside the migration,
4. validates Python syntax,
5. installs both canonical-workflow hooks.

It intentionally does NOT mutate the raw pre-migration `app/` tree in-place.
The effective source is patched during the canonical build after the existing v4 source-generation/migration layers.

## Important validation status

The migration scripts themselves pass Python syntax validation.
The all-in-one archive is a build/install package; a successful Android Gradle build and GitHub Actions release gate are still the final authority for APK release readiness.
