# KSP Crypto AI — v1.6.0 Live Completion Audit

This is a truth-audited Kraken live build. It focuses on live Kraken trading, automatic symbol discovery, live portfolio/orders, diagnostics, and paper/manual modes. See `docs/live/FEATURE_COMPLETION_AUDIT.md` before assuming every advanced module is production-live.

This release is intentionally focused on **working live functionality** instead of exposing unfinished placeholder systems.

## Working modes

- **PAPER** — local simulated trading. No exchange order is sent.
- **KRAKEN** — live Kraken spot trading through authenticated REST API calls.
- **MANUAL** — signal/trade-plan mode where you place orders yourself.
- **BINANCE_READ_ONLY** — market-data/signal mode only for Belgium restrictions.

Coinbase Advanced and Bitvavo code slots are kept in source for future development, but they are hidden from the main provider picker and are not presented as live-working connectors.

## Working live Kraken features

- Secure local API-key storage through Android Keystore-backed encryption.
- Kraken ticker and OHLC candles.
- Kraken AssetPairs auto-discovery.
- Symbol validation, minimum order size, price precision and quantity precision handling.
- Live balance and portfolio reading.
- Live open-order reading.
- Manual cancel of open Kraken orders.
- Limit orders.
- Optional market orders with spread/slippage guard.
- Automatic BUY and SELL decisions when enabled.
- Free EUR check before buys.
- Free base-asset check before sells.
- Live status timeline explaining every block/attempt/failure.
- Local trade/decision history.
- Lifecycle/position snapshots from Kraken balances and order history.

## Important limits

No trading bot can guarantee profit or sell at the maximum possible top. This app uses profit-lock, take-profit, stop-loss, trailing-exit, bearish-signal and risk guards, but market risk remains.

Only use API keys with withdrawals disabled. Start with small sizes and inspect the Live Status tab before leaving the bot running.

## v1.6.0 completion highlights

- Kraken live trading remains the primary path.
- Bitvavo REST live connector added.
- Coinbase Advanced JWT connector added.
- Kraken WebSocket ticker feed added.
- Strategy optimizer, shadow comparison, remote notifier, watchdog, and Belgian tax CSV exporter added.
- Impossible/refused items remain documented: no guaranteed profits, no regulatory bypass, no withdrawal automation.
