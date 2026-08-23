# Crypto TradeStation M6 — Selective GPT-5.6 Luna / Sol AI Router

M6 turns the existing deterministic "AI" score into a true hybrid decision stack
without handing trading authority to a language model.

## Runtime order

1. Technical/news/memory scoring
2. Advanced automation
3. Autonomous/self-learning logic
4. Research intelligence
5. Production intelligence / kill-switch checks
6. **Selective cloud validator**
7. M5 net expected-value gate
8. Deterministic risk and execution
9. M4 Kraken execution-state gate

Cloud AI can never override a later deterministic block.

## Cost routing

The default configuration is:

- Selective cloud AI: **OFF**
- Monthly API budget: **$2.00**
- Sol escalation: allowed, but only after cloud AI is enabled
- Maximum Sol calls/day: **3**

If cloud AI is disabled, the app incurs zero OpenAI API cost.

When enabled:

- obvious high-confidence, low-news, small-position BUY candidates stay local;
- uncertain/meaningful BUY candidates can use GPT-5.6 Luna;
- Sol is only considered when Luna explicitly escalates, has confidence below 0.60,
  or abstains on a higher-value candidate;
- budget exhaustion skips cloud review instead of disrupting trading safeguards.

## Authority restrictions

OpenAI receives a compact snapshot only. It has no Kraken key, no OpenAI key in the
prompt, no tools, no browsing, and no direct execution ability.

It returns strict JSON:

- APPROVE
- REJECT
- ABSTAIN
- ESCALATE
- confidence
- strategy
- regime
- risk_multiplier (0..1)
- reason
- invalidation conditions

Important semantics:

- APPROVE does **not** create or strengthen a trade.
- REJECT changes an already-approved BUY to WAIT.
- ABSTAIN preserves the deterministic decision.
- risk_multiplier can only reduce size.
- API failures preserve deterministic behavior.
- WAIT/AVOID decisions never go to cloud AI and can never be promoted by it.

## M5 cost integration

Every successful Luna/Sol call records token usage and model cost.

M6 includes:

- ordinary input tokens
- cached input tokens
- cache-write tokens
- output tokens

The total paid-call cost is passed to:

`TradeEconomicsInput.externalDecisionCostQuote`

before the M5 net-EV decision.

Until the FX utility milestone, USD API cost is reserved 1:1 against EUR quote cost,
which is deliberately conservative rather than understating AI cost.

## Security

The OpenAI API key is stored through the existing Android-Keystore-backed
`SecureSettingsStore`, not ordinary SharedPreferences.

The Responses API request uses:

- `store=false`
- low reasoning effort
- no tools
- strict JSON Schema Structured Outputs
- a small max output budget

## Install

Copy this ZIP into the repository root, preserving paths, and commit the bootstrap
files to `main`.

Then run:

**Actions → M6 Selective Luna-Sol AI Router → Run workflow**

The workflow runs M6 → M5 → M4 → M3.2 → M3 → canonical verification, compiles Kotlin,
runs unit tests, assembles the APK and pushes:

`milestone/m6-selective-ai-<run>`

## After install

In the Android app:

**Connection & Trading → Secure API Credentials → Selective Cloud AI Validation**

1. Enter your OpenAI API key.
2. Set the monthly budget.
3. Choose whether Sol escalation is allowed.
4. Set the Sol daily cap.
5. Turn on selective cloud AI.
6. Save Secure Keys.

Your ChatGPT Plus subscription does not itself provide API billing; the API key must
belong to an OpenAI API account with API billing available.
