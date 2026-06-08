# KSP Crypto AI — v1.7.1 Adaptive Multi-Strategy Learning

Android-only crypto trading bot focused on Kraken live trading, paper trading with live Kraken public market data, auto symbol discovery, rotation safety, editable advanced settings, and now a bounded persistent self-learning engine.

## v1.7.1 highlights

- Persistent learned symbol profiles.
- Persistent learned strategy profiles.
- Learning feature snapshots for every scanned decision.
- Score adjustment based on real/paper trade outcomes.
- Position-size multiplier hints based on learned profitability.
- Sample-size protection before strong adjustments are applied.
- Self-learning audit trail.
- New Self Learning tab.
- Advanced Settings controls for self-learning limits.

The self-learning engine does not bypass exchange, legal, API, balance, quote, reserve, cooldown, spread, market-order or risk guards. It only adjusts score/sizing hints inside bounded limits.

## Build

Use GitHub Actions or run:

```bash
gradle --no-daemon clean :app:assembleDebug
```

## Safe testing order

1. Build/install the APK.
2. Select `PAPER` provider.
3. Confirm Paper mode uses Kraken public data in Live Status.
4. Run paper mode until enough completed trades exist.
5. Open Self Learning tab and confirm profiles appear.
6. Only then test small live Kraken trades.


## v1.7.3 GitHub Actions SDK fix

This build removes android-actions/setup-android@v3 from the workflows and uses the preinstalled Android SDK/sdmanager directly. It installs only platform-tools, Android 35 platform, and build-tools 35.0.0, avoiding the failing Android Emulator package download.
