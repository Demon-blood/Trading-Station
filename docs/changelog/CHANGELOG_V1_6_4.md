# v1.6.4 Compile Fix

Fixes Kotlin nullability compile failure in `BotController.freeBalanceForAsset()`.

## Fixed
- `freeBalanceForAsset()` now always returns a non-null `BigDecimal`.
- Kraken alias lookups for `ZEUR`, `XXBT`/`XBT`, and `XETH` now safely fall back to `BigDecimal.ZERO`.

## Why
The v1.6.3 balance-aware rotation patch used nullable map lookups in an Elvis chain that Kotlin inferred as `BigDecimal?`, causing Gradle `:app:compileDebugKotlin` to fail.
