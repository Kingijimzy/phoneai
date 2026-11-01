package com.phoneai.app

import android.content.Context
import java.net.URI
import java.net.InetAddress

/** Host-scoped outbound HTTP policy. A host must be explicitly registered by the owner. */
internal object OrionPythonNetworkPolicy {
    private const val PREFS = "orion_python_network_hosts_v1"
    data class Endpoint(val uri: URI, val host: String, val local: Boolean, val allowWrites: Boolean)
    fun grantHost(context: Context, hostInput: String, allowPlainHttp: Boolean = false, allowWrites: Boolean = false): Boolean {
        val host = normalizeHost(hostInput) ?: return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(host, true).putBoolean("$host:plain_http", allowPlainHttp).putBoolean("$host:writes", allowWrites).apply()
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
    fun validate(context: Context, url: String): Endpoint? {
        if (!OrionSensorConsent.read(context, OrionSensorScope.NETWORK).active()) return null
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (uri.userInfo != null || uri.fragment != null || (uri.rawQuery?.length ?: 0) > 4096) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme !in setOf("https", "http") || uri.host.isNullOrBlank() || uri.port !in -1..65535) return null
        val host = normalizeHost(uri.host) ?: return null
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean(host, false)) return null
        val local = isLocalHost(host)
        if (scheme == "http" && (!local || !p.getBoolean("$host:plain_http", false))) return null
        if (!local) {
            val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull() ?: return null
            if (addresses.isEmpty() || addresses.any { isUnsafeAddress(it) }) return null
        }
        return Endpoint(uri, host, local, p.getBoolean("$host:writes", false))
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
        return bytes.size == 16 && (bytes[0] and 0xfe) == 0xfc
    }

    private fun normalizeHost(value: String): String? {
        val host = value.trim().lowercase().trimEnd('.')
        if (host.length !in 1..253 || host.contains('/') || host.contains('@') ||
            !host.matches(Regex("^[a-z0-9.-]+$")) || host.startsWith('.') || host.endsWith('.')) return null
        return host
    }
    private fun isLocalHost(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") || host.startsWith("127.") || host == "::1"
}
