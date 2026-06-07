# Auto Symbol Discovery

The bot can now automatically search all Kraken spot markets and build its own rotation list.

## How it works

1. Fetch Kraken AssetPairs.
2. Keep EUR spot pairs only.
3. Validate pair status, minimum order size, price decimals and quantity decimals.
4. Fetch ticker data for each candidate.
5. Score the symbol using spread, 24h EUR volume, 24h movement and major-pair safety boost.
6. Enable symbols that pass the configured limits.
7. Use the top enabled symbols in the live scan cycle.

## Important settings

- `autoSymbolDiscoveryEnabled`
- `autoSymbolCandidateLimit`
- `autoSymbolActiveLimit`
- `autoSymbolMaxSpreadPercent`
- `autoSymbolMinVolume24hEur`

## Recommended first live settings

For a small account, use a low active limit and small max position:

```text
Auto Symbol Discovery: ON
Active Limit: 3 to 5
Max Position EUR: 5 to 10
Market Orders: OFF until stable
```
