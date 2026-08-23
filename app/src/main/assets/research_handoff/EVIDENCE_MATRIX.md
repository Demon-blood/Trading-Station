# Evidence Matrix

## How to read this

`Taught` means a creator publicly uses/teaches the idea.  
`Reproducible` means the public rule can be implemented with low ambiguity.  
`Empirical support` is broader academic/market evidence and **not proof of the creator's exact version**.

| Method | Creator(s) | Taught | Reproducible | Empirical status | App treatment |
|---|---|---:|---:|---|---|
| Risk-defined position sizing | Brandt, CryptoCred, TCG | Yes | High | Risk arithmetic is valid; no sizing rule creates alpha | Mandatory risk layer |
| Classical horizontal breakout | Brandt | Yes | Medium | Technical rules can work in some datasets/regimes; parameter/regime sensitivity material | Isolated backtest |
| ATR-standardized breakout | Brandt | Yes | High-ish | Volatility scaling is standard; exact 0.5×ATR rule not universally proven | Versioned strategy |
| ADX compression | Brandt | Yes | High detection | Predictive edge must be tested | Feature/filter |
| 3DTSR | Brandt | Yes | Medium | No independent universal validation found | Management experiment |
| S/R role reversal / retest | CryptoCred | Yes | Medium | Common TA concept; exact edge data-dependent | Formalized detector |
| Top-down multi-timeframe context | CryptoCred, TCG | Yes | Medium | Sensible process; exact mapping must be tested | Architecture |
| FTA | CryptoCred | Yes | Medium | Trade-management heuristic | Management module |
| PDH/PDL sweep/reclaim | CryptoCred | Yes | Medium | Liquidity/reference concept; no universal edge assumed | Experimental |
| BackBurner | TCG | Yes | Core medium / exact indicator no | Paid exact formula proprietary | Bullish formalization only |
| Equilibrium / inside bar | TCG | Yes | High detection | Compression/breakout is testable; detection != edge | Detector + separate entry |
| Relative strength | TCG | Yes | High | Momentum/relative-strength effects have broader literature | Filter, validate crypto-specific |
| EMA Rider | TCG | Yes | Public core medium | Exact gated version unavailable | Generic independent variant |
| Trend/vol/momentum confluence | Krown | Yes | Concept high | Common quantitative structure; weights matter | State engine |
| VMP / proprietary Krown indicators | Krown | Yes | No | Formula unavailable | Do not clone |
| Walk-forward / strategy shelf-life | Krown | Yes | High | Strongly consistent with anti-overfit methodology | Mandatory R&D |
| Dynamic DCA | Cowen | Yes | Concept high | DCA is allocation policy; exact risk-timing edge unproven | Independent risk-band DCA |
| ITC Price Risk | Cowen | Yes | No | Proprietary | Do not clone |
| BTC dominance regime | Cowen | Yes | High feature | Predictive relationship must be tested | Regime feature |
| Four-year BTC cycle | Loukas | Yes | Medium conceptual | Historical regularities can break; recent cycle papers are not definitive proof | Slow context only |
| 60-day cycle | Loukas | Yes | Low/medium | Needs exact formalization/OOS | Research |
| Swing plan with entry/stop/target | Pizzino | Yes | Process high | No creator-specific edge established | Generic process |
| Elliott Wave | Rastani | Yes | Low | Subjective/non-unique labeling | Low-weight research context |
| Opening gap fill | Rastani | Yes for session markets | Medium | Session anomaly requires market-specific test | Not exact 24/7 crypto strategy |

## Academic validation principles extracted

The literature reviewed supports a cautious methodology more strongly than it supports any one influencer:
- transaction costs can erase apparent short-horizon crypto edge;
- technical-strategy results are sensitive to market regime and parameter choice;
- repeated optimization can overfit;
- rolling/out-of-sample and walk-forward evaluation is preferable to one in-sample equity curve;
- momentum has been documented as a crypto return characteristic in multiple studies, but implementation, turnover and costs determine realizable returns.

Therefore the app's **scientific contribution** should be testing the sourced rules honestly rather than assuming the source is proof.
