# M17 — Portfolio Correlation & Capital Allocation

M17 builds on merged M16.

## What changes

The existing portfolio allocator mostly used:
- duplicate-base blocking;
- confidence;
- score;
- open-position count;
- symbol trade history.

M17 makes the allocator account-aware and correlation-aware.

## Account state

For each candidate BUY the execution coordinator passes the active exchange into M17.

M17 reads fresh portfolio balances and derives:
- EUR-valued account equity using `BalanceInfo.eurValue`;
- free EUR cash;
- required reserve using the stricter of:
  - `minimumQuoteReserveAmount`;
  - `minimumQuoteReservePercent`;
  - `minimumEurReservePercent`;
- currently investable EUR cash after reserve.

If LIVE cannot obtain authoritative portfolio context while the portfolio balancer is
enabled, the new entry fails closed. PAPER may continue with the older downstream gates.

## Position exposure

For open positions M17 prefers the exchange's current EUR-valued asset balance. If that
value is unavailable it falls back to durable position quantity × entry price.

That makes concentration checks conservative when the account contains extra holdings.

## Correlation

M17 requests H1 candles for the candidate and current open-position symbols.

Returns are:
- chronological;
- timestamp aligned;
- calculated close-to-close;
- correlated with Pearson correlation.

A coefficient is only accepted with at least 30 paired returns.

If fewer than 30 paired returns exist, correlation remains `unknown`; M17 never turns
missing data into 0.0 correlation.

Positive-correlation sizing:
- >= 0.90 -> 0.40x
- >= 0.80 -> 0.60x
- >= 0.70 -> 0.80x
- otherwise -> 1.00x

Negative correlation does not increase capital.

## Common-factor fallback

Assets are placed into broad risk buckets:
- BTC_CORE;
- ETH_CORE;
- ALT_RISK;
- CASH_STABLE.

These buckets are NOT presented as empirical correlation.

When empirical correlation is unavailable but the portfolio already carries the same
risk bucket, M17 uses a conservative 0.80x fallback rather than claiming diversification.

A broad common-factor bucket cannot exceed 70% of authoritative account equity.

## Single-asset concentration

M17 also respects the stricter of:
- `maxSingleAssetAllocationPercent`;
- `maxCoinExposurePercent`.

The remaining account-equity capacity becomes a hard cap on the candidate allocation.

## Capital cannot be created

All M17 sizing begins from the amount already requested by the controller.

M17 may:
- reduce it;
- cap it;
- block it.

M17 may never increase it.

M5 economics, M14 authority/DMS, M16 microstructure, exchange minimums, and all existing
risk gates remain downstream/final authority.

## Run

Commit the bootstrap files to `main`, then run:

Actions -> M17 Portfolio Correlation & Capital Allocation -> Run workflow -> main

Expected branch:

`milestone/m17-portfolio-correlation-<run-number>`
