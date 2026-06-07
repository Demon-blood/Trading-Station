# Live Verification Plan

Use this sequence before trusting the bot with larger amounts.

## Phase 1 — no money risk
1. Install the APK.
2. Confirm header says `v1.5.0 Live Audit`.
3. Set Exchange Provider to PAPER.
4. Run Scan Now.
5. Confirm Live Status says paper mode/no live order submitted.
6. Open Symbol Scanner and run discovery.

## Phase 2 — Kraken read-only validation
1. Set Exchange Provider to KRAKEN.
2. Enter Kraken API key/private key with read permissions.
3. Open Portfolio.
4. Confirm balances match Kraken.
5. Open Orders.
6. Confirm open orders match Kraken.
7. Run Symbol Scanner.
8. Confirm valid EUR symbols are discovered.

## Phase 3 — tiny live trading validation
1. Enable Create/Modify orders on Kraken API.
2. Keep Withdrawals OFF.
3. Set Max Position EUR to 5 or 6.
4. Use 1 or 2 symbols only, preferably BTCEUR and ETHEUR.
5. Use LIMIT orders first.
6. Keep market orders OFF.
7. Enable LIVE_AUTO and live acknowledgement.
8. Watch Live Status for every step.
9. Confirm any placed order appears in Kraken.
10. Test cancel from Orders tab.

## Phase 4 — automation validation
1. Enable auto symbol discovery.
2. Keep max active symbols low.
3. Keep daily trade limit low.
4. Keep daily loss limit low.
5. Let the bot run while watching Live Status.
6. Export logs/screenshots for every unexpected behavior.

## Required Kraken API permissions
- Query funds: ON
- Query open orders/trades: ON
- Create and modify orders: ON for live trading
- Cancel/close orders: ON
- Withdrawals: OFF

Never enable withdrawals for this bot.
