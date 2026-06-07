# v0.7.3 Kotlin compile fix

This patch fixes the GitHub Actions build failure in `:app:compileDebugKotlin`.

## Error fixed

`AppSettingsStore.kt` called:

- `secure.saveEncryptedString(...)`
- `secure.readEncryptedString(...)`

but `SecureSettingsStore.kt` only exposed:

- `saveSecret(...)`
- `readSecret(...)`

## Patch

Added compatibility methods to `SecureSettingsStore`:

```kotlin
fun saveEncryptedString(name: String, value: String) = saveSecret(name, value)
fun readEncryptedString(name: String): String? = readSecret(name)
fun clearSecret(name: String) { ... }
```

Also changed Room schema export to false for the current starter project:

```kotlin
exportSchema = false
```

This removes the Room KSP schema warning until a formal schema export directory is added.

## Version

App version bumped to `0.7.3`.
