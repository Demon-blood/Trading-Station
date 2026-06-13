# Crypto TradeStation v2.8.5 — Backup Folder Picker

Implemented:
- Backup / Restore now has a Select Folder button.
- Uses Android Storage Access Framework folder picker.
- Stores the selected content:// folder URI as the backup destination.
- Takes persistable read/write URI permission so future exports can reuse the selected folder.
- Export now supports content:// tree URIs and writes the backup through Android's ContentResolver.
- Manual path entry remains available for app-writable file paths.

Notes:
- Android does not allow unrestricted direct file-path writes to every public folder.
- The folder picker is the recommended method because it grants write permission safely.
