# Crypto TradeStation v4.0.7 — True Step-by-Step CloudShare Assistant

This replaces the v4.0.6 tabbed CloudShare configuration screen with a real guided setup assistant.

## Create your own CloudShare

The screen now walks through one task at a time:

1. Welcome / what will be created
2. Create and verify a temporary Cloudflare API token
3. Find the 32-character Cloudflare Account ID and verify D1/R2/Workers permissions
4. Review simple defaults (resource names stay hidden under Advanced)
5. Automatic provisioning with live per-component status
6. Final CloudShare verification and Finish

The assistant contains buttons to open the Cloudflare API Tokens page and Cloudflare dashboard, and it does not let setup advance until the relevant verification passes.

## Join existing

Join is also sequential: Worker URL → health check → invite → registration/auth verification → sync/backfill options → initial sync → complete.

## Security

The temporary Cloudflare provisioning token remains only in Compose memory, is never written to app settings or diagnostics, and is cleared after provisioning. The generated CloudShare owner token continues to use encrypted app storage.

## Existing fixes retained

This full pack also retains the lifecycle/integration cleanup, Kraken minimum-order fix, exact-preview UI/navigation, System Diagnostics and directory fixes, GDELT rate-limit fix, CloudShare Worker/D1/R2 backend assets, and canonical GitHub Actions workflow.

## Build identity

- versionName: 4.0.7
- versionCode: 112
- applicationId: com.ksp.cryptobot
