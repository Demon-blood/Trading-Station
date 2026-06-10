# Crypto TradeStation v2.0.8 — Unified Live Chart

Changed:
- The main Chart tab now opens the full unified live chart directly.
- Removed the Chart hub as the main view to keep the app cleaner.
- The chart is now one primary auto-updating chart with:
  - Kraken OHLC candles
  - full candlestick bodies and wicks
  - volume bars
  - TP and SL overlay lines
  - last buy and last sell guide lines
  - current price guide line and marker
  - actual BUY/SELL entry/exit markers from the local trade journal
  - symbol selector
  - timeframe selector
  - live auto-refresh toggle
  - manual refresh
  - zoom in/out
  - pan left/right
  - reset view
  - AI action badge and chart stats
- Updated stale chart text that said these chart features were only future upgrades.

Structure:
- Future chart-related features should be added into the single Chart tab/chart screen instead of creating more top-level tabs.
- The existing hidden chart detail screens remain available internally but the main user path is the unified Chart tab.
