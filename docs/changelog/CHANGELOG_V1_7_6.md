# v1.7.6 EUR Primary Quote Routing

This release fixes the practical trading behavior for Belgian Kraken accounts where EUR is the main cash balance.

## Changes

- EUR is now the default allowed quote asset.
- Auto-discovery can still scan the full Kraken universe, but trading rotation now respects allowed quote assets correctly.
- The scanner no longer treats `quoteUniverse=ALL` as permission to trade every quote asset.
- EUR pairs receive a stronger ranking boost so the bot prefers pairs it can actually buy with free EUR.
- USD/USDT/USDC and crypto quote pairs are still visible/analyzable, but they are not used for BUY trades unless explicitly allowed and funded.
- Live Status now explains that EUR is the primary quote/cash balance for Belgian Kraken usage.

## Recommended setting

For Belgian Kraken spot trading with normal cash deposits:

```text
Auto Symbol Universe: ALL
Allowed Quote Assets: EUR
Non-EUR Quote Buys: OFF
```

This means:

- Scan all Kraken markets.
- Prefer/select EUR pairs for BUY trades.
- Permit SELLs when you hold a base asset.
- Avoid blocking EUR incorrectly.
