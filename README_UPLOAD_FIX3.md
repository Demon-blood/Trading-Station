# Crypto TradeStation v4 — Migration Fix 3

This patch fixes GitHub Actions run `32178788192` failing during **Apply cumulative v4 migration** with:

```
Cannot patch controller exchange fill truth: expected exactly one match, found 0.
```

## Root cause
Milestone 3 inserts `productionIntelligence.observeExecution(...)` between the trade insert and the legacy `Order placed` status line. Milestone 4 was still requiring the pre-M3 block as an exact string, so the cumulative M3 -> M4 path aborted before Gradle/Kotlin compilation.

## Fix
Both cumulative Milestone-4 installer copies now accept either the pre-M3 or post-M3 controller form. The patch remains idempotent. Execution-quality recording is retained inside the confirmed-fill path, so accepted-but-unfilled orders are not treated as execution-quality observations.

## Upload
Upload this ZIP's contents to the repository root, preserving the hidden `.cts-v4-migration` directory and replacing the two existing `apply_milestone4.py` files. Commit to `main`; the canonical GitHub Actions workflow will run automatically.

No version bump is required because v4.0.1/code 106 never reached APK assembly in the failed run.
