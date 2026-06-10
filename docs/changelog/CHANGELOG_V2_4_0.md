# Crypto TradeStation v2.4.0 — Clean Ultimate Automation

Implemented:
- Duplicate-position protection.
- Portfolio exposure guard wired through the existing Portfolio Balancer / Max Single Asset Allocation settings.
- Duplicate Feature Audit in System Test / Feature Verification.
- Advanced Settings UI for duplicate-position protection.
- Settings persistence for duplicate-position protection.
- Backup/export includes duplicate-position protection setting.

Clean structure / no duplicate features:
- Did not add a duplicate feature for existing max-spend logic. Max Position remains the canonical max spend per buy.
- Did not add a duplicate feature for existing exposure logic. Portfolio Balancer + Max Single Asset Allocation remains the canonical exposure control.
- Did not add a duplicate feature for existing live safety checks. LIVE_AUTO Preflight + Release Safety remains the canonical live gate.
- Did not add a duplicate feature for existing buy-price caps. Global/Per-symbol Max Buy Price remains the canonical price cap.
- Added only one new non-duplicate guard: Duplicate-position protection, which blocks adding a second BUY to the same held/open symbol while keeping SELL/exit paths available.

Live behavior:
- BUY is blocked when an OPEN lifecycle position exists for the same symbol.
- BUY is blocked when a meaningful existing base holding is detected for that symbol.
- BUY is blocked if portfolio exposure is above Max Single Asset Allocation.
- SELL remains allowed so the bot can exit positions.
