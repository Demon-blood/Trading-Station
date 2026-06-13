# Crypto TradeStation v2.7.9 — AI Signals Real Decisions

Fixes:
- AI Signals no longer behaves like a BTC/ETH placeholder screen.
- Added Scan AI Signals button.
- AI Signals scan uses configured symbols plus active position symbols.
- AI Signals loads lifecycle/portfolio/trade journal context while open.
- Active-position decisions are sorted first and marked ACTIVE POSITION.
- Empty state now explains that no real scan has been loaded yet.

Why:
- The Dashboard placeholder issue was fixed in v2.7.8, but AI Signals still depended on the shared decision list without its own active-symbol scan workflow.
