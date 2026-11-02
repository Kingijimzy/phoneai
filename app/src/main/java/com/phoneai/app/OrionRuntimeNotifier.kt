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

    fun shouldNotifyApproval(preferenceEnabled: Boolean, appForeground: Boolean, sdkInt: Int, permissionGranted: Boolean): Boolean =
        preferenceEnabled && !appForeground && canPost(sdkInt, permissionGranted)

    fun shouldNotifyTaskOutcome(preferenceEnabled: Boolean, appForeground: Boolean, sdkInt: Int, permissionGranted: Boolean): Boolean =
        preferenceEnabled && !appForeground && canPost(sdkInt, permissionGranted)
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



    private fun appForeground(): Boolean {
        val process = android.app.ActivityManager.RunningAppProcessInfo()
        runCatching { android.app.ActivityManager.getMyMemoryState(process) }
        return process.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    /** Task-level result notifications are emitted only after durable checkpoint transitions. */
    fun postTaskOutcome(context: Context, success: Boolean) {
        val config = OrionAutonomySettings.read(context)
        val preference = if (success) config.notifyOnCompletion else config.notifyOnFailure
        val granted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!OrionNotificationPolicy.shouldNotifyTaskOutcome(preference, appForeground(), Build.VERSION.SDK_INT, granted)) return
        runCatching {
            val body = if (success) "Your task was completed and its checkpoint was saved."
                else "Your task is paused or needs recovery. Open Orion to review the saved progress."
            val id = NOTIFICATION_BASE + 0x3ffd
            manager(context).notify(
                id,
                builder(context)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(if (success) "Orion completed your task" else "Orion task needs attention")
                    .setContentText(body)
                    .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                    .setContentIntent(openIntent(context, id))
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    /** Notify that evidence is ready in the originating chat; never claim the parent task is complete. */
    fun postSpecialistResult(context: Context, jobId: String) {
        val config = OrionAutonomySettings.read(context)
        val granted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!OrionNotificationPolicy.shouldNotifyTaskOutcome(
                config.notifyOnCompletion, appForeground(), Build.VERSION.SDK_INT, granted
            )) return
        runCatching {
            val id = NOTIFICATION_BASE + (jobId.hashCode() and 0x3fff)
            val body = "A specialist result is saved in its original Orion conversation. Open chat to review the evidence; the parent task is not marked complete."
            manager(context).notify(
                id,
                builder(context)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Orion has a specialist result")
                    .setContentText(body)
                    .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                    .setContentIntent(openIntent(context, id))
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    /** Generic approval alert only; never include the action text or private arguments. */
    fun postApprovalRequired(context: Context) {
        val config = OrionAutonomySettings.read(context)
        val process = android.app.ActivityManager.RunningAppProcessInfo()
        runCatching { android.app.ActivityManager.getMyMemoryState(process) }
        val foreground = process.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val granted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!OrionNotificationPolicy.shouldNotifyApproval(config.notifyOnApproval, foreground, Build.VERSION.SDK_INT, granted)) return
        runCatching {
            val body = "An action is waiting for your approval. Return to Orion to review it."
            manager(context).notify(
                NOTIFICATION_BASE + 0x3ffe,
                builder(context)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Orion needs your approval")
                    .setContentText(body)
                    .setContentIntent(openIntent(context, NOTIFICATION_BASE + 0x3ffe))
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

    /** Routine reminders contain only owner-approved, non-sensitive routine memory text. */
    fun postRoutineReminder(context: Context, title: String) {
        if (!allowed(context)) return
        runCatching {
            val body = title.trim().take(160).ifBlank { "A routine you asked Orion to remind you about." }
            val id = NOTIFICATION_BASE + (body.hashCode() and 0x1fff)
            manager(context).notify(
                id,
                builder(context)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Orion routine reminder")
                    .setContentText(body)
                    .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                    .setContentIntent(openIntent(context, id))
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

}
