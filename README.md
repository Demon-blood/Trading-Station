# M16 verifier v1.1 compatibility hotfix

Verifier-only compatibility update for M19.

M16 originally verified the literal runtime condition:

`it.makerFillProbability < 0.60`

M19 tightened this to:

`it.makerFillProbability < governedFillThreshold`

with:

`.coerceIn(0.45, 0.60)`

v1.1 accepts either representation while preserving the original M16 maximum fill-probability threshold and the mandatory adverse-selection gate `< 0.65`.

Runtime changes: NONE.
