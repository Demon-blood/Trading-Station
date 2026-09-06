M25 ACTIONS-ONLY V3

GOAL
----
After this bootstrap is present on main, M25 implementation is performed by GitHub
Actions only. No local BAT, PowerShell, Python, git patch, or local branch operation
is part of the milestone flow.

WHY A SEPARATE TOKEN IS REQUIRED
--------------------------------
GitHub's built-in GITHUB_TOKEN is a GitHub App installation token and cannot be
authorized to modify files under .github/workflows.

M25 intentionally corrects:
  .github/workflows/android-canonical-build.yml
  roomSchema=11 -> roomSchema=12

Therefore the Action must use a repository-scoped token that has Workflows write.

ONE-TIME GITHUB SETUP
---------------------
Create a fine-grained Personal Access Token for ONLY:
  Demon-blood/Trading-Station

Repository permissions:
  Contents       Read and write
  Workflows      Read and write
  Pull requests  Read and write
  Actions        Read and write

Then store it as this repository Actions secret:
  CTS_AUTOMATION_TOKEN

Do NOT paste the token into ChatGPT, commits, logs, source files, or workflow inputs.

AFTER THAT
----------
Actions -> M25 Final RC Burn-In Controlled LIVE -> Run workflow -> main

The workflow itself will:
  - verify M24.1
  - apply M25 app files
  - correct canonical roomSchema 11 -> 12
  - run the complete regression chain
  - compile Kotlin
  - run unit tests
  - build APK
  - create milestone/m25-final-rc-<run>
  - push the workflow-containing commit with CTS_AUTOMATION_TOKEN
  - create the M25 PR

There is no local patch step.

NOTE ABOUT BOOTSTRAPPING
------------------------
The current repository workflow cannot magically grant itself Workflows write.
The V3 workflow file itself must first exist on main, and CTS_AUTOMATION_TOKEN must
exist as a repository secret. This is the one-time trust bootstrap. After that,
workflow-file changes can be performed from GitHub Actions.
