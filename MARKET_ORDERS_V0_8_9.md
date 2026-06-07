# v0.8.9 Kraken market orders

This build adds optional Kraken market-order execution.

## What changed

- Added `OrderType.LIMIT` and `OrderType.MARKET`.
- Added `enableMarketOrders` setting. Default: `false`.
- Added `maxMarketOrderEur` setting. Default: `25.00`.
- Added `marketOrderSlippageWarningPercent` setting. Default: `0.75`.
- Kraken AddOrder now sends `ordertype=market` when market-order mode is enabled.
- Limit orders remain the default and safer mode.
- Live Status logs whether an order is submitted as LIMIT or MARKET.
- Market orders are blocked when spread exceeds the configured slippage warning percentage.

## Safety behavior

Market orders execute immediately and can fill worse than the visible price. Keep max order size low while testing.
