# Crypto TradeStation v4.0.2 — Exact Preview UI Hotfix 1

Upload/replace only these two files in `Demon-blood/Trading-Station`:

1. `.github/workflows/android-v4-build.yml`
2. `.cts-v4-migration/apply_exact_preview_ui.py`

Commit them to `main`. Do not rerun the old failed attempt; the new commit will start a fresh
canonical build.

This hotfix keeps the exact-preview redesign while preserving News provider health/cooldown
wiring and aligns the canonical release identity to v4.0.2 / versionCode 107.
