# M22 v1.2 — GitHub Workflow-Permission Bootstrap Hotfix

The M22 run successfully reached branch creation, committed 11 files, and then GitHub
rejected the push because the generated commit modified a root Actions workflow:

`refusing to allow a GitHub App to create or update workflow
.github/workflows/android-release-apk.yml without workflows permission`

This is a GitHub App permission boundary, not a Kotlin/test/security failure.

The connected ChatGPT GitHub integration is subject to the same restriction and returns
HTTP 403 for root workflow writes, so the workflow files cannot be fixed by either App
token.

## Correct delivery model

Root workflow files are configuration/bootstrap code and must already exist on `main`
before the M22 Action runs.

This package therefore contains three root workflow files that must be committed directly
to `main` using GitHub's web editor or another credential that has workflow-write
permission:

- `.github/workflows/m22-security-release-integrity.yml`
- `.github/workflows/android-release-apk.yml`
- `.github/workflows/osv-scanner.yml`

It also contains updated branch-safe tooling:

- `tools/apply_m22_security_release_integrity.py`
- `tools/verify_m22_security_release_integrity.py`

The M22 Action now:

1. verifies the protected workflows are already present on `main`;
2. applies only runtime/test M22 source changes;
3. generates `gradle/verification-metadata.xml`;
4. scans `releaseRuntimeClasspath` as the blocking APK-runtime OSV gate;
5. separately audits the full Gradle/tooling inventory without hiding it;
6. compiles/tests/builds the APK;
7. refuses to continue if `.github/workflows/**` became dirty;
8. stages only `app/**` and `gradle/verification-metadata.xml`;
9. pushes the milestone branch without requiring GitHub `workflows` permission.

## Important

Do not use **Re-run jobs** on the failed run. It is tied to the old workflow commit.

After committing this v1.2 bootstrap to `main`, start a NEW:

**Actions → M22 Security API-Key Permissions & Release Integrity → Run workflow → main**
