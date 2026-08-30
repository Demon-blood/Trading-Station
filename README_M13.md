# Crypto TradeStation M13 — Private Execution Fill Ledger & Order Identity Hardening

M13 builds directly on the merged M12 execution-truth layer.

## What changes

### 1. Kraken-native UUID `cl_ord_id`

Exchange-facing entry, lifecycle-exit, protective-stop, and emergency-flatten orders stop using `symbol + currentTimeMillis` identifiers.

M13 uses UUIDs directly through `KrakenClientOrderId.newId()`.

Kraken supports long UUID, short UUID, and free-text `cl_ord_id` formats and enforces uniqueness across open orders. UUIDs remove same-millisecond collision risk without requiring truncation/hashing.

### 2. Exact private execution-event ledger

Kraken WebSocket v2 `executions` provides trade events with:

- `exec_id`
- `order_id`
- `cl_ord_id`
- `last_qty`
- `last_price`
- fee rows
- cumulative quantity / average price / order status

M13 persists unseen `trade` events by `exec_id`. Reconnect/snapshot replay is idempotent because a previously-recorded `exec_id` is ignored.

This is the fast path for realized execution truth.

### 3. REST remains the authoritative fallback

Private WebSocket delivery is not assumed to be immortal.

For open partially-filled orders, M13 keeps M12's authoritative `OpenOrders` fallback but expands it to both BUY and SELL.

The REST path calculates the unrecorded fill price from cumulative economics:

```text
cumulative cost = cumulative quantity × cumulative average price
new fill cost   = cumulative cost - already journaled fill cost
new fill price  = new fill cost / unrecorded quantity
```

This prevents a new 0.30 fill from incorrectly inheriting the order's entire cumulative average.

### 4. Partial SELLs become journal truth

Open SELL partial fills now record:

- incremental quantity
- incremental fee
- incremental realized PnL against tracked entry price

Position quantity is then refreshed from authoritative exchange balances rather than guessed from local order math.

### 5. WebSocket permission diagnostics

Kraken currently requires the API key setting:

`WebSocket interface - On`

for `GetWebSocketsToken`.

M13 detects permission-like token/subscription errors and writes that exact requirement into runtime diagnostics rather than showing only `Permission denied`.

### 6. Existing safety layers remain authoritative

M13 does not weaken:

- M4 ambiguous-submission BUY gate
- M5 net-EV gate
- M9/M10 champion governance
- M11 fail-closed reconciliation
- M12 durable `cl_ord_id` recovery
- M12 distributed LIVE engine authority
- protective SELL/EXIT behavior

Kraken REST remains authoritative when private WebSocket execution truth is absent or incomplete.

## No Room migration

M13 uses existing trade rows and embeds the Kraken `exec_id` marker in the journal reason for replay deduplication. Room remains version 12.

## Run

Copy this package into the repository root, preserving paths, and commit the bootstrap files to `main`.

Then launch a NEW workflow:

**Actions → M13 Private Execution Fill Ledger → Run workflow → main**

Expected branch:

`milestone/m13-private-execution-ledger-<run-number>`
