# Crypto TradeStation v2.8.9 — Stable Update Signing

Fixes:
- Debug APKs from GitHub Actions now use a project-local stable debug signing key.
- This allows Android to update over the existing app as long as:
  - applicationId remains com.ksp.cryptobot
  - versionCode is higher
  - the previously installed app was signed with the same stable key

Important:
- If the app currently installed on the phone was signed by GitHub Actions' temporary debug key, Android will still require one final uninstall before installing this version.
- After installing v2.8.9 once, future debug builds using this same keystore should update normally without uninstalling.
- For production distribution, use a private release keystore through signing.properties or GitHub Secrets. Do not rely on this debug key for public release.
