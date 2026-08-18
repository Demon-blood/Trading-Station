# Crypto TradeStation v4 canonical update fix

Upload the contents of this ZIP to the root of `Demon-blood/Trading-Station`, preserving `.github/workflows/`.

## What changes

- `android-debug-apk.yml` becomes a manual notice only, so it no longer creates a second/wrong APK on every push.
- `android-v4-build.yml` becomes the canonical automatic build.
- The canonical APK is built only after applying `.cts-v4-migration/apply_milestone6.py`.
- The update build identity is `4.0.1` / `versionCode 106`.
- The debug APK must be signed by the stable CTS update certificate:
  `b690958cb434544e7f8963ecc86559562a82155ffbd915cb2088c9333e06aa28`.
- GitHub Actions fails the build instead of uploading an APK when package name, version identity, or debug signer is wrong.
- The artifact contains `INSTALL_IDENTITY.txt`, `apk-signature.txt`, `apk-package.txt`, and `SHA256SUMS.txt`.

## Artifact to install

After the green workflow finishes, download only:

`CryptoTradeStation-v4.0.1-debug-update-apk`

Inside it, install:

`CryptoTradeStation-v4.0.1-debug-update.apk`

Do not use `BelgiumCryptoBot-debug-apk`; that was produced by the legacy workflow.

## If Android still refuses the update

Run from PowerShell with the phone connected over USB debugging:

```powershell
.\CHECK_AND_INSTALL_UPDATE.ps1 -ApkPath "C:\path\CryptoTradeStation-v4.0.1-debug-update.apk"
```

To install after compatibility checks:

```powershell
.\CHECK_AND_INSTALL_UPDATE.ps1 -ApkPath "C:\path\CryptoTradeStation-v4.0.1-debug-update.apk" -Install
```

The script compares package name, versionCode and the real signing certificate of the installed APK against the new APK before using `adb install -r`.
