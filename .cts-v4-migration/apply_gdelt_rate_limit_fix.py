#!/usr/bin/env python3
"""Apply GDELT global request pacing and per-symbol cache."""
from __future__ import annotations

import sys
from pathlib import Path

GDELT_SOURCE = 'package com.ksp.cryptobot.news\n\nimport com.ksp.cryptobot.core.NewsArticle\nimport com.squareup.moshi.Json\nimport com.squareup.moshi.Moshi\nimport com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory\nimport kotlinx.coroutines.Dispatchers\nimport kotlinx.coroutines.withContext\nimport okhttp3.HttpUrl.Companion.toHttpUrl\nimport okhttp3.OkHttpClient\nimport okhttp3.Request\nimport java.time.Instant\nimport java.time.LocalDateTime\nimport java.time.ZoneOffset\nimport java.time.format.DateTimeFormatter\n\nprivate data class GdeltCacheEntry(\n    val fetchedAtEpochMs: Long,\n    val rows: List<NewsArticle>\n)\n\nclass GdeltNewsClient(\n    private val baseUrl: String = "https://api.gdeltproject.org"\n) : NewsClient {\n    private val client = OkHttpClient.Builder().build()\n    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()\n    private val adapter = moshi.adapter(GdeltResponse::class.java)\n\n    override suspend fun latestCryptoNews(symbol: String): List<NewsArticle> = withContext(Dispatchers.IO) {\n        val normalizedSymbol = symbol.uppercase().replace("/", "").replace("-", "").replace("_", "")\n        val now = System.currentTimeMillis()\n\n        // Fresh symbol data is reused. If another symbol arrives before the\n        // global six-second request window opens, use stale cache when possible\n        // or fail-neutral for this pass. Never sleep/block the trading scan.\n        val decision: Pair<Boolean, List<NewsArticle>?> = synchronized(throttleLock) {\n            val cached = cache[normalizedSymbol]\n            val cacheAge = cached?.let { now - it.fetchedAtEpochMs } ?: Long.MAX_VALUE\n            when {\n                cached != null && cacheAge in 0L..CACHE_TTL_MS ->\n                    false to cached.rows\n                now - lastRemoteAttemptEpochMs < MIN_REMOTE_INTERVAL_MS ->\n                    false to cached?.takeIf {\n                        cacheAge in 0L..STALE_CACHE_MAX_AGE_MS\n                    }?.rows\n                else -> {\n                    lastRemoteAttemptEpochMs = now\n                    true to null\n                }\n            }\n        }\n\n        if (!decision.first) {\n            return@withContext decision.second.orEmpty()\n        }\n\n        val base = baseAssetFromSymbol(symbol)\n        val query = when (base) {\n            "BTC" -> "(bitcoin OR BTC) crypto"\n            "ETH" -> "(ethereum OR ETH) crypto"\n            "SOL" -> "(solana OR SOL) crypto"\n            "XRP" -> "(ripple OR XRP) crypto"\n            else -> "($base crypto OR $base cryptocurrency OR $base blockchain)"\n        }\n\n        val url = "$baseUrl/api/v2/doc/doc".toHttpUrl().newBuilder()\n            .addQueryParameter("query", query)\n            .addQueryParameter("mode", "ArtList")\n            .addQueryParameter("format", "json")\n            .addQueryParameter("maxrecords", "25")\n            .addQueryParameter("sort", "HybridRel")\n            .build()\n\n        val request = Request.Builder().url(url).get().build()\n        client.newCall(request).execute().use { response ->\n            val body = response.body?.string().orEmpty()\n            if (!response.isSuccessful) error("GDELT HTTP ${response.code}: ${body.take(180)}")\n\n            val parsed = adapter.fromJson(body)\n            val rows = parsed?.articles.orEmpty().mapNotNull { item ->\n                val title = item.title ?: return@mapNotNull null\n                NewsArticle(\n                    title = title,\n                    description = item.seendate.orEmpty(),\n                    source = "GDELT:${item.sourceCountry.orEmpty()}",\n                    url = item.url.orEmpty(),\n                    publishedAt = item.seendate?.let { parseGdeltDate(it) }\n                )\n            }\n\n            synchronized(throttleLock) {\n                cache[normalizedSymbol] = GdeltCacheEntry(now, rows)\n                while (cache.size > MAX_CACHE_SYMBOLS) {\n                    val oldestKey = cache.minByOrNull { it.value.fetchedAtEpochMs }?.key ?: break\n                    cache.remove(oldestKey)\n                }\n            }\n            rows\n        }\n    }\n\n    private fun parseGdeltDate(raw: String): Instant? = runCatching {\n        LocalDateTime.parse(raw.take(14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))\n            .toInstant(ZoneOffset.UTC)\n    }.getOrNull()\n\n    private fun baseAssetFromSymbol(symbol: String): String {\n        val clean = symbol.uppercase().replace("/", "").replace("-", "").replace("_", "")\n        val quotes = listOf("ZEUR", "ZUSD", "EUR", "USD", "USDT", "USDC", "GBP", "BTC", "ETH")\n        val raw = quotes.firstOrNull { clean.endsWith(it) }?.let { clean.removeSuffix(it) } ?: clean\n        return when (raw) {\n            "XBT", "XXBT" -> "BTC"\n            "XETH" -> "ETH"\n            "XXRP" -> "XRP"\n            else -> raw.removePrefix("X").removePrefix("Z")\n        }\n    }\n\n    companion object {\n        // GDELT\'s own 429 response asks clients to stay at or below one request\n        // every five seconds. CTS uses six seconds for headroom.\n        private const val MIN_REMOTE_INTERVAL_MS = 6_000L\n\n        // News does not need a new GDELT request every 30/60-second trading scan.\n        private const val CACHE_TTL_MS = 15L * 60L * 1000L\n\n        // Previously fetched context remains usable during transient trouble.\n        private const val STALE_CACHE_MAX_AGE_MS = 60L * 60L * 1000L\n\n        private const val MAX_CACHE_SYMBOLS = 500\n\n        private val throttleLock = Any()\n        private var lastRemoteAttemptEpochMs: Long = 0L\n        private val cache = linkedMapOf<String, GdeltCacheEntry>()\n    }\n}\n\ndata class GdeltResponse(val articles: List<GdeltArticle>? = emptyList())\n\ndata class GdeltArticle(\n    val url: String?,\n    val title: String?,\n    val seendate: String?,\n    @Json(name = "sourcecountry") val sourceCountry: String?\n)\n'

def fail(message: str) -> None:
    raise SystemExit(f"[CTS GDELT rate-limit fix] {message}")

def main() -> None:
    if len(sys.argv) != 2:
        fail("usage: apply_gdelt_rate_limit_fix.py <repo-root>")

    repo = Path(sys.argv[1]).resolve()
    target = repo / "app/src/main/java/com/ksp/cryptobot/news/GdeltNewsClient.kt"
    if not target.exists():
        fail(f"missing {target}")

    target.write_text(GDELT_SOURCE.rstrip() + "\n", encoding="utf-8")

    effective = target.read_text(encoding="utf-8")
    checks = {
        "global 6-second request spacing": "MIN_REMOTE_INTERVAL_MS = 6_000L" in effective,
        "15-minute per-symbol cache": "CACHE_TTL_MS = 15L * 60L * 1000L" in effective,
        "non-blocking trading scan": "return@withContext decision.second.orEmpty()" in effective,
        "bounded symbol cache": "MAX_CACHE_SYMBOLS = 500" in effective,
        "stale-cache fallback": "STALE_CACHE_MAX_AGE_MS" in effective,
    }

    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)

    failed = [name for name, ok in checks.items() if not ok]
    if failed:
        fail("validation failed: " + ", ".join(failed))

    print("[CTS GDELT rate-limit fix] Applied successfully.")

if __name__ == "__main__":
    main()
