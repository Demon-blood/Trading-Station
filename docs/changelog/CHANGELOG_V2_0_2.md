# Crypto TradeStation v2.0.2 — System Test + Feature Verification

Added:
- System Test + Feature Verification inside Settings.
- System Test detail screen.
- Settings hub card with PASS/FAIL/WARN/NOT_CONFIGURED summary.
- BotController.runSystemFeatureVerification(...).

Verification checks:
- Settings store loads.
- Secure key store is reachable.
- Kraken AssetPairs validation.
- Kraken public ticker.
- Kraken OHLC/chart data.
- Trade journal database.
- Kraken Health Monitor.
- Telegram alert test when configured/enabled.
- Discord alert test when configured/enabled.
- Release Safety Lock.
- Kraken live credential presence when relevant.
- Live order path wiring.
- Chart auto-refresh wiring.
- Grouped navigation routing.

Safety:
- The system test does not place a real live order.
- It verifies the live order path is wired, but real order testing still requires deliberate user action.
