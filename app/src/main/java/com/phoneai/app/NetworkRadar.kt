package com.phoneai.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.os.Build

/** Privacy-conscious network diagnostics. Does not scan LAN devices or collect Wi-Fi passwords/SSID. */
data class NetworkRadarSnapshot(
    val connected: Boolean,
    val transport: String,
    val hasInternetCapability: Boolean,
    val validatedInternet: Boolean,
    val captivePortal: Boolean,
    val metered: Boolean,
    val vpnActive: Boolean,
    val dnsServers: List<String>,
    val mtu: Int?,
    val summary: String
)

object NetworkRadar {
    fun inspect(context: Context): NetworkRadarSnapshot {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return offline("Connectivity service unavailable.")
        val network = manager.activeNetwork
            ?: return offline("No active network.")
        val caps = manager.getNetworkCapabilities(network)
            ?: return offline("Network is present but its capabilities are unavailable.")
        val link = manager.getLinkProperties(network)
        val transports = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports += "Wi-Fi"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports += "Mobile data"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports += "Ethernet"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports += "Bluetooth"
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        if (vpn) transports += "VPN"
        if (transports.isEmpty()) transports += "Other"
        val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val captive = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val summary = when {
            !internet -> "Connected to a network, but Android reports no Internet capability."
            captive -> "A sign-in page may be required before Internet access works."
            validated -> "Android has validated Internet connectivity."
            else -> "A network is connected, but Internet access has not been validated."
        }
        return NetworkRadarSnapshot(
            connected = true,
            transport = transports.distinct().joinToString(" + "),
            hasInternetCapability = internet,
            validatedInternet = validated,
            captivePortal = captive,
            metered = metered,
            vpnActive = vpn,
            dnsServers = link?.dnsServers?.mapNotNull { it.hostAddress }?.distinct()?.take(4) ?: emptyList(),
            mtu = link?.mtu?.takeIf { it > 0 },
            summary = summary
        )
    }

    private fun offline(message: String) = NetworkRadarSnapshot(
        connected = false,
        transport = "Offline",
        hasInternetCapability = false,
        validatedInternet = false,
        captivePortal = false,
        metered = true,
        vpnActive = false,
        dnsServers = emptyList(),
        mtu = null,
        summary = message
    )
}
