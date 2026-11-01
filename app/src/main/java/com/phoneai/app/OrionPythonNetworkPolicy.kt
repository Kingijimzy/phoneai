package com.phoneai.app

import android.content.Context
import java.net.InetAddress
import java.net.URI

/** Exact-host network grants. RFC1918 LAN endpoints are permitted only after an explicit host grant. */
internal object OrionPythonNetworkPolicy {
    private const val PREFS = "orion_python_network_hosts_v1"
    data class Endpoint(
        val uri: URI,
        val host: String,
        val local: Boolean,
        val allowWrites: Boolean,
        val addresses: List<InetAddress>
    )

    fun grantHost(context: Context, hostInput: String, allowPlainHttp: Boolean = false, allowWrites: Boolean = false): Boolean {
        val host = normalizeHost(hostInput) ?: return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(host, true)
            .putBoolean("$host:plain_http", allowPlainHttp)
            .putBoolean("$host:writes", allowWrites)
            .apply()
        return true
    }

    fun grantedHosts(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.all.keys.filter { key ->
            !key.endsWith(":plain_http") && !key.endsWith(":writes") && prefs.getBoolean(key, false)
        }.sorted()
    }

    fun revokeHost(context: Context, hostInput: String) {
        val host = normalizeHost(hostInput) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(host).remove("$host:plain_http").remove("$host:writes").apply()
    }

    fun validate(context: Context, url: String): Endpoint? = validateUrl(context, url, websocket = false)
    fun validateWebSocket(context: Context, url: String): Endpoint? = validateUrl(context, url, websocket = true)

    private fun validateUrl(context: Context, url: String, websocket: Boolean): Endpoint? {
        if (!OrionSensorConsent.read(context, OrionSensorScope.NETWORK).active()) return null
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (uri.userInfo != null || uri.fragment != null || (uri.rawQuery?.length ?: 0) > 4096) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val allowedSchemes = if (websocket) setOf("ws", "wss") else setOf("https", "http")
        if (scheme !in allowedSchemes || uri.host.isNullOrBlank() || uri.port !in -1..65535) return null
        val host = normalizeHost(uri.host) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(host, false)) return null

        // Resolve once and pin this exact address set in OkHttp. Mixed public/private DNS answers
        // are rejected to prevent a hostname from changing the network trust boundary.
        val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull()
            ?.distinctBy { it.hostAddress } ?: return null
        if (addresses.isEmpty()) return null
        val allPrivateLan = addresses.all(::isPrivateLanAddress)
        val anyPrivateOrSpecial = addresses.any(::isUnsafeAddress)
        if (allPrivateLan) {
            // Only RFC1918 IPv4 and explicitly named loopback are allowed as local endpoints.
            if (addresses.any { !isPrivateLanAddress(it) }) return null
        } else if (anyPrivateOrSpecial) {
            return null
        }
        val local = allPrivateLan
        val plainScheme = scheme == "http" || scheme == "ws"
        if (plainScheme && (!local || !prefs.getBoolean("$host:plain_http", false))) return null
        return Endpoint(uri, host, local, prefs.getBoolean("$host:writes", false), addresses)
    }

    private fun isPrivateLanAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true
        val bytes = address.address.map { it.toInt() and 0xff }
        if (bytes.size != 4) return false
        val a = bytes[0]
        val b = bytes[1]
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    private fun isUnsafeAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address.map { it.toInt() and 0xff }
        if (bytes.size == 4) {
            val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
            return a == 0 || a == 10 || a == 127 || (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 100 && b in 64..127) || (a == 192 && b == 0 && c == 0) ||
                (a == 192 && b == 0 && c == 2) || (a == 198 && b in 18..19) ||
                (a == 198 && b == 51 && c == 100) || (a == 203 && b == 0 && c == 113) ||
                a >= 224
        }
        return bytes.size == 16 && ((bytes[0] and 0xfe) == 0xfc)
    }

    private fun normalizeHost(value: String): String? {
        val host = value.trim().lowercase().trimEnd('.')
        if (host.length !in 1..253 || host.contains('/') || host.contains('@') ||
            !host.matches(Regex("^[a-z0-9.-]+$")) || host.startsWith('.') || host.endsWith('.')) return null
        if (host.split('.').any { label ->
                label.isEmpty() || label.length > 63 || label.startsWith('-') || label.endsWith('-')
            }) return null
        return host
    }
}