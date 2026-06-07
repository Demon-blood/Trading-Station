package com.ksp.cryptobot.status

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BotStatusStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("bot_live_status", Context.MODE_PRIVATE)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun write(message: String, level: String = "INFO") {
        val now = System.currentTimeMillis()
        val clean = message.replace('\n', ' ').take(500)
        val line = "${timeFormat.format(Date(now))} [$level] $clean"
        val current = prefs.getString(KEY_HISTORY, "").orEmpty()
            .lines()
            .filter { it.isNotBlank() }
        val updated = (listOf(line) + current).take(80).joinToString("\n")
        prefs.edit()
            .putString(KEY_TEXT, clean)
            .putString(KEY_LEVEL, level)
            .putLong(KEY_TIME, now)
            .putString(KEY_HISTORY, updated)
            .apply()
    }

    fun latestText(): String = prefs.getString(KEY_TEXT, "Ready").orEmpty().ifBlank { "Ready" }
    fun latestLevel(): String = prefs.getString(KEY_LEVEL, "INFO").orEmpty().ifBlank { "INFO" }
    fun latestTimeMs(): Long = prefs.getLong(KEY_TIME, 0L)
    fun recentLines(limit: Int = 40): List<String> = prefs.getString(KEY_HISTORY, "").orEmpty()
        .lines()
        .filter { it.isNotBlank() }
        .take(limit)

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
        write("Status log cleared.")
    }

    companion object {
        private const val KEY_TEXT = "latest_text"
        private const val KEY_LEVEL = "latest_level"
        private const val KEY_TIME = "latest_time_ms"
        private const val KEY_HISTORY = "history"
    }
}
