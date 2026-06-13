# Crypto TradeStation v2.9.5 — Restore File Picker

Changed:
- Load / Restore Backup no longer asks the user to paste backup text or type a local file path.
- Added Android file picker for restore.
- Restore workflow is now:
  1. Select Backup File
  2. Restore Selected
- The app stores read permission for the selected backup URI when Android grants it.
- Existing backup folder picker for export remains unchanged.

Why:
- Backup restore should use Android's Storage Access Framework, just like backup export.
- This avoids manual text/path entry and works better with Android scoped storage.
