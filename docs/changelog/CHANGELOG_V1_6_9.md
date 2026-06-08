# v1.6.9 Advanced Settings Persistence + Pre-Scan Block Fix

## Fixed

- Advanced settings now save using `commit()` instead of async `apply()` so the foreground service can read updated settings immediately on the next scan.
- Quick profile buttons now apply and save immediately instead of only filling text fields.
- Added visible editable toggle for `Auto-disable bad symbols`.
- Disabled `Auto-disable bad symbols` by default to prevent BTCEUR/ETHEUR from being blocked before scan from stale/limited local trade history.
- Updated Small Balance, Balanced and Aggressive profiles to set `Auto-disable bad symbols = OFF` by default.
- Updated visible app label to v1.6.9 Settings Fix.

## Notes

If Live Status shows `Auto-disabled before scan`, turn off `Auto-disable bad symbols` in Advanced Settings or apply the Small Balance profile. This lets symbols scan normally while the bot rebuilds fresh paper/live performance history.
