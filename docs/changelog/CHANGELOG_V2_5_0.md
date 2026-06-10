# Crypto TradeStation v2.5.0 — Ultimate Conversation Context Upgrade

Integrated from the Ultimate Crypto Trading Bot discussion:
- Multi-timeframe scalping/confirmation before automatic BUY.
- Ultimate readiness score in System Test.
- Clean canonical feature audit to avoid duplicate features.
- Reinforced architecture around existing canonical controls:
  - Max Position = max spend per buy.
  - Max Buy Price = global/per-symbol price cap.
  - Portfolio Balancer = exposure control.
  - LIVE_AUTO Preflight + Release Safety = live gate.
  - Auto-Pause = repeated failure safety stop.
  - Per-symbol rules = symbol-specific overrides.
  - Multi-Timeframe Consensus = trend confirmation.

Implemented:
- Settings persistence for multi-timeframe consensus.
- Advanced Settings UI for multi-timeframe consensus and readiness score.
- Live execution guard that checks M5/M15/H1 Kraken candles before BUY.
- System Test rows for Ultimate Readiness Score and Multi-Timeframe Consensus.
- Backup/export includes the new settings.

Safety:
- Multi-timeframe consensus blocks BUY only.
- SELL/exit management remains available.
- No profit is guaranteed.
