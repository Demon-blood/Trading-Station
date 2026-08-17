# Crypto TradeStation Android v4.0.0 — Final Stage 6 Pack

This is the **final cumulative Android v4 migration overlay** for `Demon-blood/Trading-Station`. It contains Stages 1 through 6 and upgrades the current Android 3.2.5 source (or any previous validated v4 milestone) to the final v4 integration.

## Apply on Windows

```powershell
python .\apply_milestone6.py C:\path\to\Trading-Station
cd C:\path\to\Trading-Station
.\gradlew clean :app:assembleDebug
```

For a release build, configure `signing.properties` and run:

```powershell
.\gradlew clean :app:assembleRelease
```

The final build hardening refuses release packaging when `signing.properties` is missing. Debug builds continue to use the project's stable CTS debug update key.

## Final target

- versionName: `4.0.0`
- versionCode: `105`
- Room database: `11`
- explicit migrations: `6 -> 7 -> 8 -> 9 -> 10 -> 11`
- no `fallbackToDestructiveMigration()`
- CloudShare protocol: `2026-07-26`
- migration stages complete: `6 / 6`

## Stage 6 adds

- top-level **V4 Systems** Android tab;
- Overview, CloudShare, Research, and Recovery sub-panels;
- final v4 verification integrated into the existing System Test;
- runtime install/signing-lineage verification;
- release-signing Gradle guard;
- complete Stage-5 research controls, including secure Whale Alert key storage;
- complete CloudShare sync interval/backfill/admin/client controls;
- v4 supplemental backup/restore for governance, execution-quality, advanced-execution, production-state, research-event, research-profile, and research-state data;
- redacted v4 diagnostics ZIP export;
- native v4 operational data compaction/storage audit;
- final visible UI version `v4.0.0 CTS`.

## Recovery model

Use two files for a full v4 device migration:

1. **Core Full Backup** from the existing CTS Backup/Restore system — core settings, trades, positions, learning profiles, etc.
2. **v4 Supplemental Backup** from V4 Systems -> Recovery — v4 governance/execution/research history and non-secret CloudShare/research settings.

The supplemental backup deliberately does **not** export CloudShare client tokens, CloudShare owner/admin tokens, exchange credentials, remote-control secrets, or the Whale Alert API key. Re-enter/rejoin those secrets on the destination device.

CloudShare downloaded intelligence is not required in the supplemental backup because it can be re-downloaded from the Worker. Uploaded outbox payloads are already erased after successful upload.

## Maintenance

`Compact v4 Operational Data` prunes operational/research telemetry older than 365 days by default, old successfully uploaded CloudShare outbox rows, and old CloudShare audit rows. It does **not** delete core trade history or learned profiles. It then checkpoints the WAL and attempts SQLite `VACUUM`.

## Safety invariants

1. M5 research executes before M3 production governance.
2. M3 anomaly, safe-mode, kill-switch and risk-budget gates remain authoritative.
3. M4 post-balance capital ceiling remains authoritative and cannot be increased by later intelligence.
4. Research cannot alter/block an upstream SELL exit.
5. Research-created LIVE entries remain disabled by default.
6. CloudShare collective evidence remains bounded and cannot bypass live safety gates.
7. Supplemental backups exclude migration secrets.
8. Release packaging requires explicit release signing configuration.

See `docs/MILESTONE_6.md`, `docs/PORT_ROADMAP.md`, and `VALIDATION_REPORT_M6.json`.
