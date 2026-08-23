# Crypto TradeStation M4 — Kraken execution-state hardening

The connected ChatGPT GitHub integration still returns:
`403 Resource not accessible by integration`
for direct repository writes, so this milestone remains GitHub-Actions-first.

## Install

Copy this ZIP into the repository root, preserving paths, and commit the bootstrap
files to `main`.

Then run:

**Actions → M4 Kraken Execution-State Hardening → Run workflow**

The workflow patches the canonical app, verifies M4 + M3.2 + M3 + v4.0.7,
compiles Kotlin, runs unit tests, assembles an APK, and pushes:

`milestone/m4-execution-hardening-<run>`

## M4 changes

- strictly monotonic Kraken nonces across Kraken REST calls
- REST AddOrder uses Kraken `cl_ord_id`
- old AddOrder `userref` is removed because Kraken documents it as mutually exclusive with `cl_ord_id`
- valid Kraken UUID client order IDs are preserved
- longer arbitrary IDs are deterministically converted to <=18 ASCII characters
- authenticated Kraken WebSocket v2 `executions` stream
- explicit open-order and trade snapshots
- all order-status transitions enabled
- `order_id` ↔ `cl_ord_id` correlation
- `pending_new`, `new`, `partially_filled`, `filled`, `canceled`, `expired` modeling
- cumulative fills, latest fill, average/latest price and fees captured
- sequence-gap detection invalidates state and reconnects
- ambiguous AddOrder results quarantine new LIVE_AUTO entries
- direct REST open-order check blocks a duplicate BUY for the same symbol
- successful private execution events clear matching ambiguous submissions
- recent full REST reconciliation is the fallback truth source for 60 seconds
- new LIVE_AUTO BUY entries require known execution state
- SELL/protective exits remain available during private-feed degradation
- foreground notification shows private execution state as known/unknown

## Kraken key permission

For the authenticated private WebSocket feed, the Kraken API key needs:

`WebSocket interface - On`

Kraken says a WebSockets token should be used within 15 minutes of creation; after a
successful maintained private subscription it remains valid for that connection.
M4 fetches a fresh token for each new private connection/reconnect.
