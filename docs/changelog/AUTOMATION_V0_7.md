# v0.7 Advanced Automation Upgrade

This version expands the Android-only crypto bot from a single recovered scalping strategy into a more automatic trading command system.

## Added modules

- `AdvancedAutomationEngine`
  - Combines market regime, selected strategy, news intelligence, previous-trade memory and risk state.
- `MarketRegimeDetector`
  - Classifies markets as trending up, trending down, sideways, high volatility, low volatility, news-driven or risk-off.
- `MultiStrategyEngine`
  - Selects between scalping, trend, breakout, reversal and news-momentum strategies.
- `NewsIntelligenceEngine`
  - Scores news by sentiment, severity, source confidence and duplicate-adjusted count.
- `AdvancedTradeMemoryEngine`
  - Learns from recent trades using win rate, profit factor and losing streaks.
- `AdvancedRiskManager`
  - Adds daily/weekly loss locks, losing-streak cooldown and advanced state reporting.
- `PositionSizer`
  - Adjusts trade size using confidence, regime, news and memory.
- `SmartOrderManager`
  - Plans spread-aware limit entries, stale-order cancellation, partial TP and trailing stop fields.
- `BacktestEngine`
  - Provides local backtest reports with trades, win rate, profit factor, drawdown and pass/fail gate.

## Added app screens/tabs

- Backtest Lab
- Market Regime
- Smart Orders
- Trade Memory / History
- Expanded Strategy Lab
- Expanded Risk Center

## Automation logic

The new automatic decision flow is:

```text
market data + candles
→ market regime detection
→ strategy selection
→ news severity and sentiment scoring
→ previous-trade memory adjustment
→ risk-state validation
→ dynamic position sizing
→ smart order plan
→ execution guard
→ live/paper order
```

## Live trading rules

The app remains intentionally guarded:

- Paper mode remains the default.
- LIVE_AUTO requires explicit acknowledgement.
- Market orders remain disabled.
- Smart limit orders are the intended execution path.
- Withdrawal permission must remain disabled on exchange API keys.
- Daily and weekly loss guards can block trading.
- High-severity negative news can block trading.
- Losing streaks can force cooldown/safe mode.

## Honest limitation

This package is structured and coded for Android Studio / GitHub Actions builds. This environment does not include a full Android SDK emulator, so APK compilation should be verified through the included GitHub Actions workflows.
