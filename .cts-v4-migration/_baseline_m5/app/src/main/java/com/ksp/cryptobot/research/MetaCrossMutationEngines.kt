package com.ksp.cryptobot.research

import com.ksp.cryptobot.core.*
import com.ksp.cryptobot.data.TradeEntity
import java.math.BigDecimal
import kotlin.math.abs

class MetaModelDecisionEngine {
    fun evaluate(symbol:String,strategy:String,trades:List<TradeEntity>):MetaModelAssessment{
        val exact=trades.filter{it.symbol.equals(symbol,true) && (it.realizedPnlEur.toDoubleOrNull()?:0.0)!=0.0 && it.aiReason.contains(strategy,true)}
        val closed=(if(exact.size>=5)exact else trades.filter{it.symbol.equals(symbol,true) && (it.realizedPnlEur.toDoubleOrNull()?:0.0)!=0.0}).takeLast(100)
        if(closed.size<5)return MetaModelAssessment(true,0,1.0,1.0,closed.size,"Meta-model neutral; not enough closed symbol-strategy outcomes.")
        val pnl=closed.sumOf{it.realizedPnlEur.toDoubleOrNull()?:0.0};val wins=closed.count{(it.realizedPnlEur.toDoubleOrNull()?:0.0)>0};val wr=wins.toDouble()/closed.size
        return when{
            pnl>0 && wr>=.58 -> MetaModelAssessment(true,3,1.08,1.0,closed.size,"Meta-model promotes $strategy on $symbol: winRate=${"%.1f".format(wr*100)}%, pnl=€${"%.2f".format(pnl)}.")
            pnl<0 && wr<=.42 -> MetaModelAssessment(false,-6,.75,.70,closed.size,"Meta-model blocks weak edge: $strategy on $symbol, winRate=${"%.1f".format(wr*100)}%, pnl=€${"%.2f".format(pnl)}.")
            pnl<0 -> MetaModelAssessment(true,-2,.92,.85,closed.size,"Meta-model cautious: negative realized edge €${"%.2f".format(pnl)}.")
            else -> MetaModelAssessment(true,0,1.0,1.0,closed.size,"Meta-model neutral: winRate=${"%.1f".format(wr*100)}%, pnl=€${"%.2f".format(pnl)}.")
        }
    }
}

class CrossSymbolIntelligenceEngine {
    private val trendFamilies=setOf("VOLATILITY_BREAKOUT","BOLLINGER_SQUEEZE_BREAKOUT","EMA_TREND_RIDER","SUPERTREND","PULLBACK_CONTINUATION","DONCHIAN_CHANNEL_BREAKOUT","KELTNER_CHANNEL_BREAKOUT","MACD_TREND_CROSS","ADX_TREND_PULLBACK","PARABOLIC_SAR_FLIP","ICHIMOKU_CLOUD_BREAKOUT","ROLLING_RANGE_EXPANSION")
    private val reversalFamilies=setOf("MEAN_REVERSION","LIQUIDITY_SWEEP_REVERSAL","SUPPORT_RESISTANCE_BOUNCE","RSI_DIVERGENCE_REVERSAL","STOCHASTIC_OVERSOLD_REVERSAL","CCI_MEAN_REVERSION","VWAP_DEVIATION_REVERSION")
    fun evaluate(symbol:String,strategy:String,ctx:BroadMarketContext):CrossSymbolAssessment{
        val broad=ctx.broadMomentumPct; val major=symbol.uppercase() in setOf("BTCEUR","ETHEUR","BTCUSD","ETHUSD","BTCUSDT","ETHUSDT")
        if(!major && strategy in trendFamilies && broad < -.60)return CrossSymbolAssessment(false,-6,0.70,broad,"Alt trend entry blocked because BTC/ETH broad momentum is negative (${"%.2f".format(broad)}%).")
        if(!major && strategy in trendFamilies && broad > .50)return CrossSymbolAssessment(true,2,1.0,broad,"Alt trend entry confirmed by positive BTC/ETH momentum (${"%.2f".format(broad)}%).")
        if(!major && strategy in reversalFamilies && abs(broad)<.35)return CrossSymbolAssessment(true,1,1.0,broad,"Range/reversal strategy confirmed by neutral broad-market momentum.")
        return CrossSymbolAssessment(true,0,1.0,broad,"Cross-symbol neutral; broadMomentum=${"%.2f".format(broad)}%.")
    }
}

class StrategyMutationLab {
    fun evaluate(strategy:String,profile:AdvancedRegimeProfile,ticker:MarketTicker):MutationCandidate{
        val candidates=mutableListOf<MutationCandidate>()
        if(profile.regime=="TRENDING" || profile.regime=="TRENDING_HIGH_VOL")candidates+=MutationCandidate("momentum_strict",2,"Trend-compatible strict momentum variant.")
        if(profile.volatility=="HIGH_VOLATILITY")candidates+=MutationCandidate("volatility_defensive",-4,"High volatility: defensive variant reduces confidence.")
        if(profile.trend=="RANGE")candidates+=MutationCandidate("range_mean_revert_bias",1,"Range regime supports bounded mean-reversion bias.")
        if(ticker.volume24h < BigDecimal("200000"))candidates+=MutationCandidate("low_liquidity_defensive",-3,"Low 24h quote volume; research variant is defensive.")
        return candidates.maxByOrNull{abs(it.adjustment)} ?: MutationCandidate("none",0,"No strong strategy mutation candidate.")
    }
}

class AutonomousHypothesisEngine {
    fun evaluate(symbol:String,trades:List<TradeEntity>,candles:List<Candle>):Pair<Int,String>{
        val rows=trades.filter{it.symbol.equals(symbol,true) && (it.realizedPnlEur.toDoubleOrNull()?:0.0)!=0.0}.takeLast(100)
        val wins=rows.count{(it.realizedPnlEur.toDoubleOrNull()?:0.0)>0};val losses=rows.count{(it.realizedPnlEur.toDoubleOrNull()?:0.0)<0}
        val vol=if(candles.size>10){ val r=candles.takeLast(20).map{it.close.toDouble()}.zipWithNext().mapNotNull{(a,b)->if(a==0.0)null else (b-a)/a*100}; if(r.size>3){ val m=r.average();kotlin.math.sqrt(r.sumOf{(it-m)*(it-m)}/r.size)}else 0.0 }else 0.0
        return when{
            losses>wins+3 && vol>1.2 -> -3 to "Hypothesis: high volatility plus weak symbol history reduces edge. wins=$wins losses=$losses vol=${"%.2f".format(vol)}%."
            wins>=maxOf(3,(losses*1.5).toInt()) -> 2 to "Hypothesis: symbol has favorable recent outcome history. wins=$wins losses=$losses."
            else -> 0 to "No strong validated autonomous hypothesis yet. wins=$wins losses=$losses."
        }
    }
}

class ParameterOptimizerEngine {
    fun suggestion(strategy:String,symbol:String,trades:List<TradeEntity>):String{
        val exact=trades.filter{it.symbol.equals(symbol,true) && (it.realizedPnlEur.toDoubleOrNull()?:0.0)!=0.0 && it.aiReason.contains(strategy,true)}
        val rows=(if(exact.size>=10)exact else trades.filter{it.symbol.equals(symbol,true)&&(it.realizedPnlEur.toDoubleOrNull()?:0.0)!=0.0}).takeLast(100)
        if(rows.size<10)return "Parameter optimizer: ${rows.size}/10 realized outcomes; observation only."
        val pnl=rows.sumOf{it.realizedPnlEur.toDoubleOrNull()?:0.0};val wins=rows.count{(it.realizedPnlEur.toDoubleOrNull()?:0.0)>0};val wr=wins.toDouble()/rows.size
        return when{pnl<0&&wr<.45->"Parameter optimizer: reduce allocation or tighten confirmation; recent edge is weak (win=${"%.1f".format(wr*100)}%, pnl=€${"%.2f".format(pnl)}).";pnl>0&&wr>.60->"Parameter optimizer: candidate for cautious allocation/threshold promotion only after walk-forward confirmation (win=${"%.1f".format(wr*100)}%, pnl=€${"%.2f".format(pnl)}).";else->"Parameter optimizer: keep current bounded parameters; edge is mixed/neutral (win=${"%.1f".format(wr*100)}%, pnl=€${"%.2f".format(pnl)})."}
    }
}
