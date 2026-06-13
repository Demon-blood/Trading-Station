# Crypto TradeStation v3.2.0 — Full Strategy Library Implementation

Implemented strategy modes:
- MEAN_REVERSION_RSI_BOLLINGER
- VWAP_PULLBACK
- DONCHIAN_BREAKOUT
- RANGE_GRID
- MARKET_MAKING_IMBALANCE
- FUNDING_NEWS_RISK_OFF
- PAIRS_RELATIVE_STRENGTH
- DCA_CRASH_PROTECTION
- MOMENTUM_SPIKE_CONTINUATION
- VOLUME_ANOMALY_WHALE_MOVE

Changed:
- StrategyMode enum expanded.
- MultiStrategyEngine now evaluates all added strategies in AUTO mode and individually when selected.
- RecommendationEngine now uses MultiStrategyEngine instead of hardcoded scalping-only selection.
- BacktestEngine now applies strategy-specific entry/exit logic instead of using EMA crossover for every strategy.
- Strategy Sandbox, Auto-Tuner, and Performance Lab now include the new strategies.
- TechnicalIndicators now includes RSI, SMA, standard deviation, VWAP, Donchian high/low helpers.

Notes:
- Market-making imbalance and whale-move modules use currently available ticker/candle/order-book-proxy data. Full order-book-weighted execution remains protected by existing live order-book guards.
