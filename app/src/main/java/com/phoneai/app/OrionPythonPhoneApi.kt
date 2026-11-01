package com.phoneai.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Narrow Python-to-Android bridge. Every method is explicit, read/write-scoped and rechecks
 * consent at call time. The Python AST admits only phone.<registered_method>() calls.
 */
internal class OrionPythonPhoneApi(
    private val context: Context,
    private val confirm: (String) -> Boolean = { false }
) {
    private val workspace = OrionPythonWorkspace(context)

    fun battery(): String {
        val manager = context.getSystemService(BatteryManager::class.java)
        val level = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val status = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        return JSONObject().put("level_percent", level.takeIf { it in 0..100 })
            .put("charging", status?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) in setOf(
                BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL
            )).put("power_save", runCatching {
                context.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode
            }.getOrNull()).toString()
    }

    fun location(): String {
        if (!OrionSensorConsent.read(context, OrionSensorScope.LOCATION).active())
            return error("location access is not enabled in Sensor & context permissions")
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return error("Android location permission is not granted")
        return try {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location = manager.getProviders(true).mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }.maxByOrNull { it.time } ?: return error("no last-known location is available; no background location request was started")
            JSONObject().put("latitude", (location.latitude * 1000.0).toInt() / 1000.0)
                .put("longitude", (location.longitude * 1000.0).toInt() / 1000.0)
                .put("accuracy_m", location.accuracy.toDouble())
                .put("timestamp_ms", location.time)
                .put("source", "last_known_approximate").toString()
        } catch (e: SecurityException) { error("Android denied location access") }
        catch (e: Exception) { error("location read failed: ${e.javaClass.simpleName}") }
    }

    fun notifications(): String {
        if (!OrionSensorConsent.read(context, OrionSensorScope.NOTIFICATIONS).active())
            return error("notification content grant is not enabled")
        return OrionNotificationListener.readSafeNotifications()
    }

    fun contacts(query: String): String {
        if (!OrionSensorConsent.read(context, OrionSensorScope.CONTACTS).active())
            return error("contacts access is not enabled in Sensor & context permissions")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED)
            return error("Android Contacts permission is not granted")
        val term = query.trim().take(100)
        if (term.length < 2) return error("contacts search requires at least two characters")
        val out = JSONArray()
        return try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$term%"),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < 15) {
                    out.put(JSONObject()
                        .put("name", cursor.getString(0).orEmpty().take(100))
                        .put("number", cursor.getString(1).orEmpty().take(40)))
                    count++
                }
            }
            out.toString()
        } catch (e: SecurityException) { error("Android denied Contacts access") }
        catch (e: Exception) { error("contacts query failed: ${e.javaClass.simpleName}") }
    }

    fun accessibility_screen(): String {
        if (!OrionSensorConsent.read(context, OrionSensorScope.SCREEN_CONTEXT).active())
            return error("screen context grant is not enabled")
        val service = PhoneAiService.instance ?: return error("Orion Accessibility Service is not connected")
        // Reuse the production screen reader and suppress its potentially sensitive surfaces entirely
        // at the Python boundary rather than returning partially filtered node text.
        return screenSnapshotIfSafe(service)
            ?: error("screen reading is blocked on a potentially sensitive surface")
    }

    fun ui_tap_text(text: String): String {
        val target = text.trim().take(120)
        if (target.isBlank()) return error("visible text target is required")
        val service = PhoneAiService.instance ?: return error("Orion Accessibility Service is not connected")
        if (!OrionSensorConsent.read(context, OrionSensorScope.SCREEN_CONTEXT).active())
            return error("screen context grant is not enabled")
        if (!screenSafe(service)) return error("accessibility actions are blocked on a potentially sensitive surface")
        if (!confirm("Python requests an accessibility tap on the visible text: \"$target\". Continue?"))
            return error("owner declined or did not approve the UI action")
        return Tools(service).clickText(target)
    }

    fun ui_tap(x: Double, y: Double): String {
        if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x > 20_000 || y > 20_000)
            return error("tap coordinates are invalid")
        val service = PhoneAiService.instance ?: return error("Orion Accessibility Service is not connected")
        if (!OrionSensorConsent.read(context, OrionSensorScope.SCREEN_CONTEXT).active())
            return error("screen context grant is not enabled")
        val metrics = context.resources.displayMetrics
        if (x > metrics.widthPixels || y > metrics.heightPixels) return error("tap coordinates are outside the current display")
        if (!screenSafe(service)) return error("accessibility actions are blocked on a potentially sensitive surface")
        if (!confirm("Python requests an accessibility tap at screen coordinates ${x.toInt()}, ${y.toInt()}. Continue?"))
            return error("owner declined or did not approve the UI action")
        return service.tap(x.toFloat(), y.toFloat())
    }

    fun ui_swipe(x1: Double, y1: Double, x2: Double, y2: Double): String {
        val values = listOf(x1, y1, x2, y2)
        if (values.any { !it.isFinite() || it < 0 || it > 20_000 }) return error("swipe coordinates are invalid")
        val service = PhoneAiService.instance ?: return error("Orion Accessibility Service is not connected")
        if (!OrionSensorConsent.read(context, OrionSensorScope.SCREEN_CONTEXT).active())
            return error("screen context grant is not enabled")
        val metrics = context.resources.displayMetrics
        if (values.filterIndexed { index, _ -> index % 2 == 0 }.any { it > metrics.widthPixels } ||
            values.filterIndexed { index, _ -> index % 2 == 1 }.any { it > metrics.heightPixels })
            return error("swipe coordinates are outside the current display")
        if (!screenSafe(service)) return error("accessibility actions are blocked on a potentially sensitive surface")
        if (!confirm("Python requests an accessibility swipe from (${x1.toInt()}, ${y1.toInt()}) to (${x2.toInt()}, ${y2.toInt()}). Continue?"))
            return error("owner declined or did not approve the UI action")
        return service.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), 350L)
    }

    private fun screenSafe(service: PhoneAiService): Boolean = screenSnapshotIfSafe(service) != null

    private fun screenSnapshotIfSafe(service: PhoneAiService): String? {
        val root = service.rootInActiveWindow ?: return null
        val packageName = root.packageName?.toString().orEmpty()
        if (SafetyGate.isSensitiveApp(packageName, packageName)) return null
        val snapshot = service.readScreen()
        return snapshot.takeIf {
            !it.startsWith("error:") && !it.contains("(OCR withheld on a potentially sensitive screen)")
        }
    }

    fun fs_list(): String = workspace.list()
    fun fs_read(path: String): String = workspace.read(path)
    fun fs_write(path: String, content: String): String = workspace.write(path, content)

    /** JSON argv only. No /bin/sh, shell expansion, pipes, redirects, privilege escalation or root. */
    fun shell(argvJson: String): String {
        val root = File(context.filesDir, "orion-python/workspace")
        if (!root.exists() && !root.mkdirs()) return error("Python workspace is unavailable")
        val command = OrionPythonShellPolicy.parse(argvJson, root.absolutePath)
            ?: return error("command rejected: use a registered read-only command and workspace-relative paths")
        if (command.executable == "pwd") return root.absolutePath
        val output = File(root, ".command-output-${SystemClock.elapsedRealtime()}.tmp")
        return try {
            val executable = File(command.executable)
            if (!executable.isFile || !executable.canExecute()) return error("Android utility is unavailable: ${executable.name}")
            val process = ProcessBuilder(listOf(command.executable) + command.args)
                .directory(root).redirectErrorStream(true).redirectOutput(output).start()
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return error("command timed out after 4 seconds")
            }
            val text = if (output.isFile) output.inputStream().use { it.readNBytes(12_000).toString(Charsets.UTF_8) } else ""
            JSONObject().put("exit_code", process.exitValue()).put("output", text).toString()
        } catch (e: Exception) { error("command failed: ${e.javaClass.simpleName}") }
        finally { output.delete() }
    }

    /** GET is autonomous for explicitly owner-granted hosts; POST requires per-call approval unless the host has a write grant. */
    fun http_get(url: String): String = request(url, "GET", "", requireApproval = false)

    fun http_post(url: String, body: String): String {
        if (body.length > 32_000) return error("HTTP body is limited to 32 KB")
        return request(url, "POST", body, requireApproval = true)
    }

    private fun request(url: String, method: String, body: String, requireApproval: Boolean): String {
        val endpoint = OrionPythonNetworkPolicy.validate(context, url)
            ?: return error("network request blocked: grant NETWORK and explicitly allow this host first")
        if (requireApproval && !endpoint.allowWrites &&
            !confirm("Python requests an HTTP POST to owner-approved host ${endpoint.host}. Send this external request?"))
            return error("owner approval is required for HTTP POST to this host")
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(endpoint.uri.toString()).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            if (method == "POST") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var total = 0
                while (total < 64 * 1024) {
                    val n = input.read(buffer, 0, minOf(buffer.size, 64 * 1024 - total))
                    if (n < 0) break
                    out.write(buffer, 0, n); total += n
                }
                out.toByteArray()
            } ?: ByteArray(0)
            JSONObject().put("status", code).put("body", bytes.toString(Charsets.UTF_8)).toString()
        } catch (e: Exception) { error("HTTP request failed: ${e.javaClass.simpleName}") }
        finally { connection?.disconnect() }
    }

    private fun error(message: String): String = JSONObject().put("error", message.take(300)).toString()
}
