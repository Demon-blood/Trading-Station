# Crypto TradeStation v3.2.2 — NewsAPI Call Diagnostics

Fixes:
- News Dashboard Scan News now forces useNewsAi=true for that scan.
- NewsAPI calls are no longer hidden behind CompositeNewsClient silent failure handling.
- The controller now calls every NewsAPI key provider explicitly and logs each provider result.
- Live Status now shows:
  - [SYMBOL] NewsAPI-1 API call complete: articles=N
  - [SYMBOL] NewsAPI-1 API call failed: HTTP/status error
  - [SYMBOL] CryptoCompare API call complete: articles=N
- NewsApiClient now reports HTTP/status failures instead of silently returning empty results.
- NewsAPI source labels remain NewsAPI-1, NewsAPI-2, etc.

Why:
- Previously CompositeNewsClient swallowed provider failures with runCatching(...).getOrDefault(emptyList()), so bad/limited/blocked NewsAPI keys looked like “no API call happened”.
