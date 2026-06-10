# Crypto TradeStation — Ultimate Trading Bot Architecture

This app is structured around independent engines instead of one monolithic trading loop.

## Canonical layers

- Market Data Engine: Kraken ticker, OHLC candles, AssetPairs validation, symbol discovery and order book depth.
- Strategy Engine: strategy mode, adaptive strategy learning, regime-aware strategy selection and multi-timeframe confirmation.
- Signal Scoring Engine: combined technical, news and trade-memory scoring.
- Risk Manager: max position, daily/weekly loss, drawdown, quote reserve, spread, volatility, pump-chase, max buy price and duplicate-position protection.
- Execution Engine: market/limit selection, fallback-to-limit, order book depth/slippage guard, minimum order validation and failure auto-pause.
- Portfolio Manager: max simultaneous positions, max single-asset allocation, duplicate-position protection and reserve management.
- AI / Learning Engine: true self-learning, learned hold, spike timing and adaptive strategy profiles.
- Backtesting Engine: Kraken OHLC backtesting, forward-test gates and staged LIVE_AUTO preflight.
- Monitoring Dashboard: live status, system test, chart, notifications, Kraken health and export.
- Security Layer: encrypted local key storage, no withdrawal permission requirement and backup export without secrets.

## Core principle

The app should not trade more just because it can. It should trade only when market context, signal quality, risk limits, execution quality and portfolio exposure all agree.

## Canonical controls

- Max Position = max spend per buy.
- Max Buy Price = global/per-symbol price cap.
- Portfolio Balancer = exposure control.
- LIVE_AUTO Preflight + Release Safety = live gate.
- Auto-Pause = repeated failure safety stop.
- Per-symbol Rules = symbol-specific overrides.
- Multi-Timeframe Consensus = trend confirmation.
- Order Book Guard = execution quality.
