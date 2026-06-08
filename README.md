# Crypto TradeStation Android App

Android-only Kraken/PAPER crypto bot with adaptive multi-strategy self-learning, EUR-first Belgian cash routing, learned hold profiles, and historical spike/profit-cycle timing.

## v1.7.9 highlight

The lifecycle manager now analyzes historical spike behavior for held symbols such as BTC. It compares the current move against prior spike size, duration, momentum, and pullback behavior. It can defer normal take-profit/trailing exits when the move still looks early and healthy, or lock profit when the run looks exhausted.

It still cannot know the exact top and does not guarantee maximum profit. It is a bounded profit-capture improvement, not a prediction guarantee.

# Crypto TradeStation v1.7.7 EUR Cash

Android-only crypto trading bot focused on Kraken live/paper trading, automatic symbol discovery, adaptive multi-strategy learning, and EUR-first cash routing for Belgian users.

## Important default

For Belgian Kraken accounts, EUR is treated as the main quote/cash asset:

```text
Allowed Quote Assets: EUR
Auto Symbol Universe: ALL
```

The app can still scan all Kraken markets, but BUY trades are routed only through allowed quote balances. This prevents the bot from selecting USD/USDT/BTC quote pairs when your available cash is EUR.
# Crypto TradeStation — v1.7.1 Adaptive Multi-Strategy Learning

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
