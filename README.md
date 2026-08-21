# Crypto TradeStation v4.0.3 — System Diagnostics + Navigation Fix

This update fixes the dead **System** segment on Backup & Recovery and adds a real exportable diagnostics workflow.

## System navigation
- Backup & Recovery → **System** now opens **System Diagnostics**.
- System Diagnostics → **Backup & Recovery** navigates back.
- The previous hamburger Quick Navigation fix is included in the replacement exact-preview migration.

## Full App Diagnostics
Open:
**Settings → Backup & Recovery → System**

The new **Full App Diagnostics** card contains:
- **Select Diagnostics Folder**
- **Run & Save Full Diagnostics**

The folder picker behaves like Backup and keeps Android read/write permission for the selected folder.

The saved report includes:
- package/version/device identity
- selected non-secret trading settings
- complete system-verification output
- portfolio snapshot
- lifecycle/position snapshot
- open orders
- news provider health
- recent trades
- recent runtime/status log

The exporter explicitly excludes/redacts:
- exchange API keys/secrets
- Telegram bot token
- Discord token/webhook
- news API keys
- remote-command PIN

## Repository changes
Replace:
- `.cts-v4-migration/apply_exact_preview_ui.py`
- `.github/workflows/android-v4-build.yml`

Add:
- `.cts-v4-migration/apply_system_diagnostics_ui.py`

Canonical update identity: **v4.0.3 / versionCode 108**.
