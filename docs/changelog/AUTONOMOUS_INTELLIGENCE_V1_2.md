# v1.2.0 Autonomous Intelligence Pack

This release adds the next automation layer on top of the existing Kraken live-trading system.

## Added

- Per-symbol autonomous strategy selection
- Self-optimization scoring from recent trades
- Auto-disable logic for weak symbols
- Shadow paper/live comparison notes
- Trade replay snapshots saved into AI explanations
- Local remote-command parser scaffold
- Belgian tax CSV export helper
- Portfolio reserve manager diagnostics
- Android watchdog diagnostics
- Persisted v1.1 and v1.2 advanced settings
- New **Autonomous** tab
- Visible version label updated to `v1.2.0`

## Important behavior

The autonomous layer does **not** bypass live-trading safety gates. Kraken credentials, live acknowledgement, manual-mode checks, balance checks, minimum order checks, spread/slippage checks and risk guards still apply.

## New remote commands scaffold

Supported parser commands:

```text
/status
/pause
/resume
/orders
/positions
/profit
/kill
```

The parser is local in this version. It is ready to be wired to Telegram or Discord in a later package.

## Belgian tax export

The Autonomous tab can generate a CSV preview using lifecycle tax rows when present, otherwise fallback trade rows.

This is a recordkeeping aid only. It is not official Belgian tax advice.
