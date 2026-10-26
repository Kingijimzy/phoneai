package com.phoneai.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Shared Termux RUN_COMMAND transport. AI-callable execution is restricted to exact read-only diagnostics. */
object TermuxBridgeRunner {
    private data class WaitingResult(val latch: CountDownLatch = CountDownLatch(1), @Volatile var text: String = "")
    private val waiting = ConcurrentHashMap<Int, WaitingResult>()
    private val ids = AtomicInteger(2000)

    fun nextRequestId(): Int = ids.getAndIncrement()

    fun executeReadOnly(context: Context, command: String, shouldStop: () -> Boolean = { false }): String {
        if (!TermuxCommandPolicy.isReadOnly(command)) {
            return "error: the AI Termux tool is limited to exact read-only diagnostics. Use the Termux Bridge screen for other commands, where Orion applies its approval policy."
        }
        val preflight = preflight(context)
        if (preflight != null) return "error: $preflight"
        val id = nextRequestId()
        val pending = WaitingResult()
        waiting[id] = pending
        val dispatchError = send(context, command, id)
        if (dispatchError != null) {
            waiting.remove(id)
            return "error: Termux command was not dispatched. $dispatchError"
        }
        val timeoutMs = AgentPolicy.maxMinutes(context).coerceIn(1, 30) * 60_000L
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                if (shouldStop()) {
                    waiting.remove(id)
                    return "error: Orion stopped waiting for the Termux diagnostic. Its read-only command may continue until the configured Termux timeout; no completion is claimed."
                }
                if (pending.latch.await(250L, TimeUnit.MILLISECONDS)) {
                    waiting.remove(id)
                    return pending.text
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            waiting.remove(id)
            return "error: Orion interrupted the Termux wait. The read-only command may continue until its configured timeout; no completion is claimed."
        }
        waiting.remove(id)
        return "error: Termux did not return a result before Orion's configured time limit. Outcome is unverified; the bounded read-only command may still be finishing in Termux."
    }

    /** Manual terminal UI has already checked approval; this method only validates setup and dispatches. */
    fun dispatch(context: Context, command: String): String {
        val preflight = preflight(context)
        if (preflight != null) return "error: $preflight"
        val id = nextRequestId()
        val error = send(context, command, id)
        return if (error == null) {
            "Command dispatched to Termux. Waiting for Termux's result callback; execution is not confirmed yet."
        } else "error: Termux command was not dispatched. $error"
    }

    /** Returns null only when the configured Termux transport passes all local readiness checks. */
    fun readiness(context: Context): String? = preflight(context)

    private fun preflight(context: Context): String? {
        val prefs = context.getSharedPreferences("phoneai", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("termux_bridge_enabled", false) || !AgentPolicy.enabled(context, "termux_bridge")) {
            return "Termux bridge is disabled."
        }
        try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo("com.termux", 0)
            if (info.packageName != "com.termux") return "Termux app is not installed."
        } catch (_: Exception) {
            return "Termux app is not installed."
        }
        if (Build.VERSION.SDK_INT >= 23 &&
            context.checkSelfPermission("com.termux.permission.RUN_COMMAND") != PackageManager.PERMISSION_GRANTED) {
            return "PhoneAI lacks the additional Android Run commands in Termux environment permission."
        }
        return null
    }

    private fun send(context: Context, command: String, requestCode: Int): String? {
        return try {
            val callback = Intent(context, TermuxBridgeResultReceiver::class.java)
                .putExtra(TermuxBridgeResultReceiver.EXTRA_REQUEST_ID, requestCode)
            val pendingFlags = PendingIntent.FLAG_ONE_SHOT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val resultPendingIntent = PendingIntent.getBroadcast(context, requestCode, callback, pendingFlags)
            val maxSeconds = AgentPolicy.maxMinutes(context).coerceIn(1, 30) * 60
            val intent = Intent().apply {
                setClassName("com.termux", "com.termux.app.RunCommandService")
                action = "com.termux.RUN_COMMAND"
                putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/timeout")
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(
                    "${maxSeconds}s", "/data/data/com.termux/files/usr/bin/bash", "-lc", command
                ))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Orion approved command")
                putExtra("com.termux.RUN_COMMAND_COMMAND_DESCRIPTION", "Command dispatched from Orion's Termux bridge.")
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", resultPendingIntent)
            }
            context.startService(intent)
            AgentPolicy.log(context, "termux_command", "dispatch-result", null, "Android accepted Termux service start; awaiting result callback")
            null
        } catch (e: SecurityException) {
            AgentPolicy.log(context, "termux_command", "dispatch-result", false, "Termux permission or external-app policy rejected service start")
            "Termux rejected the request. Check PhoneAI's Run commands permission and Termux's allow-external-apps setting."
        } catch (e: Exception) {
            AgentPolicy.log(context, "termux_command", "dispatch-result", false, "service start failed: ${e.javaClass.simpleName}")
            "service start failed: ${e.javaClass.simpleName}"
        }
    }

    internal fun deliver(requestId: Int, result: String) {
        TermuxBridgeResultStore.lastResult = result
        waiting[requestId]?.let {
            it.text = result
            it.latch.countDown()
        }
        TermuxBridgeResultStore.listener?.invoke()
    }
}
