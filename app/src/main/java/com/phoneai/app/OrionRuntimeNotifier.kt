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
}

/** Posts user-configured background-work notifications without claiming unsupported execution. */
internal object OrionRuntimeNotifier {
    private const val CHANNEL_ID = "orion_background_work"
    private const val NOTIFICATION_BASE = 42000

    private fun allowed(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return OrionNotificationPolicy.canPost(Build.VERSION.SDK_INT, granted)
    }

    private fun manager(context: Context): NotificationManager {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Orion background work", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        return manager
    }

    private fun builder(context: Context): android.app.Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }

    private fun openIntent(context: Context, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun postResult(context: Context, item: OrionRuntime.WorkItem, success: Boolean) {
        val config = OrionAutonomySettings.read(context)
        if (success && !config.notifyOnCompletion) return
        if (!success && !config.notifyOnFailure) return
        if (!allowed(context)) return

        runCatching {
            val detail = item.goal.trim().ifBlank { "Standing-order check" }.take(180)
            val body = if (success) detail else "$detail could not be completed after its retry limit."
            manager(context).notify(
                NOTIFICATION_BASE + (item.key.hashCode() and 0x3fff),
                builder(context)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(if (success) "Orion finished a background check" else "Orion needs attention")
                    .setContentText(body)
                    .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                    .setContentIntent(openIntent(context, NOTIFICATION_BASE + (item.key.hashCode() and 0x3fff)))
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    /** Tell the user that durable local work is waiting; do not imply it ran in the background. */
    fun postRecoveryPending(context: Context, item: OrionRuntime.WorkItem) {
        if (!OrionAutonomySettings.read(context).notifyOnFailure || !allowed(context)) return
        runCatching {
            val detail = item.goal.trim().ifBlank { "A saved local task" }.take(160)
            val body = "$detail is saved. Open Orion to resume; Android may have paused its accessibility service."
            manager(context).notify(
                NOTIFICATION_BASE + 0x3fff,
                builder(context)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Orion has work waiting")
                    .setContentText(body)
                    .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                    .setContentIntent(openIntent(context, NOTIFICATION_BASE + 0x3fff))
                    .setAutoCancel(true)
                    .build()
            )
        }
    }
}
