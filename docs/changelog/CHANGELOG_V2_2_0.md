# Crypto TradeStation v2.2.0 — Ultimate Automation Pack

Implemented:
- Ultimate Automation layer toggle.
- Per-symbol automation rules.
- Per-symbol max position.
- Per-symbol minimum buy score.
- Per-symbol max buy price.
- Per-symbol cooldown.
- Auto-compounding hard cap.
- Repeated order/API failure auto-pause.
- Advanced Settings UI for all new controls.
- Settings persistence for all new controls.
- System Test / Feature Verification reports the new automation layers.
- Full backup export includes the new automation settings.

Per-symbol rule format:
SYMBOL=maxPosition|minScore|maxBuyPrice|cooldownMinutes

Example:
BTCEUR=20|78|95000|30;ETHEUR=10|74|3500|20

Live behavior:
- BUY can be blocked by per-symbol score, cooldown, or max buy price.
- SELL remains available.
- Adaptive position size respects the per-symbol cap and auto-compounding hard cap.
- After repeated live order/API failures, LIVE_AUTO changes to LIVE_CONFIRM/manual signal mode.

Safety:
- This does not guarantee profit.
- It adds more automatic controls while keeping all live order submission behind existing Kraken, balance, release-safety and preflight checks.
