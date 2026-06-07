# v0.8 Multi-Exchange Belgium Mode

This version removes the Binance-only live-trading assumption and adds a provider-aware execution layer.

## Providers

- PAPER: simulation only
- BINANCE_READ_ONLY: market data/signals only for Belgium mode; no spot order placement
- KRAKEN: connector slot for compliant Kraken API spot trading accounts
- COINBASE_ADVANCED: connector slot for compliant Coinbase Advanced API spot trading accounts
- BITVAVO: connector slot for compliant Bitvavo API spot trading accounts
- MANUAL: app creates trade plans; user places the order manually

## Safety behavior

The bot blocks automatic live order placement when:

- manual execution mode is enabled
- the selected provider is read-only/manual-only
- exchange credentials are missing
- normal execution guards fail

Binance is intentionally configured as read-only in Belgium mode. Do not try to bypass Binance or Belgian restrictions with VPNs, false residency details, borrowed accounts, or other evasion methods. Use a provider that legally supports your Belgian account, or keep the app in paper/manual mode.

## What changed

- Added `ExchangeProvider` to `BotSettings`
- Added multi-exchange secure credential storage
- Added provider selector in Settings
- Added Binance read-only connector
- Added Kraken/Coinbase/Bitvavo connector placeholders with legal capability checks
- Added manual execution mode
- Updated BotController to select the provider and block unsupported live orders

## Next production step

Before enabling real live trading on Kraken/Coinbase/Bitvavo, verify the selected exchange legally supports Belgian API spot trading for your account, then complete and test that exchange's private order signing implementation on test/sandbox mode where available.
