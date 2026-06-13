# Crypto TradeStation v2.9.8 — Max Trades Per Day 10000 Fix

Fixes:
- Advanced Settings no longer clamps Max trades per day to 500.
- The field now allows values from 1 to 10000.
- Added UI hint: "Max trades per day, max 10000".

Why:
- The app was saving 10000 through the text field, but the Advanced Settings apply logic used:
  maxTradesDay.toIntOrNull()?.coerceIn(1, 500)
- Therefore every value above 500 was forced back to 500.
