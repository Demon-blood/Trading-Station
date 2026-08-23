package com.ksp.cryptobot.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

data class RuntimeNetworkSnapshot(
    val available: Boolean = false,
    val internet: Boolean = false,
    val validated: Boolean = false,
    val metered: Boolean = false,
    val transports: String = "none",
    val changedEpochMs: Long = System.currentTimeMillis()
) {
    val usable: Boolean
        get() = available && internet && validated

    fun summary(): String =
        "available=$available,internet=$internet,validated=$validated,metered=$metered,transport=$transports"
}

class RuntimeConnectivityMonitor(
    context: Context,
    private val onChanged: (RuntimeNetworkSnapshot) -> Unit = {}
) {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    @Volatile
    var snapshot: RuntimeNetworkSnapshot = currentSnapshot()
        private set

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = publish(currentSnapshot())
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            publish(fromCapabilities(capabilities))
        override fun onLost(network: Network) = publish(currentSnapshot())
        override fun onUnavailable() = publish(RuntimeNetworkSnapshot())
    }

    fun start() {
        if (registered) return
        registered = true
        snapshot = currentSnapshot()
        onChanged(snapshot)
        manager.registerDefaultNetworkCallback(callback)
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { manager.unregisterNetworkCallback(callback) }
    }

    fun refresh(): RuntimeNetworkSnapshot {
        val fresh = currentSnapshot()
        publish(fresh)
        return fresh
    }

    private fun publish(value: RuntimeNetworkSnapshot) {
        snapshot = value
        onChanged(value)
    }

    private fun currentSnapshot(): RuntimeNetworkSnapshot {
        val network = manager.activeNetwork ?: return RuntimeNetworkSnapshot()
        val caps = manager.getNetworkCapabilities(network) ?: return RuntimeNetworkSnapshot(available = true)
        return fromCapabilities(caps)
    }

    private fun fromCapabilities(caps: NetworkCapabilities): RuntimeNetworkSnapshot {
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
        }.ifEmpty { listOf("other") }.joinToString("+")
        return RuntimeNetworkSnapshot(
            available = true,
            internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            transports = transports
        )
    }
}
