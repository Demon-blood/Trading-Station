# M24 Cross-Platform LIVE Authority Protocol

M24 keeps CloudShare/D1 as the single distributed source of LIVE-engine ownership truth.
The protocol is HTTP/JSON and is intentionally usable by both Android and Windows clients.

Hard invariant: **only one LIVE engine may submit new orders for one Kraken account authority key.**
Dashboard-only processes do not acquire the LIVE lease.

Supported holder platform values are exactly `ANDROID` and `WINDOWS`.

## Lease operations

All routes below require the existing authenticated CloudShare client headers.
The `account_key` is the existing SHA-256 Kraken account-authority identity; raw Kraken credentials are never sent.

- `POST /v1/engine-lease/acquire`
  - `account_key`, `engine_id`, `platform`, `ttl_seconds`
- `POST /v1/engine-lease/heartbeat`
  - `account_key`, `engine_id`, `platform`, `fence_token`, `ttl_seconds`
- `POST /v1/engine-lease/status`
  - `account_key`, `engine_id`, `fence_token`
- `POST /v1/engine-lease/release`
  - `account_key`, `engine_id`, `fence_token`
- `POST /v1/engine-lease/transfer`
  - `account_key`, `engine_id`, `fence_token`, `target_client_id`, `target_engine_id`, `target_platform`, `ttl_seconds`

## Fencing

The D1 row is never deleted on normal release. Release expires the row but preserves the fencing token.
A later acquisition therefore advances to a strictly newer fencing epoch.
Transfer is a single conditional D1 update and also increments the fencing token.
Old holder/fence pairs cannot heartbeat, release, transfer, or pass status after ownership changes.

## Final order boundary

Android performs an authenticated `/status` check immediately before a Kraken BUY `AddOrder` call.
The returned holder engine, platform, schema version, fencing token and remaining lease time must all match.
Network failure, malformed response, wrong platform, wrong owner, stale token, or expired lease rejects the BUY.
The full request RTT and a fixed safety margin are subtracted from server-reported remaining time.

Protective and exit SELL orders are intentionally not blocked by M24's new-entry authority gate.

## Windows integration requirement

A Windows LIVE engine must use this same protocol and the same account-authority derivation. It must perform the same authoritative status/fence validation at its own final Kraken AddOrder boundary. If Windows owns the lease, Android may remain connected as a dashboard but must not submit new LIVE BUY orders.
