# Crypto TradeStation v1.9.2 — Compile Fix

Fixes:
- Aliased drawscope Stroke import to DrawStroke to avoid collision with the app's Stroke color.
- Fixed BorderStroke/Divider/trackColor references that were resolving Stroke as the drawscope class.
- Replaced invalid SignalAction.HOLD fallback with SignalAction.WAIT.
- Added local actionColor helper for chart/AI status badges.
- Removed misplaced trade-marker code from the old PriceLineChart helper.
- Re-added compile-safe BUY/SELL markers to CandlestickChart only.
- Fixed LinearProgressIndicator calls for this Compose version.
- Version label updated to v1.9.2 CTS.
