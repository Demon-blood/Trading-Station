package com.ksp.cryptobot.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val store = RuntimeHostStateStore(context)
        val state = store.snapshot()
        if (!state.desiredRunning || !state.resumeAfterBoot) {
            return
        }

        val serviceIntent = Intent(context, BotForegroundService::class.java).apply {
            action = BotForegroundService.ACTION_RECOVER
            putExtra(BotForegroundService.EXTRA_RECOVERY_REASON, intent.action ?: "BOOT")
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }.onFailure {
            store.failure("Boot/package recovery start failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }
}
