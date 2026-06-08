# Learned Hold Profit Capture

The bot now learns whether specific symbols tend to continue upward after normal take-profit or trailing-exit conditions.

When a normal exit condition appears, the lifecycle manager asks the self-learning engine whether the symbol has enough profitable continuation history to justify holding longer.

The bot may defer:

- take-profit exits
- trailing-profit exits

The bot will not defer:

- stop-loss exits
- risk-off exits
- emergency exits
- bearish AI exits unless explicitly enabled in settings

The Self Learning tab shows learned hold profiles with:

- sample count
- profitable/loss exits
- continuation win rate
- average hold duration
- hold confidence
- whether TP/trailing exits may be deferred

Use PAPER mode first so the hold engine can learn without real money.
