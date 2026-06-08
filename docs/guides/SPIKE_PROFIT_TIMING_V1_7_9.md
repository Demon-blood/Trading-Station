# Spike Profit Timing

The Spike Profit Timing engine is designed for assets like BTC that historically make large continuation moves.

For each held position, it compares:

- current run from recent swing low,
- average historical spike size,
- average spike duration,
- pullback from current high,
- momentum strength,
- AI decision score.

The result can be:

- **HOLD**: normal take-profit/trailing sell is deferred because the move still looks early and healthy.
- **SELL / lock profit**: the current run looks historically extended, momentum is weakening, or the pullback exceeds the dynamic trailing band.
- **NEUTRAL**: the normal lifecycle/learned-hold logic is used.

This feature improves profit-capture behavior but cannot identify the perfect market top.
