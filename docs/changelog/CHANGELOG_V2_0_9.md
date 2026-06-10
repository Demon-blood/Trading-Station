# Crypto TradeStation v2.0.9 — Advanced Automation Pack

Implemented:
- Max Buy Price hard guard.
- Global max buy price.
- Per-symbol max buy price list.
- Advanced Settings UI for the new buy-price guard.
- Settings persistence for the new guard.
- System Test now reports the Max Buy Price guard status.
- Full backup export includes the new Max Buy Price settings.
- Live order execution path blocks BUY orders when ask price is above configured cap.

Usage:
- Settings → Advanced Settings → Position, Reserve and Trade Limits
- Enable Max Buy Price filter
- Set Global max buy price, or use per-symbol list:
  BTCEUR=95000,ETHEUR=3500,SOLEUR=180

Safety:
- This blocks BUY orders only.
- SELL orders remain available so the bot can exit positions.
- A value of 0 disables the global max buy price.
