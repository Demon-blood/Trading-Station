# M12 v1.1 — Reconciliation scope hotfix

No M12 Android/runtime design has changed.

The v1 patcher assumed the exact M11 reconciliation text occurred twice globally in
BotController.kt. Current main has two required reconciliation paths, but only one
matches that global block byte-for-byte.

v1.1 patches each required function independently:

1. `reconcileLiveExecutionState(...)` — strict startup/recovery truth.
2. `scanOnce(...)` — normal LIVE_AUTO pre-scan reconciliation.

Both still receive `KrakenOrderTruthResolver.resolveDurable(exchange)` and both
remain fail-closed while durable client-order ambiguity exists.

Replace:
`tools/apply_m12_order_truth_authority.py`

Then launch a NEW M12 workflow_dispatch run from `main`.
Do not use Re-run jobs on the failed run.
