# v1.7.9 Spike Profit Timing

This version adds a historical spike/profit-cycle timing layer to the lifecycle exit manager.

## Added

- Historical spike pattern analyzer for held symbols.
- H1/H4 candle comparison for current run vs prior spike behavior.
- Spike progress estimate: current move as a percentage of the symbol's typical historical spike.
- Dynamic trailing distance derived from historical pullbacks.
- Hold deferral when the current run is still early and momentum remains healthy.
- Profit-lock sell when the current run looks exhausted, momentum weakens, or pullback from high exceeds the dynamic trailing band.
- Live Status explanations for hold/sell timing decisions.

## Safety

The bot still cannot know the exact market top and does not guarantee maximum profit. The new logic tries to improve profit capture by learning and analyzing when a move is more likely to keep running versus when it is likely becoming exhausted.

It does not override hard stop-loss, emergency exits, risk-off exits, balance checks, exchange checks, quote restrictions, or cooldowns.
