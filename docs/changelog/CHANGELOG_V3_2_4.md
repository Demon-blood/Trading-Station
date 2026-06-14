# Crypto TradeStation v3.2.4 — Free News Stack

Implemented requested provider stack:
1. GDELT (free, no key)
2. CoinGecko (free, no key)
3. CryptoPanic (free tier, API key)
4. NewsAPI (free tier, one or more keys)

Changed:
- Added GdeltNewsClient.
- Added CoinGeckoNewsClient.
- Added CryptoPanicNewsClient.
- Replaced CryptoCompare in the active news stack with the requested free provider stack.
- Added CryptoPanic API key secure storage.
- Added CryptoPanic key field in Settings.
- Secure backup/restore now includes cryptopanic_api_key.
- Live Status logs each provider separately:
  - GDELT API call complete/failed
  - CoinGecko API call complete/failed
  - CryptoPanic API call complete/failed or skipped when no key is saved
  - NewsAPI-1/2/etc call complete/failed
