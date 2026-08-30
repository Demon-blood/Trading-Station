# M12 v1.2 — indentation-aware reconciliation patch

No runtime design change.

The current `BotController.kt` has:
- 8-space indentation in `reconcileLiveExecutionState(...)`
- 12-space indentation in the LIVE block inside `scanOnce(...)`

v1.1 scoped the functions correctly but still matched an 8-space multiline block,
so `scanOnce()` returned zero matches.

v1.2 locates only this statement inside each function:

`val reconciliation = advancedExecution.reconcileLive(settings, exchange)`

It derives that line's actual indentation and inserts the M12 `KrakenOrderTruthResolver`
block immediately afterward. This preserves both current layouts and remains fail-closed.

Replace:
`tools/apply_m12_order_truth_authority.py`

Commit to `main`, then launch a NEW M12 workflow from `main`.
Do not use Re-run jobs on the failed run.
