# Crypto TradeStation Android v4.0.0 — Cumulative M1→M6 + Research Handoff Truth Payload

This directory is the cumulative migration payload consumed by the repository's existing GitHub Actions workflow.

## Canonical build path

The authoritative build is GitHub Actions:

```text
Actions → Crypto TradeStation v4 Build
```

The workflow checks out the repository and runs:

```bash
python3 .cts-v4-migration/apply_milestone6.py "$GITHUB_WORKSPACE"
```

before Kotlin/KSP compilation, unit tests and APK assembly. The repository does not need a committed Gradle wrapper for this workflow because CI provisions Gradle 8.9.

## Final target

- `versionName = 4.0.0`
- `versionCode = 105`
- Room schema `11`
- explicit migrations `6→7→8→9→10→11`
- no destructive Room fallback
- CloudShare protocol `2026-07-26`
- original migration stages complete `6/6`

## Additional truth-automation layer

The cumulative payload now also contains:

- desktop-parity strategy/research behavior;
- professional/practitioner variants as a separate auditable layer;
- all 15 supplied research-handoff assets;
- 31 versioned handoff strategy records evaluated automatically;
- explicit proprietary/source-unknown blocking;
- market-data integrity gates;
- account/pair Kraken fee-tier retrieval;
- `ordermin`, `costmin` and `tick_size` enforcement;
- source cost/risk sizing and correlated-risk cap;
- strategy-ID-specific empirical promotion using realized outcomes, walk-forward and Monte Carlo;
- truthful pending PAPER LIMIT/STOP/TP execution and realized P&L;
- accepted-but-unfilled LIVE order accounting;
- source-specific stop/target persistence;
- exchange-level Kraken protective-stop attachment/verification;
- emergency `UNPROTECTED_POSITION` fail-safe;
- stop-aware partial/managed exits with protection restoration.

## Truth gate

The supplied research freeze currently contains **zero** positive `live_truth_gate=PASS` rows. The payload preserves that constraint. Positive handoff strategies can automatically PAPER trade and accumulate evidence, but they do not become positive LIVE entries until a deliberate future source-reverification update marks the source gate PASS and the empirical gate also passes.

Protective/risk-reducing actions remain automatic when their own controls and safety conditions permit.

## Validation

Run the payload-only validation anywhere Python is available:

```bash
python3 .cts-v4-migration/validate_handoff_truth.py
```

GitHub Actions remains the final Android SDK/KSP/Compose/APK validation gate.

See `docs/RESEARCH_HANDOFF_TRUTH.md` and the upload-patch audit for the full boundary.
