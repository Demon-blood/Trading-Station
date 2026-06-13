# Crypto TradeStation v2.7.8 — Dashboard Active Decisions

Fixes:
- Dashboard no longer starts with hardcoded BTCEUR/ETHEUR sample AI decisions.
- Dashboard no longer falls back to sample decisions when a scan returns empty.
- Dashboard loads lifecycle/portfolio/trade journal symbols while open.
- Scan Market and Execute Once now scan configured symbols plus active position symbols.
- Top AI Decisions are sorted with active-position symbols first.
- Empty dashboard state now explains that no real scan results have been loaded yet.

Why:
- Previous dashboard state used sampleDecisions() as placeholder UI data.
- That made it look like BTC/ETH were the only AI decisions even when the app had other active positions.
