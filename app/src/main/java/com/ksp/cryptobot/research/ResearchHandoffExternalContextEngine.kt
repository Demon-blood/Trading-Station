package com.ksp.cryptobot.research

import com.ksp.cryptobot.data.ResearchDao
import com.ksp.cryptobot.data.ResearchStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Public, optional regime data. Missing external context is neutral, never silently bullish. */
class ResearchHandoffExternalContextEngine(private val dao: ResearchDao) {
    private val http = OkHttpClient.Builder().build()
    @Volatile private var cache: DominanceContext? = null

    data class DominanceContext(val valuePct: Double?, val changePct: Double?, val status: String, val reason: String, val updatedAt: Long)

    suspend fun btcDominance(force: Boolean = false): DominanceContext {
        val now = System.currentTimeMillis()
        cache?.let { if (!force && now - it.updatedAt < 15 * 60_000L) return it }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url("https://api.coingecko.com/api/v3/global").get().build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) error("CoinGecko global HTTP ${res.code}")
                    val root = JSONObject(res.body?.string().orEmpty())
                    val value = root.getJSONObject("data").getJSONObject("market_cap_percentage").optDouble("btc", Double.NaN)
                    if (!value.isFinite()) error("BTC dominance missing")
                    value
                }
            }
        }
        val previous = dao.state("handoff_btc_dominance")
        val prevParts = previous?.value?.split('|')
        val prevValue = prevParts?.getOrNull(0)?.toDoubleOrNull()
        val prevAt = prevParts?.getOrNull(1)?.toLongOrNull() ?: previous?.updatedAtEpochMs ?: 0L
        val out = result.fold(
            onSuccess = { value ->
                val change = if (prevValue != null && now - prevAt >= 6 * 60 * 60_000L) value - prevValue else null
                dao.putState(ResearchStateEntity("handoff_btc_dominance", "$value|$now", now))
                DominanceContext(value, change, "OK", "BTC dominance from CoinGecko global market-cap percentage. Trend delta is only computed against a persisted sample >=6h old and is an app formalization.", now)
            },
            onFailure = { error -> DominanceContext(null, null, "UNAVAILABLE", "BTC dominance unavailable: ${error.message}; fail-neutral per handoff truth rule.", now) }
        )
        cache = out
        return out
    }
}
