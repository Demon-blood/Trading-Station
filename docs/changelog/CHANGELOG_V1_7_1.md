# v1.7.1 Adaptive Multi-Strategy Learning

This version extends v1.7.0 true self-learning so the bot can automatically change trading strategies based on learned performance.

## Added

- Adaptive strategy selector.
- Per-symbol preferred strategy use.
- Global strategy fallback when a strategy performs well across symbols.
- Strategy-score adjustment based on learned symbol and strategy profiles.
- Learned position-size multiplier based on symbol and strategy performance.
- Live Status logs explaining which strategy was selected and why.
- Decision snapshots now store the actual selected strategy instead of only the global setting.

## Strategies the bot can switch between

- SCALPING
- TREND
- BREAKOUT
- REVERSAL
- NEWS_MOMENTUM
- AUTO fallback

## Safety

Adaptive strategy learning does not bypass balance checks, quote reserve checks, spread filters, cooldowns, risk guards, or Kraken execution safeguards.
