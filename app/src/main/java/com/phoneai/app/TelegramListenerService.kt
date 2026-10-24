package com.phoneai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-started Telegram responder. Polls the Telegram Bot API while this foreground service is active.
 * Android/HyperOS can still stop it; the persistent notification makes its state visible.
 */
class TelegramListenerService : Service() {
    companion object {
        private const val CHANNEL = "orion_telegram_listener"
        private const val NOTIFICATION_ID = 4218
        private const val STATE_PREFS = "orion_telegram_listener_state"
        private const val KEY_ENABLED = "enabled"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun start(context: Context): Boolean {
            if (!TelegramBridge.isConfigured(context)) return false
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).apply()
            return try {
                context.startForegroundService(Intent(context, TelegramListenerService::class.java))
                true
            } catch (_: Exception) {
                context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
                false
            }
        }

        fun stop(context: Context) {
            context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
            context.stopService(Intent(context, TelegramListenerService::class.java))
        }
    }

    private val active = AtomicBoolean(false)
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isEnabled(this) || !TelegramBridge.isConfigured(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Orion Telegram replies", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shows when Orion is listening for Telegram messages." }
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Orion Telegram responder is active")
            .setContentText("Checking for your messages and replying in the background")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply()
            stopSelf()
            return START_NOT_STICKY
        }

        if (active.compareAndSet(false, true)) {
            worker = Thread({
                val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                val wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Orion:TelegramListener")
                try {
                    wakeLock.acquire(10 * 60 * 1000L)
                    while (active.get() && isEnabled(this) && TelegramBridge.isConfigured(this)) {
                        try {
                            TelegramBridge.checkUpdates(this)
                        } catch (_: Exception) {
                            // Keep listening; a later poll can recover from transient network/provider errors.
                        }
                        try { Thread.sleep(3000L) } catch (_: InterruptedException) { break }
                    }
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                    active.set(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }, "OrionTelegramListener").apply {
                isDaemon = true
                start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        active.set(false)
        worker?.interrupt()
        worker = null
        super.onDestroy()
    }
}
