package com.phoneai.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * Google Drive, Docs, Sheets and Notion for real: one tool each (`google`, `notion`), real REST calls, the owner's own
 * credentials in the encrypted vault, and errors that say what is wrong (missing scope, bad id, no token).
 * `plan` is pure so every request shape is unit-tested; `run` only adds the token and the network.
 */
internal object OrionWorkspaceApps {
    data class Req(val method: String, val url: String, val body: String? = null, val note: String = "")
    sealed class Plan { data class Ok(val req: Req) : Plan(); data class Error(val message: String) : Plan() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun secret(id: String) = "orion_app_${id}_token_enc"
    private fun legacy(id: String) = "orion_app_${id}_token"

    private val driveFields = enc("files(id,name,mimeType,modifiedTime,webViewLink)")

    fun googlePlan(action: String, a: JSONObject): Plan {
        val id = a.optString("id").trim()
        val text = a.optString("text")
        return when (action) {
            "drive_search" -> {
                val q = a.optString("query").trim().replace("'", "\\'")
                val expr = if (q.isEmpty()) "trashed=false" else "name contains '$q' and trashed=false"
                Plan.Ok(Req("GET", "https://www.googleapis.com/drive/v3/files?pageSize=${a.optInt("limit", 10).coerceIn(1, 30)}&orderBy=modifiedTime%20desc&fields=$driveFields&q=${enc(expr)}"))
            }
            "drive_read" -> if (id.isEmpty()) Plan.Error("error: drive_read needs the file id (drive_search finds it).")
                else Plan.Ok(Req("GET", "https://www.googleapis.com/drive/v3/files/${enc(id)}?fields=id,name,mimeType", note = "export"))
            "docs_create" -> Plan.Ok(Req("POST", "https://docs.googleapis.com/v1/documents", JSONObject().put("title", a.optString("title").ifBlank { "Orion note" }).toString(), note = "docs_fill:$text"))
            "docs_append" -> if (id.isEmpty() || text.isEmpty()) Plan.Error("error: docs_append needs id and text.")
                else Plan.Ok(Req("POST", "https://docs.googleapis.com/v1/documents/${enc(id)}:batchUpdate", docInsert(text)))
            "docs_read" -> if (id.isEmpty()) Plan.Error("error: docs_read needs id.") else Plan.Ok(Req("GET", "https://www.googleapis.com/drive/v3/files/${enc(id)}/export?mimeType=text%2Fplain"))
            "sheets_create" -> Plan.Ok(Req("POST", "https://sheets.googleapis.com/v4/spreadsheets", JSONObject().put("properties", JSONObject().put("title", a.optString("title").ifBlank { "Orion sheet" })).toString(), note = "sheets_fill"))
            "sheets_read" -> if (id.isEmpty()) Plan.Error("error: sheets_read needs id (and optional range like Sheet1!A1:D20).")
                else Plan.Ok(Req("GET", "https://sheets.googleapis.com/v4/spreadsheets/${enc(id)}/values/${enc(a.optString("range").ifBlank { "A1:Z100" })}"))
            "sheets_append" -> {
                val rows = a.optJSONArray("rows")
                if (id.isEmpty() || rows == null || rows.length() == 0) Plan.Error("error: sheets_append needs id and rows [[..],[..]].")
                else Plan.Ok(Req("POST", "https://sheets.googleapis.com/v4/spreadsheets/${enc(id)}/values/${enc(a.optString("range").ifBlank { "A1" })}:append?valueInputOption=USER_ENTERED&insertDataOption=INSERT_ROWS",
                    JSONObject().put("values", rows).toString()))
            }
            else -> Plan.Error("error: unknown google action '$action'. Use drive_search, drive_read, docs_create, docs_append, docs_read, sheets_create, sheets_read, sheets_append, status, howto, connect, disconnect.")
        }
    }

    fun docInsert(text: String): String = JSONObject().put("requests", JSONArray().put(JSONObject().put("insertText",
        JSONObject().put("endOfSegmentLocation", JSONObject()).put("text", text)))).toString()

    fun notionPlan(action: String, a: JSONObject): Plan {
        val id = a.optString("id").trim()
        val text = a.optString("text")
        return when (action) {
            "search" -> Plan.Ok(Req("POST", "https://api.notion.com/v1/search", JSONObject().put("query", a.optString("query")).put("page_size", a.optInt("limit", 10).coerceIn(1, 25)).toString()))
            "read" -> if (id.isEmpty()) Plan.Error("error: notion read needs the page id (search finds it).") else Plan.Ok(Req("GET", "https://api.notion.com/v1/blocks/${enc(id)}/children?page_size=100"))
            "create" -> {
                val parent = a.optString("parent_id").trim()
                if (parent.isEmpty()) Plan.Error("error: notion create needs parent_id, a page the integration was shared with (search finds one).")
                else Plan.Ok(Req("POST", "https://api.notion.com/v1/pages", JSONObject()
                    .put("parent", JSONObject().put("page_id", parent))
                    .put("properties", JSONObject().put("title", JSONObject().put("title", JSONArray().put(JSONObject().put("text", JSONObject().put("content", a.optString("title").ifBlank { "Orion note" }))))))
                    .put("children", paragraphs(text)).toString()))
            }
            "append" -> if (id.isEmpty() || text.isEmpty()) Plan.Error("error: notion append needs id and text.")
                else Plan.Ok(Req("PATCH", "https://api.notion.com/v1/blocks/${enc(id)}/children", JSONObject().put("children", paragraphs(text)).toString()))
            else -> Plan.Error("error: unknown notion action '$action'. Use search, read, create, append, status, howto, connect, disconnect.")
        }
    }

    /** Notion limits a rich-text block to 2000 characters, so long text becomes several paragraphs. */
    fun paragraphs(text: String): JSONArray {
        val out = JSONArray()
        text.lines().filter { it.isNotBlank() }.flatMap { it.chunked(1900) }.take(90).forEach { line ->
            out.put(JSONObject().put("object", "block").put("type", "paragraph").put("paragraph",
                JSONObject().put("rich_text", JSONArray().put(JSONObject().put("type", "text").put("text", JSONObject().put("content", line))))))
        }
        return out
    }

    fun googleHowTo() = "Google (Drive, Docs, Sheets): create an OAuth client in Google Cloud Console (Desktop app), enable the Drive, Docs and Sheets APIs, authorise the scopes drive, documents and spreadsheets once (OAuth Playground works), then give me google {action:connect, refresh_token, client_id, client_secret}."
    fun notionHowTo() = "Notion: open notion.so/profile/integrations, create an internal integration, copy its secret, share the pages you want me to use with it (Connections menu on the page), then give me notion {action:connect, token}."

    fun connectedNodes(c: Context): Set<String> {
        val out = HashSet<String>()
        runCatching { if (ApiKeyVault.read(c, secret("google"), legacy("google")).isNotBlank()) out += setOf("drive", "docs", "sheets") }
        runCatching { if (ApiKeyVault.read(c, secret("notion"), legacy("notion")).isNotBlank()) out += "notion" }
        runCatching { if (OrionConnectionStore.get(c, "gmail") != null && OrionConnectionStore.tokenForConnector(c, "gmail").isNotBlank()) out += "gmail" }
        return out
    }

    fun google(c: Context, a: JSONObject): String = app(c, "google", a.optString("action").trim().lowercase(), a)
    fun notion(c: Context, a: JSONObject): String = app(c, "notion", a.optString("action").trim().lowercase(), a)

    private fun app(c: Context, which: String, action: String, a: JSONObject): String {
        when (action) {
            "", "status" -> return if (ApiKeyVault.read(c, secret(which), legacy(which)).isBlank()) "$which is not connected. " + (if (which == "google") googleHowTo() else notionHowTo()) else "$which has a saved credential. Try ${if (which == "google") "drive_search" else "search"}; the error text will say if scopes or sharing are missing."
            "howto" -> return if (which == "google") googleHowTo() else notionHowTo()
            "disconnect" -> { ApiKeyVault.write(c, secret(which), legacy(which), ""); return "$which disconnected." }
            "connect" -> {
                val stored = if (which == "google") {
                    val r = a.optString("refresh_token").trim(); val id = a.optString("client_id").trim(); val s = a.optString("client_secret").trim()
                    if (r.isEmpty() || id.isEmpty() || s.isEmpty()) return "error: google connect needs refresh_token, client_id and client_secret."
                    "gr:$r|$id|$s"
                } else a.optString("token").trim().ifEmpty { return "error: notion connect needs the integration token." }
                return if (ApiKeyVault.write(c, secret(which), legacy(which), stored)) "$which connected." else "error: could not save the credential."
            }
        }
        val plan = if (which == "google") googlePlan(action, a) else notionPlan(action, a)
        val req = (plan as? Plan.Error)?.let { return it.message } ?: (plan as Plan.Ok).req
        val saved = ApiKeyVault.read(c, secret(which), legacy(which))
        if (saved.isBlank()) return "error: $which is not connected. " + (if (which == "google") googleHowTo() else notionHowTo())
        val token = if (which == "google") OrionSocialConnector.googleToken(saved) ?: return "error: Google refused the refresh token (revoked, or client_id/client_secret do not match the app that issued it)." else saved
        return runCatching { execute(which, action, req, token, a) }.getOrElse { "error: ${it.message ?: it.javaClass.simpleName}" }
    }

    private fun execute(which: String, action: String, req: Req, token: String, a: JSONObject): String {
        if (req.note == "export") {
            val meta = JSONObject(http(req, token, which))
            val mime = meta.optString("mimeType")
            val exportMime = when {
                mime.endsWith("document") -> "text/plain"; mime.endsWith("spreadsheet") -> "text/csv"; mime.endsWith("presentation") -> "text/plain"; else -> ""
            }
            val u = if (exportMime.isNotEmpty()) "https://www.googleapis.com/drive/v3/files/${enc(a.optString("id"))}/export?mimeType=${enc(exportMime)}" else "https://www.googleapis.com/drive/v3/files/${enc(a.optString("id"))}?alt=media"
            return meta.optString("name") + "\n" + http(Req("GET", u), token, which).take(12_000)
        }
        val body = http(req, token, which)
        if (req.note.startsWith("docs_fill:")) {
            val doc = JSONObject(body); val docId = doc.optString("documentId"); val text = req.note.removePrefix("docs_fill:")
            if (text.isNotEmpty()) http(Req("POST", "https://docs.googleapis.com/v1/documents/${enc(docId)}:batchUpdate", docInsert(text)), token, which)
            return "Created Google Doc \"${doc.optString("title")}\"\nhttps://docs.google.com/document/d/$docId/edit"
        }
        if (req.note == "sheets_fill") {
            val sh = JSONObject(body); val sid = sh.optString("spreadsheetId")
            a.optJSONArray("rows")?.takeIf { it.length() > 0 }?.let {
                http(Req("POST", "https://sheets.googleapis.com/v4/spreadsheets/${enc(sid)}/values/A1:append?valueInputOption=USER_ENTERED", JSONObject().put("values", it).toString()), token, which)
            }
            return "Created Google Sheet \"${sh.optJSONObject("properties")?.optString("title")}\"\nhttps://docs.google.com/spreadsheets/d/$sid/edit"
        }
        return summarize(action, body)
    }

    fun summarize(action: String, body: String): String = runCatching {
        val o = JSONObject(body)
        when {
            o.has("files") -> o.getJSONArray("files").let { f -> (0 until f.length()).joinToString("\n") { i -> f.getJSONObject(i).let { "- ${it.optString("name")} (${it.optString("mimeType").substringAfterLast('.')}) id=${it.optString("id")} ${it.optString("webViewLink")}" } }.ifBlank { "No files matched." } }
            o.has("values") -> o.getJSONArray("values").let { v -> (0 until v.length()).joinToString("\n") { i -> v.getJSONArray(i).join(" | ").replace("\"", "") } }.ifBlank { "(empty range)" }
            o.has("results") -> o.getJSONArray("results").let { r -> (0 until r.length()).joinToString("\n") { i -> r.getJSONObject(i).let { x -> "- ${notionTitle(x)} (${x.optString("object")}) id=${x.optString("id")}" } }.ifBlank { "Nothing found; share pages with the integration first." } }
            o.optString("object") == "list" && o.has("results").not() -> body.take(3000)
            action.startsWith("append") || action == "create" -> "Done: " + (o.optString("url").ifBlank { o.optString("id") })
            else -> body.take(3000)
        }
    }.getOrDefault(body.take(3000))

    private fun notionTitle(o: JSONObject): String {
        val props = o.optJSONObject("properties")
        props?.keys()?.forEach { k -> val p = props.optJSONObject(k); if (p?.optString("type") == "title") return p.optJSONArray("title")?.optJSONObject(0)?.optString("plain_text").orEmpty().ifBlank { "(untitled)" } }
        return o.optJSONArray("title")?.optJSONObject(0)?.optString("plain_text").orEmpty().ifBlank { "(untitled)" }
    }

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()

    private fun http(r: Req, token: String, which: String): String {
        val body = r.body?.toRequestBody("application/json".toMediaType())
            ?: if (r.method == "GET") null else "".toRequestBody(null)
        val req = okhttp3.Request.Builder().url(r.url).method(r.method, body)
            .header("Authorization", "Bearer $token")
            .apply { if (which == "notion") header("Notion-Version", "2022-06-28") }.build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException(explain(which, resp.code, text))
            return text
        }
    }

    fun explain(which: String, code: Int, body: String): String {
        val b = body.lowercase()
        val cause = when {
            code == 401 -> "the credential was rejected (expired or revoked); reconnect it"
            code == 403 && ("insufficient" in b || "scope" in b) -> "the token lacks a scope for this API; re-authorise with the drive, documents and spreadsheets scopes"
            code == 403 && ("has not been used" in b || "api has not been enabled" in b) -> "that Google API is not enabled in the Cloud project; enable it in the console"
            code == 403 -> "access denied; for Notion the page must be shared with the integration"
            code == 404 -> "not found; check the id" + if (which == "notion") " and that the page is shared with the integration" else ""
            code == 429 -> "rate limited; wait and retry"
            else -> "request failed"
        }
        return "HTTP $code from $which: $cause. ${body.take(200)}"
    }
}
