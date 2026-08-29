# CTS M10 v1.1 — M9 verifier compatibility hotfix

M10 intentionally strengthens M9 LIVE authorization:

M9:
    strategyGovernance.productionAuthorized

M10:
    strategyGovernance.productionAuthorized
    && championHealth.liveEntryAuthorized
    && championHealth.championAfter == governedStrategy

The original M9 verifier checked the exact M9 source strings, so it reported
three false failures after M10 rewrote those gates.

This hotfix updates only the M9 static verifier. It accepts:
- the original M9 direct gate, or
- M10's stricter composite gate.

It still requires PAPER trials to remain explicitly allowed and verifies that
the M10 composite authority includes M9 production authorization.

No Android runtime code or trading logic is changed.

Apply:
1. Replace tools/verify_m9_champion_challenger.py on main.
2. Commit it.
3. Rerun Actions -> M10 Champion Degradation Rollback.
