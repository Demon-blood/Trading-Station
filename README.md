# Crypto TradeStation v4.0.2 — Exact Preview UI Hotfix 4

## Confirmed compiler error

GitHub Actions reported:

`MainActivity.kt:32:43 Cannot access 'val RowColumnParentData?.weight: Float': it is internal in file.`

## Root cause

The exact-preview migration explicitly injected:

`import androidx.compose.foundation.layout.weight`

With the Compose version used by Crypto TradeStation, that import resolves to an
internal parent-data symbol. `Modifier.weight(...)` should instead resolve as the
normal `RowScope` / `ColumnScope` member extension.

The original app already used `Modifier.weight(...)` without this explicit import.

## Fix

The redesign migration no longer injects the import and also removes it defensively
if it is already present in the effective `MainActivity.kt`.

Replace only:

`.cts-v4-migration/apply_exact_preview_ui.py`

No workflow, diagnostics, or full-integration-cleanup file needs changing for this
specific compiler error.

Validation:
- migration Python syntax: PASS
- invalid import injection removed: PASS
- defensive bad-import removal present: PASS
- generated PreviewReplicaUi.kt does not import layout.weight: PASS
