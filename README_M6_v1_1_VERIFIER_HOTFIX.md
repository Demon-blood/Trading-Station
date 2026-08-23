# CTS M6 v1.1 verifier hotfix

This hotfix changes one verifier assertion only.

Old (incorrect):
    '"This check makes no paid API call."' in controller

New:
    "This check makes no paid API call." in controller

The controller already contains that sentence inside a longer status string, so the old
assertion incorrectly required a quote character immediately before the sentence.

No Android app source, trading logic, AI routing, risk logic, M5 economics, execution
logic, API pricing, or workflow behavior is changed.

Apply:
1. Copy this ZIP into the repository root, preserving paths.
2. Replace tools/verify_m6_selective_ai.py.
3. Commit to main.
4. Rerun: Actions -> M6 Selective Luna-Sol AI Router.
