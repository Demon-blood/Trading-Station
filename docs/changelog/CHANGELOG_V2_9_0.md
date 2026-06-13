# Crypto TradeStation v2.9.0 — Signing Keystore CI Fix

Fixes:
- GitHub Actions build failed at validateSigningDebug because keystore/cts_debug_update_key.jks was missing in the runner workspace.
- Added a base64 fallback keystore file:
  - keystore/cts_debug_update_key.jks.b64
  - app/keystore/cts_debug_update_key.jks.b64
- Gradle now recreates the stable debug keystore from the .b64 file if the binary .jks is missing.
- Added .gitignore exceptions so the debug update keystore files are not accidentally ignored.

Why:
- Android requires the same signing certificate to update an existing app.
- The stable debug key must be present in every GitHub Actions build.
- The .b64 fallback makes the build robust even if binary .jks files are not committed correctly.
