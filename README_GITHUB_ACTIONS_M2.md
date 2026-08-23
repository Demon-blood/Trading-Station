# Crypto TradeStation — GitHub Actions M2

This package is the GitHub-native replacement for the earlier PowerShell-oriented M2 pack.

## Files to add/replace in the repository

Copy the contents of this ZIP into the repository root, preserving paths:

- `.github/workflows/canonicalize-v407.yml`
- `.github/workflows/android-canonical-build.yml`
- `.github/workflows/android-v4-build.yml` **replaces the current legacy generator**
- `tools/canonicalize_v407.py`
- `tools/verify_canonical_v407.py`

Commit those files to `main`.

The bootstrap commit itself does not touch `app/`, so the new canonical Android build's
push path filter will not run yet.

## Then run entirely from GitHub

Go to:

**GitHub → Actions → Canonicalize Crypto TradeStation v4.0.7 → Run workflow**

The action will:

1. check out the repository;
2. run the current v4 source-generation chain in the same order used by the old build;
3. freeze the resulting v4.0.7 / versionCode 112 source into `app/`;
4. restore `.cts-v4-migration` so it remains historical/audit-only;
5. add `app/.cts-canonical-v407.json`;
6. run the consolidated source-contract verifier;
7. compile Kotlin;
8. run unit tests;
9. assemble a debug APK;
10. push a `migration/canonical-v4.0.7-<run>` branch;
11. attempt to open a PR into the branch from which the workflow was run;
12. upload the APK and migration logs as Actions artifacts.

If GitHub repository settings do not allow Actions to create PRs, the branch is still
pushed successfully and the workflow prints the exact branch to use for the PR.

## After the PR is merged

The normal build becomes:

**GitHub → Actions → Crypto TradeStation Canonical Android APK**

There are no `apply_*.py` source-mutation stages in that workflow.

## Important safety boundary

M2 deliberately does not change runtime trading behavior. It is an architecture/build
cleanup before the next milestone.

M3 will harden Android as a 24/7 standalone host:
- foreground-service classification
- process/reboot recovery
- exchange reconciliation after restart
- network transition recovery
- background/battery health checks
