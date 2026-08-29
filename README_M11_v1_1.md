# M11 v1.1 verifier scope fix

No Android or trading runtime code is changed.

The original M11 verifier used global `str.find()` positions across entire Kotlin
files for two ordering checks:

1. authoritative reconciliation reads before local position mutation
2. Kraken AddOrder pending marker before the AddOrder HTTP transport call

Those files contain unrelated earlier occurrences of `"open orders"` and
`http.newCall(req).execute().use`, so the verifier compared the M11 code against
the wrong occurrence and produced false failures.

v1.1 scopes:
- reconciliation ordering to `AdvancedExecutionCoordinator.reconcileLive()`
- AddOrder ordering to `KrakenSpotClient.placeOrder()`

Replace:
`tools/verify_m11_execution_fail_closed.py`

Commit the replacement to main and rerun:
Actions -> M11 Execution Fail Closed Hardening
