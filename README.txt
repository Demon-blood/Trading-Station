Replace this one file in the repository:

.github/workflows/canonicalize-v407.yml

Then commit to main and rerun:
Actions -> Canonicalize Crypto TradeStation v4.0.7 -> Run workflow

Why:
The diagnostics migration guard requires the original audited android-v4-build.yml
shape. This workflow temporarily restores that exact file from commit
9081c5aa5ed73be8f9f3a72f7e7981901af9233b, runs materialization, then restores
the current GitHub-native workflow before verification/branch creation.
