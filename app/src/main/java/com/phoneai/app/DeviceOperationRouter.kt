package com.phoneai.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentName
import android.app.NotificationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.Settings
import java.util.Locale

/** Typed, allowlisted Android operations. Arguments are values, never shell fragments. */
internal enum class DeviceOperation(val wireName: String, val requiresWrite: Boolean = false) {
    READ_BATTERY("read_battery"),
    READ_AIRPLANE_MODE("read_airplane_mode"),
    READ_BRIGHTNESS("read_brightness"),
    READ_SCREEN_TIMEOUT("read_screen_timeout"),
    LIST_LAUNCHABLE_APPS("list_launchable_apps"),
    LIST_USER_PACKAGES("list_user_packages"),
    READ_VOLUME("read_volume"),
    SET_VOLUME("set_volume", true),
    VOLUME_UP("volume_up", true),
    VOLUME_DOWN("volume_down", true),
    VOLUME_MUTE("volume_mute", true),
    VOLUME_UNMUTE("volume_unmute", true),
    WIFI_SETTINGS("wifi_settings"),
    BLUETOOTH_SETTINGS("bluetooth_settings"),
    DND_SETTINGS("dnd_settings"),
    NOTIFICATION_SETTINGS("notification_settings"),
    SHOW_ALARMS("show_alarms"),
    SET_ALARM("set_alarm", true),
    SET_TIMER("set_timer", true),
    READ_NETWORK("read_network"),
    READ_STORAGE("read_storage"),
    READ_MEMORY("read_memory"),
    DEVICE_INFO("device_info"),
    CAPABILITIES("capabilities"),
    SET_BRIGHTNESS("set_brightness", true),
    SET_SCREEN_TIMEOUT("set_screen_timeout", true);

    companion object {
        fun parse(raw: String): DeviceOperation? =
            values().firstOrNull { it.wireName == raw.trim().lowercase(Locale.ROOT) }
    }
}

internal enum class DeviceBackend { NATIVE_ANDROID, SHIZUKU_USER_SERVICE, ACCESSIBILITY_HANDOFF, UNAVAILABLE }
internal data class DeviceRoute(val operation: DeviceOperation, val backend: DeviceBackend, val reason: String)

/** Pure route policy; Android framework operations never fall through to Linux/PRoot. */
internal object DeviceOperationRoutePolicy {
    fun choose(operation: DeviceOperation, nativeCanExecute: Boolean, shizukuReady: Boolean): DeviceRoute = when {
        nativeCanExecute -> DeviceRoute(operation, DeviceBackend.NATIVE_ANDROID, "native Android API is available")
        shizukuReady && operation in setOf(DeviceOperation.LIST_USER_PACKAGES, DeviceOperation.SET_BRIGHTNESS, DeviceOperation.SET_SCREEN_TIMEOUT) ->
            DeviceRoute(operation, DeviceBackend.SHIZUKU_USER_SERVICE, "Shizuku permission is granted; UserService binds on demand")
        operation.requiresWrite -> DeviceRoute(operation, DeviceBackend.ACCESSIBILITY_HANDOFF, "required Android permission is unavailable; user-facing Settings handoff is required")
        else -> DeviceRoute(operation, DeviceBackend.UNAVAILABLE, "no compatible backend is available")
    }
}

internal object DeviceOperationRouter {
    fun execute(context: Context, rawOperation: String, argument: String = ""): String {
        val operation = DeviceOperation.parse(rawOperation)
            ?: return "error: unsupported device operation. Supported: " + DeviceOperation.values().joinToString(", ") { it.wireName }
        val app = context.applicationContext
        if (operation == DeviceOperation.CAPABILITIES) return capabilityReport(app)
        if (operation == DeviceOperation.SET_BRIGHTNESS && argument.toIntOrNull()?.let { it in 0..255 } != true)
            return "error: brightness must be an integer from 0 to 255"
        if (operation == DeviceOperation.SET_SCREEN_TIMEOUT && argument.toLongOrNull()?.let { it in 15_000L..1_800_000L } != true)
            return "error: screen timeout must be 15000..1800000 milliseconds"
        val nativeCanExecute = when (operation) {
            DeviceOperation.SET_BRIGHTNESS, DeviceOperation.SET_SCREEN_TIMEOUT -> Settings.System.canWrite(app)
            DeviceOperation.LIST_USER_PACKAGES -> false
            else -> true
        }
        val shizuku = OrionShizukuBridge.state()
        val route = DeviceOperationRoutePolicy.choose(operation, nativeCanExecute, shizuku.ready)
        return when (route.backend) {
            DeviceBackend.NATIVE_ANDROID -> executeNative(app, operation, argument)
            DeviceBackend.SHIZUKU_USER_SERVICE -> {
                val result = OrionShizukuBridge.execute(app, operation.wireName, argument)
                val shellWriteRejected = result.startsWith("error: command exited") ||
                    result.startsWith("error: command could not start") || result.contains("write was not verified")
                if (shellWriteRejected && operation in setOf(DeviceOperation.SET_BRIGHTNESS, DeviceOperation.SET_SCREEN_TIMEOUT)) {
                    runCatching {
                        app.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                            .setData(android.net.Uri.parse("package:" + app.packageName))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    "Shizuku could not apply this setting on this Android/OEM build. I opened Android's Modify system settings page as the native fallback. Original result: " + result
                } else result
            }
            DeviceBackend.ACCESSIBILITY_HANDOFF -> {
                if (operation == DeviceOperation.SET_BRIGHTNESS || operation == DeviceOperation.SET_SCREEN_TIMEOUT) {
                    if (shizuku.installedAndRunning && !shizuku.permissionGranted) {
                        runCatching { app.startActivity(Intent(app, OrionShizukuPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        "Shizuku is running but Orion permission is missing. I opened its permission prompt; approve it, then retry."
                    } else {
                        runCatching {
                            app.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                                .setData(android.net.Uri.parse("package:" + app.packageName))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        "Android has not granted Orion Modify system settings. I opened that access page; grant it and retry."
                    }
                } else "error: no compatible Android backend"
            }
            DeviceBackend.UNAVAILABLE -> "error: " + route.reason
        }
    }

    private fun capabilityReport(context: Context): String {
        val shizuku = OrionShizukuBridge.state()
        val canWrite = Settings.System.canWrite(context)
        val accessibility = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().contains(context.packageName, ignoreCase = true)
        val notificationAccess = runCatching {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .isNotificationListenerAccessGranted(ComponentName(context, OrionNotificationListener::class.java))
        }.getOrDefault(false)
        val postNotifications = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return buildString {
            appendLine("ORION DEVICE CAPABILITY INVENTORY")
            appendLine("Clock: alarm/timer intents and alarm listing")
            appendLine("Audio: media stream read/set/up/down/mute/unmute")
            appendLine("Connectivity: Wi-Fi/Bluetooth settings panels; airplane-mode state read-only")
            appendLine("Notification reading/replies: " + if (notificationAccess) "NotificationListener granted" else "NotificationListener access not granted")
            appendLine("Orion notification posting: " + if (postNotifications) "allowed" else "Android 13+ notification permission not granted; background health alerts cannot be shown")
            appendLine("Applications: PackageManager launchable inventory and existing open_app/deep-link tools")
            appendLine("Files: existing MediaStore Downloads and SAF document tools")
            appendLine("System info: battery, network, storage, memory, Android build")
            appendLine("Brightness/timeout writes: " + if (canWrite) "native Android API" else if (shizuku.ready) "Shizuku UserService fallback" else "Settings access needed")
            appendLine("Accessibility: " + if (accessibility) "enabled" else "not enabled; UI automation needs an active accessible window")
            appendLine("Shizuku: " + shizuku.detail)
            appendLine("PRoot: computation and file processing only; never used as Android settings backend")
            append("Restrictions: Android/OEM may block background UI, exact alarm delivery, or specific setting changes. No lock bypass or arbitrary app-private data access.")
        }
    }

    private fun executeNative(context: Context, operation: DeviceOperation, argument: String): String {
        return try {
            when (operation) {
                DeviceOperation.READ_BATTERY -> battery(context)
                DeviceOperation.READ_AIRPLANE_MODE ->
                    "airplane_mode=" + Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
                DeviceOperation.READ_BRIGHTNESS ->
                    "screen_brightness=" + Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
                DeviceOperation.READ_SCREEN_TIMEOUT ->
                    "screen_timeout_ms=" + Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1L)
                DeviceOperation.LIST_LAUNCHABLE_APPS -> {
                    val pm = context.packageManager
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    pm.queryIntentActivities(intent, 0).map { it.loadLabel(pm).toString() }.distinct().sorted()
                        .take(100).joinToString("\n").ifBlank { "No launchable apps found." }
                }
                DeviceOperation.LIST_USER_PACKAGES -> "error: full package inventory requires Shizuku shell identity"
                DeviceOperation.READ_VOLUME -> {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    "media_volume_percent=" + (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
                }
                DeviceOperation.SET_VOLUME -> {
                    val percent = argument.toIntOrNull()?.takeIf { it in 0..100 }
                        ?: return "error: volume must be an integer from 0 to 100"
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, percent * max / 100, 0)
                    val actual = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
                    "verified: media_volume_percent=$actual"
                }
                DeviceOperation.VOLUME_UP, DeviceOperation.VOLUME_DOWN, DeviceOperation.VOLUME_MUTE, DeviceOperation.VOLUME_UNMUTE -> {
                    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val direction = when (operation) {
                        DeviceOperation.VOLUME_UP -> AudioManager.ADJUST_RAISE
                        DeviceOperation.VOLUME_DOWN -> AudioManager.ADJUST_LOWER
                        DeviceOperation.VOLUME_MUTE -> AudioManager.ADJUST_MUTE
                        else -> AudioManager.ADJUST_UNMUTE
                    }
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    "media_volume_percent=" + (audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max)
                }
                DeviceOperation.WIFI_SETTINGS -> openSettings(context, "wifi")
                DeviceOperation.BLUETOOTH_SETTINGS -> openSettings(context, "bluetooth")
                DeviceOperation.DND_SETTINGS -> openSettings(context, "dnd")
                DeviceOperation.NOTIFICATION_SETTINGS -> openSettings(context, "notifications")
                DeviceOperation.SHOW_ALARMS -> launch(context, Intent(AlarmClock.ACTION_SHOW_ALARMS))
                DeviceOperation.SET_ALARM -> setAlarm(context, argument)
                DeviceOperation.SET_TIMER -> setTimer(context, argument)
                DeviceOperation.READ_NETWORK -> network(context)
                DeviceOperation.READ_STORAGE -> storage(context)
                DeviceOperation.READ_MEMORY -> memory(context)
                DeviceOperation.DEVICE_INFO -> "${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); ${battery(context)}"
                DeviceOperation.CAPABILITIES -> "error: capability inventory must be handled before native dispatch"
                DeviceOperation.SET_BRIGHTNESS -> {
                    val value = argument.toIntOrNull()?.takeIf { it in 0..255 }
                        ?: return "error: brightness must be an integer from 0 to 255"
                    if (!Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value))
                        "error: Android rejected the brightness update"
                    else if (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1) == value)
                        "verified: screen brightness=$value"
                    else "error: brightness update could not be verified"
                }
                DeviceOperation.SET_SCREEN_TIMEOUT -> {
                    val value = argument.toLongOrNull()?.takeIf { it in 15_000L..1_800_000L }
                        ?: return "error: screen timeout must be 15000..1800000 milliseconds"
                    if (!Settings.System.putLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, value))
                        "error: Android rejected the screen-timeout update"
                    else if (Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1L) == value)
                        "verified: screen timeout=$value ms"
                    else "error: screen-timeout update could not be verified"
                }
            }
        } catch (failure: SecurityException) {
            "error: Android denied this operation; check the specific Android permission or access grant"
        } catch (failure: Exception) {
            "error: device operation failed (" + failure.javaClass.simpleName + ")"
        }
    }

    private fun battery(context: Context): String {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return "error: battery state unavailable"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (level < 0 || scale <= 0) return "error: battery level unavailable"
        val temp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        return "battery=${level * 100 / scale}%; charging=$charging; temperature_c=${if (temp >= 0) temp / 10.0 else "unknown"}"
    }

    private fun openSettings(context: Context, kind: String): String {
        val intent = when (kind) {
            "wifi" -> if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "dnd" -> Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            else -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return launch(context, intent)
    }

    private fun setAlarm(context: Context, raw: String): String {
        val parts = raw.split("|", limit = 2)
        val time = parts[0].trim().split(":")
        if (time.size != 2) return "error: set_alarm value must be HH:mm|optional label"
        val hour = time[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return "error: alarm hour must be 00..23"
        val minute = time[1].toIntOrNull()?.takeIf { it in 0..59 } ?: return "error: alarm minute must be 00..59"
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour).putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, parts.getOrNull(1).orEmpty().take(80))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(context, intent, "alarm request sent; OEM clock support varies, so verify it was saved")
    }

    private fun setTimer(context: Context, raw: String): String {
        val seconds = raw.toIntOrNull()?.takeIf { it in 1..86_400 } ?: return "error: set_timer value must be 1..86400 seconds"
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        return launch(context, intent, "timer request sent; verify the clock app accepted it")
    }

    private fun launch(context: Context, intent: Intent, success: String = "Android activity opened"): String = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        success
    } catch (e: Exception) {
        "error: Android could not open the requested system activity (${e.javaClass.simpleName})"
    }

    private fun network(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = cm.activeNetwork ?: return "network=offline"
        val caps = cm.getNetworkCapabilities(active) ?: return "network=connected; transport=unknown"
        val transport = when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            android.os.Build.VERSION.SDK_INT >= 26 && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
        return "network=connected; transport=$transport; validated=${caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
    }

    private fun storage(context: Context): String {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val total = stat.totalBytes
        val free = stat.availableBytes
        return "internal_storage_total_bytes=$total; available_bytes=$free; used_percent=${if (total > 0) ((total - free) * 100 / total) else 0}"
    }

    private fun memory(context: Context): String {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return "ram_total_bytes=${info.totalMem}; available_bytes=${info.availMem}; low_memory=${info.lowMemory}"
    }
}
