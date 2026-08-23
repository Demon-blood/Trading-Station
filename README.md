# Crypto TradeStation — Milestone 2: Canonical Source Freeze

This converts the current v4.0.7 build from:

`checked-in source -> Python migration/patch chain -> Kotlin compile`

into:

`committed canonical Kotlin source -> validation -> Kotlin compile`

## Materialization order

The installer reproduces the current canonical CI order exactly:

1. diagnostics integration fix
2. cumulative milestone 6
3. full integration / UX cleanup
4. Kraken minimum-order sizing fix
5. approved preview UI
6. system diagnostics / export UI
7. GDELT pacing / cache fix
8. CloudShare setup wizard
9. CloudShare guided assistant

It freezes the resulting `app/` tree, sets v4.0.7 / versionCode 112, commits the JUnit dependency, restores `.cts-v4-migration/` to its original historical state, and removes all migration `apply_*.py` calls from the build workflow.

## Safety boundary

This milestone intentionally does **not** redesign strategies, Kraken execution, PAPER/LIVE/SHADOW semantics, risk rules, AI decisions, Room schema, or Android foreground-service behavior.

## Apply

```powershell
powershell -ExecutionPolicy Bypass -File .\APPLY_M2_CANONICALIZE.ps1 -RepoPath "C:\path\to\Trading-Station"
```

The tool refuses to overwrite uncommitted changes under `app/` or `.cts-v4-migration/`.

## Backup and rollback safety

Before modifying the project it creates, beside the repository:

`Trading-Station_before_M2_canonicalize_YYYYMMDD_HHMMSS.zip`

and a materialization log. If a transformation or verification fails, `app/`, `.cts-v4-migration/`, and the canonical workflow are automatically restored.

## After it passes

Review:

```powershell
git status --short
git diff --stat
```

Then commit/push and let GitHub Actions compile and run unit tests.

## Expected result

The workflow contains no:

`python3 .cts-v4-migration/apply_...`

calls. The v4.0.7 files that CI used to generate now exist directly under `app/`.

## Next milestone

After the frozen source passes CI, harden Android for 24/7 autonomous hosting: foreground-service classification, process/reboot recovery, network transition reconciliation, and battery/background configuration health checks.
