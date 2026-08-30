# Crypto TradeStation M12 — Authoritative Order Truth, Partial Fills & Distributed Engine Authority

M12 closes the next execution-state gaps after M11.

## 1. Kraken client-order IDs become authoritative recovery keys

Kraken supports `cl_ord_id` on Spot orders. M12 exposes it in the Android order models and adds a Kraken-specific resolver.

Recovery flow:

```text
durable unresolved AddOrder
        ↓
OpenOrders(cl_ord_id)
        ├─ found → authoritative OPEN
        └─ not found
              ↓
ClosedOrders(cl_ord_id)
        ├─ found → authoritative CLOSED
        └─ not found → keep quarantined during 10-minute consistency grace
                           ↓
                     still not found after grace
                           ↓
                       clear quarantine
```

An OpenOrders or ClosedOrders API failure throws. It is never interpreted as "not found."

M12 runs this resolution in both:
- normal LIVE reconciliation before execution; and
- strict startup/recovery reconciliation.

New BUY authority remains blocked while durable ambiguity remains.

## 2. Partial fills are real exposure

M12 no longer waits for a Kraken order to become fully closed before treating executed quantity as exposure.

For an open BUY:

```text
submitted 1.00
filled    0.40
remaining 0.60
```

the app now records only the actual 0.40 exposure, updates the position to `OPEN_PARTIAL`, and protects the cumulative filled quantity.

If the same cumulative fill is seen again, the journal delta is zero.

If it later grows to 0.70, only 0.30 is added.

When the order finally closes at 1.00, closed-order sync records only the remaining 0.30—not another full 1.00.

Fees use the same cumulative-delta rule.

## 3. Protective stops grow with partial exposure

The previous stop verifier could place a complete new stop whenever coverage was below the requested quantity.

M12 computes:

```text
missingCoverage = cumulativeFilledQuantity - existingProtectiveStopCoverage
```

and adds only the missing stop quantity.

This prevents repeated partial-fill scans from over-covering / over-selling a position.

## 4. Real distributed single-engine LIVE authority

A local mutex is not enough: Android and Windows are separate processes/devices.

M12 upgrades CloudShare/D1 with an `engine_leases` table keyed by an account-stable Kraken fingerprint.

The Kraken account identity is derived from the Internal IBAN returned by `GetApiKeyInfo`, then hashed before it is sent to CloudShare. M12 deliberately refuses an API-key-specific fallback: two different API keys on one Kraken account must not become two independent leases.

Atomic D1 acquisition:

```text
Kraken account fingerprint
          ↓
CloudShare / engine-lease/acquire
          ↓
D1 UPSERT allowed only when:
  existing lease expired
  OR same client + same engine renews
          ↓
one holder
```

Policy:
- lease TTL: 75 seconds
- heartbeat: every 20 seconds
- lease loss / network error → runtime authority becomes UNKNOWN/LOST
- UNKNOWN/LOST → new LIVE BUYs blocked
- protective SELL/EXIT remains available
- process death → lease naturally expires
- explicit stop → best-effort release
- service destruction → release/expiry path

PAPER does not need this lease.

LIVE_AUTO and LIVE_CONFIRM do.

## 5. CloudShare is mandatory for LIVE engine authority

This is intentional.

If CloudShare is disabled, unregistered, unreachable, or still runs the old Worker without M12 lease routes:

```text
LIVE start = BLOCKED
PAPER      = available
```

Crypto TradeStation cannot truthfully guarantee "only one LIVE engine" with local storage alone.

### Existing CloudShare installations

The APK contains the upgraded D1 schema and Worker source, but the app does **not** store your Cloudflare provisioning token, so it cannot silently modify remote infrastructure.

After installing an M12 build, an existing CloudShare deployment must be upgraded/reprovisioned through the existing CloudShare setup flow using your Cloudflare token so that:
- `engine_leases` is created in D1; and
- the Worker receives `/v1/engine-lease/acquire`, `/heartbeat`, and `/release`.

Existing event data is not intentionally deleted by the `CREATE TABLE IF NOT EXISTS` upgrade path.

Until that remote upgrade is complete, LIVE fails closed.

## 6. Kraken Spot Dead Man's Switch capability

M12 implements:

```text
POST /0/private/CancelAllOrdersAfter
```

including timeout `0` to deactivate it.

It is **not automatically armed**.

Reason: Kraken's DMS cancels all open orders when it fires. Crypto TradeStation deliberately keeps exchange-side protective stop orders alive for held positions. Blindly arming a cancel-all timer could remove those stops exactly when the app loses connectivity.

A future policy can use DMS only where order classes and protection ownership prove that cancel-all is safer than keeping protective orders resting.

## 7. No Room migration

M12 keeps the Room schema at version 12.

The distributed lease is stored in Cloudflare D1; order-recovery information uses Kraken truth plus the M11 durable quarantine.

## Tests

M12 adds regressions for:
- 10-minute authoritative-not-found grace;
- incremental partial-fill quantity;
- no duplicate partial-fill replay;
- incremental cumulative fees;
- PAPER without distributed lease;
- LIVE_AUTO requires distributed lease;
- LIVE_CONFIRM requires distributed lease.

The M12 verifier also confirms the DMS is implemented but not auto-armed.

## Run

Copy this package into the repository root preserving paths and commit the bootstrap files to `main`.

Then launch a **new** workflow:

**Actions → M12 Authoritative Order Truth & Engine Authority → Run workflow → main**

Do not use "Re-run jobs" from an older commit.

Expected output branch:

`milestone/m12-order-truth-authority-<run-number>`
