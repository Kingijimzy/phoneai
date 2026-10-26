package com.phoneai.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

/** Local-only opt-in daily recap. It does not run an AI model or claim always-on monitoring. */
object ProactiveBriefing {
    private const val CHANNEL = "orion_proactive_briefing"
    private const val ACTION = "com.phoneai.app.PROACTIVE_BRIEFING"
    private const val REQUEST_CODE = 84017

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences("phoneai", Context.MODE_PRIVATE).getBoolean("proactive_briefing_enabled", false) &&
            AgentPolicy.enabled(context, "proactive")

    fun schedule(context: Context) {
        if (!enabled(context)) {
            cancel(context)
            return
        }
        val prefs = context.getSharedPreferences("phoneai", Context.MODE_PRIVATE)
        val hour = prefs.getInt("proactive_briefing_hour", 8).coerceIn(0, 23)
        val minute = prefs.getInt("proactive_briefing_minute", 0).coerceIn(0, 59)
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now.timeInMillis) add(Calendar.DAY_OF_YEAR, 1)
        }
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setInexactRepeating(
            AlarmManager.RTC_WAKEUP, next.timeInMillis, AlarmManager.INTERVAL_DAY, pending(context)
        )
    }

    fun cancel(context: Context) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pending(context))
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, ProactiveBriefingReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    internal fun notifyBriefing(context: Context) {
        if (!enabled(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Orion proactive briefing", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            AgentPolicy.log(context, "proactive_briefing", "notification", false, "notification permission missing; no notification posted")
            return
        }
        val now = System.currentTimeMillis()
        val active = TaskStore.all(context).filter { it.status !in setOf("completed", "done", "failed", "cancelled") }
        val overdue = active.count { it.dueAt != null && it.dueAt!! <= now }
        val urgent = active.count { it.priority == "urgent" }
        val body = "Open tasks: ${active.size} · Overdue: $overdue · Urgent: $urgent. Tap to review your local task list."
        val open = PendingIntent.getActivity(
            context, REQUEST_CODE, Intent(context, TaskCenterActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            android.app.Notification.Builder(context, CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(context)
        }.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Orion morning briefing")
            .setContentText(body)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(84017, notification)
        AgentPolicy.log(context, "proactive_briefing", "notification", true, "local task-count briefing delivered; no AI/API request")
    }
}

class ProactiveBriefingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "com.phoneai.app.PROACTIVE_BRIEFING") {
            ProactiveBriefing.notifyBriefing(context)
        }
    }
}

class ProactiveActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val prefs by lazy { getSharedPreferences("phoneai", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var packSwitch: Switch
    private lateinit var briefingSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Theme.BG) }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(26))
        }
        scroll.addView(root)
        setContentView(scroll)
        addText("ORION / PROACTIVE", 10f, Theme.MUTED, true, 0)
        addText("Local briefing controls", 24f, Theme.TEXT, true, 5)
        addText("Opt-in only. The daily briefing reads the on-device task list and posts one notification at your chosen time. It does not call an AI provider, scan the phone continuously, or promise exact delivery time; Android may delay inexact alarms.", 13f, Theme.MUTED, false, 8)
        addButton("‹ Back to Agent Control") { finish() }

        section("CAPABILITY PACK")
        packSwitch = Switch(this).apply {
            text = "Enable Proactive pack"
            setTextColor(Theme.TEXT)
            isChecked = AgentPolicy.enabled(this@ProactiveActivity, "proactive")
            setOnCheckedChangeListener { _, checked ->
                AgentPolicy.setEnabled(this@ProactiveActivity, "proactive", checked)
                if (checked) ProactiveBriefing.schedule(this@ProactiveActivity) else ProactiveBriefing.cancel(this@ProactiveActivity)
                AgentPolicy.log(this@ProactiveActivity, "proactive", "toggle-pack", true, if (checked) "proactive pack enabled" else "proactive pack disabled")
                refreshStatus()
            }
        }
        root.addView(packSwitch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        section("DAILY MORNING BRIEFING")
        briefingSwitch = Switch(this).apply {
            text = "Send a daily local task recap"
            setTextColor(Theme.TEXT)
            isChecked = prefs.getBoolean("proactive_briefing_enabled", false)
            setOnCheckedChangeListener { _, checked ->
                if (checked && !AgentPolicy.enabled(this@ProactiveActivity, "proactive")) {
                    toast("Enable the Proactive capability pack first.")
                    isChecked = false
                    return@setOnCheckedChangeListener
                }
                if (checked && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
                    return@setOnCheckedChangeListener
                }
                prefs.edit().putBoolean("proactive_briefing_enabled", checked).apply()
                if (checked) ProactiveBriefing.schedule(this@ProactiveActivity) else ProactiveBriefing.cancel(this@ProactiveActivity)
                AgentPolicy.log(this@ProactiveActivity, "proactive_briefing", "opt-in", true, if (checked) "user opted into daily local task recap" else "user opted out of daily recap")
                refreshStatus()
            }
        }
        root.addView(briefingSwitch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        addButton("Choose briefing time") { chooseTime() }
        addButton("Send a preview now") {
            if (!AgentPolicy.enabled(this, "proactive")) {
                toast("Enable the Proactive pack first.")
            } else if (!prefs.getBoolean("proactive_briefing_enabled", false)) {
                toast("Opt in to the daily briefing first.")
            } else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            } else {
                ProactiveBriefing.notifyBriefing(this)
                toast("Local briefing preview posted.")
            }
        }
        section("STATUS")
        status = addText("", 13f, Theme.TEXT, false, 5)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }

    private fun chooseTime() {
        val hour = prefs.getInt("proactive_briefing_hour", 8).coerceIn(0, 23)
        val minute = prefs.getInt("proactive_briefing_minute", 0).coerceIn(0, 59)
        TimePickerDialog(this, { _, h, m ->
            prefs.edit().putInt("proactive_briefing_hour", h).putInt("proactive_briefing_minute", m).apply()
            if (ProactiveBriefing.enabled(this)) ProactiveBriefing.schedule(this)
            refreshStatus()
        }, hour, minute, false).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted && AgentPolicy.enabled(this, "proactive")) {
                prefs.edit().putBoolean("proactive_briefing_enabled", true).apply()
                briefingSwitch.isChecked = true
                ProactiveBriefing.schedule(this)
                toast("Daily briefing enabled.")
            } else {
                prefs.edit().putBoolean("proactive_briefing_enabled", false).apply()
                briefingSwitch.isChecked = false
                ProactiveBriefing.cancel(this)
                toast("Notifications permission was not granted; daily briefing remains off.")
            }
            refreshStatus()
        }
    }

    private fun refreshStatus() {
        if (!::status.isInitialized) return
        val enabled = ProactiveBriefing.enabled(this)
        val hour = prefs.getInt("proactive_briefing_hour", 8).coerceIn(0, 23)
        val minute = prefs.getInt("proactive_briefing_minute", 0).coerceIn(0, 59)
        val time = "%02d:%02d".format(hour, minute)
        status.text = "Proactive pack: ${if (AgentPolicy.enabled(this, "proactive")) "enabled" else "OFF"}\n" +
            "Daily briefing opt-in: ${if (enabled) "ON" else "OFF"}\n" +
            "Scheduled local time: $time (Android may delay it)\n" +
            "Data source: on-device task list only · no AI/API request."
    }

    private fun addText(text: String, size: Float, color: Int, bold: Boolean, top: Int): TextView {
        val view = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        root.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
        return view
    }
    private fun section(text: String) { addText(text, 10f, Theme.MUTED, true, 18).letterSpacing = .12f }
    private fun addButton(text: String, action: () -> Unit) {
        val button = Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(Theme.TEXT)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF222222.toInt())
            setOnClickListener { action() }
        }
        root.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_NOTIFICATIONS = 8041
    }
}
