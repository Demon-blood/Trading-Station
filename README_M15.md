# M15 — Atomic Amend & Self-Calibrating Order Lifecycle

M15 builds on merged M14.

## Why

Before M15, `smartLimitRequote` did not actually requote. Once a LIMIT order became stale,
the Android controller simply called `CancelOrder`. That discarded the working order and
left any new opportunity to a later scan.

Kraken now exposes atomic `AmendOrder`, which modifies a working order in place. Kraken
states that the Kraken/client identifiers stay the same and queue priority is retained
where possible.

## M15 behavior

For automatic LIVE BUY LIMIT entries only:

`HOLD -> ATOMIC AMEND -> ATOMIC AMEND -> ATOMIC AMEND -> HARD CANCEL`

- Price-only amend: automatic M15 never changes quantity.
- `post_only=true`: repricing must remain maker/passive.
- 5-second Kraken deadline protects latency-sensitive amend requests.
- Stable `cl_ord_id` is preferred; txid is fallback.
- Up to 3 automatic amends.
- At 4x the adaptive stale threshold, the signal is treated as stale and the BUY is
  cancelled without submitting a replacement.
- Amend failure never triggers cancel/recreate.
- SELL LIMIT orders are deliberately not blindly repriced because their strategy intent
  may be take-profit/exit/protection.

## Self calibration

M15 persists per-symbol:

- completed-fill samples;
- mean fill duration;
- mean adverse/favorable slippage in bps;
- amendment count;
- cancellation count;
- amendments per completed fill.

After at least 3 completed-fill samples, observed fill time adjusts the stale/requote
threshold, but only inside a strict 0.5x..2x range around the configured
`staleOrderTimeoutSeconds`.

This learning cannot override:

- distributed authority;
- Kraken DMS safety;
- risk limits;
- net-EV economics;
- order-side intent.

## Kraken semantics

`order_qty` on AmendOrder is the new TOTAL quantity, not remaining quantity. M15's
automatic lifecycle therefore does not send `order_qty` at all. This avoids a dangerous
partial-fill sizing mistake.

## Run

Commit the M15 bootstrap package to `main`, then:

Actions -> M15 Atomic Amend & Self-Calibrating Order Lifecycle -> Run workflow -> main

Successful branch:

`milestone/m15-atomic-amend-lifecycle-<run-number>`
