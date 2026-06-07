# Risk and Automation Model

The bot is designed to automate trading only when several gates agree.

## Decision gates

```text
symbol validation
→ market data available
→ strategy score
→ AI/news/trade-memory adjustment
→ risk guard
→ balance check
→ fee/spread check
→ order mode check
→ exchange order submission
```

## Buy requirements

```text
valid Kraken pair
tradable BUY signal
free EUR available
order above minimum size
risk guard passes
```

## Sell requirements

```text
valid Kraken pair
tradable SELL signal or lifecycle exit signal
free base asset available
order above minimum size
risk guard passes
```

## Market orders

Market orders are optional and riskier. They can fill worse than the displayed bid/ask.

Recommended first setting:

```text
Market Orders: OFF
```

Only enable market orders after you understand Live Status behavior.

## Profit capture

The app uses practical profit-capture logic:

- take-profit,
- stop-loss,
- trailing/profit lock,
- bearish signal exits,
- portfolio/risk guards.

It cannot know the exact market top.
