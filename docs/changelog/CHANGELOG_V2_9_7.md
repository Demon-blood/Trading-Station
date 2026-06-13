# Crypto TradeStation v2.9.7 — Symbol News Scoring Upgrade

Fixes:
- News score no longer silently stays at 0 without explanation.
- Live Status now reports whether a NewsAPI key is configured.
- Every scanned symbol logs a News check complete line with article count.
- NewsAPI queries now use stronger symbol aliases, title/description search, 7-day window and up to 25 articles.
- Smaller/non-major symbols get a looser fallback query.
- Neutral but present news now gives a tiny non-zero score so the app shows the news layer was actually checked.
- AI explanation now includes newsArticles count.

Important:
- A saved NewsAPI key is required. Without it, the app uses NoopNewsClient and news score remains 0 by design.
