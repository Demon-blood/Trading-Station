# KSP Crypto Bot Android v0.2

Android-only crypto trading bot with optional Binance Spot live execution.

## What changed in v0.2

This version adds the live-trading architecture the user requested:

- Binance Spot live order execution path.
- Live modes: `PAPER`, `LIVE_CONFIRM`, `LIVE_AUTO`.
- AI decision layer that combines:
  - technical recommendation score,
  - news sentiment score,
  - previous-trade memory score.
- Optional NewsAPI integration for crypto news.
- Previous-trade learning from local Room database history.
- Execution guard:
  - live acknowledgement required,
  - max trades per day,
  - max daily realized loss,
  - duplicate trade cooldown,
  - low-confidence trade block,
  - Binance API keys required for live orders.
- Encrypted local API-key storage using Android Keystore wrapper.
- Foreground service loop for Android-only operation.

## Safety model

This app can place live Binance Spot limit orders when all of these are true:

1. Mode is set to `LIVE_AUTO`.
2. Binance API key and secret are saved.
3. Live trading acknowledgement checkbox is enabled.
4. The AI decision is tradable.
5. Execution guards pass.

`LIVE_CONFIRM` saves/scans decisions but does not automatically place orders.

Market orders are intentionally disabled. The live client uses limit orders only.

## Recommended API-key permissions

Use Binance API keys with:

- Read permission: yes
- Spot trading permission: yes
- Withdrawal permission: no

Never enable withdrawals on an Android trading-bot API key.

## News AI

The app supports an optional NewsAPI key.

Without a NewsAPI key, the news score is neutral and the bot still works using technical + trade-memory scoring.

## Trade-memory AI

The local AI memory engine reviews previous trades stored in the local Room database.
It adjusts future confidence based on recent wins/losses and the AI scores attached to earlier trades.

This is not a guaranteed-profit model. It is a defensive adaptive scoring layer.

## Build steps

1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Run the `app` configuration on a real Android device.
4. Start with `PAPER` mode.
5. Save Binance keys only when you are ready to test live trading.
6. Use `LIVE_CONFIRM` before `LIVE_AUTO`.

## Important files

```text
app/src/main/java/com/ksp/cryptobot/
├── MainActivity.kt
├── core/
│   ├── BotController.kt
│   └── Models.kt
├── intelligence/
│   ├── AiDecisionEngine.kt
│   ├── NewsSentimentEngine.kt
│   └── TradeMemoryEngine.kt
├── execution/
│   └── ExecutionGuard.kt
├── news/
│   ├── NewsClient.kt
│   └── NewsApiClient.kt
├── exchange/
│   ├── CryptoExchangeClient.kt
│   ├── PaperExchangeClient.kt
│   └── BinanceSpotClient.kt
├── settings/
│   └── AppSettingsStore.kt
├── security/
│   └── SecureSettingsStore.kt
├── data/
│   ├── AppDatabase.kt
│   ├── AppDao.kt
│   └── Entities.kt
└── service/
    ├── BotForegroundService.kt
    └── BootReceiver.kt
```

## Important limitation

Android can still stop or restrict background execution depending on device settings. For Android-only operation, disable battery optimization for the app and keep the foreground-service notification active.

## Belgian tax note

The included tax helper is planning support only. Crypto taxation in Belgium depends on facts and classification. Keep full records and verify with a Belgian tax adviser before relying on automated tax assumptions.

## GitHub Actions build

This repository includes two workflows:

```text
.github/workflows/android-debug-apk.yml
.github/workflows/android-release-apk.yml
```

Use **Android Debug APK** first. It builds automatically on push and uploads the APK as a GitHub artifact.

Use **Android Signed Release APK** only after adding the required keystore secrets. See `GITHUB_ACTIONS_BUILD.md`.


## v0.4 Advanced UI upgrade

The app now has a polished Jetpack Compose trading interface with feature tabs for Dashboard, Bot, AI Signals, Portfolio, News, Belgium Tax, Risk, and Settings. The old single boring page has been replaced with a dark glass-style trading dashboard, action buttons, metric cards, status pills, confidence bars and structured controls. See `UI_ENHANCEMENT_V0_4.md` for details.


## v0.5 Strategy Upgrade

Added recovered multi-timeframe scalping strategy: EMA 9/21, OBV, ATR, momentum, news sentiment, previous-trade memory, and a dedicated Strategy tab.


## v0.7 Advanced Automation

See `AUTOMATION_V0_7.md` for the new multi-strategy, market-regime, smart-order, backtest, news intelligence, trade memory and advanced risk modules.
