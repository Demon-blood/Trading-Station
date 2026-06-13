# Crypto TradeStation v2.9.6 — Auto-Tuner Crash Guard

Fixes:
- Auto Strategy Tuner no longer launches all backtests at once.
- Auto Strategy Tuner now runs tests sequentially.
- Auto Strategy Tuner caps the symbol universe to 8 symbols per run.
- Backtest candle count for auto-tune reduced from 720 to 360 per test.
- Result rendering is capped to 50 rows to avoid oversized Compose lists.

Why:
- Running every strategy across a large symbol universe in parallel can overload the phone with network calls, OHLC parsing, Room/UI state updates and Compose recompositions.
- Sequential safe mode keeps the app responsive and prevents the full app crash.
