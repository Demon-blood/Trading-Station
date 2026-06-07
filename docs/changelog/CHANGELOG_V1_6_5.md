# v1.6.5 Editable Advanced Settings

This build exposes the advanced bot controls that were previously stored in `BotSettings` but were not physically editable from the Android UI.

## Added

- New **Advanced Settings** tab
- Editable minimum AI / strategy score
- Editable required timeframe agreement
- Editable allowed quote assets
- Editable auto-symbol discovery candidate limit
- Editable active rotation size
- Editable max new trades per scan
- Editable max trades per hour
- Editable max simultaneous live positions
- Editable max position size
- Editable quote reserve amount and reserve percentage
- Editable market and limit spread thresholds
- Editable buy/sell/loss cooldowns
- Editable market order, fallback, liquidity blacklist, multi-symbol, non-EUR buy toggles
- Quick profiles:
  - Small Balance Active
  - Balanced Safe
  - Aggressive

## Notes

The recommended small-balance profile uses EUR-only quotes, small trade size, lower reserve requirements, and a balanced minimum AI score so the bot can actually trade while still using safety guards.
