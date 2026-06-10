# Crypto TradeStation v2.7.2 — Paper/Live SELL No-Balance Guard

Fixes:
- Prevents invalid SELL attempts when the bot has no available base-asset balance.
- Stops repeated errors such as:
  Paper SELL blocked: insufficient paper ALGO balance. Available=0 ALGO
- SELL signals without holdings are skipped before order submission.

Behavior:
- If ALGOEUR generates SELL but available ALGO = 0, the bot logs a WARN and does not submit an order.
- This applies before both paper and live order submission paths.
- Valid SELL/exit management remains available when the asset balance exists.
