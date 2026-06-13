# Crypto TradeStation v2.9.1 — Signing Gradle Order Fix

Fixes:
- app/build.gradle.kts referenced stableDebugKeystoreResolved before it was initialized.
- stableDebugKeystoreFile now correctly points to rootProject.file("keystore/cts_debug_update_key.jks").
- Gradle can now compile the signing helper and recreate the keystore from the .b64 fallback when needed.

Build error fixed:
- Variable 'stableDebugKeystoreResolved' must be initialized
