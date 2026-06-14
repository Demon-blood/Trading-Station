# Crypto TradeStation v3.2.5 — Expanded News Provider Stack

Implemented requested providers:
1. GDELT
2. Marketaux
3. RSS Feed Aggregator
4. NewsData.io
5. GNews
6. Guardian
7. NewsAPI.org

Changed:
- Added MarketauxNewsClient.
- Added RssFeedNewsClient using Google News RSS search.
- Added NewsDataNewsClient.
- Added GNewsNewsClient.
- Added GuardianNewsClient.
- Kept GDELT and NewsAPI.org support.
- Added secure API-key storage for Marketaux, NewsData.io, GNews and Guardian.
- Added settings fields for Marketaux, NewsData.io, GNews and Guardian.
- News scans now log each provider separately.
- Secure backup/restore includes:
  - marketaux_api_key
  - newsdata_api_key
  - gnews_api_key
  - guardian_api_key

Provider behavior:
- GDELT and RSS do not require keys.
- Marketaux, NewsData.io, GNews, Guardian and NewsAPI.org require their own API keys.
