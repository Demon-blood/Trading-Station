#!/usr/bin/env python3
from pathlib import Path
import sys

def main():
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    manifest = (repo / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    service = (repo / "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt").read_text(encoding="utf-8")
    boot = (repo / "app/src/main/java/com/ksp/cryptobot/service/BootReceiver.kt").read_text(encoding="utf-8")
    state = (repo / "app/src/main/java/com/ksp/cryptobot/service/RuntimeHostStateStore.kt").read_text(encoding="utf-8")
    net = (repo / "app/src/main/java/com/ksp/cryptobot/service/RuntimeConnectivityMonitor.kt").read_text(encoding="utf-8")
    health = (repo / "app/src/main/java/com/ksp/cryptobot/service/RuntimeHostHealthInspector.kt").read_text(encoding="utf-8")

    checks = {
        "specialUse permission": "FOREGROUND_SERVICE_SPECIAL_USE" in manifest,
        "dataSync permission removed": "FOREGROUND_SERVICE_DATA_SYNC" not in manifest,
        "specialUse service type": 'android:foregroundServiceType="specialUse"' in manifest,
        "specialUse subtype explanation": "PROPERTY_SPECIAL_USE_FGS_SUBTYPE" in manifest and "cryptocurrency" in manifest,
        "boot completed receiver": "android.intent.action.BOOT_COMPLETED" in manifest,
        "package replaced receiver": "android.intent.action.MY_PACKAGE_REPLACED" in manifest,
        "no exact-alarm workaround": "SCHEDULE_EXACT_ALARM" not in manifest and "AlarmManager" not in service,
        "ServiceCompat foreground type": "FOREGROUND_SERVICE_TYPE_SPECIAL_USE" in service and "ServiceCompat.startForeground" in service,
        "sticky restart gated by durable intent": "sticky-process-restart" in service and "desiredRunning" in service,
        "explicit continuous-run intent": 'requestContinuousRun("USER_BACKGROUND_AUTO", resumeAfterBoot = true)' in service,
        "manual stop clears durable run": "requestStop(" in service,
        "network validation pause": "Network not validated" in service and "network.usable" in service,
        "network recovery reconciliation": "NETWORK_RECOVERED_RECONCILING" in service and 'reconcileAfterRecovery(settingsStore.load(), "network-recovery")' in service,
        "live recovery uses Kraken health": "controller.runKrakenDataHealth(settings)" in service,
        "live recovery refreshes open orders": "controller.loadOpenOrdersSnapshot(settings)" in service,
        "live recovery refreshes lifecycle": "controller.loadLifecycleSnapshot(settings)" in service,
        "live recovery refreshes portfolio": "controller.loadPortfolioSnapshot(settings)" in service,
        "new scans paused until recovery": "New scans/orders remain paused until retry" in service,
        "process/task removal preserves intent only": "TASK_REMOVED_HOST_INTENT_PRESERVED" in service,
        "defensive timeout": "override fun onTimeout" in service,
        "boot respects opt-in": "state.desiredRunning" in boot and "state.resumeAfterBoot" in boot,
        "boot uses foreground service": "startForegroundService" in boot,
        "host state durability": 'getSharedPreferences(PREFS, Context.MODE_PRIVATE)' in state,
        "validated internet capability": "NET_CAPABILITY_VALIDATED" in net,
        "wifi/cellular transition visibility": "TRANSPORT_WIFI" in net and "TRANSPORT_CELLULAR" in net,
        "background restriction health": "isBackgroundRestricted" in health,
        "battery optimization health": "isIgnoringBatteryOptimizations" in health,
        "notification health": "areNotificationsEnabled" in health,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)
    if failed:
        raise SystemExit("M3 host verification failed: " + ", ".join(failed))
    print("\nPASS | M3 Android runtime-host contracts satisfied.")

if __name__ == "__main__":
    main()
