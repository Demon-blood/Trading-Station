package com.ksp.cryptobot.alerts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class RemoteAlertClient(
    private val http: OkHttpClient = OkHttpClient()
) {
    suspend fun sendTelegram(botToken: String, chatId: String, text: String): Boolean = withContext(Dispatchers.IO) {
        if (botToken.isBlank() || chatId.isBlank()) return@withContext false
        val url = "https://api.telegram.org/bot${botToken.trim()}/sendMessage"
        val body = JSONObject()
            .put("chat_id", chatId.trim())
            .put("text", text.take(3900))
            .put("disable_web_page_preview", true)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        http.newCall(request).execute().use { response -> response.isSuccessful }
    }

    suspend fun sendDiscord(webhookUrl: String, text: String): Boolean = withContext(Dispatchers.IO) {
        if (webhookUrl.isBlank()) return@withContext false
        val body = JSONObject()
            .put("content", text.take(1900))
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(webhookUrl.trim()).post(body).build()
        http.newCall(request).execute().use { response -> response.isSuccessful }
    }
}
