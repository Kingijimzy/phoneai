package com.phoneai.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Date

internal object TaskReminderPolicy {
    fun isTerminal(status: String): Boolean = status.trim().lowercase() in setOf("done", "completed", "cancelled", "canceled")
}

/** Schedules local deadline reminders; no server or paid service is needed. */
object TaskReminder {
    private const val CHANNEL = "orion_task_reminders"
    private const val ACTION_REMIND = "com.phoneai.app.TASK_REMINDER"
    private const val EXTRA_TASK_ID = "task_id"

    private fun pendingIntent(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, TaskReminderReceiver::class.java)
            .setAction(ACTION_REMIND)
            .putExtra(EXTRA_TASK_ID, id)
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context, task: OrionTask) {
        cancel(context, task.id)
        val due = task.dueAt ?: return
        if (TaskReminderPolicy.isTerminal(task.status) || due <= System.currentTimeMillis()) return
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = pendingIntent(context, task.id)
        val exactAccessGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
        if (TaskReminderAlarmPolicy.useExactAlarm(Build.VERSION.SDK_INT, exactAccessGranted)) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, operation)
        } else {
            // Keep the reminder scheduled when exact-alarm special access is unavailable.
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, operation)
        }
    }

    fun cancel(context: Context, id: Int) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pendingIntent(context, id))
    }

    fun restoreAll(context: Context) {
        TaskStore.all(context).forEach { schedule(context, it) }
    }

    internal fun notifyDue(context: Context, id: Int) {
        val task = TaskStore.all(context).firstOrNull { it.id == id } ?: return
        if (TaskReminderPolicy.isTerminal(task.status)) return
        val due = task.dueAt ?: return
        if (due > System.currentTimeMillis()) {
            schedule(context, task)
            return
        }
        val notificationGranted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!OrionNotificationPolicy.canPost(Build.VERSION.SDK_INT, notificationGranted)) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Task reminders", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(context, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Orion task reminder")
            .setContentText(task.title)
            .setStyle(android.app.Notification.BigTextStyle().bigText(
                task.title + if (task.details.isBlank()) "" else "\n" + task.details
            ))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(10000 + id, notification) }
    }
}

class TaskReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val id = intent?.getIntExtra("task_id", -1) ?: -1
        if (id >= 0) TaskReminder.notifyDue(context, id)
    }
}

class TaskReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED ||
            intent?.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) {
            TaskReminder.restoreAll(context)
            ProactiveBriefing.schedule(context)
            GamingFocus.schedule(context)
            OrionProactiveWork.ensureScheduled(context)
            OrionDeviceHealthWork.ensureScheduled(context)
            if (OrionMasterControl.isEnabled(context)) OrionRecoveryWork.schedule(context)
        }
    }
}
