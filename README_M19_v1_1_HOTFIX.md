# M19 v1.1 STRONG_AVOID compile hotfix

Fixes the single Kotlin compile error from M19 run #2 by making `LearningMonotonicPolicy.action()` exhaustive for the repository's existing `SignalAction.STRONG_AVOID` enum member.

`STRONG_AVOID` remains terminal and can never be promoted by learning.

Runtime expansion: NONE.
