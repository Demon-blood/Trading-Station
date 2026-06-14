# Crypto TradeStation v3.2.3 — Position Exit Trigger Fix

Fixes:
- Stop-loss, trailing-stop, and take-profit hits are now treated as hard price exits.
- Spike timing and learned-hold logic can no longer silently defer TP/trailing/SL exits.
- If spike timing or learned hold wanted to hold but TP/trailing/SL is hit, Live Status logs that it was overridden and sells anyway.

Why:
- The portfolio screen could show TP ARMED while the lifecycle engine deferred the exit because spike timing or learned-hold thought the move might continue.
- That made it look like the bot ignored the armed TP condition.
