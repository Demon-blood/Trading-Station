# v0.8.5 Kraken Balance Diagnostics

This patch explains why Kraken may report only a small amount of free EUR even when the wallet/portfolio screen shows a larger balance.

Kraken can lock EUR in open buy limit orders. The app now logs:

- BalanceEx EUR total, holdTrade, and free amount
- Open order count
- Estimated EUR locked in open EUR buy orders
- Example open orders

Important wording change:

- `reservedByBotThisScan` only means funds the app reserved during the current scan cycle.
- Kraken `holdTrade` / open orders are funds already locked on the exchange from previous orders.

If free EUR is near zero, check Kraken Pro → Orders → Open Orders and cancel stale orders or lower Max Position EUR.
