# Troubleshooting

## Bot does not trade

Check Live Status for the block reason.

Common reasons:

```text
manual execution mode is ON
bot mode is PAPER or LIVE_CONFIRM
live acknowledgement is OFF
no free EUR for BUY
no free crypto for SELL
AI score is too low
symbol is invalid on Kraken
spread/slippage guard blocked the order
risk guard/cooldown is active
```

## Kraken shows money, app shows no free EUR

Kraken portfolio value is not the same as free EUR cash.

BUY orders require:

```text
free EUR cash
```

SELL orders require:

```text
free crypto balance
```

If your value is already in ETH/BTC/etc., the bot cannot buy more without free EUR.

## API key works for portfolio but not trading

Your key may have read permission but not order permission.

Enable:

```text
Create and modify orders
Cancel/close orders
```

Keep withdrawals disabled.

## Build fails in GitHub Actions

Check:

```text
gradle.properties exists
android.useAndroidX=true
Android SDK setup step is present
JDK 17 is used
```

Then send the full build log for patching.
