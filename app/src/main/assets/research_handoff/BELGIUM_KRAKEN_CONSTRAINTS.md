# Belgium + Kraken Constraints

**Snapshot date: 2026-08-17. Revalidate at runtime and before releases.**  
This is technical/compliance research, not individualized legal or tax advice.

## Belgium

### Derivative distribution
The Belgian FSMA has a regulation restricting/prohibiting distribution of certain derivatives to retail consumers, including products in the CFD/leveraged category. The practical product policy for this app is:

**Belgium profile default: crypto spot, long-only.**

Do not expose leveraged perpetual/futures/CFD execution merely because a YouTube tutorial uses it.

### MiCA / CASP
Belgian FSMA material states that MiCA is implemented in Belgium and the Belgian law of 11 December 2025 provides the national framework. The transitional regime for the relevant pre-existing crypto-provider category ended by July 2026. The app should use an appropriately authorized/passported crypto-asset service provider and periodically re-check status.

## Kraken

Kraken's current official licensing page states that its relevant Irish entities hold European authorizations/passports covering EEA countries including Belgium.

### Restricted assets
The current Kraken EEA restrictions page includes a number of restricted assets/stablecoins (the list can change). **Do not hardcode it as permanent.**
Implementation:
`MarketRegistry.refresh()` → exchange metadata + account availability → local date-stamped cache → reject unavailable.

### Spot fees — research snapshot
Kraken's current Spot fee page showed the lowest 30-day spot-volume tier at:
- maker: **0.40%**
- taker: **0.80%**

Thus, before spread/slippage:
- maker → maker round trip ≈ 0.40% + 0.40% = **0.80%**
- taker → taker round trip ≈ 0.80% + 0.80% = **1.60%**

These percentages apply to traded notional on each side, so exact EUR cost also depends on entry and exit notional.

The app should fetch the account's actual tier where the API permits. Never assume the user stays Tier 1.

### Minimums
Official Kraken support material reviewed states examples including:
- EUR trade-form volume minimum around **€5**;
- BTC base minimum around `0.0001 BTC`;
- ETH base minimum around `0.01 ETH`;
- a separate quote-currency cost minimum may also apply (Kraken currently documents EUR cost minimum separately).

Exact market precision/minimums must come from live exchange market metadata because they vary by asset and can change.

### Order semantics
Kraken supports:
- market orders
- limit orders
- post-only behavior
- stop-loss / take-profit and conditional-close style features depending on interface/API/product.

A market order is taker. A post-only limit is intended to add liquidity; if it would immediately match, it should not silently become a taker fill.

### Safety rule
If strategy economics need a 0.3% move while round-trip fees alone are 0.8–1.6%, reject it for the current fee/execution assumptions. Do not let historical zero-fee backtests override real cost math.

## Sources
- FSMA derivative FAQ: https://www.fsma.be/en/faq/fsma-regulation-governing-distribution-certain-derivative-financial-instruments-binary-options
- FSMA CASP: https://www.fsma.be/en/crypto-asset-service-provider-casp
- Kraken licenses: https://support.kraken.com/articles/where-is-kraken-licensed-or-regulated
- Kraken spot fees: https://www.kraken.com/features/fee-schedule
- Kraken crypto minimums: https://support.kraken.com/articles/360001389303-overview-of-cryptocurrency-minimums
- Kraken order types / API examples: https://docs.kraken.com/
