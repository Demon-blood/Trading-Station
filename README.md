# Crypto TradeStation v4.0.6 — Guided CloudShare Setup Wizard

This is the complete current update pack.

## CloudShare setup is now guided in-app

Open:
Settings → CloudShare

The screen now has:
- Overview
- Create New
- Join Existing
- Repair / Test
- Manage

## Create New — automatic Cloudflare provisioning

The user supplies:
1. Cloudflare Account ID
2. a one-time restricted Cloudflare API token

The app then automatically:

1. verifies the token can access the account D1 API;
2. finds or creates the CloudShare D1 database;
3. initializes the D1 schema;
4. generates the first one-use registration invitation;
5. finds or creates the R2 backup bucket;
6. uploads the bundled CloudShare Worker;
7. binds D1 to the Worker as `DB`;
8. binds R2 as `BACKUPS`;
9. generates the CloudShare owner/admin secret;
10. enables the Worker on workers.dev;
11. discovers/creates the account workers.dev subdomain;
12. builds the final HTTPS Worker URL;
13. tests `/v1/health`;
14. stores the CloudShare owner token in encrypted Android storage;
15. registers this Android device automatically;
16. verifies client and intelligence endpoints;
17. performs the first sync/backfill batch;
18. enables CloudShare.

The Cloudflare provisioning token is never persisted by Crypto TradeStation and
the UI clears it after each provisioning attempt.

Required Cloudflare token permissions:
- D1 Write
- Workers R2 Storage Write
- Workers Scripts Write

EU D1/R2 jurisdiction is enabled by default but can be switched off in the wizard.

## Join Existing

The wizard walks through:
- Worker URL
- health test
- invitation code
- device registration
- client/intelligence verification
- sync interval
- historical backfill
- initial sync

## Repair / Test

Available actions:
- full CloudShare health/auth/intelligence verification
- force sync
- upload full bootstrap archive
- re-register this device with a new invitation
- disconnect this device

Disconnecting CloudShare does not stop local trading or local learning.

## Manage

Owner/admin tools include:
- owner verification
- create invitation
- list invitations
- list clients
- forget owner token

Existing lower-level client methods for revoke/enable/disable/rotate remain available
for future dedicated management UI expansion.

## Bundled backend

The pack includes the CloudShare Worker and D1 schema under:

`.cts-v4-migration/cloudshare_setup/`

The build migration copies those assets into the Android application so the setup
wizard can deploy the exact backend version expected by the Android CloudShare client.

## Included current fixes

- diagnostics integration
- full integration/lifecycle cleanup
- Kraken minimum-order sizing
- exact-preview UI + hamburger Quick Navigation
- System Diagnostics + selectable directory
- Backup/Diagnostics selected-directory fix
- GDELT global request pacing/cache
- guided CloudShare setup/provisioning
- canonical GitHub Actions workflow

## Build identity

- versionName: 4.0.6
- versionCode: 111
- applicationId: com.ksp.cryptobot
