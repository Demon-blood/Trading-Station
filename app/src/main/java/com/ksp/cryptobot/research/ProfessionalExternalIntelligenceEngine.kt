package com.ksp.cryptobot.research

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlin.math.abs

/**
 * Android port/upgrade of desktop production_intelligence MultiExchangeReferenceFeed + OnChainIntelligence.
 * Public providers are advisory only. Missing/error providers are neutral except for a small composite data-quality
 * penalty, matching desktop behavior. API-key providers are OFF by default and keys stay in Android Keystore.
 */
data class ProfessionalExternalBundle(
    val compositeAdjustment: Int,
    val reference: ContextAssessment,
    val providers: List<ContextAssessment>,
    val reason: String
)

class ProfessionalExternalIntelligenceEngine(private val settings: ResearchSettingsStore) {
    private val http = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build()
    private val cache = ConcurrentHashMap<String, Pair<Long, ProfessionalExternalBundle>>()

    suspend fun evaluate(symbol: String, krakenPrice: Double): ProfessionalExternalBundle {
        val key = "${symbol.uppercase()}:${settings.multiExchangeReferenceEnabled()}:${settings.btcMempoolEnabled()}:${settings.defillamaEnabled()}:${settings.etherscanEnabled()}:${settings.etherscanApiKey().isNotBlank()}:${settings.dropstabUnlocksEnabled()}:${settings.dropstabApiKey().isNotBlank()}"
        val now = System.currentTimeMillis(); val ttl = settings.onchainCacheSeconds() * 1000L
        cache[key]?.takeIf { now - it.first < ttl }?.let { return it.second }
        return supervisorScope {
            val referenceDeferred = async(Dispatchers.IO) { multiExchangeReference(symbol, krakenPrice) }
            val providerDeferred = listOf(
                async(Dispatchers.IO) { btcMempool(symbol) },
                async(Dispatchers.IO) { etherscanGas(symbol) },
                async(Dispatchers.IO) { defiLlama(symbol) },
                async(Dispatchers.IO) { dropstabUnlocks(symbol) }
            )
            val reference = referenceDeferred.await()
            val providers = providerDeferred.map { it.await() }
            val active = providers.filter { it.status == "OK" || it.status == "SUPPORT" || it.status == "RISK" || it.status == "NEUTRAL" }
            val errors = providers.count { it.status == "ERROR" }
            val providerScore = active.sumOf { it.adjustment } - minOf(3, errors)
            val total = (reference.adjustment + providerScore).coerceIn(-16, 10)
            val reason = buildString {
                append("External professional context: ${reference.reason}")
                providers.filter { it.status !in setOf("DISABLED", "UNSUPPORTED") }.forEach { append("; ${it.provider}: ${it.reason}") }
            }
            ProfessionalExternalBundle(total, reference, providers, reason).also { cache[key] = now to it }
        }
    }

    /** Desktop MultiExchangeReferenceFeed upgraded to avoid mixing EUR and USDT in a scored median. */
    private fun multiExchangeReference(symbol: String, krakenPrice: Double): ContextAssessment {
        if (!settings.multiExchangeReferenceEnabled()) return neutral("Multi-exchange reference", "DISABLED", "Multi-exchange reference disabled.")
        if (krakenPrice <= 0.0) return ContextAssessment(false, -8, .8, "Multi-exchange reference", "INVALID", "Invalid Kraken price.")
        val (base, quote) = split(symbol)
        val prices = mutableListOf<Pair<String, Double>>()
        coinbase(base, quote)?.let { prices += "Coinbase" to it }
        coinGecko(base, quote)?.let { prices += "CoinGecko" to it }
        binance(base, quote, sameQuoteOnly = true)?.let { prices += "Binance" to it }
        if (prices.isEmpty()) return neutral("Multi-exchange reference", "NO_PRICE", "No same-quote external reference available from Coinbase/CoinGecko/Binance.")
        val values = prices.map { it.second }.sorted(); val ref = if (values.size % 2 == 1) values[values.size/2] else (values[values.size/2-1]+values[values.size/2])/2.0
        val dev = if (ref > 0) (krakenPrice - ref) / ref * 100.0 else 0.0; val source = prices.joinToString("+") { it.first }
        return when {
            abs(dev) > 1.25 -> ContextAssessment(true, -8, .80, "Multi-exchange reference", "RISK", "Kraken deviates ${"%.2f".format(dev)}% from external same-quote median ($source).")
            abs(dev) < .35 -> ContextAssessment(true, 3, 1.0, "Multi-exchange reference", "SUPPORT", "External same-quote median confirms Kraken within ${"%.2f".format(abs(dev))}% ($source).")
            else -> neutral("Multi-exchange reference", "NEUTRAL", "External reference neutral deviation ${"%.2f".format(dev)}% ($source).")
        }
    }

    private fun btcMempool(symbol: String): ContextAssessment {
        if (!settings.btcMempoolEnabled()) return neutral("mempool.space", "DISABLED", "BTC mempool provider disabled.")
        val base = base(symbol); if (base !in setOf("BTC", "XBT")) return neutral("mempool.space", "UNSUPPORTED", "BTC mempool provider only supports BTC/XBT.")
        return runCatching {
            val fees = getJson("https://mempool.space/api/v1/fees/recommended")
            val mempool = getJson("https://mempool.space/api/mempool")
            val fastest = fees.optDouble("fastestFee", 0.0); val count = mempool.optInt("count", 0)
            when {
                fastest > 120 || count > 250_000 -> ContextAssessment(true, -5, .90, "mempool.space", "RISK", "BTC congestion high: fastest fee ${"%.1f".format(fastest)} sat/vB, mempool $count tx.")
                fastest < 30 && count < 100_000 -> ContextAssessment(true, 2, 1.0, "mempool.space", "SUPPORT", "BTC congestion low/normal: fastest fee ${"%.1f".format(fastest)} sat/vB, mempool $count tx.")
                else -> neutral("mempool.space", "NEUTRAL", "BTC congestion neutral: fastest fee ${"%.1f".format(fastest)} sat/vB, mempool $count tx.")
            }
        }.getOrElse { neutral("mempool.space", "ERROR", "BTC mempool fetch failed: ${it.message}") }
    }

    /** Etherscan V2 equivalent of the desktop gas-oracle provider. */
    private fun etherscanGas(symbol: String): ContextAssessment {
        if (!settings.etherscanEnabled()) return neutral("Etherscan", "DISABLED", "Etherscan gas provider disabled.")
        val key = settings.etherscanApiKey(); if (key.isBlank()) return neutral("Etherscan", "MISSING_API_KEY", "Etherscan enabled but API key is missing.")
        val base = base(symbol); if (base !in setOf("ETH","USDT","USDC","DAI","LINK","ARB","OP","MATIC","POL")) return neutral("Etherscan", "UNSUPPORTED", "Ethereum gas is not relevant for $base.")
        return runCatching {
            val url = "https://api.etherscan.io/v2/api".toHttpUrl().newBuilder()
                .addQueryParameter("chainid", "1").addQueryParameter("module", "gastracker").addQueryParameter("action", "gasoracle").addQueryParameter("apikey", key).build()
            val data = getJson(url.toString()); val result = data.optJSONObject("result") ?: error(data.optString("message", "missing result"))
            val fast = result.optString("FastGasPrice", "0").toDoubleOrNull() ?: 0.0
            when { fast > 120 -> ContextAssessment(true,-4,.90,"Etherscan","RISK","Ethereum gas high: fast ${"%.2f".format(fast)} gwei.")
                fast < 25 -> ContextAssessment(true,2,1.0,"Etherscan","SUPPORT","Ethereum gas low/normal: fast ${"%.2f".format(fast)} gwei.")
                else -> neutral("Etherscan","NEUTRAL","Ethereum gas neutral: fast ${"%.2f".format(fast)} gwei.") }
        }.getOrElse { neutral("Etherscan", "ERROR", "Etherscan fetch failed: ${it.message}") }
    }

    private fun defiLlama(symbol: String): ContextAssessment {
        if (!settings.defillamaEnabled()) return neutral("DefiLlama", "DISABLED", "DefiLlama provider disabled.")
        val b = base(symbol); val chain = assetChain[b]
        return runCatching {
            val stable = getJson("https://stablecoins.llama.fi/stablecoins?includePrices=true")
            val assets = stable.optJSONArray("peggedAssets") ?: JSONArray(); var totalMc = 0.0
            for (i in 0 until minOf(80, assets.length())) totalMc += assets.optJSONObject(i)?.optJSONObject("circulating")?.optDouble("peggedUSD", 0.0) ?: 0.0
            var score = if (totalMc > 0) 1 else 0; val reasons = mutableListOf<String>()
            if (totalMc > 0) reasons += "stablecoin liquidity dataset available ($${"%,.0f".format(totalMc)} tracked)"
            if (chain != null) {
                val arr = getArray("https://api.llama.fi/v2/chains")
                var match: JSONObject? = null
                for (i in 0 until arr.length()) { val row=arr.optJSONObject(i) ?: continue; if (row.optString("name").equals(chain,true) || row.optString("gecko_id").equals(chain,true)) { match=row; break } }
                if (match != null) {
                    val change = match.optDouble("change_1d", 0.0)
                    when { change <= -5 -> { score -= 4; reasons += "$chain TVL stress ${"%.2f".format(change)}% 1d" }
                        change >= 3 -> { score += 2; reasons += "$chain TVL improving ${"%.2f".format(change)}% 1d" }
                        else -> reasons += "$chain TVL neutral ${"%.2f".format(change)}% 1d" }
                } else reasons += "DefiLlama chain mapping not found for $chain"
            } else reasons += "no DefiLlama chain mapping for $b"
            when { score > 1 -> ContextAssessment(true,score.coerceAtMost(4),1.0,"DefiLlama","SUPPORT",reasons.joinToString("; "))
                score < 0 -> ContextAssessment(true,score.coerceAtLeast(-5),.90,"DefiLlama","RISK",reasons.joinToString("; "))
                else -> neutral("DefiLlama","NEUTRAL",reasons.joinToString("; ")) }
        }.getOrElse { neutral("DefiLlama", "ERROR", "DefiLlama fetch failed: ${it.message}") }
    }

    /** Current DropsTab API requires a key; unlike desktop v1.0.50 we do not send unauthenticated requests. */
    private fun dropstabUnlocks(symbol: String): ContextAssessment {
        if (!settings.dropstabUnlocksEnabled()) return neutral("DropsTab TokenUnlocks", "DISABLED", "Token-unlock provider disabled.")
        val key = settings.dropstabApiKey(); if (key.isBlank()) return neutral("DropsTab TokenUnlocks", "MISSING_API_KEY", "DropsTab enabled but API key is missing.")
        val query = if (base(symbol) == "POL") "MATIC" else base(symbol)
        return runCatching {
            val url = "https://public-api.dropstab.com/api/v1/tokenUnlocks".toHttpUrl().newBuilder().addQueryParameter("sortingOrder","ASC").addQueryParameter("pageSize","100").build()
            val req = Request.Builder().url(url).header("x-dropstab-api-key", key).header("Authorization", "Bearer $key").get().build()
            val body = http.newCall(req).execute().use { r -> if (!r.isSuccessful) error("HTTP ${r.code}"); r.body?.string().orEmpty() }
            val root = JSONObject(body); val items = root.optJSONArray("data") ?: JSONArray(); var match: JSONObject? = null
            for (i in 0 until items.length()) {
                val row = items.optJSONObject(i) ?: continue; val token = row.optJSONObject("token") ?: row.optJSONObject("coin") ?: row
                if (token.optString("symbol", row.optString("symbol")).equals(query,true)) { match=row; break }
            }
            if (match == null) return neutral("DropsTab TokenUnlocks","NEUTRAL","No upcoming unlock entry found for $query.")
            val pctKeys=listOf("nextUnlockPercent","next_unlock_percent","unlockPercent","percent","percentage")
            val usdKeys=listOf("nextUnlockValue","next_unlock_value","unlockValue","valueUsd","amountUsd")
            val pct=pctKeys.maxOfOrNull{match.optDouble(it,0.0)}?:0.0; val usd=usdKeys.maxOfOrNull{match.optDouble(it,0.0)}?:0.0
            when { pct>=3 || usd>=50_000_000 -> ContextAssessment(true,-7,.75,"DropsTab TokenUnlocks","RISK","Upcoming $query unlock risk: ${"%.2f".format(pct)}% / $${"%,.0f".format(usd)}.")
                pct>=1 || usd>=10_000_000 -> ContextAssessment(true,-3,.90,"DropsTab TokenUnlocks","RISK","Moderate $query unlock risk: ${"%.2f".format(pct)}% / $${"%,.0f".format(usd)}.")
                else -> neutral("DropsTab TokenUnlocks","NEUTRAL","$query token unlock risk low/neutral.") }
        }.getOrElse { neutral("DropsTab TokenUnlocks", "ERROR", "DropsTab token-unlock fetch failed: ${it.message}") }
    }

    private fun coinbase(base: String, quote: String): Double? = runCatching {
        val body = getJson("https://api.exchange.coinbase.com/products/$base-$quote/ticker"); body.optString("price").toDoubleOrNull()?.takeIf { it > 0 }
    }.getOrNull()

    private fun binance(base: String, quote: String, sameQuoteOnly: Boolean): Double? {
        val candidates = if (sameQuoteOnly) listOf("$base$quote") else listOf("$base$quote", "${base}USDT").distinct()
        for (symbol in candidates) runCatching {
            val url="https://api.binance.com/api/v3/ticker/price".toHttpUrl().newBuilder().addQueryParameter("symbol",symbol).build(); val p=getJson(url.toString()).optString("price").toDoubleOrNull(); if(p!=null&&p>0)return p
        }
        return null
    }

    private fun coinGecko(base: String, quote: String): Double? {
        val id = coinGeckoIds[base.uppercase()] ?: return null
        return runCatching {
            val url="https://api.coingecko.com/api/v3/simple/price".toHttpUrl().newBuilder().addQueryParameter("ids",id).addQueryParameter("vs_currencies",quote.lowercase()).build()
            getJson(url.toString()).optJSONObject(id)?.optDouble(quote.lowercase(),0.0)?.takeIf{it>0}
        }.getOrNull()
    }

    private fun getJson(url: String): JSONObject {
        val req=Request.Builder().url(url).header("User-Agent","CryptoTradeStation-Android/4.0").get().build()
        val body=http.newCall(req).execute().use{r->if(!r.isSuccessful)error("HTTP ${r.code}");r.body?.string().orEmpty()}
        return JSONObject(body)
    }
    private fun getArray(url: String): JSONArray {
        val req=Request.Builder().url(url).header("User-Agent","CryptoTradeStation-Android/4.0").get().build()
        val body=http.newCall(req).execute().use{r->if(!r.isSuccessful)error("HTTP ${r.code}");r.body?.string().orEmpty()}
        return JSONArray(body)
    }
    private fun split(symbol:String):Pair<String,String>{val clean=symbol.uppercase().replace("/","").replace("-","").replace("XBT","BTC");val q=listOf("USDT","USDC","EUR","USD","GBP").firstOrNull{clean.endsWith(it)}?:"EUR";return clean.removeSuffix(q) to q}
    private fun base(symbol:String):String=split(symbol).first
    private fun neutral(provider:String,status:String,reason:String)=ContextAssessment(true,0,1.0,provider,status,reason)

    companion object {
        private val coinGeckoIds=mapOf("BTC" to "bitcoin","ETH" to "ethereum","SOL" to "solana","XRP" to "ripple","ADA" to "cardano","DOGE" to "dogecoin","DOT" to "polkadot","LINK" to "chainlink","AVAX" to "avalanche-2","MATIC" to "matic-network","LTC" to "litecoin","BCH" to "bitcoin-cash")
        private val assetChain=mapOf("BTC" to "bitcoin","ETH" to "ethereum","SOL" to "solana","ADA" to "cardano","XRP" to "ripple","DOGE" to "dogecoin","DOT" to "polkadot","MATIC" to "polygon","POL" to "polygon","AVAX" to "avalanche","ARB" to "arbitrum","OP" to "optimism","APT" to "aptos","SUI" to "sui","INJ" to "injective","SEI" to "sei","TIA" to "celestia")
    }
}
