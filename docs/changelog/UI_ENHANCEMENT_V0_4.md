# KSP Crypto AI v0.4 UI Upgrade

This release changes the app from a basic control screen into a structured trading command center.

## New app layout

The Compose UI is now organized into feature tabs:

- Dashboard
- Bot
- AI Signals
- Portfolio
- News
- Belgium Tax
- Risk
- Settings

## Visual design

The app now uses a custom dark trading theme with:

- gradient background
- glass-style cards
- rounded panels
- status pills
- colored risk/action states
- metric cards
- polished action buttons
- progress bars for AI confidence and allocation

## Feature screens

### Dashboard
Central command view with quick scan, execute pass, service start/stop, metrics, and top AI decisions.

### Bot
Trading mode selection, live acknowledgement, symbol universe and service controls.

### AI Signals
Expanded AI decision cards showing technical score, news score, trade-memory score, confidence, and trade permission.

### Portfolio
Structured portfolio allocation view. It is ready for live Binance balance integration.

### News
News sentiment module controls and planned feed categories.

### Belgium Tax
Tax-aware sell guard view, realized-gain tracking concept, and export structure.

### Risk
Position size, daily loss, max trades, spread control, tax optimization and BTC/ETH restriction.

### Settings
Secure Binance and NewsAPI key input plus AI module toggles.

## Notes

The UI is designed to compile as a single `MainActivity.kt` replacement to keep this starter project simple. Later versions can split each screen into its own file under `ui/screens/` once the app grows.

Live trading remains guarded. Keep withdrawal permission disabled on exchange API keys.
