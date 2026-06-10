# Crypto TradeStation v2.7.3 — Backup Restore Import

Implemented:
- Load / Restore Backup screen inside Backup / Restore.
- Restore from pasted full backup text.
- Restore from local backup file path shown by the export function.
- Optional replace-existing-local-data toggle.
- Settings restore.
- Trade journal restore.
- Open lifecycle positions restore.
- DAO restore helpers using REPLACE conflict behavior.

Security:
- API keys, Telegram bot token, Discord webhook/token and remote command PIN are intentionally not restored.
- Secrets must be re-entered manually after reinstall/moving phones.

Limitations:
- Legacy backup sections exported as Kotlin toString rows, such as learned profiles and tax reports from older versions, are preserved in the backup file but skipped by this importer unless future structured export sections are added.
