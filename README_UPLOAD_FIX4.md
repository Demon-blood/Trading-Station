# Crypto TradeStation v4 — Migration Fix 4

This fixes GitHub Actions run `32181978456` / commit `d0afe01`, which failed during **Apply cumulative v4 migration** with:

```text
Cannot patch Kraken current fee tier: expected exactly one match, found 3.
```

## Root cause

`ExchangeClientsV08.kt` contains multiple exchange implementations with the same `getAvailableBalances()` method signature. The M6 installer searched for that method globally and incorrectly required exactly one occurrence.

## Fix

`apply_milestone6.py` now scopes the fee-tier insertion to the `KrakenSpotClient` class before locating `getAvailableBalances()`. The other exchange implementations are ignored. The patch is idempotent: if `getTradingFeeSchedule()` is already present, it is not inserted again.

## Upload

Upload this ZIP's contents to the repository root, preserving the hidden `.cts-v4-migration` directory, and replace the existing files.

Files replaced:

```text
.cts-v4-migration/apply_milestone6.py
.cts-v4-migration/SHA256SUMS.txt
```

Keep the previous Migration Fix 3 M4 installer files in place.

No version bump is needed: v4.0.1 / versionCode 106 has still not reached APK assembly.
