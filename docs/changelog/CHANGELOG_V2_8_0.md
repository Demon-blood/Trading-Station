# Crypto TradeStation v2.8.0 — Performance Lab Active Symbols

Fixes:
- Performance Lab no longer focuses only on the configured/default BTCEUR and ETHEUR symbols.
- Performance Lab now builds its promotion universe from configured symbols plus active position symbols.
- Performance tab loads lifecycle/portfolio/trade journal context while open.
- Refresh Performance Lab uses the expanded active-symbol universe.
- Active-position candidates are sorted first and marked ACTIVE POSITION.
- AI Center refresh performance also uses active symbols.

Why:
- PerformanceLabEngine builds candidates from settings.symbolsCsv.
- Older UI calls passed unmodified settings, so only configured symbols were promoted/tested.
