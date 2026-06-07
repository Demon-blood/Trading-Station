# v0.8.2 Kraken OkHttp compile fix

This patch fixes the GitHub Actions compile failure in `ExchangeClientsV08.kt` caused by deprecated OkHttp calls being treated as compilation errors.

## Changed

- Replaced deprecated `MediaType.parse(...)` usage with OkHttp extension `toMediaType()`.
- Replaced deprecated `RequestBody.create(...)` usage with `String.toRequestBody(...)`.
- Added imports:
  - `okhttp3.MediaType.Companion.toMediaType`
  - `okhttp3.RequestBody.Companion.toRequestBody`

## Affected file

`app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt`

Re-run:

```bash
gradle --no-daemon clean :app:assembleDebug
```
