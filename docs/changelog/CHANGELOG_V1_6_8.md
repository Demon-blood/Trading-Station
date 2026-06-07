# v1.6.8 Paper Kraken Public Data Compile Fix

Fixed a Kotlin constructor argument mismatch in `PaperExchangeClient.kt`.

## Fix

`KrakenSpotClient(apiKey = "", secret = "")` was corrected to:

```kotlin
KrakenSpotClient(apiKey = "", secretKey = "")
```

This keeps PAPER mode using Kraken public market data without requiring private Kraken credentials.
