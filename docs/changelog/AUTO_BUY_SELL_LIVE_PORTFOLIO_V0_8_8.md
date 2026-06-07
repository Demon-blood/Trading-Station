# v0.8.8 Auto buy/sell + live portfolio

Changes:
- Portfolio tab now loads live exchange balances instead of placeholders.
- Kraken portfolio uses BalanceEx when available and displays total/free/held balances.
- Automatic SELL decisions are generated on bearish/risk-off conditions.
- SELL sizing checks free base asset balance before order submission.
- BUY still requires free EUR; SELL requires free crypto balance.
- Version label updated to v0.8.8.

Safety:
- Market orders remain disabled.
- Live orders remain guarded limit orders.
- No withdrawal capability is used or required.
