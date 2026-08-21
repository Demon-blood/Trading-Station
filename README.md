# Crypto TradeStation v4.0.2 — Exact Preview UI Hotfix 3

## Confirmed compile-stage defect

The generated `PreviewReplicaUi.kt` contained two Compose `Box(...)` calls without
a content lambda:

1. the small asset-allocation legend color marker;
2. the selected segmented-tab underline.

Compose `Box` requires a content block, so both statements are Kotlin compile errors.

## Fix

Both visual-only boxes now use an explicit empty content block:

`Box(...) {}`

Replace only:

`.cts-v4-migration/apply_exact_preview_ui.py`

No workflow, diagnostics, or full-integration-cleanup file needs changing for this
specific compile failure.

Validation performed:
- migration Python syntax: PASS
- generated PREVIEW_SOURCE extraction: PASS
- both invalid Box statements removed: PASS
- no remaining one-line `Box(...)` statement without a content block: PASS
