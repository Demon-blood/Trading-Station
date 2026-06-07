# v1.6.3 — Balance-Aware Rotation + Trade Unblock Diagnostics

This release fixes a practical issue introduced by full Kraken-universe scanning: the scanner could select strong symbols whose quote asset had no usable free balance, causing the bot to analyze multiple symbols but submit no trades.

## Added

- Balance-aware auto-rotation.
- Rotation now prefers symbols where the account has either:
  - enough free quote balance to BUY, or
  - enough free base asset to SELL.
- Live Status now logs the usable quote/base balances for selected rotation candidates.
- Live Status now prints a clear `No orders submitted this scan` summary when every candidate is blocked.

## Why this matters

When scanning every Kraken pair, the best-ranked markets may be USD, USDT, BTC, ETH or other quote pairs. If your Kraken account only has EUR, those pairs are useful for analysis but cannot be bought automatically unless the matching quote balance exists. v1.6.3 filters the rotation by actual available balances before execution.

## Recommended settings for small EUR accounts

- Allowed quote assets: `EUR` first.
- Auto symbol quote universe: `ALL` is okay, but the balance-aware filter will prioritize usable pairs.
- Max new trades per scan: `1` or `2`.
- Minimum quote reserve: keep low enough for your balance, for example `2.00` to `5.00` if testing with small funds.
