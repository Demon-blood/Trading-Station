# Crypto TradeStation v2.0.4 — Background Auto Start Button

Added:
- Explicit Start Background Auto Bot button on Dashboard.
- Explicit Start Background Auto Bot button in Bot Control.
- New BotForegroundService action ACTION_START_BACKGROUND_AUTO.
- Service status now clearly says background auto bot is running/scanning.
- Dashboard now separates:
  - Start Background Auto Bot
  - Stop Bot
  - Scan Once
  - Execute Once

Note:
- Android requires long-running background trading to run as a foreground service with a persistent notification.
- The new button starts that persistent service and the service keeps scanning/executing automatically using the saved mode/settings.
