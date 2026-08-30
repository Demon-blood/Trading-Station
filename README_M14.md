# M14 — Safe Kraken DMS & Distributed Authority Fencing v2

M14 builds on merged M13.

## What M14 changes

### 1. Distributed authority fencing v2

M12 introduced an account-level CloudShare/D1 lease. M14 adds:

- monotonically increasing `fence_token` on each new ownership epoch;
- `schema_version = 2`;
- heartbeat and release requests must present the active fencing token;
- Worker-computed `lease_remaining_ms`;
- Android converts server remaining time to `SystemClock.elapsedRealtime()` deadline;
- stale/expired local leases fail the BUY gate even if a stale in-memory snapshot still says authorized;
- `/v1/health` verifies the actual fencing columns exist;
- old M12 Worker/D1 infrastructure produces `LEASE_SCHEMA_UPGRADE_REQUIRED`.

PAPER remains unaffected.

### 2. Safe Kraken Dead Man's Switch policy

Kraken `CancelAllOrdersAfter` is account-wide. If its timer fires it cancels all client
orders. This app uses exchange-resting protective SELL stops, so blindly arming DMS can
remove the very stop that protects a filled position.

M14 therefore does NOT auto-arm DMS.

For Kraken LIVE:

1. startup calls `CancelAllOrdersAfter(timeout=0)`;
2. Kraken must confirm DMS is disabled before LIVE starts;
3. the foreground host reasserts `timeout=0` during service cycles;
4. the confirmation expires after 45 seconds;
5. stale/unknown DMS confirmation blocks new BUYs;
6. protective SELL/exit orders are never DMS-gated.

This is intentionally conservative until a future execution architecture can prove that
an account-wide DMS cannot cancel required protective orders.

## GitHub workflow

Commit these M14 bootstrap files to `main`, then run:

`Actions -> M14 Safe DMS & Authority Fencing -> Run workflow -> main`

The workflow performs:

M14 verifier
-> M13
-> M12
-> M11
-> M10
-> M9
-> M8
-> M7
-> M6
-> M5
-> M4
-> M3.2
-> M3
-> canonical verifier
-> Kotlin compile
-> unit tests
-> APK assembly
-> controlled diff
-> `milestone/m14-dms-authority-fencing-<run-number>`

## CloudShare deployment requirement before LIVE

The GitHub Action does NOT mutate the deployed Cloudflare Worker/D1 database.

After M14 is eventually merged and before LIVE is used:

1. Apply `app/src/main/assets/cloudshare_setup/m14_engine_lease_v2_migration.sql`
   ONCE to an existing M12 D1 database.
2. Deploy the updated `cloudshare-worker.js`.
3. The Worker `/v1/health` must report `engine_lease_schema_version: 2`.

For a brand-new CloudShare database, use the updated `schema.sql`; do not also run the
one-time migration because the fresh schema already contains the columns.

Until the remote Worker/D1 is upgraded, LIVE fails closed with
`LEASE_SCHEMA_UPGRADE_REQUIRED`. PAPER continues to work.

## Safety invariant

Unknown authority or DMS state can stop a new BUY.

It must never prevent the bot from attempting a protective SELL.
