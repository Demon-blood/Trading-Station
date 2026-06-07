# Rotation Safety Guide — v1.6.2

The bot can scan the full Kraken spot universe, but live execution is now controlled by several guards:

1. **Allowed Quote Assets** — only pairs ending in enabled quote assets can create BUY orders.
2. **Liquidity/Spread Blacklist** — pairs with low 24h volume or high spread are skipped.
3. **Max New Trades Per Scan** — prevents one scan from opening too many orders.
4. **Max Trades Per Hour** — prevents repeated live execution bursts.
5. **Max Simultaneous Positions** — prevents over-exposure.
6. **Cooldowns** — prevents immediate re-entry after buys, sells or losses.
7. **Quote Reserve** — keeps a cash/stablecoin reserve before buying.
8. **Market Order Fallback** — unsafe market orders are converted to limit orders when fallback is enabled.

Recommended conservative setup for small accounts:

```text
Allowed quote assets: EUR
Max new trades per scan: 1
Max trades per hour: 2
Max simultaneous positions: 2
Minimum quote reserve: 10 EUR
Minimum quote reserve percent: 20%
Market orders: OFF or fallback-to-limit ON
```
