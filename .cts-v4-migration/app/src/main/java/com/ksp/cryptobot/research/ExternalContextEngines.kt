package com.ksp.cryptobot.research

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class FuturesAndWalletContextEngine(private val settings: ResearchSettingsStore) {
    private val client=OkHttpClient.Builder().connectTimeout(5,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).build()
    private val adapter=Moshi.Builder().build().adapter<Map<String,Any?>>(Types.newParameterizedType(Map::class.java,String::class.java,Any::class.java))
    @Volatile private var futuresCacheAt=0L
    @Volatile private var futuresCache:Map<String,Any?> = emptyMap()

    fun futures(symbol:String):ContextAssessment {
        if(!settings.futuresContextEnabled()) return neutral("Kraken Futures","DISABLED","Futures context disabled.")
        val base=asset(symbol)
        if(base !in setOf("BTC","ETH","SOL","XRP","ADA")) return neutral("Kraken Futures","UNMAPPED","No Kraken Futures context mapping for $base.")
        return runCatching {
            val data=loadFutures(); val rows=data["tickers"] as? List<*> ?: emptyList<Any?>()
            val hits=rows.mapNotNull{it as? Map<*,*>}.filter{ row -> val s=row["symbol"]?.toString()?.uppercase().orEmpty(); base in s || (base=="BTC" && "XBT" in s) }
            val best=hits.firstOrNull() ?: return neutral("Kraken Futures","NO_CONTRACT","No matching Kraken Futures contract for $base.")
            val mark=num(best["markPrice"] ?: best["last"]); val index=num(best["indexPrice"]); val premium=if(index!=0.0)(mark-index)/index*100.0 else 0.0
            when { premium>.65 -> ContextAssessment(true,-4,.90,"Kraken Futures","RISK","Futures premium=${"%.2f".format(premium)}%; crowded-long risk (${best["symbol"]}).")
                premium<-.35 -> ContextAssessment(true,2,1.0,"Kraken Futures","SUPPORT","Futures premium=${"%.2f".format(premium)}%; mildly supportive (${best["symbol"]}).")
                else -> neutral("Kraken Futures","NEUTRAL","Futures premium=${"%.2f".format(premium)}% (${best["symbol"]}).") }
        }.getOrElse{ neutral("Kraken Futures","ERROR","Futures context unavailable: ${it.message}") }
    }


    fun crossMarket(symbol:String, krakenPrice:Double):ContextAssessment {
        if(krakenPrice<=0.0)return neutral("Binance public","INVALID","Cross-market reference skipped: invalid Kraken price.")
        val upper=symbol.uppercase().replace("/","").replace("-","")
        val quote=when{upper.endsWith("USDT")->"USDT";upper.endsWith("USD")->"USD";upper.endsWith("EUR")->"EUR";else->""}
        val base=upper.removeSuffix(quote).replace("XBT","BTC")
        val candidate=if(quote=="USDT")"${base}USDT" else "${base}USDT"
        return runCatching {
            val url="https://api.binance.com/api/v3/ticker/price".toHttpUrl().newBuilder().addQueryParameter("symbol",candidate).build()
            val body=client.newCall(Request.Builder().url(url).get().build()).execute().use{r->if(!r.isSuccessful)error("HTTP ${r.code}");r.body?.string().orEmpty()}
            val data=adapter.fromJson(body).orEmpty(); val ref=num(data["price"]); if(ref<=0.0)return neutral("Binance public","NO_PRICE","Cross-market reference unavailable for $candidate.")
            val dev=(krakenPrice-ref)/ref*100.0; val absDev=kotlin.math.abs(dev)
            if(quote=="EUR" && absDev>3.0) return neutral("Binance public","DIFFERENT_QUOTE","Cross-market reference uses USDT for an EUR pair; deviation=${"%.2f".format(dev)}% is informational only.")
            when{ absDev>.85 -> ContextAssessment(true,-4,.90,"Binance public","DIVERGENCE","Cross-market price divergence=${"%.2f".format(dev)}% vs $candidate.")
                absDev<.25 -> ContextAssessment(true,1,1.0,"Binance public","CONFIRMED","Cross-market price confirmation=${"%.2f".format(dev)}% vs $candidate.")
                else -> neutral("Binance public","NEUTRAL","Cross-market deviation=${"%.2f".format(dev)}% vs $candidate.") }
        }.getOrElse{ neutral("Binance public","ERROR","Cross-market reference unavailable: ${it.message}") }
    }

    fun labeledWallet(symbol:String):ContextAssessment {
        if(!settings.labeledWalletEnabled())return neutral("Whale Alert","DISABLED","Labeled-wallet intelligence disabled.")
        val key=settings.whaleAlertApiKey(); if(key.isBlank())return neutral("Whale Alert","MISSING_API_KEY","Labeled-wallet intelligence requires a Whale Alert API key.")
        val cur=asset(symbol).lowercase(); if(cur !in setOf("btc","eth","xrp","usdt","usdc","sol","ada"))return neutral("Whale Alert","UNMAPPED","Unsupported labeled-wallet asset $cur.")
        return runCatching {
            val url="https://api.whale-alert.io/v1/transactions".toHttpUrl().newBuilder()
                .addQueryParameter("api_key",key).addQueryParameter("currency",cur).addQueryParameter("min_value",settings.whaleAlertMinUsd().toString())
                .addQueryParameter("start",((System.currentTimeMillis()/1000)-6*3600).toString()).build()
            val body=client.newCall(Request.Builder().url(url).get().build()).execute().use{r->if(!r.isSuccessful)error("HTTP ${r.code}");r.body?.string().orEmpty()}
            val data=adapter.fromJson(body).orEmpty(); val txs=data["transactions"] as? List<*> ?: emptyList<Any?>()
            var exchangeIn=0.0;var exchangeOut=0.0;var labeled=0
            txs.mapNotNull{it as? Map<*,*>}.forEach{tx->
                val amount=num(tx["amount_usd"]);val src=tx["from"] as? Map<*,*>;val dst=tx["to"] as? Map<*,*>
                val st=(src?.get("owner_type")?:src?.get("type"))?.toString()?.lowercase().orEmpty();val dt=(dst?.get("owner_type")?:dst?.get("type"))?.toString()?.lowercase().orEmpty()
                if(st.isNotBlank()||dt.isNotBlank())labeled++
                if(dt=="exchange"&&st!="exchange")exchangeIn+=amount
                if(st=="exchange"&&dt!="exchange")exchangeOut+=amount
            }
            val net=exchangeIn-exchangeOut
            when { net>settings.whaleAlertExchangeRiskUsd() -> ContextAssessment(true,-5,.80,"Whale Alert","RISK","Labeled wallets: labeled=$labeled, net exchange inflow=$${"%,.0f".format(net)}.")
                net < -settings.whaleAlertExchangeOutflowBullUsd() -> ContextAssessment(true,3,1.0,"Whale Alert","SUPPORT","Labeled wallets: labeled=$labeled, net exchange outflow=$${"%,.0f".format(-net)}.")
                else -> neutral("Whale Alert","NEUTRAL","Labeled wallets: labeled=$labeled, net exchange inflow=$${"%,.0f".format(net)}.") }
        }.getOrElse{ neutral("Whale Alert","ERROR","Labeled-wallet context unavailable: ${it.message}") }
    }

    private fun loadFutures():Map<String,Any?>{
        val now=System.currentTimeMillis();if(futuresCache.isNotEmpty()&&now-futuresCacheAt<300_000)return futuresCache
        val body=client.newCall(Request.Builder().url("https://futures.kraken.com/derivatives/api/v3/tickers").get().build()).execute().use{r->if(!r.isSuccessful)error("HTTP ${r.code}");r.body?.string().orEmpty()}
        futuresCache=adapter.fromJson(body).orEmpty();futuresCacheAt=now;return futuresCache
    }
    private fun asset(symbol:String):String=symbol.uppercase().replace("XBT","BTC").removeSuffix("USDT").removeSuffix("USDC").removeSuffix("EUR").removeSuffix("USD").take(5)
    private fun num(v:Any?):Double=(v as? Number)?.toDouble() ?: v?.toString()?.toDoubleOrNull() ?: 0.0
    private fun neutral(provider:String,status:String,reason:String)=ContextAssessment(true,0,1.0,provider,status,reason)
}
