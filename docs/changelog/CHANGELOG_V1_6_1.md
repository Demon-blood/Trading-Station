# v1.6.1 Full Kraken Universe Rotation

This patch fixes the auto-symbol scanner being too narrow and too top-heavy.

## Added

- Full Kraken spot universe discovery with `autoSymbolQuoteAsset = ALL`.
- Multi-quote scanning instead of EUR-only scanning.
- Larger default candidate pool: 250 pairs.
- Larger default active rotation: 20 symbols.
- Better Live Status text showing how many symbols are selected.
- Quote-aware BUY/SELL budget checks.
- Balance-aware handling for EUR, USD, USDT, USDC and non-fiat quote pairs.
- Scanner UI text now explains that the scanner covers all Kraken spot markets.

## Trading behavior

The app scans and analyzes all Kraken spot pairs, but live BUY execution is still quote-balance aware:

- EUR pairs use free EUR.
- USD/USDT/USDC pairs use the matching free quote balance.
- BTC/ETH/other quote pairs are analyzed, but live BUY is blocked by default unless non-EUR quote buys are enabled.
- SELL remains available when you hold the base asset and the value is above minimum order size.

This makes the scanner broad without letting the bot accidentally spend BTC/ETH quote balances unless explicitly enabled.
