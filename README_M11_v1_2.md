# M11 v1.2 — Kraken placeOrder verifier scope fix

No runtime or Android code changes.

v1.1 correctly scoped the reconciliation assertion, but the AddOrder assertion still
started at the first `override suspend fun placeOrder(...)` in ExchangeClientsV08.kt.
That file contains multiple exchange clients, so the verifier compared Kraken's
pending marker against an unrelated earlier HTTP call.

v1.2 first isolates `class KrakenSpotClient`, then isolates Kraken's own `placeOrder()`.
The ordering assertion now verifies only:

KrakenPrivateExecutionRegistry.markSubmissionPending(...)
    occurs before
http.newCall(req).execute().use

Replace:
tools/verify_m11_execution_fail_closed.py

Commit to main and launch a NEW M11 workflow_dispatch run from main.
Do not use Re-run jobs on an older run.
