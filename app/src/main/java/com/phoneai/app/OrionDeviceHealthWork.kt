package com.phoneai.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Low-frequency local monitor: battery and free storage only; no app or personal-content inspection. */
internal class OrionDeviceHealthWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!OrionMasterControl.isEnabled(applicationContext)) return Result.success()
        val battery = applicationContext.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val free = stat.availableBytes
        val total = stat.totalBytes
        val freePercent = if (total > 0) (free * 100 / total).toInt() else null
        val alerts = OrionDeviceHealthPolicy.alerts(percent, charging, free, freePercent)
        if (alerts.isNotEmpty()) post(alerts.joinToString(" ")) else clear()
        return Result.success()
    }

    private fun clear() {
        runCatching {
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(7319)
        }
    }

    private fun post(message: String) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "orion_device_health"
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(channel, "Orion device health", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(context, 7319, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(context, channel)
            else android.app.Notification.Builder(context)
        manager.notify(7319, builder.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Orion device health").setContentText(message)
            .setStyle(android.app.Notification.BigTextStyle().bigText(message))
            .setContentIntent(open).setAutoCancel(true).build())
    }
}

internal object OrionDeviceHealthPolicy {
    fun alerts(batteryPercent: Int?, charging: Boolean, freeBytes: Long, freePercent: Int?): List<String> {
        val result = mutableListOf<String>()
        if (batteryPercent != null && batteryPercent in 0..15 && !charging) result.add("Battery is low (" + batteryPercent + "%) and not charging.")
        if (freeBytes >= 0 && freeBytes < 2147483648L) result.add("Internal storage is critically low (" + (freeBytes / 1048576L) + " MB free).")
        else if (freePercent != null && freePercent < 5) result.add("Internal storage is below 5% free.")
        return result
    }
}

internal object OrionDeviceHealthWork {
    private const val NAME = "orion_device_health_monitor"
    fun ensureScheduled(context: Context) {
        val app = context.applicationContext
        runCatching {
            val manager = WorkManager.getInstance(app)
            if (!OrionMasterControl.isEnabled(app)) {
                manager.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<OrionDeviceHealthWorker>(6, TimeUnit.HOURS)
                .addTag(NAME).build()
            manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
