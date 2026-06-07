# v0.5 Recovered Trading Strategy Implementation

This build implements the trading strategy context recovered from the earlier project discussion:

- TradingView/Binance-style crypto scalping
- Multi-timeframe decision-making using 5m, 15m and 1h candles
- EMA fast/slow trend confirmation
- OBV volume confirmation
- ATR volatility and TP/SL sizing
- Momentum scoring
- Symbol rotation over the configured Binance Spot symbols
- News sentiment score integration
- Previous-trade memory score integration
- Execution guards before live orders

## Technical score

The technical strategy now uses:

```text
strategy_score = EMA trend agreement + OBV confirmation + short-term momentum - ATR volatility penalty + liquidity/spread adjustment
```

Default parameters:

```text
EMA fast: 9
EMA slow: 21
OBV lookback: 20 candles
ATR period: 14 candles
Required trend agreement: 2 of 3 timeframes
Minimum buy score: 72
Take-profit approximation: ATR × 1.4
Stop-loss approximation: ATR × 1.0
```

## AI decision score

The AI layer still combines:

```text
final_score = technical strategy score + news sentiment score + previous-trade memory score
```

Live execution is only allowed when:

1. the action is BUY or SMALL_BUY,
2. the selected mode allows execution,
3. execution guards pass,
4. live trading has been acknowledged,
5. API keys are configured,
6. risk/spread/trade-count/loss caps are respected.

## Android UI

A new **Strategy** tab has been added. It displays the recovered strategy parameters, scoring formula, and the ATR-based TP/SL design.

## Safety note

This is not failproof. It is a guarded Android-only crypto scalping strategy implementation. Use paper mode first and validate results before enabling live-auto mode.
