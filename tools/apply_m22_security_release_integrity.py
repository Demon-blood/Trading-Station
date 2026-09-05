#!/usr/bin/env python3
from pathlib import Path
import os
import sys

PAYLOAD_FILES = [
    "app/src/main/java/com/ksp/cryptobot/exchange/KrakenApiKeySecurity.kt",
    "app/src/main/java/com/ksp/cryptobot/security/SecureSettingsStore.kt",
    "app/src/test/java/com/ksp/cryptobot/exchange/KrakenApiKeySecurityPolicyM22Test.kt",
    "app/src/test/java/com/ksp/cryptobot/exchange/KrakenApiKeySecurityRuntimeM22Test.kt",
    "app/src/test/java/com/ksp/cryptobot/security/SecureSettingsContractM22Test.kt",
]

def fail(msg):
    raise SystemExit("ERROR | " + msg)

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        fail(f"{label}: expected one match, got {count}")
    return text.replace(old, new, 1)

def main():
    print("INFO | M22 applier revision v1.2")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    if not (repo / ".git").exists():
        fail("Not a git checkout")
    if not (repo / "tools/verify_m21_chaos_recovery.py").exists():
        fail("M21 prerequisite missing from main")

    release_workflow = repo / ".github/workflows/android-release-apk.yml"
    osv_workflow = repo / ".github/workflows/osv-scanner.yml"
    if not release_workflow.exists():
        fail("M22 protected workflow bootstrap missing: .github/workflows/android-release-apk.yml")
    if not osv_workflow.exists():
        fail("M22 protected workflow bootstrap missing: .github/workflows/osv-scanner.yml")

    release_text = release_workflow.read_text(encoding="utf-8")
    osv_text = osv_workflow.read_text(encoding="utf-8")
    if "ANDROID_EXPECTED_CERT_SHA256" not in release_text or "verify --verbose --print-certs" not in release_text:
        fail("M22 signed-release workflow on main is not the hardened bootstrap revision.")
    if "releaseRuntimeClasspath" not in osv_text or "--lockfile=osv-scanner:build/m22-runtime-osv-scanner.json" not in osv_text:
        fail("M22 OSV workflow on main is not the runtime-scoped bootstrap revision.")

    dirty = os.popen(
        f'cd "{repo}" && git status --porcelain -- app gradle'
    ).read().strip()
    if dirty:
        fail("Refusing to patch dirty M22 target tree:\n" + dirty)

    payload = Path(__file__).resolve().parent / "m22_payload"
    for rel in PAYLOAD_FILES:
        src = payload / rel
        dst = repo / rel
        if not src.exists():
            fail(f"M22 payload missing: {rel}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        dst.write_text(src.read_text(encoding="utf-8").rstrip() + "\n", encoding="utf-8")
        copied = dst.read_bytes()
        if copied.endswith(b"\\n"):
            fail(f"M22 payload copy produced literal backslash-n EOF: {rel}")
        if not copied.endswith(b"\n"):
            fail(f"M22 payload copy missing real newline EOF: {rel}")
        print("WRITE |", rel)

    p = repo / "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt"
    t = p.read_text(encoding="utf-8")

    security_method = '''
    suspend fun getApiKeySecurityInfo(): KrakenApiKeySecurityInfo = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) {
            error("Kraken credentials are required for API-key security inspection.")
        }
        val root = privateJson("/0/private/GetApiKeyInfo", emptyMap())
        val result = root.optJSONObject("result")
            ?: error("Kraken GetApiKeyInfo returned no result.")

        val permissions = linkedSetOf<String>()
        val permissionsArray = result.optJSONArray("permissions")
        if (permissionsArray != null) {
            for (i in 0 until permissionsArray.length()) {
                permissionsArray.optString(i, "")
                    .trim()
                    .lowercase()
                    .takeIf { it.isNotBlank() }
                    ?.let { permissions += it }
            }
        }

        val ipAllowlist = mutableListOf<String>()
        val ipArray = result.optJSONArray("ipAllowlist")
        if (ipArray != null) {
            for (i in 0 until ipArray.length()) {
                ipArray.optString(i, "")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { ipAllowlist += it }
            }
        }

        KrakenApiKeySecurityInfo(
            keyFingerprint = KrakenApiKeySecurityRuntime.fingerprint(apiKey),
            keyName = result.optString("apiKeyName", "").trim(),
            permissions = permissions,
            validUntilEpochSeconds = result.optString("validUntil", "0").toLongOrNull() ?: 0L,
            ipAllowlist = ipAllowlist
        )
    }

'''
    marker = "    suspend fun accountAuthorityIdentity(): KrakenAccountAuthorityIdentity = withContext(Dispatchers.IO) {"
    if marker not in t:
        fail("M22 Kraken GetApiKeyInfo insertion anchor missing")
    t = t.replace(marker, security_method + marker, 1)

    t = replace_once(
        t,
        '''    override suspend fun placeOrder(request: OrderRequest): OrderResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Kraken API key and private key are required for live trading.")
        val rule = resolvePairRule(request.symbol)
''',
        '''    override suspend fun placeOrder(request: OrderRequest): OrderResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || secretKey.isBlank()) error("Kraken API key and private key are required for live trading.")
        if (request.side == OrderSide.BUY) {
            val securityGate = KrakenApiKeySecurityRuntime.gateForNewBuy(apiKey)
            if (!securityGate.first) {
                error("M22 Kraken API-key security gate blocks BUY: ${securityGate.second}")
            }
        }
        val rule = resolvePairRule(request.symbol)
''',
        "M22 Kraken BUY permission gate"
    )
    p.write_text(t.rstrip() + "\n", encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/settings/AppSettingsStore.kt"
    t = p.read_text(encoding="utf-8")
    start = t.find("    fun secureBackupMap(): Map<String, String> {")
    end = t.find("\n    fun restoreSecureBackupMap(values: Map<String, String>) {", start)
    if start < 0 or end < 0:
        fail("M22 secureBackupMap block anchor missing")
    replacement = '''    /**
     * M22: ordinary backup/export intentionally excludes every secret.
     *
     * Credentials must be re-entered after a normal restore. The legacy restore method
     * remains below only for an explicit, user-supplied migration payload; this method
     * will no longer generate such plaintext payloads.
     */
    fun secureBackupMap(): Map<String, String> = emptyMap()
'''
    t = t[:start] + replacement + t[end:]
    p.write_text(t.rstrip() + "\n", encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    p = repo / "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt"
    t = p.read_text(encoding="utf-8")

    t = replace_once(
        t,
        '''import com.ksp.cryptobot.exchange.KrakenRealtimeMarketDataRegistry
import com.ksp.cryptobot.exchange.KrakenPrivateExecutionRegistry
''',
        '''import com.ksp.cryptobot.exchange.KrakenRealtimeMarketDataRegistry
import com.ksp.cryptobot.exchange.KrakenPrivateExecutionRegistry
import com.ksp.cryptobot.exchange.KrakenSpotClient
import com.ksp.cryptobot.exchange.KrakenApiKeySecurityPolicy
import com.ksp.cryptobot.exchange.KrakenApiKeySecurityRuntime
''',
        "M22 service security imports"
    )

    t = replace_once(
        t,
        '''    @Volatile
    private var loopJob: Job? = null
''',
        '''    @Volatile
    private var loopJob: Job? = null

    @Volatile
    private var lastKrakenApiKeySecurityCheckMs: Long = 0L
''',
        "M22 service security timestamp"
    )

    t = replace_once(
        t,
        '''            configureRealtimeMarketData(startSettings, connectivity.snapshot.usable)
            configurePrivateExecutionState(startSettings, connectivity.snapshot.usable)

            if (startSettings.mode != BotMode.PAPER && startSettings.exchangeProvider != ExchangeProvider.PAPER) {
''',
        '''            configureRealtimeMarketData(startSettings, connectivity.snapshot.usable)
            configurePrivateExecutionState(startSettings, connectivity.snapshot.usable)

            if (startSettings.mode != BotMode.PAPER &&
                startSettings.exchangeProvider == ExchangeProvider.KRAKEN
            ) {
                updateNotification("Inspecting Kraken API-key permissions…")
                if (!refreshKrakenApiKeySecurity(startSettings, "startup:$recoveryReason")) {
                    hostStore.failure("LIVE blocked: Kraken API-key permission security check failed.")
                    updateNotification("LIVE blocked: unsafe Kraken API key")
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }
            }

            if (startSettings.mode != BotMode.PAPER && startSettings.exchangeProvider != ExchangeProvider.PAPER) {
''',
        "M22 startup key-security prerequisite"
    )

    t = replace_once(
        t,
        '''                if (!network.usable) {
                    lastNetworkUsable = false
                    KrakenPrivateExecutionRegistry.markRecoveryUnknown(
                        "Validated network unavailable during service cycle."
                    )
''',
        '''                if (!network.usable) {
                    lastNetworkUsable = false
                    KrakenPrivateExecutionRegistry.markRecoveryUnknown(
                        "Validated network unavailable during service cycle."
                    )
                    KrakenApiKeySecurityRuntime.markUnknown(
                        "Validated network unavailable; server-side API-key permissions cannot be considered current."
                    )
                    lastKrakenApiKeySecurityCheckMs = 0L
''',
        "M22 network loss key-security unknown"
    )

    t = replace_once(
        t,
        '''                if (current.mode != BotMode.PAPER && current.exchangeProvider == ExchangeProvider.KRAKEN) {
                    val dms = dmsSafety.ensureDisarmed(current, "service-cycle")
''',
        '''                if (current.mode != BotMode.PAPER && current.exchangeProvider == ExchangeProvider.KRAKEN) {
                    val now = System.currentTimeMillis()
                    if (now - lastKrakenApiKeySecurityCheckMs >= KRAKEN_KEY_SECURITY_RECHECK_MS) {
                        val keySafe = refreshKrakenApiKeySecurity(current, "service-cycle")
                        if (!keySafe) {
                            statusStore.write(
                                "Kraken API-key security is unsafe/unknown. New BUYs remain blocked; protective SELLs and risk reduction remain available.",
                                "ERROR"
                            )
                        }
                    }

                    val dms = dmsSafety.ensureDisarmed(current, "service-cycle")
''',
        "M22 recurring key-security inspection"
    )

    helper_marker = '''    private fun configureRealtimeMarketData(
'''
    helper = '''    private suspend fun refreshKrakenApiKeySecurity(
        settings: com.ksp.cryptobot.core.BotSettings,
        reason: String
    ): Boolean {
        if (settings.mode == BotMode.PAPER || settings.exchangeProvider != ExchangeProvider.KRAKEN) {
            KrakenApiKeySecurityRuntime.markUnknown("Kraken LIVE permission inspection is not active in this mode.")
            return true
        }

        val key = settingsStore.exchangeApiKey(ExchangeProvider.KRAKEN).orEmpty()
        val secret = settingsStore.exchangeSecretKey(ExchangeProvider.KRAKEN).orEmpty()
        if (key.isBlank() || secret.isBlank()) {
            KrakenApiKeySecurityRuntime.markUnknown("Kraken credentials are missing.")
            statusStore.write("Kraken API-key security check blocked: credentials are missing.", "ERROR")
            return false
        }

        return runCatching {
            val info = KrakenSpotClient(key, secret).getApiKeySecurityInfo()
            val assessment = KrakenApiKeySecurityPolicy.assess(info)
            KrakenApiKeySecurityRuntime.publish(assessment)
            lastKrakenApiKeySecurityCheckMs = assessment.checkedAtEpochMs
            statusStore.write(
                "Kraken API-key security [$reason]: ${assessment.reason}",
                if (assessment.safeForLive) "INFO" else "ERROR"
            )
            assessment.safeForLive
        }.getOrElse { error ->
            KrakenApiKeySecurityRuntime.markUnknown(
                "Permission inspection failed after $reason: ${error.message ?: error.javaClass.simpleName}"
            )
            statusStore.write(
                "Kraken API-key security inspection failed after $reason: ${error.message ?: error.javaClass.simpleName}. New BUYs remain fail-closed.",
                "ERROR"
            )
            false
        }
    }

'''
    if helper_marker not in t:
        fail("M22 service helper insertion anchor missing")
    t = t.replace(helper_marker, helper + helper_marker, 1)

    t = replace_once(
        t,
        '''        KrakenRealtimeMarketDataRegistry.stop()
        KrakenPrivateExecutionRegistry.stop()
        authorityLease.stop()
        dmsSafety.stop()
        controller.stop()
''',
        '''        KrakenRealtimeMarketDataRegistry.stop()
        KrakenPrivateExecutionRegistry.stop()
        KrakenApiKeySecurityRuntime.markUnknown("Trading host stopped.")
        authorityLease.stop()
        dmsSafety.stop()
        controller.stop()
''',
        "M22 stop resets key-security state"
    )

    t = replace_once(
        t,
        '''        KrakenRealtimeMarketDataRegistry.stop()
        KrakenPrivateExecutionRegistry.stop()
        authorityLease.stop()
        dmsSafety.stop()
        connectivity.stop()
''',
        '''        KrakenRealtimeMarketDataRegistry.stop()
        KrakenPrivateExecutionRegistry.stop()
        KrakenApiKeySecurityRuntime.markUnknown("Trading host destroyed.")
        authorityLease.stop()
        dmsSafety.stop()
        connectivity.stop()
''',
        "M22 destroy resets key-security state"
    )

    t = replace_once(
        t,
        '''        private const val FAILURE_RECONCILE_THRESHOLD = 3
''',
        '''        private const val FAILURE_RECONCILE_THRESHOLD = 3
        private const val KRAKEN_KEY_SECURITY_RECHECK_MS = 10L * 60L * 1000L
''',
        "M22 key-security recheck constant"
    )

    p.write_text(t.rstrip() + "\n", encoding="utf-8")
    print("PATCH |", p.relative_to(repo))

    changed = set(os.popen(
        f'cd "{repo}" && git diff --name-only -- app'
    ).read().splitlines())
    untracked = set(os.popen(
        f'cd "{repo}" && git ls-files --others --exclude-standard -- app'
    ).read().splitlines())
    actual = changed | untracked

    allowed = set(PAYLOAD_FILES) | {
        "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt",
        "app/src/main/java/com/ksp/cryptobot/settings/AppSettingsStore.kt",
        "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt",
    }

    if actual - allowed:
        fail("Unexpected M22 changes: " + ",".join(sorted(actual - allowed)))
    if allowed - actual:
        fail("Expected M22 changes missing: " + ",".join(sorted(allowed - actual)))

    print("PASS | M22 controlled runtime/release diff.")

if __name__ == "__main__":
    main()
