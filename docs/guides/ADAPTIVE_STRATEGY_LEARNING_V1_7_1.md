# Adaptive Strategy Learning

The bot now learns which strategy works best and can switch strategy selection automatically.

## How it works

1. Every decision and trade is recorded with a strategy label.
2. Completed paper/live trades update symbol and strategy profiles.
3. If a symbol has enough samples, the symbol profile can choose a preferred strategy.
4. If a symbol does not have enough samples, the bot can use the best global learned strategy.
5. The selected strategy is used in the automation decision engine.
6. The final decision receives a bounded score and size adjustment.

## Minimum samples

The default minimum is 8 strategy samples and 10 self-learning symbol samples. Before enough samples exist, the bot logs warm-up messages and stays close to the base strategy.

## Live Status examples

```text
[ETHEUR] Adaptive strategy selector: selected=TREND, source=SYMBOL_PROFILE, confidence=100%, scoreAdj=6.
[ETHEUR] Adaptive multi-strategy result: strategy=TREND, score 72→78, action SMALL_BUY→BUY, size 5.00→5.50.
```

## Safety

The strategy selector only changes scoring and strategy preference. It cannot force a live order through risk, spread, balance, cooldown, quote-asset, or exchange safeguards.
