# Crypto TradeStation v2.2.1 — Export Crash Fix

Fixes:
- Full backup export no longer loads the entire backup into an editable text field.
- Full backup is now written to a local app file under the app's external files backup folder.
- UI shows the saved file path and a small preview only.
- Backup text field is now read-only to avoid large text recomposition crashes.

Why:
- Large trade/learning/tax databases can create very large backup text.
- Rendering that full text inside Compose OutlinedTextField can crash the app.
- Writing the full backup to file and showing only a preview is safer.
