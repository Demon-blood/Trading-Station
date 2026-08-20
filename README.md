# Crypto TradeStation v4 — Exact Preview UI

The previously approved preview is the visual source of truth for this release.

Upload/replace these GitHub files:

- `.github/workflows/android-v4-build.yml`
- `.cts-v4-migration/apply_diagnostics_integration_fix.py`
- `.cts-v4-migration/apply_full_integration_cleanup.py`
- `.cts-v4-migration/apply_exact_preview_ui.py`

The canonical Action now applies diagnostics, Milestone 6, the full integration cleanup, then the exact-preview UI before Kotlin compilation/tests/build/signing verification.

Implemented visual contract:
- five-root bottom navigation: Dashboard / Portfolio / AI / News / Settings
- compact top bar
- dark navy/black premium palette
- purple navigation/action accents
- green positive/bullish/profit states
- red loss/exit/error states
- compact 12dp cards
- Dashboard line + shaded area graph
- Portfolio allocation donut
- AI confidence gauge and compact signal rows
- News sentiment and story cards
- segmented Settings with Connection & Trading / Automation & Risk
- switch-style settings controls
- preview-style System Test health screen

Dynamic balances, P/L, positions, AI scores and news are sourced from the real app state; the mock preview numbers are not hardcoded.

Validation performed in this pack:
- Python migration syntax compilation
- workflow visual-contract assertions

A full Android/Kotlin build, unit tests, APK identity and signer checks still require the real GitHub Action run before the APK can be called verified.
