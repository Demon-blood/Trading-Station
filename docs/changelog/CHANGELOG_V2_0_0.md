# Crypto TradeStation v2.0.0 — Live Complete Pack

Added:
- Real Telegram setup UI: bot token, chat ID, save, test alert.
- Real Discord setup UI: webhook URL, save, test alert.
- Remote alert client using Telegram Bot API and Discord webhook API.
- Live alert wiring for order placed, order submit failed, and LIVE_AUTO safety lock blocks.
- LIVE_AUTO release safety enforcement in BotController.
- Live chart auto-refresh every 30 seconds.
- Chart zoom controls.
- Chart pan controls.
- Full candlestick bodies/wicks with volume bars.
- Actual BUY/SELL markers from local trade journal database.
- Version label updated to v2.0.0 CTS.

Important:
- Live trading requires valid Kraken API keys and withdrawal permission must remain disabled.
- Telegram/Discord require valid user-provided credentials.
- The app can only send live orders when live safety checks pass.
