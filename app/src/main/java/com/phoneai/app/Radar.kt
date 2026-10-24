package com.phoneai.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

/** Network Radar: what connection you have and how fast it answers. No AI, no key. */
object Radar {

    private fun ping(url: String): Long {
        val t0 = System.currentTimeMillis()
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            c.requestMethod = "GET"
            c.responseCode
            c.disconnect()
            System.currentTimeMillis() - t0
        } catch (e: Exception) {
            -1L
        }
    }

    private fun grade(ms: Long): String = when {
        ms < 0 -> "unreachable"
        ms < 150 -> "excellent"
        ms < 400 -> "good"
        ms < 900 -> "slow"
        else -> "very slow"
    }

    fun report(c: Context): String {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return "You are offline. No network connection right now."
        val caps = cm.getNetworkCapabilities(net) ?: return "I can't read the network details."
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "a VPN"
            else -> "an unknown network"
        }
        val down = caps.linkDownstreamBandwidthKbps / 1000
        val up = caps.linkUpstreamBandwidthKbps / 1000
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val g = ping("https://www.google.com/generate_204")
        val b = ping("https://api.bybit.com/v5/market/time")
        val sb = StringBuilder()
        sb.append("You are on ").append(kind).append(if (online) " and the internet works. " else ", but the internet may not be working. ")
        if (down > 0) sb.append("The link is rated about ").append(down).append(" megabits down and ").append(up).append(" up. ")
        sb.append("Google answers in ").append(if (g < 0) "no time, it failed" else "$g milliseconds").append(" (").append(grade(g)).append("). ")
        sb.append("Bybit answers in ").append(if (b < 0) "no time, it failed" else "$b milliseconds").append(" (").append(grade(b)).append(").")
        if (b >= 900L) sb.append(" That is slow for trading. Be careful placing orders.")
        return sb.toString()
    }
}
