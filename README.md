# Crypto TradeStation v4.0.2 — Kraken Minimum Order Fix

The Telegram screenshot exposed a deterministic live-order sizing bug:
CTS requested about 3.65–3.67 NEAR while Kraken required at least 4 NEAR.

The controller was already reading Kraken pair metadata, but BUY quantity sizing still used
`targetNotional / price` and only enforced a generic €5 notional floor. Kraken therefore
rejected every undersized order and the failure path sent a Telegram alert on every retry.

This patch uses both Kraken `ordermin` (`minOrderSize`) and `costmin` (`minOrderCost`).

Behavior after the patch:
- Before AI/research sizing, CTS may raise the requested BUY to the exchange minimum only if
  it still fits the hard order/position cap and spendable quote balance after reserve/current-scan reservations.
- After AI/research sizing, CTS checks the exchange minimum again. If risk sizing reduced the
  trade below the exchange minimum, CTS skips the trade instead of overriding the risk engine.
- A final pre-submit quantity check prevents undersized BUYs from reaching Kraken.
- Kraken's connector-side checks remain the final line of defense.
- If pair metadata changes between validation and submission, deterministic minimum-size/cost
  failures are logged locally without repeated Telegram spam.

Repository changes:
- ADD `.cts-v4-migration/apply_exchange_minimum_order_fix.py`
- REPLACE `.github/workflows/android-v4-build.yml`

Keep the existing diagnostics, full-integration cleanup, and exact-preview UI migrations.
