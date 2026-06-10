# Crypto TradeStation v2.3.0 — Ultimate Live Guards

Implemented:
- Volatility circuit breaker for automatic BUY entries.
- Pump-chase protection for automatic BUY entries.
- Adaptive compounding from realized P/L with hard cap.
- Dynamic scan interval support in the background bot service.
- Advanced Settings UI for the new Live Guard Automation controls.
- Settings persistence for all new controls.
- System Test / Feature Verification reports the new live guards.
- Full backup export includes the new live guard settings.

Live behavior:
- BUY can be blocked when 24h move is too extreme.
- BUY can be blocked when the market has already pumped beyond your configured threshold.
- SELL remains available so the bot can still exit positions.
- Adaptive compounding can raise position cap only from realized positive P/L and never above the hard cap.
- Background service can scan faster after tradable signals while keeping normal scan interval otherwise.

Safety:
- No profit is guaranteed.
- All live order execution still goes through the existing Kraken credentials, balance, release safety, system preflight, quote reserve, max position, spread, and cooldown checks.
