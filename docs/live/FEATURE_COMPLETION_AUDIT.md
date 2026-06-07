# v1.6.0 Live Completion Audit

This file separates what is implemented/live-capable from what is impossible or refused.

## Live-capable exchange features

| Area | Status | Notes |
|---|---:|---|
| Kraken live spot trading | Live-capable | Primary tested exchange path. |
| Kraken balance/portfolio/open-order/order cancel | Live-capable | Used by Portfolio, Orders, Live Status and bot execution. |
| Kraken limit/market buy/sell | Live-capable | Guarded by balances, risk, pair metadata and settings. |
| Kraken EUR symbol discovery/rotation | Live-capable | Discovers, validates, scores and rotates EUR symbols. |
| Kraken WebSocket ticker feed | Implemented/live-capable | Public ticker stream wrapper is now implemented. |
| Bitvavo REST trading | Implemented/live-capable | Requires Bitvavo account/API permissions. Test with small orders first. |
| Coinbase Advanced trading | Implemented/live-capable | Requires compatible Coinbase Advanced/CDP API key and EC private key. Test carefully. |

## Implemented local intelligence and automation

| Feature | Status | Notes |
|---|---:|---|
| Trade lifecycle manager | Implemented | Manages open positions/exits through app state and exchange data. |
| TP/SL/profit-lock logic | Implemented | Practical profit-capture rules; not future prediction. |
| Strategy optimizer | Implemented | Runs candle-grid local simulations. |
| Shadow paper/live comparison | Implemented | Compares alternate exit styles. |
| Remote notifications | Implemented | Telegram/Discord notifier support added. |
| Watchdog | Implemented | Stale loop, repeated API errors, and low battery checks. |
| Belgian tax CSV export | Implemented | Recordkeeping helper, not certified tax advice. |

## Refused or impossible

| Feature | Status | Reason |
|---|---:|---|
| Binance regulatory bypass | Refused | The app does not evade Belgian or Binance restrictions. |
| Exchange withdrawal automation | Refused | Withdrawal permissions should remain disabled. |
| Failproof trading | Impossible | Markets are uncertain. |
| Maximum possible profit selling | Impossible | The app cannot know future market tops; it uses TP/SL/trailing/bearish exits. |

## Definition

A live-capable feature has concrete code against a live provider endpoint or local automation path. It still must be validated on the user's account, because exchanges may restrict accounts by region, permissions, KYC tier or product availability.
