# Crypto TradeStation v2.7.5 — Compile Fix

Fixes:
- Remote positions command now uses PositionInfo fields:
  - entryPrice
  - unrealizedPnlEur
  - managed
  - messages
- Removed accidental duplicate Kraken order-book implementation blocks from non-Kraken exchange classes.
- Keeps the Kraken Depth/order-book implementation only in KrakenSpotClient.

Build target:
- Fixes compile errors in BotController.kt and ExchangeClientsV08.kt from v2.7.4.
