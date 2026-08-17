# Stage 6 / M6 — Unified UI, Hardening, Recovery, Final Integration

Stage 6 completes the six-stage desktop v1.0.50 -> Android v4 migration.

## UI integration

The existing large `MainActivity.kt` is intentionally preserved. The installer makes only stable navigation/header patches:

- adds `AppTab.V4_SYSTEMS("V4 Systems")`;
- adds it to the top-level live tab row;
- routes it to `V4ControlCenterScreen()`;
- changes the visible build label from `v3.2.5 CTS` to `v4.0.0 CTS`.

The V4 Systems panel contains:

- Overview / final verification;
- CloudShare;
- Research controls;
- Recovery / diagnostics / maintenance.

## Final system verification

`V4SystemVerifier` checks:

- Room schema v11;
- package/version identity;
- APK signer continuity;
- debuggable/non-debuggable state;
- governance/execution/research persistence;
- foreground-service heartbeat;
- CloudShare state/HTTPS/registration/collective data;
- default-off research LIVE promotion;
- LIVE_AUTO acknowledgement;
- hard stop-loss and validation gates;
- storage footprint;
- 6/6 migration completion.

The installer also injects these results into the existing `runSystemFeatureVerification()` flow, so the normal System Test includes the final v4 checks.

## Backup/recovery

The existing core backup predates the v4 migration tables, so Stage 6 adds a companion v4 supplemental backup.

Included:

- governance events;
- execution-quality events;
- advanced-execution events;
- production intelligence state;
- research events;
- research strategy profiles;
- research state;
- non-secret CloudShare settings;
- non-secret Research settings.

Excluded:

- exchange keys/secrets;
- CloudShare client token;
- CloudShare admin/owner token;
- remote command PIN/tokens;
- Whale Alert API key.

The diagnostics ZIP is also redacted and intended for debugging/support.

## Maintenance/compaction

`V4MaintenanceManager` replaces Windows-specific data-management scripts with Android-native maintenance:

- age-prunes v4 operational telemetry;
- removes old uploaded CloudShare outbox rows;
- removes old CloudShare sync audit rows;
- checkpoints SQLite WAL;
- attempts VACUUM;
- reports database/internal/external app-storage footprint.

Core trades and learned profiles are not pruned by this action.

## Release/install hardening

- Final version is `4.0.0 / 105`.
- Release Gradle tasks require `signing.properties`.
- Runtime checker stores the first v4 signer SHA-256 fingerprint and warns/fails verification if later APK signer lineage changes unexpectedly.
- Debug builds are explicitly reported as WARN in the final verification screen; non-debuggable release builds report PASS.
