package com.phoneai.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/** Shared Android 13+ notification permission gate. */
internal object OrionNotificationPolicy {
    fun canPost(sdkInt: Int, permissionGranted: Boolean): Boolean = sdkInt < 33 || permissionGranted

    /** Tell the user that durable local work is waiting; never imply it ran in the background. */
    fun postRecoveryPending(context: Context, item: OrionRuntime.WorkItem) {
        if (!OrionAutonomySettings.read(context).notifyOnFailure) return
        val notificationGranted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!OrionNotificationPolicy.canPost(Build.VERSION.SDK_INT, notificationGranted)) return

        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Orion background work", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val open = PendingIntent.getActivity(
                context,
                NOTIFICATION_BASE + 0x3fff,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val detail = item.goal.trim().ifBlank { "A saved local task" }.take(160)
            val body = "$detail is saved. Open Orion to resume; Android may have paused its accessibility service."
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(context)
            }
            manager.notify(
                NOTIFICATION_BASE + 0x3fff,
                builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Orion has work waiting")
                    .setContentText(body)
                    .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

}

/** Posts only user-configured standing-order outcome notifications. */
internal object OrionRuntimeNotifier {
    private const val CHANNEL_ID = "orion_background_work"
    private const val NOTIFICATION_BASE = 42000

    fun postResult(context: Context, item: OrionRuntime.WorkItem, success: Boolean) {
        val config = OrionAutonomySettings.read(context)
        if (success && !config.notifyOnCompletion) return
        if (!success && !config.notifyOnFailure) return
        val notificationGranted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!OrionNotificationPolicy.canPost(Build.VERSION.SDK_INT, notificationGranted)) return

        runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Orion background work", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
            val open = PendingIntent.getActivity(
                context,
                NOTIFICATION_BASE + (item.key.hashCode() and 0x3fff),
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val title = if (success) "Orion finished a background check" else "Orion needs attention"
            val detail = item.goal.trim().ifBlank { "Standing-order check" }.take(180)
            val body = if (success) detail else "$detail could not be completed after its retry limit."
            val builder = if (Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(context)
            }
            manager.notify(
                NOTIFICATION_BASE + (item.key.hashCode() and 0x3fff),
                builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }
}
