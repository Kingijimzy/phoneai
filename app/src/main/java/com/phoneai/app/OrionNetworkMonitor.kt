package com.phoneai.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Offline detection for the unified model system.
 *
 * Orion checks network availability before attempting cloud provider calls. When the
 * device is offline, the local provider (Ollama at 127.0.0.1:11434) is preferred and
 * cloud providers are skipped with an honest "offline" diagnostic rather than burning
 * request budget on connection timeouts.
 *
 * This is a snapshot check, not a persistent listener: it answers "can we reach a
 * cloud endpoint right now?" so the model router can make an immediate, correct choice.
 */
internal object OrionNetworkMonitor {
    private const val PROBE_HOST = "1.1.1.1"
    private const val PROBE_PORT = 53
    private const val PROBE_TIMEOUT_MS = 3000L

    /** True when a network interface is up and appears capable of reaching the internet. */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val active = runCatching { cm.activeNetwork }.getOrNull() ?: return false
        val caps = runCatching { cm.getNetworkCapabilities(active) }.getOrNull() ?: return false
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val hasValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!hasInternet || !hasValidated) return false
        // A quick socket probe confirms reachable, not just "connected to a captive portal."
        return runCatching {
            java.net.Socket().let { s ->
                s.connect(java.net.InetSocketAddress(PROBE_HOST, PROBE_PORT), PROBE_TIMEOUT_MS.toInt())
                s.close()
                true
            }
        }.getOrDefault(false)
    }

    /** The provider to prefer when offline: local Ollama only. Cloud providers are never tried. */
    fun offlinePreferredProviderId(): String = LOCAL_PROVIDER.ID

    /** Whether the configured provider would work offline (local only). */
    fun isOfflineCapable(providerId: String): Boolean = providerId.lowercase() == LOCAL_PROVIDER.ID
}
