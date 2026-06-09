# Crypto TradeStation v1.8.7 — Kraken OHLC Backtest

Upgrades:
- Backtest Lab buttons now use real Kraken public OHLC candle data.
- No API key is required for historical OHLC tests.
- Run Kraken Backtest uses the first configured symbol on M15 candles.
- Forward Test uses the second configured symbol, or falls back to the first, on M5 candles.
- Shows loading status while candles are fetched.
- Routes test execution through BotController instead of fake local candle generation.

Notes:
- The old generated candle fallback remains in source as a helper, but the Backtest tab no longer uses it.
- This is still not a profit guarantee; it is a validation layer for strategy gates.
