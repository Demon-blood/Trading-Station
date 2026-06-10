package com.ksp.cryptobot.alerts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class RemoteCommandMessage(
    val source: String,
    val id: String,
    val chatId: String,
    val text: String
)

class RemoteCommandClient(
    private val http: OkHttpClient = OkHttpClient()
) {
    suspend fun pollTelegram(
        botToken: String,
        allowedChatId: String,
        offset: Long
    ): Pair<List<RemoteCommandMessage>, Long> = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || allowedChatId.isBlank()) return@withContext emptyList<RemoteCommandMessage>() to offset
        val url = "https://api.telegram.org/bot${botToken.trim()}/getUpdates?timeout=0&limit=20${if (offset > 0) "&offset=$offset" else ""}"
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Telegram getUpdates HTTP ${res.code}")
            val body = res.body?.string() ?: error("Telegram getUpdates empty response")
            val root = JSONObject(body)
            val result = root.optJSONArray("result") ?: JSONArray()
            var nextOffset = offset
            val messages = mutableListOf<RemoteCommandMessage>()
            for (i in 0 until result.length()) {
                val update = result.getJSONObject(i)
                val updateId = update.optLong("update_id")
                if (updateId >= nextOffset) nextOffset = updateId + 1
                val msg = update.optJSONObject("message") ?: update.optJSONObject("edited_message") ?: continue
                val chat = msg.optJSONObject("chat") ?: continue
                val chatId = chat.optLong("id").toString()
                if (chatId != allowedChatId.trim()) continue
                val text = msg.optString("text").trim()
                if (text.isBlank()) continue
                messages += RemoteCommandMessage(
                    source = "telegram",
                    id = updateId.toString(),
                    chatId = chatId,
                    text = text
                )
            }
            if (offset <= 0L) emptyList<RemoteCommandMessage>() to nextOffset else messages to nextOffset
        }
    }

    suspend fun sendTelegram(botToken: String, chatId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) return@withContext false
        val url = "https://api.telegram.org/bot${botToken.trim()}/sendMessage"
        val body = JSONObject()
            .put("chat_id", chatId.trim())
            .put("text", text.take(3900))
            .put("disable_web_page_preview", true)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        http.newCall(req).execute().use { it.isSuccessful }
    }

    suspend fun pollDiscord(
        botToken: String,
        channelId: String,
        afterMessageId: String?
    ): Pair<List<RemoteCommandMessage>, String?> = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || channelId.isBlank()) return@withContext emptyList<RemoteCommandMessage>() to afterMessageId
        val suffix = if (!afterMessageId.isNullOrBlank()) "?limit=20&after=$afterMessageId" else "?limit=20"
        val req = Request.Builder()
            .url("https://discord.com/api/v10/channels/${channelId.trim()}/messages$suffix")
            .get()
            .header("Authorization", "Bot ${botToken.trim()}")
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("Discord messages HTTP ${res.code}")
            val arr = JSONArray(res.body?.string() ?: "[]")
            var newest = afterMessageId
            val rows = mutableListOf<RemoteCommandMessage>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val id = item.optString("id")
                if (id.isNotBlank() && (newest.isNullOrBlank() || id > newest!!)) newest = id
                val author = item.optJSONObject("author")
                if (author?.optBoolean("bot") == true) continue
                val text = item.optString("content").trim()
                if (text.isBlank()) continue
                rows += RemoteCommandMessage(
                    source = "discord",
                    id = id,
                    chatId = channelId.trim(),
                    text = text
                )
            }
            val ordered = rows.sortedBy { it.id }
            if (afterMessageId.isNullOrBlank()) emptyList<RemoteCommandMessage>() to newest else ordered to newest
        }
    }

    suspend fun sendDiscordBotMessage(botToken: String, channelId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || channelId.isBlank()) return@withContext false
        val body = JSONObject()
            .put("content", text.take(1900))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url("https://discord.com/api/v10/channels/${channelId.trim()}/messages")
            .post(body)
            .header("Authorization", "Bot ${botToken.trim()}")
            .build()
        http.newCall(req).execute().use { it.isSuccessful }
    }
}
