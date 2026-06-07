# v1.6.0 Remaining Feature Implementation

This version implements the remaining items that were previously marked as scaffold/partial where implementation is technically and legally possible.

## Newly completed implementation areas

| Area | v1.6 status | Notes |
|---|---:|---|
| Bitvavo REST live connector | Implemented | Balance, portfolio, ticker, candles, open orders, cancel, order placement. Requires account/API trading permission. |
| Coinbase Advanced connector | Implemented | Public ticker/candles, accounts, and order placement using JWT signing. Requires CDP/Advanced key format compatible with Android PKCS8 EC private key parsing. |
| Kraken WebSocket ticker feed | Implemented | Public ticker channel stream wrapper using Kraken WebSocket v2. |
| Full strategy optimizer | Implemented | Local grid simulation over EMA/TP/SL/min-score settings using downloaded candles. |
| Shadow paper/live comparison | Implemented | Compares fixed TP, trailing, and AI-exit styles from entry/current/high prices. |
| Telegram/Discord notifications | Implemented | HTTP notifier with Telegram Bot API and Discord webhook support. |
| Watchdog diagnostics | Implemented | Detects stale loop, repeated API errors, and Android low-battery state. |
| Belgian tax CSV export | Implemented | Exports app tax rows to CSV for recordkeeping. |
| Completion registry | Implemented | Shows what is live-capable, local-only, impossible, or refused. |

## Still intentionally not implemented

These remain impossible or inappropriate:

- guaranteed/failproof profit,
- knowing the exact market top for maximum possible profit,
- bypassing Binance/Belgian regulatory restrictions,
- enabling exchange withdrawals.

## Important validation note

Implemented means non-placeholder code exists. Live-capable exchange features still require your own account to have the relevant API permissions and must be tested with tiny orders first.
