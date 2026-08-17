# Crypto TradeStation Android v4 migration — Milestone 1

Reference implementations:
- Android baseline: Demon-blood/Trading-Station v3.2.5 / versionCode 97.
- Installed comparison APK: v3.2.3 / versionCode 96.
- Desktop reference: CryptoTradeStation v1.0.50 CloudShare Admin 401 Fix.

Implemented in this overlay:
1. CloudShare protocol parity (`2026-07-26`, schema v1).
2. Desktop-compatible canonical JSON, secret/path sanitisation, payload hashes, deterministic event IDs and batch IDs.
3. Native Android registration through one-time owner invitation codes.
4. Android Keystore-backed client token and owner/admin token storage.
5. Upload outbox with deterministic deduplication, retry/backoff and rejection state.
6. Collective-intelligence cursor download and local materialisation.
7. Owner/admin calls: ping, create/list/revoke invitations, list clients, disable/enable/rotate clients.
8. Android v3 evidence collector mapping trades/signals/news/AI/self-learning into CloudShare source names.
9. Room database v6 -> v7 explicit migration; destructive fallback removed.
10. Foreground-service integration patch so sync runs alongside the existing bot service and remains non-fatal.

Not yet wired in Milestone 1:
- Compose CloudShare/CloudShare Admin screens.
- Collective score adjustment inside AiDecisionEngine / strategy voting.
- Desktop advanced_ai / governance_ai / meta-model modules.
- Desktop production_intelligence / advanced_execution modules.
- Walk-forward, Monte Carlo, research lab, portfolio optimizer and expanded strategy modules.
- Shared aggregate materialisation equivalent to the desktop shared_* daily tables.

The migration intentionally starts with protocol/data integrity because every later intelligence module depends on stable evidence and non-destructive persistence.
