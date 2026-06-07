# v1.6.2 Rotation Safety + Live Verification

This release hardens the full-Kraken-universe scanner so it can use many symbols without overtrading.

## Added

- Allowed quote-asset controls (`EUR,USD,USDT,USDC` by default).
- Max new trades per scan guard.
- Max trades per hour guard.
- Max simultaneous live positions guard.
- Symbol cooldowns after buy, sell and loss.
- Quote reserve guard to avoid spending the last available quote balance.
- Liquidity/spread blacklist integration for auto-discovery.
- Safer market-order fallback to limit orders when spread/liquidity is unsafe.
- Live verification engine covering credentials/balance/portfolio/symbols/ticker/OHLC/orders.
- Clearer Symbol Scanner display for rotation limits and reserve/cooldown rules.

## Defaults

- Allowed quote assets: `EUR,USD,USDT,USDC`.
- Max new trades per scan: `2`.
- Max trades per hour: `3`.
- Max positions: `3`.
- Quote reserve: `10.00` or `20%`, whichever is higher.
- Non-EUR crypto-quote buys remain disabled unless explicitly enabled.
