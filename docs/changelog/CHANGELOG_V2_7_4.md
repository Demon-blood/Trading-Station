# Crypto TradeStation v2.7.4 — Custom Backup Directory

Implemented:
- Custom backup directory field in Settings → Backup / Restore.
- Backup directory path is saved in local settings.
- Export All Settings + Data writes to the custom directory when it is writable.
- If the custom path is invalid or not writable, export falls back to the default app backup folder.
- Export result now shows file path, directory, requested directory and file size.

Android note:
- Android apps cannot freely write to every public folder on modern Android without storage access handling.
- This version supports writable app-accessible paths and safely falls back to the app external files backup folder.
