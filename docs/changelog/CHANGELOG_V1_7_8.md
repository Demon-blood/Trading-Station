# v1.7.8 Learned Hold Profit Capture

This release adds learned hold/exit deferral to the self-learning layer.

## Added

- Persistent `learned_hold_profiles` Room table.
- Learned hold profiles per symbol.
- Hold confidence based on completed BUY→SELL outcomes.
- Average hold-time and continuation-win-rate tracking.
- Lifecycle manager can defer take-profit/trailing exits when the learned hold profile is strong.
- Hard stop-loss/risk-off/emergency exits are never overridden by learned hold.
- Self Learning tab now displays Learned Hold Profiles.
- Live Status logs when a SELL is deferred because learned hold is active.

## Safety

This does not guarantee maximum profit. It attempts to capture more upside by learning when symbols historically continued after ordinary profit exits. It remains bounded by stop-loss, risk, balance, spread, exchange, cooldown, and compliance guards.
