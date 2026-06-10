# Crypto TradeStation v2.7.0 — Remote Command Center

Implemented:
- Telegram remote command polling.
- Discord bot-token command polling.
- Remote command PIN.
- Remote command settings UI under Notifications / Remote Alerts.
- Command processing in the Android foreground service loop.
- Commands for status, settings, portfolio, positions, orders, scan, execute, start, stop, pause, resume, mode changes and selected setting edits.
- System Test reports the Remote Command Center state.
- Backup/export includes non-secret remote command settings.

Supported commands:
- /cts <PIN> status
- /cts <PIN> settings
- /cts <PIN> portfolio
- /cts <PIN> positions
- /cts <PIN> orders
- /cts <PIN> scan
- /cts <PIN> execute
- /cts <PIN> start
- /cts <PIN> stop
- /cts <PIN> pause
- /cts <PIN> resume
- /cts <PIN> mode PAPER|LIVE_CONFIRM|LIVE_AUTO
- /cts <PIN> set max_position 10
- /cts <PIN> set max_buy BTCEUR 95000
- /cts <PIN> set score 75

Security:
- PIN is stored in encrypted local storage.
- API keys, Discord bot tokens, Telegram tokens and the remote command PIN are not exported.
- Remote LIVE_AUTO commands are blocked unless explicitly enabled.
- The Android foreground service must be running to receive remote commands.
