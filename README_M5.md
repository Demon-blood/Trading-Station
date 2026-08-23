# Crypto TradeStation M5 — Net Expected-Value Trade Economics

## Goal

Make one engine answer the economic question for every new entry:

`EV = P(win) × expected win - P(loss) × expected loss - every modeled cost`

The trade is blocked unless **net expected value after costs is positive**.

## Cost model

M5 includes:

- realized-outcome P(win), Bayesian-shrunk toward a neutral 50% prior;
- explicit target gain;
- explicit protective-stop loss;
- maker/taker entry fee;
- conservative taker exit fee;
- current spread reserve;
- order-book depth slippage;
- model-risk safety reserve;
- external AI/decision cost hook for M6;
- net EV in quote currency;
- net EV as a percentage of notional;
- break-even win probability;
- reward/risk ratio.

AI score/confidence is **not** treated as a calibrated probability.

## Kraken fees

The live Kraken `TradeVolume` account/pair fee schedule is preferred whenever the
connector can retrieve it. If unavailable, M5 uses conservative current Tier-1
Spot Crypto fallbacks:

- maker: 0.40% (`0.0040`)
- taker: 0.80% (`0.0080`)

Observed realized live fees can make the fallback more conservative, never less.

## M4 correction included

M4 correctly added private execution-state and duplicate-entry gates, but the
integration originally checked `purpose == "ENTRY"`. Research/handoff BUY orders
carry a strategy-purpose string instead, so they could bypass those two checks.

M5 corrects this: in Kraken spot, **every BUY is an entry**. Research/handoff
purpose metadata no longer bypasses:

- private execution-state truth;
- ambiguous-submission quarantine;
- duplicate open-BUY protection.

SELL/protective exits remain unaffected.

## Install

Copy this ZIP into the repository root preserving paths and commit the bootstrap
files to `main`.

Then run:

**Actions → M5 Net Expected-Value Trade Economics → Run workflow**

The workflow will verify M5, M4, M3.2, M3 and canonical v4.0.7, then compile,
run all unit tests, build an APK and push:

`milestone/m5-trade-economics-<run>`
