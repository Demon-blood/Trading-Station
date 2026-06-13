# Crypto TradeStation v3.0.0 — Unlimited Daily Trades Option

Changed:
- Max trades per day now supports unlimited mode.
- Enter 0 in Max trades/day to disable the daily trade-count cap.
- Removed the 10000 upper clamp.
- ExecutionGuard skips the daily trade-count check when maxTradesPerDay == 0.
- Pro readiness accepts maxTradesPerDay == 0 as "Daily trade limit is unlimited".

Still active even with unlimited daily trades:
- Max trades per hour.
- Max new trades per scan.
- Max simultaneous live positions.
- Daily loss guard.
- Position sizing, quote reserve, order-book/slippage guard and duplicate-position guard.
