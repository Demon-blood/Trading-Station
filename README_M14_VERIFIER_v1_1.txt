M14 verifier v1.1

Verifier-only hotfix. No runtime/trading changes.

Root cause:
The v1 verifier required the literal substring:

    blocked("LEASE_SCHEMA_UPGRADE_REQUIRED"

but EngineAuthorityLeaseManager.kt intentionally formats the call as:

    return blocked(
        "LEASE_SCHEMA_UPGRADE_REQUIRED",
        ...
    )

The runtime is correct and fails LIVE closed for both:
1. M14 CloudShare health/schema probe failure.
2. Remote engine lease schema != v2.

v1.1 validates the semantic contract instead of one-line formatting and adds
debug output if this check ever fails again.

Replace exactly:
tools/verify_m14_dms_authority_fencing.py

Commit to main and launch a NEW M14 workflow from main.
The verifier log must show:
INFO | M14 verifier revision v1.1
