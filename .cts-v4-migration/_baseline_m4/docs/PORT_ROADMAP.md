# Desktop v1.0.50 -> Android v4 roadmap

The migration is split into **6 stages**.

## Completed

### Stage 1 / M1 — CloudShare + data foundation
- Worker client/auth/admin
- deterministic event/batch protocol
- persistent outbox/retry/download cursor
- Android Keystore credentials
- foreground-service sync
- non-destructive Room migration 6 -> 7

### Stage 2 / M2 — Collective learning
- shared aggregate generation and identity
- historical evidence backfill
- collective index/cache
- desktop-parity score adjustment
- AI + strategy-vote integration
- bootstrap upload + diagnostics
- Room migration 7 -> 8

### Stage 3 / M3 — Governance + production safety
- anomaly firewall
- safe mode
- smart kill switch
- risk-budget state/multiplier
- counterfactual learning
- execution-quality learning
- Why-Not-Trade evidence
- service watchdog + crash/resume evidence
- shared governance/execution aggregates
- PAPER execution guard correction
- Room migration 8 -> 9

### Stage 4 / M4 — Advanced execution + portfolio/risk
- M3 multiplier applied to actual BUY notional
- capital-protection ladder
- portfolio/capital allocation
- strict post-balance ceiling (no optimizer may raise it)
- liquidity-aware sizing
- fee-efficiency gate
- order-type optimization
- live reconciliation / local position repair
- lifecycle exit optimization
- realistic paper fills/fees/slippage/depth
- existing live/paper shadow comparison retained
- shared order/liquidity/exit/reconciliation/paper aggregates
- Room migration 9 -> 10

## Next

### Stage 5 / M5 — Research + strategy/AI expansion
- remaining desktop strategy modules
- walk-forward optimization
- Monte Carlo risk simulation
- research lab / strategy mutation
- advanced ensemble/meta-model features
- cross-symbol/dependency intelligence expansion
- sequence/on-chain/futures-context modules where suitable on Android

### Stage 6 / M6 — Unified UI + hardening + release
- navigation for every migrated module
- complete settings surfaces
- import/export/migration tools
- build health/system tests
- release signing/install-lineage checks
- final source pack + APK validation when Android build tooling is available
