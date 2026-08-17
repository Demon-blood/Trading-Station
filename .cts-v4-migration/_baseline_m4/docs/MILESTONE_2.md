# Milestone 2 — Collective Learning

## Purpose

Move CloudShare from transport-only synchronization into the Android decision pipeline while maintaining deterministic desktop/Worker compatibility and bounded risk.

## Data flow

1. Android collects recent raw evidence into the deterministic outbox.
2. A durable historical backfill advances through trades, signals, AI decisions and learning snapshots.
3. Android emits compact `shared_trade_daily`, `shared_signal_daily`, `shared_learning_daily`, and `shared_source_inventory` snapshots.
4. The Worker replaces aggregate snapshots by `(contributor, source_table, aggregate_key)` identity.
5. Android downloads `shared_*` events, normalizes them into `cloudshare_collective_index`, and refreshes an immutable in-memory cache.
6. Strategy selection uses a small +/-2 vote hint only for selection; the returned technical candidate score is unchanged.
7. `AiDecisionEngine` applies the bounded collective adjustment once to the final score.

## Default collective guard

- minimum matching outcome samples: 25
- maximum adjustment: +/-6 points
- weight: 1.0
- insufficient samples: neutral 0 adjustment
- downloaded evidence never blocks a local decision by itself

The match tiers are equivalent to desktop v1.0.50:

- symbol + strategy + regime + timeframe
- symbol + strategy + regime
- symbol + strategy
- strategy + regime
- strategy
- symbol + regime
- symbol

## Database

M2 adds `cloudshare_collective_index` and Room migration `7 -> 8`. The cumulative database replacement still contains M1 migration `6 -> 7`, allowing a direct upgrade from Android 3.2.5.

## Backfill

Raw backfill uses monotonically increasing primary IDs rather than OFFSET pagination. Progress is stored in `cloudshare_state`, so service restarts do not restart historical upload from zero.

## Bootstrap

Android can build a secret-free shared-intelligence ZIP containing the collective index, sync audit and source inventory, then upload it to the existing Worker `/v1/bootstrap` R2 path. Exchange/API/admin credentials are not included.

## Upgrade continuity

If M1 already downloaded collective events before the M2 index existed, the first M2 sync re-indexes the existing `cloudshare_intelligence_events` cache before continuing from the saved server cursor.
