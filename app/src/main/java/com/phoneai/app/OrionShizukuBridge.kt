package com.phoneai.app

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku lifecycle and UserService client.
 *
 * Permission and binder state are checked before each call. Binder death clears the cached
 * interface; the next request rebinds. Calls use a fixed AIDL vocabulary, never free-form shell.
 */
internal object OrionShizukuBridge {
    private const val REQUEST_CODE = 0x4f52
    private const val SERVICE_VERSION = 1

    data class State(
        val installedAndRunning: Boolean,
        val permissionGranted: Boolean,
        val uid: Int?,
        val userServiceConnected: Boolean,
        val detail: String
    ) {
        val supportedIdentity: Boolean get() = uid == 0 || uid == 2000
        val ready: Boolean get() = installedAndRunning && permissionGranted && supportedIdentity
        val canExecute: Boolean get() = ready && userServiceConnected
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var remote: IOrionDeviceService? = null
    @Volatile private var binding = false
    @Volatile private var permissionResult: Boolean? = null
    private var connection: ServiceConnection? = null
    private var connectionLatch = CountDownLatch(0)
    private var listenersRegistered = false

    @Synchronized fun initialize(context: Context) {
        appContext = context.applicationContext
        if (listenersRegistered) return
        listenersRegistered = true
        runCatching {
            Shizuku.addBinderReceivedListener {
                appContext?.let { app ->
                    runCatching { SoulEngineState.recordWatcherStatus(app, "event_received", "Shizuku/Sui binder became available") }
                    bindUserService(app)
                }
            }
            Shizuku.addBinderDeadListener {
                synchronized(this) {
                    remote = null
                    binding = false
                    connectionLatch.countDown()
                }
                appContext?.let { app ->
                    runCatching { SoulEngineState.recordWatcherStatus(app, "event_received", "Shizuku/Sui binder disconnected") }
                }
            }
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                if (requestCode == REQUEST_CODE) {
                    permissionResult = grantResult == PackageManager.PERMISSION_GRANTED
                    if (permissionResult == true) appContext?.let { bindUserService(it) }
                }
            }
        }.onFailure { listenersRegistered = false }
        if (runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) {
            appContext?.let { bindUserService(it) }
        }
    }

    fun state(): State {
        val alive = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!alive) return State(false, false, null, false,
            "Shizuku/Sui is not running. On Android 11+, start Shizuku with Wireless debugging; after reboot it may need to be started again.")
        val granted = try {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
        val uid = try { Shizuku.getUid() } catch (_: Throwable) { null }
        val connected = remote != null
        val identity = when (uid) {
            0 -> "root"
            2000 -> "ADB shell"
            null -> "unknown"
            else -> "UID $uid"
        }
        val detail = when {
            !granted -> "Shizuku is running as $identity, but Orion permission is not granted."
            uid != 0 && uid != 2000 -> "Shizuku reports unsupported execution identity $identity. Orion allows only ADB shell (UID 2000) or root (UID 0) for this UserService."
            uid == 2000 -> "Shizuku is ready with ADB shell identity. The shell permission set is limited and Android-version dependent."
            uid == 0 -> "Shizuku is ready with root identity; each operation remains allowlisted and verified."
            else -> "Shizuku binder is active as $identity; each operation must still pass its own capability check."
        }
        return State(true, granted, uid, connected, detail)
    }

    /** Must be called from a user-visible settings/activity action. True means request queued only. */
    fun requestPermission(activity: Activity): Boolean {
        val current = state()
        if (!current.installedAndRunning || current.permissionGranted) return current.permissionGranted
        return try {
            permissionResult = null
            Shizuku.requestPermission(REQUEST_CODE)
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun execute(context: Context, operation: String, argument: String = ""): String {
        val op = operation.trim()
        val allowed = setOf(
            "read_brightness", "read_screen_timeout", "read_airplane_mode", "read_battery",
            "list_user_packages", "set_brightness", "set_screen_timeout"
        )
        if (op !in allowed) return "error: operation is not in Orion's Shizuku allowlist"
        val current = state()
        if (!current.installedAndRunning) return "error: " + current.detail
        if (!current.permissionGranted) return "error: Orion has not been granted Shizuku permission. Open Orion's Shizuku setting and grant it first."
        if (!current.supportedIdentity) return "error: " + current.detail
        initialize(context)
        bindUserService(context.applicationContext)
        val latch = connectionLatch
        if (remote == null && !latch.await(4, TimeUnit.SECONDS)) {
            return "error: Shizuku UserService did not connect within 4 seconds; connection recovery will retry on the next operation"
        }
        val target = remote ?: return "error: Shizuku UserService disconnected; retry the operation"
        return try {
            target.execute(op, argument.take(32))
        } catch (failure: Throwable) {
            synchronized(this) { remote = null; binding = false }
            "error: Shizuku UserService call failed (" + failure.javaClass.simpleName + "); the next request will rebind"
        }
    }

    @Synchronized private fun bindUserService(context: Context) {
        if (remote != null || binding) return
        if (!runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)) return
        binding = true
        connectionLatch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                synchronized(this@OrionShizukuBridge) {
                    remote = IOrionDeviceService.Stub.asInterface(binder)
                    binding = false
                    connectionLatch.countDown()
                }
                appContext?.let { app ->
                    runCatching { SoulEngineState.recordWatcherStatus(app, "event_received", "Shizuku UserService connected") }
                }
                binder?.let { b ->
                    runCatching {
                        b.linkToDeath({
                            synchronized(this@OrionShizukuBridge) {
                                remote = null
                                binding = false
                            }
                        }, 0)
                    }
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                synchronized(this@OrionShizukuBridge) {
                    remote = null
                    binding = false
                }
                appContext?.let { app ->
                    runCatching { SoulEngineState.recordWatcherStatus(app, "event_received", "Shizuku UserService disconnected") }
                }
            }
        }
        connection = conn
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(context, OrionShizukuUserService::class.java))
                .tag("com.phoneai.app.device-operations")
                .daemon(false)
                .processNameSuffix("device")
                .version(SERVICE_VERSION)
            Shizuku.bindUserService(args, conn)
        } catch (_: Throwable) {
            binding = false
            connectionLatch.countDown()
        }
    }

    fun statusText(): String {
        val current = state()
        return buildString {
            appendLine("Shizuku/Sui: " + if (current.installedAndRunning) "running" else "not running")
            appendLine("Orion permission: " + if (current.permissionGranted) "granted" else "not granted")
            appendLine("Execution identity: " + (current.uid?.let {
                if (it == 0) "root (UID 0)" else if (it == 2000) "ADB shell (UID 2000)" else "UID $it"
            } ?: "unavailable"))
            appendLine("UserService: " + if (current.userServiceConnected) "connected" else "not connected")
            append(current.detail)
        }
    }
}
