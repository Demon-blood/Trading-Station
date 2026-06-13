# Crypto TradeStation v2.8.8 — Dust Cleanup Sell-All

Fixes:
- SELL execution no longer sells only the configured max-position notional.
- SELL execution now attempts to sell the full available base-asset balance.
- SELL quantity is rounded down using the exchange pair quantity decimals.
- Any leftover remainder is treated as unavoidable dust caused by exchange precision/minimum-order rules.
- Portfolio rows now mark tiny balances under about €5 as dust.

Why:
- Previous SELL sizing reused the per-order/max-position cap, which is correct for BUY but wrong for exits.
- This could sell only part of a position and leave a tiny balance that cannot be sold or converted.
- Exchanges also truncate quantities to allowed precision, so very small remainders can be unavoidable.
