# M13 v1.1 — ExecutionReport scope fix

No runtime design change.

The v1 applier tried to add `executionId` by matching only:

```kotlin
val clientOrderId: String,
val symbol: String,
```

That field pair occurs twice in `KrakenPrivateExecutionState.kt`, so the controlled
`replace_once` correctly stopped with:

`M13 execution id model: expected one match, got 2`

v1.1 scopes the replacement to the full beginning of:

```kotlin
data class ExecutionReport(
    val orderId: String,
    val clientOrderId: String,
    val symbol: String,
```

so only the intended private execution report gains `executionId`.

Replace:
`tools/apply_m13_private_execution_ledger.py`

Commit it to `main`, then launch a NEW M13 workflow from `main`.

The Apply M13 log must start with:

`INFO | M13 applier revision v1.1`
