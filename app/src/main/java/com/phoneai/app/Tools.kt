package com.phoneai.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ContentUris
import android.provider.ContactsContract
import android.provider.MediaStore
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** The extra abilities the agent can use besides touching the screen. All run on the worker thread. */
class Tools(private val svc: PhoneAiService) {

    private fun launch(i: Intent): String {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            svc.startActivity(i)
            Thread.sleep(1200)
            "ok"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    // ---- screen helpers

    fun clickText(text: String): String {
        val root = svc.rootInActiveWindow ?: return "error: cannot read this screen"
        val found = root.findAccessibilityNodeInfosByText(text)
        val n = found.firstOrNull { it.isVisibleToUser }
            ?: return "error: no visible element with text \"$text\". Use read_screen."
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 6) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Thread.sleep(600)
                return "clicked \"$text\""
            }
            cur = cur.parent
            hops++
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        return svc.tap(r.centerX().toFloat(), r.centerY().toFloat())
    }

    fun waitFor(text: String, seconds: Double): String {
        val end = System.currentTimeMillis() + (seconds.coerceIn(1.0, 30.0) * 1000).toLong()
        while (System.currentTimeMillis() < end) {
            val root = svc.rootInActiveWindow
            if (root != null && root.findAccessibilityNodeInfosByText(text).any { it.isVisibleToUser }) {
                return "found \"$text\""
            }
            Thread.sleep(500)
        }
        return "not found: \"$text\" did not appear"
    }

    fun listApps(): String {
        val pm = svc.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val names = pm.queryIntentActivities(intent, 0).map { it.loadLabel(pm).toString() }.distinct().sorted()
        return names.joinToString(", ").take(3000)
    }

    // ---- web

    fun webSearch(query: String): String {
        if (query.isBlank()) return "error: empty query"
        return svc.openUrl("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))
    }

    fun fetchUrl(url: String): String {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return "error: url must start with http or https"
        return try {
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.connectTimeout = 12000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) OrionAI")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.use { s ->
                val buf = ByteArray(200_000)
                var total = 0
                while (total < buf.size) {
                    val n = s.read(buf, total, buf.size - total)
                    if (n < 0) break
                    total += n
                }
                String(buf, 0, total, Charsets.UTF_8)
            } ?: ""
            conn.disconnect()
            var t = raw.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
            t = t.replace(Regex("(?s)<[^>]+>"), " ")
            t = t.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            t = t.replace(Regex("\\s+"), " ").trim()
            if (code !in 200..299) "error: HTTP $code ${t.take(200)}" else t.take(3500).ifEmpty { "(empty page)" }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    /**
     * Read-only public GitHub lookup. Calls a fixed api.github.com endpoint; never accepts
     * arbitrary URLs, never sends a token, and never performs write operations.
     * section: repo | release | issues | runs
     */
    fun githubPublic(owner: String, repository: String, section: String): String {
        val safePart = Regex("^[A-Za-z0-9_.-]{1,100}$")
        if (!safePart.matches(owner) || !safePart.matches(repository) ||
            owner == "." || owner == ".." || repository == "." || repository == "..") {
            return "error: provide a public GitHub owner and repository name"
        }
        val endpoint = when (section.lowercase().trim()) {
            "repo" -> "repos/$owner/$repository"
            "release" -> "repos/$owner/$repository/releases/latest"
            "issues" -> "repos/$owner/$repository/issues?state=open&per_page=10"
            "runs" -> "repos/$owner/$repository/actions/runs?per_page=5"
            else -> return "error: section must be repo, release, issues, or runs"
        }
        return try {
            val conn = (URL("https://api.github.com/$endpoint").openConnection() as HttpURLConnection)
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "PhoneAI-ReadOnly-Integration")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (total < 80_000) {
                    val n = input.read(buffer, 0, minOf(buffer.size, 80_000 - total))
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    total += n
                }
                out.toString(Charsets.UTF_8.name())
            } ?: ""
            conn.disconnect()
            if (code !in 200..299) {
                val detail = try { JSONObject(raw).optString("message", "HTTP $code") } catch (_: Exception) { "HTTP $code" }
                return "GitHub returned $code: ${detail.take(180)}. The repository may be private, missing, or rate-limited."
            }
            when (section.lowercase().trim()) {
                "repo" -> {
                    val obj = JSONObject(raw)
                    "Public GitHub repository: ${obj.optString("full_name", "$owner/$repository")}\n" +
                        "Description: ${obj.optString("description", "No description provided")}\n" +
                        "Default branch: ${obj.optString("default_branch", "unknown")}\n" +
                        "Stars: ${obj.optInt("stargazers_count")} | Forks: ${obj.optInt("forks_count")} | Open issues: ${obj.optInt("open_issues_count")}\n" +
                        "Updated: ${obj.optString("updated_at", "unknown")}\nURL: ${obj.optString("html_url", "https://github.com/$owner/$repository")}"
                }
                "runs" -> {
                    val arr = JSONObject(raw).optJSONArray("workflow_runs") ?: JSONArray()
                    if (arr.length() == 0) return "No public GitHub Actions runs were returned. Actions may be disabled or require authentication."
                    val lines = (0 until minOf(arr.length(), 5)).mapNotNull { index ->
                        val run = arr.optJSONObject(index) ?: return@mapNotNull null
                        run.optString("name", "Workflow") + " — " + run.optString("status", "unknown") + "/" +
                            run.optString("conclusion", "pending") + " — " + run.optString("head_branch", "unknown branch") +
                            "\n" + run.optString("html_url", "")
                    }
                    "Latest public GitHub Actions runs for $owner/$repository (read-only):\n" + lines.joinToString("\n\n")
                }
                "release" -> {
                    val obj = JSONObject(raw)
                    "Latest published release for $owner/$repository: ${obj.optString("name", obj.optString("tag_name", "unnamed"))}\n" +
                        "Tag: ${obj.optString("tag_name", "unknown")}\nPublished: ${obj.optString("published_at", "unknown")}\n" +
                        "URL: ${obj.optString("html_url", "https://github.com/$owner/$repository/releases")}"
                }
                else -> {
                    val arr = JSONArray(raw)
                    val issues = (0 until arr.length()).mapNotNull { i ->
                        val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                        if (obj.has("pull_request")) return@mapNotNull null
                        "#${obj.optInt("number")} ${obj.optString("title", "(untitled)")} — ${obj.optString("html_url", "")}"
                    }
                    if (issues.isEmpty()) "No open issues found for $owner/$repository (or the API returned none)." else
                        "Open issues for $owner/$repository (up to 10):\n" + issues.joinToString("\n").take(2500)
                }
            }
        } catch (e: Exception) {
            "error: GitHub lookup failed: ${e.message ?: "network error"}"
        }
    }

    /** Public Bybit spot ticker; read-only, no API key, never places orders. */
    fun bybitPublicTicker(symbol: String): String {
        val clean = symbol.trim().uppercase().replace("/", "")
        if (!Regex("^[A-Z0-9]{5,20}$").matches(clean)) return "error: use a spot symbol such as BTCUSDT or ETHUSDT."
        return try {
            val url = URL("https://api.bybit.com/v5/market/tickers?category=spot&symbol=" + URLEncoder.encode(clean, "UTF-8"))
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "PhoneAI-Public-Market-Reader")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(2048)
                var total = 0
                while (total < 20000) {
                    val n = input.read(buf, 0, minOf(buf.size, 20000 - total))
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                }
                out.toString(Charsets.UTF_8.name())
            } ?: ""
            conn.disconnect()
            if (code !in 200..299) return "Bybit public API returned HTTP " + code + "."
            val root = JSONObject(raw)
            if (root.optInt("retCode", -1) != 0) return "Bybit market lookup failed: " + root.optString("retMsg", "unknown API error")
            val list = root.optJSONObject("result")?.optJSONArray("list")
                ?: return "No spot ticker returned for " + clean + ". Check the symbol and market."
            val item = list.optJSONObject(0) ?: return "No spot ticker returned for " + clean + "."
            val last = item.optString("lastPrice", "unknown")
            val changeRaw = item.optString("price24hPcnt", "")
            val change = changeRaw.toDoubleOrNull()?.let { String.format(Locale.US, "%.2f%%", it * 100.0) } ?: "unknown"
            val high = item.optString("highPrice24h", "unknown")
            val low = item.optString("lowPrice24h", "unknown")
            val volume = item.optString("volume24h", "unknown")
            "Bybit spot ticker for " + clean + " (public market data, not a trading signal):\\nLast price: " + last +
                "\\n24h change: " + change + "" +
                "\\n24h high: " + high + "\\n24h low: " + low + "\\n24h base volume: " + volume
        } catch (e: Exception) { "Bybit market lookup failed (" + e.javaClass.simpleName + ")." }
    }
    fun telegramStatus(): String = TelegramBridge.status(svc)

    fun telegramSend(message: String): String = TelegramBridge.send(svc, message)

    fun integrationStatus(): String = IntegrationStatus.summary(svc)

    fun mcpStatus(): String = McpConnector.status(svc)

    fun mcpListTools(): String = McpConnector.listTools(svc)

    fun mcpCall(name: String, args: JSONObject): String = McpConnector.callTool(svc, name, args)

    // ---- deterministic local routes (Stage 5)

    fun lookupApp(name: String): String {
        val query = name.trim()
        if (query.isBlank()) return "error: app name or package is required"
        val pm = svc.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = pm.queryIntentActivities(intent, 0).map { ri ->
            Triple(ri.loadLabel(pm).toString(), ri.activityInfo.packageName, ri)
        }
        val exact = matches.firstOrNull { it.first.equals(query, true) || it.second.equals(query, true) }
        val match = exact ?: matches.firstOrNull { it.first.contains(query, true) || it.second.contains(query, true) }
        return if (match != null) {
            "APP MATCH: label=\"" + match.first + "\"; package=\"" + match.second + "\"; exact=" +
                (match.first.equals(query, true) || match.second.equals(query, true))
        } else {
            "error: no installed launchable app matched \"" + query + "\"."
        }
    }

    /** Only a fixed allowlist is accepted; arbitrary component/action names are never launched. */
    fun systemIntent(actionName: String, query: String = "", uriText: String = ""): String {
        val action = actionName.trim().lowercase().replace('-', '_').replace(' ', '_')
        val i: Intent = when (action) {
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "wifi_settings" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth_settings" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display_settings" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "app_settings" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
            "location_settings" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "accessibility_settings" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "dial" -> {
                val number = query.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                if (number.isBlank()) return "error: dial route needs a phone number"
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)))
            }
            "maps_search" -> {
                if (query.isBlank()) return "error: maps_search needs a place or address"
                Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(query)))
            }
            "camera" -> Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            "calendar" -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
            "clock" -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
            "open_url" -> {
                val raw = uriText.trim()
                val u = try { java.net.URI(raw) } catch (_: Exception) { null }
                if (u == null || u.scheme?.lowercase() !in setOf("http", "https") || u.host.isNullOrBlank())
                    return "error: open_url requires a complete HTTP/HTTPS URL"
                Intent(Intent.ACTION_VIEW, Uri.parse(raw))
            }
            else -> return "error: unsupported system intent. Allowed: settings, wifi_settings, bluetooth_settings, display_settings, app_settings, location_settings, accessibility_settings, dial, maps_search, camera, calendar, clock, open_url"
        }
        return try {
            if (i.resolveActivity(svc.packageManager) == null) return "error: no installed app can handle route '" + action + "'"
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            svc.startActivity(i)
            "launched allowlisted system route '" + action + "'; destination state still needs verification"
        } catch (e: Exception) {
            "error: could not launch '" + action + "' (" + (e.message ?: e.javaClass.simpleName) + ")"
        }
    }

    fun resolveContact(name: String): String {
        val q = name.trim()
        if (q.isBlank()) return "error: contact name is required"
        if (androidx.core.content.ContextCompat.checkSelfPermission(svc, android.Manifest.permission.READ_CONTACTS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "error: contacts permission is not granted. Enable Contacts permission for Orion in Android Settings, then retry."
        }
        val projection = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.HAS_PHONE_NUMBER)
        return try {
            val matches = mutableListOf<String>()
            svc.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, projection,
                ContactsContract.Contacts.DISPLAY_NAME + " LIKE ?", arrayOf("%" + q + "%"),
                ContactsContract.Contacts.DISPLAY_NAME + " COLLATE NOCASE ASC"
            )?.use { c ->
                while (c.moveToNext() && matches.size < 8) {
                    val id = c.getString(0)
                    val label = c.getString(1) ?: continue
                    if (c.getInt(2) == 0) {
                        matches.add(label + " (no saved phone number)")
                    } else {
                        val phone = svc.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                            arrayOf(id), null
                        )?.use { p -> if (p.moveToFirst()) p.getString(0) else null }
                        matches.add(if (phone.isNullOrBlank()) label + " (phone unavailable)" else label + " — " + phone)
                    }
                }
            }
            if (matches.isEmpty()) "no contact matched '" + q + "'" else "CONTACT MATCHES (confirm the intended person before calling or messaging):\n" + matches.joinToString("\n")
        } catch (e: Exception) {
            "error: contact lookup failed (" + e.javaClass.simpleName + ")"
        }
    }

    fun findFiles(query: String): String {
        val q = query.trim()
        if (q.isBlank()) return "error: file search needs a filename or keyword"
        val projection = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MIME_TYPE, MediaStore.Files.FileColumns.RELATIVE_PATH)
        return try {
            val results = mutableListOf<String>()
            svc.contentResolver.query(
                MediaStore.Files.getContentUri("external"), projection,
                MediaStore.Files.FileColumns.DISPLAY_NAME + " LIKE ?",
                arrayOf("%" + q + "%"), MediaStore.Files.FileColumns.DATE_MODIFIED + " DESC"
            )?.use { c ->
                while (c.moveToNext() && results.size < 12) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: "application/octet-stream"
                    val path = c.getString(3) ?: ""
                    results.add("id=" + id + " | name=" + name + " | type=" + mime + " | folder=" + path)
                }
            }
            if (results.isEmpty()) "No indexed, accessible file matched '" + q + "'. Android storage permissions and scoped-storage rules limit results; try the Files app if it is missing."
            else "FILE MATCHES (use open_file with one exact numeric id):\n" + results.joinToString("\n")
        } catch (e: SecurityException) {
            "error: file search permission denied. Use Android Files to browse or grant the applicable media/storage permission."
        } catch (e: Exception) {
            "error: file search failed (" + e.javaClass.simpleName + ")"
        }
    }

    fun openFile(idText: String): String {
        val id = idText.trim().toLongOrNull() ?: return "error: open_file needs the numeric id returned by find_files"
        if (id < 0L) return "error: file id must be non-negative"
        return try {
            val collection = MediaStore.Files.getContentUri("external")
            val uri = ContentUris.withAppendedId(collection, id)
            val mime = svc.contentResolver.getType(uri) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(svc.packageManager) == null) "error: no installed app can open this file type"
            else {
                svc.startActivity(intent)
                "opened file id " + id + " with its registered viewer; verify the document displayed correctly"
            }
        } catch (e: SecurityException) {
            "error: Android denied access to that file. Run find_files again and use a currently accessible id."
        } catch (e: Exception) {
            "error: could not open file (" + e.javaClass.simpleName + ")"
        }
    }

    // ---- phone features

    fun settings(page: String): String {
        val action = when (page.lowercase().trim()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "storage" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val r = launch(Intent(action))
        return if (r == "ok") "opened $page settings" else r
    }

    fun camera(): String {
        val r = launch(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE))
        return if (r == "ok") "camera opened" else r
    }

    fun navigate(place: String): String {
        val r = launch(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place))))
        if (r == "ok") return "navigation to $place started"
        val r2 = launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
        return if (r2 == "ok") "map opened for $place" else r2
    }

    fun whatsapp(number: String, text: String): String {
        val n = number.filter { it.isDigit() }
        if (n.length < 7) return "error: need a full phone number with country code"
        val r = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n?text=" + Uri.encode(text))))
        return if (r == "ok") "WhatsApp draft ready (not sent)" else r
    }

    fun youtube(query: String): String {
        val r = launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))))
        return if (r == "ok") "opened YouTube results for $query" else r
    }

    fun dial(number: String): String {
        val n = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (n.isEmpty()) return "error: no phone number"
        val r = launch(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))
        return if (r == "ok") "dialer opened with $n (not called yet; the user must press call)" else r
    }

    fun sms(number: String, text: String): String {
        val n = number.filter { it.isDigit() || it == '+' }
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$n"))
        i.putExtra("sms_body", text)
        val r = launch(i)
        return if (r == "ok") "message drafted to $n (not sent). Use ask_user, then press send." else r
    }

    fun alarm(hour: Int, minute: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            .putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val r = launch(i)
        return if (r == "ok") "alarm set for %02d:%02d".format(hour, minute) else r
    }

    fun timer(seconds: Int, label: String): String {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86400))
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val r = launch(i)
        return if (r == "ok") "timer set for $seconds seconds" else r
    }

    fun volume(action: String, percent: Int): String {
        val am = svc.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        when (action.lowercase()) {
            "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "mute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            "unmute" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            "set" -> am.setStreamVolume(
                AudioManager.STREAM_MUSIC, (percent.coerceIn(0, 100) * max / 100), AudioManager.FLAG_SHOW_UI
            )
            else -> return "error: action must be up, down, mute, unmute or set"
        }
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        return "media volume is now about $now%"
    }

    fun flashlight(on: Boolean): String {
        return try {
            val cm = svc.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "error: this phone has no flashlight"
            cm.setTorchMode(id, on)
            if (on) "flashlight on" else "flashlight off"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    fun copy(text: String): String {
        val cb = svc.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cb.setPrimaryClip(ClipData.newPlainText("Orion", text))
        return "copied to clipboard"
    }

    fun battery(): String {
        val i = svc.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "error: cannot read battery"
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (scale > 0) level * 100 / scale else level
        return "battery $pct%" + if (charging) ", charging" else ", not charging"
    }

    fun time(): String = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.getDefault()).format(Date())

    fun deviceInfo(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, ${battery()}"

    // ---- screen recording

    fun recordStart(): String {
        if (RecordService.state == "recording") return "already recording"
        RecordService.state = "waiting"
        RecordService.lastError = ""
        val i = Intent(svc, RecordRequestActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            svc.startActivity(i)
        } catch (e: Exception) {
            RecordService.state = "error"
            return "error: ${e.message}"
        }
        val end = System.currentTimeMillis() + 45000
        while (RecordService.state == "waiting" && System.currentTimeMillis() < end) Thread.sleep(300)
        return when (RecordService.state) {
            "recording" -> "recording started. Tell the user it is recording; stop with record_screen stop."
            "denied" -> "error: the user did not allow screen recording"
            "waiting" -> {
                RecordService.state = "idle"
                "error: the permission popup was not answered in 45 seconds"
            }
            else -> "error: " + RecordService.lastError
        }
    }

    fun recordStop(): String {
        val s = RecordService.instance
        if (s == null || RecordService.state != "recording") return "not recording"
        s.stopRecording()
        val end = System.currentTimeMillis() + 10000
        while (RecordService.state == "recording" && System.currentTimeMillis() < end) Thread.sleep(200)
        Thread.sleep(500)
        return if (RecordService.state == "error") {
            "error: " + RecordService.lastError
        } else {
            "saved as ${RecordService.lastName} in Movies/Orion (open the Gallery or Files app)"
        }
    }

    // ---- memory

    fun remember(category: String?, text: String): String {
        val m = MemoryStore.add(svc, category, text)
            ?: return "error: memory was not saved; check that the text is not empty and device storage is available"
        return "remembered [${m.id}] (${m.category})"
    }

    fun updateMemory(id: Int, category: String?, text: String?): String =
        if (MemoryStore.update(svc, id, category, text)) "updated memory $id"
        else "error: memory $id was not updated; it may not exist or device storage may be unavailable"

    fun forget(id: Int): String =
        if (MemoryStore.delete(svc, id)) "forgot memory $id"
        else "error: memory $id was not deleted; it may not exist or device storage may be unavailable"

    fun recall(query: String): String {
        val list = MemoryStore.search(svc, query).take(15)
        if (list.isEmpty()) return "no matching memories"
        return list.joinToString("\n") { "[${it.id}] (${it.category}) ${it.text}" }
    }
}
