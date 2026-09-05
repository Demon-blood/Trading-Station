#!/usr/bin/env python3
from pathlib import Path
import subprocess
import sys

def read(path):
    p = Path(path)
    return p.read_text(encoding="utf-8") if p.exists() else ""

def main():
    print("INFO | M22 verifier revision v1.2")
    repo = Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()

    policy = read(repo / "app/src/main/java/com/ksp/cryptobot/exchange/KrakenApiKeySecurity.kt")
    exchange = read(repo / "app/src/main/java/com/ksp/cryptobot/exchange/ExchangeClientsV08.kt")
    service = read(repo / "app/src/main/java/com/ksp/cryptobot/service/BotForegroundService.kt")
    secure_store = read(repo / "app/src/main/java/com/ksp/cryptobot/security/SecureSettingsStore.kt")
    settings = read(repo / "app/src/main/java/com/ksp/cryptobot/settings/AppSettingsStore.kt")
    manifest = read(repo / "app/src/main/AndroidManifest.xml")
    database = read(repo / "app/src/main/java/com/ksp/cryptobot/data/AppDatabase.kt")
    app_gradle = read(repo / "app/build.gradle.kts")
    gitignore = read(repo / ".gitignore")
    release_wf = read(repo / ".github/workflows/android-release-apk.yml")
    osv_wf = read(repo / ".github/workflows/osv-scanner.yml")
    verification_metadata = read(repo / "gradle/verification-metadata.xml")
    policy_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/exchange/KrakenApiKeySecurityPolicyM22Test.kt")
    runtime_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/exchange/KrakenApiKeySecurityRuntimeM22Test.kt")
    store_tests = read(repo / "app/src/test/java/com/ksp/cryptobot/security/SecureSettingsContractM22Test.kt")

    tracked = subprocess.run(
        ["git", "ls-files"],
        cwd=repo,
        text=True,
        capture_output=True,
        check=True
    ).stdout.splitlines()
    forbidden_tracked = [
        p for p in tracked
        if p == "signing.properties"
        or p.endswith("/signing.properties")
        or p.endswith("release-keystore.jks")
        or p.endswith("release.keystore")
        or p == ".env"
        or p.endswith("/.env")
        or p.endswith("secrets.properties")
    ]

    checks = {
        "M21 prerequisite exists":
            (repo / "tools/verify_m21_chaos_recovery.py").exists(),

        "no Room schema bump":
            "version = 12" in database,

        "Kraken dangerous permission set is exact":
            '"withdraw-funds"' in policy and
            '"add-withdraw-address"' in policy and
            '"update-withdraw-address"' in policy,

        "Kraken LIVE permission set matches runtime needs":
            '"query-funds"' in policy and
            '"query-open-trades"' in policy and
            '"query-closed-trades"' in policy and
            '"modify-trades"' in policy and
            '"close-trades"' in policy and
            '"create-ws-token"' in policy,

        "dangerous permissions hard-block":
            "dangerous.isEmpty() && missing.isEmpty() && !expired" in policy,

        "expired key hard-blocks":
            "nowEpochSeconds >= info.validUntilEpochSeconds" in policy,

        "IP allowlist absence is warning not hard blocker":
            "No IP allowlist configured; this is a warning, not a mobile-runtime blocker." in policy,

        "runtime defaults unknown and stale assessment blocks":
            "Kraken API-key permissions have not been inspected." in policy and
            "MAX_ASSESSMENT_AGE_MS = 15L * 60L * 1000L" in policy and
            "assessment is stale" in policy,

        "runtime fingerprints key without storing raw key":
            'MessageDigest.getInstance("SHA-256")' in policy and
            "return digest.take(16)" in policy,

        "credential mismatch blocks BUY":
            "Kraken API key changed since permission inspection" in policy,

        "Kraken uses GetApiKeyInfo for exact permissions":
            'privateJson("/0/private/GetApiKeyInfo", emptyMap())' in exchange and
            'result.optJSONArray("permissions")' in exchange and
            'result.optJSONArray("ipAllowlist")' in exchange and
            'result.optString("validUntil", "0")' in exchange,

        "Kraken BUY checks M22 security runtime":
            "KrakenApiKeySecurityRuntime.gateForNewBuy(apiKey)" in exchange and
            "M22 Kraken API-key security gate blocks BUY" in exchange,

        "Kraken SELL path is not wrapped in BUY security gate":
            "if (request.side == OrderSide.BUY)" in exchange,

        "service checks key permissions before LIVE authority acquisition":
            "refreshKrakenApiKeySecurity(startSettings" in service and
            "Acquiring distributed LIVE engine authority" in service and
            service.index("refreshKrakenApiKeySecurity(startSettings") <
                service.index("Acquiring distributed LIVE engine authority"),

        "service rechecks permissions every ten minutes":
            "KRAKEN_KEY_SECURITY_RECHECK_MS = 10L * 60L * 1000L" in service and
            'refreshKrakenApiKeySecurity(current, "service-cycle")' in service,

        "network loss invalidates key-security truth":
            "server-side API-key permissions cannot be considered current" in service,

        "runtime unsafe key keeps protective risk management alive":
            "New BUYs remain blocked; protective SELLs and risk reduction remain available." in service,

        "secure store uses Android Keystore AES-GCM":
            '"AndroidKeyStore"' in secure_store and
            '"AES/GCM/NoPadding"' in secure_store,

        "secure store binds ciphertext to logical secret using AAD":
            'cipher.updateAAD(aad(name))' in secure_store and
            '"CTS_SECURE_V2:$name"' in secure_store,

        "secure store migrates legacy ciphertext explicitly":
            "FORMAT_LEGACY" in secure_store and
            "saveSecret(name, legacy)" in secure_store,

        "secure secret writes are synchronous and checked":
            ".commit()" in secure_store and
            "Secure credential persistence failed" in secure_store,

        "secure secret clears are synchronous and checked":
            "Secure credential clear failed" in secure_store,

        "ordinary backup exports no plaintext secrets":
            "fun secureBackupMap(): Map<String, String> = emptyMap()" in settings,

        "Android backup remains disabled":
            'android:allowBackup="false"' in manifest,

        "Android cleartext traffic remains disabled":
            'android:usesCleartextTraffic="false"' in manifest,

        "release signing remains mandatory":
            "Release signing.properties is required" in app_gradle,

        "release signing files remain ignored":
            "signing.properties" in gitignore and
            "*.jks" in gitignore and
            "*.keystore" in gitignore,

        "no production signing material is tracked":
            not forbidden_tracked,

        "root protected workflows are bootstrapped on main":
            (repo / ".github/workflows/android-release-apk.yml").exists() and
            (repo / ".github/workflows/osv-scanner.yml").exists(),

        "release workflow requires pinned certificate secret":
            "ANDROID_EXPECTED_CERT_SHA256" in release_wf,

        "release workflow verifies APK cryptographic signature":
            "apksigner" in release_wf and
            "verify --verbose --print-certs" in release_wf,

        "release workflow refuses committed debug certificate":
            "Production APK is signed with the committed CTS debug update certificate" in release_wf,

        "release workflow produces APK SHA-256 evidence":
            "release-sha256.txt" in release_wf and
            'sha256sum "$APK"' in release_wf,

        "release workflow destroys temporary signing material":
            "Destroy temporary signing material" in release_wf and
            "rm -f app/release-keystore.jks signing.properties" in release_wf,

        "Gradle dependency verification metadata exists":
            "<verification-metadata" in verification_metadata and
            "<sha256 value=" in verification_metadata,

        "OSV runtime scan is pinned to immutable v2.5.0 action commit":
            "06b2ab4348248b456ee06c9e953637f55e03504f" in osv_wf,

        "OSV production gate scans releaseRuntimeClasspath only":
            "releaseRuntimeClasspath" in osv_wf and
            "m22-release-runtime-coordinates.txt" in osv_wf and
            "m22-runtime-osv-scanner.json" in osv_wf and
            "--lockfile=osv-scanner:build/m22-runtime-osv-scanner.json" in osv_wf,

        "full Gradle tooling inventory remains separately audited":
            "Audit full Gradle build-tool dependency inventory with OSV" in osv_wf and
            "continue-on-error: true" in osv_wf and
            "--lockfile=gradle/verification-metadata.xml" in osv_wf,

        "OSV is configured for PR main and scheduled scans":
            "pull_request:" in osv_wf and
            "schedule:" in osv_wf and
            "branches: [main]" in osv_wf,

        "tests cover all three dangerous permissions":
            "withdrawFundsHardBlocksLive" in policy_tests and
            "addWithdrawAddressHardBlocksLive" in policy_tests and
            "updateWithdrawAddressHardBlocksLive" in policy_tests,

        "tests cover missing permission expiry and benign extras":
            "missingModifyTradesBlocksLive" in policy_tests and
            "expiredKeyBlocksLive" in policy_tests and
            "benignExtraPermissionIsReportedButDoesNotGrantAuthority" in policy_tests,

        "tests cover unknown stale and credential mismatch runtime":
            "unknownRuntimeBlocksNewBuy" in runtime_tests and
            "staleAssessmentBlocksNewBuy" in runtime_tests and
            "changedApiKeyBlocksUntilReinspection" in runtime_tests,

        "tests document AAD and versioned migration contract":
            "m22AadNamespaceIsSecretSpecific" in store_tests and
            "legacyMigrationIsExplicitlyVersioned" in store_tests,
    }

    failed = []
    for name, ok in checks.items():
        print(("PASS" if ok else "FAIL") + " | " + name)
        if not ok:
            failed.append(name)

    if failed:
        if forbidden_tracked:
            print("DEBUG | forbidden tracked signing/secret files=" + ",".join(forbidden_tracked))
        raise SystemExit(
            "M22 security/API-key/release-integrity verification failed: " +
            ", ".join(failed)
        )

    print()
    print("PASS | M22 least-privilege API-key, secret-storage, backup, dependency and signed-release integrity contracts satisfied.")

if __name__ == "__main__":
    main()
