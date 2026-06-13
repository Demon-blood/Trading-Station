# Updating without uninstalling

Android only allows an APK to update an existing app when:
1. applicationId is identical.
2. versionCode is higher.
3. signing certificate is identical.

This project now uses a stable debug update key:
- keystore/cts_debug_update_key.jks
- fallback: keystore/cts_debug_update_key.jks.b64
- fallback: app/keystore/cts_debug_update_key.jks.b64

GitHub Actions will recreate the keystore from the .b64 fallback if the .jks file is missing.

You may need one final uninstall if your currently installed app was signed with an older temporary debug key.
After installing this stable-signed build once, future builds signed with the same key should update normally.
