# M12 v1.4 — PAPER verifier fix

No Android or trading runtime code changes.

The M12 runtime correctly bypasses distributed LIVE authority for PAPER:

```kotlin
if (settings.mode == BotMode.PAPER || settings.exchangeProvider == ExchangeProvider.PAPER) {
    val paper = EngineAuthoritySnapshot(true, "PAPER", ...)
    EngineAuthorityRuntime.publish(paper)
    return paper
}
```

The verifier incorrectly required the source literal:

`state = "PAPER"`

but `EngineAuthoritySnapshot` receives `"PAPER"` as a positional constructor argument.

v1.4 verifies the real behavior instead:
- PAPER mode/provider branch exists;
- returned snapshot is authorized with state `"PAPER"`;
- the PAPER snapshot is returned.

The dedicated `paperDoesNotRequireDistributedLease` unit regression already passed in the failed Action.

Replace:
`tools/verify_m12_order_truth_authority.py`

Commit to `main`, then launch a NEW M12 workflow from `main`.
