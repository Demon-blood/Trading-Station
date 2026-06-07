# v1.6.7 Paper Live Kraken Data

PAPER mode now delegates public market data to Kraken public endpoints when the app has Android Context. No API key is required. Orders and balances remain local/fake.

Public data used:
- AssetPairs
- Ticker
- OHLC candles

Simulated data used:
- paper wallet
- paper buy/sell fills
- paper portfolio
