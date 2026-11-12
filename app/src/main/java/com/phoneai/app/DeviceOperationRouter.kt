package com.phoneai.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.os.BatteryManager

/** Typed operation vocabulary shared by planning, capability inventory and backend routing. */
internal enum class DeviceOperation(val wireName: String, val requiresWrite: Boolean) {
    READ_BATTERY("read_battery", false),
    READ_AIRPLANE_MODE("read_airplane_mode", false),
    READ_BRIGHTNESS("read_brightness", false),
    READ_SCREEN_TIMEOUT("read_screen_timeout", false),
    LIST_USER_APPS("list_user_apps", false),
    CAPABILITIES("capabilities", false),
    SET_BRIGHTNESS("set_brightness", true),
    SET_SCREEN_TIMEOUT("set_screen_timeout", true);

    companion object {
        fun parse(raw: String): DeviceOperation? =
            values().firstOrNull { it.wireName == raw.trim().lowercase() }
    }
}

internal enum class DeviceBackend { NATIVE_ANDROID, SHIZUKU_USER_SERVICE, ACCESSIBILITY_HANDOFF, UNAVAILABLE }

internal data class DeviceRoute(val operation: DeviceOperation, val backend: DeviceBackend, val reason: String)

/** Pure route policy; Android framework settings are never sent to Linux/PRoot. */
internal object DeviceOperationRoutePolicy {
    fun choose(operation: DeviceOperation, nativeCanExecute: Boolean, shizukuReady: Boolean): DeviceRoute = when {
        nativeCanExecute -> DeviceRoute(operation, DeviceBackend.NATIVE_ANDROID, "native Android API is available")
        shizukuReady -> DeviceRoute(operation, DeviceBackend.SHIZUKU_USER_SERVICE, "Shizuku UserService is connected")
        operation.requiresWrite -> DeviceRoute(operation, DeviceBackend.ACCESSIBILITY_HANDOFF, "settings access is unavailable; user-facing Settings handoff is required")
        else -> DeviceRoute(operation, DeviceBackend.UNAVAILABLE, "no compatible backend is available")
    }
}

internal object DeviceOperationRouter {
    fun execute(context: Context, rawOperation: String, argument: String = ""): String {
        val operation = DeviceOperation.parse(rawOperation)
            ?: return "error: unsupported device operation. Supported: " + DeviceOperation.values().joinToString(", ") { it.wireName }
        val app = context.applicationContext
        if (operation == DeviceOperation.CAPABILITIES) return capabilityReport(app)
        val nativeCanExecute = when (operation) {
            DeviceOperation.SET_BRIGHTNESS, DeviceOperation.SET_SCREEN_TIMEOUT -> Settings.System.canWrite(app)
            else -> true
        }
        val shizuku = OrionShizukuBridge.state()
        val route = DeviceOperationRoutePolicy.choose(operation, nativeCanExecute, shizuku.ready)
        return when (route.backend) {
            DeviceBackend.NATIVE_ANDROID -> executeNative(app, operation, argument)
            DeviceBackend.SHIZUKU_USER_SERVICE -> OrionShizukuBridge.execute(app, operation.wireName, argument)
            DeviceBackend.ACCESSIBILITY_HANDOFF -> {
                if (shizuku.installedAndRunning && !shizuku.permissionGranted) {
                    runCatching {
                        app.startActivity(Intent(app, OrionShizukuPermissionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    "Shizuku is running but Orion permission is missing. I opened the Shizuku permission prompt; approve it, then retry."
                } else {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                        .setData(android.net.Uri.parse("package:" + app.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { app.startActivity(intent) }
                    "Android has not granted Orion 'Modify system settings'. I opened the system access page. Grant it, or start Shizuku and grant Orion access, then retry."
                }
            }
            DeviceBackend.UNAVAILABLE -> "error: " + route.reason
        }
    }


    private fun capabilityReport(context: Context): String {
        val shizuku = OrionShizukuBridge.state()
        val canWriteSettings = Settings.System.canWrite(context)
        val enabledAccessibility = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().contains(context.packageName, ignoreCase = true)
        val settingBackend = when {
            canWriteSettings -> "native Android API"
            shizuku.ready -> "Shizuku UserService (permission granted; bind on demand)"
            shizuku.installedAndRunning -> "awaiting Shizuku permission"
            else -> "user-facing Settings handoff"
        }
        return buildString {
            appendLine("ORION DEVICE CAPABILITY INVENTORY")
            appendLine("Native Android: battery, airplane-mode state, screen brightness, screen timeout, launchable app inventory")
            appendLine("System-setting writes: " + settingBackend)
            appendLine("Accessibility: " + if (enabledAccessibility) "enabled in Android settings" else "not enabled")
            appendLine("Shizuku/Sui: " + shizuku.detail)
            appendLine("Shizuku UserService: " + if (shizuku.userServiceConnected) "connected" else "not yet connected; reconnects on the next request")
            appendLine("Linux/PRoot: use the existing explicit Linux runtime for Python, scraping and file processing; it is not a system-settings backend")
            append("Policy: native Android first; Shizuku only for allowlisted privileged gaps; Accessibility is for visible UI handoff; no arbitrary shell or secure-lock bypass.")
        }
    }

    private fun executeNative(context: Context, operation: DeviceOperation, argument: String): String {
        return try {
            when (operation) {
            DeviceOperation.READ_BATTERY -> {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?: return "error: battery state unavailable"
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level < 0 || scale <= 0) "error: battery level unavailable" else "battery=" + (level * 100 / scale) + "%"
            }
            DeviceOperation.READ_AIRPLANE_MODE ->
                "airplane_mode=" + Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
            DeviceOperation.READ_BRIGHTNESS ->
                "screen_brightness=" + Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
            DeviceOperation.READ_SCREEN_TIMEOUT ->
                "screen_timeout_ms=" + Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1L)
            DeviceOperation.LIST_USER_APPS -> {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps = pm.queryIntentActivities(intent, 0).map { it.loadLabel(pm).toString() }.distinct().sorted()
                apps.take(100).joinToString("\n").ifBlank { "No launchable apps found." }
            }
            DeviceOperation.CAPABILITIES -> "error: capability inventory must be handled before native dispatch"
            DeviceOperation.SET_BRIGHTNESS -> {
                val value = argument.toIntOrNull()?.takeIf { it in 0..255 }
                    ?: return "error: brightness must be an integer from 0 to 255"
                if (!Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value))
                    "error: Android rejected the brightness update"
                else if (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1) == value)
                    "verified: screen brightness=" + value
                else "error: brightness update could not be verified"
            }
            DeviceOperation.SET_SCREEN_TIMEOUT -> {
                val value = argument.toLongOrNull()?.takeIf { it in 15_000L..1_800_000L }
                    ?: return "error: screen timeout must be 15000..1800000 milliseconds"
                if (!Settings.System.putLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, value))
                    "error: Android rejected the screen-timeout update"
                else if (Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1L) == value)
                    "verified: screen timeout=" + value + " ms"
                else "error: screen-timeout update could not be verified"
            }
            }
        } catch (failure: SecurityException) {
            "error: Android denied this operation; route requires a different granted backend"
        } catch (failure: Exception) {
            "error: device operation failed (" + failure.javaClass.simpleName + ")"
        }
    }
}