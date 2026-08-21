# Crypto TradeStation v4 — Full Integration Cleanup Hotfix 2

## Confirmed GitHub Actions failure

The Action stopped in `apply_full_integration_cleanup.py` with:

`[CTS full integration cleanup] lifecycle entry-price anchor changed`

## Root cause

`apply_milestone6.py` re-applies the Milestone 4 lifecycle truth patches before this cleanup runs.
Milestone 4 legitimately changes the old entry-price fallback block into a
`previousEntry / confirmedBuyEntry / PENDING_ENTRY` block.

The cleanup migration still expected the older pre-M4 text, so it aborted even though the
lifecycle source was valid and newer.

## Fix

Replace only:

`.cts-v4-migration/apply_full_integration_cleanup.py`

The migration now accepts all three valid states:

1. original legacy entry-price block;
2. Milestone 4 pending-entry confirmed-fill block;
3. already-upgraded cleanup block.

It preserves the Milestone 4 `PENDING_ENTRY` confirmed-fill rule while also adding the
reopened-position / latest-BUY lifecycle reset.

No workflow or exact-preview UI file needs changing for this specific failure.
