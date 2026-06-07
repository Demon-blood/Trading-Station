# GitHub Actions Android Build

This project is set up to build APKs directly in GitHub Actions.

## Debug APK build

The debug workflow is ready to run without secrets.

Path:

```text
.github/workflows/android-debug-apk.yml
```

It runs on:

```text
- push to main/master
- pull request to main/master
- manual workflow_dispatch
```

After the workflow finishes:

```text
GitHub repo → Actions → Android Debug APK → latest run → Artifacts → BelgiumCryptoBot-debug-apk
```

## Signed release APK build

The signed release workflow is manual only.

Path:

```text
.github/workflows/android-release-apk.yml
```

Required GitHub repository secrets:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Create a keystore locally:

```bash
keytool -genkeypair \
  -v \
  -keystore release-keystore.jks \
  -alias belgiumcryptobot \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

Convert it to base64 for GitHub Secrets:

### Windows PowerShell

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release-keystore.jks")) | Set-Clipboard
```

### Linux/macOS

```bash
base64 -w 0 release-keystore.jks
```

Then add the value as `ANDROID_KEYSTORE_BASE64` in:

```text
GitHub repo → Settings → Secrets and variables → Actions → New repository secret
```

## Notes

- Debug APKs are easier for testing.
- Release APKs need signing before Android will install them normally.
- Do not store Binance API keys or keystores directly in the repository.
- Keep exchange withdrawal permissions disabled on API keys.
