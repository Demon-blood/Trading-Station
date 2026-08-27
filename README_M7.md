# Crypto TradeStation M7 — AI Value Attribution & Shadow Counterfactuals

## Purpose

M6 can spend money on Luna/Sol and can veto or reduce an otherwise deterministic BUY.
M7 measures whether that intervention was economically useful.

Every successful paid cloud review creates a durable shadow record comparing:

1. Deterministic-only path
2. Deterministic + Luna path
3. Deterministic + Luna + Sol path

The record survives process death and app upgrades through Room database version 12
and an explicit non-destructive 11 → 12 migration.

## Metrics

M7 tracks:

- `AI_COST`
- `AI_GENERATED_PROFIT`
- `AI_AVOIDED_LOSS`
- `AI_MISSED_PROFIT`
- `AI_VALUE_ADDED`
- `AI_ROI`
- Luna incremental value / ROI
- Sol incremental value / ROI
- open vs resolved counterfactual count

Because M6 is veto/reduce-only, most positive AI contribution should appear as
**avoided loss**, not invented additional exposure/profit.

## Counterfactual method

Each paid cloud review opens a 4-hour shadow experiment.

Baseline:
- the deterministic BUY that existed immediately before cloud review;
- BUY uses the configured maximum position;
- SMALL_BUY uses 50% of that maximum until M5 can link the exact post-governance
  deterministic pre-cloud notional.

If the candidate reaches M5/advanced execution, M7 replaces its fallback trade-cost
estimate with the actual M5 modeled trading friction and exact deterministic
pre-cloud notional.

Resolution:
- M1 candles are fetched for the 4-hour outcome window;
- target hit → target exit;
- stop hit → stop exit;
- neither → 4-hour M1 close;
- target and stop in the same M1 candle → **stop first** (conservative);
- if historical M1 coverage is unavailable after a long offline period, current
  ticker is used and the row explicitly records `HORIZON_FALLBACK_CURRENT_TICKER`.

The shadow P/L is a decision-point counterfactual. It is not presented as a real
executed Kraken trade.

## Path economics

For the same observed return:

`deterministic_net = full deterministic exposure × net return`

`luna_net = Luna-adjusted exposure × net return - Luna API cost`

`luna_sol_net = final adjusted exposure × net return - Luna cost - Sol cost`

Then:

`Luna value = luna_net - deterministic_net`

`Sol incremental value = luna_sol_net - luna_net`

`AI value added = luna_sol_net - deterministic_net`

## Data-driven verdicts

M7 does not auto-disable AI yet. It produces recommendations:

- fewer than 20 resolved rows → `INSUFFICIENT_DATA`
- non-positive total AI value after 20+ rows → `DISABLE_CLOUD_AI_RECOMMENDED`
- positive overall AI but non-positive Sol incremental value after 10+ Sol rows
  → `KEEP_LUNA_DISABLE_SOL_RECOMMENDED`
- otherwise → `KEEP_SELECTIVE_AI`

A later adaptation milestone can consume these recommendations only after stronger
statistical gates.

## Install

Copy this ZIP into the repository root preserving paths and commit the bootstrap
files to `main`.

Run:

**Actions → M7 AI Value Attribution and Shadow Counterfactuals → Run workflow**

The workflow performs M7 → M6 → M5 → M4 → M3.2 → M3 → canonical verification,
Kotlin compilation, unit tests, APK assembly, then pushes:

`milestone/m7-ai-value-attribution-<run>`
