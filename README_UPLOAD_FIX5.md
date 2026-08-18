# Crypto TradeStation v4 - Kotlin Fix 5

Latest GitHub Actions run reached the real Android/KSP compiler and failed at:

`BotController.kt:1875:59 Unexpected tokens`

Root cause: the M4 migration installer emitted the two characters `\n` into Kotlin source between two `appendLine(...)` calls instead of emitting a real newline.

This patch changes the Python replacement from a double-escaped newline to a real generated newline and refreshes the affected SHA256 manifests.

Upload/extract this ZIP at the repository root, preserving `.cts-v4-migration/` paths. Keep Fix 3 and Fix 4 already present.
