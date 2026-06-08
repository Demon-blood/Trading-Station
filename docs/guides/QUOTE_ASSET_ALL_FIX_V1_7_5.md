# v1.7.5 Quote Asset ALL Fix

This release fixes the Allowed Quote Assets parser.

Previously, `ALL` could be stored as a literal quote asset instead of being treated as a wildcard. That caused BUY orders such as `AKTEUR`, `BTCEUR`, or `ETHEUR` to be blocked with messages like:

`quote asset EUR is not enabled in Allowed Quote Assets (ALL)`

Now these values are treated as wildcard aliases:

- `ALL`
- `ANY`
- `*`

If `Allowed Quote Assets = ALL`, every quote asset passes the quote-asset allow check.

Recommended safe setting for small EUR-only accounts:

`Allowed Quote Assets = EUR`

Recommended broad scan setting:

`Allowed Quote Assets = ALL`

Note: quote allowance only controls whether the bot is allowed to spend that quote asset. The bot still needs an actual free balance of that quote asset for BUY trades.
