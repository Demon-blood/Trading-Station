package com.ksp.cryptobot.release

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

class ReleaseIntegrityChecker(private val context: Context) {
    data class Snapshot(
        val versionName: String,
        val versionCode: Long,
        val debuggable: Boolean,
        val signerFingerprint: String,
        val signerStable: Boolean,
        val detail: String
    )

    fun snapshot(): Snapshot {
        val pm = context.packageManager
        val packageName = context.packageName
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo
            when {
                signing == null -> emptyArray()
                signing.hasMultipleSigners() -> signing.apkContentsSigners
                else -> signing.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures ?: emptyArray()
        }
        val fingerprints = signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.distinct()
        val current = fingerprints.firstOrNull().orEmpty()
        val prefs = context.getSharedPreferences("cts_v4_release_integrity", Context.MODE_PRIVATE)
        val trusted = prefs.getString("trusted_signer_sha256", "").orEmpty()
        val stable = trusted.isBlank() || current.isBlank() || fingerprints.contains(trusted)
        if (trusted.isBlank() && current.isNotBlank()) prefs.edit().putString("trusted_signer_sha256", current).apply()
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return Snapshot(
            versionName = info.versionName.orEmpty(),
            versionCode = versionCode,
            debuggable = debuggable,
            signerFingerprint = current,
            signerStable = stable,
            detail = "version=${info.versionName}/$versionCode, debuggable=$debuggable, signer=${current.take(16).ifBlank { "unavailable" }}…, stable=$stable"
        )
    }
}
