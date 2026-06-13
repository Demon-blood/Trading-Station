# Crypto TradeStation v2.9.2 — Compile Fix

Fixes:
- Removed misplaced Portfolio position-guard UI code from AllocationRow.
- Restored Portfolio dust/guard UI inside LiveBalanceRow where asset and position exist.
- Added missing full-backup restore helper functions referenced by restoreFullLocalBackup.
- Normalized exportFullLocalBackupToFile indentation.

Build log fixed:
- MainActivity unresolved asset/position references near AllocationRow.
- BotController unresolved restoreSignalsFromSection and related restore helper functions.
