# Crypto TradeStation v2.8.6 — Picker-Only Full Backup

Changed:
- Removed manual backup directory path entry from Backup / Restore.
- Backup destination now uses Android folder picker only, with a Use Default fallback.
- Selected folder is stored as a content:// URI with persistable write permission.

Full backup expansion:
- Backup now includes settings, secure credentials/tokens/PINs, trades, signals, AI decisions, tax lots, tax report rows, open positions, learned symbol profiles, learned strategy profiles, learned hold profiles, learning feature snapshots, and self-learning audit rows.
- Restore now restores these structured sections when present.
- Replace Existing Local Data now clears all backed-up Room tables before restore, not only trades/positions/tax rows.

Security:
- Full backup includes Kraken API keys/secrets, Telegram bot token/chat ID, Discord webhook/bot token/channel ID, news API key, and remote command PIN because this build is intended to restore literally everything.
- Keep backup files private and do not share them.
