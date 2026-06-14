# Crypto TradeStation v3.2.1 — Backup Folder Picker SAF Fix

Fixes:
- Custom backup folder export now uses Android Storage Access Framework correctly.
- The selected tree URI is converted to a writable parent document URI before calling DocumentsContract.createDocument.
- Backup output stream now flushes after writing.
- Error messages now tell the user to re-select the folder if Android write permission is missing.

Why:
- Android's folder picker returns a tree URI.
- createDocument must receive a document URI built from that tree URI, not the raw tree URI itself.
