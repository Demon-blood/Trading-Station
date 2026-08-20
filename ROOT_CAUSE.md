# Exact Preview UI Hotfix 1 — Root Cause

The failed GitHub Actions build exposed an integration mistake in the redesign layer.

## Confirmed repository mismatch

The repository commit `091b208c768297de78329681f1e02abbc56898b9` contains a smaller
`apply_exact_preview_ui.py` migration that replaces the News UI after the full-integration
migration. That replacement drops the previously wired provider-health UI.

The canonical validation then checks that News provider health remains wired, so the build
correctly fails instead of silently shipping a regression.

The committed workflow also reports the old v4.0.1 / versionCode 106 identity, while the
preview redesign release is v4.0.2 / versionCode 107.

## Hotfix

Replace exactly these two repository files:

- `.cts-v4-migration/apply_exact_preview_ui.py`
- `.github/workflows/android-v4-build.yml`

The replacement preview migration is the full redesign implementation. It writes
`PreviewReplicaUi.kt`, keeps `NewsProviderHealthRegistry.snapshot()` in the premium News screen,
preserves the functional integration cleanup, and styles the deeper screens consistently.

The workflow now validates provider health in `PreviewReplicaUi.kt` (where the premium News
screen lives), preflights the migration before applying it, and consistently builds v4.0.2 (107).
