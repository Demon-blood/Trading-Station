# Crypto TradeStation — M22 Security, API-Key Permissions & Release Integrity

M22 is a production-security milestone. It adds no alpha, does not increase position size,
and does not relax M12–M21 execution, authority, recovery, risk, strategy-truth or
economics gates.

## 1. Kraken API-key least privilege

M22 calls Kraken Spot REST:

`POST /0/private/GetApiKeyInfo`

before LIVE Kraken startup and periodically while LIVE is running.

The current Kraken API documents that this endpoint itself requires no API-key permission
and returns the exact permissions assigned to the calling key.

### Hard-blocked dangerous permissions

A Kraken key is rejected for LIVE trading if any of these are present:

- `withdraw-funds`
- `add-withdraw-address`
- `update-withdraw-address`

The bot has no legitimate need to withdraw funds or manage withdrawal addresses.

### Required LIVE permissions

The current CTS Kraken execution stack actually uses:

- `query-funds`
- `query-open-trades`
- `query-closed-trades`
- `modify-trades`
- `close-trades`
- `create-ws-token`

If one is missing, LIVE is blocked rather than partially operating with an unknown
recovery/execution capability.

Other non-dangerous permissions are reported as unnecessary extras but do not themselves
create trading authority.

An empty IP allowlist is reported as a warning, not a mobile-runtime hard block, because
a phone may use changing carrier/Wi-Fi public addresses.

### Runtime freshness

A successful permission assessment is valid for at most 15 minutes.

The foreground service re-inspects server-side permissions every 10 minutes.

A changed API key, stale inspection, key expiry, network loss, failed inspection, dangerous
permission, or missing required permission makes new BUYs fail-closed.

Protective/exit SELL risk reduction remains available.

## 2. Android secret storage

The existing store already used AES/GCM with a key held in Android Keystore.

M22 adds:

- AES-GCM associated data (AAD) bound to each logical secret name:
  `CTS_SECURE_V2:<secret-name>`
- synchronous `SharedPreferences.commit()` with checked success;
- synchronous checked secret deletion;
- backward-compatible migration of legacy no-AAD ciphertext on first successful read.

A v2 ciphertext cannot simply be moved from one secret slot to another without GCM
authentication failing.

The app continues to use:

- `android:allowBackup="false"`
- `android:usesCleartextTraffic="false"`

## 3. Plaintext secret backup removal

Prior code could materialize exchange API keys, exchange secrets, AI keys, Telegram /
Discord credentials, webhooks and the remote-command PIN into `secureBackupMap()`.

M22 changes ordinary backup/export behavior to:

`secureBackupMap() = emptyMap()`

Normal backups therefore no longer export credentials in plaintext. Credentials should be
re-entered after a normal restore.

The legacy restore method remains only to accept an explicit user-supplied migration map;
the application no longer generates that plaintext map itself.

## 4. Gradle dependency integrity

The M22 Action creates:

`gradle/verification-metadata.xml`

using SHA-256 verification metadata generated from the current resolved dependency graph.

Subsequent compile/test/APK steps run normally with that file present, so Gradle checks
downloaded dependency artifacts against the committed hashes.

## 5. Known-vulnerability scanning

M22 adds a permanent OSV-Scanner workflow pinned to the immutable v2.5.0 workflow commit.

It scans:

`gradle/verification-metadata.xml`

on:

- pull requests to `main`;
- merge queue;
- pushes to `main`;
- a weekly scheduled scan.

The M22 milestone Action also performs an immediate OSV scan after generating the metadata.

## 6. Production release signing

The existing committed CTS debug update key is intentionally retained for debug APK update
compatibility. It is not a production trust root.

The signed release workflow now requires these GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`
- `ANDROID_EXPECTED_CERT_SHA256`

The release workflow:

1. restores the production keystore with restrictive file permissions;
2. refuses to build without Gradle dependency verification metadata;
3. builds the minified signed release APK;
4. runs `apksigner verify --verbose --print-certs`;
5. compares the actual signer SHA-256 against `ANDROID_EXPECTED_CERT_SHA256`;
6. rejects a release signed by the committed debug update certificate;
7. calculates an APK SHA-256;
8. uploads APK + signing/hash evidence;
9. destroys temporary keystore/signing.properties files.

M22 itself does not require the production-signing secrets to exist yet. They must be
configured before the eventual production signed-release workflow is used.

## Run M22

Copy this package into repository `main`, preserving paths, and commit the bootstrap files.

Then run:

**Actions → M22 Security API-Key Permissions & Release Integrity → Run workflow → main**

Expected branch:

`milestone/m22-security-release-integrity-<run-number>`

The Action performs:

M21 prerequisite
→ apply M22
→ generate Gradle SHA-256 verification metadata
→ OSV dependency scan
→ verify M22
→ verify M21 … M3
→ canonical verification
→ checksum-enforced Kotlin compile
→ unit tests
→ APK assembly
→ controlled diff
→ milestone branch
→ PR

After M22 passes and merges, the roadmap moves to:

**M23 — Observability, Diagnostics & Remote Operations**
