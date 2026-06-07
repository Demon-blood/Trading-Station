# v0.8.6 Kraken diagnostics clarity

This build makes the installed version clearly visible as v0.8.6 and expands Kraken balance diagnostics.

It logs:

- BalanceEx available/free calculation
- raw Balance endpoint asset totals
- TradeBalance EUR-equivalent values
- OpenOrders count and estimated EUR locked

If Kraken reports free EUR near zero while the app/website shows portfolio value, the funds are not being reported as spot-tradeable EUR through the API. Common causes are non-EUR holdings, Earn/staking/read-only balances, funding/portfolio separation, or funds still settling.
