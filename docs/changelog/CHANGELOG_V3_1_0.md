# Crypto TradeStation v3.1.0 — News Dashboard, Cache and Multi-Provider News

Implemented:
- Full News Intelligence dashboard with cached articles per symbol.
- Article titles are shown under expanded AI decisions when news was found.
- New Room table: news_articles.
- News history cache can be filtered by symbol.
- Market scans cache fetched news articles automatically.
- News screen can run a symbol news scan and refresh the cache.
- Multi-provider news:
  - NewsAPI when key is saved.
  - CryptoCompare public crypto news fallback/provider.
  - CompositeNewsClient merges and de-duplicates articles.

Database:
- Added NewsArticleEntity.
- Room database version bumped from 5 to 6.
- Existing fallbackToDestructiveMigration remains active in this project.
