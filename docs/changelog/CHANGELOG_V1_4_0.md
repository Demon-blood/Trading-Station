# v1.4.0 Auto Symbol Discovery + Auto Rotation

This release adds live Kraken market-universe discovery and automatic symbol rotation.

## Added

- Kraken AssetPairs discovery as a first-class exchange capability.
- Auto Symbol Scanner tab.
- Automatic EUR pair discovery, validation, scoring and ranking.
- Automatic scan-cycle symbol universe selection when Auto Symbol Discovery is enabled.
- Spread, volume, 24h-change and tradability filters.
- "Use Top Symbols" action to replace the manual symbol list with the best scanner output.
- Live Status messages explaining why symbols were enabled or skipped.

## Live scope

The app can still only send real live orders through the Kraken connector. Coinbase and Bitvavo remain hidden/manual until their signing and account-capability flows are implemented and tested.
