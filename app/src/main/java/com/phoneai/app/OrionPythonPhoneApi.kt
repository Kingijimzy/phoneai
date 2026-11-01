package com.phoneai.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
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
internal class OrionPythonPhoneApi(private val context: Context) {
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
            val providers = manager.getProviders(true)
            val location = providers.mapNotNull { provider ->
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
        val root = service.rootInActiveWindow ?: return error("there is no active accessibility window")
        val pkg = root.packageName?.toString().orEmpty()
        if (SafetyGate.isSensitiveApp(pkg, pkg)) return error("screen reading is blocked on a sensitive app")
        val rows = JSONArray()
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 80) {
            val node = queue.removeFirst()
            visited++
            val text = (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().trim()
            if (node.isVisibleToUser && text.isNotBlank()) {
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                rows.put(JSONObject().put("text", text.take(140))
                    .put("clickable", node.isClickable)
                    .put("bounds", JSONArray().put(rect.left).put(rect.top).put(rect.right).put(rect.bottom)))
            }
            for (i in 0 until minOf(node.childCount, 20)) node.getChild(i)?.let { queue.add(it) }
        }
        return JSONObject().put("package", pkg.take(100)).put("elements", rows)
            .put("truncated", queue.isNotEmpty()).toString()
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

    /** GET only. Hosts must be explicitly owner-granted and the NETWORK scope must remain active. */
    fun http_get(url: String): String {
        val endpoint = OrionPythonNetworkPolicy.validate(context, url)
            ?: return error("network request blocked: grant NETWORK and explicitly allow this host first")
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(endpoint.uri.toString()).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
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
