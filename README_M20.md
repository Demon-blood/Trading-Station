# Crypto TradeStation — M20 Net-Profit & Cost Optimizer

M20 sits after M5 and never replaces it.

## Core invariant

M20 is monotonic and reduction-only:

- it cannot authorize an entry M5 rejected;
- it cannot increase order notional;
- it cannot loosen M12–M19 authority, risk, DMS, truth, lifecycle, portfolio or learning gates.

## M20 additions

- observed P75 adverse slippage above M5's modeled slippage;
- UTC time-bucket degradation only when enough same-hour samples exist;
- M15 measured completed-fill vs hard-cancel reliability;
- measured operational failure opportunity loss;
- explicit attributable infrastructure cost only — zero by default;
- scarce daily trade-slot opportunity cost from recent positive M5 candidate evidence;
- empirical capital-cycle duration and adjusted edge per capital-hour diagnostics;
- a break-even gross-return hurdle that includes M5 cost plus only incremental M20 cost.

M20 does not double-count M5 fees, spread, modeled slippage, cloud-AI cost or safety reserve.

## Kraken fee baseline verified 2026-09-05

Official Belgian/general Kraken Spot Crypto Tier 1 currently shows:

- maker 0.40%
- taker 0.80%

Live account/pair fee data still takes precedence. Selected Spot Maker Rebate pairs use a separate schedule, so M20 does not hard-code rebate assumptions.

## Run

Copy this package into repository `main`, preserving paths, commit it, then run:

Actions → M20 Net-Profit & Cost Optimizer → Run workflow → main

The workflow verifies M20 + M19→M3 + canonical, compiles Kotlin, runs unit tests, assembles the APK, creates `milestone/m20-net-profit-optimizer-<run-number>`, and attempts the PR.
