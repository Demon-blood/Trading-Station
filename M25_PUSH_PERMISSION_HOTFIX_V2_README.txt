M25 PUSH-PERMISSION HOTFIX V2

WHY RUN #1 FAILED
-----------------
The M25 Action created a commit that included:
  .github/workflows/android-canonical-build.yml

The repository token is a GitHub App token without Workflows write permission.
GitHub therefore rejected the entire branch push.

No M25 implementation branch was published.

WHAT V2 CHANGES
---------------
1. Canonical roomSchema=11 -> roomSchema=12 is made in YOUR MANUAL MAIN COMMIT.
2. The M25 Action no longer edits or stages any .github/workflows file.
3. The generated milestone branch contains APP FILES ONLY.
4. The workflow explicitly fails if any workflow file becomes dirty during generation.

HOW TO APPLY
------------
A. Extract this ZIP over the Trading-Station repo root.
B. Double-click:
     RUN_M25_PUSH_PERMISSION_HOTFIX_V2.bat
C. Commit ALL resulting hotfix changes to main.
D. Run GitHub Actions:
     M25 Final RC Burn-In Controlled LIVE
   again from main.

EXPECTED NEXT BRANCH
--------------------
Because run #1 failed, the next successful run number will normally create:
  milestone/m25-final-rc-2

EXPECTED APP-ONLY M25 COMMIT
----------------------------
The generated branch should contain exactly these four app files:
  app/src/main/assets/release/m25_evidence_template.json
  app/src/main/assets/release/m25_release_candidate_runbook.md
  app/src/main/java/com/ksp/cryptobot/release/M25ReleaseReadiness.kt
  app/src/test/java/com/ksp/cryptobot/release/M25ReleaseReadinessTest.kt

The canonical workflow fix is already on main from the manual bootstrap commit,
so the Action does not need permission to push workflow changes.
