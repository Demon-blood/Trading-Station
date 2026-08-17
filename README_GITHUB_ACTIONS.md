# Crypto TradeStation v4.0.0 — GitHub Actions Build

This pack replaces the local Windows Gradle bootstrap as the preferred post-migration build path.

## Install into the Trading-Station repository

Copy/extract the contents of this ZIP **into the root of `Demon-blood/Trading-Station`** so the repository contains:

- `.github/workflows/android-v4-build.yml`
- `.cts-v4-migration/apply_milestone6.py`
- the rest of `.cts-v4-migration/`

Commit and push those files to GitHub.

The workflow runs automatically on pushes/PRs to `main` or `master`, and it can also be started manually from **Actions → Crypto TradeStation v4 Build → Run workflow**.

## Debug APK

Choose `debug` in the Run workflow dialog. No signing secrets are required. The existing CTS stable debug update key remains the debug signer.

The Actions artifact is named:

`CryptoTradeStation-v4.0.0-debug-apk`

## Release APK

Choose `release` and configure these repository Actions secrets first:

- `CTS_RELEASE_KEYSTORE_B64`
- `CTS_RELEASE_STORE_PASSWORD`
- `CTS_RELEASE_KEY_ALIAS`
- `CTS_RELEASE_KEY_PASSWORD`

`CTS_RELEASE_KEYSTORE_B64` is the base64 text of your private release JKS. Never commit the JKS or `signing.properties` to the repository.

The workflow reconstructs the JKS only inside the GitHub runner and deletes it when the runner is destroyed.

## What CI does

1. Checks out the repository.
2. Sets up Temurin JDK 17, Python 3.12, Android SDK 35 and Gradle 8.9.
3. Applies the complete M1→M6 migration in the runner.
4. Adds the missing JUnit 4.13.2 unit-test dependency when needed.
5. Validates v4.0.0 / code 105 / Room 11 and confirms destructive migration fallback is absent.
6. Compiles debug Kotlin.
7. Runs the CloudShare/parity unit tests by default.
8. Builds debug or signed release APK.
9. Generates SHA-256 and build metadata.
10. Uploads the APK as a GitHub Actions artifact.
11. If anything fails, uploads a redacted failure-diagnostics artifact instead.

No exchange API keys, CloudShare credentials, remote-control secrets, release passwords, signing properties, or keystore files are uploaded as diagnostics.
