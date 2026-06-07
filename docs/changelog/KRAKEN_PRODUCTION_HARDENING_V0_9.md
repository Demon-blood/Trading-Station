# v0.9.0 Kraken Production Hardening

This build focuses on making Kraken live trading more automatic and observable.

## Added

- Kraken AssetPairs auto-discovery.
- Kraken symbol validator for configured symbols.
- Automatic pair mapping instead of relying only on hardcoded BTCEUR/ETHEUR mappings.
- Minimum order-size validation from Kraken pair metadata.
- Price decimal and quantity decimal rounding based on Kraken pair metadata.
- Live open-order sync.
- Open Orders tab now reads real exchange open orders.
- Manual cancel from the Open Orders tab.
- Foreground service syncs open orders at the beginning of every live scan.
- Stale limit order cancellation when Smart Requote is enabled.
- Better Live Status logs for pair validation and order management.
- Version label updated to v0.9.0.

## Automatic behavior

When LIVE_AUTO is enabled and Kraken is selected, the bot now does:

1. Sync existing open orders.
2. Cancel stale limit orders if smart requote is enabled.
3. Validate configured symbols against Kraken AssetPairs.
4. Skip invalid/untradable pairs automatically.
5. Fetch ticker and candle data for valid symbols.
6. Run strategy + AI + news + memory decisions.
7. Check risk guards.
8. Check free EUR for BUY or free base asset for SELL.
9. Submit LIMIT or MARKET orders depending on settings.
10. Log every step in Live Status.

## Still required

The user still needs a Kraken API key with these permissions:

- Query funds
- Query open orders/trades
- Create and modify orders
- Cancel/close orders
- Withdrawals OFF

This app still cannot guarantee profit and should be tested with small position sizes first.
