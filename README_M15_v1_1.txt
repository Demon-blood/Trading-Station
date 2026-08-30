M15 v1.1 — Kraken-scoped AmendOrder insertion hotfix

No runtime design change.

Root cause:
ExchangeClientsV08.kt contains multiple exchange connector implementations with a
cancelOrder(orderId) override. The M15 v1 applier searched for that method globally
and correctly refused to patch because it found 2 matches.

v1.1 scopes the insertion to the top-level KrakenSpotClient class before searching
for the cancelOrder anchor. It also asserts that exactly one Kraken AmendOrder
implementation exists after patching.

Replace exactly:
tools/apply_m15_atomic_amend_lifecycle.py

Commit to main and launch a NEW M15 workflow from main.

Expected log:
INFO | M15 applier revision v1.1

No Kotlin runtime, trading policy, amend policy, tests, or verifier files changed.
