# Crypto TradeStation M3.2 — Kraken WebSocket-first market data

Copy this ZIP into the repository root, preserving paths, and commit it to `main`.

Then run:

**Actions → M3.2 Kraken WebSocket-First Market Data → Run workflow**

The workflow patches only:
- new `exchange/KrakenWebSocketV2MarketData.kt`
- `exchange/ExchangeClientsV08.kt`
- `core/BotController.kt`
- `service/BotForegroundService.kt`

It then reruns the M3 host verifier, canonical v4.0.7 verifier, Kotlin compilation,
unit tests and APK assembly before pushing a milestone branch.

Runtime design:
- public Kraken WebSocket v2
- BBO-triggered ticker streaming
- OHLC subscriptions for requested strategy intervals
- bounded active symbol set only
- application ping + protocol ping
- silent-socket watchdog
- exponential reconnect with jitter
- subscription replay on reconnect
- market cache reset after gaps
- REST ticker fallback
- REST OHLC historical/backfill with latest WebSocket candle overlay
- existing private order/balance/lifecycle REST reconciliation remains unchanged
