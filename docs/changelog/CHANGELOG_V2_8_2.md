# Crypto TradeStation v2.8.2 — Portfolio Position Guards

Implemented:
- Portfolio rows now show lifecycle position guard state when a balance matches an active managed position.
- Each matched portfolio asset can show:
  - MANAGED / WATCH
  - TP ARMED / TP OFF
  - SL ARMED / SL OFF
  - TRAILING ARMED / TRAILING WAIT
  - entry, current, high, TP, SL, trailing stop
  - unrealized P/L and lifecycle reason
- Portfolio refresh now also refreshes lifecycle positions.
- Portfolio metrics now show Guarded position count.

Reason:
- The old main Positions view showed trailing/TP/SL armed state.
- Since Portfolio is now the main place where positions are visible, the guard state is embedded directly into portfolio asset rows.
