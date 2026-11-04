package com.phoneai.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class GameSessionNote(
    val id: Long,
    val game: String,
    val note: String,
    val durationMinutes: Int,
    val result: String,
    val createdAt: Long
)

/** Local-only gaming/hobby notes. Notes are never uploaded automatically. */
object GameCoachStore {
    private val lock = Any()
    private fun file(c: Context) = File(c.filesDir, "orion_game_sessions.json")

    private fun load(c: Context): MutableList<GameSessionNote> = try {
        val f = file(c)
        if (!f.exists()) mutableListOf() else {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                GameSessionNote(
                    o.optLong("id"), o.optString("game"), o.optString("note"),
                    o.optInt("durationMinutes"), o.optString("result"), o.optLong("createdAt")
                )
            }
        }
    } catch (_: Exception) { mutableListOf() }

    fun add(c: Context, game: String, note: String, durationMinutes: Int = 0, result: String = ""): Boolean = synchronized(lock) {
        val cleanGame = game.trim().replace(Regex("\\s+"), " ").take(80)
        val cleanNote = note.trim().take(1000)
        if (cleanGame.isBlank() || cleanNote.isBlank()) return@synchronized false
        val records = load(c)
        records.add(GameSessionNote(
            System.currentTimeMillis(), cleanGame, cleanNote, durationMinutes.coerceIn(0, 480),
            result.trim().replace(Regex("\\s+"), " ").take(80), System.currentTimeMillis()
        ))
        val arr = JSONArray()
        records.takeLast(200).forEach { n ->
            arr.put(JSONObject().put("id", n.id).put("game", n.game).put("note", n.note)
                .put("durationMinutes", n.durationMinutes).put("result", n.result).put("createdAt", n.createdAt))
        }
        val target = file(c)
        val temp = File(c.filesDir, "orion_game_sessions.json.tmp")
        try {
            temp.writeText(arr.toString())
            if (!temp.renameTo(target)) {
                target.writeText(arr.toString())
                temp.delete()
            }
            true
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    fun all(c: Context): List<GameSessionNote> = synchronized(lock) { load(c).sortedByDescending { it.createdAt } }

    fun summaryText(c: Context): String {
        val notes = all(c)
        if (notes.isEmpty()) return "No gaming/hobby session notes yet. Add a short note after a session; it stays on this device."
        val byGame = notes.groupBy { it.game }.entries.sortedByDescending { it.value.size }.take(5)
        val recent = notes.take(8).joinToString("\\n") { n ->
            val mins = if (n.durationMinutes > 0) " · ${n.durationMinutes} min" else ""
            val result = if (n.result.isNotBlank()) " · ${n.result}" else ""
            "• ${n.game}$mins$result — ${n.note.take(140)}"
        }
        return "Stored local session notes: ${notes.size}. Frequent games/hobbies: ${byGame.joinToString { "${it.key} (${it.value.size})" }}. Recent notes:\\n$recent"
    }
}

object GamingFocus {
    private const val ACTION = "com.phoneai.app.GAMING_FOCUS_END"
    private const val REQUEST = 74061
    private const val CHANNEL = "orion_gaming_focus"

    fun status(c: Context): String {
        if (!AgentPolicy.enabled(c, "gaming_hobby")) return "Gaming & hobbies pack is OFF."
        val end = c.getSharedPreferences("phoneai", Context.MODE_PRIVATE).getLong("gaming_focus_end_at", 0L)
        return if (end > System.currentTimeMillis()) {
            val remaining = ((end - System.currentTimeMillis()) / 60000L + 1).coerceAtLeast(1)
            "Focus timer active · about $remaining min left. This is a reminder timer only; no device-wide DND or Game Turbo setting is changed."
        } else "No focus timer active. Presets create a local timer and notification only."
    }

    fun start(c: Context, minutes: Int): String {
        if (!AgentPolicy.enabled(c, "gaming_hobby")) return "error: Gaming & hobbies capability pack is disabled."
        if (minutes !in setOf(20, 45, 60)) return "error: choose one of the supported focus presets: 20, 45 or 60 minutes."
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return "error: notification permission is required for the focus timer alert. Grant it in Android settings first."
        }
        val end = System.currentTimeMillis() + minutes * 60_000L
        c.getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit()
            .putLong("gaming_focus_end_at", end).putInt("gaming_focus_minutes", minutes).apply()
        schedule(c)
        AgentPolicy.log(c, "gaming_focus", "start", true, "$minutes-minute local reminder timer scheduled")
        return "Focus reminder scheduled for $minutes minutes. It will notify you when the block ends; Orion did not change DND, Game Turbo, performance, or app permissions."
    }

    fun cancel(c: Context) {
        val alarm = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pending(c))
        c.getSharedPreferences("phoneai", Context.MODE_PRIVATE).edit()
            .remove("gaming_focus_end_at").remove("gaming_focus_minutes").apply()
        AgentPolicy.log(c, "gaming_focus", "cancel", true, "focus reminder cancelled")
    }

    fun schedule(c: Context) {
        if (!AgentPolicy.enabled(c, "gaming_hobby")) { cancel(c); return }
        val end = c.getSharedPreferences("phoneai", Context.MODE_PRIVATE).getLong("gaming_focus_end_at", 0L)
        if (end <= System.currentTimeMillis()) { cancel(c); return }
        val alarm = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, pending(c))
    }

    internal fun finish(c: Context) {
        val prefs = c.getSharedPreferences("phoneai", Context.MODE_PRIVATE)
        val end = prefs.getLong("gaming_focus_end_at", 0L)
        if (end == 0L || end > System.currentTimeMillis() || !AgentPolicy.enabled(c, "gaming_hobby")) return
        prefs.edit().remove("gaming_focus_end_at").remove("gaming_focus_minutes").apply()
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            AgentPolicy.log(c, "gaming_focus", "finish", false, "timer finished but notification permission missing")
            return
        }
        val manager = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Gaming focus reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(c, REQUEST, Intent(c, GamingCoachActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, CHANNEL) else {
            @Suppress("DEPRECATION")
            android.app.Notification.Builder(c)
        }.setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Orion focus block complete")
            .setContentText("Pause, review your session note, and decide whether to continue.")
            .setContentIntent(open).setAutoCancel(true).build()
        manager.notify(REQUEST, notification)
        AgentPolicy.log(c, "gaming_focus", "finish", true, "focus reminder notification posted")
    }

    private fun pending(c: Context) = PendingIntent.getBroadcast(
        c, REQUEST, Intent(c, GamingFocusReceiver::class.java).setAction(ACTION),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

class GamingFocusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == "com.phoneai.app.GAMING_FOCUS_END") GamingFocus.finish(context)
    }
}

class GamingCoachActivity : Activity() {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val prefs by lazy { getSharedPreferences("phoneai", MODE_PRIVATE) }
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var notes: TextView
    private lateinit var packSwitch: Switch
    private lateinit var gameInput: EditText
    private lateinit var durationInput: EditText
    private lateinit var resultInput: EditText
    private lateinit var noteInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG
        window.navigationBarColor = Theme.BG
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Theme.BG) }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(28)) }
        scroll.addView(root)
        setContentView(scroll)
        addText("ORION / GAMING & HOBBIES", 10f, Theme.MUTED, true, 0)
        addText("Session coach", 24f, Theme.TEXT, true, 5)
        addText("Local notes and reminder timers only. Orion does not change Game Turbo, FPS settings, DND, root permissions, or other apps' settings.", 13f, Theme.MUTED, false, 8)
        addButton("‹ Back to Agent Control") { finish() }
        section("CAPABILITY PACK")
        packSwitch = Switch(this).apply {
            text = "Enable Gaming & Hobbies pack"
            setTextColor(Theme.TEXT)
            isChecked = AgentPolicy.enabled(this@GamingCoachActivity, "gaming_hobby")
            setOnCheckedChangeListener { _, checked ->
                AgentPolicy.setEnabled(this@GamingCoachActivity, "gaming_hobby", checked)
                if (!checked) GamingFocus.cancel(this@GamingCoachActivity)
                refresh()
            }
        }
        root.addView(packSwitch, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        section("SESSION NOTE")
        gameInput = edit("Game or hobby (e.g. FC Mobile)")
        durationInput = edit("Session length in minutes (optional)")
        durationInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        resultInput = edit("Result / goal (optional)")
        noteInput = edit("What happened? What worked? What should change next time?")
        noteInput.minLines = 3
        noteInput.maxLines = 5
        root.addView(gameInput, fieldParams())
        root.addView(durationInput, fieldParams())
        root.addView(resultInput, fieldParams())
        root.addView(noteInput, fieldParams())
        addButton("Save session note") { saveNote() }

        section("LIGHT FOCUS PRESETS")
        addText("These are reminder timers, not performance tuning or system-wide focus controls.", 12f, Theme.MUTED, false, 4)
        addButton("20-minute focus block") { startFocus(20) }
        addButton("45-minute focus block") { startFocus(45) }
        addButton("60-minute focus block") { startFocus(60) }
        addButton("Stop focus timer") { GamingFocus.cancel(this); refresh() }
        section("STATUS")
        status = addText("", 12f, Theme.TEXT, false, 5)
        section("RECENT NOTES")
        notes = addText("", 12f, Theme.TEXT, false, 5)
        refresh()
    }

    override fun onResume() { super.onResume(); if (::status.isInitialized) refresh() }

    private fun startFocus(minutes: Int) {
        if (!AgentPolicy.enabled(this, "gaming_hobby")) {
            toast("Enable the Gaming & Hobbies pack first."); return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            prefs.edit().putInt("gaming_focus_pending_minutes", minutes).apply()
            return
        }
        toast(GamingFocus.start(this, minutes))
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            val minutes = prefs.getInt("gaming_focus_pending_minutes", 0)
            prefs.edit().remove("gaming_focus_pending_minutes").apply()
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED && minutes in setOf(20, 45, 60)) {
                toast(GamingFocus.start(this, minutes))
            } else toast("Notification permission was not granted; no focus timer was started.")
            refresh()
        }
    }

    private fun saveNote() {
        if (!AgentPolicy.enabled(this, "gaming_hobby")) { toast("Enable the Gaming & Hobbies pack first."); return }
        val duration = durationInput.text.toString().trim().toIntOrNull() ?: 0
        if (!GameCoachStore.add(this, gameInput.text.toString(), noteInput.text.toString(), duration, resultInput.text.toString())) {
            toast("Add a game/hobby and a short note first."); return
        }
        AgentPolicy.log(this, "gaming_hobby", "save-session-note", true, "local session note saved")
        noteInput.setText("")
        toast("Session note saved locally.")
        refresh()
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        status.text = "Pack: ${if (AgentPolicy.enabled(this, "gaming_hobby")) "enabled" else "OFF"}\n" + GamingFocus.status(this)
        notes.text = GameCoachStore.summaryText(this)
        val enabled = AgentPolicy.enabled(this, "gaming_hobby")
        gameInput.isEnabled = enabled; durationInput.isEnabled = enabled; resultInput.isEnabled = enabled; noteInput.isEnabled = enabled
    }

    private fun edit(hintText: String) = EditText(this).apply {
        hint = hintText
        setTextColor(Theme.TEXT); setHintTextColor(Theme.MUTED)
        background = GradientDrawable().apply { setColor(0xFF171717.toInt()); cornerRadius = dp(10).toFloat(); setStroke(dp(1), 0xFF3A3A3A.toInt()) }
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }
    private fun fieldParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) }
    private fun addText(text: String, size: Float, color: Int, bold: Boolean, top: Int): TextView {
        val view = TextView(this).apply { this.text = text; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD; setLineSpacing(dp(2).toFloat(), 1f) }
        root.addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
        return view
    }
    private fun section(text: String) { addText(text, 10f, Theme.MUTED, true, 18).letterSpacing = .12f }
    private fun addButton(text: String, action: () -> Unit) {
        val button = Button(this).apply { this.text = text; isAllCaps = false; setTextColor(Theme.TEXT); backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF222222.toInt()); setOnClickListener { action() } }
        root.addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }
    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object { private const val REQUEST_NOTIFICATIONS = 74062 }
}
