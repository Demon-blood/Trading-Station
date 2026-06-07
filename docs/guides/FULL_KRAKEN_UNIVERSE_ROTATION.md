# Full Kraken Universe Rotation — v1.6.1

This build changes auto-symbol discovery from EUR-only to the full Kraken spot universe.

## What changed

- `autoSymbolQuoteAsset` defaults to `ALL`.
- Kraken `AssetPairs` are loaded for every quote asset, not only EUR.
- The scanner scores EUR, USD, USDT, USDC, BTC, ETH and other quote markets.
- The active rotation can contain multiple symbols, up to `autoSymbolActiveLimit`.
- The bot logs the full selected rotation in Live Status.

## Live trading behavior

The scanner can analyze every Kraken spot pair, but live BUY execution remains balance-aware:

- EUR pairs buy with free EUR.
- USD/USDT/USDC pairs buy with the matching free quote balance.
- BTC/ETH/other quote pairs are scanned and can be sold when base assets are held.
- Live BUY for non-fiat/non-stable quote pairs is disabled by default unless `nonEurQuoteBuyEnabled` is enabled.

This prevents the bot from accidentally spending BTC/ETH as quote currency just because a pair scores well.
