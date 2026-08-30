# M16 — Market Microstructure & Fill Probability

M16 builds on merged M15.

## Kraken data truth

Kraken L2 (`book`) provides aggregated quantity at each visible price level. It is useful
for spread, depth, imbalance, microprice and market-impact estimates.

It does NOT expose exact per-order queue position.

Kraken L3 (`level3`) exposes individual visible resting orders and is authenticated, but
even L3 excludes in-flight orders, unmatched market orders, untriggered stop/take-profit
orders, and hidden iceberg quantity.

M16 therefore names its passive fill estimate `makerFillProbability` and explicitly
treats it as a heuristic. It never claims an exact queue probability.

## Added microstructure features

For every evaluated L2 snapshot:

- best bid / ask;
- midpoint;
- spread in bps;
- top-5 and top-10 bid/ask quote depth;
- quote-notional imbalance in [-1,+1];
- top-of-book microprice;
- microprice pressure in bps;
- simulated market-order VWAP impact;
- insufficient-depth detection;
- heuristic passive maker-fill probability;
- passive adverse-selection risk;
- passive maker target that cannot intentionally cross the opposite touch.

Invalid/crossed books are rejected conservatively.

## Entry behavior

The old safe LIMIT optimizer frequently used the ask, while `postOnly` was only set by a
research handoff. M16 changes the default execution policy:

safe LIMIT
-> passive L2 price
-> post-only
-> M15 waits
-> M15 atomic amend only when fill odds are weak and adverse-selection risk is acceptable

A source research handoff remains authoritative when it explicitly specifies order type
and post-only behavior.

## Market-order gate

Market orders still require the existing user setting, and now also require:

- deep top-five ask liquidity;
- very tight spread;
- complete simulated depth;
- impact below the configured slippage ceiling;
- acceptable adverse-selection risk.

Missing or invalid L2 never permits a market order.

## Sizing

Liquidity sizing now uses the same microstructure engine.

Guarded LIVE sizing fails closed for:

- missing/invalid order book;
- insufficient simulated depth;
- market impact above the configured ceiling.

High adverse-selection risk can reduce size but cannot increase it.

## M15 integration

M15's atomic amend manager now retrieves L2 depth for each managed BUY LIMIT and feeds:

- current working price;
- M15 observed fill-time samples;
- M15 mean realized fill time;
- remaining notional;
- exchange tick size;

into M16.

A stale BUY is only stepped inward when:

- fill probability heuristic < 0.60;
- adverse-selection risk < 0.65;
- target improves by at least one tick;
- target remains passive.

M15's 3-amend limit, post-only invariant, and hard stale-signal cancel remain unchanged.

## Run

Commit the bootstrap files to `main`, then:

Actions -> M16 Market Microstructure & Fill Probability -> Run workflow -> main

Expected branch:

`milestone/m16-market-microstructure-<run-number>`
