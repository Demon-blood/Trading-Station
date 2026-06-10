# Crypto TradeStation v2.1.0 — LIVE_AUTO Preflight Gate

Implemented:
- Automatic LIVE_AUTO preflight verification before the background auto bot begins trading.
- The foreground service starts its notification first, then runs System Feature Verification.
- If any critical FAIL checks are found, LIVE_AUTO startup is blocked.
- The bot writes the block reason into Live Status and stops the foreground service.
- PAPER mode and LIVE_CONFIRM mode are not blocked by this LIVE_AUTO preflight gate.

Why:
- Makes the app more automatic and safer.
- Prevents unattended live auto trading when Kraken keys, release safety, chart data, credentials, or other critical checks fail.
- Keeps the app clean by reusing the existing System Test / Feature Verification logic instead of adding another tab.
