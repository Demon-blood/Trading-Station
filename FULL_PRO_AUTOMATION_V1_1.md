# v1.1.0 Full Pro Automation

This build adds the next full automation layer on top of v1.0 live Kraken trading.

## Added systems

- Kraken WebSocket-ready live ticker monitor
- Smart profit-lock engine
- Multi-stage profit-lock calculations
- Fee/spread net-profit filter
- Why traded / why skipped explanations
- Smart symbol rotation scoring
- Strategy optimizer scaffold using live candles
- Portfolio balancing guard
- EUR reserve protection
- Single-asset exposure protection
- Android battery watchdog readiness check
- Dry-run mirror exit comparison
- Local explainable AI score scaffold
- Remote command parser scaffold for future Telegram/Discord commands
- Pro Systems tab in the app

## Important behavior

The bot still cannot guarantee maximum possible profits. The new systems are designed to improve captured profit and prevent uncontrolled trading by requiring a positive expected edge after spread, fees and slippage reserves.

## Main live checks added

Before live orders, the controller now logs:

- Pro readiness gate
- Smart rotation score per symbol
- Fee/spread net-profit check
- Why/edge explanation per decision
- Portfolio balancer warnings during portfolio refresh

## Recommended testing

1. Build debug APK with GitHub Actions.
2. Install fresh APK.
3. Confirm header shows v1.1.0.
4. Open Pro Systems tab.
5. Verify readiness is READY or inspect BLOCK lines.
6. Start with small max position.
7. Watch Live Status before enabling larger sizes.

## Safety

Keep Kraken withdrawal permission disabled. Start with small position sizes. Market orders can slip, especially on low-liquidity pairs.
