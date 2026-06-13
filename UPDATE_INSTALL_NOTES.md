# Updating without uninstalling

Android only allows an APK to update an existing app when:
1. The package name/applicationId is identical.
2. The new APK versionCode is higher.
3. The signing certificate is identical.

Older GitHub Actions debug builds may have used a temporary debug signing key, so Android sees the new APK as a different signer and refuses to update. That is why uninstall was required.

This build adds a stable project-local debug signing key:
- keystore/cts_debug_update_key.jks
- alias: ctsdebug

You may need one final uninstall to switch from the old temporary signer to this stable signer.
After that, future builds from this project should install as updates.
