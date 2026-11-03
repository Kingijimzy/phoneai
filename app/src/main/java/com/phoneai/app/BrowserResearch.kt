package com.phoneai.app

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded, read-only webpage fetcher for agent research.
 *
 * This class deliberately does not click, submit forms, execute scripts, or interpret page
 * text as instructions. Call it from a worker thread. Integrators should display the returned
 * source URL alongside summaries and treat page content as untrusted data.
 */
data class BrowserResearchPage(
    val sourceUrl: String,
    val title: String,
    val text: String,
    val contentType: String
)

data class BrowserResearchResult(
    val page: BrowserResearchPage? = null,
    val error: String? = null
) {
    val isSuccess: Boolean get() = page != null
}

object BrowserResearch {
    private const val CONNECT_TIMEOUT_MS = 7_000
    private const val READ_TIMEOUT_MS = 9_000
    private const val MAX_BYTES = 180_000
    private const val MAX_TEXT_CHARS = 8_000
    private const val MAX_REDIRECTS = 3

    /**
     * Fetch one public HTML/text page. Only public HTTPS webpages on the standard HTTPS port are accepted.
     * Cancellation is cooperative and checked while reading response bytes.
     */
    fun fetch(rawUrl: String, cancelled: AtomicBoolean = AtomicBoolean(false)): BrowserResearchResult {
        var current = rawUrl.trim()
        if (current.isEmpty()) return fail("A URL is required.")
        for (redirect in 0..MAX_REDIRECTS) {
            if (cancelled.get()) return fail("Cancelled.")
            val uri = try { URI(current).normalize() } catch (_: Exception) { return fail("Invalid URL.") }
            val validated = validatePublicUrl(uri) ?: return fail("Only public HTTPS webpages on the standard HTTPS port can be fetched.")
            val (safeUrl, pinnedAddresses) = validated
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        if (!hostname.equals(uri.host, ignoreCase = true)) {
                            throw UnknownHostException("Research redirect or secondary host is blocked.")
                        }
                        return pinnedAddresses
                    }
                })
                .connectTimeout(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                .callTimeout((CONNECT_TIMEOUT_MS + READ_TIMEOUT_MS + 2_000L), TimeUnit.MILLISECONDS)
                .build()
            try {
                val request = Request.Builder().url(safeUrl)
                    .header("User-Agent", "PhoneAI-Research/1.0")
                    .header("Accept", "text/html,text/plain,application/xhtml+xml")
                    .header("Accept-Encoding", "identity")
                    .get().build()
                val call = client.newCall(request)
                if (cancelled.get()) call.cancel()
                var redirectUrl: String? = null
                call.execute().use { response ->
                    if (cancelled.get()) return fail("Cancelled.")
                    if (response.code in listOf(301, 302, 303, 307, 308)) {
                        if (redirect == MAX_REDIRECTS) return fail("Too many redirects.")
                        val location = response.header("Location") ?: return fail("Redirect had no destination.")
                        redirectUrl = uri.resolve(location).toString()
                        return@use
                    }
                    if (!response.isSuccessful) return fail("Website returned HTTP ${response.code}.")
                    val type = (response.header("Content-Type") ?: "").lowercase(Locale.ROOT)
                    if (!(type.contains("text/html") || type.contains("application/xhtml+xml") || type.startsWith("text/plain"))) {
                        return fail("This page is not HTML or plain text.")
                    }
                    val input = response.body?.byteStream() ?: return fail("Website returned an empty response.")
                    val bytes = input.use { stream ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        var total = 0
                        while (true) {
                            if (cancelled.get()) {
                                call.cancel()
                                return fail("Cancelled.")
                            }
                            val n = stream.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) return fail("Page is larger than the research limit.")
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                    val raw = String(bytes, Charsets.UTF_8)
                    val title = extractTitle(raw)
                    val text = toReadableText(raw).take(MAX_TEXT_CHARS)
                    if (text.isBlank()) return fail("No readable text was found on this page.")
                    return BrowserResearchResult(page = BrowserResearchPage(safeUrl, title, text, type))
                }
                if (redirectUrl != null) {
                    current = redirectUrl!!
                    continue
                }
            } catch (_: java.net.SocketTimeoutException) {
                return fail("Website request timed out.")
            } catch (_: Exception) {
                return fail(if (cancelled.get()) "Cancelled." else "Could not read this webpage.")
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
        return fail("Could not follow webpage redirect.")
    }

    private fun validatePublicUrl(uri: URI): Pair<String, List<InetAddress>>? {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https") return null
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.trimEnd('.')?.lowercase(Locale.ROOT) ?: return null
        if (host.isBlank() || host == "localhost" || host.endsWith(".localhost") ||
            host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".test")) return null
        val port = uri.port
        if (port != -1 && port != 443) return null
        return try {
            // Resolve before connecting; reject private, loopback, link-local, and reserved addresses.
            val addresses = InetAddress.getAllByName(host)
            if (addresses.isEmpty() || addresses.any { isNonPublic(it) }) null
            else URI(scheme, null, host, port, (uri.path ?: "").ifBlank { "/" }, uri.query, null).toASCIIString() to addresses.toList()
        } catch (_: Exception) {
            null
        }
    }

    private fun isNonPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address
        // Block IPv4 carrier-grade NAT, benchmarking, documentation, and reserved ranges too.
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 255
            val b = bytes[1].toInt() and 255
            val c = bytes[2].toInt() and 255
            return a == 0 || a == 10 || a == 127 || a >= 224 ||
                (a == 100 && b in 64..127) ||
                (a == 169 && b == 254) ||
                (a == 172 && b in 16..31) ||
                (a == 192 && (b == 168 || b == 0 && c == 0)) ||
                (a == 198 && b in 18..19) ||
                (a == 192 && b == 0 && c == 2) ||
                (a == 198 && b == 51 && c == 100) ||
                (a == 203 && b == 0 && c == 113)
        }
        // InetAddress helpers cover common IPv6 local ranges. Also block IPv4-mapped IPv6.
        return address is java.net.Inet6Address &&
            (bytes[0].toInt() and 0xfe) == 0xfc ||
            (bytes.size == 16 && bytes.slice(0..9).all { it == 0.toByte() } &&
                bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte())
    }

    private fun extractTitle(html: String): String {
        val match = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1) ?: ""
        return decodeEntities(stripTags(match)).replace(Regex("\\s+"), " ").trim().take(180)
    }

    private fun toReadableText(html: String): String {
        var value = html
        value = value.replace(Regex("(?is)<!--.*?-->"), " ")
        value = value.replace(Regex("(?is)<(script|style|noscript|svg|iframe|form|nav|footer|header)[^>]*>.*?</\\1>"), " ")
        value = value.replace(Regex("(?is)<(br|/p|/div|/li|/h[1-6]|/article|/section|/tr)[^>]*>"), "\\n")
        return decodeEntities(stripTags(value))
            .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
            .replace(Regex(" *\\n *"), "\\n")
            .replace(Regex("\\n{3,}"), "\\n\\n")
            .trim()
    }

    private fun stripTags(value: String): String = value.replace(Regex("(?s)<[^>]*>"), " ")

    private fun decodeEntities(value: String): String = value
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)

    private fun fail(message: String) = BrowserResearchResult(error = message)
}
