# Crypto TradeStation v2.8.4 — History, Notification Cleanup and Strategy Library

Fixes:
- Notification logs now group identical repeated messages instead of showing the exact same line multiple times.
- History screen now shows actual local history:
  - trade journal rows
  - buy/sell counts
  - realized P/L
  - deduplicated bot event history
- History has a Refresh button and no longer shows only static Trade Memory Brain text.

Added:
- Strategy Lab now lists currently implemented strategies and strong future strategy candidates.

Implemented/selectable strategies:
- SCALPING
- TREND
- BREAKOUT
- REVERSAL
- NEWS_MOMENTUM
- AUTO/adaptive selection

Future candidates listed:
- Mean reversion / RSI-Bollinger
- VWAP pullback
- Donchian breakout
- Range grid with caps
- Market-making spread capture
- Funding/news risk-off
- Relative-strength rotation
- DCA accumulation with crash protection
- Momentum spike continuation
- Order-book volume anomaly
