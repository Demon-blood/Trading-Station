# Crypto TradeStation v2.7.1 — Remote Command Compile Fix

Fixes:
- Remote positions command now uses the actual PositionInfo fields:
  - entryPrice instead of entryPriceEur
  - managed/reason/messages instead of missing status/warnings fields.
- Removed accidentally inserted Kraken order-book implementation from CoinbaseAdvancedClient and BitvavoClient.
- Kept Kraken Depth order-book implementation only in KrakenSpotClient.
- Bumped version to v2.7.1.
