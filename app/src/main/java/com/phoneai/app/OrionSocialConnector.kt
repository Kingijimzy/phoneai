package com.phoneai.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * One connector for social media in general. Each platform is a row in [catalog]: its API host, how it
 * authenticates, and the common reads/posts. Tokens live in the encrypted [ApiKeyVault]; the model never
 * sees them. `request` reaches any other endpoint on the same host, so new platform features need no new code.
 */
internal object OrionSocialConnector {
    data class Platform(
        val id: String, val title: String, val host: String, val base: String,
        val profile: String, val feed: String, val postPath: String?, val postField: String,
        val postAsJson: Boolean, val howTo: String, val aliases: List<String> = emptyList()
    )

    val catalog = listOf(
        Platform("facebook", "Facebook (Graph API)", "graph.facebook.com", "https://graph.facebook.com/v21.0",
            "/me?fields=id,name", "/me/feed?limit=10", "/me/feed", "message", false,
            "Create a Meta developer app, generate a User or Page access token at developers.facebook.com/tools/explorer with pages_manage_posts, pages_read_engagement, then connect it here. Posting to a Page uses that Page's id: request /PAGE_ID/feed.", listOf("fb", "meta")),
        Platform("instagram", "Instagram (Graph API)", "graph.facebook.com", "https://graph.facebook.com/v21.0",
            "/me?fields=id,username", "/me/media?fields=id,caption,permalink&limit=10", null, "caption", false,
            "Use an Instagram professional account linked to a Facebook Page and a Graph API token with instagram_basic and instagram_content_publish. Publishing is two steps: POST /IG_ID/media then POST /IG_ID/media_publish.", listOf("ig", "insta")),
        Platform("threads", "Threads", "graph.threads.net", "https://graph.threads.net/v1.0",
            "/me?fields=id,username", "/me/threads?limit=10", "/me/threads", "text", false,
            "Create a Threads API token in your Meta developer app (threads_basic, threads_content_publish). Publish is POST /me/threads then POST /me/threads_publish."),
        Platform("x", "X (Twitter) API v2", "api.x.com", "https://api.x.com/2",
            "/users/me", "/users/me", "/tweets", "text", true,
            "In developer.x.com create an app and generate a user-context OAuth 2.0 bearer token with tweet.read tweet.write users.read.", listOf("twitter", "tweet")),
        Platform("reddit", "Reddit", "oauth.reddit.com", "https://oauth.reddit.com",
            "/api/v1/me", "/best?limit=10", "/api/submit", "text", false,
            "Create a script or web app at reddit.com/prefs/apps, obtain an OAuth bearer token, and connect it here."),
        Platform("mastodon", "Mastodon", "", "",
            "/api/v1/accounts/verify_credentials", "/api/v1/timelines/home?limit=10", "/api/v1/statuses", "status", false,
            "Mastodon instance > Preferences > Development > New application (read write). Connect with instance=your.server and the access token."),
        Platform("linkedin", "LinkedIn", "api.linkedin.com", "https://api.linkedin.com/v2",
            "/userinfo", "/userinfo", "/ugcPosts", "text", true,
            "Create a LinkedIn app with Share on LinkedIn and Sign In with OpenID Connect, then connect its access token."),
        Platform("youtube", "YouTube", "www.googleapis.com", "https://www.googleapis.com/youtube/v3",
            "/channels?part=snippet,statistics&mine=true", "/activities?part=snippet&mine=true&maxResults=10", null, "", true,
            "Google Cloud: enable YouTube Data API v3 and create an OAuth access token with the youtube scope."),
        Platform("discord", "Discord (bot)", "discord.com", "https://discord.com/api/v10",
            "/users/@me", "/users/@me/guilds", null, "content", true,
            "Developer portal > your application > Bot > token. Post with request POST /channels/CHANNEL_ID/messages {\"content\":...}."),
        Platform("tiktok", "TikTok", "open.tiktokapis.com", "https://open.tiktokapis.com/v2",
            "/user/info/?fields=open_id,display_name", "/video/list/?fields=id,title", null, "", true,
            "TikTok for Developers app with user.info.basic and video.list scopes, then connect the access token."),
        Platform("bluesky", "Bluesky", "bsky.social", "https://bsky.social/xrpc",
            "/app.bsky.actor.getProfile?actor=me", "/app.bsky.feed.getTimeline?limit=10", null, "text", true,
            "Settings > App Passwords. Connect with handle (token field: handle:app-password); Orion creates a session for each call.")
    )

    fun find(name: String?): Platform? {
        val n = name.orEmpty().trim().lowercase()
        if (n.isEmpty()) return null
        return catalog.firstOrNull { it.id == n || n in it.aliases || n == it.title.lowercase() }
            ?: catalog.firstOrNull { it.id in n }
    }

    private fun secretName(id: String) = "orion_social_${id}_token_enc"
    private fun legacy(id: String) = "orion_social_${id}_token"
    private fun metaName(id: String) = "orion_social_${id}_instance"

    data class Spec(val method: String, val url: String, val body: String?, val json: Boolean)

    /** Pure request planner (unit-tested). */
    fun plan(p: Platform, action: String, args: JSONObject, instance: String): Spec? {
        val base = if (p.id == "mastodon") "https://" + instance.removePrefix("https://").trimEnd('/') else p.base
        return when (action) {
            "profile" -> Spec("GET", base + p.profile, null, false)
            "feed", "read" -> Spec("GET", base + p.feed, null, false)
            "pages" -> if (p.id == "facebook") Spec("GET", base + "/me/accounts?fields=id,name,access_token,tasks&limit=25", null, false) else null
            "post" -> {
                val text = args.optString("text").ifBlank { args.optString("message") }
                val path = p.postPath ?: return null
                if (text.isBlank()) return null
                val body = when (p.id) {
                    "x" -> JSONObject().put("text", text).toString()
                    "linkedin" -> JSONObject().put("author", args.optString("author"))
                        .put("lifecycleState", "PUBLISHED")
                        .put("specificContent", JSONObject().put("com.linkedin.ugc.ShareContent", JSONObject()
                            .put("shareCommentary", JSONObject().put("text", text))
                            .put("shareMediaCategory", "NONE")))
                        .put("visibility", JSONObject().put("com.linkedin.ugc.MemberNetworkVisibility", "PUBLIC")).toString()
                    "threads" -> "media_type=TEXT&text=" + enc(text)
                    "reddit" -> "api_type=json&kind=self&sr=" + enc(args.optString("subreddit")) + "&title=" + enc(args.optString("title").ifBlank { text.take(100) }) + "&text=" + enc(text)
                    else -> p.postField + "=" + enc(text)
                }
                Spec("POST", base + path, body, p.postAsJson && p.id in setOf("x", "linkedin"))
            }
            "request" -> {
                val path = args.optString("path").trim()
                if (!safePath(path)) return null
                val method = args.optString("method", "GET").uppercase()
                if (method !in setOf("GET", "POST", "PUT", "PATCH", "DELETE")) return null
                val body = args.opt("body")?.let { if (it is JSONObject) it.toString() else it.toString() }?.takeIf { it != "null" && it.isNotBlank() }
                Spec(method, base + path, body, body?.trimStart()?.startsWith("{") == true)
            }
            else -> null
        }
    }

    internal fun safePath(path: String): Boolean =
        path.startsWith("/") && !path.startsWith("//") && !path.contains("..") && !path.contains("@") && path.length <= 600 && path.none { it.code < 32 }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun connectedIds(c: Context): List<String> =
        catalog.filter { ApiKeyVault.read(c, secretName(it.id), legacy(it.id)).isNotBlank() }.map { it.id }

    fun connectedSummary(c: Context): String =
        runCatching { connectedIds(c) }.getOrDefault(emptyList()).let { if (it.isEmpty()) "no platform has a saved token" else "saved tokens: ${it.joinToString()}" }

    /** Turns a platform error into the real cause, so the model never has to guess. */
    fun explain(p: Platform, code: Int, body: String): String {
        val b = body.lowercase()
        return when {
            code == 401 || "expired" in b || "invalid oauth" in b || "error validating access token" in b ->
                "The platform rejected the token itself (expired, revoked or wrong type). Ask for a fresh token."
            p.id == "facebook" && code == 403 && ("#200" in b || "publish_actions" in b || "pages_manage_posts" in b || "permission" in b) ->
                "Facebook does not let any app post to a personal profile or to your own feed through the API. Posting works only to a Page you manage: run social {action:pages, platform:facebook} to list Pages, then social {action:post, platform:facebook, page:PAGE_ID or name, text}. The token needs pages_show_list, pages_manage_posts and pages_read_engagement."
            code == 403 -> "The token is valid but lacks the permission/scope this call needs; the platform text above names it."
            code == 429 -> "Rate limited by the platform; wait and retry."
            code in 500..599 -> "The platform had a server error; retry shortly."
            else -> ""
        }
    }

    fun status(c: Context): String = buildString {
        append("SOCIAL PLATFORMS (connect = social {action:connect, platform, token}):\n")
        catalog.forEach { p ->
            val on = ApiKeyVault.read(c, secretName(p.id), legacy(p.id)).isNotBlank()
            append("- ${p.id}: ${p.title} — ${if (on) "token saved (run verify to prove it works)" else "not connected"}\n")
        }
    }.trim()

    fun run(c: Context, args: JSONObject): String {
        val action = args.optString("action", "status").lowercase().trim()
        if (action == "status" || action == "list") return status(c)
        val p = find(args.optString("platform")) ?: return "error: unknown platform. " + status(c)
        when (action) {
            "howto", "setup" -> return "${p.title}: ${p.howTo}"
            "connect" -> {
                val token = args.optString("token").trim()
                if (token.isBlank()) return "${p.title}: ${p.howTo}\nThen call social {action:connect, platform:${p.id}, token:...}."
                var stored = token
                if (token.startsWith("1//")) {
                    val cid = args.optString("client_id").trim(); val cs = args.optString("client_secret").trim()
                    if (cid.isBlank() || cs.isBlank()) return "That is a Google REFRESH token (starts with 1//), not an access token, so it cannot be sent directly. To use it I also need the client_id and client_secret of the Google OAuth app that issued it: social {action:connect, platform:${p.id}, token:<refresh token>, client_id:..., client_secret:...}. Or paste a short-lived access token starting with ya29."
                    stored = "gr:$token|$cid|$cs"
                }
                if (p.id == "mastodon") {
                    val inst = args.optString("instance").trim()
                    if (inst.isBlank()) return "error: Mastodon needs instance (for example mastodon.social)."
                    c.getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit().putString(metaName(p.id), inst).apply()
                }
                val ok = ApiKeyVault.write(c, secretName(p.id), legacy(p.id), stored)
                return if (ok) "${p.title} connected. Use social {action:profile, platform:${p.id}} to verify." else "error: could not store the token."
            }
            "disconnect" -> {
                ApiKeyVault.write(c, secretName(p.id), legacy(p.id), ""); return "${p.title} disconnected."
            }
        }
        var token = ApiKeyVault.read(c, secretName(p.id), legacy(p.id))
        if (token.isBlank()) return "${p.title} is not connected. ${p.howTo}"
        val instance = c.getSharedPreferences("phoneai", Context.MODE_PRIVATE).getString(metaName(p.id), "").orEmpty()
        if (p.id == "mastodon" && instance.isBlank()) return "error: Mastodon instance missing; reconnect with instance."
        if (token.startsWith("gr:")) token = googleAccessToken(token) ?: return "error: Google refused the refresh token (revoked, or client_id/client_secret do not match the app that issued it). Reconnect with the right client credentials."
        if (p.id == "bluesky") token = blueskySession(token) ?: return "error: Bluesky login failed; reconnect with token handle:app-password."
        return try {
            when (action) {
                "verify" -> verify(p, token, instance)
                "post" -> if (p.id == "facebook") facebookPagePost(p, token, args) else send(p, action, args, instance, token)
                else -> send(p, action, args, instance, token)
            }
        } catch (e: Exception) { "error: ${p.title} request failed (${(e.message ?: e.javaClass.simpleName).take(120)})" }
    }

    private fun send(p: Platform, action: String, args: JSONObject, instance: String, token: String): String {
        val spec = plan(p, action, args, instance) ?: return "error: action '$action' is not available for ${p.title} (or arguments are missing). Actions: status, howto, connect, verify, profile, feed, post{text}, pages (facebook), request{path,method,body}."
        return call(p, spec, token)
    }

    private fun verify(p: Platform, token: String, instance: String): String {
        val r = send(p, "profile", JSONObject(), instance, token)
        return if (r.startsWith("HTTP")) "VERIFIED: ${p.title} token works.\n$r" else "NOT VERIFIED: $r"
    }

    /** Facebook only allows API posting to Pages: find the page, swap to its page token, publish. */
    private fun facebookPagePost(p: Platform, token: String, args: JSONObject): String {
        val text = args.optString("text").ifBlank { args.optString("message") }
        if (text.isBlank()) return "error: post needs text."
        val list = call(p, Spec("GET", p.base + "/me/accounts?fields=id,name,access_token&limit=50", null, false), token)
        if (!list.startsWith("HTTP")) return list
        val pages = JSONObject(list.substringAfter('\n')).optJSONArray("data") ?: return "error: could not read your Pages."
        if (pages.length() == 0) return "error: this token manages no Facebook Pages, and Facebook does not allow API posts to a personal profile. Create a Page (or use a token whose user manages one) with pages_show_list + pages_manage_posts."
        val want = args.optString("page").trim().lowercase()
        var chosen: JSONObject? = null
        for (i in 0 until pages.length()) {
            val o = pages.getJSONObject(i)
            if (want.isEmpty() && pages.length() == 1 || o.optString("id") == want || (want.isNotEmpty() && o.optString("name").lowercase().contains(want))) { chosen = o; break }
        }
        val page = chosen ?: return "error: say which Page: " + (0 until pages.length()).joinToString { pages.getJSONObject(it).let { o -> "${o.optString("name")} (${o.optString("id")})" } }
        val pageToken = page.optString("access_token").ifBlank { return "error: Facebook returned no page token; the user token needs pages_manage_posts." }
        val out = call(p, Spec("POST", p.base + "/" + page.optString("id") + "/feed", "message=" + enc(text), false), pageToken)
        return if (out.startsWith("HTTP")) "Posted to Page ${page.optString("name")}.\n$out" else out
    }

    internal fun googleToken(stored: String): String? = googleAccessToken(stored)

    private fun googleAccessToken(stored: String): String? = try {
        val (refresh, cid, cs) = stored.removePrefix("gr:").split("|").let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
        val conn = URL("https://oauth2.googleapis.com/token").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 15000; conn.readTimeout = 20000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write("grant_type=refresh_token&refresh_token=${enc(refresh)}&client_id=${enc(cid)}&client_secret=${enc(cs)}".toByteArray()) }
        if (conn.responseCode !in 200..299) null else JSONObject(conn.inputStream.bufferedReader().readText()).optString("access_token").ifBlank { null }
    } catch (_: Exception) { null }

    private fun blueskySession(handleAndPassword: String): String? = try {
        val (h, pw) = handleAndPassword.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val conn = URL("https://bsky.social/xrpc/com.atproto.server.createSession").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 15000; conn.readTimeout = 20000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(JSONObject().put("identifier", h).put("password", pw).toString().toByteArray()) }
        if (conn.responseCode !in 200..299) null else JSONObject(conn.inputStream.bufferedReader().readText()).optString("accessJwt").ifBlank { null }
    } catch (_: Exception) { null }

    private fun call(p: Platform, spec: Spec, token: String): String {
        val conn = URL(spec.url).openConnection() as HttpURLConnection
        conn.requestMethod = spec.method; conn.connectTimeout = 15000; conn.readTimeout = 30000
        val bearer = if (p.id == "discord") "Bot $token" else "Bearer $token"
        conn.setRequestProperty("Authorization", bearer)
        conn.setRequestProperty("User-Agent", "Orion/1.0")
        conn.setRequestProperty("Accept", "application/json")
        if (spec.body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", if (spec.json) "application/json" else "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(spec.body.toByteArray()) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        val clipped = text.take(12_000)
        return if (code in 200..299) "HTTP $code\n$clipped" else "error: HTTP $code from ${p.title}: ${clipped.take(600)}" + explain(p, code, text).let { if (it.isBlank()) "" else "\nCAUSE: $it" }
    }
}
