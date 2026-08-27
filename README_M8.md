# Crypto TradeStation M8 — Statistical AI Adaptive Governance

## Purpose

M7 measures whether Luna/Sol add value after API cost. M8 is the first narrowly
bounded adaptation layer that is allowed to act on that evidence.

It is **defensive-only**. M8 can remove paid AI capability, but it cannot add or
expand it.

## Allowed actions

M8 has only three decisions:

- `HOLD`
- `DISABLE_SOL`
- `DISABLE_CLOUD_AI`

There is intentionally no `ENABLE_*` action.

M8 never:

- enables/re-enables cloud AI;
- enables/re-enables Sol;
- raises the monthly API budget;
- raises the Sol daily-call cap;
- increases position size;
- changes strategy scores;
- weakens M5 positive-net-EV requirements;
- weakens M4 Kraken reconciliation/duplicate-order protection;
- weakens M6 veto/reduce-only authority;
- weakens portfolio/risk controls.

A user can still manually change cloud-AI settings later. M8 simply does not do
that in the profitable/risk-increasing direction by itself.

## Evidence quality

Only M7 rows with durable historical resolution are eligible.

Rows resolved as:

`HORIZON_FALLBACK_CURRENT_TICKER`

are excluded from automatic shutdown evidence because they lack a complete
historical M1 path.

The normalized per-review value is clipped to +/-25% before confidence testing so
one broken/extreme counterfactual cannot dominate the variance estimate. Absolute
value-added is **not** clipped for the economic total.

## Overall cloud-AI shutdown gate

All of these must be true:

1. at least **50** eligible paid-Luna counterfactuals;
2. evidence spans at least **7 days**;
3. cumulative M7 `AI_VALUE_ADDED <= -0.25` quote currency;
4. mean normalized value <= **-5 bps per intervention**;
5. the **upper 95% confidence bound is below zero**.

The last condition means even the optimistic end of the interval says paid AI is
hurting the baseline after AI cost.

If all pass:

`DISABLE_CLOUD_AI`

which also disables Sol.

## Sol-only shutdown gate

All of these must be true:

1. Sol is currently enabled;
2. at least **30** eligible Sol counterfactuals;
3. evidence spans at least **7 days**;
4. cumulative M7 `SOL_INCREMENTAL_VALUE <= -0.10` quote currency;
5. mean normalized Sol incremental value <= **-5 bps**;
6. Sol's upper 95% confidence bound is below zero.

If all pass while overall cloud AI is not proven harmful:

`DISABLE_SOL`

Luna remains enabled.

## Confidence calculation

M8 uses normalized M7 value-added per deterministic baseline notional. It computes:

- mean;
- sample standard deviation;
- standard error;
- conservative Student-t-like 95% interval.

The critical value is intentionally slightly wider than z=1.96 in the 30-60
sample range.

This is a practical sequential governance guard, not a claim that market outcomes
are IID Gaussian observations. The sample-count, seven-day-span, absolute-loss,
effect-size, outlier-clipping and upper-bound requirements are deliberately
stacked to make automatic shutdown harder, not easier.

## Runtime

M8 evaluates only when M7 resolves one or more new counterfactuals.

A 24-hour mutation cooldown prevents rapid successive automatic configuration
changes.

The System Test path calls `inspect()` only. It cannot mutate cloud settings and
makes no paid AI call.

## Install

Copy this ZIP into the repository root preserving paths, commit the bootstrap
files to `main`, then run:

**Actions → M8 Statistical AI Adaptive Governance → Run workflow**

The Action verifies M8 → M7 → M6 → M5 → M4 → M3.2 → M3 → canonical contracts,
then compiles Kotlin, runs unit tests, assembles the APK and pushes:

`milestone/m8-ai-adaptive-governance-<run>`
