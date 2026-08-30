# M12 v1.3 — semantic distributed-authority gate insertion

No trading/runtime design change.

Current `BotController.executeDecisionIfAllowed()` formats the Kraken gate as:

`if (settings.mode == BotMode.LIVE_AUTO &&`

The earlier patcher expected `if (` on a separate line, so the M12 apply step stopped
after successfully patching every preceding file.

v1.3 no longer matches the Kraken gate as one multiline whitespace-sensitive string.

It:
1. scopes to `executeDecisionIfAllowed(...)`;
2. locates the existing
   `KrakenPrivateExecutionRegistry.canSubmitNewEntry(request.symbol, request.side)`;
3. finds that call's enclosing `LIVE_AUTO` Kraken gate;
4. inserts the distributed `EngineAuthorityRuntime` BUY gate immediately before it.

Replace:
`tools/apply_m12_order_truth_authority.py`

Commit to `main`, then launch a NEW M12 workflow from `main`.
Do not use Re-run jobs on the failed run.
