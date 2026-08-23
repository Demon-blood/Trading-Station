# Crypto TradeStation — Milestone 1 Baseline Fix

Target repository: `Demon-blood/Trading-Station`

## What this fixes

The canonical Android workflow declares:

- `CTS_VERSION_NAME: 4.0.7`
- `CTS_VERSION_CODE: 112`

but later hard-codes v4.0.6 / 111 into the source-patching, source-validation,
APK-validation and artifact-naming stages.

This package makes the workflow environment values the single source of truth.

## Safety boundary

This milestone does **not** change:

- Kraken trading logic
- PAPER / LIVE / SHADOW behavior
- strategies
- risk management
- Room schema/data
- background-service behavior
- AI behavior

Only `.github/workflows/android-v4-build.yml` is modified.

## Apply on Windows

```powershell
powershell -ExecutionPolicy Bypass -File .\APPLY_M1_BASELINE.ps1 -RepoPath "C:\path\to\Trading-Station"
```

The original workflow is backed up to:

`.cts-m1-backup/android-v4-build.yml`

## Verify manually

```powershell
python .\verify_m1.py "C:\path\to\Trading-Station"
```

## Roll back

```powershell
powershell -ExecutionPolicy Bypass -File .\REVERT_M1_BASELINE.ps1 -RepoPath "C:\path\to\Trading-Station"
```

## Expected result

The workflow should build and validate one consistent identity:

- versionName `4.0.7`
- versionCode `112`

## Next milestone

After the baseline CI passes, freeze/materialize the effective generated v4.0.7
source into the normal `app/` tree and remove build-time source mutation from the
canonical workflow. Only after that should the new 24/7 Android hosting changes
and low-cost Luna/Sol/data utilities be layered in.
