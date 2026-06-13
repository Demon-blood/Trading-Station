# Crypto TradeStation v2.8.3 — Order Alert Fill Details

Fixes:
- Telegram/Discord order alerts no longer rely directly on Kraken AddOrder's zero fill fields.
- Kraken live order placement now calls QueryOrders after AddOrder to retrieve:
  - vol_exec
  - average price / cost
  - fee
- Trade journal records now use safe non-zero fallbacks when the exchange has not reported the fill yet.
- Order alerts now include:
  - amount
  - price
  - notional
  - fee
  - order type
  - order id

Why:
- Kraken AddOrder returns txid/description and usually does not include actual fill quantity, average price or fee.
- QueryOrders must be called after AddOrder to retrieve fill details.
- If an order is still open/unfilled, the app shows submitted quantity/price estimate and includes a note.
