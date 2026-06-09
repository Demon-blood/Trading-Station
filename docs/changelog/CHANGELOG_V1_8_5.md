# Crypto TradeStation v1.8.5 — Performance Lab Compile Fix

Fixes:
- Added the missing AppTab.PERFORMANCE branch to the main exhaustive when(currentTab) block.
- Keeps the Performance Lab tab and strategy promotion dashboard from v1.8.4.
- Version label updated to v1.8.5 CTS.

Reason:
Kotlin requires enum-based when expressions to cover every AppTab value. v1.8.4 added PERFORMANCE to the enum and tab row, but the main screen switch was missing the branch.
