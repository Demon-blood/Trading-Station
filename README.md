# Crypto TradeStation v4.0.5 — GDELT Rate-Limit Fix

This is the complete current update pack.

## Root cause

Crypto TradeStation evaluates news for each symbol. The previous GDELT client had
no request throttle and no cache, so one successful GDELT call could be followed by
another symbol's GDELT request immediately.

GDELT's own 429 response asks clients to limit requests to one every five seconds.

## Fix

The GDELT client now uses:

- one actual GDELT request at most every **6 seconds globally**
- a **15-minute per-symbol cache**
- up to **1 hour stale-cache fallback** during transient provider trouble
- a bounded **500-symbol cache**
- **no sleep in the trading loop**

If the local GDELT request window is not open yet, CTS uses cached GDELT results when
available or simply continues the current symbol without GDELT for that pass. RSS and
other healthy providers continue normally.

This avoids turning a 16-30 symbol scan into a 1-3 minute blocking news wait.

## Included current fixes

- diagnostics integration
- full integration cleanup
- Kraken minimum-order sizing
- exact-preview UI + hamburger navigation
- System Diagnostics + selectable directory
- Backup/Diagnostics immediate selected-directory fix
- GDELT request pacing/cache
- canonical GitHub Actions workflow

## Build identity

- versionName: 4.0.5
- versionCode: 110
- applicationId: com.ksp.cryptobot
