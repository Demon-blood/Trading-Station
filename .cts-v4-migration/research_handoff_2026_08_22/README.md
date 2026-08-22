# Crypto Trading Research → Implementation Handoff

**Research freeze:** 2026-08-17  
**Purpose:** implementation-grade handoff for a Belgium-based crypto spot trading application.  
**Scope:** Peter Brandt, CryptoCred, Dan McDermitt / The Chart Guys, Eric Krown, Benjamin Cowen, Bob Loukas, Jason Pizzino, Alessio Rastani, plus Kraken/Belgium constraints and empirical validation principles.

## Truth protocol

This package deliberately separates four different kinds of "truth":

1. **Source truth** — what a trader actually says or publishes.
2. **Mechanical truth** — whether that teaching is precise enough to reproduce in code.
3. **Empirical truth** — whether the rule has evidence of positive expectancy outside the creator's examples.
4. **Implementation truth** — what the app is allowed to claim and execute.

A creator teaching a rule does **not** prove that rule is profitable. A backtest does **not** prove future profitability. A public success story does **not** equal an audited track record.

### Provenance tags

| Tag | Meaning |
|---|---|
| `DIRECT_PRIMARY` | First-party website, paper, curriculum, platform page, or direct interview. |
| `DIRECT_PUBLIC_VIDEO` | Public video by the trader or a direct interview containing the rule. |
| `PUBLIC_MIRROR` | Public mirror/notes of original material. Use only if consistent with direct material. |
| `SECONDARY_REPORTED` | Reputable or identifiable secondary description. Not primary proof. |
| `ACADEMIC_EVIDENCE` | Research evidence about a method/factor/testing principle; does not validate a creator's personal implementation. |
| `INFERRED_ADAPTATION` | Our proposed translation to Belgium/Kraken spot. Never present as the creator's exact method. |
| `PROPRIETARY_UNKNOWN` | Creator uses a private formula/indicator/setting that public material does not disclose. **Do not guess it.** |
| `UNVERIFIED` | Claim/data could not be independently confirmed. |

### Fidelity classes

| Class | Meaning | Coding policy |
|---|---|---|
| `A` | Publicly specified enough to reproduce the stated rule with low ambiguity. | Implement as an isolated strategy and test. |
| `B` | Core rule is public, but one or more discretionary choices remain. | Implement parameters/decision hooks and label it a formalization. |
| `C` | Concept/framework only. | Implement only as an independent model inspired by the concept. |
| `X` | Exact rule is private, paid, unavailable, or materially under-specified. | Do not claim exact replication. |

## Non-negotiable implementation rules

- Default live scope: **long-only spot crypto**. A bearish/short setup may become `EXIT`, `REDUCE`, or `AVOID_NEW_LONG`; it must not silently become a short derivative trade.
- Belgium derivative restrictions are a compliance gate, not a UI warning.
- Kraken market availability/restrictions, min order sizes, precision and fees must be fetched/validated live where possible.
- Every strategy stays isolated until a separately named composite experiment is created.
- Every signal must preserve source strategy/version/provenance.
- Every backtest includes fees and slippage; no zero-cost headline results.
- No look-ahead: signal on closed data, execution no earlier than the next legally/technically available price unless an order was genuinely resting beforehand.
- If an exchange minimum would force risk above the configured budget, **skip the trade**.
- Proprietary formulas (notably Cowen's Price Risk and Krown's proprietary indicators) are not reverse-engineered or invented here.
- Public videos are summarized/paraphrased. This package does not reproduce copyrighted transcripts or paid-course material.

## Package map

- `TRADER_DUE_DILIGENCE.md` — background, evidence, weaknesses, platforms and implementation relevance.
- `VIDEO_RESEARCH_INDEX.csv` — public long-form guides/trade reviews found, with extraction status.
- `VIDEO_EXTRACTION_NOTES.md` — implementable lessons extracted from high-value videos/pages.
- `STRATEGY_CATALOG.json` — machine-readable strategies and fidelity metadata.
- `IMPLEMENTATION_SPEC.md` — architecture and exact execution/risk/backtest rules.
- `BELGIUM_KRAKEN_CONSTRAINTS.md` — 2026-08-17 legal/platform constraints.
- `EVIDENCE_MATRIX.md` — what is taught vs what is empirically established.
- `UNVERIFIED_AND_PROPRIETARY.md` — explicit do-not-invent list.
- `RESEARCH_SOURCES.md` — source bibliography.
- `HANDOFF_PROMPT.md` — paste into a new conversation together with this package.
- `SOURCE_REGISTRY.json` — machine-readable source list.

## Core conclusion

The strongest implementable combination is not "copy a YouTuber." It is a **versioned research laboratory**:

`regime → structure → setup → entry → invalidation → cost gate → risk sizing → execution → management → audit → walk-forward validation`

The app should be able to prove which formalized rules survive realistic Kraken costs and multiple market regimes before they are promoted to live trading.

## Strategy truth extension — 2026-08-18

`STRATEGY_TRUTH_STANDARD.md` is now **mandatory** for every current and future strategy.

The term **strategy** no longer means just a setup name. A faithful strategy record must capture:
`purpose → market/regime → timeframe → preconditions → setup → trigger → entry → invalidation → stop → sizing → management → target/exit → no-trade conditions → failure modes → provenance`.

If a trader's exact rule is not publicly established, the app must keep it as `FORMALIZED_FROM_PUBLIC_CORE`, `CONCEPT_INSPIRED`, or `PROPRIETARY_NOT_IMPLEMENTED`. It must never silently promote an inferred rule to source truth.

Implementation must also preserve **when the real strategy is meant to be used**. A valid-looking pattern outside its source usage context is not a valid source-faithful signal.



## Weekly research status — 2026-08-22

New thoroughly researched additions:
- Chris Dunn
- Josh Olszewicz / CarpeNoctom

New method/context records:
- Dunn 1234 Crypto Breakout
- Olszewicz Crypto Ichimoku 20/60/120/30 toolkit
- Olszewicz Alligator + Fractal public core
- Krown LTI public-core 11-component convergence framework
- Cowen 2026 macro/crypto regime memo framework
- Pizzino 3-bar candidate (blocked pending primary extraction)

See `WEEKLY_REPORT_2026-08-22.md`.
