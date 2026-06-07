# v0.8.1 Kraken Live Trading Patch

This patch changes Kraken from a placeholder connector into a real Spot REST connector for:

- public ticker data
- public OHLC candle data
- private signed `AddOrder` limit orders

Live trading still requires:

- Exchange Provider = KRAKEN
- Bot Mode = LIVE_AUTO
- Live trading acknowledgement enabled
- Manual Execution Mode disabled
- Kraken API key + Private Key saved
- Kraken key permissions: query funds, query orders/trades, create/modify orders, cancel/close orders
- Withdrawals disabled

The app sends limit orders only. Market orders remain disabled by design.
